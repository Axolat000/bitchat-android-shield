package com.bitchat.android.nostr

import android.util.Log
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.security.MessageDigest
import kotlin.random.Random

/**
 * Nostr Proof of Work (PoW) implementation following NIP-13
 * 
 * This implements the Proof of Work system for Nostr events to provide spam deterrence.
 * The difficulty is defined as the number of leading zero bits in the event ID.
 * 
 * Reference: https://github.com/nostr-protocol/nips/blob/master/13.md
 */
object NostrProofOfWork {
    
    private const val TAG = "NostrProofOfWork"
    
    /**
     * Calculate the difficulty (number of leading zero bits) of an event ID
     * @param eventIdHex The hexadecimal event ID
     * @return The number of leading zero bits
     */
    fun calculateDifficulty(eventIdHex: String): Int {
        var count = 0
        
        for (i in eventIdHex.indices) {
            val nibble = eventIdHex[i].toString().toInt(16)
            if (nibble == 0) {
                count += 4
            } else {
                // Count leading zeros in the nibble
                count += when (nibble) {
                    1 -> 3  // 0001
                    2, 3 -> 2  // 001x
                    4, 5, 6, 7 -> 1  // 01xx
                    else -> 0  // 1xxx
                }
                break
            }
        }
        
        return count
    }
    
    /**
     * Validate that an event meets the minimum difficulty requirement
     * @param event The Nostr event to validate
     * @param minimumDifficulty The minimum required difficulty
     * @return true if the event meets the difficulty requirement
     */
    fun validateDifficulty(event: NostrEvent, minimumDifficulty: Int): Boolean {
        if (minimumDifficulty <= 0) return true
        
        // Require explicit nonce tag to recognize PoW per NIP-13 intent
        if (!hasNonce(event)) {
            Log.w(TAG, "Event ${event.id.take(16)}... missing nonce tag; treating as no PoW")
            return false
        }
        
        val actualDifficulty = calculateDifficulty(event.id)
        val committedDifficulty = getCommittedDifficulty(event)
        
        Log.d(TAG, "Validating PoW: actual=$actualDifficulty, required=$minimumDifficulty, committed=$committedDifficulty")
        
        // Check if actual difficulty meets requirement
        if (actualDifficulty < minimumDifficulty) {
            Log.w(TAG, "Event ${event.id.take(16)}... has insufficient difficulty: $actualDifficulty < $minimumDifficulty")
            return false
        }
        
        // If there's a committed difficulty, it should match or exceed the minimum
        if (committedDifficulty != null && committedDifficulty < minimumDifficulty) {
            Log.w(TAG, "Event ${event.id.take(16)}... has committed difficulty $committedDifficulty but achieved $actualDifficulty (possible spam)")
            return false
        }
        
        return true
    }
    
    /**
     * Mine a Nostr event to achieve the target difficulty
     * @param event The event to mine (will be modified with nonce tag)
     * @param targetDifficulty The target difficulty to achieve
     * @param maxIterations Maximum number of iterations before giving up (default: 1,000,000)
     * @return The mined event with nonce tag, or null if mining failed
     */
    suspend fun mineEvent(
        event: NostrEvent,
        targetDifficulty: Int,
        maxIterations: Int = 1_000_000
    ): NostrEvent? = mineEventWithClock(
        event, targetDifficulty, maxIterations, Random.nextLong(0, 1_000_000),
        { (System.currentTimeMillis() / 1000).toInt() }
    )

