package com.espflash.burn

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


/**
 * 把崩溃报告 / 日志写到用户能用文件管理器看到的地方：
 *   Android 10+ 走 MediaStore 写 Download/ESPburn/（免任何权限）
 *   Android 9-  直接写 /sdcard/Download/ESPburn/
 * 同时始终在 filesDir 里留一份，双保险。
 */
object CrashStore {

    const val SUB_DIR = "ESP-FlashBurn"

    /** 对外可见路径的提示文本 */
    const val PUBLIC_HINT = "/storage/emulated/0/Download/ESP-FlashBurn/"

    fun stamp(): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    /** 保存文本，返回公共可见路径（失败返回 null） */
    fun save(ctx: Context, name: String, text: String): String? {
        runCatching { File(ctx.filesDir, name).writeText(text) }
        return runCatching { writePublic(ctx, name, text, SUB_DIR) }.getOrNull()
    }

    /** 新位置：Android/media 下（文件管理器可见，不污染根目录） */
    const val ROOT_DIR = "/storage/emulated/0/Android/media/ESP-FlashBurn"
    /** 旧位置（根目录），启动时整体搬过来 */
    private const val LEGACY_ROOT = "/storage/emulated/0/ESPburn"
    /** 软件出错日志：全应用只留这一个文件 */
    const val BUG_FILE = "$ROOT_DIR/Bug.txt"
    /** 串口日志导出也直接落在 ESPburn 根目录，不再分子目录 */
    const val DIR_RUNTIME = ROOT_DIR
    const val DIR_SERIAL = ROOT_DIR

    /** 建好三个目录（没授权就跳过） */
    fun ensureDirs(): Boolean {
        if (!hasStorageRoot()) return false
        return runCatching {
            migrateLegacyRoot()
            // 只允许一个日志文件：旧的分类子目录与散落文件全清掉
            listOf("1运行日志", "2串口日志", "一运行日志", "三串口日志", "二烧录日志", "2烧录日志")
                .forEach { deleteDir(File(ROOT_DIR, it)) }
            if (!File(ROOT_DIR).exists()) File(ROOT_DIR).mkdirs()
            runCatching {
                File(ROOT_DIR).listFiles()?.forEach { f ->
                    if (!f.isFile) return@forEach
                    val n = f.name
                    val isBug = n == "Bug.txt"
                    val isSerial = n.startsWith("串口日志-")
                    if (!isBug && !isSerial) f.delete()
                }
            }
            true
        }.getOrDefault(false)
    }

    /** 把根目录下的旧 ESPburn 文件夹整体搬到 Android/media/ESPburn */
    private fun migrateLegacyRoot() {
        runCatching {
            val old = File(LEGACY_ROOT)
            if (!old.exists() || !old.isDirectory) return@runCatching
            val newDir = File(ROOT_DIR)
            if (!newDir.exists()) newDir.mkdirs()
            old.listFiles()?.forEach { f ->
                val dst = File(newDir, f.name)
                if (f.isFile && !dst.exists()) f.copyTo(dst, overwrite = false)
            }
            deleteDir(old)
        }
    }

    private fun deleteDir(dir: File) {
        runCatching {
            if (!dir.exists()) return@runCatching
            dir.listFiles()?.forEach { if (it.isDirectory) deleteDir(it) else it.delete() }
            dir.delete()
        }
    }

