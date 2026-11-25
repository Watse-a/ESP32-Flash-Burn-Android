package com.espflash.burn

import android.app.Application
import android.os.Build
import android.os.Environment
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局兜底：
 *  1. 崩溃堆栈写 files/last_crash.txt（仅应用内部，文件管理器看不到）
 *  2. 完整报告统一追加到唯一的 /storage/emulated/0/ESPburn/Bug.txt
 */
class EspApp : Application() {

    override fun onCreate() {
        super.onCreate()
        app = this
        runCatching { CrashStore.ensureDirs() }
        installCrashHandler()
        try {
            bootMarker()
        } catch (t: Throwable) {
            Log.e("EspFlasher", "boot marker failed", t)
        }
    }

    /** 启动标记：三个位置都试，哪个能写算哪个 */
    private fun bootMarker() {
        val text = buildString {
            append("ESP burn 启动\n")
            append("时间  ：").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())).append('\n')
            append("版本  ：").append(CrashStore.appVersion(this@EspApp))
                .append(" / ").append(CrashStore.appVersionCode(this@EspApp)).append('\n')
            append("机型  ：").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
            append("系统  ：Android ").append(Build.VERSION.RELEASE).append(" SDK ").append(Build.VERSION.SDK_INT).append('\n')
            append("ABI   ：").append(Build.SUPPORTED_ABIS.joinToString(" / ")).append('\n')
        }
        // 启动信息只进 logcat，不再产生额外文件
        Log.i("EspFlasher", text)
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val report = CrashStore.crashReport(this, thread, error)
                File(filesDir, "last_crash.txt").writeText(
                    "thread=${thread.name}\n\n" + Log.getStackTraceString(error)
                )
                CrashStore.appendBug(report + "\n")
            }
            previous?.uncaughtException(thread, error)
        }
    }

    companion object {
        @Volatile
        var app: EspApp? = null
            private set
    }
}