    internal suspend fun mineEventWithClock(
        event: NostrEvent,
        targetDifficulty: Int,
        maxIterations: Int,
        initialNonce: Long,
        nowSeconds: () -> Int
    ): NostrEvent? = withContext(Dispatchers.Default) {
        if (targetDifficulty <= 0) return@withContext event
        
        Log.d(TAG, "Starting PoW mining for difficulty $targetDifficulty...")
        val startTime = System.currentTimeMillis()
        
        // Keep NIP-01 serialization identical, but serialize invariant fields
        // once. Only the timestamp and decimal nonce change during mining.
        val gson = GsonBuilder().disableHtmlEscaping().create()
        val tags = event.tags.filterNot { it.firstOrNull() == "nonce" }
        val serializedTags = tags.joinToString(",") { gson.toJson(it) }
        val beforeTimestamp = "[0,${gson.toJson(event.pubkey)},"
        val afterTimestamp = ",${event.kind},[$serializedTags${if (tags.isEmpty()) "" else ","}[\"nonce\",\""
        val suffix = "\",\"$targetDifficulty\"]],${gson.toJson(event.content)}]".toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256")
        val coroutineContext = currentCoroutineContext()
        var timestamp = nowSeconds()
        var prefix = "$beforeTimestamp$timestamp$afterTimestamp".toByteArray(Charsets.UTF_8)
        var nonce = initialNonce
        var iterations = 0
        
        while (iterations < maxIterations) {
            coroutineContext.ensureActive()
            val currentTimestamp = nowSeconds()
            if (timestamp != currentTimestamp) {
                timestamp = currentTimestamp
                prefix = "$beforeTimestamp$timestamp$afterTimestamp".toByteArray(Charsets.UTF_8)
            }
            val nonceText = nonce.toString()
            digest.update(prefix)
            digest.update(nonceText.toByteArray(Charsets.UTF_8))
            val hash = digest.digest(suffix)
            val actualDifficulty = calculateHashDifficulty(hash)
            
            if (actualDifficulty >= targetDifficulty) {
                val timeElapsed = System.currentTimeMillis() - startTime
                Log.i(TAG, "✅ PoW mining successful! Difficulty: $actualDifficulty, iterations: $iterations, time: ${timeElapsed}ms")
                
                // Return the event with the computed ID
                val alphabet = "0123456789abcdef"
                val eventId = buildString(hash.size * 2) {
                    for (byte in hash) {
                        val value = byte.toInt() and 0xff
                        append(alphabet[value ushr 4])
                        append(alphabet[value and 15])
                    }
                }
                return@withContext event.copy(
                    id = eventId,
                    createdAt = timestamp,
                    tags = tags + listOf(listOf("nonce", nonceText, targetDifficulty.toString()))
                )
            }
            
            // Increment nonce and try again
            nonce++
            iterations++
            
            // Log progress every 100,000 iterations
            if (iterations % 100_000 == 0) {
                val timeElapsed = System.currentTimeMillis() - startTime
                Log.d(TAG, "PoW mining progress: $iterations iterations, ${timeElapsed}ms elapsed")
            }
        }
        
        val timeElapsed = System.currentTimeMillis() - startTime
        Log.w(TAG, "❌ PoW mining failed after $maxIterations iterations (${timeElapsed}ms)")
        return@withContext null
    }
    
    private fun calculateHashDifficulty(hash: ByteArray): Int {
        var count = 0
        for (byte in hash) {
            val value = byte.toInt() and 0xff
            if (value == 0) count += 8
            else return count + Integer.numberOfLeadingZeros(value) - 24
        }
        return count
    }
    
    /**
     * Get the committed difficulty from an event's nonce tag
     * @param event The event to check
     * @return The committed difficulty, or null if not present
     */
    private fun getCommittedDifficulty(event: NostrEvent): Int? {
        val nonceTag = event.tags.find { tag -> 
            tag.isNotEmpty() && tag[0] == "nonce" && tag.size >= 3 
        }
        
        return nonceTag?.get(2)?.toIntOrNull()
    }
    
    /**
     * Check if an event has a nonce tag (indicating it was mined)
     * @param event The event to check
     * @return true if the event has a nonce tag
     */
    fun hasNonce(event: NostrEvent): Boolean {
        return event.tags.any { tag -> tag.isNotEmpty() && tag[0] == "nonce" }
    }
    
    /**
     * Get the nonce value from an event
     * @param event The event to check
     * @return The nonce value, or null if not present
     */
    fun getNonce(event: NostrEvent): String? {
        val nonceTag = event.tags.find { tag -> 
            tag.isNotEmpty() && tag[0] == "nonce" && tag.size >= 2 
        }
        
        return nonceTag?.get(1)
    }
    
    /**
     * Estimate the computational work required for a given difficulty
     * @param difficulty The target difficulty
     * @return Estimated number of hash operations required
     */
    fun estimateWork(difficulty: Int): Long {
        return if (difficulty <= 0) 1L else 1L shl difficulty
    }
    
    /**
     * Get a human-readable description of the estimated mining time
     * @param difficulty The target difficulty
     * @param hashesPerSecond Estimated hashes per second (default: 100,000)
     * @return Human-readable time estimate
     */
    fun estimateMiningTime(difficulty: Int, hashesPerSecond: Int = 100_000): String {
        val estimatedHashes = estimateWork(difficulty)
        val estimatedSeconds = estimatedHashes / hashesPerSecond
        
        return when {
            estimatedSeconds < 1 -> "< 1 second"
            estimatedSeconds < 60 -> "${estimatedSeconds}s"
            estimatedSeconds < 3600 -> "${estimatedSeconds / 60}m ${estimatedSeconds % 60}s"
            estimatedSeconds < 86400 -> "${estimatedSeconds / 3600}h ${(estimatedSeconds % 3600) / 60}m"
            else -> "${estimatedSeconds / 86400}d ${(estimatedSeconds % 86400) / 3600}h"
        }
    }
}
