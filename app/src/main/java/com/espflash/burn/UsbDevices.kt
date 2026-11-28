package com.espflash.burn

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialProber

object UsbDevices {

    fun list(context: Context): List<UsbDevice> {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val prober = UsbSerialProber.getDefaultProber()
        return manager.deviceList.values
            .filter { runCatching { prober.probeDevice(it) != null }.getOrDefault(false) }
            .sortedBy { it.deviceId }
    }

    fun label(device: UsbDevice): String {
        val name = device.productName ?: "USB 串口"
        val vid = String.format("%04X", device.vendorId)
        val pid = String.format("%04X", device.productId)
        // 只留型号和 VID:PID，不显示中文描述
        return "$name · $vid:$pid"
    }

    /**
     * ROM 下载模式枚举出来的设备（如 ESP32-S2 USB-OTG ROM：303A:0002）。
     * 这类设备是给烧录用的，串口日志页不主动弹连接框、也不进设备选择列表。
     */
    fun isRomMode(device: UsbDevice): Boolean =
        device.vendorId == 0x303A && device.productId == 0x0002
}
