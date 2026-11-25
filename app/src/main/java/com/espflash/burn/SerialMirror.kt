package com.espflash.burn

/**
 * 串口接收内容的旁路镜像：只保留最近一段文本，用来给「网页后台」找设备 IP。
 * 采集规则：
 *  - 采集窗口 = 设备复位 或 USB 插上之后的 10 秒；
 *  - 末段是 1 的地址一律不收，唯独 192.168.4.1 例外（先收着当兜底）；
 *  - 同时出现多个地址时，不取 192.168.4.1，优先用板子连路由器拿到的那一个；
 *  - 窗口内持续采集、随时改选，窗口结束后定住最后的选择。
 */
object SerialMirror {
    private const val MAX = 60000
    private const val WINDOW_MS = 10_000L
    private val lock = Any()
    private val buf = StringBuilder()
    private val found = LinkedHashSet<String>()
    private var windowAt = 0L
    private var frozen: String? = null

    private val ipRe = Regex("192\\.168\\.\\d{1,3}\\.\\d{1,3}")

    fun feed(text: String) {
        if (text.isEmpty()) return
        synchronized(lock) {
            buf.append(text)
            if (buf.length > MAX) buf.delete(0, buf.length - MAX)
            if (windowOpen()) harvest(buf.toString())
        }
    }

    /** USB 插上 / 设备复位后调用：清掉旧结果，重新开始 10 秒采集 */
    fun beginWindow() {
        synchronized(lock) {
            found.clear()
            frozen = null
            buf.setLength(0)
            windowAt = System.currentTimeMillis()
        }
    }

    /** 拔线时清空，什么都不留 */
    fun clear() {
        synchronized(lock) {
            buf.setLength(0)
            found.clear()
            frozen = null
            windowAt = 0L
        }
    }

    private fun windowOpen(): Boolean = windowAt != 0L && System.currentTimeMillis() - windowAt <= WINDOW_MS

    private fun harvest(text: String) {
        ipRe.findAll(text).forEach { m ->
            val ip = m.value
            val last = ip.substringAfterLast('.').toIntOrNull() ?: return@forEach
            if (last == 1 && ip != "192.168.4.1") return@forEach
            found.add(ip)
        }
    }

    /**
     * 当前该用哪个地址：
     * - 出现了非 4.1 的地址 → 立刻用它；
     * - 只见到 4.1 且窗口还没结束 → 先不给（等板子打本机地址，别去连网关）；
     * - 窗口结束还只有 4.1 → 才退回 4.1。
     */
    private fun pick(): String? {
        if (found.isEmpty()) return null
        val sta = found.firstOrNull { it != "192.168.4.1" }
        if (sta != null) return sta
        return if (windowOpen()) null else found.first()
    }

    /**
     * 供「网页后台」取地址。窗口内实时返回最新选择（不锁定），窗口结束后定住。
     */
    fun findIp(): String? = synchronized(lock) {
        if (windowOpen()) {
            harvest(buf.toString())
            return pick()
        }
        if (frozen == null) {
            harvest(buf.toString())
            frozen = pick()
        }
        frozen
    }

    fun lockedIp(): String? = synchronized(lock) { frozen }

    fun windowOpenNow(): Boolean = synchronized(lock) { windowOpen() }

    /** 只要板子连路由器后拿到的那个地址（排除 4.1），窗口内用来先等它 */
    fun findSta(): String? = synchronized(lock) {
        if (windowOpen()) harvest(buf.toString())
        found.firstOrNull { it != "192.168.4.1" }
    }

    /** 复位重启：重新开一次采集窗口 */
    fun resetWindow() = beginWindow()
}
