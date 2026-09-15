package io.termbridge.feature.terminal

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import io.termbridge.core.terminal.Key
import io.termbridge.core.terminal.Sticky
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The always-visible key row above the system keyboard (architecture §7.4):
 * `Esc Tab Ctrl Alt ← ↓ ↑ → / | - ~ ^C ⋯`. Ctrl and Alt are sticky; `⋯` docks a panel with
 * F1–F12 and navigation keys without dismissing the keyboard.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExtraKeys(
    ctrl: Sticky,
    alt: Sticky,
    onKey: (Key) -> Unit,
    onChar: (Int) -> Unit,
    onCtrl: () -> Unit,
    onAlt: () -> Unit,
    onInterrupt: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer)) {
        AnimatedVisibility(expanded, enter = expandVertically(), exit = shrinkVertically()) {
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val fn = listOf(Key.F1, Key.F2, Key.F3, Key.F4, Key.F5, Key.F6, Key.F7, Key.F8, Key.F9, Key.F10, Key.F11, Key.F12)
                fn.forEachIndexed { i, key -> KeyCap("F${i + 1}", onPress = { onKey(key) }) }
                KeyCap("Home", onPress = { onKey(Key.HOME) })
                KeyCap("End", onPress = { onKey(Key.END) })
                KeyCap("PgUp", repeat = true, onPress = { onKey(Key.PAGE_UP) })
                KeyCap("PgDn", repeat = true, onPress = { onKey(Key.PAGE_DOWN) })
                KeyCap("Ins", onPress = { onKey(Key.INSERT) })
                KeyCap("Del", repeat = true, onPress = { onKey(Key.DELETE) })
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            KeyCap("Esc", onPress = { onKey(Key.ESCAPE) })
            KeyCap("Tab", onPress = { onKey(Key.TAB) })
            KeyCap("Ctrl", sticky = ctrl, onPress = onCtrl)
            KeyCap("Alt", sticky = alt, onPress = onAlt)
            KeyCap("←", repeat = true, onPress = { onKey(Key.LEFT) })
            KeyCap("↓", repeat = true, onPress = { onKey(Key.DOWN) })
            KeyCap("↑", repeat = true, onPress = { onKey(Key.UP) })
            KeyCap("→", repeat = true, onPress = { onKey(Key.RIGHT) })
            for (c in "/|-~") KeyCap(c.toString(), onPress = { onChar(c.code) })
            KeyCap("^C", accent = MaterialTheme.colorScheme.error, onPress = onInterrupt)
            KeyCap("⋯", sticky = if (expanded) Sticky.ONCE else Sticky.OFF, onPress = { expanded = !expanded })
        }
    }
}

/**
 * A key that fires on touch-down (no tap delay) and never takes focus, so the IME stays up.
 * With [repeat], holding it auto-repeats like a hardware key.
 */
@Composable
private fun KeyCap(
    label: String,
    onPress: () -> Unit,
    sticky: Sticky = Sticky.OFF,
    repeat: Boolean = false,
    accent: Color? = null,
) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val press by rememberUpdatedState(onPress)
    var down by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val background = when {
        sticky == Sticky.LOCKED -> colors.primary
        sticky == Sticky.ONCE -> colors.primaryContainer
        down -> colors.outline
        else -> colors.surfaceContainerHighest
    }
    val content = when (sticky) {
        Sticky.LOCKED -> colors.onPrimary
        Sticky.ONCE -> colors.onPrimaryContainer
        Sticky.OFF -> accent ?: colors.onSurface
    }
    Box(
        Modifier
            .height(40.dp)
            .widthIn(min = 46.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .then(if (sticky == Sticky.ONCE) Modifier.border(1.dp, colors.primary, RoundedCornerShape(10.dp)) else Modifier)
            .pointerInput(repeat) {
                awaitEachGesture {
                    awaitFirstDown()
                    down = true
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    press()
                    val repeater = if (repeat) {
                        scope.launch {
                            delay(REPEAT_DELAY_MS)
                            while (true) {
                                press()
                                delay(REPEAT_INTERVAL_MS)
                            }
                        }
                    } else {
                        null
                    }
                    waitForUpOrCancellation()
                    repeater?.cancel()
                    down = false
                }
            }
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = content)
    }
}

private const val REPEAT_DELAY_MS = 400L
private const val REPEAT_INTERVAL_MS = 45L
