package com.espflash.burn

import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment

class MainActivity : AppCompatActivity() {

    private lateinit var flash: FlashFragment
    private lateinit var logPage: SerialFragment
    private var current: Fragment? = null

    private var webWrap: android.widget.FrameLayout? = null
    private var webView: android.webkit.WebView? = null
    private var webLoaded = false
    private var lastBackTap = 0L
    /** USB 拔掉后锁住「后台」：点了完全没反应，插回设备才解锁 */
    private var webLocked = false
    private val webHandler = android.os.Handler(android.os.Looper.getMainLooper())
    /** 当前已经加载的地址；采集窗口内如果出现更合适的地址，就换过去 */
    private var webIp: String? = null
    /** 首次识别到 IP 的时间戳；识别后留 8 秒预热，等设备 Web 服务起来再放开后台按钮 */
    private var webIpAt = 0L
    private var webIpStamped: String? = null
    private val webWarmupMs = 8_000L
    /** 主框架这一轮加载失败过：此时即使收到 onPageFinished 也是错误页，不算打开成功 */
    private var mainFrameFailed = false
    /** 加载失败后自动重试，直到页面真的打开 */
    private var retryLeft = 0
    private var pageOk = false
    /** 后台探测线程的代次，换地址/收起网页就作废旧探测 */
    private var probeGen = 0
    /** 网页里「选择文件」按钮的回调，等系统选择器返回后把结果交还给 WebView */
    private var fileChooserCallback: android.webkit.ValueCallback<Array<android.net.Uri>>? = null
    private val fileChooser = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { r ->
        val cb = fileChooserCallback
        fileChooserCallback = null
        val result = if (r.resultCode == RESULT_OK) {
            android.webkit.WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data)
        } else null
        runCatching { cb?.onReceiveValue(result) }
    }
    /** 预热 8 秒到点后，自动把后台按钮恢复成可点外观 */
    private val warmupUnlock = Runnable { markBackLocked(false) }

    /** 每秒看一次镜像里有没有 IP：识别到就开始 8 秒预热计时，不必等用户先点按钮 */
    private val ipWatch = object : Runnable {
        override fun run() {
            mirroredIp()
            webHandler.postDelayed(this, 1000)
        }
    }

    private val webRetry = object : Runnable {
        override fun run() {
            if (pageOk || retryLeft <= 0) return
            val ip = webIp ?: return
            retryLeft--
            L.i("后台网页没通，重试（剩 $retryLeft 次）")
            showWebHint("正在重新打开 http://$ip/ …\n（还剩 $retryLeft 次）")
            loadUrlDirect("http://$ip/")
            if (retryLeft > 0) webHandler.postDelayed(this, 800)
        }
    }
    private val webPoll = object : Runnable {
        override fun run() {
            if (webView == null) return
            val ip = if (SerialMirror.windowOpenNow()) SerialMirror.findSta() else SerialMirror.findIp()
            // 只有地址真的变了才切；切到别的页签不重载、不黑屏
            if (ip != null && ip != webIp) {
                loadWeb(ip)
            }
            webHandler.postDelayed(this, 1500)
        }
    }

    private val onColor = 0xFFE8EDF6.toInt()
    private val offColor = 0xFF8E949C.toInt()

    private fun showLastCrashIfAny() {
        val f = java.io.File(filesDir, "last_crash.txt")
        if (!f.exists()) return
        val text = runCatching { f.readText() }.getOrDefault("")
        f.delete()
        Dialogs.message(this, "上次启动崩溃了",
            text.take(3000) + "\n\n完整报告已追加到：\n" + CrashStore.BUG_FILE, okText = "知道了")
    }

    /** 启动阶段异常时不闪退，直接把错误显示出来，方便截图取证 */
    private fun showFatal(t: Throwable) {
        val report = runCatching { CrashStore.crashReport(this, Thread.currentThread(), t) }
            .getOrDefault(android.util.Log.getStackTraceString(t))
        // 全应用只留一个日志文件：崩溃报告追加进 Bug.txt
        val saved = runCatching { CrashStore.appendBug(report + "\n") }.getOrNull()

        val tv = TextView(this).apply {
            text = buildString {
                append("启动失败：\n\n")
                append(android.util.Log.getStackTraceString(t))
                append("\n\n报告已保存到：\n")
                append(saved ?: (CrashStore.PUBLIC_HINT + "（写入失败）"))
                append("\n\n把这段截图发给我即可定位。")
            }
            setTextIsSelectable(true)
            textSize = 12f
            setPadding(40, 60, 40, 60)
        }
        val sv = ScrollView(this).apply {
            addView(tv, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        setContentView(sv)
    }

    /** 供其它页面跳转到串口日志页 */
    fun openLogTab() {
        runCatching { selectTab(false) }
    }

    /** 烧录成功后点「打开串口助手」：切到串口页，连上后复位一次设备 */
    fun openLogTabWithReset() {
        runCatching {
            selectTab(false)
            logPage.openWithReset()
        }
    }

    /** 烧录结束后让串口页在后台静默重连，不必等用户点成功弹窗里的按钮 */
    fun reconnectSerialAfterFlash() {
        runCatching { if (::logPage.isInitialized) logPage.autoConnectIfIdle() }
    }

    /** 供其它页面跳转到固件烧录页 */
    fun openFlashTab() {
        runCatching { selectTab(true) }
    }

    /** 未选中的页签只留图标，选中的才显示文字 */
    private fun selectTab(isFlash: Boolean) {
        runCatching { applyTab(isFlash) }.onFailure { L.w("切换页签失败", it) }
    }

    private fun applyTab(isFlash: Boolean) {
        hideWeb()
        setTabVisual(if (isFlash) 1 else 2)
        val target = if (isFlash) flash else logPage
        if (current === target) return
        current = target
        supportFragmentManager.beginTransaction()
            .hide(if (isFlash) logPage else flash)
            .show(target)
            .commit()
    }

    /**
     * 底栏视觉：只有当前页签显示文字，其余只留图标；文字做一次上浮淡入。
     * which：0=后台 1=烧录 2=日志
     */
    private fun setTabVisual(which: Int) {
        val active = R.drawable.bg_tab_selected
        val idle = R.drawable.bg_tab_normal
        findViewById<View>(R.id.tabFlashPill).setBackgroundResource(if (which == 1) active else idle)
        findViewById<View>(R.id.tabLogPill).setBackgroundResource(if (which == 2) active else idle)

        val bi = findViewById<ImageView>(R.id.tabBackIcon)
        val bl = findViewById<TextView>(R.id.tabBackLabel)
        val fi = findViewById<ImageView>(R.id.tabFlashIcon)
        val fl = findViewById<TextView>(R.id.tabFlashLabel)
        val li = findViewById<ImageView>(R.id.tabLogIcon)
        val ll = findViewById<TextView>(R.id.tabLogLabel)

        bi.setColorFilter(if (which == 0) onColor else offColor)
        bl.setTextColor(onColor)
        fl.setTextColor(onColor)
        ll.setTextColor(onColor)
        fi.setColorFilter(if (which == 1) onColor else offColor)
        li.setColorFilter(if (which == 2) onColor else offColor)

        showLabel(bl, which == 0)
        showLabel(fl, which == 1)
        showLabel(ll, which == 2)
    }

    /** 选中的页签文字上浮淡入，未选中的直接收起 */
    private fun showLabel(tv: TextView, on: Boolean) {
        if (on) {
            if (tv.visibility == View.VISIBLE) return
            tv.visibility = View.VISIBLE
            tv.alpha = 0f
            tv.translationY = 6f * resources.displayMetrics.density
            tv.animate().alpha(1f).translationY(0f).setDuration(160).start()
        } else {
            tv.animate().cancel()
            tv.visibility = View.GONE
            tv.alpha = 1f
            tv.translationY = 0f
        }
    }

    /** 烧录页提供的实时状态：只有真在烧/在握手时才锁住底栏「后台」按钮 */
    var flashBusyProvider: (() -> Boolean)? = null

    /** 取镜像里的设备 IP；第一次拿到（或地址换了）时记下时间戳，供预热判定（8 秒） */
    private fun mirroredIp(): String? {
        val ip = SerialMirror.lockedIp() ?: SerialMirror.findIp()
        if (ip != null) {
            if (ip != webIpStamped) {
                webIpStamped = ip
                webIpAt = System.currentTimeMillis()
                markBackLocked(true)
                webHandler.removeCallbacks(warmupUnlock)
                webHandler.postDelayed(warmupUnlock, webWarmupMs)
            }
        }
        return ip
    }

    /** 页面内的设备后台网页：黑底铺满内容区，拿到 IP 才加载 */
    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    private fun openDeviceWeb() {
        // 串口里还没出现 IP：不切页面，点一下原地不动
        val gateIp = mirroredIp()
        if (gateIp == null) {
            L.i("后台网页：没有 IP，留在当前页面")
            toastIt("还没从串口读到设备 IP，稍等再点")
            return
        }
        val container = findViewById<ViewGroup>(R.id.container)
        var wrap = webWrap
        if (wrap == null || webView == null) {
            wrap = android.widget.FrameLayout(this).apply { setBackgroundColor(android.graphics.Color.BLACK) }
            val wv = android.webkit.WebView(this).apply {
                setBackgroundColor(android.graphics.Color.BLACK)
                // 先不显示：网页还没加载成功时，WebView 会在左上角画个默认图标
                visibility = View.INVISIBLE
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                settings.useWideViewPort = true
                // 这里必须是 false：overview 模式会按内容宽度自动适配屏幕，
                // 直接把 setInitialScale 覆盖掉，所以以前设 75%、50% 都没反应
                settings.loadWithOverviewMode = false
                settings.setSupportZoom(true)
                settings.builtInZoomControls = false
                // 网页缩放 50%
                setInitialScale(50)
                settings.allowFileAccess = true
                settings.allowContentAccess = true
                webChromeClient = object : android.webkit.WebChromeClient() {
                    // 网页里的 <input type=file>：不接管这个回调，点按钮什么都不会发生
                    override fun onShowFileChooser(
                        view: android.webkit.WebView,
                        callback: android.webkit.ValueCallback<Array<android.net.Uri>>,
                        params: FileChooserParams
                    ): Boolean {
                        fileChooserCallback?.let { runCatching { it.onReceiveValue(null) } }
                        fileChooserCallback = callback
                        val intent = runCatching { params.createIntent() }.getOrNull()
                        if (intent == null) {
                            fileChooserCallback = null
                            runCatching { callback.onReceiveValue(null) }
                            return false
                        }
                        return try {
                            fileChooser.launch(intent)
                            true
                        } catch (t: Throwable) {
                            L.w("网页选择文件打不开：${t.message}")
                            fileChooserCallback = null
                            runCatching { callback.onReceiveValue(null) }
                            false
                        }
                    }
                }
                webViewClient = object : android.webkit.WebViewClient() {
                    override fun onPageFinished(view: android.webkit.WebView, url: String?) {
                        // 主框架这轮失败过：onPageFinished 落到的是错误页，不能当成打开成功，
                        // 否则就会停在一张黑屏上不重试，正是以前「首开黑屏、手动刷新才好」的原因
                        if (mainFrameFailed) {
                            L.i("后台网页加载完成，但这一轮失败过：$url")
                            pageOk = false
                            view.visibility = View.INVISIBLE
                            return
                        }
                        L.i("后台网页加载完成：$url")
                        applyWebZoom(view)
                        view.visibility = View.VISIBLE
                        pageOk = true
                        webLoaded = true
                        webHandler.removeCallbacks(webRetry)
                        webWrap?.findViewById<View>(android.R.id.background)?.visibility = View.GONE
                        webWrap?.findViewById<TextView>(android.R.id.text1)?.visibility = View.GONE
                    }

                    override fun onReceivedError(view: android.webkit.WebView, req: android.webkit.WebResourceRequest?, err: android.webkit.WebResourceError?) {
                        val code = err?.errorCode ?: -1
                        val desc = err?.description?.toString() ?: ""
                        if (req?.isForMainFrame == true) {
                            mainFrameFailed = true
                            pageOk = false
                            L.w("后台网页打开失败 code=$code $desc url=${req.url}")
                            view.visibility = View.INVISIBLE
                            showWebHint("打不开 ${req.url}\n错误码 $code $desc\n自动重试中，手机要连上设备自己的 Wi-Fi")
                        }
                    }

                    override fun onReceivedHttpError(view: android.webkit.WebView, req: android.webkit.WebResourceRequest?, resp: android.webkit.WebResourceResponse?) {
                        if (req?.isForMainFrame == true) {
                            val code = resp?.statusCode ?: -1
                            mainFrameFailed = true
                            pageOk = false
                            L.w("后台网页 HTTP 错误 $code ${req.url}")
                            view.visibility = View.INVISIBLE
                            showWebHint("设备返回 HTTP $code\n地址 ${req.url}")
                        }
                    }
                }
            }
            wrap.addView(wv, android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            // 还没拿到 IP 时的提示：黑底上留一行灰字，别让人以为点了没反应
            val hint = TextView(this).apply {
                id = android.R.id.text1
                text = "正在等待设备的 IP 地址…\n设备连上并打印 192.168.x.x 后会自动打开后台"
                setTextColor(0xFF7E8490.toInt())
                textSize = 13f
                gravity = android.view.Gravity.CENTER
                setPadding(60, 0, 60, 0)
            }
            val scrim = View(this).apply {
                setBackgroundColor(android.graphics.Color.BLACK)
                id = android.R.id.background
            }
            wrap.addView(scrim, android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            wrap.addView(hint, android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.CENTER))
            container.addView(wrap, android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            webWrap = wrap
            webView = wv
        }
        wrap.visibility = View.VISIBLE
        wrap.bringToFront()
        excludeSystemGestures(wrap)
        setTabVisual(0)
        if (pageOk && webLoaded && webView?.url != null) {
            // 之前已经加载好：直接显示原页面，不重载
            webWrap?.findViewById<View>(android.R.id.background)?.visibility = View.GONE
            webWrap?.findViewById<TextView>(android.R.id.text1)?.visibility = View.GONE
            webView?.visibility = View.VISIBLE
            webHandler.postDelayed(webPoll, 1500)
            L.i("打开后台网页：沿用已加载的页面 ${webView?.url}")
            return
        }
        wrap.findViewById<View>(android.R.id.background)?.visibility = View.VISIBLE
        wrap.findViewById<TextView>(android.R.id.text1)?.visibility = View.VISIBLE
        webHandler.removeCallbacks(webPoll)
        val ip = if (SerialMirror.windowOpenNow()) SerialMirror.findSta() else SerialMirror.findIp()
        if (ip != null && (ip != webIp || !pageOk)) {
            // 地址换了，或者上次没打开成功：重新探测一次
            loadWeb(ip)
        } else if (webIp == null) {
            showWebHint("正在等待设备的 IP 地址…")
            L.i("后台网页：还没拿到 IP，先黑屏等待")
        }
        webHandler.postDelayed(webPoll, 800)
        L.i("打开后台网页")
    }

    /**
     * 网页层显示期间，把左右边缘交给 App 自己处理：
     * 系统手势返回只认屏幕边缘，这里声明两块排除区，滑动就不会退出 App。
     */
    private fun excludeSystemGestures(view: View) {
        view.post {
            runCatching {
                val w = view.width
                val h = view.height
                if (w <= 0 || h <= 0) return@post
                val edge = (48 * resources.displayMetrics.density).toInt()
                val band = minOf(h, (220 * resources.displayMetrics.density).toInt())
                view.systemGestureExclusionRects = listOf(
                    android.graphics.Rect(0, 0, edge, band),
                    android.graphics.Rect(w - edge, 0, w, band)
                )
            }.onFailure { L.w("排除系统手势失败", it) }
        }
    }

    /** 在网页层上显示一行状态/错误文字 */    /** 在网页层上显示一行状态/错误文字 */
    private fun showWebHint(text: String) {
        val hint = webWrap?.findViewById<TextView>(android.R.id.text1) ?: return
        hint.text = text
        hint.visibility = View.VISIBLE
    }

    private fun loadWeb(ip: String) {
        webIp = ip
        val url = "http://$ip/"
        L.i("后台网页加载：$url")
        // WebView 先别露出来：先让后台线程把 http 探测通，通了再交给 WebView 打开，
        // 这样首开不会先落到一张错误页/黑屏上
        webView?.visibility = View.INVISIBLE
        webWrap?.findViewById<View>(android.R.id.background)?.visibility = View.VISIBLE
        pageOk = false
        webLoaded = false
        webHandler.removeCallbacks(webRetry)
        retryLeft = 0
        showWebHint("正在等待设备后台启动…\n$url")
        probeAndLoad(ip, 0)
    }

    /** 后台线程反复探测 http://ip/，通了才让 WebView 加载；不通就一直等，最多约 20 秒 */
    private fun probeAndLoad(ip: String, attempt: Int) {
        val gen = ++probeGen
        Thread {
            val ok = runCatching { httpReachable(ip) }.getOrDefault(false)
            webHandler.post {
                // 地址换了、网页层收起了、或者又开了一轮探测：这轮作废
                if (gen != probeGen || webIp != ip || webView == null) return@post
                if (ok) {
                    showWebHint("设备后台已就绪，正在打开 $ip …")
                    lazyLoad(ip, attempt)
                } else if (attempt < 20) {
                    showWebHint("正在等待设备后台启动…\n$ip（第 ${attempt + 1} 次探测）")
                    webHandler.postDelayed({ probeAndLoad(ip, attempt + 1) }, 800)
                } else {
                    showWebHint("连不上 $ip\n设备 Web 服务还没起来，双击「后台」重试")
                    L.w("后台探测 20 次都没通：$ip")
                }
            }
        }.also { it.isDaemon = true }.start()
    }

    /** 裸 http 探测：只要拿到 HTTP 响应头就算服务起来了 */
    private fun httpReachable(ip: String): Boolean {
        val c = (java.net.URL("http://$ip/").openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 1500
            readTimeout = 1800
            instanceFollowRedirects = true
            useCaches = false
            setRequestProperty("Connection", "close")
        }
        return runCatching {
            val code = c.responseCode
            runCatching { c.inputStream?.close() }
            code in 200..399
        }.getOrDefault(false).also { runCatching { c.disconnect() } }
    }

    /** 探测通了才真正 loadUrl；同时开一路失败重试兜底 */
    private fun lazyLoad(ip: String, attempt: Int) {
        val url = "http://$ip/"
        L.i("后台探测通过，交给 WebView：$url（第 ${attempt + 1} 次）")
        loadUrlDirect(url)
    }

    private fun loadUrlDirect(url: String) {
        val wv = webView ?: return
        mainFrameFailed = false
        pageOk = false
        wv.visibility = View.INVISIBLE
        webWrap?.findViewById<View>(android.R.id.background)?.visibility = View.VISIBLE
        wv.loadUrl(url)
        webLoaded = false
        // 兜底重试：WebView 自己失败了也不至于停在黑屏上
        retryLeft = 8
        webHandler.removeCallbacks(webRetry)
        webHandler.postDelayed(webRetry, 1200)
    }

    /** 双击「后台」刷新：重载当前地址；还没有 IP 就重新等一次 */
    private fun refreshWeb() {
        val ip = SerialMirror.lockedIp() ?: SerialMirror.findIp()
        if (ip != null) {
            loadWeb(ip)
        } else {
            webLoaded = false
            pageOk = false
            probeGen++
            webHandler.removeCallbacks(webPoll)
            webHandler.postDelayed(webPoll, 500)
        }
        L.i("后台网页刷新")
    }

    /** USB 断开：清掉已定 IP、收起网页、退回烧录页，下一次连上重新获取地址 */
    fun onDeviceUnplugged() {
        runCatching { doOnDeviceUnplugged() }.onFailure { L.w("断线处理异常", it) }
    }

    private fun doOnDeviceUnplugged() {
        // 先记下断线时在不在网页后台，再收起网页层
        val onWeb = webWrap?.visibility == View.VISIBLE
        SerialMirror.clear()
        // 拔出后预热计时作废，插回重新识别 IP 再等 8 秒
        webIpAt = 0L
        webIpStamped = null
        webLoaded = false
        pageOk = false
        probeGen++
        webHandler.removeCallbacks(webPoll)
        webHandler.removeCallbacks(webRetry)
        webWrap?.visibility = View.GONE
        webView?.let { runCatching { it.stopLoading(); it.loadUrl("about:blank") } }
        webLocked = true
        markBackLocked(true)
        // 只有在网页后台这一层才退回烧录页；在烧录页/串口日志页时保持当前页面不动
        if (onWeb) {
            selectTab(true)
            L.i("USB 断开：退出后台网页并锁住按钮，回到烧录页")
        } else {
            L.i("USB 断开：不在网页后台，保持当前页面")
        }
    }

    /** 主界面自己也监听 USB 插拔：不依赖烧录页在不在前台，断开就锁后台 */
    private fun registerUsbLock() {
        val filter = android.content.IntentFilter().apply {
            addAction(android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(android.hardware.usb.UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        runCatching {
            androidx.core.content.ContextCompat.registerReceiver(
                this,
                object : android.content.BroadcastReceiver() {
                    override fun onReceive(c: android.content.Context?, i: android.content.Intent?) {
                        when (i?.action) {
                            android.hardware.usb.UsbManager.ACTION_USB_DEVICE_DETACHED -> onDeviceUnplugged()
                            android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED -> onDeviceAttached()
                        }
                    }
                },
                filter,
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }.onFailure { L.w("主界面注册 USB 广播失败", it) }
    }

    /** 后台按钮的锁定外观：锁住时半透明，解锁恢复 */
    private fun markBackLocked(locked: Boolean) {
        val v = findViewById<View>(R.id.tabBack)
        v?.alpha = if (locked) 0.38f else 1f
    }

    /** 设备重新插上：解锁「后台」按钮 */
    fun onDeviceAttached() {
        SerialMirror.beginWindow()
        if (!webLocked) return
        webLocked = false
        markBackLocked(false)
        L.i("USB 已插回：解锁后台网页")
    }

    /** 切回烧录/日志页时收起网页层 */
    private fun hideWeb() {
        webWrap?.apply {
            visibility = View.GONE
            runCatching { systemGestureExclusionRects = emptyList() }
        }
        setTabVisual(if (current === flash) 1 else 2)
    }

    private fun goHome() {
        try {
            val home = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
                addCategory(android.content.Intent.CATEGORY_HOME)
                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(home)
            L.i("已退到后台")
        } catch (t: Throwable) {
            L.w("回桌面失败，改用 moveTaskToBack", t)
            runCatching { moveTaskToBack(true) }
                .onFailure { toastIt("退后台失败：" + it.message) }
        }
    }

    private fun toastIt(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    private var swipeDownX = 0f
    private var swipeDownY = 0f
    private var swipeEdge = 0

    /** 屏幕左右边缘向内滑 → 返回网页上一级；没有上一级就收起网页 */
    private fun handleEdgeSwipe(ev: android.view.MotionEvent): Boolean {
        val wrap = webWrap ?: return false
        if (wrap.visibility != View.VISIBLE) return false
        val edge = 40f * resources.displayMetrics.density
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                swipeEdge = when {
                    ev.x <= edge -> 1
                    ev.x >= wrap.width - edge -> 2
                    else -> 0
                }
                swipeDownX = ev.x
                swipeDownY = ev.y
            }
            android.view.MotionEvent.ACTION_UP -> {
                if (swipeEdge == 0) return false
                val dx = ev.x - swipeDownX
                val dy = kotlin.math.abs(ev.y - swipeDownY)
                val far = 90f * resources.displayMetrics.density
                val rightwards = (swipeEdge == 1 && dx > far) || (swipeEdge == 2 && dx < -far)
                if (rightwards && dy < far) {
                    goWebBack()
                    return true
                }
                swipeEdge = 0
            }
            android.view.MotionEvent.ACTION_CANCEL -> swipeEdge = 0
        }
        return false
    }

    private fun goWebBack() {
        val wv = webView
        if (wv != null && wv.canGoBack()) {
            wv.goBack()
            L.i("网页返回上一级")
            return
        }
        hideWeb()
        setTabVisual(if (current === flash) 1 else 2)
        L.i("网页没有上一级，收起后台页")
    }

    /**
     * 回到前台时现场查一遍串口设备：后台期间被拔掉的话，这里会立刻收网页、锁按钮、
     * 退回烧录页，不会把网页后台那一屏留着。
     */
    override fun onResume() {
        super.onResume()
        val alive = runCatching { UsbDevices.list(this).isNotEmpty() }.getOrDefault(true)
        if (!alive) onDeviceUnplugged()
    }

    /**
     * 插 USB 时系统会投递一个 USB_DEVICE_ATTACHED 的启动 intent。
     * singleTop 保证不会重建 Activity，这里也刻意不动页签——
     * 用户当前停在哪一页（烧录/串口日志/网页后台）就留在哪一页。
     */
    override fun onNewIntent(intent: android.content.Intent?) {
        super.onNewIntent(intent)
        if (intent?.action == android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            L.i("插入 USB：保持当前页面不变")
        }
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (handleEdgeSwipe(ev)) return true
        if (ev.action == android.view.MotionEvent.ACTION_DOWN &&
            this::logPage.isInitialized && current === logPage
        ) {
            runCatching { logPage.collapseIfOutside(ev) }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            CrashStore.trail(this, "MainActivity.onCreate 开始")
            setContentView(R.layout.activity_main)
            CrashStore.trail(this, "布局加载完成")

            Logging.init()
            CrashStore.trail(this, "日志初始化完成")
            showLastCrashIfAny()
            UsbPerms.activityRef = java.lang.ref.WeakReference(this)

            flash = FlashFragment()
            logPage = SerialFragment()
            CrashStore.trail(this, "Fragment 构造完成")

            supportFragmentManager.beginTransaction()
                .add(R.id.container, flash, "flash")
                .add(R.id.container, logPage, "log")
                .hide(logPage)
                .commit()
            current = flash
            CrashStore.trail(this, "Fragment 添加完成")

            findViewById<View>(R.id.tabFlash).setOnClickListener { selectTab(true) }
            findViewById<View>(R.id.tabLog).setOnClickListener { selectTab(false) }

            // 「后台」在页面内打开设备后台网页：单击打开，双击刷新
            findViewById<ImageView>(R.id.tabBackIcon).setColorFilter(offColor)
            findViewById<View>(R.id.tabBack).setOnClickListener {
                // 三重判定：拔出标记 + 现场串口设备数 + 已定 IP，缺一个就不让进
                val serials = runCatching { UsbDevices.list(this) }.getOrDefault(emptyList())
                if (serials.isEmpty()) {
                    webLocked = true
                    markBackLocked(true)
                    toastIt("设备未连接，后台网页不可用")
                    L.i("USB 已断开（现场设备 0 个），后台网页锁定")
                    return@setOnClickListener
                }
                // 设备在现场：清掉可能因复位重枚举残留的拔出锁定，别让旧锁挡住
                if (webLocked) {
                    webLocked = false
                    markBackLocked(false)
                    L.i("检测到设备已插回，解除后台网页锁定")
                }
                if (flashBusyProvider?.invoke() == true) {
                    toastIt("正在烧录，稍后再打开后台")
                    L.i("正在烧录，忽略底栏点击")
                    return@setOnClickListener
                }
                // 识别到 IP 后先预热 8 秒，等设备 Web 服务起来；期间按钮锁住不可进
                val backIp = mirroredIp()
                if (backIp == null) {
                    toastIt("还没从串口读到设备 IP，稍等再点")
                    return@setOnClickListener
                }
                val remain = webWarmupMs - (System.currentTimeMillis() - webIpAt)
                if (remain > 0) {
                    markBackLocked(true)
                    toastIt("网页后台 ${(remain + 999) / 1000} 秒后可用")
                    L.i("后台网页预热中，剩余 ${remain}ms")
                    return@setOnClickListener
                }
                markBackLocked(false)
                val now = android.os.SystemClock.uptimeMillis()
                val doubleTap = now - lastBackTap < 400
                lastBackTap = now
                if (doubleTap) {
                    // 双击：已经在本页就刷新，没在本页就先打开再刷
                    if (webWrap?.visibility == View.VISIBLE) refreshWeb() else openDeviceWeb()
                } else {
                    // 单击：不在本页就打开；已在本页则什么都不做，等第二下判定
                    if (webWrap?.visibility != View.VISIBLE) openDeviceWeb()
                }
            }

            registerUsbLock()
            webHandler.postDelayed(ipWatch, 1000)
            selectTab(true)
            CrashStore.trail(this, "底部页签绑定完成")
        } catch (t: Throwable) {
            runCatching { CrashStore.trail(this, "启动异常：" + t.javaClass.name + " " + t.message) }
            showFatal(t)
        }
    }

    /**
     * 网页缩放：把整套 DOM 缩到目标比例，同时把 body 宽度放大 1/zoom，
     * 保证缩完之后仍然铺满屏幕宽度，不会右边留一条空白。
     * 页面自带 viewport 时 setInitialScale 无效，只能这样在页面内部做。
     */
    private fun applyWebZoom(view: android.webkit.WebView, zoom: Float = 1.0f) {
        // 缩放比例回到 100%：把之前注入的样式全部撤掉，页面按原始大小显示
        val js = "(function(){" +
            "var h=document.documentElement, b=document.body;" +
            "h.style.zoom='1';" +
            "h.style.width='';" +
            "h.style.overflowX='';" +
            "b.style.overflowX='';" +
            "b.style.transform='none';" +
            "b.style.width='';" +
            "b.style.margin='';" +
            "})()"
        runCatching { view.evaluateJavascript(js, null) }
    }
}
