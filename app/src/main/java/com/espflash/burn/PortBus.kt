package com.espflash.burn

import android.content.Context
import android.hardware.usb.UsbDevice

/**
 * 全局唯一的串口占用者。
 * 一个 USB 串口物理上只能被一个 open 句柄占用，烧录页和串口助手共用这里分配，
 * 谁先要谁拿，另一个自动让出，避免"另一边连不上"。
 */
object PortBus {

    @Volatile
    private var current: SerialLink? = null

    @Volatile
    var label: String = ""
        private set

    @Volatile
    var baud: Int = 115200

    /** 用户手动点断开后置 true：串口在，但不自动连；重新插拔 USB 或板子复位后清掉 */
    @Volatile
    var manualOff: Boolean = false

    /** 非空表示有独占方（目前只有烧录会设置："flash"） */
    @Volatile
    var busyBy: String? = null
        private set

    val link: SerialLink? get() = current
    val isOpen: Boolean get() = current != null

    private fun idOf(d: UsbDevice): String = "${d.vendorId}:${d.productId}:${runCatching { d.serialNumber }.getOrNull() ?: d.deviceName}"

    @Volatile
    private var currentId: String = ""

    /** 同一个设备重复点连接时直接复用已有句柄，绝不关闭再重开（重开会让读线程炸掉、App 被系统重启） */
    @Synchronized
    fun open(ctx: Context, device: UsbDevice, baudRate: Int, onLog: (String) -> Unit): SerialLink? {
        if (busyBy != null) return null
        val id = idOf(device)
        current?.let { l ->
            if (currentId == id && l.isHealthy()) {
                L.i("串口已连接，复用现有句柄")
                return l
            }
        }
        close()
        val l = SerialLink(ctx, device)
        if (!l.open(onLog)) return null
        l.setBaud(baudRate)
        current = l
        currentId = id
        label = UsbDevices.label(device)
        baud = baudRate
        L.i("串口已打开 $label @ $baudRate")
        return l
    }

    @Synchronized
    fun close() {
        val l = current ?: return
        current = null
        currentId = ""
        label = ""
        runCatching { l.close() }
        L.i("串口已释放")
    }

    /** 烧录开始前调用：先把串口助手占用的句柄关掉，再给 native 层独占 */
    fun beginExclusive(owner: String) {
        close()
        busyBy = owner
    }

    fun endExclusive() {
        busyBy = null
    }
}
