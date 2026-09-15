package io.termbridge.feature.terminal

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import io.termbridge.core.ui.components.Pill
import io.termbridge.core.ui.components.StatusDot
import io.termbridge.core.ui.theme.TbPalette
import io.termbridge.core.ui.theme.TermBridgeTheme

fun NavGraphBuilder.terminalScreen(onBack: () -> Unit) {
    composable<TerminalDestination> {
        val vm: TerminalViewModel = hiltViewModel()
        // The terminal is always dark, whatever the system theme.
        TermBridgeTheme(darkTheme = true) { TerminalScreen(vm, onBack) }
    }
}

@Composable
private fun TerminalScreen(vm: TerminalViewModel, onBack: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var terminal by remember { mutableStateOf<TerminalView?>(null) }
    val clipboard = LocalClipboardManager.current
    DisposableEffect(vm) { onDispose { vm.renderRequest = null } }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(TerminalPalette.Night.background))
            .windowInsetsPadding(WindowInsets.safeDrawing), // includes the IME: the grid shrinks → RESIZE
    ) {
        TopBar(
            ui = ui,
            onBack = onBack,
            onPaste = { clipboard.getText()?.text?.let { terminal?.paste(it) } },
            onDisconnect = {
                vm.disconnect()
                onBack()
            },
        )
        Box(Modifier.fillMaxWidth().height(2.dp)) {
            if (ui.status == TerminalStatus.Connecting || ui.status is TerminalStatus.Reconnecting) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                factory = { context ->
                    TerminalView(context).apply {
                        fontSizeSp = vm.fontSizeSp
                        onViewportChanged = vm::onViewportChanged
                        onInput = vm::sendInput
                        stickyModifiers = vm::stickyModifiers
                        onStickyConsumed = vm::consumeSticky
                        onFontSizeChanged = { vm.fontSizeSp = it }
                        emulator = vm.emulator
                        vm.renderRequest = ::requestRender
                        terminal = this
                        post { showKeyboard() }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            StatusCard(
                status = ui.status,
                onRestart = vm::restart,
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
            )
        }
        ExtraKeys(
            ctrl = ui.ctrl,
            alt = ui.alt,
            onKey = { terminal?.sendKey(it) },
            onChar = { terminal?.sendChar(it) },
            onCtrl = vm::toggleCtrl,
            onAlt = vm::toggleAlt,
            onInterrupt = { vm.sendInput(byteArrayOf(0x03)) },
        )
    }
}

@Composable
private fun TopBar(ui: TerminalUiState, onBack: () -> Unit, onPaste: () -> Unit, onDisconnect: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().height(56.dp).background(MaterialTheme.colorScheme.surfaceContainerLow).padding(end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        Column(Modifier.weight(1f)) {
            Text(ui.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(ui.endpoint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        StatusPill(ui)
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Paste") }, onClick = { menu = false; onPaste() })
                DropdownMenuItem(text = { Text("End session") }, onClick = { menu = false; onDisconnect() })
            }
        }
    }
}

@Composable
private fun StatusPill(ui: TerminalUiState) {
    val (color, label, live) = when (val s = ui.status) {
        TerminalStatus.Connecting -> Triple(TbPalette.Amber, "connecting", true)
        is TerminalStatus.Live -> Triple(TbPalette.Mint, ui.rttMillis?.let { "$it ms" } ?: "live", true)
        is TerminalStatus.Reconnecting -> Triple(TbPalette.Amber, "reconnecting", true)
        is TerminalStatus.Exited -> Triple(MaterialTheme.colorScheme.onSurfaceVariant, "exit ${s.exitCode}", false)
        is TerminalStatus.Lost -> Triple(TbPalette.Coral, "offline", false)
    }
    Pill(label, leading = { StatusDot(color, pulsing = live, size = 7.dp) })
}

@Composable
private fun StatusCard(status: TerminalStatus, onRestart: () -> Unit, modifier: Modifier = Modifier) {
    val content: Triple<String, String, String>? = when (status) {
        is TerminalStatus.Exited -> Triple("Shell exited", "Exit code ${status.exitCode}", "New session")
        is TerminalStatus.Reconnecting -> Triple("Reconnecting… your shell is still running", status.reason, "Retry now")
        is TerminalStatus.Lost -> Triple("Connection lost", status.reason, "Reconnect")
        else -> null
    }
    AnimatedVisibility(
        visible = content != null,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
        modifier = modifier,
    ) {
        val (title, detail, action) = content ?: return@AnimatedVisibility
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            shadowElevation = 12.dp,
        ) {
            Row(Modifier.padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.width(16.dp))
                Button(onClick = onRestart, shape = MaterialTheme.shapes.small) { Text(action) }
            }
        }
    }
}
