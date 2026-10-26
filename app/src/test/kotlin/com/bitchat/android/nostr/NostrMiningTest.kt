package com.bitchat.android.nostr

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.system.measureNanoTime

@RunWith(RobolectricTestRunner::class)
class NostrMiningTest {
    private val timestamp = 1700000000
    private fun event(tags: List<List<String>> = listOf(listOf("g", "000000")), content: String = "synthetic") =
        NostrEvent(pubkey = "ab".repeat(32), createdAt = timestamp, kind = 20000, tags = tags, content = content)

    @Test
    fun `16 bit golden vector preserves canonical ID and first successful nonce`() = runBlocking {
        val mined = requireNotNull(NostrProofOfWork.mineEventWithClock(event(), 16, 25535, 0) { timestamp })
        assertEquals("25534", NostrProofOfWork.getNonce(mined))
        assertEquals("0000d8397f0c0968f338e89cd2d2b8c31ba90d90797dfcd4a7d81a7be36e83c0", mined.id)
        assertEquals(mined.computeEventIdHex(), mined.id)
        assertTrue(NostrProofOfWork.validateDifficulty(mined, 16))
    }

    @Test
    fun `18 bit search matches the original canonical search`() = runBlocking {
        val original = event()
        var reference: NostrEvent? = null
        val referenceNanos = measureNanoTime {
            for (nonce in 0..31815) {
                val candidate = original.copy(tags = original.tags + listOf(listOf("nonce", nonce.toString(), "18")))
                val id = candidate.computeEventIdHex()
                if (NostrProofOfWork.calculateDifficulty(id) >= 18) {
                    reference = candidate.copy(id = id)
                    break
                }
            }
        }
        var optimized: NostrEvent? = null
        val optimizedNanos = measureNanoTime {
            optimized = NostrProofOfWork.mineEventWithClock(original, 18, 31816, 0) { timestamp }
        }
        assertNotNull(optimized)
        assertEquals(reference, optimized)
        assertEquals("00002fbe3b904ca803a4fabef013372c3d5d5bd610a80977fc0f6ce444346e8b", optimized?.id)
        println("Synthetic 18-bit search: reference_ns=$referenceNanos optimized_ns=$optimizedNanos")
    }

    @Test
    fun `escaping empty tags and replaced nonce remain canonically identical`() = runBlocking {
        val fixtures = listOf(
            event(emptyList(), "quotes \" slash \\ newline\n tab\t <>& é 🛡 \u2028\u2029"),
            event(listOf(emptyList(), listOf("synthetic", "a\"b", "<>&"), listOf("nonce", "old", "3")))
        )
        for (fixture in fixtures) {
            val mined = requireNotNull(NostrProofOfWork.mineEventWithClock(fixture, 6, 10000, 0) { timestamp })
            assertEquals(mined.computeEventIdHex(), mined.id)
            assertEquals(1, mined.tags.count { it.firstOrNull() == "nonce" })
            assertEquals(fixture.content, mined.content)
            assertTrue(NostrProofOfWork.validateDifficulty(mined, 6))
        }
    }

    @Test
    fun `clock refresh uses the timestamp that was actually hashed`() = runBlocking {
        var calls = 0
        val mined = requireNotNull(NostrProofOfWork.mineEventWithClock(event(), 16, 25535, 0) {
            if (calls++ == 0) timestamp - 1 else timestamp
        })
        assertEquals(timestamp, mined.createdAt)
        assertEquals("25534", NostrProofOfWork.getNonce(mined))
        assertEquals(mined.computeEventIdHex(), mined.id)
    }

    @Test
    fun `bounded work still fails closed`() = runBlocking {
        assertNull(NostrProofOfWork.mineEventWithClock(event(), 16, 0, 0) { timestamp })
        assertNull(NostrProofOfWork.mineEventWithClock(event(), 16, 25534, 0) { timestamp })
        assertEquals(event(), NostrProofOfWork.mineEventWithClock(event(), 0, 0, 0) { timestamp })
    }
}
