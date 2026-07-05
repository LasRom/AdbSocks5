package com.proxy

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Optional on-device UI for launching the SOCKS5 VPN manually.
 *
 * This does NOT replace the headless ADB path: [AdbCommandReceiver] /
 * [AdbCommandService] and the `com.proxy.START` / `com.proxy.STOP` intents keep
 * working exactly as before. The UI just collects a proxy config, obtains VPN
 * consent through the standard system dialog, and starts the very same
 * [SocksVpnService] with the same action + extras. Both entry points converge.
 */
class MainActivity : Activity() {

    private lateinit var ipField: EditText
    private lateinit var portField: EditText
    private lateinit var loginField: EditText
    private lateinit var passwordField: EditText
    private lateinit var statusView: TextView
    private lateinit var connectButton: Button
    private lateinit var disconnectButton: Button

    private var pendingConfig: String? = null

    private val prefs by lazy { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != SocksVpnService.ACTION_STATE) return
            val state = intent.getStringExtra(SocksVpnService.EXTRA_STATE) ?: return
            val detail = intent.getStringExtra(SocksVpnService.EXTRA_STATE_DETAIL)
            renderState(state, detail)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "ADB SOCKS5 VPN"
        setContentView(buildContent())
        loadConfig()
        maybeRequestNotificationPermission()
        // The tunnel runs in a separate ":tunnel" process, so its static state
        // is not readable here. Default to stopped and ask it live via a query;
        // if the tunnel process is alive it answers with a STATE broadcast,
        // otherwise there is nothing running and stopped is correct.
        renderState(SocksVpnService.STATE_STOPPED, null)
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(SocksVpnService.ACTION_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(stateReceiver, filter)
        }
        queryTunnelState()
    }

    private fun queryTunnelState() {
        val intent = Intent(SocksVpnService.ACTION_QUERY_STATE).setPackage(packageName)
        sendBroadcast(intent)
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(stateReceiver)
        } catch (_: IllegalArgumentException) {
            // Not registered; ignore.
        }
    }

    private fun buildContent(): View {
        val pad = dp(20)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(headerLabel("Прокси SOCKS5"))

        ipField = addField(root, "IP или хост", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        portField = addField(root, "Порт", InputType.TYPE_CLASS_NUMBER)
        loginField = addField(root, "Логин (пусто = без авторизации)", InputType.TYPE_CLASS_TEXT)
        passwordField = addField(
            root,
            "Пароль (пусто = без авторизации)",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        ).also { it.transformationMethod = PasswordTransformationMethod.getInstance() }

        val showPassword = CheckBox(this).apply {
            text = "Показать пароль"
            setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
                passwordField.transformationMethod =
                    if (checked) null else PasswordTransformationMethod.getInstance()
                passwordField.setSelection(passwordField.text?.length ?: 0)
            }
        }
        root.addView(showPassword)

        connectButton = Button(this).apply {
            text = "Подключить"
            layoutParams = marginParams(top = dp(20))
            setOnClickListener { onConnectClicked() }
        }
        root.addView(connectButton)

        disconnectButton = Button(this).apply {
            text = "Отключить"
            layoutParams = marginParams(top = dp(8))
            setOnClickListener { onDisconnectClicked() }
        }
        root.addView(disconnectButton)

        statusView = TextView(this).apply {
            layoutParams = marginParams(top = dp(20))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        }
        root.addView(statusView)

        root.addView(hintLabel("ADB-запуск по-прежнему работает:\nam start-foreground-service -n com.proxy/.AdbCommandService -a com.proxy.START --es config \"ip|port|login|password\""))

        return ScrollView(this).apply { addView(root) }
    }

    private fun onConnectClicked() {
        val ip = ipField.text.toString().trim()
        val portText = portField.text.toString().trim()
        val login = loginField.text.toString()
        val password = passwordField.text.toString()

        if (ip.isEmpty()) {
            toast("Укажите IP или хост")
            return
        }
        val port = portText.toIntOrNull()
        if (port == null || port !in 1..65535) {
            toast("Порт должен быть числом 1..65535")
            return
        }
        if (login.contains('|') ) {
            toast("Логин не может содержать символ '|'")
            return
        }

        val config = "$ip|$port|$login|$password"
        saveConfig(ip, port, login, password)

        pendingConfig = config

        val consent = VpnService.prepare(this)
        if (consent != null) {
            Log.d(TAG, "onConnectClicked: requesting VPN consent")
            renderState(SocksVpnService.STATE_CONNECTING, "запрос разрешения VPN")
            startActivityForResult(consent, REQUEST_VPN_CONSENT)
        } else {
            startVpn(config)
        }
    }

    // Framework onActivityResult (not deprecated in android.app.Activity);
    // used deliberately to avoid an AndroidX activity-result dependency.
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_VPN_CONSENT) return
        if (resultCode == RESULT_OK) {
            val config = pendingConfig
            if (config != null) {
                startVpn(config)
            }
        } else {
            Log.e(TAG, "onActivityResult: VPN consent denied")
            renderState(SocksVpnService.STATE_ERROR, "разрешение VPN отклонено")
        }
    }

    private fun startVpn(config: String) {
        Log.d(TAG, "startVpn: launching SocksVpnService")
        val intent = Intent(this, SocksVpnService::class.java).apply {
            action = SocksVpnService.ACTION_START
            putExtra(SocksVpnService.EXTRA_CONFIG, config)
        }
        startForegroundService(intent)
        renderState(SocksVpnService.STATE_CONNECTING, "подключение")
    }

    private fun onDisconnectClicked() {
        Log.d(TAG, "onDisconnectClicked: stopping SocksVpnService")
        val intent = Intent(this, SocksVpnService::class.java).apply {
            action = SocksVpnService.ACTION_STOP
        }
        startForegroundService(intent)
    }

    private fun renderState(state: String, detail: String?) {
        val (label, color) = when (state) {
            SocksVpnService.STATE_CONNECTING -> "Подключение…" to Color.parseColor("#B08900")
            SocksVpnService.STATE_CONNECTED -> "Подключено" to Color.parseColor("#1B7F1B")
            SocksVpnService.STATE_ERROR -> "Ошибка" to Color.parseColor("#B00020")
            else -> "Отключено" to Color.GRAY
        }
        val suffix = if (!detail.isNullOrEmpty()) ": $detail" else ""
        statusView.text = "Статус: $label$suffix"
        statusView.setTextColor(color)

        val busy = state == SocksVpnService.STATE_CONNECTING || state == SocksVpnService.STATE_CONNECTED
        connectButton.isEnabled = !busy
        disconnectButton.isEnabled = busy
    }

    private fun loadConfig() {
        ipField.setText(prefs.getString(KEY_IP, ""))
        val port = prefs.getInt(KEY_PORT, 0)
        portField.setText(if (port in 1..65535) port.toString() else "")
        loginField.setText(prefs.getString(KEY_LOGIN, ""))
        passwordField.setText(prefs.getString(KEY_PASSWORD, ""))
    }

    private fun saveConfig(ip: String, port: Int, login: String, password: String) {
        prefs.edit()
            .putString(KEY_IP, ip)
            .putInt(KEY_PORT, port)
            .putString(KEY_LOGIN, login)
            .putString(KEY_PASSWORD, password)
            .apply()
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
    }

    private fun addField(parent: ViewGroup, hint: String, inputType: Int): EditText {
        val field = EditText(this).apply {
            this.hint = hint
            this.inputType = inputType
            layoutParams = marginParams(top = dp(8))
        }
        parent.addView(field)
        return field
    }

    private fun headerLabel(text: String): TextView =
        TextView(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = marginParams(bottom = dp(8))
        }

    private fun hintLabel(text: String): TextView =
        TextView(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Color.GRAY)
            layoutParams = marginParams(top = dp(6))
        }

    private fun marginParams(
        top: Int = 0,
        bottom: Int = 0
    ): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, top, 0, bottom) }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_VPN_CONSENT = 1001
        private const val REQUEST_NOTIFICATIONS = 1002

        private const val PREFS_NAME = "adbsocks5_ui"
        private const val KEY_IP = "ip"
        private const val KEY_PORT = "port"
        private const val KEY_LOGIN = "login"
        private const val KEY_PASSWORD = "password"
    }
}
