package com.bitchat.android.nostr

import org.junit.Assert.*
import org.junit.Test

class NostrPublishTrackerTest {
    @Test
    fun `registration is not acceptance and unrelated confirmations are ignored`() {
        val results = mutableListOf<NostrPublishResult>()
        val tracker = NostrPublishTracker()
        tracker.track("fixture", listOf("relay-a"), 100, results::add)
        tracker.acknowledge("other", "relay-a", true)
        tracker.acknowledge("fixture", "other", true)
        assertTrue(results.isEmpty())
        tracker.acknowledge("fixture", "relay-a", true)
        tracker.acknowledge("fixture", "relay-a", true)
        tracker.expire(101)
        assertEquals(listOf(NostrPublishResult.Accepted), results)
    }

    @Test
    fun `a rejection cannot override another relay acceptance`() {
        val results = mutableListOf<NostrPublishResult>()
        val tracker = NostrPublishTracker()
        tracker.track("fixture", listOf("relay-a", "relay-b"), 100, results::add)
        tracker.acknowledge("fixture", "relay-a", false)
        tracker.acknowledge("fixture", "relay-a", false)
        assertTrue(results.isEmpty())
        tracker.acknowledge("fixture", "relay-b", true)
        tracker.clear()
        assertEquals(listOf(NostrPublishResult.Accepted), results)
    }

    @Test
    fun `only rejection by every target reports failure`() {
        val results = mutableListOf<NostrPublishResult>()
        val tracker = NostrPublishTracker()
        tracker.track("fixture", listOf("relay-a", "relay-b"), 100, results::add)
        tracker.acknowledge("fixture", "relay-a", false)
        assertTrue(results.isEmpty())
        tracker.acknowledge("fixture", "relay-b", false)
        assertEquals(listOf(NostrPublishResult.Rejected), results)
    }

    @Test
    fun `timeout is unconfirmed rather than accepted or rejected`() {
        val results = mutableListOf<NostrPublishResult>()
        val tracker = NostrPublishTracker()
        tracker.track("fixture", listOf("relay-a"), 100, results::add)
        tracker.expire(99)
        assertTrue(results.isEmpty())
        tracker.expire(100)
        tracker.acknowledge("fixture", "relay-a", true)
        assertEquals(listOf(NostrPublishResult.Unconfirmed), results)
    }

    @Test
    fun `eviction clear empty targets and replacement complete pending observers`() {
        val results = mutableListOf<NostrPublishResult>()
        val tracker = NostrPublishTracker(1)
        tracker.track("first", listOf("relay-a"), 100, results::add)
        tracker.track("second", listOf("relay-a"), 100, results::add)
        tracker.track("second", listOf("relay-a"), 100, results::add)
        tracker.clear()
        tracker.track("empty", emptyList(), 100, results::add)
        assertEquals(List(4) { NostrPublishResult.Unconfirmed }, results)
    }
}
