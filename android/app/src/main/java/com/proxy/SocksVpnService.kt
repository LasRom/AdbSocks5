package com.proxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger

class AdbCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "onReceive: action=${intent.action} extras=${describeExtras(intent)}")

        when (intent.action) {
            SocksVpnService.ACTION_START,
            SocksVpnService.ACTION_STOP -> {
                val serviceIntent = Intent(context, SocksVpnService::class.java).apply {
                    action = intent.action
                    putExtras(intent)
                }

                Log.d(TAG, "onReceive: forwarding action=${intent.action} to SocksVpnService")
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                } catch (exception: Exception) {
                    Log.e(
                        TAG,
                        "onReceive: failed to start foreground service from broadcast. " +
                            "On Android 12+ use: adb shell am start-foreground-service " +
                            "-n com.proxy/.AdbCommandService -a ${intent.action} --es config <config>",
                        exception
                    )
                }
            }
            else -> Log.e(TAG, "onReceive: unsupported action=${intent.action}")
        }
    }

    private fun describeExtras(intent: Intent): String {
        return intent.extras?.keySet()?.joinToString(prefix = "[", postfix = "]") ?: "[]"
    }

    companion object {
        private const val TAG = "AdbCommandReceiver"
    }
}

class AdbCommandService : Service() {

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate: command bridge created")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: startId=$startId flags=$flags intent=${describeIntent(intent)}")
        promoteToForeground("Processing ADB command")

        if (intent == null) {
            Log.e(TAG, "onStartCommand: intent is null")
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent.action) {
            SocksVpnService.ACTION_START,
            SocksVpnService.ACTION_STOP -> {
                val serviceIntent = Intent(this, SocksVpnService::class.java).apply {
                    action = intent.action
                    putExtras(intent)
                }

                Log.d(TAG, "onStartCommand: forwarding action=${intent.action} to SocksVpnService")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
            }
            else -> Log.e(TAG, "onStartCommand: unsupported action=${intent.action}")
        }

        stopForegroundCompat()
        stopSelf(startId)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?) = null

    private fun promoteToForeground(statusText: String) {
        Log.d(TAG, "promoteToForeground: status=$statusText")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(statusText),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification(statusText))
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("ADB SOCKS5 VPN")
            .setContentText(statusText)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setPriority(Notification.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Log.d(TAG, "createNotificationChannel: skipped, API < 26")
            return
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        if (notificationManager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) != null) {
            Log.d(TAG, "createNotificationChannel: channel already exists")
            return
        }

        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "ADB SOCKS5 VPN Commands",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Headless ADB command bridge"
            setShowBadge(false)
        }

        notificationManager.createNotificationChannel(channel)
        Log.d(TAG, "createNotificationChannel: channel created id=$NOTIFICATION_CHANNEL_ID")
    }

    private fun stopForegroundCompat() {
        Log.d(TAG, "stopForegroundCompat: removing foreground notification")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun describeIntent(intent: Intent?): String {
        if (intent == null) return "<null>"
        val extras = intent.extras?.keySet()?.joinToString(prefix = "[", postfix = "]") ?: "[]"
        return "action=${intent.action} extras=$extras"
    }

    companion object {
        private const val TAG = "AdbCommandService"
        private const val NOTIFICATION_CHANNEL_ID = "adb_command_bridge"
        private const val NOTIFICATION_ID = 1002
    }
}

class SocksVpnService : VpnService() {

    private var vpnFileDescriptor: ParcelFileDescriptor? = null
    @Volatile private var tun2SocksThread: Thread? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val startGeneration = AtomicInteger(0)

