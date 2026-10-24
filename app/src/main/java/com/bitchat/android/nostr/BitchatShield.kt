package com.bitchat.android.nostr

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

/**
 * BitchatShield
 * - Blocks Zalgo / combining diacritic bombs (e.g. "baguette#̢̛ͥ̀̽̽ͨ̌...")
 * - Blocks forbidden invisible / BiDi / control characters while allowing all standard emojis & accents
 * - Blocks link / URL spam (allows normal single link sharing, but blocks repeated links/domains
 *   even when bots change the surrounding message text or rotate pubkeys, and blocks multi-link floods)
 * - Blocks automated spam bots (bot nickname patterns like liquid-bounce_8hex / bot_8hex,
 *   # spoofing in nicknames, and repeated spam flood across rotating pubkeys)
 * - Designed to never false-positive on normal users or Nostr relay history backfills
 */
object BitchatShield {
    private const val TAG = "BitchatShield"
    private const val PREFS_NAME = "bitchat_shield_prefs_v2"

    private const val KEY_SHIELD_ENABLED = "shield_enabled"
    private const val KEY_BLOCK_ZALGO = "block_zalgo"
    private const val KEY_BLOCK_BOTS = "block_bots"
    private const val KEY_BLOCK_LINKS = "block_links"
    private const val KEY_STRICT_NICKNAMES = "strict_nicknames"
    private const val KEY_BLOCKED_COUNT = "blocked_count"

    // Zalgo thresholds (after NFC normalization, normal French/European accented letters have 0 combining marks)
    private const val MAX_COMBINING_MARKS_PER_GRAPHEME = 2
    private const val MAX_TOTAL_ZALGO_MARKS = 4
    private const val MAX_MESSAGE_LENGTH = 2000
    private const val MAX_MESSAGE_LINES = 25
    private const val MAX_NICKNAME_LENGTH = 24

    // Deduplication for long promotional/bot spam messages only (>= 45 alphanumeric chars)
    private const val MIN_FINGERPRINT_LEN_FOR_DEDUPE = 45
    private const val CONTENT_DEDUPE_WINDOW_SECONDS = 120L

    // Link spam limits: allow 1 post per domain/link every 5 minutes (300s), max 2 links in a single message
    private const val LINK_SPAM_WINDOW_SECONDS = 300L
    private const val MAX_LINKS_PER_MESSAGE = 2

    private val _shieldEnabled = MutableStateFlow(true)
    val shieldEnabled: StateFlow<Boolean> = _shieldEnabled.asStateFlow()

    private val _blockZalgo = MutableStateFlow(true)
    val blockZalgo: StateFlow<Boolean> = _blockZalgo.asStateFlow()

    private val _blockBots = MutableStateFlow(true)
    val blockBots: StateFlow<Boolean> = _blockBots.asStateFlow()

    private val _blockLinks = MutableStateFlow(true)
    val blockLinks: StateFlow<Boolean> = _blockLinks.asStateFlow()

    private val _strictNicknames = MutableStateFlow(true)
    val strictNicknames: StateFlow<Boolean> = _strictNicknames.asStateFlow()

    private val _blockedCount = MutableStateFlow(0)
    val blockedCount: StateFlow<Int> = _blockedCount.asStateFlow()

    private lateinit var prefs: SharedPreferences
    private var initialized = false

    // Maps normalized long-message fingerprint -> last event createdAt (seconds)
    private val recentLongSpamFingerprints = ConcurrentHashMap<String, Long>()

    // Maps normalized link/domain key -> last event createdAt (seconds)
    private val recentLinkTimestamps = ConcurrentHashMap<String, Long>()

    // Bot nickname regex: e.g. bot_1867b55a, liquid-bounce_c958b26e, liquid-bounce1234
    private val BOT_HEX_NICKNAME_REGEX = Regex(
        "^(?:bot|spam|liquid[-_]?bounce|autoedit)[a-z0-9_-]*$|^[a-z0-9_-]{2,20}[_-][0-9a-f]{6,12}$",
        RegexOption.IGNORE_CASE
    )

    // Link / URL detection regex (matches http://, https://, www., t.me/, discord.gg/, and domain.tld)
    private val URL_OR_LINK_REGEX = Regex(
        """(?i)(?:https?://[^\s<>"]+|www\.[^\s<>"]+|\b[a-z0-9][a-z0-9-]{1,61}\s*(?:\.|\[\.\]|\(\.\))\s*(?:com|fr|net|org|io|gg|xyz|me|co|ru|cn|top|site|online|shop|club|vip|icu|app|dev|ai|tv|cc|link|click|info|biz|pro)(?:/[^\s<>"]*)?)"""
    )

