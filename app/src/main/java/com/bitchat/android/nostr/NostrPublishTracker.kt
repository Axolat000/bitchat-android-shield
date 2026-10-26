package com.bitchat.android.nostr

/** Local confirmation tracking. Socket enqueue success is never a relay acceptance. */
enum class NostrPublishResult { Accepted, Rejected, Unconfirmed }

internal class NostrPublishTracker(private val capacity: Int = 500) {
    private data class Pending(
        val relays: MutableSet<String>,
        val deadline: Long,
        val complete: (NostrPublishResult) -> Unit
    )
    private val pending = linkedMapOf<String, Pending>()

    fun track(eventId: String, relays: Collection<String>, deadline: Long, complete: (NostrPublishResult) -> Unit) {
        val callbacks = mutableListOf<() -> Unit>()
        synchronized(this) {
            pending.remove(eventId)?.let { callbacks.add { it.complete(NostrPublishResult.Unconfirmed) } }
            if (pending.size >= capacity) {
                val oldest = pending.keys.first()
                pending.remove(oldest)?.let { callbacks.add { it.complete(NostrPublishResult.Unconfirmed) } }
            }
            if (relays.isEmpty()) callbacks.add { complete(NostrPublishResult.Unconfirmed) }
            else pending[eventId] = Pending(relays.toMutableSet(), deadline, complete)
        }
        callbacks.forEach { it() }
    }

    fun acknowledge(eventId: String, relay: String, accepted: Boolean) {
        val completion = synchronized(this) {
            val entry = pending[eventId] ?: return
            if (!entry.relays.remove(relay)) return
            when {
                accepted -> { pending.remove(eventId); entry.complete to NostrPublishResult.Accepted }
                entry.relays.isEmpty() -> { pending.remove(eventId); entry.complete to NostrPublishResult.Rejected }
                else -> null
            }
        }
        completion?.let { (callback, result) -> callback(result) }
    }

    fun expire(now: Long) {
        val expired = synchronized(this) {
            val entries = pending.filterValues { it.deadline <= now }
            entries.keys.forEach(pending::remove)
            entries.values.toList()
        }
        expired.forEach { it.complete(NostrPublishResult.Unconfirmed) }
    }

    fun clear() {
        val entries = synchronized(this) { pending.values.toList().also { pending.clear() } }
        entries.forEach { it.complete(NostrPublishResult.Unconfirmed) }
    }
}