    override fun onCreate() {
        super.onCreate()
        activeService = this
        Log.d(TAG, "onCreate: service created")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: startId=$startId flags=$flags intent=${describeIntent(intent)}")

        promoteToForeground("Starting SOCKS5 VPN")

        if (intent == null) {
            Log.e(TAG, "onStartCommand: intent is null, stopping service")
            stopServiceAfterFailure()
            return START_NOT_STICKY
        }

        return when (intent.action) {
            ACTION_START -> {
                val started = handleStartIntent(intent)
                if (started) START_REDELIVER_INTENT else START_NOT_STICKY
            }
            ACTION_STOP -> {
                Log.d(TAG, "onStartCommand: STOP action received")
                startGeneration.incrementAndGet()
                stopVpn()
                stopForegroundCompat()
                stopSelf()
                START_NOT_STICKY
            }
            else -> {
                Log.e(TAG, "onStartCommand: unsupported action=${intent.action}")
                stopServiceAfterFailure()
                START_NOT_STICKY
            }
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy: service is being destroyed")
        stopVpn()
        if (activeService === this) {
            activeService = null
        }
        super.onDestroy()
    }

    override fun onRevoke() {
        Log.e(TAG, "onRevoke: VPN permission revoked by the system or user")
        stopVpn()
        super.onRevoke()
    }

    private fun handleStartIntent(intent: Intent): Boolean {
        Log.d(TAG, "handleStartIntent: START action received")

        val udpMode = parseUdpMode(intent.getStringExtra(EXTRA_UDP_MODE))
        val proxyConfig = parseProxyConfig(intent.getStringExtra(EXTRA_CONFIG), udpMode)
        if (proxyConfig == null) {
            Log.e(TAG, "handleStartIntent: config parsing failed")
            stopServiceAfterFailure()
            return false
        }

        val consentIntent = prepare(this)
        if (consentIntent != null) {
            Log.e(
                TAG,
                "handleStartIntent: VPN is not prepared. This headless app has no Activity " +
                    "to show the consent dialog; grant VPN consent before starting from ADB."
            )
            stopServiceAfterFailure()
            return false
        }
        Log.d(TAG, "handleStartIntent: VpnService.prepare returned null, VPN permission is ready")

        val allowedPackage = parseAllowedPackage(intent.getStringExtra(EXTRA_ALLOWED_PACKAGE))
        val generation = startGeneration.incrementAndGet()
        promoteToForeground("Checking SOCKS5 proxy")
        runPreflightThenStart(proxyConfig, allowedPackage, generation)
        return true
    }

    private fun runPreflightThenStart(
        config: ProxyConfig,
        allowedPackage: String?,
        generation: Int
    ) {
        val worker = Thread({
            Log.d(TAG, "runPreflightThenStart: preflight worker started generation=$generation")
            val result = runSocks5Preflight(config)
            mainHandler.post {
                if (generation != startGeneration.get()) {
                    Log.d(
                        TAG,
                        "runPreflightThenStart: ignoring stale preflight result generation=$generation"
                    )
                    return@post
                }

                if (!result.success) {
                    Log.e(
                        TAG,
                        "runPreflightThenStart: SOCKS5 preflight failed " +
                            "stage=${result.stage} message=${result.message}"
                    )
                    stopServiceAfterFailure()
                    return@post
                }

                Log.d(
                    TAG,
                        "runPreflightThenStart: SOCKS5 preflight passed " +
                            "stage=${result.stage} message=${result.message}"
                )
                startVpnAfterPreflight(config, allowedPackage)
            }
        }, "socks5-preflight")

        worker.start()
    }

    private fun startVpnAfterPreflight(proxyConfig: ProxyConfig, allowedPackage: String?) {
        try {
            Log.d(TAG, "startVpnAfterPreflight: stopping previous VPN instance if any")
            stopVpn()

            Log.d(TAG, "startVpnAfterPreflight: establishing TUN interface")
            val tunInterface = establishTunInterface(allowedPackage)
            vpnFileDescriptor = tunInterface
            Log.d(TAG, "startVpnAfterPreflight: VPN established, fd=${tunInterface.fd}")

            startTun2Socks(tunInterface, proxyConfig)
            promoteToForeground("Connected to ${proxyConfig.ip}:${proxyConfig.port}")

            Log.d(TAG, "startVpnAfterPreflight: SOCKS5 VPN startup completed")
        } catch (throwable: Throwable) {
            Log.e(TAG, "startVpnAfterPreflight: failed to start VPN", throwable)
            stopServiceAfterFailure()
        }
    }

    private fun runSocks5Preflight(config: ProxyConfig): PreflightResult {
        Log.d(
            TAG,
            "runSocks5Preflight: checking proxy ip=${config.ip} port=${config.port} " +
                "auth=${config.hasAuth}"
        )

        return try {
            Socket().use { socket ->
                socket.tcpNoDelay = true
                socket.soTimeout = PREFLIGHT_READ_TIMEOUT_MS
                socket.connect(
                    InetSocketAddress(config.ip, config.port),
                    PREFLIGHT_CONNECT_TIMEOUT_MS
                )

                val input = socket.getInputStream()
                val output = socket.getOutputStream()
                val method = if (config.hasAuth) SOCKS5_METHOD_USER_PASS else SOCKS5_METHOD_NO_AUTH

                output.write(byteArrayOf(SOCKS5_VERSION, 1, method))
                output.flush()

                val methodResponse = readFully(input, 2)
                if (methodResponse[0] != SOCKS5_VERSION) {
                    return PreflightResult.fail(
                        "handshake",
                        "unexpected SOCKS version=${methodResponse[0].toUnsignedInt()}"
                    )
                }

                val selectedMethod = methodResponse[1]
                if (selectedMethod == SOCKS5_METHOD_NOT_ACCEPTABLE) {
                    return PreflightResult.fail("handshake", "proxy rejected offered auth methods")
                }

                if (selectedMethod == SOCKS5_METHOD_USER_PASS) {
                    val authFailure = authenticateSocks5(config, input, output)
                    if (authFailure != null) return authFailure
                } else if (selectedMethod != SOCKS5_METHOD_NO_AUTH) {
                    return PreflightResult.fail(
                        "handshake",
                        "unsupported auth method=${selectedMethod.toUnsignedInt()}"
                    )
                }

                val targetHostBytes = PREFLIGHT_TARGET_HOST.toByteArray(Charsets.UTF_8)
                if (targetHostBytes.size > 255) {
                    return PreflightResult.fail("connect", "target host is too long")
                }

                output.write(
                    byteArrayOf(
                        SOCKS5_VERSION,
                        SOCKS5_COMMAND_CONNECT,
                        0,
                        SOCKS5_ADDRESS_DOMAIN,
                        targetHostBytes.size.toByte()
                    )
                )
                output.write(targetHostBytes)
                output.write(
                    byteArrayOf(
                        (PREFLIGHT_TARGET_PORT shr 8).toByte(),
                        (PREFLIGHT_TARGET_PORT and 0xff).toByte()
                    )
                )
                output.flush()

                val connectResponse = readFully(input, 4)
                if (connectResponse[0] != SOCKS5_VERSION) {
                    return PreflightResult.fail(
                        "connect",
                        "unexpected CONNECT response version=${connectResponse[0].toUnsignedInt()}"
                    )
                }

                val reply = connectResponse[1]
                if (reply != SOCKS5_REPLY_SUCCESS) {
                    return PreflightResult.fail(
                        "connect",
                        "proxy CONNECT failed reply=${reply.toUnsignedInt()} ${socks5ReplyName(reply)}"
                    )
                }

                drainSocks5BindAddress(input, connectResponse[3])

                val request = (
                    "GET / HTTP/1.1\r\n" +
                        "Host: $PREFLIGHT_TARGET_HOST\r\n" +
                        "Connection: close\r\n" +
                        "\r\n"
                    ).toByteArray(Charsets.US_ASCII)
                output.write(request)
                output.flush()

                val firstByte = input.read()
                if (firstByte < 0) {
                    return PreflightResult.fail("http", "proxy closed connection before HTTP response")
                }

                PreflightResult.ok(
                    "http",
                    "proxy returned HTTP data from $PREFLIGHT_TARGET_HOST:$PREFLIGHT_TARGET_PORT"
                )
            }
        } catch (exception: SocketTimeoutException) {
            PreflightResult.fail("network", "timeout: ${exception.message ?: exception.javaClass.simpleName}")
        } catch (exception: IOException) {
            PreflightResult.fail("network", exception.message ?: exception.javaClass.simpleName)
        } catch (exception: RuntimeException) {
            PreflightResult.fail("network", exception.message ?: exception.javaClass.simpleName)
        }
    }

    private fun authenticateSocks5(
        config: ProxyConfig,
        input: java.io.InputStream,
        output: java.io.OutputStream
    ): PreflightResult? {
        val usernameBytes = config.login.toByteArray(Charsets.UTF_8)
        val passwordBytes = config.password.toByteArray(Charsets.UTF_8)
        if (usernameBytes.size > 255 || passwordBytes.size > 255) {
            return PreflightResult.fail("auth", "username/password is too long for SOCKS5 auth")
        }

        output.write(byteArrayOf(SOCKS5_AUTH_VERSION, usernameBytes.size.toByte()))
        output.write(usernameBytes)
        output.write(passwordBytes.size)
        output.write(passwordBytes)
        output.flush()

        val authResponse = readFully(input, 2)
        if (authResponse[0] != SOCKS5_AUTH_VERSION) {
            return PreflightResult.fail(
                "auth",
                "unexpected auth version=${authResponse[0].toUnsignedInt()}"
            )
        }
        if (authResponse[1] != SOCKS5_AUTH_SUCCESS) {
            return PreflightResult.fail(
                "auth",
                "proxy auth failed status=${authResponse[1].toUnsignedInt()}"
            )
        }

        return null
    }

    private fun readFully(input: java.io.InputStream, size: Int): ByteArray {
        val buffer = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val read = input.read(buffer, offset, size - offset)
            if (read < 0) throw EOFException("unexpected EOF")
            offset += read
        }
        return buffer
    }

