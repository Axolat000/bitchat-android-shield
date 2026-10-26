package com.bitchat.android.net

import android.app.Application
import android.util.Log
import com.bitchat.android.util.AppConstants
import info.guardianproject.arti.ArtiLogListener
import info.guardianproject.arti.ArtiProxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CancellationException

import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong

/**
 * Tor provider implementation using custom-built Arti (Tor-in-Rust).
 *
 * This singleton provides Tor anonymity features using a custom Arti build
 * compiled with 16KB page size support for Google Play compliance.
 *
 * Based on the original TorManager implementation.
 */
class ArtiTorManager internal constructor(
    private val appScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    private val proxyFactory: (Application, Int, ArtiLogListener) -> ArtiProxy = { application, port, listener ->
        ArtiProxy.Builder(application).setSocksPort(port).setDnsPort(port + 1).setLogListener(listener).build()
    },
    private val stopTimeoutMs: Long = AppConstants.Tor.STOP_TIMEOUT_MS,
    private val restartDelayMs: Long = AppConstants.Tor.RESTART_DELAY_MS,
    private val inactivityTimeoutMs: Long = AppConstants.Tor.INACTIVITY_TIMEOUT_MS,
    private val nowMillis: () -> Long = System::currentTimeMillis
) {
    enum class TorState {
        OFF,
        STARTING,
        BOOTSTRAPPING,
        RUNNING,
        STOPPING,
        ERROR
    }

    data class TorStatus(
        val mode: TorMode = TorMode.OFF,
        val running: Boolean = false,
        val bootstrapPercent: Int = 0,
        val lastLogLine: String = "",
        val state: TorState = TorState.OFF,
        val reconnecting: Boolean = false
    )

    companion object {
        private const val TAG = "ArtiTorManager"
        private const val DEFAULT_SOCKS_PORT = AppConstants.Tor.DEFAULT_SOCKS_PORT
        private const val MAX_RETRY_ATTEMPTS = AppConstants.Tor.MAX_RETRY_ATTEMPTS

        @Volatile
        private var INSTANCE: ArtiTorManager? = null

        fun getInstance(): ArtiTorManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ArtiTorManager().also { INSTANCE = it }
            }
        }
    }

    @Volatile
    private var initialized = false
    @Volatile
    private var socksAddr: InetSocketAddress? = null
    @Volatile
    private var artiProxy: ArtiProxy? = null
    @Volatile
    private var lastMode: TorMode = TorMode.OFF
    private val applyMutex = Mutex()
    @Volatile
    private var desiredMode: TorMode = TorMode.OFF
    @Volatile
    private var currentSocksPort: Int = DEFAULT_SOCKS_PORT
    @Volatile
    private var lastLogTime = AtomicLong(0L)
    @Volatile
    private var retryAttempts = 0
    @Volatile
    private var bindRetryAttempts = 0
    private var inactivityJob: Job? = null
    private var retryJob: Job? = null
    private var currentApplication: Application? = null
    private val proxyGeneration = AtomicLong(0L)

    private enum class LifecycleState { STOPPED, STARTING, RUNNING, STOPPING }

    @Volatile
    private var lifecycleState: LifecycleState = LifecycleState.STOPPED

    private val _statusFlow = MutableStateFlow(
        TorStatus(
            mode = TorMode.OFF,
            running = false,
            bootstrapPercent = 0,
            lastLogLine = "",
            state = TorState.OFF
        )
    )

    val statusFlow: StateFlow<TorStatus> = _statusFlow.asStateFlow()

    private val stateChangeDeferred = AtomicReference<CompletableDeferred<TorState>?>(null)

    fun isProxyEnabled(): Boolean {
        val s = _statusFlow.value
        return s.mode != TorMode.OFF && s.running && s.bootstrapPercent >= 100 &&
                socksAddr != null && s.state == TorState.RUNNING
    }

    fun init(application: Application) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            initialized = true
            currentApplication = application
            TorPreferenceManager.init(application)

            val savedMode = TorPreferenceManager.get(application)
            if (savedMode == TorMode.ON) {
                if (currentSocksPort < DEFAULT_SOCKS_PORT) {
                    currentSocksPort = DEFAULT_SOCKS_PORT
                }
                desiredMode = savedMode
                socksAddr = InetSocketAddress("127.0.0.1", currentSocksPort)
                try {
                    OkHttpProvider.reset()
                } catch (_: Throwable) {
                }  // Only reset OkHttp during init
            }
            appScope.launch {
                applyMode(application, savedMode)
            }

            appScope.launch {
                TorPreferenceManager.modeFlow.collect { mode ->
                    applyMode(application, mode)
                }
            }
        }
    }

    fun currentSocksAddress(): InetSocketAddress? = socksAddr

    /**
     * Wait until the currently selected HTTP route can be used.
     *
     * When Tor mode is enabled, [socksAddr] is intentionally published before
     * bootstrap completes so clients fail closed instead of leaking traffic
     * directly. Callers that initiate one-shot HTTP work should wait here rather
     * than repeatedly connecting to a SOCKS port that is not listening yet.
     */
    suspend fun awaitSelectedRoute(timeoutMs: Long): Boolean {
        if (currentSocksAddress() == null || isProxyEnabled()) {
            return true
        }

        return withTimeoutOrNull(timeoutMs) {
            statusFlow.first {
                currentSocksAddress() == null || isProxyEnabled()
            }
            true
        } ?: false
    }

    suspend fun applyMode(application: Application, mode: TorMode) {
        applyMutex.withLock {
            try {
                currentApplication = application
                desiredMode = mode
                lastMode = mode
                val s = _statusFlow.value
                if (mode == s.mode && mode != TorMode.OFF &&
                    (lifecycleState == LifecycleState.STARTING || lifecycleState == LifecycleState.RUNNING)
                ) {
                    return
                }
                when (mode) {
                    TorMode.OFF -> {
                        Log.i(TAG, "applyMode: OFF -> stopping tor")
                        lifecycleState = LifecycleState.STOPPING
                        _statusFlow.value = _statusFlow.value.copy(
                            mode = TorMode.OFF,
                            reconnecting = false,
                            running = false,
                            bootstrapPercent = 0,
                            state = TorState.STOPPING
                        )
                        stopArti()
                        waitForStateTransition(target = TorState.OFF, timeoutMs = stopTimeoutMs)
                        socksAddr = null
                        _statusFlow.value = _statusFlow.value.copy(
                            mode = TorMode.OFF,
                            reconnecting = false,
                            running = false,
                            bootstrapPercent = 0,
                            state = TorState.OFF
                        )
                        currentSocksPort = DEFAULT_SOCKS_PORT
                        bindRetryAttempts = 0
                        lifecycleState = LifecycleState.STOPPED
                        resetNetworkConnections()
                    }

                    TorMode.ON -> {
                        Log.i(TAG, "applyMode: ON -> starting arti")
                        if (currentSocksPort < DEFAULT_SOCKS_PORT) {
                            currentSocksPort = DEFAULT_SOCKS_PORT
                        }
                        bindRetryAttempts = 0
                        lifecycleState = LifecycleState.STARTING
                        _statusFlow.value = _statusFlow.value.copy(
                            mode = TorMode.ON,
                            reconnecting = false,
                            running = false,
                            bootstrapPercent = 0,
                            state = TorState.STARTING
                        )
                        socksAddr = InetSocketAddress("127.0.0.1", currentSocksPort)
                        resetNetworkConnections()
                        startArti(application, useDelay = false)
                        appScope.launch {
                            waitUntilBootstrapped()
                            if (_statusFlow.value.running && desiredMode == TorMode.ON) {
                                socksAddr = InetSocketAddress("127.0.0.1", currentSocksPort)
                                Log.i(TAG, "Tor ON: proxy set to ${socksAddr}")
                                resetNetworkConnections()
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to apply Arti mode: ${e.message}")
            }
        }
    }

    private suspend fun startArti(application: Application, useDelay: Boolean = false) {
        try {
            stopArtiAndWait()
            if (useDelay) {
                delay(restartDelayMs)
            }

            if (desiredMode != TorMode.ON) return
            lifecycleState = LifecycleState.STARTING
            val generation = proxyGeneration.incrementAndGet()
            val listener = ArtiLogListener { line ->
                if (generation == proxyGeneration.get() && line != null) {
                    lastLogTime.set(nowMillis())
                    _statusFlow.update { it.copy(lastLogLine = line) }
                    handleArtiLogLine(line)
                    if (_statusFlow.value.bootstrapPercent < 100) armBootstrapInactivityWatchdog()
                }
            }
            // A new port requires a new native proxy, including its DNS listener.
            val proxy = proxyFactory(application, currentSocksPort, listener)
            artiProxy = proxy

            // Native callbacks may arrive before start() returns. Publish startup
            // first so returning from start cannot erase a readiness callback.
            _statusFlow.update {
                it.copy(
                    running = true,
                    bootstrapPercent = 0,
                    state = TorState.STARTING
                )
            }
            proxy.start()
            lastLogTime.set(nowMillis())
            lifecycleState = LifecycleState.RUNNING
            startInactivityMonitoring()

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error starting Arti on port $currentSocksPort: ${e.message}")
            _statusFlow.update { it.copy(running = false, bootstrapPercent = 0, state = TorState.ERROR) }

            val isBindError = isBindError(e)
            if (isBindError && bindRetryAttempts < MAX_RETRY_ATTEMPTS) {
                bindRetryAttempts++
                currentSocksPort++
                Log.w(TAG, "Port bind failed (attempt $bindRetryAttempts/$MAX_RETRY_ATTEMPTS), retrying with port $currentSocksPort")
                socksAddr = InetSocketAddress("127.0.0.1", currentSocksPort)
                resetNetworkConnections()
                startArti(application, useDelay = false)
            } else if (isBindError) {
                Log.e(TAG, "Max bind retry attempts reached ($MAX_RETRY_ATTEMPTS), giving up")
                lifecycleState = LifecycleState.STOPPED
                _statusFlow.update {
                    it.copy(
                        running = false,
                        bootstrapPercent = 0,
                        state = TorState.ERROR,
                        reconnecting = false
                    )
                }
            } else {
                scheduleRetry(application)
            }
        }
    }

    private fun isBindError(exception: Exception): Boolean {
        val message = exception.message?.lowercase() ?: ""
        return message.contains("bind") ||
                message.contains("address already in use") ||
                message.contains("port") && message.contains("use") ||
                message.contains("permission denied") && message.contains("port") ||
                message.contains("could not bind")
    }

    /**
     * Reset network connections after Tor state changes.
     * Rebuilds OkHttp clients and reconnects Nostr relays.
     */
    private fun resetNetworkConnections() {
        try {
            OkHttpProvider.reset()
        } catch (_: Throwable) {
        }
        try {
            com.bitchat.android.nostr.NostrRelayManager.shared.resetAllConnections()
        } catch (_: Throwable) {
        }
    }

    private fun stopArtiInternal() {
        try {
            val proxy = artiProxy
            if (proxy != null) {
                try {
                    proxy.stop()
                } catch (_: Throwable) {
                }
            }
            stopInactivityMonitoring()
            stopRetryMonitoring()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping Arti: ${e.message}")
        }
    }

    private fun stopArti() {
        stopArtiInternal()
        socksAddr = null
        _statusFlow.value = _statusFlow.value.copy(
            running = false,
            bootstrapPercent = 0,
            state = TorState.STOPPING
        )
    }

    private suspend fun stopArtiAndWait(timeoutMs: Long = stopTimeoutMs) {
        lifecycleState = LifecycleState.STOPPING
        val hadProxy = artiProxy != null
        stopArtiInternal()
        if (hadProxy) {
            waitForStateTransition(target = TorState.OFF, timeoutMs = timeoutMs)
            delay(200)
        }
    }

    private suspend fun restartArti(application: Application) = applyMutex.withLock {
        if (desiredMode != TorMode.ON) return@withLock
        _statusFlow.update { it.copy(reconnecting = true) }
        // Detach the trigger before stopping monitors, or the restart cancels itself.
        val caller = currentCoroutineContext()[Job]
        if (retryJob === caller) retryJob = null
        if (inactivityJob === caller) inactivityJob = null
        Log.i(TAG, "Restarting Arti (keeping SOCKS proxy enabled)...")
        delay(restartDelayMs)
        if (desiredMode == TorMode.ON) startArti(application, useDelay = false)
    }

    private fun startInactivityMonitoring() {
        armBootstrapInactivityWatchdog()
    }

    /**
     * One-shot startup watchdog. Arti log activity re-arms it and successful bootstrap cancels it,
     * so a healthy background Tor process has no permanent five-second polling coroutine.
     */
    private fun armBootstrapInactivityWatchdog() {
        inactivityJob?.cancel()
        if (lifecycleState != LifecycleState.RUNNING || _statusFlow.value.bootstrapPercent >= 100) {
            inactivityJob = null
            return
        }
        inactivityJob = appScope.launch {
            delay(inactivityTimeoutMs)
            val timeSinceLastActivity = nowMillis() - lastLogTime.get()
            if (
                timeSinceLastActivity >= inactivityTimeoutMs &&
                _statusFlow.value.mode == TorMode.ON &&
                _statusFlow.value.bootstrapPercent < 100 &&
                lifecycleState == LifecycleState.RUNNING
            ) {
                Log.w(TAG, "Bootstrap inactivity detected (${timeSinceLastActivity}ms), restarting Arti")
                currentApplication?.let { restartArti(it) }
            }
        }
    }

    private fun stopInactivityMonitoring() {
        inactivityJob?.cancel()
        inactivityJob = null
    }

    private fun scheduleRetry(application: Application) {
        retryJob?.cancel()
        if (retryAttempts < MAX_RETRY_ATTEMPTS) {
            _statusFlow.update { it.copy(reconnecting = true) }
            retryAttempts++
            val delayMs = (1000L * (1 shl retryAttempts)).coerceAtMost(30000L)
            Log.w(TAG, "Scheduling Arti retry attempt $retryAttempts in ${delayMs}ms")
            retryJob = appScope.launch {
                delay(delayMs)
                val currentMode = _statusFlow.value.mode
                if (currentMode == TorMode.ON) {
                    restartArti(application)
                }
            }
        } else {
            _statusFlow.update { it.copy(reconnecting = false) }
            Log.e(TAG, "Max retry attempts reached, giving up on Arti connection")
        }
    }

    private fun stopRetryMonitoring() {
        retryJob?.cancel()
        retryJob = null
    }

    private suspend fun waitUntilBootstrapped() {
        val current = _statusFlow.value
        if (!current.running) return
        if (current.bootstrapPercent >= 100 && current.state == TorState.RUNNING) return
        while (true) {
            val s = statusFlow.first {
                (it.bootstrapPercent >= 100 && it.state == TorState.RUNNING) ||
                        !it.running ||
                        it.state == TorState.ERROR
            }
            if (!s.running || s.state == TorState.ERROR) return
            if (s.bootstrapPercent >= 100 && s.state == TorState.RUNNING) return
        }
    }

    private fun handleArtiLogLine(s: String) {
        val currentState = _statusFlow.value.state
        val currentLifecycle = lifecycleState

        when {
            s.contains("AMEx: state changed to Initialized", ignoreCase = true) -> {
                if (currentLifecycle != LifecycleState.STARTING && currentLifecycle != LifecycleState.RUNNING) {
                    return
                }
                _statusFlow.update { it.copy(state = TorState.STARTING) }
                completeWaitersIf(TorState.STARTING)
            }

            s.contains("AMEx: state changed to Starting", ignoreCase = true) -> {
                if (currentLifecycle != LifecycleState.STARTING && currentLifecycle != LifecycleState.RUNNING) {
                    return
                }
                _statusFlow.update { it.copy(state = TorState.STARTING) }
                completeWaitersIf(TorState.STARTING)
            }

            s.contains(
                "Sufficiently bootstrapped; system SOCKS now functional",
                ignoreCase = true
            ) -> {
                if (currentLifecycle != LifecycleState.RUNNING && currentLifecycle != LifecycleState.STARTING) {
                    return
                }
                _statusFlow.update {
                    it.copy(
                        bootstrapPercent = 100,
                        running = true,
                        state = TorState.RUNNING,
                        reconnecting = false
                    )
                }
                retryAttempts = 0
                bindRetryAttempts = 0
                stopInactivityMonitoring()
                completeWaitersIf(TorState.RUNNING)
            }

            s.contains("We have found that guard [scrubbed] is usable.", ignoreCase = true) -> {
                if (currentLifecycle != LifecycleState.RUNNING && currentLifecycle != LifecycleState.STARTING) {
                    return
                }
                _statusFlow.update {
                    it.copy(
                        state = TorState.RUNNING,
                        bootstrapPercent = 100,
                        running = true,
                        reconnecting = false
                    )
                }
                stopInactivityMonitoring()
                completeWaitersIf(TorState.RUNNING)
            }

            s.contains("AMEx: state changed to Stopping", ignoreCase = true) -> {
                if (currentLifecycle != LifecycleState.STOPPING) {
                    return
                }
                _statusFlow.update {
                    it.copy(
                        state = TorState.STOPPING,
                        running = false
                    )
                }
            }

            s.contains("AMEx: state changed to Stopped", ignoreCase = true) -> {
                if (currentLifecycle != LifecycleState.STOPPING && currentLifecycle != LifecycleState.STOPPED) {
                    return
                }
                _statusFlow.update {
                    it.copy(
                        state = TorState.OFF,
                        running = false,
                        bootstrapPercent = 0
                    )
                }
                completeWaitersIf(TorState.OFF)
            }

            s.contains("Another process has the lock on our state files", ignoreCase = true) -> {
                _statusFlow.update { it.copy(state = TorState.ERROR) }
            }
        }
    }

    private fun completeWaitersIf(state: TorState) {
        stateChangeDeferred.getAndSet(null)?.let { def ->
            def.complete(state)
        }
    }

    private suspend fun waitForStateTransition(target: TorState, timeoutMs: Long): TorState? {
        val def = CompletableDeferred<TorState>()
        stateChangeDeferred.getAndSet(def)?.cancel()
        return withTimeoutOrNull(timeoutMs) {
            val cur = _statusFlow.value.state
            if (cur == target) return@withTimeoutOrNull cur
            def.await()
        }
    }

    fun isTorAvailable(): Boolean = true
}
