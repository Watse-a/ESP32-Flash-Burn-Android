package com.espflash.burn

/** 烧录进度回调，native 每写入一块调一次 */
interface ProgressSink {
    fun onProgress(pct: Int)
}

/**
 * esp-serial-flasher 的 Kotlin 入口（JNI）。
 * 所有方法都会阻塞，必须在后台线程调用。
 */
object NativeFlasher {
    /** 记录 native 库是否可用，加载失败也不让 App 崩 */
    val available: Boolean = try {
        System.loadLibrary("espflasher")
        true
    } catch (t: Throwable) {
        android.util.Log.e("EspFlasher", "加载 libespflasher.so 失败", t)
        false
    }

    external fun nativeAttach(link: SerialLink)
    external fun nativeConnect(syncTimeoutMs: Int, trials: Int, wantStub: Boolean): Int
    external fun nativeTargetName(): String
    /** 芯片信息："MAC|Flash 字节数"，任一项读不到就留空 */
    external fun nativeChipInfo(): String
    external fun nativeStats(): String
    external fun nativeSetSpeed(rate: Int): Int
    external fun nativeFlashFile(
        offset: Long,
        data: ByteArray,
        blockSize: Int,
        verify: Boolean,
        sink: ProgressSink?
    ): Int

    external fun nativeFlashFileDeflate(
        offset: Long,
        data: ByteArray,
        imageSize: Long,
        blockSize: Int,
        sink: ProgressSink?
    ): Int

    external fun nativeErrorText(code: Int): String

    external fun nativeEraseChip(): Int

    external fun nativeReadFlash(address: Long, length: Long, path: String, sink: ProgressSink?): Int

    const val OK = 0
    /** 芯片回包格式错误（块尺寸等参数被 ROM/stub 拒绝时也会以它返回） */
    const val INVALID_RESPONSE = 8
    /** 超时（表示"这一步还没成功"，用于跳过可省的直接同步） */
    const val TIMEOUT_CODE = 2

    fun errorText(code: Int): String = try {
        nativeErrorText(code)
    } catch (t: Throwable) {
        "错误码 $code"
    }
}