    private fun drainSocks5BindAddress(input: java.io.InputStream, addressType: Byte) {
        val addressLength = when (addressType) {
            SOCKS5_ADDRESS_IPV4 -> 4
            SOCKS5_ADDRESS_IPV6 -> 16
            SOCKS5_ADDRESS_DOMAIN -> readFully(input, 1)[0].toUnsignedInt()
            else -> throw IOException("unsupported BND.ADDR type=${addressType.toUnsignedInt()}")
        }
        readFully(input, addressLength + 2)
    }

    private fun socks5ReplyName(reply: Byte): String {
        return when (reply.toUnsignedInt()) {
            1 -> "general failure"
            2 -> "connection not allowed"
            3 -> "network unreachable"
            4 -> "host unreachable"
            5 -> "connection refused"
            6 -> "TTL expired"
            7 -> "command not supported"
            8 -> "address type not supported"
            else -> "unknown"
        }
    }

    private fun Byte.toUnsignedInt(): Int = toInt() and 0xff

    private fun establishTunInterface(allowedPackage: String?): ParcelFileDescriptor {
        val builder = Builder()
            .setSession(VPN_SESSION_NAME)
            .setMtu(TUN_MTU)
            .addAddress(TUN_IPV4_ADDRESS, TUN_IPV4_PREFIX_LENGTH)
            .addRoute("0.0.0.0", 0)
            .addDnsServer(MAPDNS_ADDRESS)
            .setBlocking(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Log.d(TAG, "establishTunInterface: marking VPN as not metered")
            builder.setMetered(false)
        }

        if (allowedPackage != null) {
            try {
                Log.d(TAG, "establishTunInterface: allowing only package=$allowedPackage")
                builder.addAllowedApplication(allowedPackage)
            } catch (exception: Exception) {
                Log.e(TAG, "establishTunInterface: failed to allow package=$allowedPackage", exception)
                throw exception
            }
        } else {
            try {
                Log.d(TAG, "establishTunInterface: excluding own package from VPN to avoid proxy loop")
                builder.addDisallowedApplication(packageName)
            } catch (exception: Exception) {
                Log.e(TAG, "establishTunInterface: failed to exclude own package=$packageName", exception)
            }
        }

        Log.d(
            TAG,
            "establishTunInterface: builder configured address=$TUN_IPV4_ADDRESS/$TUN_IPV4_PREFIX_LENGTH " +
                "route=0.0.0.0/0 dns=$MAPDNS_ADDRESS mtu=$TUN_MTU allowedPackage=$allowedPackage"
        )

        return builder.establish()
            ?: throw IllegalStateException("VpnService.Builder.establish returned null")
    }

