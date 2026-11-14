// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.air

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.app.LocaleManagerCompat
import androidx.core.content.ContextCompat
import com.droidance.core.agent.debug.DebugLogAppender
import com.droidance.core.agent.di.ApplicationScope
import com.droidance.core.device.agent.device.DeviceConnectionMethod
import com.droidance.core.device.agent.device.RootConnectionManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.net.ServerSocket
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AirModePairingManager @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val appScope: CoroutineScope,
    private val notificationController: AirModeNotificationController,
    private val debugLogAppender: DebugLogAppender,
    private val lifecycleGate: AdbLifecycleGate,
    private val rootConnection: RootConnectionManager
) {
    enum class PairingState { IDLE, DISCOVERING, AWAITING_CODE, PAIRING, CONNECTING, CONNECTED }
    enum class PairingTrustState { UNPAIRED, PAIRED, PAIRING_REQUIRED }
    enum class ShellHealthState { UNKNOWN, READY, DOWN }

    data class ActiveAirEndpoint(
        val host: String,
        val port: Int,
        val source: String,
        val endpointId: String = "$host:$port"
    )

    data class ShellHealthSnapshot(
        val state: ShellHealthState,
        val ready: Boolean,
        val lastFailure: String,
        val lastCheckedAtMs: Long,
        val probeOutput: String,
        val pairingState: PairingState,
        val pairingTrustState: PairingTrustState,
        val previouslyPaired: Boolean,
        val wifiTransportAvailable: Boolean?,
        val secureSettingsGranted: Boolean,
        val wirelessDebuggingEnabled: Boolean?,
        val activeEndpoint: ActiveAirEndpoint?,
        val persistenceEnabled: Boolean,
        val localPort: Int?,
        val usbDebuggingEnabled: Boolean?,
        val adbdEvidence: String,
        val pairingFailure: AirPairingFailure?,
        val notificationAvailability: AirPairingNotificationAvailability
    )

    private companion object {
        const val AIR_HEALTH_CHECK_MARKER = "droidance_air_ready"
        const val AIR_HEALTH_CHECK_COMMAND = "echo $AIR_HEALTH_CHECK_MARKER"
        const val LOCAL_ADB_HOST = "127.0.0.1"
        const val TLS_PAIRING_SERVICE = "_adb-tls-pairing._tcp"
        const val TLS_CONNECT_SERVICE = "_adb-tls-connect._tcp"
        const val PAIRING_DISCOVERY_REFRESH_MS = 8_000L
        const val PAIRING_DISCOVERY_RESTART_GAP_MS = 250L
        const val RECONNECT_DELAY_MS = 1_500L
        const val CONNECT_RETRY_DELAY_MS = 5_000L
        const val KEY_AIR_PAIRED = "air_paired"
        const val KEY_AIR_PAIRING_REQUIRED = "air_pairing_required"
        const val KEY_PERSISTENCE = "air_adb_persistence"
        const val KEY_LOCAL_PORT = "air_local_adb_port"
        const val KEY_LOCAL_PORT_BOOT = "air_local_adb_boot"
        const val LOCAL_PORT_SOURCE = "local_tcpip"
        const val ADBD_EVIDENCE_COMMAND = "echo adbd_state=\$(getprop init.svc.adbd) " +
            "adbd_start_boottime_ns=\$(getprop ro.boottime.adbd) tcp_port=\$(getprop service.adb.tcp.port) " +
            "usb=\$(settings get global adb_enabled) wireless=\$(settings get global adb_wifi_enabled); " +
            "ps -A -o PID,STIME,ETIME,NAME | grep '[a]dbd$'; cat /proc/uptime"

    }

    private val prefs = context.getSharedPreferences("droidance_settings", Context.MODE_PRIVATE)
    private val _persistenceEnabled = MutableStateFlow(prefs.getBoolean(KEY_PERSISTENCE, false))
    val persistenceEnabled: StateFlow<Boolean> = _persistenceEnabled.asStateFlow()
    val shouldMaintainResidentChannel: Boolean get() = !automaticReconnectSuspended
    val hasLocalAdbPort: Boolean get() = _persistenceEnabled.value && savedLocalPort() != null
    @Volatile private var automaticReconnectSuspended = false
    @Volatile private var lastAdbdEvidence = ""
    @Volatile private var persistenceJob: Job? = null
    private val _state = MutableStateFlow(PairingState.IDLE)
    val state: StateFlow<PairingState> = _state.asStateFlow()
    private val _pairingTrustState = MutableStateFlow(initialPairingTrustState())
    val pairingTrustState: StateFlow<PairingTrustState> = _pairingTrustState.asStateFlow()
    val isPairingRequired: Boolean
        get() = _pairingTrustState.value == PairingTrustState.PAIRING_REQUIRED
    val isConnected: Boolean get() = isShellReady
    val isShellReady: Boolean
        get() = _state.value == PairingState.CONNECTED &&
            shellHealthState == ShellHealthState.READY &&
            activeConnection != null

    // 带超时的 shell 在独立线程跑阻塞的 conn.shell；超时后任务可能仍占用线程，故用可回收的 cached 池
    private val shellTimeoutExecutor = Executors.newCachedThreadPool { r ->
        Thread(r, "air-shell-timeout").apply { isDaemon = true }
    }

    private val _failure = MutableStateFlow<AirPairingFailure?>(null)
    val failure: StateFlow<AirPairingFailure?> = _failure.asStateFlow()

    val isPreviouslyPaired: Boolean
        get() = _pairingTrustState.value == PairingTrustState.PAIRED && privateKeyFile().exists()

    @Volatile private var pendingHost: String? = null
    @Volatile private var pendingPort: Int = 0
    @Volatile private var activeConnection: AirAdbClient? = null
    @Volatile private var activeEndpoint: ActiveAirEndpoint? = null
    @Volatile private var pairingTimeoutJob: Job? = null
    @Volatile private var discoveryTimeoutJob: Job? = null
    @Volatile private var reconnectJob: Job? = null
    @Volatile private var queuedPairCode: String? = null
    @Volatile private var shellHealthState: ShellHealthState = ShellHealthState.UNKNOWN
    @Volatile private var lastShellFailure: String = ""
    @Volatile private var lastShellCheckedAtMs: Long = 0L
    @Volatile private var lastShellProbeOutput: String = ""
    @Volatile private var shellSessionGeneration: Long = 0L
    // A callback has not established Wi-Fi availability at process startup.
    @Volatile private var wifiTransportAvailable: Boolean? = null

    @Volatile private var pairingDiscovery: AirMdnsDiscovery? = null
    @Volatile private var pairingDiscoveryRefreshJob: Job? = null
    private val pairingDiscoveryGeneration = AtomicLong(0L)
    private val pairingAttemptGeneration = AtomicLong(0L)
    @Volatile private var connectDiscovery: AirMdnsDiscovery? = null
    @Volatile private var connectScanJob: Job? = null
    private val connectionMutex = Mutex()
    private val connectAttemptGeneration = AtomicLong()

    init {
        if (isPairingRequired) {
            prefs.edit().remove(KEY_AIR_PAIRED).putBoolean(KEY_AIR_PAIRING_REQUIRED, true).apply()
            _failure.value = AirPairingFailure.PAIRING_REQUIRED
            if (rootConnection.currentMethod == DeviceConnectionMethod.WIRELESS &&
                notificationController.currentPairingSession() == null) {
                runCatching { notificationController.showPairingRequired() }
            }
        } else if (isPreviouslyPaired) {
            runCatching { notificationController.cancel() }
            appScope.launch(Dispatchers.IO) { ensureConnectedIfPaired() }
        }
    }

    private fun bootCount(): Int = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)

    private fun savedLocalPort(): Int? = prefs.getInt(KEY_LOCAL_PORT, -1)
        .takeIf { it in 49152..65535 && bootCount() >= 0 && prefs.getInt(KEY_LOCAL_PORT_BOOT, -2) == bootCount() }

    fun isUsbDebuggingEnabled(): Boolean? = runCatching {
        Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED) == 1
    }.getOrNull()

    fun enableAdbPersistence() {
        prefs.edit().putBoolean(KEY_PERSISTENCE, true).apply()
        _persistenceEnabled.value = true
        automaticReconnectSuspended = false
        ensureConnectedIfPaired()
    }

    /** Waits for the task lane, closes the listener before stopping the sidecar, then drops trust. */
    suspend fun stopAdbPersistence(resetTrust: Boolean, stopSidecar: () -> Unit): Boolean = lifecycleGate.whileIdle {
        automaticReconnectSuspended = true
        persistenceJob?.cancel()
        stopConnectAttempt()
        connectionMutex.withLock {
            val port = savedLocalPort()
            if (port != null && !closeLocalListener(port)) {
                _failure.value = AirPairingFailure.LOCAL_PORT_CLOSE_FAILED
                logPairingEvent("AIR local_tcpip close_failed port=$port; key and port retained")
                automaticReconnectSuspended = false
                markShellDown("AIR local TCP listener close failed", scheduleReconnect = false)
                scheduleReconnectIfPaired()
                return@withLock false
            }
            prefs.edit().putBoolean(KEY_PERSISTENCE, false).remove(KEY_LOCAL_PORT).remove(KEY_LOCAL_PORT_BOOT).apply()
            _persistenceEnabled.value = false
            stopSidecar()
            if (resetTrust) resetPairing() else disconnect()
            _failure.value = null
            true
        }
    }

    private fun listenerHasClosed(port: Int): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress(LOCAL_ADB_HOST, port), 250) }
    }.isFailure

    private fun localTcpProperty(): String? = runCatching {
        val process = ProcessBuilder("/system/bin/getprop", "service.adb.tcp.port").start()
        if (!process.waitFor(2, TimeUnit.SECONDS)) { process.destroyForcibly(); null }
        else process.inputStream.bufferedReader().use { it.readText().trim() }
    }.getOrNull()

    private fun closeLocalListener(port: Int): Boolean {
        if (localTcpProperty() == "0" && listenerHasClosed(port)) return true
        val candidates = mutableListOf<AirAdbClient>()
        activeConnection?.let { candidates.add(it) }
        // TLS remains useful when the first classic-ADB approval has not been granted yet.
        if (isWifiTransportAvailable()) {
            AirAdbPortPolicy.connectPorts(
                prefs.getInt("air_last_connect_port", -1).takeIf { it in 1..65535 }, getAdbWifiPort()
            ).forEach { candidates.add(AirAdbClient(LOCAL_ADB_HOST, it, loadOrCreateAdbKey())) }
        }
        candidates.add(AirAdbClient(LOCAL_ADB_HOST, port, loadOrCreateAdbKey(), true))
        try {
            for (connection in candidates) {
                val response = runCatching { connection.restartUsb() }
                logPairingEvent("AIR local_tcpip close_response port=$port response=${response.getOrNull()?.take(160)} error=${response.exceptionOrNull()?.javaClass?.simpleName}")
                if (response.getOrNull()?.contains("restarting in USB mode") != true && localTcpProperty() != "0") continue
                // A response acknowledges the request; wait for the old listener to close too.
                repeat(20) {
                    if (listenerHasClosed(port)) return true
                    Thread.sleep(250)
                }
            }
            return false
        } finally {
            candidates.filter { it !== activeConnection }.forEach { it.close() }
        }
    }

    /** A real authenticated connection per periodic job refreshes the system's key usage time. */
    suspend fun keepAlive() {
        if (rootConnection.currentMethod != DeviceConnectionMethod.WIRELESS) return
        if (!isPreviouslyPaired || automaticReconnectSuspended) return
        val port = savedLocalPort()?.takeIf { _persistenceEnabled.value }
        if (port != null) {
            val probe = AirAdbClient(LOCAL_ADB_HOST, port, loadOrCreateAdbKey(), true)
            try {
                probe.connect()
                check(probe.shell(AIR_HEALTH_CHECK_COMMAND).contains(AIR_HEALTH_CHECK_MARKER))
                recordLocalAttempt(port, "keepalive_ready", probe)
            } catch (error: Exception) {
                recordLocalAttempt(port, "keepalive_failed:${error.javaClass.simpleName}:${error.message.orEmpty().take(120)}")
            } finally { probe.close() }
        }
        ensureConnectedIfPaired()
    }

    private fun recordLocalAttempt(port: Int, result: String, connection: AirAdbClient? = null) {
        lastAdbdEvidence = runCatching {
            if (connection != null) connection.shell(ADBD_EVIDENCE_COMMAND) else {
                val process = ProcessBuilder("/system/bin/sh", "-c", ADBD_EVIDENCE_COMMAND).redirectErrorStream(true).start()
                if (!process.waitFor(2, TimeUnit.SECONDS)) { process.destroyForcibly(); "adbd evidence timed out" }
                else process.inputStream.bufferedReader().use { it.readText().take(1200) }
            }
        }.getOrElse { "adbd evidence unavailable: ${it.javaClass.simpleName}" }.take(1200)
        logPairingEvent("AIR local_tcpip attempt at_ms=${System.currentTimeMillis()} boot=${bootCount()} port=$port result=$result evidence=[$lastAdbdEvidence]")
    }

    private fun privateKeyFile(): File = File(context.filesDir, "air_key")
    private fun publicKeyFile(): File = File(context.filesDir, "air_key.pub")

    private fun initialPairingTrustState(): PairingTrustState = when {
        prefs.getBoolean(KEY_AIR_PAIRING_REQUIRED, false) -> PairingTrustState.PAIRING_REQUIRED
        prefs.getBoolean(KEY_AIR_PAIRED, false) && privateKeyFile().exists() -> PairingTrustState.PAIRED
        prefs.getBoolean(KEY_AIR_PAIRED, false) -> PairingTrustState.PAIRING_REQUIRED
        else -> PairingTrustState.UNPAIRED
    }

    private fun loadOrCreateAdbKey(): AirAdbKey =
        AirAdbKey.loadOrCreate(privateKeyFile(), publicKeyFile())

    fun shellHealthSnapshot(): ShellHealthSnapshot = ShellHealthSnapshot(
        state = shellHealthState,
        ready = isShellReady,
        lastFailure = lastShellFailure,
        lastCheckedAtMs = lastShellCheckedAtMs,
        probeOutput = lastShellProbeOutput,
        pairingState = _state.value,
        pairingTrustState = _pairingTrustState.value,
        previouslyPaired = isPreviouslyPaired,
        wifiTransportAvailable = wifiTransportAvailable,
        secureSettingsGranted = hasSecureSettingsPermission(),
        wirelessDebuggingEnabled = isWirelessDebuggingEnabled(),
        activeEndpoint = activeEndpoint,
        persistenceEnabled = _persistenceEnabled.value,
        localPort = savedLocalPort(),
        usbDebuggingEnabled = isUsbDebuggingEnabled(),
        adbdEvidence = lastAdbdEvidence,
        pairingFailure = _failure.value,
        notificationAvailability = notificationAvailability()
    )

    /**
     * Identity of the live ADB shell connection. It changes on every successful reconnect even
     * when wireless debugging happens to reuse the same endpoint address and port.
     */
    fun currentShellSessionId(): String? = activeEndpoint
        ?.takeIf { isShellReady }
        ?.let { endpoint -> "air-shell:${endpoint.endpointId}:$shellSessionGeneration" }

    fun notificationAvailability(): AirPairingNotificationAvailability =
        notificationController.availability()

    fun startDiscovery(): Boolean {
        automaticReconnectSuspended = false
        if (_state.value in setOf(PairingState.DISCOVERING, PairingState.AWAITING_CODE) && queuedPairCode == null) {
            return refreshPairingNotificationIfAwaiting()
        }
        if (_state.value != PairingState.IDLE) return false
        val notificationAvailability = notificationController.availability()
        if (notificationAvailability != AirPairingNotificationAvailability.AVAILABLE) {
            _failure.value = pairingNotificationFailure(notificationAvailability)
            logPairingEvent(
                "AIR pairing discovery withheld: notification=${notificationAvailability.name.lowercase()}"
            )
            return false
        }
        _failure.value = null
        queuedPairCode = null
        clearPendingPairingEndpoint()
        stopConnectAttempt()
        _state.value = PairingState.DISCOVERING
        logPairingEvent("AIR pairing discovery started")
        logPairingEvent("AIR environment manufacturer=${android.os.Build.MANUFACTURER} " +
            "model=${android.os.Build.MODEL} android=${android.os.Build.VERSION.RELEASE} " +
            "sdk=${android.os.Build.VERSION.SDK_INT} build=${android.os.Build.DISPLAY.take(120)} " +
            "usb=${isUsbDebuggingEnabled()} wireless=${isWirelessDebuggingEnabled()} " +
            "notifications=${notificationAvailability()}")
        // Prepare a silent RemoteInput while foreground so it remains usable if frozen.
        // Alert only after discovering the service opened by the system pairing-code dialog.
        if (!showPairingCodeNotification(alert = false)) return false
        // Connecting before pairing grants trust can reject the new key and reset this flow.
        // Start ADB connection discovery only after the pairing protocol succeeds.
        startPairingDiscovery()
        return true
    }

    private fun startPairingDiscovery() {
        pairingDiscoveryRefreshJob?.cancel()
        installPairingDiscovery()
        pairingDiscoveryRefreshJob = appScope.launch {
            while (_state.value == PairingState.DISCOVERING && pendingPort <= 0) {
                delay(PAIRING_DISCOVERY_REFRESH_MS)
                if (_state.value != PairingState.DISCOVERING || pendingPort > 0) break
                logPairingEvent("AIR pairing mDNS: discovery_refresh")
                pairingDiscovery?.stop()
                pairingDiscovery = null
                delay(PAIRING_DISCOVERY_RESTART_GAP_MS)
                if (_state.value == PairingState.DISCOVERING && pendingPort <= 0) {
                    installPairingDiscovery()
                }
            }
        }
    }

    private fun installPairingDiscovery() {
        val generation = pairingDiscoveryGeneration.incrementAndGet()
        pairingDiscovery?.stop()
        pairingDiscovery = AirMdnsDiscovery(
            context = context,
            appScope = appScope,
            serviceType = TLS_PAIRING_SERVICE,
            onResolved = { host, port ->
                if (
                    _state.value != PairingState.DISCOVERING ||
                    generation != pairingDiscoveryGeneration.get()
                ) return@AirMdnsDiscovery
                pendingHost = preferLocalAdbHost(host)
                pendingPort = port
                val queued = queuedPairCode
                if (queued != null) {
                    doExecutePairing(pendingHost ?: LOCAL_ADB_HOST, port, queued)
                } else {
                    moveToAwaitingCode()
                }
            },
            onDiagnostic = { detail -> logPairingEvent("AIR pairing mDNS: $detail") }
        ).also { it.start() }
    }

    fun stopDiscovery() {
        pairingAttemptGeneration.incrementAndGet()
        discoveryTimeoutJob?.cancel()
        discoveryTimeoutJob = null
        pairingDiscoveryGeneration.incrementAndGet()
        queuedPairCode = null
        clearPendingPairingEndpoint()
        pairingDiscoveryRefreshJob?.cancel()
        pairingDiscoveryRefreshJob = null
        pairingDiscovery?.stop()
        pairingDiscovery = null
        if (_state.value !in setOf(PairingState.CONNECTED, PairingState.PAIRING)) {
            notificationController.cancel()
            _state.value = PairingState.IDLE
        }
    }

    // Called when user submits the 6-digit pairing code from the notification.
    // The code is used by the system's wireless debugging pairing protocol (SPAKE2+).
    // After submitting, we discover the ADB connect port and attempt connection
    // using the pre-generated key pair (which the system will trust post-pairing).

    fun reconnect(port: Int? = null): Boolean {
        if (port != null && port !in 1..65535) {
            _failure.value = AirPairingFailure.INVALID_DEBUG_PORT
            return false
        }
        if (port != null && isPreviouslyPaired && _state.value == PairingState.CONNECTING) {
            stopConnectAttempt()
            _state.value = PairingState.IDLE
        }
        if (!isPreviouslyPaired || _state.value != PairingState.IDLE) return false
        automaticReconnectSuspended = false
        if (!hasLocalAdbPort && !isWifiTransportAvailable()) {
            if (wifiTransportAvailable == false) _failure.value = AirPairingFailure.WIFI_UNAVAILABLE
            return false
        }
        _failure.value = null
        _state.value = PairingState.CONNECTING
        startConnectDiscovery(manualPort = port)
        return true
    }

    fun ensureConnectedIfPaired() {
        if (rootConnection.currentMethod != DeviceConnectionMethod.WIRELESS) return
        if (!isPreviouslyPaired || automaticReconnectSuspended) return
        if (isShellReady) {
            preparePersistenceIfNeeded()
        } else if (_state.value == PairingState.IDLE) reconnect()
    }

    fun onWifiNetworkChanged(trigger: String, wifiAvailable: Boolean) {
        wifiTransportAvailable = wifiAvailable
        if (rootConnection.currentMethod != DeviceConnectionMethod.WIRELESS) return
        debugLogAppender.appendEvent(
            "AIR wifi_transport_changed(trigger=${trigger.take(40)}, available=$wifiAvailable, " +
                "state=${_state.value.name.lowercase()}, trust=${_pairingTrustState.value.name.lowercase()})"
        )
        if (!isPreviouslyPaired) return
        // The classic local listener is independent of wireless debugging and its network callbacks.
        if (activeEndpoint?.source == LOCAL_PORT_SOURCE) return
        stopConnectAttempt()
        markShellDown(
            "AIR WiFi transport changed: trigger=${trigger.take(40)} available=$wifiAvailable",
            scheduleReconnect = false
        )
        if (hasLocalAdbPort || wifiAvailable) {
            _failure.value = null
            scheduleReconnectIfPaired(delayMs = 0)
        } else _failure.value = AirPairingFailure.WIFI_UNAVAILABLE
    }

    fun hasSecureSettingsPermission(): Boolean =
        context.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED

    private fun isWifiTransportAvailable(): Boolean = wifiTransportAvailable == true

    fun isWirelessDebuggingEnabled(): Boolean? = runCatching {
        Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled") == 1
    }.getOrNull()

    /**
     * 首次 ADB 连通后已经授予 WRITE_SECURE_SETTINGS；以后 App/保活服务可以在本机恢复无线调试。
     * 这里只恢复开关，不伪造端口：设备重启后的动态端口仍由 Settings/mDNS 发现。
     */
    fun ensureWirelessDebuggingEnabled(): Boolean {
        if (!isPreviouslyPaired || !isWifiTransportAvailable() || !hasSecureSettingsPermission() ||
            activeEndpoint?.source == LOCAL_PORT_SOURCE) return false
        if (isWirelessDebuggingEnabled() == true) return true
        return runCatching {
            Settings.Global.putInt(context.contentResolver, "adb_wifi_enabled", 1)
        }.getOrDefault(false)
    }

    fun requestPairingFromNotification(
        session: String?,
        input: String?,
        hasResults: Boolean
    ) {
        fun record(result: String) = logPairingEvent(
            "AIR pairing notification input: result=$result " +
                "has_results=$hasResults input_length=${input?.length ?: -1} " +
                "system_locales=${LocaleManagerCompat.getSystemLocales(context).toLanguageTags()} " +
                "app_locales=${ContextCompat.getContextForLanguage(context).resources.configuration.locales.toLanguageTags()} " +
                "sdk=${android.os.Build.VERSION.SDK_INT}"
        )
        if (session == null || session != notificationController.currentPairingSession()) {
            record("stale_session")
            return
        }
        if (_state.value in setOf(PairingState.PAIRING, PairingState.CONNECTING, PairingState.CONNECTED)) {
            record("already_in_progress")
            return
        }
        val code = input?.trim()
        val failure = when {
            input == null -> AirPairingFailure.NOTIFICATION_INPUT_MISSING
            code == null || code.length != 6 || !code.all { it in '0'..'9' } -> AirPairingFailure.INVALID_PAIRING_CODE
            else -> null
        }
        if (failure != null) {
            record(if (!hasResults) "missing_results" else if (input == null) "missing_field" else "invalid_input")
            _failure.value = failure
            notificationController.showPairingFailure(failure, allowRetry = true)
            return
        }
        if (_state.value == PairingState.IDLE && !isPreviouslyPaired) {
            automaticReconnectSuspended = false
            stopConnectAttempt()
            _state.value = PairingState.DISCOVERING
            record("session_restored")
        } else {
            record("received")
        }
        requestPairing(checkNotNull(code))
    }

    private fun requestPairing(code: String) {
        if (code.length != 6 || !code.all { it in '0'..'9' }) {
            _failure.value = AirPairingFailure.INVALID_PAIRING_CODE
            runCatching { notificationController.showInvalidPairingCode() }
            return
        }
        if (_state.value !in setOf(PairingState.DISCOVERING, PairingState.AWAITING_CODE)) {
            _failure.value = AirPairingFailure.PAIRING_REQUEST_UNAVAILABLE
            runCatching { notificationController.showPairingUnavailable() }
            return
        }
        _failure.value = null
        queuedPairCode = code
        // The system allocates a fresh pairing port when its code dialog is reopened. Never
        // bind a submitted code to the endpoint that happened to be discovered earlier.
        pairingDiscoveryGeneration.incrementAndGet()
        clearPendingPairingEndpoint()
        _state.value = PairingState.DISCOVERING
        logPairingEvent("AIR pairing endpoint refresh requested for submitted code")
        runCatching { notificationController.showPairingWaitingForService() }
        // Time the submitted request, not the user's time spent in system settings.
        discoveryTimeoutJob?.cancel()
        discoveryTimeoutJob = appScope.launch {
            delay(120_000)
            if (_state.value == PairingState.DISCOVERING && queuedPairCode != null) {
                resumePairingDiscovery(AirPairingFailure.PAIRING_SERVICE_NOT_FOUND,
                    "AIR pairing endpoint discovery timed out after code submission")
            }
        }
        startPairingDiscovery()
    }

    private fun moveToAwaitingCode() {
        if (showPairingCodeNotification(alert = true)) {
            pairingDiscoveryRefreshJob?.cancel()
            pairingDiscoveryRefreshJob = null
            _state.value = PairingState.AWAITING_CODE
        }
    }

    private fun showPairingCodeNotification(alert: Boolean): Boolean {
        val postResult = notificationController.showPairingRequest(_failure.value, alert = alert)
        if (postResult.posted) {
            return true
        } else {
            _failure.value = pairingNotificationFailure(postResult.availability)
            logPairingEvent(
                "AIR pairing code notification unavailable: ${postResult.availability.name.lowercase()} " +
                    postResult.detail.take(160)
            )
            stopDiscovery()
            return false
        }
    }

    fun refreshPairingNotificationIfAwaiting(): Boolean {
        if (_state.value !in setOf(PairingState.DISCOVERING, PairingState.AWAITING_CODE) || queuedPairCode != null) return false
        // Discovery can remain active after rejection or an invalid code. Restore RemoteInput
        // immediately without requiring another settings navigation or reusing a stale endpoint.
        return showPairingCodeNotification(alert = false)
    }

    private fun pairingNotificationFailure(
        availability: AirPairingNotificationAvailability
    ): AirPairingFailure = when (availability) {
            AirPairingNotificationAvailability.PERMISSION_REQUIRED ->
                AirPairingFailure.NOTIFICATION_PERMISSION_REQUIRED
            AirPairingNotificationAvailability.APP_NOTIFICATIONS_DISABLED ->
                AirPairingFailure.APP_NOTIFICATIONS_DISABLED
            AirPairingNotificationAvailability.CHANNEL_DISABLED ->
                AirPairingFailure.NOTIFICATION_CHANNEL_DISABLED
            AirPairingNotificationAvailability.POST_FAILED,
            AirPairingNotificationAvailability.AVAILABLE ->
                AirPairingFailure.NOTIFICATION_POST_FAILED
        }

    private fun logPairingEvent(event: String) {
        val diagnostic = "$event [state=${_state.value} trust=${_pairingTrustState.value} " +
            "failure=${_failure.value} wifi=$wifiTransportAvailable]"
        debugLogAppender.appendEvent(diagnostic)
        debugLogAppender.appendPersistentEvent("air-pairing", diagnostic)
    }

    private fun clearPendingPairingEndpoint() {
        pendingHost = null
        pendingPort = 0
    }

    private fun resumePairingDiscovery(failure: AirPairingFailure, diagnostic: String) {
        pairingAttemptGeneration.incrementAndGet()
        pairingTimeoutJob?.cancel()
        pairingTimeoutJob = null
        pairingDiscoveryGeneration.incrementAndGet()
        discoveryTimeoutJob?.cancel()
        discoveryTimeoutJob = null
        queuedPairCode = null
        clearPendingPairingEndpoint()
        _failure.value = failure
        _state.value = PairingState.DISCOVERING
        logPairingEvent(diagnostic)
        runCatching { notificationController.showPairingFailure(failure, allowRetry = true) }
        startPairingDiscovery()
    }

    private fun doExecutePairing(host: String, port: Int, code: String) {
        val attemptGeneration = pairingAttemptGeneration.incrementAndGet()
        queuedPairCode = null
        discoveryTimeoutJob?.cancel()
        discoveryTimeoutJob = null
        _failure.value = null
        runCatching { notificationController.showPairingSubmitted() }
        pairingDiscoveryRefreshJob?.cancel()
        pairingDiscoveryRefreshJob = null
        _state.value = PairingState.PAIRING
        pairingTimeoutJob?.cancel()
        pairingTimeoutJob = appScope.launch(Dispatchers.IO) {
            delay(20_000)
            if (_state.value == PairingState.PAIRING &&
                pairingAttemptGeneration.get() == attemptGeneration) {
                resumePairingDiscovery(
                    AirPairingFailure.PAIRING_TIMEOUT,
                    "AIR pairing timed out while waiting for the system pairing service"
                )
            }
        }
        appScope.launch(Dispatchers.IO) {
            val adbKey = loadOrCreateAdbKey()
            runCatching {
                AirPairingClient(host, port, code, adbKey).pair()
            }.onSuccess { success ->
                if (pairingAttemptGeneration.get() != attemptGeneration) return@onSuccess
                pairingTimeoutJob?.cancel()
                pairingTimeoutJob = null
                if (success) {
                    markPairingTrusted()
                    runCatching { notificationController.showPairingSucceeded() }
                    _state.value = PairingState.CONNECTING
                    startConnectDiscovery()
                } else {
                    resumePairingDiscovery(
                        AirPairingFailure.PAIRING_REJECTED,
                        "AIR pairing was rejected by the system pairing service"
                    )
                }
            }.onFailure { e ->
                if (pairingAttemptGeneration.get() != attemptGeneration) return@onFailure
                resumePairingDiscovery(
                    AirPairingFailure.PAIRING_CONNECTION_FAILED,
                    "AIR pairing connection failed: ${e::class.java.simpleName}: ${e.message.orEmpty().take(120)}"
                )
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun getWifiIpAddress(): String? = runCatching {
        val wm = context.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
        val ip = wm?.connectionInfo?.ipAddress?.takeIf { it != 0 } ?: return null
        "${ip and 0xFF}.${(ip shr 8) and 0xFF}.${(ip shr 16) and 0xFF}.${(ip shr 24) and 0xFF}"
    }.getOrNull()

    private fun getAdbWifiPort(): Int? = runCatching {
        android.provider.Settings.Global.getString(context.contentResolver, "adb_wifi_port")
            ?.toIntOrNull()?.takeIf { it > 0 }
    }.getOrNull()

    private fun preferLocalAdbHost(discoveredHost: String?): String =
        discoveredHost?.takeIf { it == LOCAL_ADB_HOST } ?: LOCAL_ADB_HOST

    private fun isConnectionBlockedByPairingRequirement(): Boolean =
        !isPreviouslyPaired || _state.value in setOf(
            PairingState.DISCOVERING,
            PairingState.AWAITING_CODE,
            PairingState.PAIRING
        )

    private fun failConnection(
        connection: AirAdbClient?,
        failure: AirPairingFailure,
        diagnostic: String
    ) {
        markShellDown(diagnostic, connection, scheduleReconnect = false)
        _failure.value = failure
        logPairingEvent(diagnostic)
        runCatching { notificationController.showConnectionFailure(failure) }
    }

    private fun markPairingRequired(connection: AirAdbClient?, diagnostic: String) {
        reconnectJob?.cancel()
        reconnectJob = null
        connectScanJob?.cancel()
        connectScanJob = null
        connectDiscovery?.stop()
        connectDiscovery = null
        markShellDown(diagnostic, connection, scheduleReconnect = false)
        prefs.edit()
            .remove(KEY_AIR_PAIRED)
            .putBoolean(KEY_AIR_PAIRING_REQUIRED, true)
            .apply()
        _pairingTrustState.value = PairingTrustState.PAIRING_REQUIRED
        _failure.value = AirPairingFailure.PAIRING_REQUIRED
        logPairingEvent("AIR pairing authorization rejected; explicit re-pairing required")
        runCatching { notificationController.showPairingRequired() }
    }

    private fun markPairingTrusted(connectPort: Int? = null) {
        val editor = prefs.edit()
            .putBoolean(KEY_AIR_PAIRED, true)
            .remove(KEY_AIR_PAIRING_REQUIRED)
        if (connectPort != null) editor.putInt("air_last_connect_port", connectPort)
        editor.apply()
        _pairingTrustState.value = PairingTrustState.PAIRED
    }

    fun notifyPairingRequiredIfNeeded(): Boolean {
        if (rootConnection.currentMethod != DeviceConnectionMethod.WIRELESS) return false
        if (!isPairingRequired) return false
        runCatching { notificationController.showPairingRequired() }
        return true
    }

    private fun markShellReady(probeOutput: String) {
        shellHealthState = ShellHealthState.READY
        lastShellFailure = ""
        lastShellCheckedAtMs = System.currentTimeMillis()
        lastShellProbeOutput = probeOutput.take(800)
    }

    private fun stopConnectAttempt() {
        connectAttemptGeneration.incrementAndGet()
        reconnectJob?.cancel()
        reconnectJob = null
        connectScanJob?.cancel()
        connectScanJob = null
        connectDiscovery?.stop()
        connectDiscovery = null
    }

    private fun markShellDown(
        message: String,
        connection: AirAdbClient? = activeConnection,
        scheduleReconnect: Boolean = true
    ) {
        if (connection != null && activeConnection != null && connection !== activeConnection) {
            connection.runCatching { close() }
            return
        }
        shellHealthState = ShellHealthState.DOWN
        lastShellFailure = message.take(240)
        lastShellCheckedAtMs = System.currentTimeMillis()
        lastShellProbeOutput = ""
        activeConnection?.runCatching { close() }
        if (connection !== activeConnection) {
            connection?.runCatching { close() }
        }
        activeConnection = null
        activeEndpoint = null
        _state.value = PairingState.IDLE
        if (scheduleReconnect) scheduleReconnectIfPaired()
    }

    private fun scheduleReconnectIfPaired(delayMs: Long = RECONNECT_DELAY_MS) {
        if (!isPreviouslyPaired || automaticReconnectSuspended || (!hasLocalAdbPort && !isWifiTransportAvailable()) || reconnectJob?.isActive == true) return
        reconnectJob = appScope.launch(Dispatchers.IO) {
            delay(delayMs)
            if (_state.value != PairingState.IDLE || !isPreviouslyPaired || automaticReconnectSuspended) return@launch
            _state.value = PairingState.CONNECTING
            startConnectDiscovery()
        }
    }

    private fun preparePersistenceIfNeeded() {
        if (!_persistenceEnabled.value || activeEndpoint?.source == LOCAL_PORT_SOURCE ||
            !isWifiTransportAvailable() || isUsbDebuggingEnabled() != true || persistenceJob?.isActive == true) return
        persistenceJob = appScope.launch(Dispatchers.IO) {
            connectionMutex.withLock {
                val connection = activeConnection ?: return@withLock
                val endpoint = activeEndpoint ?: return@withLock
                if (endpoint.source == LOCAL_PORT_SOURCE) return@withLock
                val switched = runCatching { openLocalListener(connection) }
                if (switched.isFailure) {
                    logPairingEvent("AIR local_tcpip setup_failed: ${switched.exceptionOrNull()?.javaClass?.simpleName}")
                    markShellDown("AIR local TCP listener setup failed")
                } else if (switched.getOrDefault(false)) {
                    markShellDown("AIR adbd restarting for local TCP listener", scheduleReconnect = false)
                    scheduleReconnectIfPaired(delayMs = 0)
                }
            }
        }
    }

    private fun chooseLocalPort(): Int {
        val random = SecureRandom()
        repeat(32) {
            val candidate = 49152 + random.nextInt(16384)
            if (runCatching {
                ServerSocket().use { it.bind(InetSocketAddress(LOCAL_ADB_HOST, candidate)) }
            }.isSuccess) return candidate
        }
        error("No available high TCP port")
    }

    /** Called with the connection lock. A busy task defers this mutation without blocking recovery. */
    private suspend fun openLocalListener(connection: AirAdbClient): Boolean {
        if (!_persistenceEnabled.value || !isWifiTransportAvailable() || isUsbDebuggingEnabled() != true) return false
        return lifecycleGate.tryWhileIdle {
            withContext(NonCancellable) {
                if (!_persistenceEnabled.value || automaticReconnectSuspended || !isWifiTransportAvailable() ||
                    isUsbDebuggingEnabled() != true) return@withContext false
                val port = savedLocalPort() ?: chooseLocalPort().also {
                    check(bootCount() >= 0) { "Cannot identify the current boot" }
                    check(prefs.edit().putInt(KEY_LOCAL_PORT, it).putInt(KEY_LOCAL_PORT_BOOT, bootCount()).commit())
                }
                val current = connection.shell("getprop service.adb.tcp.port").trim().toIntOrNull()
                if (current == port) return@withContext true
                _state.value = PairingState.CONNECTING
                // Persist before the command: adbd can restart before it returns a response.
                logPairingEvent("AIR local_tcpip opening port=$port boot=${bootCount()}")
                val response = runCatching { connection.restartTcpip(port) }
                logPairingEvent("AIR local_tcpip open_response port=$port response=${response.getOrNull()?.take(160)} error=${response.exceptionOrNull()?.javaClass?.simpleName}")
                // Keep task admission closed while the asynchronous daemon restart settles.
                repeat(3) {
                    delay(1_000)
                    val probe = AirAdbClient(LOCAL_ADB_HOST, port, loadOrCreateAdbKey(), true)
                    try {
                        probe.connect()
                        check(probe.shell("getprop service.adb.tcp.port").trim() == port.toString())
                        recordLocalAttempt(port, "opened", probe)
                        return@withContext true
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        recordLocalAttempt(port, "open_probe_failed:${error.javaClass.simpleName}:${error.message.orEmpty().take(120)}")
                    } finally { probe.close() }
                }
                true
            }
        } ?: false
    }

    /** Checks a cached socket before recovery; CONNECTED alone cannot detect an adbd restart. */
    fun probeConnection() {
        if (automaticReconnectSuspended) return
        if (!isShellReady) { ensureConnectedIfPaired(); return }
        val output = shell(AIR_HEALTH_CHECK_COMMAND, 3_000)
        if (!output.contains(AIR_HEALTH_CHECK_MARKER) && isShellReady) markShellDown("AIR recovery probe failed")
    }

    private suspend fun doConnect(host: String, port: Int, source: String, generation: Long) = connectionMutex.withLock {
        currentCoroutineContext().ensureActive()
        check(generation == connectAttemptGeneration.get() && !automaticReconnectSuspended && !isConnectionBlockedByPairingRequirement()) {
            "ADB pairing authorization must be restored before reconnecting"
        }
        if (isShellReady) {
            return
        }
        val adbKey = loadOrCreateAdbKey()
        activeConnection?.runCatching { close() }
        activeConnection = null
        activeEndpoint = null
        val endpoint = ActiveAirEndpoint(host = host, port = port, source = source)
        val local = source == LOCAL_PORT_SOURCE
        val connection = AirAdbClient(host, port, adbKey, allowUserAuthorization = local)
        try {
            try {
                connection.connect()
            } catch (error: Throwable) {
                if (generation == connectAttemptGeneration.get() && !automaticReconnectSuspended &&
                    !local && AirAdbConnectionFailurePolicy.requiresPairing(error)) {
                    markPairingRequired(
                        connection,
                        "ADB authorization rejected: ${error.message.orEmpty().take(120)}"
                    )
                } else {
                    connection.runCatching { close() }
                }
                throw error
            }
            currentCoroutineContext().ensureActive()
            val healthOutput = runCatching {
                connection.shell(AIR_HEALTH_CHECK_COMMAND)
            }.getOrElse { error ->
                failConnection(
                    connection,
                    AirPairingFailure.ADB_HEALTH_CHECK_FAILED,
                    "ADB health check failed: ${error::class.java.simpleName}: ${error.message.orEmpty().take(120)}"
                )
                throw error
            }
            if (!healthOutput.contains(AIR_HEALTH_CHECK_MARKER)) {
                failConnection(
                    connection,
                    AirPairingFailure.ADB_HEALTH_RESPONSE_MISSING,
                    "ADB health check did not return the ready marker"
                )
                throw IllegalStateException("ADB health check did not return ready marker")
            }
            val identityOutput = runCatching {
                connection.shell("id")
            }.getOrElse { error ->
                failConnection(
                    connection,
                    AirPairingFailure.ADB_IDENTITY_CHECK_FAILED,
                    "ADB shell identity check failed: ${error::class.java.simpleName}: ${error.message.orEmpty().take(120)}"
                )
                throw error
            }
            if (!identityOutput.contains("uid=")) {
                failConnection(
                    connection,
                    AirPairingFailure.ADB_IDENTITY_RESPONSE_MISSING,
                    "ADB shell identity check did not return uid"
                )
                throw IllegalStateException("ADB shell identity check did not return uid")
            }
            val packageName = context.packageName
            val permissionOutput = runCatching {
                connection.shell("pm grant $packageName android.permission.WRITE_SECURE_SETTINGS")
                connection.shell(
                    "cmd package check-permission android.permission.WRITE_SECURE_SETTINGS $packageName 0"
                )
            }.getOrDefault("")
            if (permissionOutput.contains("granted", ignoreCase = true) || hasSecureSettingsPermission()) {
                prefs.edit().putBoolean("air_secure_settings_granted", true).apply()
            }
            currentCoroutineContext().ensureActive()
            check(generation == connectAttemptGeneration.get()) { "ADB connection attempt was superseded" }
            if (!local) prefs.edit().putInt("air_last_connect_port", port).apply()
            if (!local && openLocalListener(connection)) {
                connection.close()
                markShellDown("AIR adbd restarting for local TCP listener", scheduleReconnect = false)
                scheduleReconnectIfPaired(delayMs = RECONNECT_DELAY_MS)
                return
            }
            currentCoroutineContext().ensureActive()
            check(generation == connectAttemptGeneration.get()) { "ADB connection attempt was superseded" }
            if (local) recordLocalAttempt(port, "connected", connection)
            activeConnection = connection
            activeEndpoint = ActiveAirEndpoint(
                host = endpoint.host,
                port = endpoint.port,
                source = endpoint.source,
                endpointId = endpoint.endpointId
            )
            shellSessionGeneration += 1L
            markShellReady("$healthOutput\n$identityOutput")
            connectDiscovery?.stop()
            connectDiscovery = null
            // Keep TLS bootstrap candidates separate from the opt-in classic TCP listener.
            markPairingTrusted(connectPort = port.takeUnless { local })
            pairingTimeoutJob?.cancel()
            pairingTimeoutJob = null
            _failure.value = null
            _state.value = PairingState.CONNECTED
        } finally {
            if (activeConnection !== connection) connection.close()
        }
    }

    private fun startConnectDiscovery(manualPort: Int? = null) {
        if (isConnectionBlockedByPairingRequirement() || automaticReconnectSuspended) return
        val generation = connectAttemptGeneration.incrementAndGet()
        connectScanJob?.cancel()
        connectDiscovery?.stop()
        connectDiscovery = null
        connectScanJob = appScope.launch(Dispatchers.IO) {
            val localPort = savedLocalPort()?.takeIf { _persistenceEnabled.value }
            if (localPort != null) {
                val attempt = runCatching { doConnect(LOCAL_ADB_HOST, localPort, LOCAL_PORT_SOURCE, generation) }
                    .onFailure { if (it !is CancellationException) recordLocalAttempt(localPort, "connect_failed:${it.javaClass.simpleName}:${it.message.orEmpty().take(120)}") }
                currentCoroutineContext().ensureActive()
                if (attempt.isSuccess && isShellReady) return@launch
            }
            if (!isWifiTransportAvailable()) {
                _state.value = PairingState.IDLE
                _failure.value = if (localPort != null) AirPairingFailure.LOCAL_ADB_CONNECTION_FAILED else AirPairingFailure.WIFI_UNAVAILABLE
                scheduleReconnectIfPaired(CONNECT_RETRY_DELAY_MS)
                return@launch
            }
            if (generation != connectAttemptGeneration.get() || isConnectionBlockedByPairingRequirement() || automaticReconnectSuspended) return@launch
            _state.value = PairingState.CONNECTING
            // Only the bootstrap path restores wireless debugging. Local reconnect never needs it.
            ensureWirelessDebuggingEnabled()
            connectDiscovery = AirMdnsDiscovery(
                context = context, appScope = appScope, serviceType = TLS_CONNECT_SERVICE,
                onResolved = { host, port ->
                    if (generation == connectAttemptGeneration.get() && !isConnectionBlockedByPairingRequirement() && _state.value == PairingState.CONNECTING) {
                        appScope.launch(Dispatchers.IO) { runCatching { doConnect(preferLocalAdbHost(host), port, "mdns", generation) } }
                    }
                },
                onDiagnostic = { detail -> debugLogAppender.appendEvent("AIR connect mDNS: $detail") }
            ).also { it.start() }
            val ports = (listOfNotNull(manualPort) + AirAdbPortPolicy.connectPorts(
                lastPort = prefs.getInt("air_last_connect_port", -1).takeIf { it in 1..65535 },
                settingsPort = getAdbWifiPort()
            )).distinct()
            for (port in ports) {
                for (host in listOfNotNull(LOCAL_ADB_HOST, getWifiIpAddress()).distinct()) {
                    currentCoroutineContext().ensureActive()
                    if (_state.value != PairingState.CONNECTING || isConnectionBlockedByPairingRequirement()) return@launch
                    runCatching { doConnect(host, port, if (port == manualPort) "manual" else "port_scan", generation) }
                    if (_state.value != PairingState.CONNECTING || isPairingRequired) return@launch
                }
            }
            repeat(20) {
                delay(1_000)
                if (_state.value != PairingState.CONNECTING || isConnectionBlockedByPairingRequirement()) return@launch
            }
            connectDiscovery?.stop()
            connectDiscovery = null
            _failure.value = AirPairingFailure.ADB_ENDPOINT_NOT_FOUND
            logPairingEvent("ADB connect discovery timed out without a usable endpoint")
            runCatching { notificationController.showConnectionFailure(AirPairingFailure.ADB_ENDPOINT_NOT_FOUND) }
            _state.value = PairingState.IDLE
            scheduleReconnectIfPaired(CONNECT_RETRY_DELAY_MS)
        }
    }

    fun shell(command: String, service: String = "shell"): String {
        val conn = activeConnection ?: run {
            markShellDown("AIR shell disconnected: no active ADB connection")
            return "[AIR_DISCONNECTED]"
        }
        return runCatching { conn.shell(command, service) }
            .getOrElse {
                val message = "AIR shell error: ${it.message.orEmpty().ifBlank { it::class.java.simpleName }}"
                markShellDown(message, conn)
                "[AIR_ERROR] ${it.message}"
            }
    }

    /**
     * 带硬超时的 shell：ADB over TLS 的 socket 读在弱网/异常页（如反爬验证页）下可能长时间阻塞，
     * 截图等对时延敏感的调用用此版本兜底，超时返回 [AIR_TIMEOUT]，避免整个执行循环被一次截图挂死几十秒。
     */
    fun shell(command: String, timeoutMs: Long, service: String = "shell"): String {
        if (timeoutMs <= 0) return shell(command, service)
        val conn = activeConnection ?: run {
            markShellDown("AIR shell disconnected: no active ADB connection")
            return "[AIR_DISCONNECTED]"
        }
        val future = shellTimeoutExecutor.submit<String> { conn.shell(command, service) }
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            markShellDown("AIR shell timeout after ${timeoutMs}ms", conn)
            "[AIR_TIMEOUT] shell exceeded ${timeoutMs}ms"
        } catch (e: Exception) {
            markShellDown("AIR shell error: ${e.cause?.message ?: e.message}", conn)
            "[AIR_ERROR] ${e.cause?.message ?: e.message}"
        }
    }

    fun disconnect() {
        pairingAttemptGeneration.incrementAndGet()
        pairingDiscoveryGeneration.incrementAndGet()
        automaticReconnectSuspended = true
        discoveryTimeoutJob?.cancel()
        discoveryTimeoutJob = null
        stopConnectAttempt()
        queuedPairCode = null
        pairingTimeoutJob?.cancel()
        pairingTimeoutJob = null
        pairingDiscoveryRefreshJob?.cancel()
        pairingDiscoveryRefreshJob = null
        pairingDiscovery?.stop()
        pairingDiscovery = null
        markShellDown("AIR bridge disconnected", scheduleReconnect = false)
        notificationController.cancel()
    }

    private fun resetPairing() {
        disconnect()
        prefs.edit()
            .remove(KEY_AIR_PAIRED)
            .remove(KEY_AIR_PAIRING_REQUIRED)
            .remove("air_secure_settings_granted")
            .remove("air_last_connect_port")
            .apply()
        privateKeyFile().delete()
        publicKeyFile().delete()
        _pairingTrustState.value = PairingTrustState.UNPAIRED
        _failure.value = null
    }

    fun startRequiredPairing(): Boolean {
        if (!isPairingRequired) return false
        val notificationAvailability = notificationController.availability()
        if (notificationAvailability != AirPairingNotificationAvailability.AVAILABLE) {
            _failure.value = pairingNotificationFailure(notificationAvailability)
            return false
        }
        disconnect()
        return startDiscovery()
    }
}
