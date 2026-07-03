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
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.IOException

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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
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

class SocksVpnService : VpnService() {

    private var vpnFileDescriptor: ParcelFileDescriptor? = null
    @Volatile private var tun2SocksThread: Thread? = null

    override fun onCreate() {
        super.onCreate()
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
        super.onDestroy()
    }

    override fun onRevoke() {
        Log.e(TAG, "onRevoke: VPN permission revoked by the system or user")
        stopVpn()
        super.onRevoke()
    }

    private fun handleStartIntent(intent: Intent): Boolean {
        Log.d(TAG, "handleStartIntent: START action received")

        val proxyConfig = parseProxyConfig(intent.getStringExtra(EXTRA_CONFIG))
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

        return try {
            Log.d(TAG, "handleStartIntent: stopping previous VPN instance if any")
            stopVpn()

            Log.d(TAG, "handleStartIntent: establishing TUN interface")
            val tunInterface = establishTunInterface()
            vpnFileDescriptor = tunInterface
            Log.d(TAG, "handleStartIntent: VPN established, fd=${tunInterface.fd}")

            startTun2Socks(tunInterface, proxyConfig)
            promoteToForeground("Connected to ${proxyConfig.ip}:${proxyConfig.port}")

            Log.d(TAG, "handleStartIntent: SOCKS5 VPN startup completed")
            true
        } catch (throwable: Throwable) {
            Log.e(TAG, "handleStartIntent: failed to start VPN", throwable)
            stopServiceAfterFailure()
            false
        }
    }

    private fun establishTunInterface(): ParcelFileDescriptor {
        val builder = Builder()
            .setSession(VPN_SESSION_NAME)
            .setMtu(TUN_MTU)
            .addAddress(TUN_IPV4_ADDRESS, TUN_IPV4_PREFIX_LENGTH)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("1.1.1.1")
            .addDnsServer("8.8.8.8")
            .setBlocking(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Log.d(TAG, "establishTunInterface: marking VPN as not metered")
            builder.setMetered(false)
        }

        try {
            Log.d(TAG, "establishTunInterface: excluding own package from VPN to avoid proxy loop")
            builder.addDisallowedApplication(packageName)
        } catch (exception: Exception) {
            Log.e(TAG, "establishTunInterface: failed to exclude own package=$packageName", exception)
        }

        Log.d(
            TAG,
            "establishTunInterface: builder configured address=$TUN_IPV4_ADDRESS/$TUN_IPV4_PREFIX_LENGTH " +
                "route=0.0.0.0/0 dns=1.1.1.1,8.8.8.8 mtu=$TUN_MTU"
        )

        return builder.establish()
            ?: throw IllegalStateException("VpnService.Builder.establish returned null")
    }

    private fun startTun2Socks(vpnFileDescriptor: ParcelFileDescriptor, config: ProxyConfig) {
        Log.d(
            TAG,
            "startTun2Socks: preparing worker for ip=${config.ip} port=${config.port} " +
                "login=${config.login} passwordLength=${config.password.length}"
        )

        val worker = Thread({
            Log.d(TAG, "Tun2Socks worker: started")
            try {
                Log.d(TAG, "Tun2Socks worker: calling Tun2Socks.start(vpnFileDescriptor, ip, port, login, password)")

                Tun2Socks.start(
                    vpnFileDescriptor,
                    config.ip,
                    config.port,
                    config.login,
                    config.password
                )

                Log.d(TAG, "Tun2Socks worker: Tun2Socks.start returned normally")
            } catch (throwable: Throwable) {
                Log.e(TAG, "Tun2Socks worker: Tun2Socks.start failed", throwable)
                if (Thread.currentThread() === tun2SocksThread) {
                    stopSelf()
                }
            }
        }, "tun2socks-worker")

        tun2SocksThread = worker
        worker.start()
        Log.d(TAG, "startTun2Socks: worker started")
    }

    private fun stopVpn() {
        Log.d(TAG, "stopVpn: stopping tun2socks worker and closing VPN fd")

        val worker = tun2SocksThread
        tun2SocksThread = null
        if (worker != null) {
            Log.d(TAG, "stopVpn: interrupting worker name=${worker.name} alive=${worker.isAlive}")
            worker.interrupt()
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

    private fun parseProxyConfig(rawConfig: String?): ProxyConfig? {
        Log.d(TAG, "parseProxyConfig: raw config extra=${redactConfig(rawConfig)}")

        if (rawConfig == null) {
            Log.e(TAG, "parseProxyConfig: config extra is missing")
            return null
        }

        val parts = rawConfig.split('|')
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
            "parseProxyConfig: parsed ip=$ip port=$port login=$login passwordLength=${password.length}"
        )
        return ProxyConfig(ip, port, login, password)
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

        val parts = rawConfig.split('|')
        return if (parts.size == 4) {
            "${parts[0]}|${parts[1]}|${parts[2]}|<redacted:${parts[3].length}>"
        } else {
            "<invalid-format:length=${rawConfig.length}>"
        }
    }

    private data class ProxyConfig(
        val ip: String,
        val port: Int,
        val login: String,
        val password: String
    )

    companion object {
        const val ACTION_START = "com.proxy.START"
        const val ACTION_STOP = "com.proxy.STOP"
        const val EXTRA_CONFIG = "config"

        private const val TAG = "SocksVpnService"
        private const val VPN_SESSION_NAME = "ADB SOCKS5 VPN"
        private const val NOTIFICATION_CHANNEL_ID = "socks5_vpn"
        private const val NOTIFICATION_ID = 1001

        private const val TUN_MTU = 1500
        private const val TUN_IPV4_ADDRESS = "10.10.0.2"
        private const val TUN_IPV4_PREFIX_LENGTH = 32
    }
}

object Tun2Socks {
    external fun start(
        vpnFileDescriptor: ParcelFileDescriptor,
        ip: String,
        port: Int,
        login: String,
        password: String
    )
}