    private fun startTun2Socks(vpnFileDescriptor: ParcelFileDescriptor, config: ProxyConfig) {
        Log.d(
            TAG,
            "startTun2Socks: preparing worker for ip=${config.ip} port=${config.port} " +
                "auth=${config.hasAuth} passwordLength=${config.password.length}"
        )

        val tun2SocksLogFile = prepareTun2SocksLogFile()
        val tun2SocksConfig = buildTun2SocksConfig(config, tun2SocksLogFile)
        val worker = Thread({
            Log.d(TAG, "Tun2Socks worker: started")
            try {
                Log.d(TAG, "Tun2Socks worker: calling Tun2Socks.start(vpnFileDescriptor, config)")

                Tun2Socks.start(
                    vpnFileDescriptor,
                    tun2SocksConfig
                )

                Log.d(TAG, "Tun2Socks worker: Tun2Socks.start returned normally")
            } catch (throwable: Throwable) {
                Log.e(TAG, "Tun2Socks worker: Tun2Socks.start failed", throwable)
                val failedThread = Thread.currentThread()
                if (failedThread === tun2SocksThread) {
                    mainHandler.post {
                        if (tun2SocksThread === failedThread) {
                            Log.e(TAG, "Tun2Socks worker: stopping VPN service after worker failure")
                            stopServiceAfterFailure()
                        }
                    }
                }
            } finally {
                dumpTun2SocksLog(tun2SocksLogFile, "worker-exit")
            }
        }, "tun2socks-worker")

        tun2SocksThread = worker
        worker.start()
        Log.d(TAG, "startTun2Socks: worker started")
    }

