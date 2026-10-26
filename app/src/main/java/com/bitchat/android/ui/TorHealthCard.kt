package com.bitchat.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bitchat.android.R
import com.bitchat.android.net.TorHealth

@Composable
internal fun torHealthLabel(health: TorHealth): String = stringResource(when (health) {
    TorHealth.Disabled -> R.string.tor_health_disabled
    TorHealth.Starting -> R.string.tor_health_starting
    TorHealth.Reconnecting -> R.string.tor_health_reconnecting
    TorHealth.Ready -> R.string.tor_health_ready
    TorHealth.Stopping -> R.string.tor_health_stopping
    TorHealth.Unavailable -> R.string.tor_health_unavailable
})

@Composable
internal fun torHealthColor(health: TorHealth): Color = when (health) {
    TorHealth.Ready -> MaterialTheme.colorScheme.primary
    TorHealth.Unavailable -> MaterialTheme.colorScheme.error
    TorHealth.Disabled -> MaterialTheme.colorScheme.onSurfaceVariant
    else -> MaterialTheme.colorScheme.tertiary
}

@Composable
internal fun TorHealthCard(health: TorHealth, modifier: Modifier = Modifier) {
    val description = when (health) {
        TorHealth.Disabled -> R.string.tor_health_disabled_desc
        TorHealth.Starting -> R.string.tor_health_starting_desc
        TorHealth.Reconnecting -> R.string.tor_health_reconnecting_desc
        TorHealth.Ready -> R.string.tor_health_ready_desc
        TorHealth.Stopping -> R.string.tor_health_stopping_desc
        TorHealth.Unavailable -> R.string.tor_health_unavailable_desc
    }
    Surface(modifier = modifier, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(color = torHealthColor(health), shape = CircleShape, modifier = Modifier.size(10.dp)) {}
                Text(torHealthLabel(health), style = MaterialTheme.typography.titleSmall)
            }
            Text(stringResource(description), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
