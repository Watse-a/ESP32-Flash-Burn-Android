package com.espflash.burn

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * 网络日志接收器。
 *
 * 板子推日志的方式各家不一样，这里两条路都留：
 *  - 板子自己开了 TCP 服务：手机当客户端连 IP:端口，连上就读流；
 *  - 板子只往手机 IP / 广播投 UDP：TCP 连不上就退回来监听这个端口。
 * IP 留空时不试 TCP，直接监听端口（收任意来源）。
 */
class NetLog(
    private val host: String,
    private val port: Int,
    private val udpOnly: Boolean = false,
    private val onData: (String) -> Unit,
    private val onState: (String) -> Unit
) {
    @Volatile private var stopped = false
    private var tcp: Socket? = null
    private var udp: DatagramSocket? = null
    private var thread: Thread? = null

    private val sb = StringBuilder()
    private val sbLock = Any()
    private var lastFlush = 0L

    fun start() {
        if (thread != null) return
        thread = Thread({ run() }, "net-log").apply { isDaemon = true }.also { it.start() }
    }

    fun stop() {
        stopped = true
        runCatching { tcp?.close() }
        runCatching { udp?.close() }
        tcp = null
        udp = null
    }

    private fun run() {
        if (stopped) return
        val s = if (udpOnly) null else connectTcp()
        if (stopped) return
        if (s != null) readTcp(s) else readUdp()
        if (!stopped) onState("连接结束")
        flush()
    }

    /** 先按 TCP 客户端连一下，5 秒连不上就当板子不开 TCP 服务 */
    private fun connectTcp(): Socket? {
        if (host.isBlank()) return null
        return try {
            val s = Socket()
            s.connect(InetSocketAddress(InetAddress.getByName(host), port), 5000)
            s.soTimeout = 500
            tcp = s
            onState("TCP 已连上 $host:$port")
            s
        } catch (t: Throwable) {
            if (!stopped) onState("TCP $host:$port 连不上，改为监听 UDP")
            runCatching { tcp?.close() }
            tcp = null
            null
        }
    }

    private fun readTcp(s: Socket) {
        val input = runCatching { s.getInputStream() }.getOrNull() ?: return
        val buf = ByteArray(4096)
        while (!stopped) {
            val n = try {
                input.read(buf)
            } catch (e: SocketTimeoutException) {
                flush()
                continue
            } catch (t: Throwable) {
                if (!stopped) onState("TCP 断开：${t.message}")
                return
            }
            if (n < 0) {
                if (!stopped) onState("TCP 被对端关闭")
                return
            }
            if (n > 0) push(String(buf, 0, n, Charsets.UTF_8))
        }
    }

    /**
     * 绑本机 UDP 端口。各家 ROM 对 bind 的写法兼容性不一样（有的一直报
     * port out of range:-1），所以按「最普通 → 显式通配 → 手工 bind」依次试，
     * 三条都失败才报错，并把每条的错误原样带出来，方便定位。
     */
    private fun openUdp(): DatagramSocket? {
        val errs = ArrayList<String>()
        fun attempt(name: String, block: () -> DatagramSocket): DatagramSocket? = try {
            block().also { it.soTimeout = 500 }
        } catch (t: Throwable) {
            errs.add("$name：${t.javaClass.simpleName} ${t.message}")
            null
        }
        attempt("直接绑 $port") { DatagramSocket(port) }?.let { return it }
        attempt("通配地址") { DatagramSocket(port, InetAddress.getByName("0.0.0.0")) }?.let { return it }
        attempt("手工 bind") {
            DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), port))
            }
        }?.let { return it }
        onState("UDP 端口 $port 打不开（${errs.joinToString("；")}）")
        return null
    }

    private fun readUdp() {
        val ds = openUdp() ?: return
        udp = ds
        val only = host.takeIf { it.isNotBlank() }
            ?.let { runCatching { InetAddress.getByName(it) }.getOrNull() }
        onState(
            if (only != null) "UDP 监听 :$port，只收 $host 发来的报文"
            else "UDP 监听 :$port，接收任意来源"
        )
        val pkt = DatagramPacket(ByteArray(4096), 4096)
        while (!stopped) {
            val n = try {
                ds.receive(pkt)
                pkt.length
            } catch (e: SocketTimeoutException) {
                flush()
                continue
            } catch (t: Throwable) {
                if (!stopped) onState("UDP 监听停止：${t.message}")
                return
            }
            // IP 填了但报文来自别处：不是这条链路的日志，直接丢
            if (only != null && pkt.address != only) continue
            if (n > 0) {
                var text = String(pkt.data, 0, n, Charsets.UTF_8)
                // 一个 UDP 报文通常就是一行，结尾没换行补一个，否则会糊成一坨
                if (!text.endsWith("\n")) text += "\n"
                push(text)
            }
        }
    }

    private fun push(text: String) {
        synchronized(sbLock) { sb.append(text) }
        if (sb.length >= 1024 || System.currentTimeMillis() - lastFlush >= 80) flush()
    }

    private fun flush() {
        val out = synchronized(sbLock) {
            val t = sb.toString()
            sb.setLength(0)
            t
        }
        lastFlush = System.currentTimeMillis()
        if (out.isNotEmpty()) runCatching { onData(out) }
    }
}