    private fun prepareTun2SocksLogFile(): File {
        val logFile = File(cacheDir, TUN2SOCKS_LOG_FILE_NAME)
        if (logFile.exists() && !logFile.delete()) {
            Log.e(TAG, "prepareTun2SocksLogFile: failed to delete old log file=${logFile.absolutePath}")
        }
        Log.d(TAG, "prepareTun2SocksLogFile: native log file=${logFile.absolutePath}")
        return logFile
    }

    private fun buildTun2SocksConfig(config: ProxyConfig, logFile: File): String {
        val authConfig = if (config.login.isEmpty() && config.password.isEmpty()) {
            ""
        } else {
            """
              username: '${yamlSingleQuoted(config.login)}'
              password: '${yamlSingleQuoted(config.password)}'
            """.trimEnd()
        }

        return """
            tunnel:
              mtu: $TUN_MTU
            socks5:
              address: '${yamlSingleQuoted(config.ip)}'
              port: ${config.port}
              udp: '${config.udpMode}'
$authConfig
            mapdns:
              address: '$MAPDNS_ADDRESS'
              port: 53
              network: '$MAPDNS_NETWORK'
              netmask: '$MAPDNS_NETMASK'
              cache-size: $MAPDNS_CACHE_SIZE
            misc:
              task-stack-size: 24576
              connect-timeout: 10000
              tcp-read-write-timeout: 300000
              udp-read-write-timeout: 60000
              log-file: '${yamlSingleQuoted(logFile.absolutePath)}'
              log-level: info
        """.trimIndent()
    }

