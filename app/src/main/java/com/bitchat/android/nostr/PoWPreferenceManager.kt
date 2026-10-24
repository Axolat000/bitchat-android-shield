package com.bitchat.android.nostr

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages Proof of Work preferences for Nostr events:
 * 1) Incoming filter PoW (powEnabled / powDifficulty) -> default 0 bits (disabled) so all users are visible
 * 2) Outgoing send PoW (sendPowEnabled / sendPowDifficulty) -> default 12 bits (enabled) so our messages pass 12-bit filters
 */
object PoWPreferenceManager {

    private const val PREFS_NAME = "pow_preferences"
    private const val KEY_POW_ENABLED = "pow_receive_enabled_v3"
    private const val KEY_POW_DIFFICULTY = "pow_receive_difficulty_v3"
    private const val KEY_SEND_POW_ENABLED = "pow_send_enabled_v3"
    private const val KEY_SEND_POW_DIFFICULTY = "pow_send_difficulty_v3"

    // Default values for INCOMING filter: 0 bits (see everyone)
    private const val DEFAULT_POW_ENABLED = false
    private const val DEFAULT_POW_DIFFICULTY = 0

    // Default values for OUTGOING messages: 12 bits enabled by default
    private const val DEFAULT_SEND_POW_ENABLED = true
    private const val DEFAULT_SEND_POW_DIFFICULTY = 12

    // State flows for reactive UI (Incoming PoW filter)
    private val _powEnabled = MutableStateFlow(DEFAULT_POW_ENABLED)
    val powEnabled: StateFlow<Boolean> = _powEnabled.asStateFlow()

    private val _powDifficulty = MutableStateFlow(DEFAULT_POW_DIFFICULTY)
    val powDifficulty: StateFlow<Int> = _powDifficulty.asStateFlow()

    // State flows for reactive UI (Outgoing Send PoW)
    private val _sendPowEnabled = MutableStateFlow(DEFAULT_SEND_POW_ENABLED)
    val sendPowEnabled: StateFlow<Boolean> = _sendPowEnabled.asStateFlow()

    private val _sendPowDifficulty = MutableStateFlow(DEFAULT_SEND_POW_DIFFICULTY)
    val sendPowDifficulty: StateFlow<Int> = _sendPowDifficulty.asStateFlow()

    // Mining state for animated indicators
    private val _isMining = MutableStateFlow(false)
    val isMining: StateFlow<Boolean> = _isMining.asStateFlow()

    private lateinit var sharedPrefs: SharedPreferences
    private var isInitialized = false

    /**
     * Initialize the preference manager with application context
     * Should be called once during app startup
     */
    fun init(context: Context) {
        if (isInitialized) return

        sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Load current values (Incoming filter)
        _powEnabled.value = sharedPrefs.getBoolean(KEY_POW_ENABLED, DEFAULT_POW_ENABLED)
        _powDifficulty.value = sharedPrefs.getInt(KEY_POW_DIFFICULTY, DEFAULT_POW_DIFFICULTY)

        // Load current values (Outgoing send PoW)
        _sendPowEnabled.value = sharedPrefs.getBoolean(KEY_SEND_POW_ENABLED, DEFAULT_SEND_POW_ENABLED)
        _sendPowDifficulty.value = sharedPrefs.getInt(KEY_SEND_POW_DIFFICULTY, DEFAULT_SEND_POW_DIFFICULTY)

        isInitialized = true
    }

    /**
     * Get current incoming PoW filter enabled state
     */
    fun isPowEnabled(): Boolean {
        return _powEnabled.value
    }

    /**
     * Set incoming PoW filter enabled state
     */
    fun setPowEnabled(enabled: Boolean) {
        _powEnabled.value = enabled
        if (::sharedPrefs.isInitialized) {
            sharedPrefs.edit().putBoolean(KEY_POW_ENABLED, enabled).apply()
        }
    }

    /**
     * Get current incoming PoW filter difficulty setting
     */
    fun getPowDifficulty(): Int {
        return _powDifficulty.value
    }

    /**
     * Set incoming PoW filter difficulty (clamped between 0 and 32)
     */
    fun setPowDifficulty(difficulty: Int) {
        val clampedDifficulty = difficulty.coerceIn(0, 32)
        _powDifficulty.value = clampedDifficulty
        if (::sharedPrefs.isInitialized) {
            sharedPrefs.edit().putInt(KEY_POW_DIFFICULTY, clampedDifficulty).apply()
        }
    }

    /**
     * Get current outgoing Send PoW enabled state
     */
    fun isSendPowEnabled(): Boolean {
        return _sendPowEnabled.value
    }

    /**
     * Set outgoing Send PoW enabled state
     */
    fun setSendPowEnabled(enabled: Boolean) {
        _sendPowEnabled.value = enabled
        if (::sharedPrefs.isInitialized) {
            sharedPrefs.edit().putBoolean(KEY_SEND_POW_ENABLED, enabled).apply()
        }
    }

    /**
     * Get current outgoing Send PoW difficulty
     */
    fun getSendPowDifficulty(): Int {
        return _sendPowDifficulty.value
    }

    /**
     * Set outgoing Send PoW difficulty (clamped between 0 and 32)
     */
    fun setSendPowDifficulty(difficulty: Int) {
        val clampedDifficulty = difficulty.coerceIn(0, 32)
        _sendPowDifficulty.value = clampedDifficulty
        if (::sharedPrefs.isInitialized) {
            sharedPrefs.edit().putInt(KEY_SEND_POW_DIFFICULTY, clampedDifficulty).apply()
        }
    }

    /**
     * Get current settings as a data class
     */
    data class PoWSettings(
        val enabled: Boolean,
        val difficulty: Int
    )

    /**
     * Get current INCOMING filter settings
     */
    fun getCurrentSettings(): PoWSettings {
        return PoWSettings(
            enabled = _powEnabled.value,
            difficulty = _powDifficulty.value
        )
    }

    /**
     * Get current OUTGOING send PoW settings
     */
    fun getCurrentSendSettings(): PoWSettings {
        return PoWSettings(
            enabled = _sendPowEnabled.value,
            difficulty = _sendPowDifficulty.value
        )
    }

    /**
     * Reset to default settings
     */
    fun resetToDefaults() {
        setPowEnabled(DEFAULT_POW_ENABLED)
        setPowDifficulty(DEFAULT_POW_DIFFICULTY)
        setSendPowEnabled(DEFAULT_SEND_POW_ENABLED)
        setSendPowDifficulty(DEFAULT_SEND_POW_DIFFICULTY)
    }

    /**
     * Get difficulty levels with descriptions for UI
     */
    fun getDifficultyLevels(): List<Pair<Int, String>> {
        return listOf(
            0 to "Disabled (no PoW)",
            8 to "Very Low (instant)",
            12 to "Low (~0.1s)",
            16 to "Medium (~2s)",
            20 to "High (~30s)",
            24 to "Very High (~8m)",
            28 to "Extreme (~2h)",
            32 to "Maximum (~8h)"
        )
    }

    /**
     * Get current mining state
     */
    fun isMining(): Boolean {
        return _isMining.value
    }

    /**
     * Start mining state - triggers animated indicators
     */
    fun startMining() {
        _isMining.value = true
    }

    /**
     * Stop mining state - stops animated indicators
     */
    fun stopMining() {
        _isMining.value = false
    }
}
