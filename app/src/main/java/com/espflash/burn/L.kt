package com.espflash.burn

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 极简日志：只写 logcat + 唯一的 /storage/emulated/0/ESPburn/Bug.txt（软件出错才落盘）。
 * 普通信息只进 logcat，不进文件，保证目录里永远只有 Bug.txt + 导出的串口日志。
 */
object L {
    private const val TAG = "EspFlasher"

    private fun ts(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())

    fun i(msg: String) {
        Log.i(TAG, msg)
    }

    /** 警告/异常才写 Bug.txt */
    fun w(msg: String, t: Throwable? = null) {
        Log.w(TAG, msg, t)
        write("W", msg + if (t != null) "\n" + Log.getStackTraceString(t) else "")
    }

    fun e(msg: String, t: Throwable? = null) {
        Log.e(TAG, msg, t)
        write("E", msg + if (t != null) "\n" + Log.getStackTraceString(t) else "")
    }

    private fun write(level: String, msg: String) {
        val ctx = EspApp.app ?: return
        CrashStore.appendBug("${ts()} $level - $msg\n")
        if (!CrashStore.hasStorageRoot()) {
            // 没给存储权限时留在应用私有目录，同样只有这一个文件
            runCatching {
                val f = java.io.File(ctx.filesDir, "Bug.txt")
                if (f.exists() && f.length() > (1L shl 20)) f.delete()
                f.appendText("${ts()} $level - $msg\n")
            }
        }
    }

    fun logFileText(maxLines: Int = 400): String = CrashStore.bugFileText(maxLines)
}