    private fun yamlSingleQuoted(value: String): String {
        return value.replace("'", "''")
    }

    private fun parseAllowedPackage(rawAllowedPackage: String?): String? {
        val allowedPackage = rawAllowedPackage?.trim()?.takeIf { it.isNotEmpty() }
        if (allowedPackage == null) {
            Log.d(TAG, "parseAllowedPackage: full-device VPN mode")
        } else {
            Log.d(TAG, "parseAllowedPackage: package-scoped VPN mode package=$allowedPackage")
        }
        return allowedPackage
    }

    private fun dumpTun2SocksLog(logFile: File, reason: String) {
        if (!logFile.exists()) {
            Log.d(TAG, "dumpTun2SocksLog: no native log for reason=$reason")
            return
        }

        try {
            val lines = logFile.readLines().takeLast(TUN2SOCKS_LOG_TAIL_LINES)
            if (lines.isEmpty()) {
                Log.d(TAG, "dumpTun2SocksLog: native log is empty for reason=$reason")
                return
            }

            Log.d(
                TAG,
                "dumpTun2SocksLog: last ${lines.size} native log lines for reason=$reason"
            )
            for (line in lines) {
                Log.d(TAG, "tun2socks.log: $line")
            }
        } catch (exception: IOException) {
            Log.e(TAG, "dumpTun2SocksLog: failed to read native log for reason=$reason", exception)
        }
    }

    private fun stopVpn() {
        Log.d(TAG, "stopVpn: stopping tun2socks worker and closing VPN fd")

        val worker = tun2SocksThread
        tun2SocksThread = null
        if (worker != null) {
            Log.d(TAG, "stopVpn: requesting Tun2Socks.stop")
            Tun2Socks.stop()
            Log.d(TAG, "stopVpn: interrupting worker name=${worker.name} alive=${worker.isAlive}")
            worker.interrupt()
            // Wait for the native tunnel loop to actually exit before we close
            // the VPN fd or establish a new interface. Without this, a quick
            // reconnect (proxy hot-swap) races the old worker and the fresh
            // tunnel comes up on a half-torn-down state -> "Connection refused".
            if (worker !== Thread.currentThread()) {
                try {
                    worker.join(WORKER_JOIN_TIMEOUT_MS)
                } catch (exception: InterruptedException) {
                    Log.e(TAG, "stopVpn: interrupted while waiting for worker to finish", exception)
                    Thread.currentThread().interrupt()
                }
            }
            Log.d(TAG, "stopVpn: worker join complete alive=${worker.isAlive}")
        } else {
            Log.d(TAG, "stopVpn: no tun2socks worker to interrupt")
        }

        val descriptor = vpnFileDescriptor
        vpnFileDescriptor = null
        if (descriptor != null) {
            try {
                Log.d(TAG, "stopVpn: closing vpn fd=${descriptor.fd}")
                descriptor.close()
            } catch (exception: IOException) {
                Log.e(TAG, "stopVpn: failed to close VPN fd", exception)
            }
        } else {
            Log.d(TAG, "stopVpn: no VPN fd to close")
        }
    }

    private fun stopServiceAfterFailure() {
        Log.d(TAG, "stopServiceAfterFailure: cleaning up after startup failure")
        stopVpn()
        stopForegroundCompat()
        stopSelf()
    }