    fun init(context: Context) {
        if (initialized) return
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _shieldEnabled.value = prefs.getBoolean(KEY_SHIELD_ENABLED, true)
        _blockZalgo.value = prefs.getBoolean(KEY_BLOCK_ZALGO, true)
        _blockBots.value = prefs.getBoolean(KEY_BLOCK_BOTS, true)
        _blockLinks.value = prefs.getBoolean(KEY_BLOCK_LINKS, true)
        _strictNicknames.value = prefs.getBoolean(KEY_STRICT_NICKNAMES, true)
        _blockedCount.value = prefs.getInt(KEY_BLOCKED_COUNT, 0)
        initialized = true
    }

    fun setShieldEnabled(enabled: Boolean) {
        _shieldEnabled.value = enabled
        if (::prefs.isInitialized) prefs.edit().putBoolean(KEY_SHIELD_ENABLED, enabled).apply()
    }

    fun setBlockZalgo(enabled: Boolean) {
        _blockZalgo.value = enabled
        if (::prefs.isInitialized) prefs.edit().putBoolean(KEY_BLOCK_ZALGO, enabled).apply()
    }

    fun setBlockBots(enabled: Boolean) {
        _blockBots.value = enabled
        if (::prefs.isInitialized) prefs.edit().putBoolean(KEY_BLOCK_BOTS, enabled).apply()
    }

    fun setBlockLinks(enabled: Boolean) {
        _blockLinks.value = enabled
        if (::prefs.isInitialized) prefs.edit().putBoolean(KEY_BLOCK_LINKS, enabled).apply()
    }

    fun setStrictNicknames(enabled: Boolean) {
        _strictNicknames.value = enabled
        if (::prefs.isInitialized) prefs.edit().putBoolean(KEY_STRICT_NICKNAMES, enabled).apply()
    }

    private fun recordBlocked(reason: String, pubkey: String? = null) {
        val next = _blockedCount.value + 1
        _blockedCount.value = next
        if (::prefs.isInitialized) {
            prefs.edit().putInt(KEY_BLOCKED_COUNT, next).apply()
        }
        Log.w(TAG, "Shield blocked event ($reason) from ${pubkey?.take(12) ?: "unknown"}")
    }

    sealed class InspectionResult {
        data class Allowed(val cleanContent: String, val cleanNickname: String?) : InspectionResult()
        data class Blocked(val reason: String) : InspectionResult()
    }

    /**
     * Returns true if the text contains any URL, web link, or obfuscated domain link.
     */
    fun containsLinkOrUrl(input: String): Boolean {
        if (input.isEmpty()) return false
        return URL_OR_LINK_REGEX.containsMatchIn(input)
    }

    /**
     * Extracts normalized domain/host keys from all URLs/links present in the text.
     * For example, "https://www.AutoEditLab.com/promo?id=1" -> "autoeditlab.com"
     */
    fun extractNormalizedLinkKeys(input: String): List<String> {
        if (input.isEmpty()) return emptyList()
        return URL_OR_LINK_REGEX.findAll(input).map { match ->
            var raw = match.value.lowercase()
                .replace("[.]", ".")
                .replace("(.)", ".")
                .replace(" ", "")
            raw = raw.removePrefix("https://").removePrefix("http://").removePrefix("www.")
            val host = raw.substringBefore('/').substringBefore('?').substringBefore('#').trimEnd('.', ',', ';', ')', ']')
            host.ifEmpty { raw }
        }.toList()
    }

    /**
     * Returns true if the code point is a Zalgo / stacked combining mark.
     * Explicitly allows Emoji Variation Selectors (U+FE00..U+FE0F, U+E0100..U+E01EF)
     * and Emoji Keycap (U+20E3) so normal emojis never trigger Zalgo detection.
     */
    fun isCombiningOrZalgoCodePoint(cp: Int): Boolean {
        // Allow Emoji Variation Selectors & Emoji Keycap
        if (cp in 0xFE00..0xFE0F || cp in 0xE0100..0xE01EF || cp == 0x20E3) {
            return false
        }
        // Explicit Zalgo / combining diacritic blocks & Cyrillic combining millions/hundred-thousands (U+0488, U+0489)
        if (cp in 0x0300..0x036F ||
            cp in 0x0483..0x0489 ||
            cp in 0x1AB0..0x1AFF ||
            cp in 0x1DC0..0x1DFF ||
            cp in 0x20D0..0x20FF ||
            cp in 0xFE20..0xFE2F
        ) {
            return true
        }
        val type = Character.getType(cp).toByte()
        return type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK
    }

