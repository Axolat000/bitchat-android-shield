package com.bitchat.android.net

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.nostr.NostrRelayManager
import info.guardianproject.arti.ArtiProxy
import info.guardianproject.arti.ArtiLogListener
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.robolectric.RobolectricTestRunner
import java.net.BindException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ArtiTorManagerRecoveryTest {
    private val application: Application = ApplicationProvider.getApplicationContext()

    @Before
    @After
    fun disconnectRelays() { NostrRelayManager.shared.disconnect() }

    @Test
    fun `readiness delivered during start survives return and stops watchdog`() = runTest {
        val proxy = mock<ArtiProxy>()
        lateinit var listener: ArtiLogListener
        doAnswer {
            listener.onLogLine("Sufficiently bootstrapped; system SOCKS now functional")
            null
        }.`when`(proxy).start()
        val manager = ArtiTorManager(backgroundScope, { _, _, callback -> listener = callback; proxy }, 1, 0, 1000, { testScheduler.currentTime })
        backgroundScope.launch { manager.applyMode(application, TorMode.ON) }
        advanceTimeBy(3000); runCurrent()
        assertTrue(manager.isProxyEnabled())
        assertFalse(manager.statusFlow.value.reconnecting)
        assertTrue(manager.awaitSelectedRoute(1))
        verify(proxy, times(1)).start()
    }

    @Test
    fun `selected Tor route waits for functional SOCKS without requiring guard log`() = runTest {
        val proxy = mock<ArtiProxy>()
        lateinit var listener: ArtiLogListener
        val manager = ArtiTorManager(backgroundScope, { _, _, callback -> listener = callback; proxy }, 1, 0, 100000, { testScheduler.currentTime })
        backgroundScope.launch { manager.applyMode(application, TorMode.ON) }
        runCurrent()
        assertFalse(manager.isProxyEnabled())
        assertNotNull(manager.currentSocksAddress())
        var ready = false
        backgroundScope.launch { ready = manager.awaitSelectedRoute(10000) }
        runCurrent()
        assertFalse(ready)
        listener.onLogLine("Sufficiently bootstrapped; system SOCKS now functional")
        runCurrent()
        assertTrue(ready)
        assertEquals(100, manager.statusFlow.value.bootstrapPercent)
    }

    @Test
    fun `transient startup failure retries without cancelling its own coroutine`() = runTest {
        val first = mock<ArtiProxy>()
        val second = mock<ArtiProxy>()
        doThrow(IllegalStateException("synthetic failure")).`when`(first).start()
        var builds = 0
        val manager = ArtiTorManager(backgroundScope, { _, _, _ -> if (builds++ == 0) first else second }, 1, 0, 100000, { testScheduler.currentTime })
        backgroundScope.launch { manager.applyMode(application, TorMode.ON) }
        advanceTimeBy(3000); runCurrent()
        verify(first).start()
        verify(second).start()
        assertTrue(manager.statusFlow.value.running)
        assertTrue(manager.statusFlow.value.reconnecting)
        assertNotNull(manager.currentSocksAddress())
    }

    @Test
    fun `bind retry constructs the replacement proxy at the newly advertised port`() = runTest {
        val first = mock<ArtiProxy>()
        val second = mock<ArtiProxy>()
        doThrow(BindException("address already in use")).`when`(first).start()
        val ports = mutableListOf<Int>()
        val manager = ArtiTorManager(backgroundScope, { _, port, _ -> ports.add(port); if (ports.size == 1) first else second }, 1, 0, 100000, { testScheduler.currentTime })
        backgroundScope.launch { manager.applyMode(application, TorMode.ON) }
        advanceTimeBy(1000); runCurrent()
        assertEquals(2, ports.size)
        assertEquals(ports.first() + 1, ports.last())
        assertEquals(ports.last(), manager.currentSocksAddress()?.port)
        verify(second).start()
    }

    @Test
    fun `bootstrap watchdog can restart without cancelling itself`() = runTest {
        val proxy = mock<ArtiProxy>()
        val manager = ArtiTorManager(backgroundScope, { _, _, _ -> proxy }, 1, 0, 1000, { testScheduler.currentTime })
        backgroundScope.launch { manager.applyMode(application, TorMode.ON) }
        advanceTimeBy(1800); runCurrent()
        verify(proxy, times(2)).start()
        assertTrue(manager.statusFlow.value.running)
    }

    @Test
    fun `disabling Tor cancels a scheduled retry`() = runTest {
        val proxy = mock<ArtiProxy>()
        doThrow(IllegalStateException("synthetic failure")).`when`(proxy).start()
        val manager = ArtiTorManager(backgroundScope, { _, _, _ -> proxy }, 1, 0, 100000, { testScheduler.currentTime })
        backgroundScope.launch { manager.applyMode(application, TorMode.ON) }
        advanceTimeBy(300); runCurrent()
        backgroundScope.launch { manager.applyMode(application, TorMode.OFF) }
        advanceTimeBy(4000); runCurrent()
        verify(proxy).start()
        assertNull(manager.currentSocksAddress())
        assertFalse(manager.statusFlow.value.reconnecting)
    }
}