    private fun parseProxyConfig(rawConfig: String?, udpMode: String): ProxyConfig? {
        Log.d(TAG, "parseProxyConfig: raw config extra=${redactConfig(rawConfig)}")

        if (rawConfig == null) {
            Log.e(TAG, "parseProxyConfig: config extra is missing")
            return null
        }

        val parts = rawConfig.split('|', limit = 4)
        if (parts.size != 4) {
            Log.e(TAG, "parseProxyConfig: expected 4 parts ip|port|login|password, got ${parts.size}")
            return null
        }

        val ip = parts[0].trim()
        val port = parts[1].trim().toIntOrNull()
        val login = parts[2]
        val password = parts[3]

        if (ip.isEmpty()) {
            Log.e(TAG, "parseProxyConfig: ip is empty")
            return null
        }

        if (port == null || port !in 1..65535) {
            Log.e(TAG, "parseProxyConfig: invalid port=${parts[1]}")
            return null
        }

        Log.d(
            TAG,
            "parseProxyConfig: parsed ip=$ip port=$port auth=${login.isNotEmpty() || password.isNotEmpty()} " +
                "passwordLength=${password.length} udp=$udpMode"
        )
        return ProxyConfig(ip, port, login, password, udpMode)
    }

    private fun parseUdpMode(rawUdpMode: String?): String {
        val requested = rawUdpMode?.trim()?.lowercase()
        return when {
            requested.isNullOrEmpty() -> {
                Log.d(TAG, "parseUdpMode: no udp extra, using default=$DEFAULT_UDP_MODE")
                DEFAULT_UDP_MODE
            }
            requested in ALLOWED_UDP_MODES -> {
                Log.d(TAG, "parseUdpMode: using udp mode=$requested")
                requested
            }
            else -> {
                Log.e(
                    TAG,
                    "parseUdpMode: unsupported udp mode=$requested, falling back to " +
                        "$DEFAULT_UDP_MODE (allowed: $ALLOWED_UDP_MODES)"
                )
                DEFAULT_UDP_MODE
            }
        }
    }

    private fun promoteToForeground(statusText: String) {
        Log.d(TAG, "promoteToForeground: status=$statusText")
        val notification = buildNotification(statusText)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("ADB SOCKS5 VPN")
            .setContentText(statusText)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setPriority(Notification.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Log.d(TAG, "createNotificationChannel: skipped, API < 26")
            return
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        if (notificationManager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) != null) {
            Log.d(TAG, "createNotificationChannel: channel already exists")
            return
        }

        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "SOCKS5 VPN",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Headless SOCKS5 VPN foreground service"
            setShowBadge(false)
        }