    /** 每个分类目录最多保留 10 个文件，超了删最旧的 */
    fun prune(dir: String, keep: Int = 10) {
        runCatching {
            val files = File(dir).listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }
                ?: return
            files.drop(keep).forEach { it.delete() }
        }
    }

    /** 追加写进唯一的 Bug 日志；超过 1MB 时只留最后一段，保证始终一个文件 */
    @Synchronized
    fun appendBug(text: String): String? {
        if (!hasStorageRoot()) return null
        return runCatching {
            val f = File(BUG_FILE)
            if (!f.exists()) File(ROOT_DIR).mkdirs()
            if (f.exists() && f.length() > (1L shl 20)) {
                val lines = f.readLines()
                f.writeText(lines.takeLast(2000).joinToString("\n") + "\n")
            }
            f.appendText(text)
            BUG_FILE
        }.getOrNull()
    }

    fun bugFileText(maxLines: Int = 400): String {
        val f = File(BUG_FILE)
        if (!f.exists()) return "(无 Bug.txt)"
        return runCatching { f.readLines().takeLast(maxLines).joinToString("\n") }
            .getOrElse { "(读取失败：${it.message})" }
    }

    /** 写到指定分类目录，返回绝对路径 */
    fun saveToDir(dir: String, name: String, text: String): String? {
        if (!hasStorageRoot()) return null
        return runCatching {
            val d = File(dir)
            if (!d.exists()) d.mkdirs()
            val f = File(d, name)
            f.writeText(text)
            prune(dir)
            f.absolutePath
        }.getOrNull()
    }

    /** 是否拿到「所有文件访问」，没有就只能退回 Download */
    fun hasStorageRoot(): Boolean =
        Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()

    /** 导出到内部存储根目录下的 ESPburn 文件夹 */
    fun saveToStorageRoot(ctx: Context, name: String, text: String): String? {
        if (!hasStorageRoot()) return null
        return runCatching {
            val dir = File(ROOT_DIR)
            if (!dir.exists()) dir.mkdirs()
            val f = File(dir, name)
            f.writeText(text)
            f.absolutePath
        }.getOrNull()
    }

    /** 导出用户手动要的日志：直接落在 Download 根目录，方便在文件管理里找 */
    fun saveToDownload(ctx: Context, name: String, text: String): String? {
        runCatching { File(ctx.filesDir, name).writeText(text) }
        return runCatching { writePublic(ctx, name, text, null) }.getOrNull()
    }

    private fun writePublic(ctx: Context, name: String, text: String, subDir: String?): String {
        if (Build.VERSION.SDK_INT >= 29) {
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    if (subDir == null) Environment.DIRECTORY_DOWNLOADS
                    else Environment.DIRECTORY_DOWNLOADS + File.separator + subDir
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val res = ctx.contentResolver
            val uri = res.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                ?: throw IllegalStateException("MediaStore 拒绝写入")
            res.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                ?: throw IllegalStateException("拿不到输出流")
            cv.clear()
            cv.put(MediaStore.MediaColumns.IS_PENDING, 0)
            res.update(uri, cv, null, null)
            return if (subDir == null) "/storage/emulated/0/Download/$name" else "$PUBLIC_HINT$name"
        } else {
            val dir = if (subDir == null) {
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            } else {
                File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    subDir
                )
            }
            if (!dir.exists()) dir.mkdirs()
            val f = File(dir, name)
            f.writeText(text)
            return f.absolutePath
        }
    }

    /** 崩溃现场：异常 + 设备 + 启动脚印 + 最近日志 */
    fun crashReport(ctx: Context, thread: Thread?, t: Throwable): String {
        val sb = StringBuilder()
        sb.append("==== ESP burn 崩溃报告 ====\n")
        sb.append("时间    ：").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())).append('\n')
        sb.append("版本    ：").append(appVersion(ctx)).append("（versionCode ").append(appVersionCode(ctx)).append("）\n")
        sb.append("机型    ：").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
        sb.append("系统    ：Android ").append(Build.VERSION.RELEASE)
            .append("  SDK ").append(Build.VERSION.SDK_INT).append('\n')
        sb.append("ABI     ：").append(Build.SUPPORTED_ABIS.joinToString(" / ")).append('\n')
        sb.append("线程    ：").append(thread?.name ?: "?").append('\n')
        sb.append("异常    ：").append(t.javaClass.name).append(": ").append(t.message ?: "").append("\n\n")
        sb.append("==== 堆栈 ====\n").append(Log.getStackTraceString(t)).append('\n')
        sb.append("\n==== 启动脚印 ====\n").append(readTextOrEmpty(ctx, "trail.txt"))
        sb.append("\n==== 最近日志（espflasher.log 尾部）====\n").append(tailLog(ctx, 300))
        return sb.toString()
    }

    /** 启动脚印，每次启动都重开一份，出问题知道走到哪一步 */
    fun trailReset(ctx: Context) {
        runCatching { File(ctx.filesDir, "trail.txt").writeText("boot ${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())}\n") }
    }

    fun trail(ctx: Context, mark: String) {
        runCatching { Log.i("EspFlasher", mark) }
        runCatching {
            File(ctx.filesDir, "trail.txt").appendText(
                SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date()) + "  " + mark + "\n"
            )
        }
    }

    fun readTextOrEmpty(ctx: Context, name: String): String =
        runCatching { File(ctx.filesDir, name).readText() }.getOrDefault("(无)")

    fun tailLog(ctx: Context, lines: Int): String {
        val f = File(ctx.filesDir, "espflasher.log")
        if (!f.exists()) return "(无 espflasher.log)"
        return runCatching {
            val all = f.readLines()
            all.takeLast(lines).joinToString("\n")
        }.getOrElse { "(读取失败：${it.message})" }
    }

    fun appVersion(ctx: Context): String = runCatching {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    }.getOrDefault("?")

    fun appVersionCode(ctx: Context): Long = runCatching {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    }.getOrDefault(0L)

}
