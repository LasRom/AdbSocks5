package com.proxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import java.io.File
import java.io.IOException
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

/**
 * VpnService that routes device traffic to a SOCKS5 proxy using the SocksDroid
 * engine model: BadVPN tun2socks (a separate native process that receives the
 * TUN fd over a unix socket) plus pdnsd (resolves DNS over TCP through the
 * proxy). No SOCKS5 preflight — the tunnel is established immediately. Runs in
 * its own ":tunnel" process (see manifest); killed on STOP for a clean restart.
 */
class SocksVpnService : VpnService() {

    @Volatile private var vpnFileDescriptor: ParcelFileDescriptor? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val startGeneration = AtomicInteger(0)

    // Answers a QUERY_STATE from the UI (which lives in a different process and
    // cannot read our static state) by re-broadcasting the current state.
    private val queryStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_QUERY_STATE) {
                Log.d(TAG, "queryStateReceiver: re-broadcasting state=$currentState")
                broadcastState(currentState, currentStateDetail)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        activeService = this
        Log.d(TAG, "onCreate: service created")
        createNotificationChannel()
        registerQueryStateReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: startId=$startId flags=$flags intent=${describeIntent(intent)}")

        promoteToForeground("Starting SOCKS5 VPN")

        if (intent == null) {
            Log.e(TAG, "onStartCommand: intent is null, stopping service")
            stopServiceAfterFailure("no intent")
            return START_NOT_STICKY
        }

        return when (intent.action) {
            ACTION_START -> {
                cancelTunnelProcessKill()
                val started = handleStartIntent(intent)
                if (started) START_REDELIVER_INTENT else START_NOT_STICKY
            }
            ACTION_STOP -> {
                Log.d(TAG, "onStartCommand: STOP action received")
                startGeneration.incrementAndGet()
                stopVpn()
                broadcastState(STATE_STOPPED)
                stopForegroundCompat()
                stopSelf()
                scheduleTunnelProcessKill()
                START_NOT_STICKY
            }
            else -> {
                Log.e(TAG, "onStartCommand: unsupported action=${intent.action}")
                stopServiceAfterFailure("unsupported action")
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
        try {
            unregisterReceiver(queryStateReceiver)
        } catch (_: IllegalArgumentException) {
            // Not registered; ignore.
        }
        super.onDestroy()
    }

    override fun onRevoke() {
        Log.e(TAG, "onRevoke: VPN permission revoked by the system or user")
        stopVpn()
        broadcastState(STATE_STOPPED, "VPN revoked")
        scheduleTunnelProcessKill()
        super.onRevoke()
    }

    private fun registerQueryStateReceiver() {
        val filter = IntentFilter(ACTION_QUERY_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(queryStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(queryStateReceiver, filter)
        }
    }

    /**
     * The tunnel runs in its own ":tunnel" process (see manifest). After a
     * user-initiated teardown we kill that process so the next START comes up in
     * a fresh process. The native daemons (tun2socks, pdnsd) daemonize and
     * reparent away, so killing this process does not stop them — they are
     * stopped explicitly by pid file in stopVpn(). The UI lives in the main
     * process and is unaffected. Delayed slightly so the STATE broadcast and
     * foreground notification removal are dispatched first.
     */
    private val killProcessRunnable = Runnable {
        Log.d(TAG, "killProcessRunnable: killing :tunnel process pid=${Process.myPid()}")
        Process.killProcess(Process.myPid())
    }

    private fun scheduleTunnelProcessKill() {
        mainHandler.removeCallbacks(killProcessRunnable)
        mainHandler.postDelayed(killProcessRunnable, PROCESS_KILL_DELAY_MS)
    }

    private fun cancelTunnelProcessKill() {
        // A new START arrived before the delayed kill fired (rapid reconnect in
        // the same process). Do not kill a process that is now connecting.
        mainHandler.removeCallbacks(killProcessRunnable)
    }

    private fun handleStartIntent(intent: Intent): Boolean {
        Log.d(TAG, "handleStartIntent: START action received")

        val proxyConfig = parseProxyConfig(intent.getStringExtra(EXTRA_CONFIG))
        if (proxyConfig == null) {
            Log.e(TAG, "handleStartIntent: config parsing failed")
            stopServiceAfterFailure("invalid config")
            return false
        }

        val consentIntent = prepare(this)
        if (consentIntent != null) {
            Log.e(
                TAG,
                "handleStartIntent: VPN is not prepared. Grant VPN consent (via the UI or " +
                    "`appops set com.proxy ACTIVATE_VPN allow`) before starting from ADB."
            )
            stopServiceAfterFailure("VPN consent not granted")
            return false
        }

        val allowedPackage = parseAllowedPackage(intent.getStringExtra(EXTRA_ALLOWED_PACKAGE))
        val udpgw = intent.getStringExtra(EXTRA_UDPGW)?.trim()?.takeIf { it.isNotEmpty() }
        val generation = startGeneration.incrementAndGet()

        promoteToForeground("Connecting to ${proxyConfig.ip}:${proxyConfig.port}")
        broadcastState(STATE_CONNECTING, "${proxyConfig.ip}:${proxyConfig.port}")
        startTunnel(proxyConfig, allowedPackage, udpgw, generation)
        return true
    }

    private fun startTunnel(
        config: ProxyConfig,
        allowedPackage: String?,
        udpgw: String?,
        generation: Int
    ) {
        // Off the main thread: establish + exec daemons + hand over the tun fd
        // (the fd handover retries with backoff and must not block the UI/ANR).
        val worker = Thread({
            try {
                stopTunnelProcesses()

                Log.d(TAG, "startTunnel: establishing TUN interface")
                val tunInterface = establishTunInterface(allowedPackage)

                // Only publish the descriptor once we know this start is current.
                // A superseded (stale) worker must close its OWN local interface,
                // never the shared field — otherwise it could close the winning
                // worker's live fd (and racing raw closes trip fdsan).
                if (generation != startGeneration.get()) {
                    Log.d(TAG, "startTunnel: stale generation=$generation, closing own tun")
                    try {
                        tunInterface.close()
                    } catch (exception: IOException) {
                        Log.e(TAG, "startTunnel: failed to close stale tun", exception)
                    }
                    return@Thread
                }

                vpnFileDescriptor = tunInterface
                val fd = tunInterface.fd
                Log.d(TAG, "startTunnel: VPN established fd=$fd")

                writePdnsdConf()
                startPdnsd()
                val rc = startTun2Socks(config, fd, udpgw)
                if (rc != 0) {
                    throw IOException("tun2socks exec returned $rc")
                }

                if (!sendTunFd(fd)) {
                    throw IOException("failed to hand TUN fd to tun2socks")
                }

                mainHandler.post {
                    if (generation != startGeneration.get()) {
                        Log.d(TAG, "startTunnel: stale generation after handover, ignoring")
                        return@post
                    }
                    promoteToForeground("Connected to ${config.ip}:${config.port}")
                    broadcastState(STATE_CONNECTED, "${config.ip}:${config.port}")
                    Log.d(TAG, "startTunnel: tunnel up for ${config.ip}:${config.port}")
                }
            } catch (throwable: Throwable) {
                Log.e(TAG, "startTunnel: failed to start tunnel", throwable)
                mainHandler.post {
                    if (generation == startGeneration.get()) {
                        stopServiceAfterFailure(throwable.message ?: "tunnel start failed")
                    }
                }
            }
        }, "tunnel-start")
        worker.start()
    }

    private fun establishTunInterface(allowedPackage: String?): ParcelFileDescriptor {
        val builder = Builder()
            .setSession(VPN_SESSION_NAME)
            .setMtu(TUN_MTU)
            .addAddress(TUN_ADDRESS, TUN_PREFIX_LENGTH)
            .addRoute("0.0.0.0", 0)
            .addRoute(DNS_STUB, 32)
            .addDnsServer(DNS_STUB)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        if (allowedPackage != null) {
            Log.d(TAG, "establishTunInterface: allowing only package=$allowedPackage")
            builder.addAllowedApplication(allowedPackage)
        } else {
            // Full-device: bypass our own UID so tun2socks/pdnsd upstream sockets
            // do not loop back into the tunnel (SocksDroid does exactly this).
            try {
                Log.d(TAG, "establishTunInterface: excluding own package to avoid proxy loop")
                builder.addDisallowedApplication(packageName)
            } catch (exception: Exception) {
                Log.e(TAG, "establishTunInterface: failed to exclude own package", exception)
            }
        }

        Log.d(
            TAG,
            "establishTunInterface: address=$TUN_ADDRESS/$TUN_PREFIX_LENGTH route=0.0.0.0/0 " +
                "dns=$DNS_STUB mtu=$TUN_MTU allowedPackage=$allowedPackage"
        )

        return builder.establish()
            ?: throw IllegalStateException("VpnService.Builder.establish returned null")
    }

    private fun startPdnsd() {
        val cmd = arrayOf(
            "${applicationInfo.nativeLibraryDir}/$LIB_PDNSD",
            "-c",
            "${filesDir}/pdnsd.conf"
        )
        Log.d(TAG, "startPdnsd: ${cmd.joinToString(" ")}")
        val rc = execAndWait(cmd)
        // pdnsd daemonizes (daemon=on); a non-zero here is logged but DNS failure
        // is not fatal to the TCP tunnel.
        Log.d(TAG, "startPdnsd: exit=$rc")
    }

    private fun startTun2Socks(config: ProxyConfig, fd: Int, udpgw: String?): Int {
        val command = ArrayList<String>()
        command.add("${applicationInfo.nativeLibraryDir}/$LIB_TUN2SOCKS")
        command.add("--netif-ipaddr"); command.add(TUN_NETIF_IPADDR)
        command.add("--netif-netmask"); command.add(TUN_NETIF_NETMASK)
        command.add("--socks-server-addr"); command.add("${config.ip}:${config.port}")
        command.add("--tunfd"); command.add(fd.toString())
        command.add("--tunmtu"); command.add(TUN_MTU.toString())
        command.add("--loglevel"); command.add("3")
        command.add("--pid"); command.add("${filesDir}/tun2socks.pid")
        command.add("--sock"); command.add("${applicationInfo.dataDir}/sock_path")
        if (config.hasAuth) {
            command.add("--username"); command.add(config.login)
            command.add("--password"); command.add(config.password)
        }
        command.add("--dnsgw"); command.add(DNSGW)
        if (udpgw != null) {
            command.add("--udpgw-remote-server-addr"); command.add(udpgw)
        }

        Log.d(TAG, "startTun2Socks: launching tun2socks (server=${config.ip}:${config.port} auth=${config.hasAuth})")
        return execAndWait(command.toTypedArray())
    }

    private fun sendTunFd(fd: Int): Boolean {
        val sockPath = "${applicationInfo.dataDir}/sock_path"
        for (attempt in 1..FD_SEND_ATTEMPTS) {
            if (Native.sendfd(fd, sockPath) != -1) {
                Log.d(TAG, "sendTunFd: handed fd=$fd to tun2socks on attempt=$attempt")
                return true
            }
            Log.d(TAG, "sendTunFd: attempt=$attempt failed, retrying")
            try {
                Thread.sleep(1000L * attempt)
            } catch (exception: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return false
    }

    private fun writePdnsdConf() {
        val dir = filesDir.absolutePath
        val conf = PDNSD_CONF_TEMPLATE
            .replace("{DIR}", dir)
            .replace("{IP}", DNS_UPSTREAM)
            .replace("{PORT}", DNS_UPSTREAM_PORT.toString())
        File(filesDir, "pdnsd.conf").writeText(conf)
        val cache = File(filesDir, "pdnsd.cache")
        if (!cache.exists()) {
            try {
                cache.createNewFile()
            } catch (exception: IOException) {
                Log.e(TAG, "writePdnsdConf: failed to create pdnsd.cache", exception)
            }
        }
    }

    private fun execAndWait(command: Array<String>): Int {
        return try {
            Runtime.getRuntime().exec(command).waitFor()
        } catch (exception: Exception) {
            Log.e(TAG, "execAndWait: failed to run ${command.firstOrNull()}", exception)
            -1
        }
    }

    private fun stopTunnelProcesses() {
        killPidFile(File(filesDir, "tun2socks.pid"))
        killPidFile(File(filesDir, "pdnsd.pid"))
    }

    private fun killPidFile(file: File) {
        if (!file.exists()) return
        try {
            val pid = file.readText().trim().toIntOrNull()
            if (pid != null && pid > 0) {
                Log.d(TAG, "killPidFile: killing pid=$pid from ${file.name}")
                Runtime.getRuntime().exec(arrayOf("kill", pid.toString())).waitFor()
            }
        } catch (exception: Exception) {
            Log.e(TAG, "killPidFile: failed for ${file.name}", exception)
        } finally {
            file.delete()
        }
    }

    private fun stopVpn() {
        Log.d(TAG, "stopVpn: stopping tunnel processes and closing VPN fd")
        stopTunnelProcesses()

        val descriptor = vpnFileDescriptor
        vpnFileDescriptor = null
        if (descriptor != null) {
            // Close through ParcelFileDescriptor only. A raw close() on its fd
            // (e.g. Native.jniclose) trips Android fdsan (double-close abort),
            // since the fd is fdsan-tagged. tun2socks holds its own dup, so this
            // only releases our copy; killing tun2socks releases the tunnel.
            try {
                descriptor.close()
            } catch (exception: IOException) {
                Log.e(TAG, "stopVpn: failed to close VPN fd", exception)
            }
        } else {
            Log.d(TAG, "stopVpn: no VPN fd to close")
        }
    }

    private fun stopServiceAfterFailure(reason: String? = null) {
        Log.d(TAG, "stopServiceAfterFailure: cleaning up after startup failure reason=$reason")
        broadcastState(STATE_ERROR, reason)
        stopVpn()
        stopForegroundCompat()
        stopSelf()
    }

    private fun broadcastState(state: String, detail: String? = null) {
        currentState = state
        currentStateDetail = detail
        Log.d(TAG, "broadcastState: state=$state detail=$detail")
        val intent = Intent(ACTION_STATE)
            .setPackage(packageName)
            .putExtra(EXTRA_STATE, state)
            .putExtra(EXTRA_STATE_DETAIL, detail)
        sendBroadcast(intent)
    }

    private fun parseProxyConfig(rawConfig: String?): ProxyConfig? {
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
                "passwordLength=${password.length}"
        )
        return ProxyConfig(ip, port, login, password)
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
        val password: String
    ) {
        val hasAuth: Boolean
            get() = login.isNotEmpty() || password.isNotEmpty()
    }

    companion object {
        const val ACTION_START = "com.proxy.START"
        const val ACTION_STOP = "com.proxy.STOP"
        const val EXTRA_CONFIG = "config"
        const val EXTRA_ALLOWED_PACKAGE = "allowedPackage"

        // Optional BadVPN udpgw server "addr:port" for UDP relay. Without it UDP
        // is not relayed (DNS still works via pdnsd). Kept for parity with
        // SocksDroid; most commercial SOCKS5 proxies have no udpgw.
        const val EXTRA_UDPGW = "udpgw"

        // Lightweight state signalling for the optional on-device UI
        // (MainActivity). Does not affect the headless ADB path.
        const val ACTION_STATE = "com.proxy.STATE"
        const val ACTION_QUERY_STATE = "com.proxy.QUERY_STATE"
        const val EXTRA_STATE = "state"
        const val EXTRA_STATE_DETAIL = "detail"
        const val STATE_CONNECTING = "connecting"
        const val STATE_CONNECTED = "connected"
        const val STATE_STOPPED = "stopped"
        const val STATE_ERROR = "error"
        @Volatile internal var currentState: String = STATE_STOPPED
        @Volatile internal var currentStateDetail: String? = null

        @Volatile internal var activeService: SocksVpnService? = null

        private const val TAG = "SocksVpnService"
        private const val VPN_SESSION_NAME = "ADB SOCKS5 VPN"
        private const val NOTIFICATION_CHANNEL_ID = "socks5_vpn"
        private const val NOTIFICATION_ID = 1001

        private const val LIB_TUN2SOCKS = "libtun2socks.so"
        private const val LIB_PDNSD = "libpdnsd.so"

        // TUN + tun2socks addressing (mirrors SocksDroid).
        private const val TUN_MTU = 1500
        private const val TUN_ADDRESS = "26.26.26.1"
        private const val TUN_PREFIX_LENGTH = 24
        private const val TUN_NETIF_IPADDR = "26.26.26.2"
        private const val TUN_NETIF_NETMASK = "255.255.255.0"

        // DNS: a stub server the system routes into the tun; tun2socks --dnsgw
        // redirects it to pdnsd (listening on 26.26.26.1:8091) which resolves
        // over TCP through the proxy/upstream.
        private const val DNS_STUB = "8.8.8.8"
        private const val DNSGW = "26.26.26.1:8091"
        private const val DNS_UPSTREAM = "8.8.8.8"
        private const val DNS_UPSTREAM_PORT = 53

        private const val FD_SEND_ATTEMPTS = 5
        private const val PROCESS_KILL_DELAY_MS = 400L

        private val PDNSD_CONF_TEMPLATE = """
            global {
                perm_cache=1024;
                cache_dir="{DIR}";
                server_port = 8091;
                server_ip = 0.0.0.0;
                query_method=tcp_only;
                min_ttl=15m;
                max_ttl=1w;
                timeout=10;
                daemon=on;
                pid_file="{DIR}/pdnsd.pid";
            }

            server {
                label= "upstream";
                ip = {IP};
                port = {PORT};
                uptest = none;
            }

            rr {
                name=localhost;
                reverse=on;
                a=127.0.0.1;
                owner=localhost;
                soa=localhost,root.localhost,42,86400,900,86400,86400;
            }
        """.trimIndent()
    }
}

/**
 * Tiny JNI helper (libsystem.so, from SocksDroid) for handing the TUN file
 * descriptor to the tun2socks process over a unix socket, and closing it.
 * The native library registers these against class com/proxy/Native.
 */
object Native {
    init {
        System.loadLibrary("system")
    }

    external fun sendfd(fd: Int, sock: String): Int

    // Unused by us (we close via ParcelFileDescriptor to stay fdsan-safe), but
    // must stay declared: libsystem.so's JNI_OnLoad RegisterNatives binds both
    // methods against com/proxy/Native, and a missing one fails library load.
    @Suppress("unused")
    external fun jniclose(fd: Int)
}