        notificationManager.createNotificationChannel(channel)
        Log.d(TAG, "createNotificationChannel: channel created id=$NOTIFICATION_CHANNEL_ID")
    }

    private fun stopForegroundCompat() {
        Log.d(TAG, "stopForegroundCompat: removing foreground notification")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun describeIntent(intent: Intent?): String {
        if (intent == null) return "<null>"
        val extras = intent.extras?.keySet()?.joinToString(prefix = "[", postfix = "]") ?: "[]"
        return "action=${intent.action} extras=$extras"
    }

    private fun redactConfig(rawConfig: String?): String {
        if (rawConfig == null) return "<null>"

        val parts = rawConfig.split('|', limit = 4)
        return if (parts.size == 4) {
            "${parts[0]}|${parts[1]}|<redacted:${parts[2].length}>|<redacted:${parts[3].length}>"
        } else {
            "<invalid-format:length=${rawConfig.length}>"
        }
    }

    private data class ProxyConfig(
        val ip: String,
        val port: Int,
        val login: String,
        val password: String,
        val udpMode: String
    ) {
        val hasAuth: Boolean
            get() = login.isNotEmpty() || password.isNotEmpty()
    }

    private data class PreflightResult(
        val success: Boolean,
        val stage: String,
        val message: String
    ) {
        companion object {
            fun ok(stage: String, message: String) = PreflightResult(true, stage, message)
            fun fail(stage: String, message: String) = PreflightResult(false, stage, message)
        }
    }

    companion object {
        const val ACTION_START = "com.proxy.START"
        const val ACTION_STOP = "com.proxy.STOP"
        const val EXTRA_CONFIG = "config"
        const val EXTRA_ALLOWED_PACKAGE = "allowedPackage"
        const val EXTRA_UDP_MODE = "udp"
        @Volatile internal var activeService: SocksVpnService? = null

        private const val TAG = "SocksVpnService"
        private const val VPN_SESSION_NAME = "ADB SOCKS5 VPN"
        private const val NOTIFICATION_CHANNEL_ID = "socks5_vpn"
        private const val NOTIFICATION_ID = 1001

        private const val TUN_MTU = 1500
        private const val TUN_IPV4_ADDRESS = "10.10.0.2"
        private const val TUN_IPV4_PREFIX_LENGTH = 32
        private const val TUN2SOCKS_LOG_FILE_NAME = "tun2socks.log"
        private const val TUN2SOCKS_LOG_TAIL_LINES = 120

        // SOCKS5 UDP relay mode passed to hev-socks5-tunnel:
        //   'udp' -> standard RFC 1928 UDP ASSOCIATE (works with normal proxies;
        //            if the proxy has no UDP, UDP just fails while TCP is fine)
        //   'tcp' -> hev's non-standard UDP-in-TCP (only hev-socks5-server).
        // Default is 'udp' so ordinary commercial SOCKS5 proxies do not receive
        // non-standard framing and reset the whole connection.
        private const val DEFAULT_UDP_MODE = "udp"
        private val ALLOWED_UDP_MODES = setOf("udp", "tcp")
        private const val WORKER_JOIN_TIMEOUT_MS = 3000L

        private const val MAPDNS_ADDRESS = "198.18.0.2"
        private const val MAPDNS_NETWORK = "100.64.0.0"
        private const val MAPDNS_NETMASK = "255.192.0.0"
        private const val MAPDNS_CACHE_SIZE = 10000

        private const val PREFLIGHT_CONNECT_TIMEOUT_MS = 30000
        private const val PREFLIGHT_READ_TIMEOUT_MS = 30000
        private const val PREFLIGHT_TARGET_HOST = "api.ipify.org"
        private const val PREFLIGHT_TARGET_PORT = 80

        private const val SOCKS5_VERSION: Byte = 5
        private const val SOCKS5_COMMAND_CONNECT: Byte = 1
        private const val SOCKS5_METHOD_NO_AUTH: Byte = 0
        private const val SOCKS5_METHOD_USER_PASS: Byte = 2
        private const val SOCKS5_METHOD_NOT_ACCEPTABLE: Byte = -1
        private const val SOCKS5_AUTH_VERSION: Byte = 1
        private const val SOCKS5_AUTH_SUCCESS: Byte = 0
        private const val SOCKS5_REPLY_SUCCESS: Byte = 0
        private const val SOCKS5_ADDRESS_IPV4: Byte = 1
        private const val SOCKS5_ADDRESS_DOMAIN: Byte = 3
        private const val SOCKS5_ADDRESS_IPV6: Byte = 4
    }
}

object Tun2Socks {
    private const val TAG = "Tun2Socks"

    init {
        System.loadLibrary("hev-socks5-tunnel")
        System.loadLibrary("tun2socks_jni")
    }

    fun start(vpnFileDescriptor: ParcelFileDescriptor, config: String) {
        val result = nativeStart(config, vpnFileDescriptor.fd)
        if (result != 0) {
            throw IllegalStateException("hev_socks5_tunnel_main_from_str failed with code=$result")
        }
    }

    fun stop() {
        nativeStop()
    }

    @Suppress("unused")
    fun protectSocket(socketFd: Int): Boolean {
        val service = SocksVpnService.activeService
        if (service == null) {
            Log.e(TAG, "protectSocket: no active VpnService for fd=$socketFd")
            return false
        }

        val protected = service.protect(socketFd)
        Log.d(TAG, "protectSocket: fd=$socketFd protected=$protected")
        return protected
    }

    private external fun nativeStart(config: String, tunFd: Int): Int
    private external fun nativeStop()
}
