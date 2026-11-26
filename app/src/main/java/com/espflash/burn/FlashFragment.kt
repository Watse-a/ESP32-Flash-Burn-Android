package com.espflash.burn

import android.hardware.usb.UsbDevice
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.app.AlertDialog
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume



class FlashFragment : Fragment(R.layout.fragment_flash) {

    private data class PendingFile(
        val name: String,
        val size: Long,
        var offset: Long?,
        val data: ByteArray,
        val chip: String?,
        val idf: String?,
        val ver: String?,
        var picked: Boolean = true
    )

    private lateinit var deviceLabel: TextView
    private lateinit var logScroll: android.widget.ScrollView
    private var selectedIndex = -1
    private lateinit var fileList: LinearLayout
    private var summaryCard: android.view.View? = null
    private lateinit var startButton: View
    private lateinit var progressBar: ProgressBar
    private lateinit var progressWrap: View
    private lateinit var progressLabel: TextView
    private lateinit var progressPct: TextView
    private lateinit var progressIndet: MarqueeBar
    private lateinit var fileSummary: TextView
    private lateinit var infoPanel: LinearLayout
    private var compactViews: List<android.view.View>? = null
    private lateinit var logText: TextView
    private lateinit var logLive: TextView
    private var stickLogBottom = true
    private lateinit var paramLine: TextView
    private lateinit var cbAutoReset: android.widget.Switch
    private lateinit var cbHighSpeed: android.widget.Switch

    private val files = mutableListOf<PendingFile>()
    private val rowInputs = mutableListOf<Pair<String, EditText>>()
    private val devices = mutableListOf<UsbDevice>()
    private var job: Job? = null

