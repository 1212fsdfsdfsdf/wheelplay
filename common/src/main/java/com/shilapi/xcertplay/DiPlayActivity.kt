// SPDX-License-Identifier: AGPL-3.0-only
// UI copy and visual language adapted from DiAuto. See docs/THIRD_PARTY_NOTICES.md.
package com.shilapi.xcertplay

import android.Manifest
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.shilapi.xcertplay.web.WebSession
import com.shilapi.xcertplay.web.RootAccess
import com.shilapi.xcertplay.web.LanAddresses
import com.shilapi.xcertplay.web.TeslaHttpCompatibility
import com.shilapi.xcertplay.web.LanTls
import com.shilapi.xcertplay.web.BrowserExperienceLink
import com.shilapi.xcertplay.network.CarPlayVpnService
import com.shilapi.xcertplay.network.TeslaHttpMode
import io.github.g00fy2.quickie.QRResult
import io.github.g00fy2.quickie.ScanQRCode
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.compose.ui.platform.ComposeView
import com.shilapi.xcertplay.settings.*
import androidx.lifecycle.ViewModelProvider
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Connection and preference pages in the shared server navigation. */
class DiPlayActivity : AppCompatActivity() {
    private class CertificateDocument : ActivityResultContracts.OpenDocument() {
        override fun createIntent(context: android.content.Context, input: Array<String>): Intent =
            super.createIntent(context, input).addCategory(Intent.CATEGORY_OPENABLE)
    }
    private val ui by lazy { ServerUi(this) }
    private val handler = Handler(Looper.getMainLooper())
    private var page = "service"
    private val settingsModel by lazy { ViewModelProvider(this)[SettingsViewModel::class.java] }
    private var settingsView: ComposeView? = null
    private val settingsDialogs = SettingsDialogHost()
    private lateinit var shell: ServerUi.Shell
    private var webAddress: TextView? = null
    private var webInterface: TextView? = null
    private var webBusy = false
    private var awaitingTeslaVpnConsent = false
    private val teslaVpnConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val requested = awaitingTeslaVpnConsent
        awaitingTeslaVpnConsent = false
        if (requested && TeslaHttpCompatibility.config(this).mode == TeslaHttpMode.VPN) {
            applyTeslaHttpEnabled(result.resultCode == RESULT_OK)
            if (result.resultCode != RESULT_OK) toast("VPN 授权已取消，免 Root 模式保持关闭")
        }
    }
    private var pendingPemCertificate: ByteArray? = null
    private val pemCertificatePicker = registerForActivityResult(CertificateDocument()) { uri ->
        if (uri != null) {
            webBusy = true
            Thread({
                val result = runCatching { readCertificateFile(uri) }
                runOnUiThread {
                    webBusy = false
                    if (isFinishing || isDestroyed) { result.getOrNull()?.fill(0); return@runOnUiThread }
                    result.onSuccess { pendingPemCertificate = it; pemKeyPicker.launch(arrayOf("*/*")) }
                        .onFailure { toast("证书读取失败：${it.message ?: it.javaClass.simpleName}") }
                }
            }, "https-certificate-read").start()
        }
    }
    private val pemKeyPicker = registerForActivityResult(CertificateDocument()) { uri ->
        val certificate = pendingPemCertificate
        pendingPemCertificate = null
        if (uri == null || certificate == null) certificate?.fill(0)
        else textInput("PEM 私钥密码（未加密则留空）", "", true, onCancel = { certificate.fill(0) }) { value ->
            val password = value.toCharArray()
            configureWeb("HTTPS 证书已导入，浏览器请重新连接") {
                try {
                    val key = readCertificateFile(uri)
                    try { LanTls.preparePem(certificate, key, password).use { WebSession.installCertificate(this, it) } }
                    finally { key.fill(0) }
                } finally { certificate.fill(0); password.fill('\u0000') }
            }
        }
    }
    private val pkcs12Picker = registerForActivityResult(CertificateDocument()) { uri ->
        if (uri != null) textInput("PKCS#12 文件密码（可留空）", "", true) { value ->
            choosePkcs12Identity(uri, value.toCharArray())
        }
    }
    private var otherAddresses: LinearLayout? = null
    private var otherAddressesToggle: ServerUi.DisclosureRow? = null
    private var addressesExpanded = false
    private var displayedAddresses: List<LanAddresses.Entry>? = null
    private var webCode: TextView? = null
    private var webPairingControls: LinearLayout? = null
    private var quickBrowserButton: Button? = null
    private var webViewer: TextView? = null
    private var webStage: TextView? = null
    private var sessionConnectButton: Button? = null
    private var setupError: String? = null
    private var status: TextView? = null
    private var connectButton: com.google.android.material.button.MaterialButton? = null
    private var disconnectButton: Button? = null
    private var lastRunning: Boolean? = null
    private var pendingWireless = false
    private var initialLaunch = true
    private var notificationTransport = true
    private var exportInProgress = false
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        connect(notificationTransport)
    }
    private val tick = object : Runnable {
        override fun run() { refreshStatus(); refreshService(); refreshSettingsRuntime(); handler.postDelayed(this, 1000) }
    }
    private val bluetoothPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) choosePhone() else permissionHelp("附近设备", "请允许访问附近设备，以连接已配对的 iPhone。")
    }
    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) exportDiagnostics(uri)
    }

    private val oemImagePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) runCatching {
            oemImageCrop.launch(Intent(this, ImageCropActivity::class.java)
                .setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }.onFailure { toast("无法打开图标裁剪界面，请重试") }
    }
    private val oemImageCrop = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            render()
            toast("车厂图标已保存，下次连接 iPhone 生效")
        }
    }

    private val authenticationImport by lazy {
        ViewModelProvider(this, ViewModelProvider.AndroidViewModelFactory(application))[AuthenticationImportModel::class.java]
    }
    private var selectedPrivateKey: Uri? = null
    private val selectAuthenticationKey = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            selectedPrivateKey = uri
            launchAuthenticationPicker { selectAuthenticationCertificate.launch(arrayOf("*/*")) }
        }
    }
    private val selectAuthenticationCertificate = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val key = selectedPrivateKey
        selectedPrivateKey = null
        if (uri != null && key != null) authenticationImport.import(key = key, certificate = uri)
    }
    private val selectAuthenticationApk = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) authenticationImport.import(apk = uri)
    }

    private val scan = registerForActivityResult(ScanQRCode()) { result ->
        val payload = when (result) {
            is QRResult.QRSuccess -> result.content.rawValue?.take(257)
            QRResult.QRUserCanceled -> null
            QRResult.QRMissingPermission -> {
                permissionHelp("相机", "扫码配对需要相机权限，也可以在车机网页手动输入配对码。")
                null
            }
            is QRResult.QRError -> {
                toast("扫码失败，请检查相机权限或关闭正在使用相机的其他应用。")
                null
            }
        } ?: return@registerForActivityResult
        val request = WebSession.inspectPairing(payload)
        if (request == null) {
            toast("二维码无效或已过期，请刷新此服务端的车机网页后重试。")
        } else {
            MaterialAlertDialogBuilder(this).setTitle("允许这台车机连接？")
                .setMessage("浏览器地址：${request.peer}\n授权后，该浏览器可以显示 CarPlay 并发送触控操作。")
                .setNegativeButton("取消", null)
                .setPositiveButton("允许连接") { _, _ ->
                    toast(if (WebSession.approvePairing(payload)) "已授权，车机网页将自动连接" else "请求已过期，请刷新网页重新扫码")
                }.show()
        }
    }
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scanPairing() else permissionHelp("相机", "扫码配对需要相机权限，也可以在车机网页手动输入配对码。")
    }
    private fun scanPairing() {
        if (!WebSession.running) { toast("请等待 Web 服务启动后再扫码"); return }
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            toast("此设备没有相机，请在车机网页输入配对码"); return
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPermission.launch(Manifest.permission.CAMERA); return
        }
        scan.launch(null)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (TeslaHttpCompatibility.config(this).let { it.enabled && it.mode == TeslaHttpMode.ROOT }) RootAccess.checkOnStartup()
        com.shilapi.xcertplay.hud.BydNavigationOutputs.onAppOpened(applicationContext)
        ServerWindow.showSystemBars(window)
        refreshAuthentication()
        selectedPrivateKey = savedInstanceState?.getString("selectedPrivateKey")?.let(Uri::parse)
        awaitingTeslaVpnConsent = savedInstanceState?.getBoolean("awaitingTeslaVpnConsent") ?: false
        page = savedInstanceState?.getString("page") ?: intent.getStringExtra("page")
            ?: if (setupError == null) "service" else "settings"
        if (savedInstanceState == null && page == "settings" && setupError != null) {
            settingsModel.open(SettingsCategory.AUTHENTICATION)
        }
        createShell()
        startWebServiceIfReady()
        handleWirelessRecovery()
        authenticationImport.state.observe(this) { state ->
            refreshAuthentication()
            render(); refreshService()
            state.result?.let {
                startWebServiceIfReady()
                toast(it); authenticationImport.consumeResult()
            }
        }
        if (savedInstanceState == null) handleAuthenticationIntent(intent)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    page == "settings" && settingsModel.state.value.category != null -> settingsModel.open(null)
                    page != "service" -> navigate("service")
                    else -> moveTaskToBack(true)
                }
            }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        if (!handleAuthenticationIntent(intent)) navigate(intent.getStringExtra("page") ?: "service")
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("page", page)
        outState.putString("selectedPrivateKey", selectedPrivateKey?.toString())
        outState.putBoolean("awaitingTeslaVpnConsent", awaitingTeslaVpnConsent)
        super.onSaveInstanceState(outState)
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        settingsView?.disposeComposition()
        super.onConfigurationChanged(newConfig)
        createShell()
    }
    override fun onResume() {
        super.onResume(); refreshAuthentication(); render(); ServerWindow.showSystemBars(window); handler.removeCallbacks(tick); handler.post(tick)
        WebSession.setPhoneConnectHandler {
            if (setupError != null) setupError!!
            else {
                connect(AirPlayPersistence.loadWirelessEnabled(this))
                "已请求连接 iPhone；如有提示，请在 Android 上完成设备选择或授权，并在 iPhone 上允许 CarPlay"
            }
        }
        // Back from the car settings: refresh the car hotspot reminder on the home page.
        if (!initialLaunch) { refreshPhone(); refreshService() }
        if (initialLaunch) {
            initialLaunch = false
            if (setupError == null && !DiPlayBootstrap.importing && selectedPrivateKey == null &&
                intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_VIEW &&
                !CarPlayBackgroundSession.hasSession() &&
                DiPlayPreferences.autoConnect(this) && intent.getStringExtra("page") == null) {
                handler.post { connect(AirPlayPersistence.loadWirelessEnabled(this)) }
            }
        }
    }
    override fun onPause() { WebSession.setPhoneConnectHandler(null); handler.removeCallbacks(tick); super.onPause() }
    override fun onDestroy() { settingsDialogs.cancel(); pendingPemCertificate?.fill(0); pendingPemCertificate = null; super.onDestroy() }

    private fun normalizedPage(value: String) = when (value) {
        "home", "wireless-recovery" -> "phone"
        "about" -> "settings"
        "phone", "settings" -> value
        else -> "service"
    }

    private fun navigate(destination: String) {
        val target = normalizedPage(destination)
        if (target == "settings") {
            settingsModel.open(if (setupError != null) SettingsCategory.AUTHENTICATION else null)
        }
        shell.select(target)
        page = target
        if (destination == "wireless-recovery") confirmWirelessReset()
    }

    private fun createShell() {
        val initial = normalizedPage(page)
        shell = ui.Shell(initial) { page = it }
        page = initial
        shell.put("service", ui.content(::service))
        refreshPhone()
        settingsView = ComposeView(this).apply {
            id = R.id.settings_compose
            setContent { SettingsRoute(settingsModel, settingsActions, settingsDialogs) { shell.setPageScroll("settings", it) } }
        }.also { shell.put("settings", it) }
        refreshSettings()
        setContentView(shell.root)
        refreshService()
    }

    private fun refreshPhone() {
        status = null; connectButton = null; disconnectButton = null; lastRunning = null
        shell.put("phone", ui.content(::home))
        refreshStatus()
    }

    private fun render() {
        refreshPhone()
        refreshSettings()
    }

    private fun refreshSettings() {
        settingsModel.reload()
        refreshSettingsRuntime()
    }

    private fun refreshSettingsRuntime() {
        val session = CarPlayBackgroundSession.snapshot()
        val audioStatus = when {
            session == null -> "当前：未连接 iPhone"
            AirPlayPersistence.loadBrowserAudioPlayback(this) && WebSession.audio.playbackReady && session.sink.isRemoteMediaAvailable() -> "当前输出：车机浏览器"
            AirPlayPersistence.loadBrowserAudioPlayback(this) -> "当前输出：Android（浏览器尚未就绪或已离线）"
            else -> "当前输出：Android"
        }
        val source = AirPlayPersistence.loadMicrophoneSource(this)
        val microphoneStatus = when {
            source == CarPlayMicrophoneSource.DISABLED -> "当前：麦克风已禁用"
            session == null -> "当前：未连接 iPhone"
            source == CarPlayMicrophoneSource.BROWSER && WebSession.audio.microphoneReady -> "输入来源：浏览器（按需采集）"
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED -> "Android 麦克风未授权；浏览器未就绪时无法输入语音"
            source == CarPlayMicrophoneSource.BROWSER -> "输入来源：Android（浏览器麦克风尚未就绪）"
            else -> "输入来源：Android（按需采集）"
        }
        val compatibility = TeslaHttpCompatibility.status
        val teslaConfig = TeslaHttpCompatibility.config(this)
        val teslaMessage = if (!teslaConfig.enabled) "已关闭"
            else if (!WebSession.running && setupError != null) "导入认证资源后启动"
            else compatibility.error ?: compatibility.address?.let {
                if (compatibility.mode == TeslaHttpMode.VPN) "免 Root 地址已配置：$it；热点与车机访问需实测"
                else "Root 热点路由已启用：$it（${compatibility.downstreams.joinToString()}）；请用车机测试访问"
            } ?: "正在准备${teslaConfig.mode.label}…"
        settingsModel.updateRuntime(SettingsRuntime(
            authenticationError = setupError, importing = DiPlayBootstrap.importing, webBusy = webBusy,
            awaitingVpnConsent = awaitingTeslaVpnConsent, exporting = exportInProgress,
            rootGranted = RootAccess.granted, rootChecking = RootAccess.checking, rootStatus = RootAccess.status,
            teslaStatus = teslaMessage, audioStatus = audioStatus, microphoneStatus = microphoneStatus,
            version = version(), fingerprint = WebSession.tls?.fingerprint
        ))
    }

    private val settingsActions by lazy {
        SettingsActions(
            choice = { setting, index -> changeSettings { applySettingsEffect(settingsModel.choose(setting, index)) }?.let(::toast) },
            toggle = { setting, checked -> changeSettings { applySettingsEffect(settingsModel.toggle(setting, checked)) }?.let(::toast) },
            text = { setting, value -> changeSettings { applySettingsEffect(settingsModel.edit(setting, value)) } },
            credentials = { ssid, password -> changeSettings { settingsModel.saveCredentials(ssid, password) } },
            margins = { margins -> changeSettings { applySettingsEffect(settingsModel.saveMargins(margins)) }?.let(::toast) },
            resetBranding = { includeName -> changeSettings { settingsModel.resetBranding(includeName) }?.let(::toast) },
            command = { command -> changeSettings { settingsCommand(command) }?.let(::toast) },
            renameBrowser = { id, name -> changeSettings { settingsModel.renameBrowser(id, name) }?.let(::toast) },
            removeBrowser = { id -> changeSettings { settingsModel.removeBrowser(id) }?.let(::toast) }
        )
    }

    private fun changeSettings(action: () -> Unit): String? = runCatching {
        action(); refreshSettings(); refreshService()
    }.exceptionOrNull()?.let { it.message ?: "设置保存失败" }

    private fun applySettingsEffect(effect: SettingsEffect) {
        when (effect) {
            SettingsEffect.None -> Unit
            is SettingsEffect.Reconnect -> if (CarPlayBackgroundSession.hasSession()) connect(effect.wireless ?: AirPlayPersistence.loadWirelessEnabled(this))
            is SettingsEffect.WebUpdate -> configureWeb(effect.message, effect.operation)
            is SettingsEffect.TeslaEnabled -> setTeslaHttpEnabled(effect.enabled)
        }
    }

    private fun settingsCommand(command: SettingsCommand) {
        when (command) {
            SettingsCommand.SELECT_PHONE -> choosePhone()
            SettingsCommand.HOTSPOT_SETTINGS -> openCarWifiSettings()
            SettingsCommand.REQUEST_ROOT -> configureWeb("Root 已授权") { RootAccess.request().also { TeslaHttpCompatibility.retry() } }
            SettingsCommand.RETRY_ROOT -> TeslaHttpCompatibility.retry()
            SettingsCommand.ENABLE_VPN -> setTeslaHttpEnabled(true)
            SettingsCommand.SELECT_ICON -> oemImagePicker.launch("image/*")
            SettingsCommand.IMPORT_APK -> if (canImportAuthentication()) launchAuthenticationPicker { selectAuthenticationApk.launch(arrayOf("*/*")) }
            SettingsCommand.IMPORT_FILES -> if (canImportAuthentication()) launchAuthenticationPicker { selectAuthenticationKey.launch(arrayOf("*/*")) }
            SettingsCommand.SELECT_PEM -> pemCertificatePicker.launch(arrayOf("*/*"))
            SettingsCommand.SELECT_PKCS12 -> pkcs12Picker.launch(arrayOf("*/*"))
            SettingsCommand.APP_PERMISSIONS -> openSystem(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            SettingsCommand.BLUETOOTH_SETTINGS -> openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            SettingsCommand.WIRELESS_HELP -> wirelessHelp()
            SettingsCommand.EXPORT_REPORT -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) exportDiagnostics() else chooseReportDestination()
            SettingsCommand.DIPLAY -> openSystem(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/shihabal3amri/DiPlay")))
            SettingsCommand.SOURCE -> openSystem(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/fython/wheelplay")))
            SettingsCommand.DOWNLOAD -> openSystem(Intent(Intent.ACTION_VIEW, Uri.parse("https://shihabal3amri.github.io/DiPlay/")))
        }
    }

    private fun service(content: LinearLayout) {
        content.addView(label(getString(R.string.app_name), 21, TEXT, true).apply { setPadding(0, 0, 0, dp(24)) })
        content.addView(label("在车机浏览器中使用 CarPlay", 28, TEXT, true))
        content.addView(space(24))
        ui.card(content, "车机访问", R.drawable.ic_server_server) { body ->
            body.addView(label("局域网地址", 14, MUTED))
            webAddress = label("正在启动服务…", 21, ACCENT, true).apply {
                setTextIsSelectable(true); setPadding(0, dp(8), 0, dp(8))
            }
            body.addView(webAddress)
            webInterface = label("", 14, MUTED)
            body.addView(webInterface)
            quickBrowserButton = button("在本机浏览器中体验", false, R.drawable.ic_server_launch) {
                openLocalBrowserExperience()
            }.apply { isEnabled = false }
            body.addView(quickBrowserButton, ui.secondaryButtonLayout(12))
            otherAddressesToggle = ui.DisclosureRow("其他地址") {
                addressesExpanded = !addressesExpanded
                updateAddressExpansion(animate = true)
            }.apply { visibility = View.GONE }
            body.addView(otherAddressesToggle, ui.secondaryButtonLayout(4))
            otherAddresses = column().apply { visibility = View.GONE }
            body.addView(otherAddresses)
            displayedAddresses = null
        }
        ui.card(content, "浏览器配对", R.drawable.ic_server_qr) { body ->
            webPairingControls = column().apply {
                addView(label("扫描车机网页上的二维码，即可授权显示与触控。", 16, MUTED))
                addView(button("扫描二维码配对", true, R.drawable.ic_server_scan) { scanPairing() }, matchButton(12))
                addView(label("或在网页输入配对码", 14, MUTED).apply { setPadding(0, dp(16), 0, 0) })
                webCode = label("— — — — — —", 32, TEXT, true).apply {
                    letterSpacing = .12f; setTextIsSelectable(true); setPadding(0, dp(8), 0, dp(16))
                }
                addView(webCode)
            }
            body.addView(webPairingControls)
            webViewer = label("等待车机浏览器连接", 16, MUTED)
            body.addView(webViewer)
        }
        ui.card(content, "iPhone 会话", R.drawable.ic_server_phone) { body ->
            webStage = label("等待连接 iPhone", 16, TEXT)
            body.addView(webStage)
            sessionConnectButton = button("连接", false, R.drawable.ic_server_phone) {
                if (!CarPlayBackgroundSession.hasSession()) connect(true)
            }.apply { visibility = View.GONE }
            body.addView(sessionConnectButton, ui.secondaryButtonLayout(12))
            body.addView(button("管理 iPhone 连接", false) { navigate("phone") }, ui.secondaryButtonLayout(16))
        }
        content.addView(label("音频与麦克风默认使用此设备，可在 App 设置中分别启用浏览器转发。", 14, MUTED))
        content.addView(button("停止服务", false) {
            startService(Intent(this, DiPlaySessionService::class.java).setAction(DiPlaySessionService.ACTION_STOP))
            finishAndRemoveTask()
        }, ui.secondaryButtonLayout(24))
    }

    private fun refreshService() {
        val addresses = if (WebSession.running && WebSession.error == null) WebSession.addresses(this) else emptyList()
        val primary = addresses.firstOrNull()
        val addressText = if (!WebSession.running && setupError != null) "请先导入认证资源以激活 Web 服务"
            else WebSession.error ?: if (WebSession.running) primary?.url
                ?: "未找到局域网地址，请连接 Wi-Fi" else "正在启动服务…"
        if (webAddress?.text?.toString() != addressText) webAddress?.text = addressText
        webInterface?.text = primary?.let { it.description + if (it.hostname.isNotEmpty()) "\n直接 IP：${it.ipUrl}" else "" }.orEmpty()
        webInterface?.visibility = if (primary == null) View.GONE else View.VISIBLE
        if (displayedAddresses != addresses) {
            displayedAddresses = addresses
            otherAddresses?.removeAllViews()
            addresses.drop(1).forEachIndexed { index, entry ->
                otherAddresses?.addView(label(entry.description, 14, MUTED))
                otherAddresses?.addView(label(entry.url, 16, TEXT).apply {
                    setTextIsSelectable(true); setPadding(0, dp(4), 0, if (index < addresses.size - 2) dp(16) else 0)
                })
            }
            updateAddressExpansion()
        }
        webCode?.text = if (WebSession.running) WebSession.code else "— — — — — —"
        quickBrowserButton?.isEnabled = WebSession.running && BrowserExperienceLink.local(WebSession.code, WebSession.httpPort) != null
        val viewerConnected = WebSession.hasViewer
        webPairingControls?.visibility = if (viewerConnected) View.GONE else View.VISIBLE
        webViewer?.text = if (!WebSession.running && setupError != null) "导入认证资源后可配对浏览器"
            else if (viewerConnected) "车机浏览器已连接" else "等待车机浏览器连接"
        webStage?.text = setupError ?: if (WebSession.videoActive) "CarPlay 画面正在串流" else WebSession.stage
    }

    private fun openLocalBrowserExperience() {
        val url = BrowserExperienceLink.local(WebSession.code, WebSession.httpPort)
        if (!WebSession.running || url == null) {
            toast("请等待 Web 服务启动后再打开")
            return
        }
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { toast("未找到可打开网页的浏览器") }
    }

    private fun updateAddressExpansion(animate: Boolean = false) {
        val count = ((displayedAddresses?.size ?: 0) - 1).coerceAtLeast(0)
        otherAddressesToggle?.visibility = if (count > 0) View.VISIBLE else View.GONE
        otherAddressesToggle?.setExpanded(addressesExpanded, animate)
        otherAddresses?.visibility = if (count > 0 && addressesExpanded) View.VISIBLE else View.GONE
    }

    private fun home(content: LinearLayout) {
        content.addView(label("连接 iPhone", 30, TEXT, true))
        content.addView(label("此设备接收 CarPlay，车机浏览器负责显示与触控。", 16, MUTED).apply { setPadding(0, dp(8), 0, dp(24)) })
        status = label("等待连接", 20, ACCENT, true)
        ui.card(content, "连接状态", R.drawable.ic_server_phone) { it.addView(status) }
        ui.card(content, "无线连接", R.drawable.ic_server_wifi) { body ->
            body.addView(label("先将 iPhone 与这台 Android 设备完成蓝牙配对，并保持双方蓝牙和 Wi-Fi 开启。", 15, MUTED))
            body.addView(ui.preference("iPhone", selectedPhoneName()) { choosePhone() }, matchButton())
            connectButton = button("连接 iPhone", true, R.drawable.ic_server_phone) { connect(true) }
            body.addView(connectButton, matchButton(12))
            disconnectButton = button("断开 iPhone", false) {
                disconnectButton?.isEnabled = false
                CarPlayBackgroundSession.stop { runOnUiThread {
                    startWebServiceIfReady()
                    WebSession.stage = "iPhone 已断开"
                    refreshStatus(); refreshService()
                } }
            }.apply { visibility = View.GONE }
            body.addView(disconnectButton, ui.secondaryButtonLayout(12))
            if (carHotspotOff()) {
                body.addView(label("热点“${AirPlayPersistence.loadManualHotspotSsid(this)}”尚未开启，请先在系统设置中打开。", 15, WARNING).apply { setPadding(0, dp(16), 0, 0) })
                body.addView(button("打开热点设置", false) { openCarWifiSettings() }, ui.secondaryButtonLayout(12))
            }
        }
        ui.card(content, "USB 连接", R.drawable.ic_server_usb) { body ->
            body.addView(label("使用数据线连接 iPhone 与此设备，并在 iPhone 上允许 CarPlay。", 15, MUTED))
            body.addView(button("通过 USB 连接", false) { connect(false) }, ui.secondaryButtonLayout(16))
        }
        setupError?.let { content.addView(label(it, 16, WARNING)) }
    }

    private fun refreshAuthentication() {
        setupError = if (DiPlayBootstrap.importing) "正在导入认证资源，请稍候"
        else runCatching { DiPlayBootstrap.ensure(this) }.exceptionOrNull()?.let {
            "认证资源未就绪，请到设置 → CarPlay 认证资源导入文件或 DiPlay APK。"
        }
    }

    private fun startWebServiceIfReady() {
        if (setupError == null && !DiPlayBootstrap.importing) {
            startForegroundService(Intent(this, DiPlaySessionService::class.java))
        }
    }

    private fun canImportAuthentication(): Boolean {
        if (DiPlayBootstrap.importing) { toast("正在导入认证资源，请稍候"); return false }
        if (CarPlayBackgroundSession.hasSession()) { toast("请先断开 iPhone，再导入认证资源"); return false }
        return true
    }

    private fun launchAuthenticationPicker(launch: () -> Unit) {
        runCatching(launch).onFailure {
            selectedPrivateKey = null
            toast("无法打开文件选择器，请启用系统文件管理器；APK 也可以通过文件管理器分享给 WheelPlay")
        }
    }

    private fun handleAuthenticationIntent(incoming: Intent): Boolean {
        if (incoming.action != Intent.ACTION_SEND && incoming.action != Intent.ACTION_VIEW) return false
        settingsModel.open(SettingsCategory.AUTHENTICATION)
        shell.select("settings")
        page = "settings"
        val uri = AuthenticationImportIntents.apk(incoming)
        if (uri == null) { toast("无法读取 APK，请在设置中通过文件选择器导入"); return true }
        if (!canImportAuthentication()) return true
        settingsDialogs.confirm("从此 APK 导入认证资源？", "WheelPlay 只在本机读取 DiPlay APK 内的两份认证资源，不安装 APK。校验通过后将替换原有资源。") {
            authenticationImport.import(apk = uri)
        }
        return true
    }

    private fun setTeslaHttpEnabled(enabled: Boolean) {
        if (enabled && TeslaHttpCompatibility.config(this).mode == TeslaHttpMode.VPN) {
            if (awaitingTeslaVpnConsent) return
            try {
                val consent = CarPlayVpnService.prepare(this)
                if (consent != null) {
                    awaitingTeslaVpnConsent = true
                    teslaVpnConsent.launch(consent)
                    render()
                    return
                }
            } catch (error: Exception) {
                awaitingTeslaVpnConsent = false
                toast("无法请求 VPN 授权：${error.message ?: error.javaClass.simpleName}")
                render()
                return
            }
        }
        applyTeslaHttpEnabled(enabled)
    }

    private fun applyTeslaHttpEnabled(enabled: Boolean) {
        TeslaHttpCompatibility.save(this, TeslaHttpCompatibility.config(this).copy(enabled = enabled))
        if (!WebSession.running) startWebServiceIfReady()
        handler.post { render(); refreshService() }
    }

    // The car hotspot link needs the hotspot on; DiPlay only checks it (turning it on needs ADB-only permission).
    private fun carHotspotOff(): Boolean =
        AirPlayPersistence.loadWirelessHotspotMode(this) == WirelessHotspotMode.MANUAL &&
            com.shilapi.xcertplay.network.CarHotspotStatus.isEnabled(this) == false

    private fun carHotspotOffDialog() {
        MaterialAlertDialogBuilder(this).setTitle("热点尚未开启")
            .setMessage("当前使用热点“${AirPlayPersistence.loadManualHotspotSsid(this)}”，请先在系统设置中开启后再连接。")
            .setPositiveButton("打开系统设置") { _, _ -> openCarWifiSettings() }
            .setNeutralButton("连接") { _, _ -> connect(true) }
            .setNegativeButton("取消", null).show()
    }

    // BYD maps the AOSP tether action to its own hotspot screen; other firmware falls back to Wi-Fi settings.
    // BYD shows that screen as a dialog and closes it unless its own settings or the car home screen is on top,
    // so the home screen goes first.
    private fun openCarWifiSettings() {
        val hotspot = Intent("com.android.settings.WIFI_TETHER_SETTINGS")
        val target = packageManager.resolveActivity(hotspot, 0)?.activityInfo?.packageName
        if (target == null) {
            openSystem(Intent(Settings.ACTION_WIRELESS_SETTINGS))
            return
        }
        if (target == "com.byd.carsettings") {
            runCatching { startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)) }
        }
        if (runCatching { startActivity(hotspot) }.isSuccess) return
        openSystem(Intent(Settings.ACTION_WIRELESS_SETTINGS))
    }

    private fun selectedPhoneName() = if (DiPlayPreferences.phoneAddress(this) == null) "未选择" else DiPlayPreferences.phoneName(this)

    private fun readCertificateFile(uri: Uri): ByteArray = contentResolver.openInputStream(uri)?.use(LanTls::readImport)
        ?: error("无法打开所选文件")

    private fun configureWeb(success: String, action: () -> String?) {
        if (webBusy) { toast("正在更新设置，请稍候"); return }
        webBusy = true
        render()
        Thread({
            val failure = runCatching(action).getOrElse { "Web 设置更新失败：${it.message ?: it.javaClass.simpleName}" }
            runOnUiThread {
                webBusy = false
                if (!isFinishing && !isDestroyed) { toast(failure ?: success); render(); refreshService() }
            }
        }, "web-configure").start()
    }

    private fun choosePkcs12Identity(uri: Uri, password: CharArray) {
        if (webBusy) { password.fill('\u0000'); toast("正在更新 HTTPS，请稍候"); return }
        webBusy = true
        Thread({
            var bytes: ByteArray? = null
            val result = runCatching {
                readCertificateFile(uri).also { bytes = it }.let { LanTls.pkcs12Aliases(it, password) }
            }
            runOnUiThread {
                webBusy = false
                fun clear() { bytes?.fill(0); password.fill('\u0000') }
                if (isFinishing || isDestroyed) { clear(); return@runOnUiThread }
                result.onFailure { clear(); toast("PKCS#12 读取失败：请检查文件及密码") }
                    .onSuccess { aliases ->
                        val choose = { alias: String ->
                            textInput("私钥密码（留空使用文件密码）", "", true, onCancel = ::clear) { value ->
                                val keyPassword = if (value.isEmpty()) password.copyOf() else value.toCharArray()
                                configureWeb("PKCS#12 证书已导入，浏览器请重新连接") {
                                    try { LanTls.preparePkcs12(bytes!!, password, alias, keyPassword).use { WebSession.installCertificate(this, it) } }
                                    finally { clear(); keyPassword.fill('\u0000') }
                                }
                            }
                        }
                        if (aliases.size == 1) choose(aliases.single())
                        else settingsDialogs.select("选择 PKCS#12 私钥条目", aliases, ::clear) { index -> choose(aliases[index]) }
                    }
            }
        }, "https-pkcs12-read").start()
    }

    private fun textInput(title: String, current: String, secret: Boolean, preserveWhitespace: Boolean = false,
        onCancel: () -> Unit = {}, save: (String) -> Unit) {
        settingsDialogs.input(title, current, secret, preserveWhitespace, onCancel, save)
    }

    private fun connect(wireless: Boolean) {
        refreshAuthentication()
        if (setupError != null) { toast(setupError!!); return }
        if (wireless && carHotspotOff()) { carHotspotOffDialog(); return }
        if (wireless && DiPlayPreferences.phoneAddress(this) == null) {
            pendingWireless = true; choosePhone(); return
        }
        val preferences = getSharedPreferences("diplay", MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && !preferences.getBoolean("notification_asked", false)) {
            preferences.edit().putBoolean("notification_asked", true).apply()
            notificationTransport = wireless
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        val open = {
            AirPlayPersistence.saveWirelessEnabled(this, wireless)
            openProjection()
        }
        if (CarPlayBackgroundSession.hasSession()) CarPlayBackgroundSession.stop { runOnUiThread { open() } }
        else open()
    }
    private fun openProjection() {
        navigate("service")
        startActivity(Intent(this, CarPlayHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NO_ANIMATION))
        overridePendingTransition(0, 0)
    }
    private fun choosePhone() {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT); return
        }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            MaterialAlertDialogBuilder(this).setTitle("请开启蓝牙")
                .setMessage("请开启这台 Android 设备的蓝牙，并先与 iPhone 配对。")
                .setPositiveButton("打开蓝牙设置") { _, _ -> openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                .setNegativeButton("稍后", null).show(); return
        }
        val devices = runCatching { adapter.bondedDevices.sortedBy { it.name ?: "" } }.getOrDefault(emptyList())
        if (devices.isEmpty()) {
            MaterialAlertDialogBuilder(this).setTitle("请先配对 iPhone")
                .setMessage("在 iPhone 的“设置 → 蓝牙”中与这台 Android 设备配对，然后返回选择 iPhone。")
                .setPositiveButton("打开蓝牙设置") { _, _ -> openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                .setNegativeButton("知道了", null).show(); return
        }
        MaterialAlertDialogBuilder(this).setTitle("选择 iPhone")
            .setItems(devices.map { device ->
                val name = device.name ?: "已配对设备"
                if (devices.count { it.name == device.name } > 1) "$name · ${device.address.takeLast(5)}" else name
            }.toTypedArray()) { _, index ->
                val device = devices[index]
                DiPlayPreferences.savePhone(this, device.address, device.name ?: "iPhone")
                val start = pendingWireless; pendingWireless = false
                render()
                if (start) connect(true)
            }.setNeutralButton("配对其他设备") { _, _ -> openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
            .setNegativeButton("取消") { _, _ -> pendingWireless = false }.show()
    }

    private fun wirelessHelp() {
        MaterialAlertDialogBuilder(this).setTitle("无线连接帮助")
            .setMessage("将 iPhone 与此设备完成蓝牙配对，保持 Wi-Fi 开启，并在 iPhone 上允许 CarPlay。请关闭其他投屏应用。\n\n如果之前的连接仍占用网络，可重置 CarPlay Wi-Fi 后重连。此操作不会关闭普通上网 Wi-Fi。")
            .setPositiveButton("知道了", null)
            .setNeutralButton("重置 CarPlay Wi-Fi") { _, _ ->
                confirmWirelessReset()
            }.show()
    }

    private fun handleWirelessRecovery() {
        if (intent.getStringExtra("page") != "wireless-recovery") return
        intent.removeExtra("page")
        navigate("phone")
        confirmWirelessReset()
    }

    private fun confirmWirelessReset() {
        MaterialAlertDialogBuilder(this).setTitle("重置 CarPlay Wi-Fi？")
            .setMessage("这会结束当前 Wi-Fi Direct 连接，包括重新安装前遗留的连接。请先关闭其他投屏应用，普通上网 Wi-Fi 不受影响。")
            .setPositiveButton("重置并连接") { _, _ ->
                CarPlayBackgroundSession.stop { runOnUiThread { resetWirelessGroup() } }
            }.setNegativeButton("取消", null).show()
    }

    private fun resetWirelessGroup() {
        val manager = getSystemService(android.net.wifi.p2p.WifiP2pManager::class.java)
        if (manager == null) { toast("此设备不支持 Wi-Fi Direct。"); return }
        val channel = manager.initialize(this, mainLooper, null)
        try {
            manager.requestGroupInfo(channel) { group ->
                if (group == null) { channel.close(); connect(true); return@requestGroupInfo }
                manager.removeGroup(channel, object : android.net.wifi.p2p.WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        val deadline = android.os.SystemClock.elapsedRealtime() + 4000
                        fun waitUntilRemoved() {
                            manager.requestGroupInfo(channel) { remaining ->
                                when {
                                    remaining == null -> { channel.close(); if (!isFinishing && !isDestroyed) connect(true) }
                                    android.os.SystemClock.elapsedRealtime() >= deadline -> {
                                        channel.close(); toast("Wi-Fi Direct 仍被占用，请关闭其他投屏应用后重试。")
                                    }
                                    else -> handler.postDelayed({ waitUntilRemoved() }, 200)
                                }
                            }
                        }
                        waitUntilRemoved()
                    }
                    override fun onFailure(reason: Int) { channel.close(); toast("无法重置 Wi-Fi Direct，请关闭其他投屏应用后重试。") }
                })
            }
        } catch (_: SecurityException) {
            channel.close(); permissionHelp("无线连接权限", "请先允许附近设备权限；旧版 Android 还需定位权限。")
        }
    }

    private fun refreshStatus() {
        val running = CarPlayBackgroundSession.hasSession()
        status?.text = when {
            setupError != null -> "认证配置需要处理"
            CarPlayBackgroundSession.active -> "CarPlay 已连接"
            running -> "正在连接 iPhone…"
            DiPlayPreferences.phoneAddress(this) != null -> "已选择 ${DiPlayPreferences.phoneName(this)}"
            else -> "尚未选择 iPhone"
        }
        if (lastRunning != running) {
            connectButton?.visibility = if (running) View.GONE else View.VISIBLE
            disconnectButton?.visibility = if (running) View.VISIBLE else View.GONE
            disconnectButton?.isEnabled = true
            lastRunning = running
        }
        connectButton?.isEnabled = setupError == null && !DiPlayBootstrap.importing
        sessionConnectButton?.visibility = if (!running && !CarPlayBackgroundSession.active &&
            DiPlayPreferences.phoneAddress(this) != null) View.VISIBLE else View.GONE
        sessionConnectButton?.isEnabled = setupError == null && !DiPlayBootstrap.importing
    }
    private fun reportFileName() = "WheelPlay-${SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())}.txt"

    private fun chooseReportDestination() {
        // Some head units omit or disable DocumentsUI. Launch itself can throw, before
        // the result callback and the background writer's exception handler ever run.
        runCatching { export.launch(reportFileName()) }.onFailure {
            toast(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                "无法打开保存位置，请重试保存到下载目录。"
                else "此设备没有可用的文件选择器。")
        }
    }

    private fun exportDiagnostics(uri: Uri? = null) {
        if (exportInProgress) return
        exportInProgress = true
        refreshSettingsRuntime()
        val appContext = applicationContext
        val fileName = reportFileName()
        Thread({
            val result = runCatching {
                val report = buildString {
                    appendLine("WheelPlay ${version()} · private beta diagnostic report")
                    appendLine("Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
                    appendLine("Head unit: ${Build.MANUFACTURER} ${Build.MODEL}")
                    appendLine("Connection: ${if (AirPlayPersistence.loadWirelessEnabled(appContext)) "wireless" else "USB"}")
                    appendLine("Authentication: local experimental beta identity; no remote fallback")
                    appendLine("Saved video preference (may differ from active session): ${if (AirPlayPersistence.loadHevcEnabled(appContext)) "HEVC" else "H.264"}; ${AirPlayPersistence.loadFps(appContext)} fps")
                    appendLine("CarPlay size: ${com.shilapi.xcertplay.airplay.CarPlaySize.fromWidthMillimeters(AirPlayPersistence.loadWidthPhysicalMm(appContext)).label}")
                    appendLine("Saved resolution preference (may differ from active session): ${AirPlayPersistence.loadDisplayScaleTenths(appContext) * 10}%")
                    appendLine("Tesla compatibility: enabled=${TeslaHttpCompatibility.config(appContext).enabled}; mode=${TeslaHttpCompatibility.config(appContext).mode}; addressConfigured=${TeslaHttpCompatibility.status.address != null}; downstreams=${TeslaHttpCompatibility.status.downstreams.joinToString()}")
                    TeslaHttpCompatibility.status.error?.let { appendLine("Tesla compatibility status: $it") }
                    appendLine("Session: ${if (CarPlayBackgroundSession.active) "active" else if (CarPlayBackgroundSession.hasSession()) "connecting" else "stopped"}")
                    appendLine("Head-unit board: ${Build.BOARD}; hardware: ${Build.HARDWARE}; build: ${Build.DISPLAY}")
                    appendLine()
                    appendLine("--- Last display negotiation (timestamps distinguish it from current settings) ---")
                    appendLine(DisplayDiagnosticSnapshot.report(appContext))
                    appendLine()
                    for (name in SessionLogFile.REPORT_NAMES) {
                        val file = File(appContext.filesDir, "logs/$name")
                        if (file.isFile) {
                            appendLine("--- $name ---")
                            file.useLines { lines -> lines.forEach { line -> DiagnosticRedactor.redact(line)?.let { appendLine(it) } } }
                        }
                    }
                }
                if (uri != null) DiagnosticExportStore.write(appContext.contentResolver, uri, report)
                else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    DiagnosticExportStore.saveToDownloads(appContext.contentResolver, fileName, report)
                } else error("A save location is required")
            }
            runOnUiThread {
                exportInProgress = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                refreshSettingsRuntime()
                if (result.isSuccess) {
                    MaterialAlertDialogBuilder(this).setTitle("诊断报告已保存")
                        .setMessage(if (uri == null) "Downloads/WheelPlay/$fileName" else "报告已保存到所选位置。")
                        .setPositiveButton("完成", null).show()
                } else {
                    MaterialAlertDialogBuilder(this).setTitle("无法保存报告")
                        .setMessage("请检查存储空间，或选择其他保存位置。")
                        .setPositiveButton("选择位置") { _, _ -> chooseReportDestination() }
                        .setNegativeButton("关闭", null).show()
                }
            }
        }, "diplay-export").start()
    }
    private fun permissionHelp(title: String, body: String) {
        MaterialAlertDialogBuilder(this).setTitle(title).setMessage(body).setPositiveButton("应用设置") { _, _ ->
            openSystem(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }.setNegativeButton("稍后", null).show()
    }
    private fun openSystem(intent: Intent) { runCatching { startActivity(intent) }.onFailure { toast("请从此设备的系统设置中打开该选项。") } }
    private fun toast(message: String) { com.google.android.material.snackbar.Snackbar.make(shell.root, message, com.google.android.material.snackbar.Snackbar.LENGTH_LONG).show() }
    private fun version() = packageManager.getPackageInfo(packageName, 0).versionName ?: "0.1.0-beta.1"
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(-1, -2) }
    private fun label(value: String, size: Int, color: Int, bold: Boolean = false) = ui.text(value, size, color, bold)
    private fun button(title: String, primary: Boolean, @androidx.annotation.DrawableRes iconRes: Int = 0, click: () -> Unit) = ui.button(title, primary, iconRes, click)
    private fun matchButton(top: Int = 0) = LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }
    private fun space(height: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(height)) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    companion object {
        private val ACCENT = ServerUi.ACCENT
        private val TEXT = ServerUi.TEXT
        private val MUTED = ServerUi.MUTED
        private val WARNING = ServerUi.WARNING
    }
}