    /**
     * Returns true if the code point is a malicious invisible / BiDi override / control character.
     * Allows standard whitespace (\n, \r, \t) and Emoji Zero-Width Joiner (U+200D).
     */
    fun isForbiddenInvisibleOrControl(cp: Int): Boolean {
        if (cp == '\n'.code || cp == '\r'.code || cp == '\t'.code) return false
        // Allow Emoji ZWJ (U+200D) and Variation Selectors (U+FE0E, U+FE0F)
        if (cp == 0x200D || cp in 0xFE00..0xFE0F) return false
        if (cp < 0x20 || cp == 0x7F) return true
        // Combining Grapheme Joiner (U+034F), BiDi overrides (U+202A..U+202E, U+2066..U+2069), invisible math operators
        if (cp == 0x034F ||
            cp == 0x061C ||
            cp in 0x200B..0x200C ||
            cp in 0x200E..0x200F ||
            cp in 0x202A..0x202E ||
            cp in 0x2060..0x206F ||
            cp == 0xFEFF ||
            cp in 0xFFF9..0xFFFB
        ) {
            return true
        }
        return false
    }

    /**
     * Detects Zalgo / combining mark flood in a string.
     * Standard French/European accented characters (é, è, ê, à, ç, ô, œ...) compose under NFC into
     * single precomposed letters, so they have 0 combining marks after NFC normalization.
     */
    fun containsZalgoOrForbiddenChars(input: String): Boolean {
        if (input.isEmpty()) return false
        val nfc = Normalizer.normalize(input, Normalizer.Form.NFC)
        var consecutiveMarks = 0
        var totalMarks = 0
        var totalForbidden = 0

        var i = 0
        while (i < nfc.length) {
            val cp = nfc.codePointAt(i)
            i += Character.charCount(cp)

            if (isCombiningOrZalgoCodePoint(cp)) {
                consecutiveMarks++
                totalMarks++
                if (consecutiveMarks > MAX_COMBINING_MARKS_PER_GRAPHEME) {
                    return true
                }
            } else {
                consecutiveMarks = 0
            }

            if (isForbiddenInvisibleOrControl(cp)) {
                totalForbidden++
                if (totalForbidden >= 3) {
                    return true
                }
            }
        }

        return totalMarks >= MAX_TOTAL_ZALGO_MARKS
    }

    /**
     * Strips all Zalgo combining marks and forbidden control characters, preserving normal text & emojis.
     */
    fun sanitizeText(input: String, maxLength: Int = MAX_MESSAGE_LENGTH): String {
        if (input.isEmpty()) return ""
        val nfc = Normalizer.normalize(input, Normalizer.Form.NFC)
        val sb = StringBuilder(nfc.length.coerceAtMost(maxLength))
        var i = 0
        while (i < nfc.length && sb.length < maxLength) {
            val cp = nfc.codePointAt(i)
            i += Character.charCount(cp)
            if (isCombiningOrZalgoCodePoint(cp) || isForbiddenInvisibleOrControl(cp)) {
                continue
            }
            sb.appendCodePoint(cp)
        }
        return sb.toString().trim()
    }

    /**
     * Sanitizes a nickname and removes '#' injection attempts (like "baguette#̢̛ͥ...").
     */
    fun sanitizeNickname(rawNickname: String?): String? {
        if (rawNickname.isNullOrBlank()) return null
        val beforeHash = rawNickname.substringBefore('#')
        val singleLine = beforeHash.replace('\n', ' ').replace('\r', ' ')
        val cleaned = sanitizeText(singleLine, MAX_NICKNAME_LENGTH)
        return cleaned.ifBlank { null }
    }