    private val createDoc = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri == null) return@registerForActivityResult
        val ok = runCatching {
            requireContext().contentResolver.openOutputStream(uri)?.use { out ->
                out.write(collectLog().toByteArray(Charsets.UTF_8))
            } ?: throw IllegalStateException("无法写入目标文件")
        }.isSuccess
        toast(if (ok) "日志已保存" else "保存失败")
    }

    /** 空列表时点「管理固件 / 刷写固件」：先直接弹文件选择，选完再打开固件管理页 */
    private var openLibAfterPick = false
    private val firmwareMime = arrayOf(
        "application/octet-stream",
        "application/x-binary",
        "application/zip",
        "application/x-elf"
    )

    private fun pickFirmware() {
        picker.launch(firmwareMime)
    }

    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNullOrEmpty()) {
            openLibAfterPick = false
            return@registerForActivityResult
        }
        // 选固件就当作新一轮烧录：清掉上一次的烧录日志、缓存，并把页面展开回「已选固件」
        setCompact(false)
        clearInfoRows()
        visLog.setLength(0)
        rawLog.setLength(0)
        clearLiveLine()
        logText.setText("")
        logScroll.scrollTo(0, 0)
        fileSummary.text = ""
        uris.forEach { uri -> runCatching { addFile(uri) }.onFailure { appendLog("读取失败：${it.message}") } }
        refreshFileList()
        val wantLib = openLibAfterPick
        openLibAfterPick = false
        if (libRebuild != null) {
            libRebuild?.invoke()
        } else if (wantLib && files.isNotEmpty()) {
            manageFiles()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        val v = inflater.inflate(R.layout.fragment_flash, container, false)
        deviceLabel = v.findViewById(R.id.deviceLabel)
        logScroll = v.findViewById(R.id.logScroll)
        fileList = v.findViewById(R.id.fileList)
        summaryCard = null
        startButton = v.findViewById(R.id.startButton)
        progressBar = v.findViewById(R.id.progressBar)
        progressWrap = v.findViewById(R.id.progressWrap)
        progressLabel = v.findViewById(R.id.progressLabel)
        progressPct = v.findViewById(R.id.progressPct)
        progressIndet = v.findViewById(R.id.progressIndet)
        fileSummary = v.findViewById(R.id.fileSummary)
        infoPanel = v.findViewById<LinearLayout>(R.id.infoPanel).apply { visibility = android.view.View.GONE }
        // 烧录时收起的是日志区顶部那块「文件名 + 彩色标签」，给日志让位
        compactViews = listOfNotNull(
            v.findViewById<android.view.View>(R.id.fileSummary),
            v.findViewById<android.view.View>(R.id.fileList)
        )
        logText = v.findViewById(R.id.logText)
        logLive = v.findViewById(R.id.logLive)
        paramLine = v.findViewById(R.id.paramLine)
        cbAutoReset = v.findViewById(R.id.cbAutoReset)
        cbHighSpeed = v.findViewById(R.id.cbHighSpeed)
        bindLogScroll()
        return v
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<ImageButton>(R.id.refreshDevices).setOnClickListener { refreshDevices() }
        view.findViewById<android.view.View>(R.id.deviceCard).setOnClickListener { pickDevice() }
        startButton.setOnClickListener { startFlash() }
        // 点固件卡片/参数行不再弹烧录参数，参数只在右上角 ⋮ 菜单里改
        view.findViewById<View>(R.id.btnErase).setOnClickListener { eraseChip() }
        view.findViewById<View>(R.id.btnManage).setOnClickListener {
            // 列表空的时候不先弹固件库，直接弹文件选择；选完再进管理页
            if (files.isEmpty()) {
                openLibAfterPick = true
                pickFirmware()
            } else {
                manageFiles()
            }
        }
        cbAutoReset.isChecked = prefs0().getBoolean("autoReset", true)
        cbHighSpeed.isChecked = prefs0().getBoolean("highSpeed", true)
        cbAutoReset.setOnCheckedChangeListener { _, v -> prefs0().edit().putBoolean("autoReset", v).apply() }
        cbHighSpeed.setOnCheckedChangeListener { _, v -> prefs0().edit().putBoolean("highSpeed", v).apply() }
        updateParamLine()
        view.findViewById<ImageButton>(R.id.moreButton).setOnClickListener { anchor ->
            MoreMenu.show(
                anchor,
                listOf(
                    MoreMenu.Item(R.drawable.ic_tune, "设置烧录参数") { showFlashParams() },
                    MoreMenu.Item(R.drawable.ic_reset, "设备复位重启") { resetDevice() },
                    MoreMenu.Item(R.drawable.ic_flash, "设备型号读取") { chipInfo() },
                    MoreMenu.Item(R.drawable.ic_share, "读取当前固件") { readFirmware() },
                    MoreMenu.Item(R.drawable.ic_copy, "复制烧录日志") { copyFlashLog() },
                )
            )
        }
        refreshDevices()
    }

    /** 设置烧录参数：型号 / 波特率 / 频率 / 模式 / 大小，点字段弹出列表选择 */
    private fun showFlashParams() {
        val ctx = requireContext()
        val prefs = ctx.getSharedPreferences("espburn", android.content.Context.MODE_PRIVATE)
        val d = android.app.Dialog(ctx)
        val v = android.view.LayoutInflater.from(ctx).inflate(R.layout.dialog_flash_params, null)

        val chips = listOf("auto", "ESP32", "ESP32-S3", "ESP32-C3", "ESP32-C6", "ESP32-S2", "ESP32-H2", "ESP32-C2", "ESP8266")
        val bauds = listOf("115200", "230400", "460800", "921600", "1500000", "2000000")
        val freqs = listOf("keep", "80m", "40m", "26m", "20m", "16m", "12m")
        val modes = listOf("dio", "qio", "dout", "qout")
        val sizes = listOf("keep", "1MB", "2MB", "4MB", "8MB", "16MB", "32MB")
        val blocks = listOf("16k", "8k", "4k", "1k")

        val fields = listOf(
            Triple(R.id.pChip, "选择芯片型号", chips) to "pChip",
            Triple(R.id.pBaud, "选择波特率", bauds) to "flashBaud",
            Triple(R.id.pFreq, "选择闪存频率", freqs) to "pFreq",
            Triple(R.id.pMode, "选择闪存模式", modes) to "pMode",
            Triple(R.id.pSize, "选择闪存大小", sizes) to "pSize",
            Triple(R.id.pBlock, "选择加速块大小", blocks) to "blockKb"
        )
        val defaults = mapOf("pChip" to "auto", "flashBaud" to "115200", "pFreq" to "keep",
            "pMode" to "dio", "pSize" to "keep", "blockKb" to "16k")

        fields.forEach { pair ->
            val (spec, key) = pair
            val (id, title, items) = spec
            val view = v.findViewById<TextView>(id)
            view.text = prefs.getString(key, defaults[key]) ?: defaults[key]!!
            view.setOnClickListener {
                DevicePicker.show(ctx, title, items) { which ->
                    view.text = items[which]
                    prefs.edit().putString(key, items[which]).apply()
                    if (key == "flashBaud") PortBus.baud = items[which].toIntOrNull() ?: 115200
                }
            }
        }

        v.findViewById<TextView>(R.id.pOk).setOnClickListener {
            val chip = v.findViewById<TextView>(R.id.pChip).text.toString()
            val baud = v.findViewById<TextView>(R.id.pBaud).text.toString()
            val freq = v.findViewById<TextView>(R.id.pFreq).text.toString()
            val mode = v.findViewById<TextView>(R.id.pMode).text.toString()
            val size = v.findViewById<TextView>(R.id.pSize).text.toString()
            val block = v.findViewById<TextView>(R.id.pBlock).text.toString()
            prefs.edit()
                .putString("pChip", chip).putString("flashBaud", baud)
                .putString("pFreq", freq).putString("pMode", mode).putString("pSize", size)
                .putString("blockKb", block)
                .apply()
            PortBus.baud = baud.toIntOrNull() ?: 115200
            d.dismiss()
            updateParamLine()
            updateParamLine()
        }

        d.setContentView(v)
        d.window?.let { w ->
            w.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            w.setGravity(android.view.Gravity.CENTER)
            val m = ctx.resources.displayMetrics
            w.setLayout((m.widthPixels - (56 * m.density).toInt()), android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
            w.setDimAmount(0.6f)
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        d.show()
    }

    /** 菜单「读取当前固件」：按地址和大小把设备里的 flash 读出来存成 bin */
    private fun readFirmware() {
        val device = selectedDevice() ?: run { toast("先选设备"); return }
        if (PortBus.busyBy != null) { toast("正在烧录，稍后再说"); return }
        val ctx = requireContext()
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val addr = EditText(ctx).apply {
            hint = "起始地址，如 0x0"
            setText("0x0")
            setTextColor(0xFFE9E6F4.toInt())
            setBackgroundResource(R.drawable.bg_field)
            setPadding(dp(14), 0, dp(10), 0)
        }
        val size = EditText(ctx).apply {
            hint = "大小，如 4m / 0x400000"
            setText("4m")
            setTextColor(0xFFE9E6F4.toInt())
            setBackgroundResource(R.drawable.bg_field)
            setPadding(dp(14), 0, dp(10), 0)
        }
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44))
        box.addView(addr, lp)
        box.addView(size, LinearLayout.LayoutParams(lp).apply { topMargin = dp(10) })

        Dialogs.custom(ctx, "读取当前固件", box, okText = "开始读取") {
            val a = parseNum(addr.text.toString())
            val n = parseNum(size.text.toString())
            if (a < 0 || n <= 0) { toast("地址或大小填错了"); return@custom }
            startRead(device, a, n)
        }
    }

    private fun parseNum(raw: String): Long {
        val t = raw.trim().lowercase()
        return runCatching {
            when {
                t.startsWith("0x") -> t.removePrefix("0x").toLong(16)
                t.endsWith("m") -> (t.removeSuffix("m").toDouble() * 1024 * 1024).toLong()
                t.endsWith("k") -> (t.removeSuffix("k").toDouble() * 1024).toLong()
                else -> t.toLong()
            }
        }.getOrDefault(-1)
    }

    private fun startRead(device: UsbDevice, address: Long, length: Long) {
        val ctx = requireContext()
        val dir = if (CrashStore.hasStorageRoot()) java.io.File(CrashStore.ROOT_DIR)
        else java.io.File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "read")
        if (!dir.exists()) dir.mkdirs()
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        val path = java.io.File(dir, "firmware-$stamp.bin").absolutePath

        showProgress(true, -1, "读取固件…")
        appendLog("开始读取 0x%X 起 %d 字节".format(address, length))
        job = lifecycleScope.launch(Dispatchers.Default) {
            PortBus.beginExclusive("flash")
            val link = SerialLink(ctx.applicationContext, device)
            try {
                if (!openLink(link)) {
                    uiStatus("打开串口失败")
                    return@launch
                }
                link.screenSink = { text -> appendNative(text) }
                NativeFlasher.nativeAttach(link)
                val err = NativeFlasher.nativeConnect(200, 10, true)
                if (err != NativeFlasher.OK) {
                    uiStatus("连接失败：" + NativeFlasher.errorText(err))
                    return@launch
                }
                uiLog("识别到芯片：${NativeFlasher.nativeTargetName()}")
                val sink = object : ProgressSink {
                    override fun onProgress(pct: Int) {
                        activity?.runOnUiThread { showProgress(true, pct, "读取固件…") }
                    }
                }
                val r = NativeFlasher.nativeReadFlash(address, length, path, sink)
                if (r == NativeFlasher.OK) {
                    uiLog("读取完成：$path")
                } else {
                    uiLog("读取失败：" + NativeFlasher.errorText(r) + "（可能超出 flash 容量）")
                    dumpFlashLog("读取日志")
                }
            } catch (t: Throwable) {
                L.w("读取固件异常", t)
                uiLog("读取异常：" + (t.message ?: t.javaClass.simpleName))
            } finally {
                activeLink = null
                runCatching { link.close() }
                PortBus.endExclusive()
                PortBus.close()
                activity?.runOnUiThread {
                    showProgress(false)
                    uiStatus("待机")
                }
            }
        }
    }

    private fun resetDevice() {
        val device = selectedDevice() ?: run { toast("先选设备"); return }
        logText.text = ""
        if (PortBus.busyBy != null) { toast("正在烧录，稍后再说"); return }
        lifecycleScope.launch(Dispatchers.IO) {
            val l = runCatching { PortBus.open(requireContext().applicationContext, device, 115200) {} }.getOrNull()
            if (l == null) {
                withContext(Dispatchers.Main) { toast("打开串口失败") }
                return@launch
            }
            var rst = 0
            runCatching {
                // 普通复位：IO0 一直保持高（DTR=false），只脉冲 EN（RTS）
                // 芯片重启后直接跑应用固件，不会掉进下载模式
                rst = l.setControlLinesIdx(false, true, 0)     // EN 低，芯片复位
                Thread.sleep(100)
                val r2 = l.setControlLinesIdx(false, false, 0) // EN 放开，IO0 仍高
                Thread.sleep(120)
                l.purgeInput()
                val boot = readBootBanner(l)
                L.i("复位重启：行状态 $rst/$r2，打印=${boot.take(80)}")
            }
            selfResetUntil = System.currentTimeMillis() + 5000
            PortBus.close()
            withContext(Dispatchers.Main) {
                // 设备正在重启：设备行不再显示旧信息，界面回到「已选固件」状态
                showSelectedFirmwareOnly()
                toast("已复位重启，芯片回到运行态")
            }
            // 复位后设备可能换 IP：重开检测窗口，下一段串口数据起 10 秒内重新识别
            SerialMirror.beginWindow()
            L.i("复位重启：后台网页地址检测重新开始")
        }
    }

    private fun arrayAdapter(items: List<String>): ArrayAdapter<String> =
        ArrayAdapter(requireContext(), R.layout.spinner_item, items)

    private var usbReceiver: android.content.BroadcastReceiver? = null
    private var pollJob: kotlinx.coroutines.Job? = null
    /** 正在烧录用的连接；USB 拔掉时用它立刻关断，native 读写马上失败退出 */
    private var activeLink: SerialLink? = null
    /** 本次进程内验证过的最小可用写入块，跨文件复用，避免每个文件都去撞大块 */
    private var goodBlock = 0
    /** 完整原始日志（含库内部调试行），屏幕上不显示，但「复制」用它 */
    private val rawLog = StringBuilder()
    private var usbLost = false
    /** 自己点「设备复位重启」后的几秒内，USB 掉线是我们自己造成的，别当成断线 */
    private var selfResetUntil = 0L
    /** 烧录已经全部写完：芯片随后自动复位掉线，属于正常收尾 */
    private var endingOk = false

    private fun usbVidPidOf(d: android.hardware.usb.UsbDevice?): Pair<Int, Int>? =
        d?.let { it.vendorId to it.productId }

    /** USB 拔掉：立刻关断连接让 native 收发失败退出，并终止这次烧录 */
    private fun abortFlashByUsbLoss() {
        if (endingOk) return
        if (job?.isActive != true) return
        usbLost = true
        appendLog("USB 已断开，终止烧录")
        runCatching { activeLink?.close() }
        job?.cancel()
        showProgress(false, -1, "已终止")
    }
    /** 复位/拔插导致设备暂时消失时，设备一回来就自动接着做 */
    private var pendingRetry: (() -> Unit)? = null

    override fun onResume() {
        super.onResume()
        if (::deviceLabel.isInitialized) {
            refreshDevices()
            startAutoDetect()
        }
    }

    override fun onPause() {
        super.onPause()
        stopAutoDetect()
    }

    /** 插拔立刻重扫 + 每 2 秒轮询，跟日志页保持一致 */
    private fun startAutoDetect() {
        if (usbReceiver == null) {
            val r = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: android.content.Context?, i: android.content.Intent?) {
                    if (i?.action == android.hardware.usb.UsbManager.ACTION_USB_DEVICE_DETACHED) {
                        val ignore = endingOk || System.currentTimeMillis() < selfResetUntil
                        SerialMirror.clear()
                        if (!ignore) (activity as? MainActivity)?.onDeviceUnplugged()
                        val gone = (i.getParcelableExtra(android.hardware.usb.UsbManager.EXTRA_DEVICE) as? android.hardware.usb.UsbDevice)
                            ?: (i.getParcelableExtra<android.hardware.usb.UsbDevice>("device"))
                        val vidPid = gone?.let { it.vendorId to it.productId }
                        val same = vidPid == null || usbVidPidOf(selectedDevice()) == vidPid
                        if (same && !ignore && job?.isActive == true) {
                            abortFlashByUsbLoss()
                        }
                        pendingRetry = null
                    }
                    refreshDevices()
                    if (i?.action == android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED) {
                        (activity as? MainActivity)?.onDeviceAttached()
                        val retry = pendingRetry
                        pendingRetry = null
                        retry?.invoke()
                    }
                }
            }
            usbReceiver = r
            val filter = android.content.IntentFilter().apply {
                addAction(android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(android.hardware.usb.UsbManager.ACTION_USB_DEVICE_DETACHED)
            }
            runCatching {
                androidx.core.content.ContextCompat.registerReceiver(
                    requireContext().applicationContext, r, filter,
                    androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
                )
            }.onFailure { L.w("烧录页注册 USB 广播失败", it) }
        }
        if (pollJob?.isActive == true) return
        pollJob = lifecycleScope.launch {
            while (true) {
                kotlinx.coroutines.delay(2000)
                if (job?.isActive == true) continue
                refreshDevices()
            }
        }
    }

    private fun stopAutoDetect() {
        pollJob?.cancel()
        pollJob = null
        usbReceiver?.let { runCatching { requireContext().applicationContext.unregisterReceiver(it) } }
        usbReceiver = null
    }

    private fun refreshDevices() {
        devices.clear()
        devices.addAll(runCatching { UsbDevices.list(requireContext()) }.getOrDefault(emptyList()))
        when {
            devices.isEmpty() -> {
                selectedIndex = -1
                deviceLabel.text = "未发现设备，点此重试"
                deviceLabel.setTextColor(0xFF8E949C.toInt())
            }
            devices.size == 1 -> {
                selectedIndex = 0
                updateDeviceLabel()
            }
            else -> {
                autoSelectRemembered()
                if (selectedIndex !in devices.indices) selectedIndex = -1
                if (selectedIndex >= 0) updateDeviceLabel()
                else {
                    deviceLabel.text = "${devices.size} 个设备，点此选择"
                    deviceLabel.setTextColor(0xFF3ED07D.toInt())
                }
            }
        }
    }

    /** 多设备时也优先选回上次连过的那台（复位重枚举后不用重新确认） */
    private fun autoSelectRemembered() {
        val saved = prefs0().getString("lastUsb", null) ?: return
        val idx = devices.indexOfFirst { "${it.vendorId}:${it.productId}" == saved }
        if (idx >= 0 && idx != selectedIndex) {
            selectedIndex = idx
            updateDeviceLabel()
        }
    }

    private fun updateDeviceLabel() {
        val d = devices.getOrNull(selectedIndex) ?: return
        deviceLabel.text = UsbDevices.label(d)
        deviceLabel.setTextColor(0xFF3ED07D.toInt())
    }

    /** 设备复位期间：设备行显示「未发现设备」，信息区只留已选中的固件 */
    private fun showSelectedFirmwareOnly() {
        if (!::deviceLabel.isInitialized) return
        deviceLabel.text = "未发现设备，点此重试"
        deviceLabel.setTextColor(0xFF8E949C.toInt())
        runCatching { refreshFileList() }
        runCatching { refreshDevices() }
    }

    private fun pickDevice() {
        if (devices.isEmpty()) {
            refreshDevices()
            if (devices.isEmpty()) {
                toast("没找到串口设备，插上 USB 再试")
                return
            }
        }
        val labels = devices.map { UsbDevices.label(it) }
        DevicePicker.show(requireContext(), "选择设备", labels) { which ->
            selectedIndex = which
            updateDeviceLabel()
            appendLog("已选择 ${labels[which]}")
        }
    }

    private fun selectedDevice(): UsbDevice? = devices.getOrNull(selectedIndex)

    /**
     * 自动识别烧录偏移；识别不出来返回 null（输入框留空，由用户手填）。
     * 优先级：文件名显式 0xXXXX > 文件名关键字 > 文件头特征 > 整包体积判定 > 应用镜像头。
     */
    private fun detectOffset(name: String, b: ByteArray): Long? {
        Regex("(?i)0x([0-9a-f]{3,8})").find(name)?.let { m ->
            return m.groupValues[1].toLong(16)
        }
        val n = name.lowercase()
        when {
            "bootloader" in n -> return 0x0
            "partition" in n -> return 0x8000
            "boot_app0" in n -> return 0xE000
            "otadata" in n -> return 0xD000
            "model0" in n -> return 0x190000
            "model1" in n -> return 0x510000
            "model2" in n -> return 0x890000
            "model3" in n -> return 0xC10000
        }
        fun at(i: Int) = if (i < b.size) b[i].toInt() and 0xFF else -1
        // 分区表镜像：头两字节 AA 50
        if (at(0) == 0xAA && at(1) == 0x50) return 0x8000
        // ≥8MB 的整包镜像（merged full-flash），从 0x0 整片烧
        if (b.size >= 8 * 1024 * 1024) return 0x0
        // 应用镜像：magic E9 + 0x20 处 app_desc magic ABCD5432
        if (b.size >= 0x100 && at(0) == 0xE9) {
            val magic = at(0x20) or (at(0x21) shl 8) or (at(0x22) shl 16) or (at(0x23) shl 24)
            if (magic == 0xABCD5432.toInt()) return 0x10000
        }
        return null
    }

    /** 允许的固件后缀 */
    private val FIRMWARE_EXTS = setOf("bin", "hex", "elf", "img", "app")
    /** 压缩包一律拒收：要先解压出 bin 再添加 */
    private val ARCHIVE_EXTS = setOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "cab", "iso")
    /** 明确拒收的安装包后缀 */
    private val INSTALLER_EXTS = setOf("apk", "exe", "msi", "dmg", "iso", "jar", "ipa")
    /** 图片后缀：不是固件 */
    private val IMAGE_EXTS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif",
        "ico", "tif", "tiff", "svg", "raw", "psd")
    /** 视频后缀：不是固件 */
    private val VIDEO_EXTS = setOf("mp4", "mov", "avi", "mkv", "webm", "flv", "wmv", "m4v",
        "3gp", "mpg", "mpeg", "ts", "rmvb")

    private fun addFile(uri: Uri) {
        val resolver = requireContext().contentResolver
        var name = uri.lastPathSegment ?: "firmware.bin"
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) name = c.getString(0)
        }
        val ext = name.substringAfterLast('.', "").lowercase()
        // 安装包类一律拒收：apk/exe/msi/dmg/iso 不是 ESP 固件
        if (ext in INSTALLER_EXTS) {
            throw IllegalIOException("${".$ext"} 是安装包，不能当固件烧录")
        }
        if (ext in ARCHIVE_EXTS) {
            throw IllegalIOException("${".$ext"} 是压缩包，不能直接烧录；先解压出 .bin 再添加")
        }
        if (ext in IMAGE_EXTS) {
            throw IllegalIOException("${".$ext"} 是图片，不能当固件烧录")
        }
        if (ext in VIDEO_EXTS) {
            throw IllegalIOException("${".$ext"} 是视频，不能当固件烧录")
        }
        if (ext !in FIRMWARE_EXTS) {
            throw IllegalIOException("不是固件文件（只认 ${FIRMWARE_EXTS.joinToString("/") { ".$it" }}）")
        }
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalIOException("无法读取文件")
        // 后缀改了也拦：按文件头判断是不是压缩包 / 图片 / 视频
        when (sniffKind(bytes)) {
            "压缩包" -> throw IllegalIOException("这个文件其实是压缩包（改了后缀也一样），先解压出 .bin 再添加")
            "图片" -> throw IllegalIOException("这个文件其实是图片（改了后缀也一样），不能当固件烧录")
            "视频" -> throw IllegalIOException("这个文件其实是视频（改了后缀也一样），不能当固件烧录")
        }
        if (looksLikeArchive(bytes)) {
            throw IllegalIOException("这个文件其实是压缩包（改了后缀也一样），先解压出 .bin 再添加")
        }
        val info = probeImage(bytes)
        val offset = detectOffset(name, bytes)
        files.add(PendingFile(name, bytes.size.toLong(), offset, bytes, info.first, info.second, info.third))
    }

    /** 文件头识别常见压缩包格式：zip / rar / 7z / gzip / bzip2 / xz / tar */
    private fun looksLikeArchive(b: ByteArray): Boolean {
        fun at(i: Int) = if (i < b.size) b[i].toInt() and 0xFF else -1
        return when {
            at(0) == 0x50 && at(1) == 0x4B && (at(2) == 0x03 || at(2) == 0x05 || at(2) == 0x07) -> true
            at(0) == 0x52 && at(1) == 0x61 && at(2) == 0x72 && at(3) == 0x21 -> true
            at(0) == 0x37 && at(1) == 0x7A && at(2) == 0xBC && at(3) == 0xAF -> true
            at(0) == 0x1F && at(1) == 0x8B -> true
            at(0) == 0x42 && at(1) == 0x5A && at(2) == 0x68 -> true
            at(0) == 0xFD && at(1) == 0x37 && at(2) == 0x7A -> true
            at(257) == 0x75 && at(258) == 0x73 && at(259) == 0x74 && at(260) == 0x61 && at(261) == 0x72 -> true
            else -> false
        }
    }

    /** 按文件头嗅探真实类型：压缩包 / 图片 / 视频 / 其它 */
    private fun sniffKind(b: ByteArray): String? {
        fun at(i: Int) = if (i < b.size) b[i].toInt() and 0xFF else -1
        if (looksLikeArchive(b)) return "压缩包"
        val img = (at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47) ||   // PNG
            (at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF) ||                          // JPEG
            (at(0) == 0x47 && at(1) == 0x49 && at(2) == 0x46) ||                          // GIF
            (at(0) == 0x42 && at(1) == 0x4D) ||                                           // BMP
            (at(0) == 0x49 && at(1) == 0x49 && at(2) == 0x2A) ||                          // TIFF
            (at(0) == 0x52 && at(1) == 0x49 && at(2) == 0x46 && at(3) == 0x46 &&
                at(8) == 0x57 && at(9) == 0x45 && at(10) == 0x42 && at(11) == 0x50)        // WEBP
        if (img) return "图片"
        val ftyp = at(4) == 0x66 && at(5) == 0x74 && at(6) == 0x79 && at(7) == 0x70
        if (ftyp) {
            val brand = if (b.size > 12) String(b, 8, 4) else ""
            return when (brand.trim()) {
                "heic", "heix", "mif1", "msf1", "hevc" -> "图片"
                else -> "视频"
            }
        }
        if (at(0) == 0x1A && at(1) == 0x45 && at(2) == 0xDF && at(3) == 0xA3) return "视频" // Matroska
        return null
    }

    private class IllegalIOException(msg: String) : Exception(msg)

    /** 从 bin 头里读芯片型号 / IDF 版本 / 应用版本（esp_app_desc_t 在镜像 0x20 处） */
    private fun probeImage(bytes: ByteArray): Triple<String?, String?, String?> {
        if (bytes.size < 0x100 || bytes[0].toInt() and 0xFF != 0xE9) return Triple(null, null, null)
        val chip = when (bytes[12].toInt() and 0xFF) {
            0 -> "ESP32"
            2 -> "ESP32-S2"
            5 -> "ESP32-C3"
            9 -> "ESP32-S3"
            12 -> "ESP32-C2"
            13 -> "ESP32-C6"
            16 -> "ESP32-H2"
            18 -> "ESP32-P4"
            else -> null
        }
        val magic = (bytes[0x20].toInt() and 0xFF) or ((bytes[0x21].toInt() and 0xFF) shl 8) or
            ((bytes[0x22].toInt() and 0xFF) shl 16) or ((bytes[0x23].toInt() and 0xFF) shl 24)
        if (magic != 0xABCD5432.toInt()) return Triple(chip, null, null)
        val ver = readCStr(bytes, 0x30, 32)
        val idf = readCStr(bytes, 0x90, 32)
        return Triple(chip, idf, ver)
    }

    private fun readCStr(b: ByteArray, off: Int, len: Int): String? {
        if (off + len > b.size) return null
        val sb = StringBuilder()
        for (i in 0 until len) {
            val c = b[off + i].toInt() and 0xFF
            if (c == 0) break
            if (c in 0x20..0x7E) sb.append(c.toChar())
        }
        return sb.toString().ifBlank { null }
    }

    /** 点地址标签改烧录偏移 */
    private fun editOffset(index: Int, onSaved: (() -> Unit)? = null) {
        val f = files.getOrNull(index) ?: return
        val ctx = context ?: return
        val et = EditText(ctx).apply {
            setText(f.offset?.let { "0x" + it.toString(16) } ?: "")
            setTextColor(0xFFE4E4EC.toInt())
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(24, 18, 24, 18)
            background = androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.bg_field)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        Dialogs.custom(ctx, "烧录地址", et, "保存") {
            val raw = et.text.toString().trim().removePrefix("0x").removePrefix("0X")
            if (raw.isEmpty()) {
                f.offset = null
                refreshFileList()
                onSaved?.invoke()
                return@custom
            }
            val v = raw.toLongOrNull(16)
            if (v == null) {
                toast("地址格式不对，例：0x10000")
            } else {
                f.offset = v
                refreshFileList()
                onSaved?.invoke()
            }
        }
    }

    private fun refreshFileList() {
        fileList.removeAllViews()
        rowInputs.clear()
        val inflater = LayoutInflater.from(requireContext())
        files.forEachIndexed { index, f ->
            val row = inflater.inflate(R.layout.item_flash_file, fileList, false)
            row.findViewById<TextView>(R.id.fileName).text = f.name
            if (!f.picked) row.alpha = 0.42f else row.alpha = 1f
            val idx = index
            fun bindTag(id: Int, text: String?) {
                val tv = row.findViewById<TextView>(id)
                if (text.isNullOrBlank()) {
                    tv.visibility = android.view.View.GONE
                } else {
                    tv.visibility = android.view.View.VISIBLE
                    tv.text = text
                }
            }
            bindTag(R.id.tagModel, f.chip)
            bindTag(R.id.tagIdf, f.idf?.let { if (it.startsWith("v")) "IDF $it" else "IDF v$it" })
            bindTag(R.id.tagVer, f.ver?.let { if (it.startsWith("v")) it else "v$it" })
            val addrTv = row.findViewById<TextView>(R.id.tagAddr)
            val off = f.offset
            if (off == null) {
                addrTv.visibility = android.view.View.GONE
            } else {
                addrTv.visibility = android.view.View.VISIBLE
                addrTv.text = "0x" + off.toString(16)
            }
            addrTv.isClickable = false
            addrTv.isFocusable = false
            bindTag(R.id.tagSize, readableSize(f.size))
            // 日志区只做展示，删除统一走固件库
            row.findViewById<Button>(R.id.removeFile).visibility = android.view.View.GONE
            fileList.addView(row)
        }
        if (files.isEmpty()) {
            val tip = TextView(requireContext())
            tip.text = "还没有固件，点上排「管理固件」添加 bin（支持多选）"
            tip.setTextColor(0xFF888888.toInt())
            fileList.addView(tip)
        }
        renderFileSummary()
    }

    /** 日志区顶部标题行：已选几个固件（明细由下面的固件块展示） */
    private fun renderFileSummary() {
        if (!::fileSummary.isInitialized) return
        fileSummary.text = when {
            files.isEmpty() -> "已选固件 0"
            else -> "已选固件 ${files.count { it.picked }} / ${files.size}"
        }
    }

    private fun readableSize(bytes: Long): String = when {
        bytes >= 1 shl 20 -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0)
        bytes >= 1 shl 10 -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    /** 把界面日志 + logback 文件 + 崩溃堆栈拼成一份纯文本 */
    private fun collectLog(): String {
        val sb = StringBuilder()
        // 只导屏幕上的这份日志，不带机型、日期这类头信息
        sb.append(logText.text)
        return sb.toString()
    }

    private fun exportLogFile() {
        val ts = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.CHINA).format(java.util.Date())
        val name = "espburn-log-$ts.txt"
        val saved = CrashStore.saveToDownload(requireContext(), name, collectLog())
        if (saved != null) {
            toast("已保存：$saved")
            appendLog("日志已保存：$saved")
        } else {
            createDoc.launch(name)
        }
    }

    private fun collectOffsets(): List<Long?> = files.map { it.offset }


    /** 报错现场（仅弹窗内展示，不再往文件夹落盘） */
    private fun dumpFlashLog(prefix: String): String? {
        val ctx = context ?: return null
        val p = prefs0()
        val report = buildString {
            append("==== ESP burn ").append(prefix).append(" ====\n")
            append("时间    ：").append(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date())).append('\n')
            append("版本    ：").append(CrashStore.appVersion(ctx))
                .append("（versionCode ").append(CrashStore.appVersionCode(ctx)).append("）\n")
            append("机型    ：").append(android.os.Build.MANUFACTURER).append(' ')
                .append(android.os.Build.MODEL).append("  Android ")
                .append(android.os.Build.VERSION.RELEASE).append('\n')
            append("设备    ：").append(runCatching { PortBus.label }.getOrDefault(""))
                .append("  ").append(devices.getOrNull(selectedIndex)?.let { UsbDevices.label(it) } ?: "未选择")
                .append('\n')
            append("芯片    ：").append(runCatching { NativeFlasher.nativeTargetName() }.getOrDefault("?"))
                .append('\n')
            append("参数    ：型号 ").append(p.getString("pChip", "auto"))
                .append(" / 波特率 ").append(p.getString("flashBaud", "115200"))
                .append(" / 频率 ").append(p.getString("pFreq", "keep"))
                .append(" / 模式 ").append(p.getString("pMode", "dio"))
                .append(" / 大小 ").append(p.getString("pSize", "keep"))
                .append(" / 块 ").append(p.getString("blockKb", "16k"))
                .append(" / 自动BOOT ").append(if (p.getBoolean("autoReset", true)) "开" else "关")
                .append(" / 加速 ").append(if (p.getBoolean("highSpeed", true)) "开" else "关")
                .append('\n')
            append("固件    ：").append(files.size).append(" 个\n")
            files.forEachIndexed { i, f ->
                val off = f.offset?.let { "0x" + it.toString(16) } ?: "未填"
                append("  ").append(i + 1).append(". ").append(f.name)
                    .append("  ").append(f.size).append(" 字节  偏移 ").append(off).append('\n')
            }
            append("\n==== 过程日志 ====\n").append(logText.text)
            append("\n==== 内部日志（最近 200 行）====\n").append(L.logFileText(200))
            append('\n')
        }
        return report
    }

    /** 烧录进度：细长条 + 左侧说明 + 右侧百分比 */
    private fun showProgress(visible: Boolean, pct: Int = -1, label: String = "烧录固件…") {
        if (!::progressWrap.isInitialized) return
        progressWrap.visibility = if (visible) View.VISIBLE else View.GONE
        progressLabel.text = label
        // pct < 0：还没拿到真实进度，用来回走动的条；拿到进度后从左侧开始实心推进
        val unknown = pct < 0
        progressIndet.visibility = if (visible && unknown) View.VISIBLE else View.GONE
        progressBar.visibility = if (visible && !unknown) View.VISIBLE else View.GONE
        progressPct.visibility = if (visible && !unknown) View.VISIBLE else View.GONE
        if (!unknown) {
            progressPct.text = "$pct%"
            progressBar.progress = pct
        }
    }

    private fun prefs0() = requireContext().getSharedPreferences("espburn", android.content.Context.MODE_PRIVATE)

    /** 固件卡片第二行：型号 · 波特率 · 频率 · 模式 · 大小 */
    private fun updateParamLine() {
        if (!::paramLine.isInitialized) return
        val p = prefs0()
        paramLine.text = "型号:" + (p.getString("pChip", "auto") ?: "auto") +
            " · 波特率:" + (p.getString("flashBaud", "115200") ?: "115200") +
            " · 频率:" + (p.getString("pFreq", "keep") ?: "keep") +
            " · 模式:" + (p.getString("pMode", "dio") ?: "dio") +
            " · 大小:" + (p.getString("pSize", "keep") ?: "keep")
    }

    /** 打开串口：303A 原生 USB 优先走原生 CDC bulk，通路可疑时再退回库 */
    private fun openLink(link: SerialLink): Boolean {
        val ok = link.open { uiLog(it) }
        if (ok && link.rawInfo.isNotBlank()) uiLog(link.rawInfo)
        return ok
    }

    /**
     * 一次完整复位：拉低 EN → 拉低 IO0 → 释放 EN → 释放 IO0。
     * 原生 USB 复位后通常不掉线，沿用现有连接；只有真掉线才重开。
     * 注意：写完 1 字节探针"成功"不能证明 ROM 在下载模式（USB 收数据不等于 ROM 在读）。
     * 所以这里只做复位动作，是否进下载模式由后面的 SLIP 同步来判定。
     */
    private suspend fun resetOnce(link: SerialLink, dtrIsIo0: Boolean, idx: Int): Boolean {
        // 复位会让设备短暂离开总线，先开 5 秒窗口，别把自家触发的掉线当成异常
        selfResetUntil = System.currentTimeMillis() + 5000
        fun io0(low: Boolean) = if (dtrIsIo0) link.setControlLinesIdx(low, !low, idx) else link.setControlLinesIdx(!low, low, idx)
        val r1 = io0(false)
        kotlinx.coroutines.delay(100)
        val r2 = io0(true)
        kotlinx.coroutines.delay(60)
        // 收尾必须两根线都释放。若这里再调 io0(false)，会把 RTS 拉回 1 = EN 拉低，
        // 芯片刚启动 60ms 又被摁回复位，永远收不到同步应答（之前所有读超时的根因）。
        val r3 = link.setControlLinesIdx(false, false, idx)
        uiLog("复位：接口#$idx ${if (dtrIsIo0) "DTR=IO0" else "RTS=IO0"}，行状态返回值 $r1/$r2/$r3")
        kotlinx.coroutines.delay(120)
        // 芯片被复位时 USB-Serial-JTAG 的 USB 外设一起重置，旧句柄必然失效：
        // 写会卡满超时后 rc=-1、读永远 0 字节。设备可能几十毫秒就回来，
        // 所以不能按"设备还在"就沿用旧连接，必须丢句柄重新 openDevice。
        val reopened = withContext(Dispatchers.IO) { link.reopenAfterReset(2000) }
        if (!reopened) {
            uiLog("复位后端口重开失败：设备没回到 USB")
            return false
        }
        uiLog("复位后已重新打开端口" + if (link.rawInfo.isNotBlank()) "：${link.rawInfo}" else "")
        NativeFlasher.nativeAttach(link)
        link.purgeInput()
        return true
    }

    /**
     * 乐鑫 USB-Serial-JTAG（303A:1001）专用进下载模式序列，
     * 逐步对应 esptool 的 usb_jtag_bootloader_reset：
     *   (0,0) 空闲 100ms → (1,0) IO0 拉低 100ms → (0,1) EN 拉低复位、IO0 释放 100ms → (0,0) 释放 EN
     * 关键差别：IO0 是在"复位之前"拉低、在"复位期间"释放的，
     * 普通 UART 那套"复位释放瞬间 IO0 保持低"在这里反而不生效。
     */
    private suspend fun usbJtagReset(link: SerialLink) {
        selfResetUntil = System.currentTimeMillis() + 5000
        fun lines(dtr: Boolean, rts: Boolean, tag: String) {
            val r = link.setControlLinesIdx(dtr, rts, 0)
            uiLog("USB-JTAG 复位 $tag：DTR=${if (dtr) 1 else 0} RTS=${if (rts) 1 else 0}（ret=$r）")
        }
        lines(dtr = false, rts = false, tag = "①空闲")
        kotlinx.coroutines.delay(100)
        lines(dtr = true, rts = false, tag = "②IO0 拉低")
        kotlinx.coroutines.delay(100)
        // 官方这里是 setRTS(1) → setDTR(0) → setRTS(1)，最后一笔是为了让 Windows 把 DTR 变化推下去
        link.setControlLinesIdx(true, true, 0)
        lines(dtr = false, rts = true, tag = "③EN 复位")
        link.setControlLinesIdx(false, true, 0)
        kotlinx.coroutines.delay(100)
        lines(dtr = false, rts = false, tag = "④释放复位")
        kotlinx.coroutines.delay(150)
        // 复位同样会让 USB-Serial-JTAG 重枚举，旧句柄作废，必须重开
        val reopened = withContext(Dispatchers.IO) { link.reopenAfterReset(2000) }
        if (reopened) uiLog("复位后已重新打开端口" + if (link.rawInfo.isNotBlank()) "：${link.rawInfo}" else "")
        else uiLog("复位后端口重开失败：设备没回到 USB")
    }

    /** 复位后读一小段，拿芯片启动打印（ESP-ROM 开头说明确实重启了） */
    private suspend fun readBootBanner(link: SerialLink): String {
        val sb = StringBuilder()
        val deadline = System.currentTimeMillis() + 1200
        while (System.currentTimeMillis() < deadline && sb.length < 256) {
            val buf = ByteArray(64)
            val n = withContext(Dispatchers.IO) { link.readFully(buf, 32, 200) }
            if (n > 0) sb.append(String(buf, 0, n, Charsets.UTF_8))
        }
        return sb.toString().replace("\u0000", "").replace("\r", "").trim()
    }

    /** 弹窗等用户按住 BOOT 再点 RST，然后重试握手 */
    private suspend fun askManualBoot(): Boolean = withContext(Dispatchers.Main) {
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        val ctx = requireContext()
        Dialogs.confirm(ctx, "需要手动进入BOOT模式",
            "板子的 DTR/RTS 自动复位没生效。请按住 BOOT 键，再点一下 RST（或断电重插），然后点「重试」。",
            okText = "重试",
            onCancel = { if (cont.isActive) cont.resume(false) }
        ) { if (cont.isActive) cont.resume(true) }
        }
    }

    /**
     * 握手：先直接同步（芯片可能已在下载模式）。
     * 不行就复位后同步，以"ROM 有没有回 SLIP 应答"作为唯一判据，
     * 依次尝试 CDC 控制接口 #0 / 数据接口 #1 × DTR=IO0 / RTS=IO0 四种组合。
     * 不像以前那样靠"写 1 字节成功"就宣布已进入下载模式。
     */
    private suspend fun connectChip(link: SerialLink, wantStub: Boolean = false): Int {
        link.purgeInput()
        Thread.sleep(100)
        var err: Int

        if (link.isUsbJtag()) {
            // 先做一次极短的同步探测：芯片若已经在下载模式，几十毫秒就会回包。
            // 以前一上来就带 stub 连 8 轮，芯片在跑应用固件时白等 11 秒还必然失败。
            val probe = NativeFlasher.nativeConnect(200, 1, false)
            if (probe == NativeFlasher.OK) {
                if (!wantStub) return probe
                // 已在下载模式：重新同步一次并上传 stub（很快）
                return NativeFlasher.nativeConnect(400, 6, true)
            }
            if (!cbAutoReset.isChecked) {
                uiLog("直接同步失败（${NativeFlasher.errorText(probe)}），自动BOOT模式已关闭")
                return probe
            }
            uiLog("自动进入BOOT模式")
            err = probe
        } else if (cbAutoReset.isChecked) {
            // UART 桥（CH340 等）插着时芯片一般跑着应用，直接同步只会收到它的串口输出
            uiLog("自动进入BOOT模式")
            err = NativeFlasher.TIMEOUT_CODE
        } else {
            err = NativeFlasher.nativeConnect(400, 8, wantStub)
            if (err == NativeFlasher.OK) return err
            uiLog("直接同步失败（${NativeFlasher.errorText(err)}），自动BOOT模式已关闭")
            return err
        }

        if (link.isUsbJtag()) {
            usbJtagReset(link)
            NativeFlasher.nativeAttach(link)
            for (round in 1..2) {
                err = NativeFlasher.nativeConnect(500, 8, wantStub)
                if (err == NativeFlasher.OK) {
                    uiLog("握手成功：USB-JTAG 官方复位序列")
                    return err
                }
                uiLog("USB-JTAG 序列第 $round 次同步未回包（${NativeFlasher.errorText(err)}）")
                kotlinx.coroutines.delay(300)
                NativeFlasher.nativeAttach(link)
            }
        }
        for (dtr in booleanArrayOf(true, false)) {
            if (!resetOnce(link, dtr, 0)) continue
            Thread.sleep(120)
            err = NativeFlasher.nativeConnect(400, 8, wantStub)
            if (err == NativeFlasher.OK) {
                uiLog("握手成功：${if (dtr) "DTR=IO0" else "RTS=IO0"}")
                return err
            }
            uiLog("该组合同步失败（${NativeFlasher.errorText(err)}），换下一种")
        }
        return err
    }

    /** 握手失败的诊断文本：带上收发字节数与读超时次数，不再只说“无法握手” */
    private fun connectDiagnosis(err: Int): String {
        val stats = runCatching { NativeFlasher.nativeStats() }.getOrDefault("")
        val tx = Regex("tx=(\\d+)").find(stats)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val rx = Regex("rx=(\\d+)").find(stats)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val rto = Regex("read_timeout=(\\d+)").find(stats)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val base = NativeFlasher.errorText(err)
        return when {
            tx > 0 && rx == 0 && rto > 0 ->
                "串口无响应：发出 $tx 字节无任何回包（读超时 $rto 次），芯片没有在BOOT模式读串口"
            tx == 0 ->
                "串口打不开或写不出去：0 字节发出，检查数据线是否支持传输、USB 是否被别的 App 占用"
            else -> "无法与芯片握手（$base）"
        }
    }

    /**
     * 打开串口 → （可选）自动BOOT → 连接芯片。
     * 失败返回错误原因，成功回调里已经把 loader 连上。
     */
    private suspend fun <T> withLoader(device: UsbDevice, wantStub: Boolean = false,
                                       body: suspend (SerialLink) -> T): Pair<T?, String?> {
        val ctx = requireContext().applicationContext
        PortBus.beginExclusive("flash")
        val link = SerialLink(ctx, device)
        try {
            if (!openLink(link)) return null to "打开串口失败"
            NativeFlasher.nativeAttach(link)
            // 擦除/读信息也要能看到 native 日志（容量、模式、校验结果都从这里出）
            link.screenSink = { appendLog(it) }
            val err = connectChip(link, wantStub)
            if (err != NativeFlasher.OK) {
                return null to connectDiagnosis(err)
            }
            if (cbHighSpeed.isChecked) {
                val baud = prefs0().getString("flashBaud", "115200")?.toIntOrNull() ?: 115200
                val e2 = NativeFlasher.nativeSetSpeed(baud)
                if (e2 == NativeFlasher.OK) uiLog("加速模式：波特率已切到 $baud")
                else uiLog("加速模式未生效（${NativeFlasher.errorText(e2)}），保持 115200")
            }
            val chip = NativeFlasher.nativeTargetName()
            uiLog("识别到芯片：$chip")
            val out = body(link)
            return out to null
        } catch (t: Throwable) {
            L.w("串口操作异常", t)
            return null to (t.message ?: t.javaClass.simpleName)
        } finally {
            runCatching { link.close() }
            PortBus.endExclusive()
            PortBus.close()
        }
    }

    /** 「设备型号读取」：连上后读出芯片型号 */
    private fun chipInfo() {
        val device = selectedDevice() ?: run { toast("先选一个串口设备"); return }
        if (job?.isActive == true) { toast("正在忙，稍后再试"); return }
        job = lifecycleScope.launch(Dispatchers.Default) {
            val (chip, err) = withLoader(device) { NativeFlasher.nativeTargetName() }
            withContext(Dispatchers.Main) {
                job = null
                if (chip == null) {
                    appendLog("读取型号失败：$err")
                    dumpFlashLog("读取型号日志")
                    FlashResult.showFail(requireContext(), 0.0, err ?: "读取型号失败", collectLog())
                } else {
                    appendLog("芯片型号：$chip")
                    Dialogs.message(requireContext(), "设备型号", chip, okText = "知道了")
                }
            }
        }
    }

    /** 「设备芯片擦除」：整片擦除 flash */
    private fun eraseChip() {
        val device = selectedDevice() ?: run { toast("先选一个串口设备"); return }
        if (job?.isActive == true) { toast("正在忙，稍后再试"); return }
        Dialogs.confirm(requireContext(), "擦除整片 Flash",
            "会把芯片里所有数据清空，且不可恢复。继续？", okText = "擦除") {
                val startedAt = System.currentTimeMillis()
                // 擦除开始同样清屏，只留这一次擦除的日志
                visLog.setLength(0)
                clearLiveLine()
                logText.setText("")
                rawLog.setLength(0)
                showProgress(true, -1, "擦除中…")
                // 擦除是整片擦，16 MB 要二十来秒，秒数往上走，别让人以为卡死了
                val ticker = lifecycleScope.launch(Dispatchers.Main) {
                    while (true) {
                        kotlinx.coroutines.delay(1000)
                        val sec = ((System.currentTimeMillis() - startedAt) / 1000).coerceAtLeast(1)
                        showProgress(true, -1, "擦除中… ${sec}s")
                    }
                }
                job = lifecycleScope.launch(Dispatchers.Default) {
                    // 擦除强制带 stub：ROM 的 FLASH_BEGIN 擦 16 MB 会卡 24 位地址边界，
                    // 经常“返回成功但没擦掉”，stub 的整片擦除才是 esptool 同款可靠路径
                    val (ok, err) = withLoader(device, wantStub = true) { NativeFlasher.nativeEraseChip() }
                    val seconds = (System.currentTimeMillis() - startedAt) / 1000.0
                    withContext(Dispatchers.Main) {
                        ticker.cancel()
                        job = null
                        showProgress(false)
                        // 复制用完整报告：版本/机型/参数/过程日志/内部日志都带
                        val report = dumpFlashLog("擦除日志") ?: collectLog()
                        if (ok != null && ok == NativeFlasher.OK) {
                            appendLog("芯片擦除完成")
                            FlashResult.showEraseOk(
                                requireContext(), seconds,
                                "全片已擦除\n关键区域 0x0 / 0x8000 / 0x10000 / 末尾扇区 均为 0xFF",
                                report
                            )
                        } else {
                            val reason = err ?: ("擦除失败：" + NativeFlasher.errorText(ok ?: -1))
                            appendLog(reason)
                            FlashResult.showEraseFail(requireContext(), seconds, reason, report)
                        }
                    }
                }
            }
    }

    /** 「管理固件」：列出当前文件，可添加 / 删除 / 清空 */
    private var libRebuild: (() -> Unit)? = null

    /** 固件库：整套列表弹窗（返回箭头 + 新建固件 + 每行偏移/删除 + 底部确定/清除全部） */
    private fun manageFiles() {
        val ctx = requireContext()
        val dlg = android.app.Dialog(ctx)
        val v = LayoutInflater.from(ctx).inflate(R.layout.dialog_firmware_lib, null)
        dlg.setContentView(v)
        dlg.show()
        dlg.window?.let { w ->
            w.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            val width = (ctx.resources.displayMetrics.widthPixels * 0.92f).toInt()
            w.setLayout(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val listView = v.findViewById<LinearLayout>(R.id.libList)
        val libScroll = v.findViewById<android.widget.ScrollView>(R.id.libScroll)

        // 列表区高度：默认按两行给（比一行高出一倍），之后随文件数自动长高，最多占屏幕一半
        val rowPitch = dp(72)   // 64dp 行 + 8dp 间距
        fun fitListHeight() {
            val screenH = ctx.resources.displayMetrics.heightPixels
            val minH = rowPitch * 2
            val want = rowPitch * files.size.coerceAtLeast(1)
            val maxH = (screenH * 0.50f).toInt()
            libScroll.layoutParams = libScroll.layoutParams.apply {
                height = want.coerceIn(minH, maxH)
            }
        }

        fun rebuild() {
            listView.removeAllViews()
            val inflater = LayoutInflater.from(ctx)
            files.forEachIndexed { index, f ->
                val row = inflater.inflate(R.layout.item_firmware_row, listView, false)
                row.findViewById<TextView>(R.id.fwName).text = f.name
                val ext = f.name.substringAfterLast('.', "bin").lowercase()
                val head = ext.uppercase()
                // 副标题：后缀（按类型上色） · 自动识别的偏移（红字） · 大小；识别不到就不带偏移段
                val headText = head
                val offText = f.offset?.let { "0x" + it.toString(16).uppercase() }
                val sizeText = readableSize(f.size)
                val subText = listOfNotNull(headText, offText, sizeText).joinToString(" · ")
                val sub = android.text.SpannableString(subText)
                sub.setSpan(
                    android.text.style.ForegroundColorSpan(
                        when (ext) {
                            "bin" -> 0xFF5B8DEF.toInt()
                            "hex" -> 0xFF4ADE80.toInt()
                            "elf" -> 0xFFA78BFA.toInt()
                            "img" -> 0xFFF59E0B.toInt()
                            "app" -> 0xFF34D399.toInt()
                            else -> 0xFF9AA0AA.toInt()
                        }
                    ), 0, headText.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                if (offText != null) {
                    val start = headText.length + 3   // 分隔符 " · " 占 3 个字符
                    sub.setSpan(
                        android.text.style.ForegroundColorSpan(0xFFF4756B.toInt()),
                        start, start + offText.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                row.findViewById<TextView>(R.id.fwSub).text = sub
                // 右侧 0x + 下划线：显示自动识别的偏移，点一下可以改
                // 前缀 0x：0 放大一号、x 保持小号，靠 RelativeSizeSpan 共享同一条基线
                row.findViewById<TextView>(R.id.fwOffsetPrefix).apply {
                    val pfx = android.text.SpannableString("0x")
                    pfx.setSpan(android.text.style.RelativeSizeSpan(1.3f), 0, 1,
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    pfx.setSpan(android.text.style.ForegroundColorSpan(0xFFB4B4BE.toInt()), 0, 1,
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    text = pfx
                }
                row.findViewById<TextView>(R.id.fwOffset).apply {
                    text = f.offset?.toString(16)?.uppercase() ?: ""
                    setOnClickListener {
                        editOffset(index) {
                            refreshFileList()
                            libRebuild?.invoke()
                        }
                    }
                }
                // 多选：每一行都能独立勾选，勾上的才进烧录
                val cb = row.findViewById<android.widget.CheckBox>(R.id.fwRadio)
                cb.isChecked = f.picked
                fun toggle() {
                    f.picked = !f.picked
                    cb.isChecked = f.picked
                    refreshFileList()
                }
                cb.setOnClickListener { toggle() }
                row.setOnClickListener { toggle() }
                row.findViewById<android.view.View>(R.id.fwDel).setOnClickListener {
                    files.removeAt(index)
                    refreshFileList()
                    rebuild()
                }
                listView.addView(row)
            }
            fitListHeight()
            if (files.isEmpty()) {
                listView.addView(TextView(ctx).apply {
                    text = "还没有固件，点上面「添加固件」添加"
                    setTextColor(0xFF8E8E9A.toInt())
                    textSize = 13f
                    gravity = android.view.Gravity.CENTER
                    setPadding(8, dp(40), 8, dp(40))
                })
                fitListHeight()
            }
        }

        // 顶部：左「添加固件」打开文件选择，右圆形垃圾桶清空；底部大按钮返回
        v.findViewById<android.view.View>(R.id.libAdd).setOnClickListener {
            openLibAfterPick = false
            picker.launch(firmwareMime)
            libRebuild = { runCatching { rebuild() } }
        }
        v.findViewById<android.view.View>(R.id.libClear).setOnClickListener {
            files.clear()
            rowInputs.clear()
            refreshFileList()
            rebuild()
        }
        v.findViewById<android.view.View>(R.id.libBack).setOnClickListener { dlg.dismiss() }
        rebuild()
        libRebuild = { runCatching { rebuild() } }
        dlg.setOnDismissListener { libRebuild = null }
    }

    /**
     * 成功弹窗里点「打开串口助手」时调用：先把芯片正常复位一次（EN 拉低再释放，IO0 保持高），
     * 让它从下载模式跳出、跑刚烧进去的固件，再切到串口页。复位会让设备短暂重枚举，
     * 期间串口页会被挡住，等复位做完再放行，页面会自己重扫并连上。
     */
    /** 成功弹窗里点「打开串口助手」：切到串口页，由串口页连着的时候做一次复位重启 */
    private fun openSerialWithReset() {
        val act = activity as? MainActivity
        if (act == null) return
        selfResetUntil = System.currentTimeMillis() + 5000
        act.openLogTabWithReset()
    }

    private fun startFlash() {
        if (job?.isActive == true) return
        if (selectedIndex < 0 && devices.size == 1) {
            selectedIndex = 0
            updateDeviceLabel()
        }
        if (files.isEmpty()) {
            // 没固件就直接弹文件选择，选完再打开固件管理页，省得先看一个空列表
            openLibAfterPick = true
            pickFirmware()
            return
        }
        val device = selectedDevice()
        if (device == null) {
            toast("先选一个串口设备")
            return
        }
        if (!NativeFlasher.available) {
            toast("native 库没加载成功，无法烧录")
            return
        }
        val targets = files.filter { it.picked }
        if (targets.isEmpty()) {
            toast("固件库里没有勾选任何固件")
            return
        }
        val noAddr = targets.filter { it.offset == null }
        if (noAddr.isNotEmpty()) {
            toast("「${noAddr.first().name}」未填偏移地址")
            return
        }
        val offsets = targets.map { it.offset!! }
        val startedAt = System.currentTimeMillis()
        usbLost = false

        publishBusy()
        endingOk = false
        showProgress(true, -1, "烧录固件…")
        goodBlock = 0
        // 开始烧录就清屏，并把信息卡输出的那几行字清空（卡片和固件标签保留）
        visLog.setLength(0)
        clearLiveLine()
        logText.setText("")
        rawLog.setLength(0)
        fileSummary.text = ""
        setCompact(true)
        clearInfoRows()
        val devLabel = runCatching { "${device.productName ?: "未知设备"} · " +
                String.format("%04X:%04X", device.vendorId, device.productId) }.getOrDefault("串口设备")
        appendLog("========== 开始烧录 ${targets.size} 个文件 ==========")
        appendLog("设备：$devLabel")
        job = lifecycleScope.launch(Dispatchers.Default) {
            var failReason: String? = null
            PortBus.beginExclusive("flash")
            val ctx = requireContext().applicationContext
            val link = SerialLink(ctx, device)
            activeLink = link
            try {
                if (!openLink(link)) {
                    failReason = "打开串口失败"
                    return@launch
                }
                link.screenSink = { appendLog(it) }
                NativeFlasher.nativeAttach(link)
                uiStatus("同步芯片…")
                var err = connectChip(link, cbHighSpeed.isChecked)
                var manualTries = 0
                while (err != NativeFlasher.OK && manualTries < 2) {
                    manualTries++
                    if (!askManualBoot()) break
                    runCatching { link.reopenAfterReset(5000) }
                    link.purgeInput()
                    Thread.sleep(120)
                    uiStatus("再同步一次（第 ${manualTries + 1} 次）…")
                    err = NativeFlasher.nativeConnect(400, 12, cbHighSpeed.isChecked)
                }
                if (err != NativeFlasher.OK) {
                    failReason = connectDiagnosis(err)
                    uiStatus("错误：" + failReason)
                    return@launch
                }
                val chip = NativeFlasher.nativeTargetName()
                // 本机信息：芯片型号 / MAC / Flash 容量（读不到的行不打印）
                uiLog("芯片类型：$chip")
                val info = runCatching { NativeFlasher.nativeChipInfo() }.getOrDefault("")
                val mac = info.substringBefore('|')
                val flashBytes = info.substringAfter('|').toLongOrNull() ?: 0L
                if (mac.isNotBlank()) uiLog("MAC 地址：$mac")
                if (flashBytes > 0) uiLog("Flash 容量：${readableSize(flashBytes)}")
                // 型号一致性：固件包里读到的芯片型号必须和板上一致，不同就停下不烧
                val mismatch = targets.firstOrNull { f -> f.chip != null && !f.chip.equals(chip, true) }
                if (mismatch != null) {
                    failReason = "型号不一致：板子是 $chip，固件 ${mismatch.name} 是 ${mismatch.chip}，已取消烧录"
                    uiStatus("错误：" + failReason)
                    return@launch
                }
                // 加速块大小由「设置烧录参数」决定：16k / 8k / 4k / 1k，默认 16k
                val blockKb = prefs0().getString("blockKb", "16k") ?: "16k"
                val selBlock = when (blockKb) {
                    "1k" -> 1024
                    "4k" -> 4096
                    "8k" -> 8192
                    else -> 16384
                }
                if (cbHighSpeed.isChecked) {
                    if (link.isUsbJtag()) {
                        uiLog("加速：开（原生 USB，写入块 ${selBlock / 1024} KB）")
                    } else {
                        val baud = prefs0().getString("flashBaud", "115200")?.toIntOrNull() ?: 115200
                        val e2 = NativeFlasher.nativeSetSpeed(baud)
                        if (e2 == NativeFlasher.OK) uiLog("加速：开（CH340，波特率 $baud）")
                        else uiLog("加速未生效（${NativeFlasher.errorText(e2)}），保持 115200")
                    }
                } else {
                    uiLog("加速：关")
                }
                // 原生 USB 靠大块减少往返；16 KB 是实测这块芯片能收的上限，
                // 被拒（格式错误/参数非法）或首块超时都会自动降到 8K→4K→1K。
                val bigBlock = if (cbHighSpeed.isChecked && link.isUsbJtag()) selBlock else 4096
                link.purgeInput()
                for ((idx, f) in targets.withIndex()) {
                    val offset = offsets.getOrElse(idx) { f.offset!! }
                    val addr = "0x" + offset.toString(16).uppercase()
                    setLiveLine("正在写入 ${f.name}：0%")
                    val t0 = System.currentTimeMillis()
                    val sink = object : ProgressSink {
                        override fun onProgress(pct: Int) {
                            val scaled = (idx * 100 + pct) / targets.size
                            activity?.runOnUiThread {
                                showProgress(true, scaled, "正在写入 ${f.name}")
                                setLiveLine("正在写入 ${f.name}：$pct%")
                            }
                        }
                    }
                    // 只走普通写入：压缩通道已经整个去掉
                    var e = NativeFlasher.OK
                    // 分块从大到小试：块越大 ACK 往返越少，速度越快；
                    // 芯片不认某个块尺寸（回包格式错误/参数非法）就降一档。
                    // 起始档位取「设置烧录参数」里的加速块大小（16k/8k/4k/1k），再逐档往下退。
                    val allSizes = intArrayOf(16384, 8192, 4096, 1024)
                        .filter { it <= bigBlock }
                        .toIntArray()
                    // 前面已验证某个档位可用，从它开始，免得每个文件都重撞大块
                    val sizes = if (goodBlock in allSizes)
                        intArrayOf(goodBlock) + allSizes.filter { it != goodBlock && it < goodBlock }
                    else allSizes
                    // 单个文件最多试 3 次：第一次按大块，失败先降档，还不行就整段重写
                    for (attempt in 1..3) {
                        for (bs in sizes.distinct()) {
                            e = NativeFlasher.nativeFlashFile(offset, f.data, bs, sink)
                            if (e == NativeFlasher.OK) {
                                if (bs != sizes.first() || goodBlock == 0) {
                                    if (goodBlock != bs) goodBlock = bs
                                }
                                break
                            }
                            // 芯片明确不认这个块（格式错误/参数非法），或在第一个块上就超时，
                            // 都说明这一档吃不消，降一档；写了一半才超时则是真掉线，不降档。
                            val canDrop = bs != sizes.last()
                            val rejected = e == NativeFlasher.INVALID_RESPONSE ||
                                           e == 5 || e == 1
                            val tooBig = e == NativeFlasher.TIMEOUT_CODE && bs == sizes.first()
                            if (!(canDrop && (rejected || tooBig))) break
                            uiLog("块 $bs 字节被拒（${NativeFlasher.errorText(e)}），降一档重试")
                        }
                        if (e == NativeFlasher.OK) break
                        if (usbLost) break
                        if (attempt == 3) break
                        uiLog("写入 ${f.name} 第 $attempt 次失败（${NativeFlasher.errorText(e)}），重试 ${attempt + 1}/3")
                        uiStatus("重试 ${f.name}（${attempt + 1}/3）…")
                        kotlinx.coroutines.delay(300)
                    }
                    if (e != NativeFlasher.OK) {
                        failReason = "写入 ${f.name} 失败：" + NativeFlasher.errorText(e)
                        break
                    }
                    val ms = (System.currentTimeMillis() - t0).coerceAtLeast(1)
                    val kbs = f.data.size / 1024.0 / (ms / 1000.0)
                    finishLiveLine("正在写入 ${f.name}：100%")
                    uiLog("写入完成，耗时 ${ms / 1000.0}s，均速 ${"%.1f".format(kbs)} KB/s")
                }
                if (failReason == null) {
                    // 写完先让芯片自己跳回运行态：DTR/IO0 保持高，只脉冲 RTS/EN。
                    // 以前这里只打了一句“已复位运行”，实际没发复位，芯片一直停在 stub 里。
                    endingOk = true
                    val rs = runCatching {
                        val a = link.setControlLinesIdx(false, true, 0)
                        Thread.sleep(120)
                        val b = link.setControlLinesIdx(false, false, 0)
                        Thread.sleep(200)
                        a to b
                    }
                    if (rs.isSuccess) {
                        uiLog("已复位重启，芯片开始运行新固件")
                        L.i("烧录结束：复位脉冲已发出（$rs）")
                    } else {
                        uiLog("复位重启失败：${rs.exceptionOrNull()?.message ?: "串口已关闭"}")
                    }
                    progressWrap.post { showProgress(true, 100, "烧录固件…") }
                }
            } catch (t: Throwable) {
                failReason = "烧录异常：" + (t.message ?: t.javaClass.simpleName)
                L.w("烧录异常", t)
            } finally {
                activeLink = null
                runCatching { link.close() }
                PortBus.endExclusive()
                val seconds = (System.currentTimeMillis() - startedAt) / 1000.0
                // 烧录结束（成功或失败）自动展开回固件块
                val ok = failReason == null
                val reason = failReason
                withContext(Dispatchers.Main) {
                    job = null
                    publishBusy()
                    showProgress(false)
                    if (ok) {
                        // 先记下这批固件，弹窗要用；然后清空列表，下次烧录重新挑
                        val done = targets.map { it.name to it.ver }
                        files.clear()
                        rowInputs.clear()
                        refreshFileList()
                        renderFileSummary()
                        FlashResult.showOk(requireContext(), seconds, done) {
                            openSerialWithReset()
                        }
                    } else {
                        dumpFlashLog("烧录日志")
                        val gone = usbLost ||
                            runCatching { UsbDevices.list(requireContext()) }.getOrDefault(emptyList()).isEmpty()
                        if (usbLost) {
                            FlashResult.showFail(requireContext(), seconds, "USB 已断开，烧录已终止", rawLogText())
                        } else if (gone) {
                            appendLog("设备已断开，烧录未开始；插回后点「刷写固件」手动重新开始")
                            FlashResult.showFail(requireContext(), seconds,
                                "设备已断开，请插回后手动点「刷写固件」重试", rawLogText())
                        } else {
                            FlashResult.showFail(requireContext(), seconds, reason ?: "烧录失败", rawLogText())
                        }
                    }
                }
            }
        }
    }

    /** ⋮ 菜单里的「复制烧录日志」：把当前这屏烧录日志整段拷到剪贴板 */
    private fun copyFlashLog() {
        // 复制真实内容：带库内部行的完整原始日志，屏幕上那份是精简后的
        val text = rawLog.toString().ifEmpty { logText.text?.toString().orEmpty() }.trim()
        if (text.isEmpty()) {
            toast("还没有烧录日志，先烧一次")
            return
        }
        val cm = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
        runCatching { cm.setPrimaryClip(android.content.ClipData.newPlainText("ESPburn 烧录日志", text)) }
            .onSuccess { toast("烧录日志已复制（${text.length} 字）") }
            .onFailure { toast("复制失败：${it.message}") }
    }

    private fun rawLogText(): String =
        rawLog.toString().ifEmpty { logText.text?.toString().orEmpty() }.trim()

    /** 把「是否正在烧录」实时告诉 MainActivity：烧录页空闲时底栏「后台」随时可点 */
    private fun clearInfoRows() {
        if (::infoPanel.isInitialized) infoPanel.removeAllViews()
    }

    /** 烧录中收起上半部分（设备卡、按钮、开关），把整屏留给日志 */
    private fun setCompact(on: Boolean) {
        compactViews?.forEach { it.visibility = if (on) android.view.View.GONE else android.view.View.VISIBLE }
    }

    private fun publishBusy() {
        (activity as? MainActivity)?.flashBusyProvider = { job?.isActive == true && !usbLost }
    }

    private fun uiStatus(text: String) {
        activity?.runOnUiThread { appendLog(text) }
        L.i(text)
    }

    private fun uiLog(text: String) {
        if (text.isBlank()) return
        activity?.runOnUiThread { appendLog(text) }
        L.i(text)
    }

    /** 库内部调试行（[2]/[3] 前缀）与复位细节，属于实现细节，不上屏 */
    private fun isNoise(text: String): Boolean {
        val t = text.trim()
        if (t.matches(Regex("^\\[\\d+\\]\\s.*"))) return true
        val keys = arrayOf(
            "接口拓扑", "读通道正常", "内部复位跳过",
            "USB-JTAG 复位", "复位：接口", "复位后已", "复位后重新打开",
            "同步芯片", "同步结果", "握手成功", "串口已打开", "识别到芯片"
        )
        return keys.any { t.contains(it) }
    }

    /** 库/底层输出的行：完整进原始日志，屏幕上只在报错时出现 */
    private fun appendNative(text: String) {
        val act = activity ?: return
        // 库里的报错都是英文（failed / error / timeout），这类也必须上屏，
        // 否则失败原因只留在原始日志里，界面上看着像“卡住了”
        val zh = arrayOf("失败", "错误", "超时", "被拒", "异常", "断开")
        val en = arrayOf("failed", "error", "timeout", "retry", "invalid")
        // [2]/[3] 这类库内部行即使写着失败，也只是重试噪声，不上屏（原件仍在原始日志里）
        val inner = text.trim().matches(Regex("^\\[\\d+\\]\\s.*"))
        val show = !inner && (zh.any { text.contains(it) } ||
                en.any { text.lowercase().contains(it) })
        val line = "[${java.text.SimpleDateFormat("mm:ss", java.util.Locale.CHINA).format(java.util.Date())}] $text\n"
        val push = {
            rawLog.append(line)
            if (show) {
                visLog.append(line)
                pushVisible()
            }
        }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) push()
        else act.runOnUiThread { push() }
    }

    /**
     * 正在刷新的那一行（进度会动）。它单独占 logLive 这个 TextView，
     * 只在它自己身上 setText，不碰上面整块已定稿日志，避免写入时整屏闪。
     */
    private var liveTs = ""
    private fun setLiveLine(text: String) {
        if (!::logLive.isInitialized) return
        // 写入线程（DefaultDispatcher）也会调到这里，碰 View 必须切回主线程
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            logLive.post { setLiveLine(text) }
            return
        }
        if (liveTs.isEmpty()) liveTs = stampNow()
        logLive.text = liveTs + text
        if (logLive.visibility != android.view.View.VISIBLE) {
            logLive.visibility = android.view.View.VISIBLE
        }
        stickLogBottom = true
        pinLogToBottom()
    }

    /** 收尾：把这一行按 100% 定稿进主日志，清掉临时行 */
    private fun finishLiveLine(text: String) {
        if (!::logLive.isInitialized) return
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            logLive.post { finishLiveLine(text) }
            return
        }
        if (liveTs.isEmpty()) liveTs = stampNow()
        visLog.append(liveTs).append(text).append('\n')
        clearLiveLine()
        applyVisible()
    }

    private fun clearLiveLine() {
        if (::logLive.isInitialized &&
            android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            logLive.post { clearLiveLine() }
            return
        }
        liveTs = ""
        if (::logLive.isInitialized) {
            logLive.text = ""
            logLive.visibility = android.view.View.GONE
        }
    }

    private fun stampNow(): String =
        "[" + java.text.SimpleDateFormat("mm:ss", java.util.Locale.CHINA).format(java.util.Date()) + "] "

    private fun appendLog(text: String) {
        // 日志可能来自 native 读线程（link.screenSink），必须在主线程改 TextView，
        // 否则后台线程 append 与排版并发，最后那次滚动会拿到旧高度，停在半截。
        val act = activity ?: return
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            appendLogOnUi(text)
        } else {
            act.runOnUiThread { appendLogOnUi(text) }
        }
    }

    /** 屏幕上这份日志的纯文本缓冲：每次整体 setText，不做增量 append */
    private val visLog = StringBuilder()

    /** 刷新节流：进度回调很密，120ms 内最多重排一次，避免主线程被排版拖住 */
    private var lastPushAt = 0L
    private var pushPending = false

    private fun pushVisible() {
        if (!::logText.isInitialized) return
        val now = android.os.SystemClock.uptimeMillis()
        val wait = 120L - (now - lastPushAt)
        if (wait > 0) {
            if (!pushPending) {
                pushPending = true
                logText.postDelayed({ pushPending = false; applyVisible() }, wait)
            }
            return
        }
        applyVisible()
    }

    private fun applyVisible() {
        if (!::logText.isInitialized) return
        lastPushAt = android.os.SystemClock.uptimeMillis()
        logText.setText(visLog.toString())
        stickLogBottom = true
        pinLogToBottom()
    }

    private fun appendLogOnUi(text: String) {
        if (!::logText.isInitialized) return
        if (text.isBlank()) return   // 空串不占一行，避免出现只有时间的空行
        val line = "[${java.text.SimpleDateFormat("mm:ss", java.util.Locale.CHINA).format(java.util.Date())}] $text\n"
        // 原始日志全留，复制/报错时给完整版
        rawLog.append(line)
        if (rawLog.length > 80000) {
            val cut = rawLog.indexOf('\n', rawLog.length / 3)
            if (cut > 0) rawLog.delete(0, cut + 1)
        }
        // 库里的英文关键行翻成中文再上屏（Flash size: 16 MB → Flash 容量：16 MB）
        val shown = when {
            text.contains("Flash size:") -> "Flash 容量：" + text.substringAfter("Flash size:").trim()
            else -> text
        }
        // 屏幕上只留真实烧录输出：库内部的 [N] 调试行、复位/拓扑这类噪声不上屏
        if (!::logText.isInitialized || isNoise(text)) return
        // 超过 400 行就从行首裁掉前四分之一，再整体刷上去
        if (visLog.count { it == '\n' } > 500) {
            val cut = visLog.indexOf("\n", visLog.length / 4)
            if (cut > 0) visLog.delete(0, cut + 1)
        }
        visLog.append("[").append(java.text.SimpleDateFormat("mm:ss", java.util.Locale.CHINA).format(java.util.Date()))
            .append("] ").append(shown).append('\n')
        pushVisible()
    }

    /**
     * 把日志钉到最底部：排版结束后按"子视图高度 - 视口高度"滚到底，
     * 再连查两次，高度还在长（长行换行）就继续滚，保证最后一条完整贴在底边。
     */
    private fun pinLogToBottom() {
        if (!::logScroll.isInitialized) return
        fun pin(round: Int) {
            if (!stickLogBottom) return
            val child = logScroll.getChildAt(0) ?: return
            val max = maxOf(0, child.height - (logScroll.height - logScroll.paddingTop - logScroll.paddingBottom))
            if (logScroll.scrollY != max) logScroll.scrollTo(0, max)
            if (round < 3) logScroll.postDelayed({ pin(round + 1) }, 40L)
        }
        logScroll.post { pin(0) }
    }

    private fun bindLogScroll() {
        if (!::logScroll.isInitialized) return
        // 用户自己往回翻就先不抢，翻回底部后继续钉底
        logScroll.setOnScrollChangeListener { _, _, _, _, _ ->
            val child = logScroll.getChildAt(0) ?: return@setOnScrollChangeListener
            stickLogBottom = child.height - logScroll.height - logScroll.scrollY < dp(24)
        }
        logText.addOnLayoutChangeListener { _, _, _, _, bottom, _, _, _, oldBottom ->
            if (bottom != oldBottom && stickLogBottom) pinLogToBottom()
        }
    }


    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) {
        ToastKit.show(requireContext(), msg)
    }
}
