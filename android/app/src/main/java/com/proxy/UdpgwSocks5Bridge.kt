package com.proxy

import android.util.Log
import java.io.DataInputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * Relays UDP from BadVPN tun2socks (built with -DANDROID_UDP + a patch that
 * points its UDP relay at a local address) through the proxy's SOCKS5 UDP
 * ASSOCIATE, so UDP uses the same authenticated SOCKS5 proxy as TCP.
 *
 *   tun2socks --enable-udprelay 127.0.0.1:<port>
 *        --(UDP, SOCKS5-UDP datagrams)--> this bridge
 *        --(SOCKS5 UDP ASSOCIATE, authenticated)--> proxy relay
 *
 * tun2socks (ANDROID_UDP) already frames datagrams as SOCKS5-UDP
 * (RSV|FRAG|ATYP|addr|port|data), which is exactly what the proxy's UDP relay
 * expects, so the bridge forwards bytes unchanged and only parses the header to
 * route replies back to the right tun2socks source.
 *
 * Runs under the app UID (excluded from the VPN), so the proxy sockets go
 * direct and do not loop. Best-effort: if the ASSOCIATE cannot be established,
 * UDP silently fails while TCP is unaffected.
 */
class UdpgwSocks5Bridge(
    private val proxyIp: String,
    private val proxyPort: Int,
    private val login: String,
    private val password: String
) {
    @Volatile private var running = false
    @Volatile private var localSocket: DatagramSocket? = null
    @Volatile private var relaySocket: DatagramSocket? = null
    @Volatile private var controlSocket: Socket? = null
    @Volatile private var relayAddr: InetAddress? = null
    @Volatile private var relayPort: Int = 0

    // remote "atyp:addr:port" (hex) -> tun2socks source, for reply routing.
    private val clientByRemote = ConcurrentHashMap<String, InetSocketAddress>()

    /** Bind, establish the ASSOCIATE, start threads. Returns the local UDP port
     *  for tun2socks' --enable-udprelay, or -1 on failure. */
    fun start(): Int {
        val local = try {
            DatagramSocket(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
        } catch (e: IOException) {
            Log.e(TAG, "start: failed to bind local udp relay", e)
            return -1
        }
        localSocket = local
        running = true
        val port = local.localPort
        Log.d(TAG, "start: udp relay listening on 127.0.0.1:$port")

        Thread({ associateWithRetry() }, "udp-associate").start()
        Thread({ localToRelayLoop(local) }, "udp-local2relay").start()
        Thread({ relayToLocalLoop() }, "udp-relay2local").start()
        return port
    }

    fun stop() {
        running = false
        closeQuietly(localSocket); localSocket = null
        closeQuietly(relaySocket); relaySocket = null
        try { controlSocket?.close() } catch (_: Exception) {}
        controlSocket = null
        clientByRemote.clear()
    }

    // ---- SOCKS5 UDP ASSOCIATE ----

    private fun associateWithRetry() {
        while (running) {
            try {
                establishAssociate()
                Log.d(TAG, "associate: UDP ASSOCIATE up, relay=${relayAddr?.hostAddress}:$relayPort")
                val control = controlSocket ?: return
                val input = control.getInputStream()
                // Hold the control connection open (ASSOCIATE lives with it);
                // block until it closes, then re-establish.
                while (running && input.read() != -1) { /* drain */ }
            } catch (e: Exception) {
                if (running) Log.e(TAG, "associate: failed, retrying: ${e.message}")
            }
            if (!running) return
            try { Thread.sleep(3000) } catch (_: InterruptedException) { return }
        }
    }

    private fun establishAssociate() {
        closeQuietly(relaySocket); relaySocket = null
        try { controlSocket?.close() } catch (_: Exception) {}

        val control = Socket()
        control.tcpNoDelay = true
        control.connect(InetSocketAddress(proxyIp, proxyPort), 15000)
        control.soTimeout = 20000
        val cin = DataInputStream(control.getInputStream())
        val cout = control.getOutputStream()

        // Method negotiation: offer no-auth and user/pass.
        cout.write(byteArrayOf(5, 2, 0, 2)); cout.flush()
        val method = ByteArray(2).also { cin.readFully(it) }
        if (method[0].toInt() != 5) throw IOException("bad socks version ${method[0]}")
        when (method[1].toInt() and 0xff) {
            0x02 -> {
                val u = login.toByteArray(); val p = password.toByteArray()
                val auth = java.io.ByteArrayOutputStream()
                auth.write(1)            // auth sub-negotiation version
                auth.write(u.size); auth.write(u)
                auth.write(p.size); auth.write(p)
                cout.write(auth.toByteArray()); cout.flush()
                val ar = ByteArray(2).also { cin.readFully(it) }
                if (ar[1].toInt() != 0) throw IOException("socks auth failed")
            }
            0x00 -> { /* no auth */ }
            else -> throw IOException("no acceptable auth method: ${method[1]}")
        }

        // UDP ASSOCIATE (cmd=3), DST 0.0.0.0:0.
        cout.write(byteArrayOf(5, 3, 0, 1, 0, 0, 0, 0, 0, 0)); cout.flush()
        val head = ByteArray(4).also { cin.readFully(it) }
        if (head[1].toInt() != 0) throw IOException("UDP ASSOCIATE refused rep=${head[1]}")
        val (bndIp, bndPort) = when (head[3].toInt() and 0xff) {
            0x01 -> {
                val a = ByteArray(4).also { cin.readFully(it) }
                val pt = ByteArray(2).also { cin.readFully(it) }
                InetAddress.getByAddress(a) to portOf(pt)
            }
            0x04 -> {
                val a = ByteArray(16).also { cin.readFully(it) }
                val pt = ByteArray(2).also { cin.readFully(it) }
                InetAddress.getByAddress(a) to portOf(pt)
            }
            else -> throw IOException("unexpected BND atyp ${head[3]}")
        }
        // A relay of 0.0.0.0 means "same host as the control connection".
        val relay = if (bndIp.hostAddress == "0.0.0.0") InetAddress.getByName(proxyIp) else bndIp

        // Keep control open (blocking) to hold the ASSOCIATE.
        control.soTimeout = 0

        val udp = DatagramSocket()
        controlSocket = control
        relayAddr = relay
        relayPort = bndPort
        relaySocket = udp
    }

    // ---- tun2socks -> proxy relay ----

    private fun localToRelayLoop(local: DatagramSocket) {
        val buf = ByteArray(65535)
        while (running) {
            val pkt = DatagramPacket(buf, buf.size)
            try {
                local.receive(pkt)
            } catch (e: IOException) {
                if (running) Log.d(TAG, "localToRelayLoop: ${e.message}")
                return
            }
            val relay = relayAddr
            val udp = relaySocket
            if (relay == null || udp == null) continue

            val key = remoteKeyFromSocksUdp(buf, pkt.length) ?: continue
            clientByRemote[key] = InetSocketAddress(pkt.address, pkt.port)
            try {
                // Forward the SOCKS5-UDP datagram unchanged to the proxy relay.
                udp.send(DatagramPacket(buf, pkt.length, relay, relayPort))
            } catch (e: IOException) {
                Log.d(TAG, "localToRelayLoop: relay send failed: ${e.message}")
            }
        }
    }

    // ---- proxy relay -> tun2socks ----

    private fun relayToLocalLoop() {
        val buf = ByteArray(65535)
        while (running) {
            val udp = relaySocket
            if (udp == null) {
                try { Thread.sleep(200) } catch (_: InterruptedException) { return }
                continue
            }
            val pkt = DatagramPacket(buf, buf.size)
            try {
                udp.receive(pkt)
            } catch (e: IOException) {
                if (running) try { Thread.sleep(200) } catch (_: InterruptedException) { return }
                continue
            }
            val key = remoteKeyFromSocksUdp(buf, pkt.length) ?: continue
            val client = clientByRemote[key] ?: continue
            val local = localSocket ?: continue
            try {
                // Forward the SOCKS5-UDP reply unchanged back to tun2socks.
                local.send(DatagramPacket(buf, pkt.length, client.address, client.port))
            } catch (e: IOException) {
                Log.d(TAG, "relayToLocalLoop: local send failed: ${e.message}")
            }
        }
    }

    /** Parse a SOCKS5-UDP datagram header (RSV[2] FRAG[1] ATYP[1] addr port ...)
     *  into a demux key over (atyp, addr, port). */
    private fun remoteKeyFromSocksUdp(buf: ByteArray, length: Int): String? {
        if (length < 4) return null
        val atyp = buf[3].toInt() and 0xff
        val addrLen = when (atyp) { 0x01 -> 4; 0x04 -> 16; else -> return null }
        val end = 4 + addrLen + 2
        if (length < end) return null
        val sb = StringBuilder()
        sb.append(atyp).append(':')
        for (i in 4 until end) sb.append(HEX[(buf[i].toInt() ushr 4) and 0xf]).append(HEX[buf[i].toInt() and 0xf])
        return sb.toString()
    }

    private fun portOf(pt: ByteArray): Int = ((pt[0].toInt() and 0xff) shl 8) or (pt[1].toInt() and 0xff)

    private fun closeQuietly(c: java.io.Closeable?) {
        try { c?.close() } catch (_: Exception) {}
    }

    companion object {
        private const val TAG = "UdpgwBridge"
        private val HEX = "0123456789abcdef".toCharArray()
    }
}
