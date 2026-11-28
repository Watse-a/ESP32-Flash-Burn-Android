package com.espflash.burn

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.io.IOException
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit



/** 权限申请需要前台 Activity，Dialog 是异步的，这里用 latch 等结果 */
object UsbPerms {
    var activityRef: WeakReference<android.app.Activity>? = null

    fun request(device: UsbDevice, timeoutMs: Int): Boolean {
        val act = activityRef?.get() ?: return false
        val manager = act.getSystemService(Context.USB_SERVICE) as UsbManager
        if (manager.hasPermission(device)) return true

        val latch = CountDownLatch(1)
        var granted = false
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == ACTION_PERM) {
                    granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    latch.countDown()
                }
            }
        }
        val flags = if (Build.VERSION.SDK_INT >= 33) Context.RECEIVER_NOT_EXPORTED else 0
        if (Build.VERSION.SDK_INT >= 33) {
            act.registerReceiver(receiver, IntentFilter(ACTION_PERM), flags)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            act.registerReceiver(receiver, IntentFilter(ACTION_PERM))
        }
        val pi = PendingIntent.getBroadcast(
            act, 0, Intent(ACTION_PERM).setPackage(act.packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        manager.requestPermission(device, pi)
        try {
            latch.await(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            granted = false
        }
        try {
            act.unregisterReceiver(receiver)
        } catch (ignore: IllegalArgumentException) {
        }
        return granted
    }

    private const val ACTION_PERM = "com.espflash.burn.USB_PERMISSION"
}

/**
 * 一路 USB 串口。native 层通过它读写字节、控制 DTR/RTS。
 * 原生 USB（ESP32-C3/S3 内置 USB-Serial-JTAG）复位后会重新枚举，reopenAfterReset 负责重开。
 */
class SerialLink(
    private val context: Context,
    initial: UsbDevice
) {
    private val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var device: UsbDevice = initial
    private var port: UsbSerialPort? = null
    private var connection: android.hardware.usb.UsbDeviceConnection? = null
    private var baudRate = 115200

    val deviceName: String get() = "${device.productName ?: "USB 串口"} (${device.vendorId.toString(16)}:${device.productId.toString(16)})"

    /** 0x303A 是乐鑫原生 USB，PID 0x1001 为 USB-Serial-JTAG */
    fun isUsbJtag(): Boolean = device.vendorId == 0x303A

    /** 句柄还活着：端口已打开、连接对象还在 */
    fun isHealthy(): Boolean = port != null && connection != null

    // ---- 原生 CDC 通道：直接 bulkTransfer 收发，绕开 usb-serial-for-android ----
    private var rawConn: android.hardware.usb.UsbDeviceConnection? = null
    private var dtrState = false
    private var rtsState = false
    private var rawIn: android.hardware.usb.UsbEndpoint? = null
    private var rawOut: android.hardware.usb.UsbEndpoint? = null

    val usingRaw: Boolean get() = rawConn != null
    private var preferRaw = false

    var rawInfo: String = ""
        private set
    var lastRawError: String = ""
        private set
    private var rawItfId = 0

    /** 本次打开成功 claim 下来的接口号，关闭时全部释放，避免残留占用导致下一次打不开 */
    private val claimedItfs = mutableListOf<Int>()

    fun openRaw(): Boolean {
        val conn = manager.openDevice(device) ?: return false
        val sb = StringBuilder("接口拓扑：")
        for (i in 0 until device.interfaceCount) {
            val it = device.getInterface(i)
            sb.append(" #%d(cls=%d,sub=%d,proto=%d,ep=%d)".format(i, it.interfaceClass, it.interfaceSubclass, it.interfaceProtocol, it.endpointCount))
        }
        rawInfo = sb.toString()
        var itf: android.hardware.usb.UsbInterface? = null
        var epIn: android.hardware.usb.UsbEndpoint? = null
        var epOut: android.hardware.usb.UsbEndpoint? = null
        for (i in 0 until device.interfaceCount) {
            val it = device.getInterface(i)
            var foundIn: android.hardware.usb.UsbEndpoint? = null
            var foundOut: android.hardware.usb.UsbEndpoint? = null
            for (e in 0 until it.endpointCount) {
                val ep = it.getEndpoint(e)
                if (ep.type == android.hardware.usb.UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.direction == android.hardware.usb.UsbConstants.USB_DIR_IN && foundIn == null) foundIn = ep
                    if (ep.direction == android.hardware.usb.UsbConstants.USB_DIR_OUT && foundOut == null) foundOut = ep
                }
            }
            if (foundIn != null && foundOut != null) {
                itf = it; epIn = foundIn; epOut = foundOut
                rawInfo += " ；选中 #%d 端点 in=0x%02X out=0x%02X".format(i, foundIn.address, foundOut.address)
                break
            }
        }
        if (itf == null || epIn == null || epOut == null) { conn.close(); return false }
        if (!conn.claimInterface(itf, true)) { conn.close(); return false }
        claimedItfs.add(itf.id)
        // 顺手把 CDC 控制接口也占住，保证 SET_CONTROL_LINE_STATE 确实作用到这个设备
        runCatching {
            for (k in 0 until device.interfaceCount) {
                val it2 = device.getInterface(k)
                if (it2.interfaceClass == 2) {
                    if (conn.claimInterface(it2, true)) claimedItfs.add(it2.id)
                    break
                }
            }
        }
        // CDC 要显式选 alternate setting 0，否则端点可能不可用
        rawItfId = itf.id
        runCatching { conn.controlTransfer(0x01, 0x0B, 0, itf.id, null, 0, 300) }
        clearHaltEp(conn, epOut!!.address)
        clearHaltEp(conn, epIn!!.address)
        rawConn = conn
        connection = conn
        rawIn = epIn
        rawOut = epOut
        preferRaw = true
        setLineCoding(baudRate)
        // 打开时不要拉任何控制线：DTR/RTS 在这块原生 USB 上可能直连 EN/IO0，拉低会一直把芯片按住
        setControlLines(false, false)
        return true
    }

    /** CLEAR_FEATURE(ENDPOINT_HALT)，等价于 clearHalt，兼容各版本 SDK */
    private fun clearHaltEp(conn: android.hardware.usb.UsbDeviceConnection, epAddr: Int) {
        runCatching { conn.controlTransfer(0x02, 0x01, 0, epAddr, null, 0, 200) }
    }

    private fun setLineCoding(rate: Int) {
        val c = rawConn ?: return
        val buf = ByteArray(7)
        // dwDTERate 小端
        buf[0] = (rate and 0xFF).toByte()
        buf[1] = ((rate shr 8) and 0xFF).toByte()
        buf[2] = ((rate shr 16) and 0xFF).toByte()
        buf[3] = ((rate shr 24) and 0xFF).toByte()
        buf[4] = 0 // 1 stop bit
        buf[5] = 0 // no parity
        buf[6] = 8 // 8 data bits
        runCatching { c.controlTransfer(0x21, 0x20, 0, 0, buf, 7, 200) }
    }

    var lastLineStateResult: Int = -99
        private set

    /** 指定 interface index 下发 SET_CONTROL_LINE_STATE（索引不同可能被芯片忽略） */
    fun setControlLinesIdx(dtr: Boolean, rts: Boolean, idx: Int): Int {
        val c = (rawConn ?: connection) ?: return -1
        var v = 0
        if (dtr) v = v or 1
        if (rts) v = v or 2
        return runCatching { c.controlTransfer(0x21, 0x22, v, idx, null, 0, 200) }.getOrDefault(-1)
    }

    fun setControlLines(dtr: Boolean, rts: Boolean) {
        val c = rawConn ?: return
        var v = 0
        if (dtr) v = v or 1
        if (rts) v = v or 2
        lastLineStateResult = runCatching { c.controlTransfer(0x21, 0x22, v, 0, null, 0, 200) }
            .getOrDefault(-1)
    }

    fun open(logSink: (String) -> Unit = {}): Boolean {
        // 303A（USB-Serial-JTAG）先走原生 CDC bulk：驱动层的 1 字节读会返回 0，
        // ROM 的 SLIP 应答就是被它吞掉的。raw 通了就不再走驱动。
        if (isUsbJtag()) {
            if (!manager.hasPermission(device) && !UsbPerms.request(device, 8000)) {
                logSink("USB 权限被拒绝")
                return false
            }
            if (openRaw()) {
                logSink("串口已打开（原生 CDC bulk）：$deviceName @ $baudRate")
                return true
            }
            logSink("原生 CDC 通道打不开，退回串口驱动")
        }
        val driver = UsbSerialProber.getDefaultProber().probeDevice(device)
        if (driver == null) {
            logSink("没有匹配的串口驱动（原生 USB 需 0x303A，CH340 需 0x1A86）")
            return false
        }
        if (!manager.hasPermission(device) && !UsbPerms.request(device, 8000)) {
            logSink("USB 权限被拒绝")
            return false
        }
        val conn = manager.openDevice(device)
        connection = conn
        if (conn == null) {
            logSink("openDevice 失败（可能被占用）")
            return false
        }
        return try {
            val p = driver.ports[0]
            p.open(conn)
            p.setParameters(baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            try {
                p.setDTR(false)
                p.setRTS(false)
            } catch (ignore: Exception) {
            }
            port = p
            logSink("串口已打开：$deviceName @ $baudRate")
            true
        } catch (e: IOException) {
            logSink("打开串口失败：${e.message}")
            false
        }
    }

    fun close() {
        rawConn?.let { c ->
            // 释放本次 claim 过的全部接口（数据接口 + CDC 控制接口），逐个兜底再按接口 0 放一次
            claimedItfs.distinct().forEach { id ->
                runCatching {
                    for (k in 0 until device.interfaceCount) {
                        val itf = device.getInterface(k)
                        if (itf.id == id) { c.releaseInterface(itf); break }
                    }
                }
            }
            runCatching { c.releaseInterface(device.getInterface(0)) }
            runCatching { c.close() }
        }
        claimedItfs.clear()
        rawConn = null
        rawIn = null
        rawOut = null
        try {
            port?.close()
        } catch (ignore: Exception) {
        }
        port = null
        connection = null
    }

    /** native -> Java：读满 len 字节，超时返回已读数量 */
    // 原生 bulk IN 接收缓冲：这个设备的端点在"只请求 1 字节"时会直接返回 0，
    // 请求 32/64 字节却能拿到数据（ROM 的 SLIP 应答就是这样被读丢的）。
    // 所以底层永远按大块读，再按调用方要的 len 切片发出去。
    private val rxBuf = ByteArray(4096)
    private var rxPos = 0
    private var rxLen = 0

    fun readFully(dst: ByteArray, len: Int, timeoutMs: Int): Int {
        if (rawConn != null) {
            if (dst.size < len) return -1
            if (rxPos >= rxLen) {
                rxPos = 0
                rxLen = 0
                val n = try {
                    rawConn!!.bulkTransfer(rawIn, rxBuf, rxBuf.size, timeoutMs.coerceAtLeast(1))
                } catch (e: Exception) {
                    lastRawError = "bulk 读异常：${e.javaClass.simpleName} ${e.message}"
                    -1
                }
                if (n <= 0) return -1
                rxLen = n
            }
            val take = minOf(len, rxLen - rxPos)
            System.arraycopy(rxBuf, rxPos, dst, 0, take)
            rxPos += take
            return take
        }
        val p = port ?: return -1
        // 驱动层同样按大块请求，再从缓冲里切片：小请求（尤其 1 字节）在这块设备上拿不到数据
        val deadline = System.currentTimeMillis() + timeoutMs
        var got = 0
        while (got < len) {
            if (rxPos >= rxLen) {
                rxPos = 0
                rxLen = 0
                val remain = deadline - System.currentTimeMillis()
                if (remain <= 0) break
                try {
                    val n = p.read(rxBuf, rxBuf.size, remain.toInt().coerceAtLeast(1))
                    if (n <= 0) break
                    rxLen = n
                } catch (e: IOException) {
                    return if (got > 0) got else -1
                }
            }
            val take = minOf(len - got, rxLen - rxPos)
            System.arraycopy(rxBuf, rxPos, dst, got, take)
            rxPos += take
            got += take
        }
        return if (got > 0) got else -1
    }

    /** 串口助手用：读到多少返回多少；0 表示这一轮没数据，-1 才是真断开 */
    fun readAny(dst: ByteArray, len: Int, timeoutMs: Int): Int {
        if (rawConn != null) {
            if (rxPos < rxLen) {
                val take = minOf(len, rxLen - rxPos)
                System.arraycopy(rxBuf, rxPos, dst, 0, take)
                rxPos += take
                return take
            }
            val n = runCatching {
                rawConn!!.bulkTransfer(rawIn, rxBuf, minOf(rxBuf.size, maxOf(len, 1024)), timeoutMs.coerceAtLeast(1))
            }.getOrDefault(0)
            if (n <= 0) {
                // 超时只是没数据，别让串口页误判成掉线；设备真没了才返回 -1
                val alive = runCatching { sameDeviceAlive() }.getOrDefault(true)
                return if (alive) 0 else -1
            }
            rxLen = n
            rxPos = 0
            val take = minOf(len, rxLen)
            System.arraycopy(rxBuf, 0, dst, 0, take)
            rxPos = take
            return take
        }
        val p = port ?: return -1
        return try {
            p.read(dst, len, timeoutMs)
        } catch (e: IOException) {
            -1
        }
    }

    /** native -> Java：写出 len 字节 */
    fun writeAll(src: ByteArray, len: Int, timeoutMs: Int): Boolean {
        if (rawConn != null) {
            lastRawError = ""
            for (attempt in 1..3) {
                val n = try {
                    rawConn!!.bulkTransfer(rawOut, src, len, timeoutMs.coerceAtLeast(1))
                } catch (e: Exception) {
                    lastRawError += "#$attempt 异常 ${e.javaClass.simpleName}:${e.message}; "
                    -1
                }
                if (n > 0) return true
                lastRawError += "#$attempt 返回 $n; "
                rawConn?.let { clearHaltEp(it, rawOut!!.address) }
                runCatching { rawConn!!.controlTransfer(0x01, 0x0B, 0, rawItfId, null, 0, 200) }
                Thread.sleep(40)
            }
            return false
        }
        val p = port ?: return false
        return try {
            p.write(src, len, timeoutMs.coerceAtLeast(1))
            true
        } catch (e: IOException) {
            lastRawError = "写失败：${e.message}"
            runCatching { screenSink?.invoke("[通道] $lastRawError") }
            false
        }
    }

    /**
     * 直接用 CDC SET_CONTROL_LINE_STATE 写 DTR/RTS：
     * 有些设备 usb-serial-for-android 的 setDTR/setRTS 会抛异常被吞掉，这里兜底。
     */
    fun setLineStateDirect(dtr: Boolean, rts: Boolean): Boolean {
        val c = connection ?: return false
        var value = 0
        if (dtr) value = value or 0x01
        if (rts) value = value or 0x02
        return runCatching {
            c.controlTransfer(0x21, 0x22, value, 0, null, 0, 200) >= 0
        }.getOrDefault(false)
    }

    /** 复位后设备是否真的掉了（重新枚举）：等到它从列表里消失返回 true */
    fun waitDetach(timeoutMs: Int): Boolean {
        val vid = device.vendorId
        val pid = device.productId
        val key = runCatching { device.deviceName }.getOrDefault("")
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val still = manager.deviceList.values.any {
                it.vendorId == vid && it.productId == pid && runCatching { it.deviceName }.getOrDefault("") == key
            }
            if (!still) return true
            try { Thread.sleep(40) } catch (e: InterruptedException) { return false }
        }
        return false
    }

    fun setDtr(value: Boolean) {
        dtrState = value
        setControlLinesIdx(dtrState, rtsState, 0)
    }

    fun setRts(value: Boolean) {
        rtsState = value
        setControlLinesIdx(dtrState, rtsState, 0)
    }

    fun setBaud(rate: Int): Boolean {
        if (rawConn != null) {
            baudRate = rate
            setLineCoding(rate)
            return true
        }
        val p = port ?: return false
        return try {
            p.setParameters(rate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            baudRate = rate
            true
        } catch (e: IOException) {
            false
        }
    }

    fun purgeInput() {
        if (rawConn != null) {
            rxPos = 0
            rxLen = 0
            val junk = ByteArray(256)
            repeat(4) {
                val n = runCatching { rawConn!!.bulkTransfer(rawIn, junk, junk.size, 20) }.getOrDefault(-1)
                if (n <= 0) return
            }
            return
        }
        rxPos = 0
        rxLen = 0
        try {
            port?.purgeHwBuffers(true, true)
        } catch (ignore: Exception) {
        }
    }

    /** native 层（含握手收发）打的日志：除了写文件，也送到界面上，方便导出定位 */
    var screenSink: ((String) -> Unit)? = null

    fun logLine(msg: String) {
        L.i(msg)
        runCatching { screenSink?.invoke(msg) }
    }

    /** 复位后设备可能重新枚举：等它回来并重新打开 */
    /** 复位后设备是否还在（同一个 deviceId），在的话就不用重开端口 */
    fun sameDeviceAlive(): Boolean = runCatching {
        manager.deviceList.values.any { it.deviceId == device.deviceId && it.vendorId == device.vendorId }
    }.getOrDefault(false)

    fun reopenAfterReset(timeoutMs: Int): Boolean {
        val vid = device.vendorId
        val pid = device.productId
        val serial = try {
            device.serialNumber
        } catch (t: Throwable) {
            null
        }
        close()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val candidate = manager.deviceList.values.firstOrNull {
                it.vendorId == vid && it.productId == pid && (serial == null || it.serialNumber == serial)
            }
            if (candidate != null) {
                device = candidate
                if (manager.hasPermission(candidate) || UsbPerms.request(candidate, 5000)) {
                    if (preferRaw && openRaw()) return true
                    if (open()) return true
                }
            }
            try {
                Thread.sleep(120)
            } catch (e: InterruptedException) {
                return false
            }
        }
        L.w("复位后没有等到设备回来")
        return false
    }
}
