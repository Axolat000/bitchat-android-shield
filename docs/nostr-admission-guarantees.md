# Nostr admission and outgoing proof-of-work guarantees

Public Nostr events keep their existing NIP-01/NIP-13 wire format. The rules below are local admission and lifecycle rules, not new protocol fields.

- An event must pass ID/content consistency and Schnorr signature verification before its ID can enter the relay manager's global deduplication cache. A forged copy must never suppress a later valid copy with the same ID.
- Shield serializes inspection and commits content/domain admission slots only after every rule accepts the event. A rejected message does not reserve domains. Link repetition is keyed by normalized hostname, excluding userinfo and port, with IDN and the existing www alias normalization.
- A requested outgoing proof of work is mandatory. Exhausting the mining budget returns an explicit failure instead of signing/publishing the unmined event. Mining checks coroutine cancellation. A local pending row appears while mining; only successful signing replaces its temporary ID with the canonical event ID and shows the achieved work. Failure marks the same row as failed and never publishes unmined work.
- Mining serializes invariant NIP-01 fields once, reuses a SHA-256 digest, checks difficulty on hash bytes, and converts only the winning hash to hexadecimal. Timestamp refreshes rebuild the prefix; the returned event contains exactly that timestamp and the winning nonce. NostrMiningTest covers a fixed 16-bit golden vector, JSON escaping, empty tags, nonce replacement, clock advancement and exhausted budgets. Requested difficulty and iteration budgets are unchanged.
- If proof is explicitly disabled or its target is zero, ordinary signed messages remain supported.
- Tor retries keep a SOCKS route selected while starting/restarting; they do not introduce a direct fallback. The coroutine executing a restart must not cancel itself while stopping monitor jobs. Each start constructs a proxy using the selected SOCKS port and the corresponding DNS port. Old-proxy log callbacks cannot modify the replacement's state.
- Native SOCKS-functional readiness is accepted during startup and is not overwritten when start returns. The bundled native implementation emits this signal after Tor bootstrap and listener binding; readiness does not depend on its later synthetic guard log. Relay sockets wait for the selected route for up to 60 seconds and then use the existing retry policy, without a direct fallback. Cancellation stops the pending connection attempt.

Regression coverage: BitchatShieldTest, NostrEventDeduplicatorTest, OutgoingPoWTest and ArtiTorManagerRecoveryTest. Network peers and test identities are synthetic; tests do not contact public relays or inspect device locations. Physical-device Mesh Lab validation remains required for messaging/transport changes before release.

## Local publication status

Geohash messages display local states for mining, submitting, accepted by a relay,
failed, or unconfirmed. These states are not serialized in Bitchat binary payloads
or Nostr events and do not claim delivery to a person or a read receipt.

Acceptance requires a positive Nostr OK response for the exact event ID from one
of its target relays. Enqueuing a WebSocket frame is insufficient. One rejection
does not override another target's acceptance; rejection by every target reports
failure. After 60 seconds without a conclusive response, the local state becomes
unconfirmed, rather than accepted or failed. The existing transport queue is
unchanged. Confirmation tracking is bounded to 500 events and cleared on panic.
Clearing a timeline cannot be undone by a late callback. Replacing the temporary
row keeps one timeline entry and reserves the canonical ID against relay echoes.

NostrPublishTrackerTest and GeohashSendStateTest cover unrelated or duplicate
responses, partial and total rejection, acceptance, timeout, eviction, local
replacement, clearing and identical wire bytes regardless of local status.

## Tor health display

Settings observe the selected Tor preference and runtime status directly. The
health card distinguishes disabled, starting, reconnecting, ready, stopping and
unavailable. A scheduled retry remains visibly reconnecting until bootstrap
finishes; disabling Tor or exhausting retries clears that flag. Ready requires
Tor mode on, a running instance, full bootstrap and the running state. It does
not assert that an exit circuit or a remote relay is reachable. Raw Tor log
lines are not shown in the health card. TorHealthTest and recovery tests use
synthetic state snapshots and mocked native proxies without network probes.
