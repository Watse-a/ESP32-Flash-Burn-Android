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
}
