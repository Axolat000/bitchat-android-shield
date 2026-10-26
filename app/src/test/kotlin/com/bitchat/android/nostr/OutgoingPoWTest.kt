package com.bitchat.android.nostr

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OutgoingPoWTest {
    private val identity = NostrIdentity.fromPrivateKey("0".repeat(63) + "1")

    @After
    fun reset() { PoWPreferenceManager.resetToDefaults() }

    @Test
    fun `exhausted mining budget fails without returning an unsigned-work message`() {
        PoWPreferenceManager.setSendPowEnabled(true)
        PoWPreferenceManager.setSendPowDifficulty(32)
        val error = assertThrows(PoWMiningException::class.java) {
            runBlocking { NostrProtocol.createEphemeralGeohashEvent("synthetic", "000000", identity, maxMiningIterations = 0) }
        }
        assertEquals(32, error.difficulty)
        assertFalse(PoWPreferenceManager.isMining())
    }

    @Test
    fun `explicitly disabled sending proof still returns a valid signed event`() = runBlocking {
        PoWPreferenceManager.setSendPowEnabled(false)
        val event = NostrProtocol.createEphemeralGeohashEvent("synthetic", "000000", identity, maxMiningIterations = 0)
        assertTrue(event.isValidSignature())
        assertFalse(NostrProofOfWork.hasNonce(event))
        assertFalse(PoWPreferenceManager.isMining())
    }
}