    /**
     * Inspects an incoming Nostr geohash/DM/note event or Mesh message.
     */
    fun inspectEvent(
        pubkey: String,
        rawContent: String,
        rawNickname: String?,
        createdAtSeconds: Long? = null,
        nowMs: Long = System.currentTimeMillis()
    ): InspectionResult {
        if (!_shieldEnabled.value) {
            return InspectionResult.Allowed(rawContent, rawNickname)
        }

        val normalizedPubkey = pubkey.lowercase()

        // 1. Zalgo & forbidden character check on both nickname and content
        if (_blockZalgo.value) {
            if (rawNickname != null && containsZalgoOrForbiddenChars(rawNickname)) {
                recordBlocked("Zalgo/forbidden chars in nickname", normalizedPubkey)
                return InspectionResult.Blocked("Zalgo in nickname")
            }
            if (rawContent.isNotEmpty() && containsZalgoOrForbiddenChars(rawContent)) {
                recordBlocked("Zalgo/forbidden chars in content", normalizedPubkey)
                return InspectionResult.Blocked("Zalgo in content")
            }
        }

        val cleanNick = sanitizeNickname(rawNickname)
        // If rawContent was empty (e.g. presence or teleport event), allow it if nickname passed
        if (rawContent.isEmpty()) {
            if (_strictNicknames.value && rawNickname != null) {
                if (rawNickname.contains('#') && containsZalgoOrForbiddenChars(rawNickname.substringAfter('#'))) {
                    recordBlocked("Zalgo after '#' in nickname", normalizedPubkey)
                    return InspectionResult.Blocked("Zalgo in nickname")
                }
                if (_blockBots.value && BOT_HEX_NICKNAME_REGEX.matches(rawNickname.trim())) {
                    recordBlocked("bot nickname pattern ($rawNickname)", normalizedPubkey)
                    return InspectionResult.Blocked("bot nickname pattern")
                }
            }
            if (_blockLinks.value && rawNickname != null && containsLinkOrUrl(rawNickname)) {
                recordBlocked("link in nickname", normalizedPubkey)
                return InspectionResult.Blocked("link in nickname")
            }
            return InspectionResult.Allowed("", cleanNick)
        }

        val cleanContent = sanitizeText(rawContent, MAX_MESSAGE_LENGTH)
        if (cleanContent.isEmpty()) {
            recordBlocked("empty after sanitization", normalizedPubkey)
            return InspectionResult.Blocked("only forbidden chars")
        }

        // 2. Strict nickname check (must happen BEFORE recording links so a bot nickname doesn't consume a valid link slot)
        if (_strictNicknames.value && rawNickname != null) {
            if (rawNickname.contains('#')) {
                recordBlocked("forbidden '#' in nickname tag", normalizedPubkey)
                return InspectionResult.Blocked("forbidden '#' in nickname")
            }
            if (_blockBots.value && BOT_HEX_NICKNAME_REGEX.matches(rawNickname.trim())) {
                recordBlocked("bot nickname pattern ($rawNickname)", normalizedPubkey)
                return InspectionResult.Blocked("bot nickname pattern")
            }
        }

        val eventSec = createdAtSeconds ?: (nowMs / 1000L)

        // 3. Anti-Link-Spam check (allows normal link sharing, blocks repeated domain/URL spam or multi-link floods)
        if (_blockLinks.value) {
            if (rawNickname != null && containsLinkOrUrl(rawNickname)) {
                recordBlocked("link in nickname", normalizedPubkey)
                return InspectionResult.Blocked("link in nickname")
            }
            val linkKeys = extractNormalizedLinkKeys(cleanContent)
            if (linkKeys.size > MAX_LINKS_PER_MESSAGE) {
                recordBlocked("too many links in message (${linkKeys.size})", normalizedPubkey)
                return InspectionResult.Blocked("link flood")
            }
            for (linkKey in linkKeys.distinct()) {
                val previousSec = recentLinkTimestamps[linkKey]
                if (previousSec != null && kotlin.math.abs(eventSec - previousSec) < LINK_SPAM_WINDOW_SECONDS) {
                    recordBlocked("repeated link spam ($linkKey)", normalizedPubkey)
                    return InspectionResult.Blocked("link spam ($linkKey)")
                }
            }
            for (linkKey in linkKeys.distinct()) {
                recentLinkTimestamps[linkKey] = eventSec
            }
            if (recentLinkTimestamps.size > 500) {
                recentLinkTimestamps.clear()
            }
        }

        // 4. Anti-bot content heuristics
        if (_blockBots.value) {
            // 4a. Line flood / excessive multiline spam
            val lineCount = cleanContent.count { it == '\n' } + 1
            if (lineCount > MAX_MESSAGE_LINES) {
                recordBlocked("excessive lines ($lineCount)", normalizedPubkey)
                return InspectionResult.Blocked("excessive lines")
            }

            // 4b. Long promotional / spam message deduplication across rotating pubkeys
            val fingerprint = contentFingerprint(cleanContent)
            if (fingerprint.length >= MIN_FINGERPRINT_LEN_FOR_DEDUPE) {
                val previousSec = recentLongSpamFingerprints.put(fingerprint, eventSec)
                if (previousSec != null && kotlin.math.abs(eventSec - previousSec) < CONTENT_DEDUPE_WINDOW_SECONDS) {
                    recordBlocked("duplicate long spam content", normalizedPubkey)
                    return InspectionResult.Blocked("duplicate content")
                }
                if (recentLongSpamFingerprints.size > 500) {
                    recentLongSpamFingerprints.clear()
                }
            }
        }

        return InspectionResult.Allowed(cleanContent, cleanNick)
    }

    /**
     * Computes a normalized content fingerprint for long messages so bots rotating pubkeys/nicknames
     * with the same promotional text or URL get caught.
     */
    internal fun contentFingerprint(text: String): String {
        val lower = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFKD)
        val sb = StringBuilder(lower.length)
        for (ch in lower) {
            if (ch in 'a'..'z' || ch in '0'..'9') {
                sb.append(ch)
            }
        }
        return sb.toString().take(120)
    }

    fun resetStateForTesting() {
        recentLongSpamFingerprints.clear()
        recentLinkTimestamps.clear()
        _blockedCount.value = 0
        _shieldEnabled.value = true
        _blockZalgo.value = true
        _blockBots.value = true
        _blockLinks.value = true
        _strictNicknames.value = true
    }
}
