package io.termbridge.feature.devices

import android.text.format.DateUtils
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import io.termbridge.core.crypto.PairedMachine
import io.termbridge.core.ui.components.Pill
import io.termbridge.core.ui.components.StatusDot
import io.termbridge.core.ui.components.Wordmark
import io.termbridge.core.ui.theme.TbPalette
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Serializable
data object DevicesDestination

/** [live]: agent IDs with a shell open right now. */
fun NavGraphBuilder.devicesScreen(live: Flow<Set<String>>, onOpen: (PairedMachine) -> Unit, onPair: () -> Unit) {
    composable<DevicesDestination> {
        val vm: DevicesViewModel = hiltViewModel()
        val machines by vm.machines.collectAsStateWithLifecycle()
        val liveIds by live.collectAsStateWithLifecycle(emptySet())
        DevicesScreen(machines = machines, live = liveIds, onOpen = onOpen, onPair = onPair, onForget = vm::forget)
    }
}

@Composable
fun DevicesScreen(
    machines: List<PairedMachine>?,
    live: Set<String> = emptySet(),
    onOpen: (PairedMachine) -> Unit,
    onPair: () -> Unit,
    onForget: (PairedMachine) -> Unit,
) {
    var forgetting by remember { mutableStateOf<PairedMachine?>(null) }
    Scaffold(
        floatingActionButton = {
            if (!machines.isNullOrEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = onPair,
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("Pair computer") },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                )
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(Modifier.statusBarsPadding()) {
                    Wordmark()
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "Your computers, one tap away.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Pill(
                            "end-to-end encrypted",
                            color = MaterialTheme.colorScheme.primary,
                            leading = { Icon(Icons.Filled.Lock, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.primary) },
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
            when {
                machines == null -> Unit
                machines.isEmpty() -> item { EmptyState(onPair) }
                else -> items(machines, key = { it.agentId }) { m ->
                    MachineCard(m, live = m.agentId in live, onClick = { onOpen(m) }, onForget = { forgetting = m })
                }
            }
        }
    }
    forgetting?.let { m ->
        AlertDialog(
            onDismissRequest = { forgetting = null },
            title = { Text("Forget ${m.name}?") },
            text = {
                Text("This phone stops trusting it and you'll need to pair again. To also revoke this phone on the computer, run  termbridge revoke  there.")
            },
            confirmButton = {
                TextButton(onClick = {
                    onForget(m)
                    forgetting = null
                }) { Text("Forget", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { forgetting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun MachineCard(machine: PairedMachine, live: Boolean, onClick: () -> Unit, onForget: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(machine.name.take(1).uppercase().ifEmpty { ">" }, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(machine.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                    if (live) Pill("shell open", color = TbPalette.Mint, leading = { StatusDot(TbPalette.Mint, pulsing = true, size = 6.dp) })
                }
                Text(
                    machine.addresses.firstOrNull().orEmpty(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val last = maxOf(machine.lastConnectedAt, machine.pairedAt)
                Text(
                    (if (machine.lastConnectedAt > 0) "Last connected " else "Paired ") + DateUtils.getRelativeTimeSpanString(last),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onForget) {
                Icon(Icons.Filled.Delete, contentDescription = "Forget ${machine.name}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun EmptyState(onPair: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(88.dp).clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Text(">_", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(20.dp))
        Text("Pair your first computer", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(16.dp))
        Step(1, "Open TermBridge Desktop on the computer, or run  termbridge pair  in its terminal.")
        Step(2, "Tap Scan QR code and point the camera at the code.")
        Step(3, "Check the fingerprint matches — that's it, you're in.")
        Spacer(Modifier.height(24.dp))
        Button(onClick = onPair, modifier = Modifier.fillMaxWidth().height(52.dp), shape = MaterialTheme.shapes.small) {
            Text("Scan QR code", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun Step(n: Int, text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(24.dp).clip(MaterialTheme.shapes.extraLarge).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text("$n", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Start,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}
