package com.bitchat.android.ui

import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.GeohashSendState
import com.bitchat.android.services.AppStateStore
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import java.util.Date

class GeohashSendStateTest {
    @Before @After fun clear() = AppStateStore.clear()
    private fun message() = BitchatMessage(
        id = "local-fixture", sender = "synthetic", content = "fixture",
        timestamp = Date(1700000000000), geohashSendState = GeohashSendState.Mining
    )

    @Test
    fun `mining row becomes signed row and confirmation without adding another row`() = runTest {
        val state = ChatState(backgroundScope)
        val manager = MessageManager(state)
        val local = message()
        manager.addChannelMessage("fixture-channel", local)
        val signed = local.copy(id = "signed-fixture", powDifficulty = 16, geohashSendState = GeohashSendState.Sending)
        manager.replaceChannelMessage("fixture-channel", local.id, signed)
        val accepted = signed.copy(geohashSendState = GeohashSendState.RelayAccepted)
        manager.replaceChannelMessage("fixture-channel", signed.id, accepted)
        manager.addChannelMessage("fixture-channel", signed.copy(geohashSendState = null))
        assertEquals(listOf(accepted), state.channelMessages.value["fixture-channel"])
        assertEquals(listOf(accepted), AppStateStore.channelMessages.value["fixture-channel"])
    }

    @Test
    fun `late confirmation cannot resurrect cleared messages`() {
        val local = message()
        AppStateStore.addChannelMessage("fixture-channel", local)
        AppStateStore.clearChannelMessages("fixture-channel")
        AppStateStore.replaceChannelMessage("fixture-channel", local.id, local.copy(geohashSendState = GeohashSendState.RelayAccepted))
        assertTrue(AppStateStore.channelMessages.value["fixture-channel"].orEmpty().isEmpty())
    }

    @Test
    fun `local status changes equality but never changes wire bytes`() {
        val local = message()
        val accepted = local.copy(geohashSendState = GeohashSendState.RelayAccepted)
        assertNotEquals(local, accepted)
        assertArrayEquals(local.toBinaryPayload(), accepted.toBinaryPayload())
        val decoded = BitchatMessage.fromBinaryPayload(requireNotNull(accepted.toBinaryPayload()))
        assertNotNull(decoded)
        assertNull(decoded?.geohashSendState)
    }
}
