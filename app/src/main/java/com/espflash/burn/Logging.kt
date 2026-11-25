package com.espflash.burn

/** 日志入口（保留调用点，实际写由 L 完成：logcat + files/espflasher.log） */
object Logging {
    fun init() {
        L.i("日志系统就绪（内置文件日志，不依赖 logback）")
    }
}
