package com.espflash.burn

import android.app.AlertDialog
import android.hardware.usb.UsbDevice
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SerialFragment : Fragment(R.layout.fragment_serial) {

    private lateinit var status: TextView
    private lateinit var baudSpinner: Spinner
    private lateinit var serialLog: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var input: EditText
    private lateinit var optionsPanel: View
    private lateinit var modeGroup: android.widget.RadioGroup
    private lateinit var cbTimestamp: android.widget.CheckBox
    private lateinit var cbChunkNewline: android.widget.CheckBox
    private var tuneView: View? = null
    private var usbReceiver: android.content.BroadcastReceiver? = null
    private var pollJob: Job? = null
    private var scanning = false

    private val devices = mutableListOf<UsbDevice>()
    private var link: SerialLink? = null
    private var readerJob: Job? = null
    private var hexDisplay = false
    private var showTimestamp = false
    private var chunkNewline = false
    private var autoScroll = true
    private val timeFmt = SimpleDateFormat("mm:ss", Locale.CHINA)

    private val bauds = listOf("9600", "19200", "38400", "57600", "115200", "230400", "460800", "921600", "2000000")

    private fun currentBaud(): Int = bauds.getOrNull(baudSpinner.selectedItemPosition)?.toIntOrNull() ?: 115200

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        val v = inflater.inflate(R.layout.fragment_serial, container, false)
        status = v.findViewById(R.id.serialStatus)
        baudSpinner = v.findViewById(R.id.baudSpinner)
        serialLog = v.findViewById(R.id.serialLog)
        logScroll = v.findViewById(R.id.logScroll)
        input = v.findViewById(R.id.inputText)
        optionsPanel = v.findViewById(R.id.optionsPanel)
        modeGroup = v.findViewById(R.id.modeGroup)
        cbTimestamp = v.findViewById(R.id.cbTimestamp)
        cbChunkNewline = v.findViewById(R.id.cbChunkNewline)
        return v
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        baudSpinner.adapter = ArrayAdapter(requireContext(), R.layout.spinner_item, bauds)
        baudSpinner.setSelection(bauds.indexOf("115200"))

        // 点设备行：和烧录页一样，弹出设备列表随时切换
        status.setOnClickListener {
            PortBus.manualOff = false
            pickDeviceAndConnect()
        }

        view.findViewById<ImageButton>(R.id.clearButton).setOnClickListener { clearScreenAndBuffer() }
        // 展开期间点设置图标也走「点外部收起」逻辑，这里只负责展开，避免收起后又弹开
        tuneView = view.findViewById<ImageButton>(R.id.tuneButton)
        tuneView?.setOnClickListener {
            optionsPanel.visibility = if (optionsPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        modeGroup.setOnCheckedChangeListener { _, checkedId ->
            hexDisplay = checkedId == R.id.modeHex
            appendLine(if (hexDisplay) "切换为十六进制显示" else "切换为文本显示")
        }
        cbTimestamp.setOnCheckedChangeListener { _, checked ->
            showTimestamp = checked
            appendLine(if (checked) "时间戳显示：开" else "时间戳显示：关")
        }
        cbChunkNewline.setOnCheckedChangeListener { _, checked ->
            chunkNewline = checked
            appendLine(if (checked) "分包换行：开" else "分包换行：关")
        }
        view.findViewById<View>(R.id.sendButton).setOnClickListener { send() }

        view.findViewById<ImageButton>(R.id.moreButton).setOnClickListener { anchor ->
            MoreMenu.show(
                anchor,
                listOf(
                    MoreMenu.Item(R.drawable.ic_tune, "设置烧录参数") {
                        (activity as? MainActivity)?.openFlashTab()
                    },
                    MoreMenu.Item(R.drawable.ic_reset, "设备复位重启") { resetDevice() },
                    MoreMenu.Item(R.drawable.ic_share, "导出串口日志") { exportLog() },
                    MoreMenu.Item(R.drawable.ic_copy, "复制串口日志") { copyLog() }
                )
            )
        }

        baudSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val l = link ?: return
                PortBus.baud = currentBaud()
                if (l.setBaud(currentBaud())) {
                    appendLine("波特率切到 ${currentBaud()}")
                    updateStatus(connected = true)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                send()
                true
            } else {
                false
            }
        }

        refreshDevices()
    }

    /** Activity 派发触摸时调用：面板展开期间，点面板以外的任何地方都收起 */
    fun collapseIfOutside(ev: android.view.MotionEvent) {
        if (!this::optionsPanel.isInitialized) return
        if (optionsPanel.visibility != View.VISIBLE) return
        val loc = IntArray(2)
        optionsPanel.getLocationOnScreen(loc)
        val x = ev.rawX.toInt()
        val y = ev.rawY.toInt()
        val inside = x >= loc[0] && x <= loc[0] + optionsPanel.width &&
                y >= loc[1] && y <= loc[1] + optionsPanel.height
        if (inside) return
        val tl = IntArray(2)
        tuneView?.getLocationOnScreen(tl)
        val tw = tuneView?.width ?: 0
        val th = tuneView?.height ?: 0
        val onTune = x >= tl[0] && x <= tl[0] + tw && y >= tl[1] && y <= tl[1] + th
        if (onTune) return
        optionsPanel.visibility = View.GONE
    }

    private fun refreshDevices() {
        if (scanning) return
        scanning = true
        val ctx = requireContext().applicationContext
        lifecycleScope.launch(Dispatchers.IO) {
            val fresh = runCatching { UsbDevices.list(ctx) }.getOrDefault(emptyList())
            withContext(Dispatchers.Main) {
                scanning = false
                applyDevices(fresh)
            }
        }
    }

    private fun applyDevices(fresh: List<UsbDevice>) {
        if (!isAdded) return
        devices.clear()
        devices.addAll(fresh)
        if (link == null) updateStatus(connected = false)
        appendLine("发现 ${devices.size} 个串口设备")
        // 只有一个设备，或上次用过的那台还在，就自动连上
        if (link == null && PortBus.busyBy == null && autoTarget() != null) {
            val target = autoTarget() ?: return@applyDevices
            view?.post {
                if (link == null && PortBus.busyBy == null) {
                    appendLine("自动连接 ${UsbDevices.label(target)}")
                    connect(target, silent = true)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!::status.isInitialized) return
        refreshDevices()
        startAutoDetect()
    }

    override fun onPause() {
        super.onPause()
        stopAutoDetect()
    }

    /** 插拔 USB 立刻重扫 + 每 2 秒轮询，扫到就自动连 */
    private fun startAutoDetect() {
        if (usbReceiver == null) {
            val r = object : android.content.BroadcastReceiver() {
                override fun onReceive(ctx: android.content.Context?, intent: android.content.Intent?) {
                    scanning = false
                    // 拔插视为新的连接意图，清掉「主动断开」状态
                    PortBus.manualOff = false
                    val detach = intent?.action == android.hardware.usb.UsbManager.ACTION_USB_DEVICE_DETACHED
                    if (detach) {
                        // USB 断开：之前记下的后台 IP 作废，重新开始获取
                        SerialMirror.clear()
                        L.i("USB 断开，后台 IP 已失效")
                    }
                    // 还连着的时候不扫；拔出设备才需要重新扫
                    if (detach || link == null) refreshDevices()
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
            }.onFailure { L.w("注册 USB 广播失败", it) }
        }
        if (pollJob?.isActive == true) return
        pollJob = lifecycleScope.launch {
            while (true) {
                delay(2000)
                // 已经连上（或烧录占用中）就不再反复扫串口，避免设备列表抖动和日志刷屏
                val busy = link != null || PortBus.isOpen || PortBus.busyBy != null
                if (!busy && !scanning) refreshDevices()
            }
        }
    }

    private fun stopAutoDetect() {
        pollJob?.cancel()
        pollJob = null
        usbReceiver?.let { runCatching { requireContext().applicationContext.unregisterReceiver(it) } }
        usbReceiver = null
    }

    private fun updateStatus(connected: Boolean) {
        if (connected) {
            status.text = PortBus.label.ifEmpty { link?.deviceName ?: "已连接" } + " · 已连接"
            status.setTextColor(0xFF3ED07D.toInt())
        } else {
            status.text = when {
                PortBus.busyBy != null -> "烧录中，串口被占用"
                devices.isEmpty() -> "串口设备没有连接"
                PortBus.manualOff -> "串口设备没有连接"
                else -> "${devices.size} 个设备可用，点此选择"
            }
            status.setTextColor(0xFFC9CDD4.toInt())
        }
    }

    private fun pickDeviceAndConnect() {
        val fresh = runCatching { UsbDevices.list(requireContext()) }.getOrDefault(emptyList())
        devices.clear()
        devices.addAll(fresh)
        if (devices.isEmpty()) {
            showUsbDump()
            updateStatus(false)
            return
        }
        val labels = devices.map { UsbDevices.label(it) }
        DevicePicker.show(requireContext(), "选择串口设备", labels) { which ->
            devices.getOrNull(which)?.let { connect(it) }
        }
    }

    /** 扫不到设备时，把所有 USB 设备原样列出来，方便判断是没识别还是驱动不匹配 */
    private fun showUsbDump() {
        val dump = runCatching {
            val um = requireContext().getSystemService(android.content.Context.USB_SERVICE) as android.hardware.usb.UsbManager
            buildString {
                append("USB 主机识别到的设备数：${um.deviceList.size}\n\n")
                if (um.deviceList.isEmpty()) {
                    append("一个 USB 设备都没有：\n")
                    append("· 线是不是只能充电的线\n")
                    append("· OTG 转接头是否插紧、板子是否上电\n")
                    append("· 系统里 OTG / USB 调试是否被限制\n")
                } else {
                    um.deviceList.values.forEach { d ->
                        append("VID:${"%04X".format(d.vendorId)} PID:${"%04X".format(d.productId)}  ")
                        append("class=${d.deviceClass} 接口=${d.interfaceCount}  ")
                        append("权限=${if (um.hasPermission(d)) "有" else "无"}  ")
                        append("${runCatching { d.productName ?: d.deviceName }.getOrDefault(d.deviceName)}\n")
                    }
                    append("\n只有 VID 303A（ESP 原生 USB）/ CH340 等已知芯片会被当成串口，上面没有就意味着芯片型号不认识。")
                }
            }
        }.getOrElse { "读取 USB 信息失败：${it.message}" }

        Dialogs.message(requireContext(), "未扫到串口设备", dump, okText = "知道了") {
            refreshDevices()
            toast("已重新扫描")
        }
    }

    private fun connect(device: UsbDevice, silent: Boolean = false) {
        if (PortBus.busyBy != null) {
            if (!silent) toast("正在烧录，烧完再连串口")
            return
        }
        // 已经连着同一台设备：不重开句柄，只刷新状态，避免读线程被打断导致崩溃重启
        if (PortBus.isOpen && !PortBus.manualOff) {
            updateStatus(true)
            if (pendingResetAfterConnect) performResetNow()
            if (!silent) toast("串口已连接")
            return
        }
        val baud = currentBaud()
        status.text = "正在连接…"
        val ctx = requireContext().applicationContext
        lifecycleScope.launch(Dispatchers.IO) {
            val l = runCatching {
                PortBus.open(ctx, device, baud) { appendLine(it) }
            }.onFailure { L.w("打开串口异常", it) }.getOrNull()
            withContext(Dispatchers.Main) {
                if (l == null) {
                    if (!silent) toast("打开失败（可能被其他 App 占用）")
                    updateStatus(false)
                    return@withContext
                }
                startReader(l, device, baud)
            }
        }
    }

    /** 优先上次连过的那台；只有一台设备就用它 */
    private fun autoTarget(): UsbDevice? {
        if (PortBus.manualOff) return null
        if (devices.isEmpty()) return null
        val saved = lastUsbId()
        if (saved.isNotEmpty()) {
            devices.firstOrNull { "${it.vendorId}:${it.productId}" == saved }?.let { return it }
        }
        return if (devices.size == 1) devices[0] else null
    }

    private fun lastUsbId(): String =
        requireContext().getSharedPreferences("espburn", android.content.Context.MODE_PRIVATE)
            .getString("lastUsb", "") ?: ""

    private fun rememberUsb(device: UsbDevice) {
        requireContext().getSharedPreferences("espburn", android.content.Context.MODE_PRIVATE)
            .edit().putString("lastUsb", "${device.vendorId}:${device.productId}").apply()
    }

    /** 从烧录成功弹窗切过来时置位：连上就复位一次，保证能抓到芯片启动的完整输出 */
    private var pendingResetAfterConnect = false

    /** 外部入口：切到本页后自动连接，连上立刻做一次复位重启 */
    fun openWithReset() {
        pendingResetAfterConnect = true
        if (link != null) {
            performResetNow()
        } else {
            val target = autoTarget()
            if (target != null) connect(target, silent = true)
        }
    }

    /** 清屏：屏幕内容 + 中途缓冲 + 串口日志缓存一起清，避免清完又冒出旧内容 */
    private fun clearScreenAndBuffer() {
        serialLog.text = ""
        SerialMirror.clear()
        runCatching { link?.purgeInput() }
    }

    /** 清空旧日志再复位：这样屏幕上只剩这次启动的输出，不是上一次的残留 */
    private fun performResetNow() {
        val l = link ?: return
        pendingResetAfterConnect = false
        clearScreenAndBuffer()
        appendLine("—— 设备复位重启，等待启动输出 ——")
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                l.setControlLinesIdx(false, false, 0)
                delay(60)
                l.setControlLinesIdx(false, true, 0)     // RTS=1 → EN 拉低
                delay(120)
                l.setControlLinesIdx(false, false, 0)    // 释放，正常启动
            }.onFailure { L.w("串口页复位失败", it) }
        }
    }

    private fun startReader(l: SerialLink, device: UsbDevice, baud: Int) {
        rememberUsb(device)
        link = l
        updateStatus(connected = true)
        appendLine("已连接 ${UsbDevices.label(device)} @ $baud")
        if (pendingResetAfterConnect) performResetNow()

        readerJob = lifecycleScope.launch(Dispatchers.IO) {
            val buf = ByteArray(4096)
            val sb = StringBuilder()
            var lastFlush = 0L
            while (true) {
                val n = runCatching { l.readAny(buf, buf.size, 60) }.getOrDefault(-1)
                if (n < 0) {
                    if (sb.isNotEmpty()) {
                        val left = sb.toString(); sb.setLength(0)
                        withContext(Dispatchers.Main) { appendToScreen(formatChunk(left)) }
                    }
                    withContext(Dispatchers.Main) {
                        appendLine("串口已断开")
                        disconnect()
                    }
                    break
                }
                if (n > 0) {
                    if (hexDisplay) {
                        sb.append(buf.copyOfRange(0, n).joinToString(" ") { String.format("%02X", it) })
                        sb.append(' ')
                    } else {
                        sb.append(String(buf, 0, n, Charsets.UTF_8))
                    }
                    val now = SystemClock.uptimeMillis()
                    if (sb.length >= 1536 || now - lastFlush >= 100) {
                        val chunk = sb.toString()
                        sb.setLength(0)
                        lastFlush = now
                        withContext(Dispatchers.Main) { appendToScreen(formatChunk(chunk)) }
                    }
                }
            }
        }
    }

    /** 按「时间戳显示 / 分包换行」两个勾选框加工接收内容 */
    /** DTR/RTS 拉一个复位时序，等价于按一下 EN 键 */
    private fun resetDevice() {
        val l = link ?: run { toast("先连接串口"); return }
        if (PortBus.busyBy != null) { toast("正在烧录，稍后再说"); return }
        clearScreenAndBuffer()
        runCatching {
            l.setDtr(false)
            l.setRts(true)
            Thread.sleep(120)
            l.setRts(false)
            Thread.sleep(50)
            l.setDtr(true)
        }.onFailure { toast("复位失败：${it.message}") ; return }
        toast("已发送复位")
    }

    private fun formatChunk(raw: String): String {
        val stamp = "[" + timeFmt.format(Date()) + "] "
        var s = raw
        if (cbChunkNewline.isChecked && !s.endsWith("\n")) s += "\n"
        if (cbTimestamp.isChecked) {
            val parts = s.split("\n")
            s = parts.mapIndexed { i, line ->
                if (line.isEmpty()) line else stamp + line
            }.joinToString("\n").let { it }
        }
        return s
    }

    private fun disconnect() {
        readerJob?.cancel()
        readerJob = null
        PortBus.close()
        link = null
        if (::status.isInitialized) updateStatus(connected = false)
    }

    private fun send() {
        val l = link ?: run {
            toast("先连接串口")
            return
        }
        val text = input.text.toString()
        if (text.isEmpty()) return
        val bytes = if (hexDisplay) {
            val hex = text.replace(" ", "").replace("\n", "")
            if (hex.length % 2 != 0 || hex.any { Character.digit(it, 16) < 0 }) {
                toast("HEX 格式不对")
                return
            }
            ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        } else {
            (text + "\n").toByteArray(Charsets.UTF_8)
        }
        val ok = runCatching { l.writeAll(bytes, bytes.size, 500) }.getOrDefault(false)
        if (ok) {
            val echo = if (hexDisplay) bytes.joinToString(" ") { String.format("%02X", it) } else text
            appendToScreen("→ $echo\n")
            input.setText("")
        } else {
            appendLine("发送失败")
        }
    }

    /**
     * ⋮ 菜单「复制串口日志」：从屏幕最底部那条（最新）往上数 60 条。
     * 日志是按时间追加的，所以取末尾 50 行再按时间正序排回去，
     * 复制出来的顺序和屏幕上从上到下的阅读顺序一致。
     */
    private fun copyLog() {
        val all = serialLog.text?.toString().orEmpty().trimEnd()
        if (all.isEmpty()) {
            toast("还没有串口日志")
            return
        }
        val lines = all.split("\n")
        val tail = lines.takeLast(60).joinToString("\n")
        val cm = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
        runCatching { cm.setPrimaryClip(android.content.ClipData.newPlainText("ESPburn 串口日志", tail)) }
            .onSuccess {
                val n = lines.size.coerceAtMost(60)
                toast("已复制最近 $n 条串口日志（${tail.length} 字）")
            }
            .onFailure { toast("复制失败：${it.message}") }
    }

    private fun exportLog() {
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.CHINA).format(Date())
        // 导出的就是屏幕上这份串口日志，不夹带内部文件日志
        val text = serialLog.text.toString()
        val ctx = requireContext()
        if (!CrashStore.hasStorageRoot()) {
            toast("需要「所有文件访问」权限才能导出到 ${CrashStore.DIR_SERIAL}")
            runCatching {
                startActivity(
                    android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        android.net.Uri.parse("package:" + ctx.packageName)
                    )
                )
            }
            return
        }
        val saved = CrashStore.saveToDir(CrashStore.DIR_SERIAL, "串口日志-$ts.txt", text)
        toast(if (saved != null) "已保存：$saved" else "保存失败")
    }

    /** 时间戳颜色跟着日志等级走：E 红、W 黄、I 蓝、D 灰、V 紫 */
    private fun tsColor(level: Char): Int = when (level) {
        'E', 'A' -> 0xFFF4756B.toInt()
        'W' -> 0xFFFFD400.toInt()
        'I' -> 0xFFAFC3F7.toInt()
        'D' -> 0xFF8E8E9A.toInt()
        'V' -> 0xFFA78BFA.toInt()
        else -> 0xFFAFC3F7.toInt()
    }

    /** 上屏：去掉行首的等级字母，(时间戳) 按等级上色，括号本身灰白 */
    private fun appendWithBlueStart(text: CharSequence) {
        val cur = serialLog.text
        val raw = text.toString()
        val sb = android.text.SpannableStringBuilder()
        var pos = 0
        var lineStart = cur.isEmpty() || cur[cur.length - 1] == '\n'
        while (pos < raw.length) {
            val nl = raw.indexOf('\n', pos).let { if (it < 0) raw.length else it }
            var line = raw.substring(pos, nl)
            var level = ' '
            if (lineStart && line.length > 3 && line[1] == ' ' && line[2] == '(' &&
                line[0].uppercaseChar() in "IWEVDAG") {
                level = line[0].uppercaseChar()
                line = line.substring(2)
            }
            val base = sb.length
            sb.append(line)
            val open = line.indexOf('(')
            val close = line.indexOf(')')
            if (open >= 0 && close > open && close - open <= 12) {
                sb.setSpan(android.text.style.ForegroundColorSpan(0xFFCFCFD8.toInt()),
                    base, base + open + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(android.text.style.ForegroundColorSpan(tsColor(level)),
                    base + open + 1, base + close, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(android.text.style.ForegroundColorSpan(0xFFCFCFD8.toInt()),
                    base + close, base + close + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (nl < raw.length) sb.append('\n')
            lineStart = nl < raw.length
            pos = nl + 1
        }
        serialLog.append(sb)
    }

    private fun appendRaw(text: String) {
        appendWithBlueStart(text)
        if (serialLog.lineCount > 900) {
            val raw = serialLog.text.toString()
            // 从换行边界切，保证屏幕上第一条永远是完整的一行
            serialLog.text = cutAtLineBoundary(raw, raw.length / 4)
        }
        if (autoScroll) logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    /** 系统提示只进内部日志文件，不上屏：屏幕上只留串口收到数据和发出去的内容 */
    private fun appendLine(text: String) {
        L.i(text)
    }

    /** 只有「串口收到的数据 / 我发出去的内容」才写进接收区 */
    private fun appendToScreen(text: String) {
        if (text.isEmpty()) return
        SerialMirror.feed(text)
        appendWithBlueStart(text)
        // 只保留最近的内容，避免长日志把 UI 拖死
        val len = serialLog.length()
        if (len > 60000) {
            val editable = serialLog.editableText
            val cut = lineBoundary(editable.toString(), len - 30000)
            editable.delete(0, cut)
        }
        if (autoScroll) logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    /** 从 cut 往后找第一个换行，返回下一个行首位置；找不到就回到 cut */
    private fun lineBoundary(text: String, cut: Int): Int {
        if (cut <= 0) return 0
        if (cut >= text.length) return text.length
        val nl = text.indexOf('\n', cut)
        return if (nl >= 0) nl + 1 else cut
    }

    private fun cutAtLineBoundary(text: String, cut: Int): String =
        text.substring(lineBoundary(text, cut))

    private fun toast(msg: String) {
        ToastKit.show(requireContext(), msg)
    }

    override fun onDestroyView() {
        stopAutoDetect()
        disconnect()
        super.onDestroyView()
    }
}
