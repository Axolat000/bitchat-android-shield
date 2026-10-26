package com.bitchat.android.net

/** Local Tor readiness, not a claim about remote relay reachability. */
enum class TorHealth { Disabled, Starting, Reconnecting, Ready, Stopping, Unavailable }

fun ArtiTorManager.TorStatus.health(selectedMode: TorMode): TorHealth = when {
    selectedMode == TorMode.OFF && state == ArtiTorManager.TorState.STOPPING -> TorHealth.Stopping
    selectedMode == TorMode.OFF -> TorHealth.Disabled
    reconnecting -> TorHealth.Reconnecting
    state == ArtiTorManager.TorState.ERROR -> TorHealth.Unavailable
    mode == TorMode.ON && running && bootstrapPercent >= 100 &&
        state == ArtiTorManager.TorState.RUNNING -> TorHealth.Ready
    else -> TorHealth.Starting
}
