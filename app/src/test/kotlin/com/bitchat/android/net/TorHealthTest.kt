package com.bitchat.android.net

import org.junit.Assert.assertEquals
import org.junit.Test

class TorHealthTest {
    private val ready = ArtiTorManager.TorStatus(
        mode = TorMode.ON, running = true, bootstrapPercent = 100,
        state = ArtiTorManager.TorState.RUNNING
    )

    @Test fun `selected off never advertises an old ready snapshot`() {
        assertEquals(TorHealth.Disabled, ready.health(TorMode.OFF))
        assertEquals(TorHealth.Stopping, ready.copy(state = ArtiTorManager.TorState.STOPPING).health(TorMode.OFF))
    }

    @Test fun `newly selected Tor starts before its manager catches up`() {
        assertEquals(TorHealth.Starting, ArtiTorManager.TorStatus().health(TorMode.ON))
    }

    @Test fun `ready requires running fully bootstrapped Tor`() {
        assertEquals(TorHealth.Ready, ready.health(TorMode.ON))
        assertEquals(TorHealth.Starting, ready.copy(running = false).health(TorMode.ON))
        assertEquals(TorHealth.Starting, ready.copy(bootstrapPercent = 75).health(TorMode.ON))
        assertEquals(TorHealth.Starting, ready.copy(mode = TorMode.OFF).health(TorMode.ON))
        assertEquals(TorHealth.Starting, ready.copy(state = ArtiTorManager.TorState.STARTING).health(TorMode.ON))
    }

    @Test fun `scheduled retry shows reconnecting rather than a terminal failure`() {
        val failure = ready.copy(running = false, state = ArtiTorManager.TorState.ERROR)
        assertEquals(TorHealth.Unavailable, failure.health(TorMode.ON))
        assertEquals(TorHealth.Reconnecting, failure.copy(reconnecting = true).health(TorMode.ON))
        assertEquals(TorHealth.Disabled, failure.copy(reconnecting = true).health(TorMode.OFF))
    }
}
