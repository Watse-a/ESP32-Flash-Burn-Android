package com.espflash.burn

import android.app.AlertDialog
import android.hardware.usb.UsbDevice
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.ScrollView
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
    private lateinit var serialLog: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var input: EditText
    private lateinit var logTitle: TextView
    private var usbReceiver: android.content.BroadcastReceiver? = null
    private var pollJob: Job? = null
    private var scanning = false

    private val devices = mutableListOf<UsbDevice>()
    private var link: SerialLink? = null
    private var readerJob: Job? = null
    private var baudRate = 115200
    private var autoScroll = true

    private lateinit var sendRow: View
    private lateinit var netHeaderBar: View
    private lateinit var netHeaderText: TextView

    /** 网络日志模式：true 时标题为「网络日志」，日志区只显示端口收到的内容 */
    private var netMode = false
    private var net: NetLog? = null
    private var netHost = ""
    private var netPort = 8266
    /** 网络链路是否真的通了（TCP 连上 / UDP 监听中）；决定状态行显示地址还是「网络设备未连接」 */
    private var netUp = false
    private var netConnecting = false

    /** 本次插入是否已经自动尝试过连接：试过一次就不再重复，拔线后重置 */
    private var attachPrompted = false

    /** 正在打开串口（含后台静默重连）：期间不再插连接框，避免确认框和系统授权框堆叠 */
    @Volatile private var connecting = false

    /** 本轮插入内静默自动连的重试次数：首开偶尔失败（接口未释放/授权时序），自动补一次 */
    private var connectAttempts = 0

    private val bauds = listOf("9600", "19200", "38400", "57600", "115200", "230400", "460800", "921600")

    private fun currentBaud(): Int = baudRate

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        val v = inflater.inflate(R.layout.fragment_serial, container, false)
        status = v.findViewById(R.id.serialStatus)
        serialLog = v.findViewById(R.id.serialLog)
        logScroll = v.findViewById(R.id.logScroll)
        input = v.findViewById(R.id.inputText)
        logTitle = v.findViewById(R.id.logTitle)
        sendRow = v.findViewById(R.id.sendRow)
        netHeaderBar = v.findViewById(R.id.netHeaderBar)
        netHeaderText = v.findViewById(R.id.netHeaderText)
        return v
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 波特率从偏好里恢复，默认 115200
        baudRate = prefs().getInt("serialBaud", 115200)
        if (baudRate !in bauds.mapNotNull { it.toIntOrNull() }) {
            baudRate = 115200
            prefs().edit().putInt("serialBaud", baudRate).apply()
        }

        // 点状态行：串口模式＝选设备；网络模式＝重新配置 IP 和端口
        status.setOnClickListener {
            if (netMode) {
                showNetDialog()
                return@setOnClickListener
            }
            PortBus.manualOff = false
            pickDeviceAndConnect()
        }

        // 网络日志标题栏同样点一下就能重选 IP / 端口
        netHeaderBar.setOnClickListener { if (netMode) showNetDialog() }

        // 点标题：串口日志 ⇄ 网络日志互相切换；进网络日志时弹 IP:端口 配置框
        logTitle.setOnClickListener { onTitleClick() }

        view.findViewById<ImageButton>(R.id.clearButton).setOnClickListener { clearScreenAndBuffer() }

        view.findViewById<View>(R.id.sendButton).setOnClickListener { send() }

        view.findViewById<ImageButton>(R.id.moreButton).setOnClickListener { anchor ->
            // 菜单跟着模式走：网络日志模式下只留复制/导出，串口参数与复位对网络没意义
            val name = if (netMode) "网络日志" else "串口日志"
            val items = mutableListOf<MoreMenu.Item>()
            if (!netMode) {
                items.add(MoreMenu.Item(R.drawable.ic_tune, "设置串口参数") { showSerialParams() })
                items.add(MoreMenu.Item(R.drawable.ic_reset, "设备复位重启") { resetDevice() })
            }
            items.add(MoreMenu.Item(R.drawable.ic_share, "导出$name") { exportLog() })
            items.add(MoreMenu.Item(R.drawable.ic_copy, "复制$name") { copyLog() })
            MoreMenu.show(anchor, items)
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

    private fun prefs() = requireContext()
        .getSharedPreferences("espburn", android.content.Context.MODE_PRIVATE)

    /** 芯片预设：直接给好波特率和线路格式，省得每次手动挑 */
    private data class ChipPreset(val name: String, val baud: Int, val note: String)

    private val presets = listOf(
        ChipPreset("ESP32 调试口", 115200, "ESP 系列串口日志默认 115200"),
        ChipPreset("全志 H3 调试口", 115200, "友善 NanoPi NEO / NEO Core / M1 的 Debug UART，8N1")
    )

    /** ⋮ 菜单「设置串口参数」：先给芯片预设，再留给手动挑波特率 */
    private fun showSerialParams() {
        val curPreset = prefs().getString("serialPreset", "").orEmpty()
        fun label(p: ChipPreset) =
            "芯片预设：${p.name}" + if (curPreset == p.name) "（当前）" else ""
        val rows = ArrayList<String>()
        rows.add("手动选择波特率：当前 $baudRate")
        rows.add(label(presets[0]))
        rows.add(label(presets[1]))
        Dialogs.list(requireContext(), "设置串口参数", rows) { idx ->
            when (idx) {
                0 -> showBaudList()
                else -> {
                    val p = presets[idx - 1]
                    applyBaud(p.baud, preset = p.name)
                    toast("${p.name}：${p.baud} 8N1，${p.note}")
                }
            }
        }
    }

    /** 手动挑波特率：选中就立刻下发 */
    private fun showBaudList() {
        val items = bauds.map { if (it.toIntOrNull() == baudRate) "$it（当前）" else it }
        Dialogs.list(requireContext(), "选择波特率", items) { idx ->
            val b = bauds.getOrNull(idx)?.toIntOrNull() ?: return@list
            applyBaud(b, preset = "")
        }
    }

    /** 统一切波特率：存偏好、下发到已打开的串口 */
    private fun applyBaud(b: Int, preset: String) {
        baudRate = b
        PortBus.baud = b
        prefs().edit().putInt("serialBaud", b).putString("serialPreset", preset).apply()
        val l = link
        when {
            l == null -> toast("波特率设为 $b，连接时生效")
            l.setBaud(b) -> {
                toast("波特率切到 $b")
                updateStatus(connected = true)
            }
            else -> toast("切换波特率失败")
        }
    }

    /** Activity 派发触摸时调用：设置面板已经删掉，这里保留空实现兼容旧调用 */
    fun collapseIfOutside(ev: android.view.MotionEvent) {
        // 串口页已无展开面板，无需收起
    }

    /** 标题点击：串口日志 → 弹 IP:端口 配置框；网络日志 → 再点一下直接切回串口 */
    private fun onTitleClick() {
        if (netMode) {
            backToSerial(announce = true)
        } else {
            showNetDialog()
        }
    }

    private fun refreshTitle() {
        logTitle.text = if (netMode) "网络日志" else "串口日志"
    }

    private fun modeName(): String = if (netMode) "网络日志" else "串口日志"

    /** 网络日志配置：IP 与端口放在同一行，中间一个冒号 */
    private fun showNetDialog() {
        val ctx = requireContext()
        val savedIp = prefs().getString("netIp", "").orEmpty()
            .takeIf { it.isNotBlank() && it != "0.0.0.0" }.orEmpty()
        val savedPort = prefs().getInt("netPort", 8266)

        val ipInput = EditText(ctx).apply {
            hint = "IP 地址"
            setHintTextColor(0xFF6E6A7E.toInt())
            setText(savedIp.ifEmpty { SerialMirror.lockedIp() ?: "192.168.4.1" })
            setTextColor(0xFFE9E6F4.toInt())
            textSize = 15f
            setBackgroundResource(R.drawable.bg_field)
            setPadding(dp(14), 0, dp(10), 0)
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        val colon = TextView(ctx).apply {
            text = ":"
            setTextColor(0xFFC9CDD4.toInt())
            textSize = 17f
            setPadding(dp(8), 0, dp(8), 0)
        }
        val portInput = EditText(ctx).apply {
            hint = "端口"
            setHintTextColor(0xFF6E6A7E.toInt())
            setText(savedPort.toString())
            setTextColor(0xFFE9E6F4.toInt())
            textSize = 15f
            setBackgroundResource(R.drawable.bg_field)
            setPadding(dp(12), 0, dp(12), 0)
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }

        val row = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        row.addView(ipInput, android.widget.LinearLayout.LayoutParams(0, dp(46), 1f))
        row.addView(colon)
        row.addView(portInput, android.widget.LinearLayout.LayoutParams(dp(96), dp(46)))

        val udpOnly = prefs().getBoolean("netUdpOnly", false)
        val sw = androidx.appcompat.widget.SwitchCompat(ctx).apply {
            isChecked = udpOnly
            text = "仅 UDP 监听"
            setTextColor(0xFFE9E6F4.toInt())
            textSize = 14f
            switchPadding = dp(10)
        }
        val switchRow = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(sw)
        }

        val box = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(row, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(switchRow, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(12)
            })
        }

        Dialogs.custom(ctx, "网络日志", box, okText = "开始监听") {
            val ip = ipInput.text.toString().trim()
            val port = portInput.text.toString().trim().toIntOrNull() ?: -1
            if (ip.isNotEmpty() && !isIpv4(ip)) {
                toast("IP 地址格式不对，例如 192.168.4.1")
                return@custom
            }
            if (port !in 1..65535) {
                toast("端口要填 1–65535 之间的数字")
                return@custom
            }
            prefs().edit()
                .putString("netIp", ip)
                .putInt("netPort", port)
                .putBoolean("netUdpOnly", sw.isChecked)
                .apply()
            startNet(ip, port, sw.isChecked)
        }
    }

    private fun isIpv4(ip: String): Boolean =
        Regex("^((25[0-5]|2[0-4]\\d|1\\d\\d|\\d{1,2})\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|\\d{1,2})$")
            .matches(ip)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** 切到网络日志：清屏 → 标题改名 → 收起发送区 → 开始收数据 */
    private fun startNet(host: String, port: Int, udpOnly: Boolean = false) {
        stopNet()
        netMode = true
        netHost = host
        netPort = port
        refreshTitle()
        sendRow.visibility = View.GONE
        clearScreenAndBuffer()
        netHeaderText.text = "网络日志 ${host.ifEmpty { "任意来源" }}:$port"
        netHeaderBar.visibility = View.VISIBLE
        netUp = false
        netConnecting = true
        updateStatus(connected = link != null)

        val nl = NetLog(
            host = host,
            port = port,
            udpOnly = udpOnly,
            onData = { text -> runOnUi { if (netMode) appendToScreen(text, fromSerial = false) } },
            onState = { s ->
                runOnUi { if (netMode) onNetState(s) }
            }
        )
        net = nl
        nl.start()
    }

    private fun stopNet() {
        net?.stop()
        net = null
    }

    /** 回到串口模式：标题、发送区、状态行一起复原 */
    private fun backToSerial(announce: Boolean) {
        stopNet()
        netMode = false
        netUp = false
        netConnecting = false
        refreshTitle()
        if (::sendRow.isInitialized) sendRow.visibility = View.VISIBLE
        if (::netHeaderBar.isInitialized) netHeaderBar.visibility = View.GONE
        updateStatus(connected = link != null)
        if (announce) {
            clearScreenAndBuffer()
            appendToScreen("—— 已切回串口日志 ——\n")
        }
    }

    /** TCP 连上 / UDP 监听中 → 状态行显示 地址:端口；失败或断开 → 网络设备未连接 */
    private fun onNetState(s: String) {
        when {
            s.startsWith("TCP 已连上") || s.startsWith("UDP 监听") -> {
                netUp = true
                netConnecting = false
            }
            s.contains("打不开") || s.contains("断开") || s.contains("关闭") ||
                s.contains("结束") || s.contains("停止") -> {
                netUp = false
                netConnecting = false
            }
        }
        updateStatus(connected = link != null)
        appendToScreen("· $s\n", fromSerial = false)
    }

    /** 状态行里的地址：IP 留空时只显示 :端口 */
    private fun netAddr(): String = if (netHost.isBlank()) ":$netPort" else "$netHost:$netPort"

    private fun runOnUi(block: () -> Unit) {
        val v = view ?: return
        v.post { if (isAdded && view != null) runCatching { block() } }
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
        // ROM 下载模式的设备只给烧录用，串口页不连
        val connectable = devices.filter { !UsbDevices.isRomMode(it) }
        // 上一轮若异常遗留了「连接中」标记，这里复位，避免一直挡住自动连接
        if (link == null && PortBus.busyBy == null) connecting = false
        // 插线后不再弹我方确认框（系统 USB 授权框已经问过一次）：单台/上次连过的设备直接静默连上；
        // 多台且没记过的不自动连，用户点状态行手动选。只有真拿到目标设备才标记本轮已尝试，
        // 避免首扫没扫到、后两轮询才出现设备时，名额被提前消耗掉而一直不连。
        if (link == null && PortBus.busyBy == null && !netMode && !attachPrompted && !connecting && connectable.isNotEmpty()) {
            val target = autoTarget()
            if (target != null) {
                attachPrompted = true
                view?.post {
                    if (isAdded && link == null && PortBus.busyBy == null && !netMode && !connecting) {
                        connect(target, silent = true)
                    }
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
                    // 拔插视为新的连接意图，清掉「主动断开」状态，下次插入重新弹一次连接
                    PortBus.manualOff = false
                    attachPrompted = false
                    connectAttempts = 0
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
        // 网络日志模式：状态行只反映网络链路，设备扫描不能把它改回串口文案
        if (netMode) {
            status.text = when {
                netUp -> netAddr()
                netConnecting -> "正在连接 ${netAddr()}"
                else -> "网络设备未连接"
            }
            status.setTextColor(if (netUp) 0xFF3ED07D.toInt() else 0xFFC9CDD4.toInt())
            return
        }
        if (connected) {
            status.text = PortBus.label.ifEmpty { link?.deviceName ?: "已连接" } + " · 已连接"
            status.setTextColor(0xFF3ED07D.toInt())
        } else {
            val avail = devices.count { !UsbDevices.isRomMode(it) }
            status.text = when {
                PortBus.busyBy != null -> "烧录中，串口被占用"
                avail == 0 || PortBus.manualOff -> "串口设备未连接"
                else -> "串口设备未连接 · $avail 个可用，点此选择"
            }
            status.setTextColor(0xFFC9CDD4.toInt())
        }
    }

    private fun pickDeviceAndConnect() {
        val fresh = runCatching { UsbDevices.list(requireContext()) }.getOrDefault(emptyList())
        devices.clear()
        devices.addAll(fresh)
        val candidates = devices.filter { !UsbDevices.isRomMode(it) }
        if (candidates.isEmpty()) {
            showUsbDump()
            updateStatus(false)
            return
        }
        val labels = candidates.map { UsbDevices.label(it) }
        DevicePicker.show(requireContext(), "选择串口设备", labels) { which ->
            candidates.getOrNull(which)?.let { connect(it) }
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
        // 已有一轮打开在进行：不重复发起，避免两套授权框/连接框堆叠
        if (connecting) {
            L.i("串口正在连接中，跳过重复连接请求")
            return
        }
        val baud = currentBaud()
        connecting = true
        status.text = "正在连接…"
        val ctx = requireContext().applicationContext
        lifecycleScope.launch(Dispatchers.IO) {
            val l = runCatching {
                PortBus.open(ctx, device, baud) { appendLine(it) }
            }.onFailure { L.w("打开串口异常", it) }.getOrNull()
            withContext(Dispatchers.Main) {
                connecting = false
                if (l == null) {
                    val reason = PortBus.lastFailReason.ifEmpty { "接口打不开（被占用或未授权）" }
                    L.w("串口打开失败：${UsbDevices.label(device)} 第 ${connectAttempts + 1} 次，$reason")
                    // 首开偶尔因接口还没释放/授权时序失败，静默模式下自动补一次（每轮插入最多重试 1 次）；
                    // 这一次失败不弹提示，避免最后连成功了还闪一条失败 toast。
                    val willRetry = silent && connectAttempts < 1
                    if (!willRetry) toast("串口打开失败：$reason")
                    updateStatus(false)
                    if (willRetry) {
                        connectAttempts++
                        view?.postDelayed({
                            if (isAdded && link == null && PortBus.busyBy == null && !netMode && !connecting) {
                                connect(device, silent = true)
                            }
                        }, 1000)
                    }
                    return@withContext
                }
                connectAttempts = 0
                startReader(l, device, baud)
            }
        }
    }

    /** 优先上次连过的那台；只有一台设备就用它 */
    private fun autoTarget(): UsbDevice? {
        if (PortBus.manualOff) return null
        val candidates = devices.filter { !UsbDevices.isRomMode(it) }
        if (candidates.isEmpty()) return null
        val saved = lastUsbId()
        if (saved.isNotEmpty()) {
            candidates.firstOrNull { "${it.vendorId}:${it.productId}" == saved }?.let { return it }
        }
        return if (candidates.size == 1) candidates[0] else null
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

    /** 烧录结束后外部调用：没连着就静默自动连上一台可用设备，不必等用户点弹窗按钮 */
    fun autoConnectIfIdle() {
        if (netMode || link != null || PortBus.busyBy != null || connecting) return
        connecting = true
        lifecycleScope.launch(Dispatchers.IO) {
            val fresh = runCatching { UsbDevices.list(requireContext()) }.getOrDefault(emptyList())
            withContext(Dispatchers.Main) {
                if (!isAdded || netMode || link != null || PortBus.busyBy != null) {
                    connecting = false
                    return@withContext
                }
                devices.clear()
                devices.addAll(fresh)
                val target = autoTarget()
                if (target == null) {
                    connecting = false
                    return@withContext
                }
                // connect 内部会再置一次 connecting 并在结束时复位，这里先交还控制权
                connecting = false
                connect(target, silent = true)
            }
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
                        withContext(Dispatchers.Main) { appendToScreen(left) }
                    }
                    withContext(Dispatchers.Main) {
                        appendLine("串口已断开")
                        disconnect()
                    }
                    break
                }
                if (n > 0) {
                    sb.append(String(buf, 0, n, Charsets.UTF_8))
                    val now = SystemClock.uptimeMillis()
                    if (sb.length >= 1536 || now - lastFlush >= 100) {
                        val chunk = sb.toString()
                        sb.setLength(0)
                        lastFlush = now
                        withContext(Dispatchers.Main) { appendToScreen(chunk) }
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

    private fun disconnect() {
        readerJob?.cancel()
        readerJob = null
        connecting = false
        PortBus.close()
        link = null
        if (::status.isInitialized) updateStatus(connected = false)
    }

    private fun send() {
        if (netMode) {
            toast("网络日志模式下不发送串口数据")
            return
        }
        val l = link ?: run {
            toast("先连接串口")
            return
        }
        val text = input.text.toString()
        if (text.isEmpty()) return
        val bytes = (text + "\n").toByteArray(Charsets.UTF_8)
        val ok = runCatching { l.writeAll(bytes, bytes.size, 500) }.getOrDefault(false)
        if (ok) {
            appendToScreen("→ $text\n")
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
            toast("还没有${modeName()}")
            return
        }
        val lines = all.split("\n")
        val tail = lines.takeLast(60).joinToString("\n")
        val cm = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
        runCatching { cm.setPrimaryClip(android.content.ClipData.newPlainText("ESPburn ${modeName()}", tail)) }
            .onSuccess {
                val n = lines.size.coerceAtMost(60)
                toast("已复制最近 $n 条${modeName()}（${tail.length} 字）")
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
        val saved = CrashStore.saveToDir(CrashStore.DIR_SERIAL, "${modeName()}-$ts.txt", text)
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

    /** 上屏：去掉行首的等级字母，括号里的毫秒数换算成时间，按等级上色，括号本身灰白 */
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
            // 括号里是 ESP-IDF 开机毫秒数（如 I (398)），换成 mm:ss.mmm 更好读
            val open = line.indexOf('(')
            val close = line.indexOf(')')
            if (open >= 0 && close > open && close - open <= 12) {
                val digits = line.substring(open + 1, close)
                val ms = digits.toLongOrNull()
                if (ms != null) {
                    line = line.substring(0, open + 1) + msToClock(ms) + line.substring(close)
                }
            }
            val base = sb.length
            sb.append(line)
            val o = line.indexOf('(')
            val c = line.indexOf(')')
            if (o >= 0 && c > o && c - o <= 16) {
                sb.setSpan(android.text.style.ForegroundColorSpan(0xFFCFCFD8.toInt()),
                    base, base + o + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(android.text.style.ForegroundColorSpan(tsColor(level)),
                    base + o + 1, base + c, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(android.text.style.ForegroundColorSpan(0xFFCFCFD8.toInt()),
                    base + c, base + c + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (nl < raw.length) sb.append('\n')
            lineStart = nl < raw.length
            pos = nl + 1
        }
        serialLog.append(sb)
    }

    /** 开机毫秒数 → 00:00.39 / 1:02:03.45（毫秒只留两位，按 10ms 取整） */
    private fun msToClock(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        val centi = (ms % 1000) / 10
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d.%02d", h, m, s, centi)
        else String.format(Locale.US, "%02d:%02d.%02d", m, s, centi)
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

    /** 只有「串口收到的数据 / 我发出去的内容」才写进接收区；网络模式下串口内容不上屏 */
    private fun appendToScreen(text: String, fromSerial: Boolean = true) {
        if (text.isEmpty()) return
        SerialMirror.feed(text)
        if (fromSerial && netMode) return
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
        stopNet()
        disconnect()
        super.onDestroyView()
    }
}
