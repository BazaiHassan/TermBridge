package io.termbridge.feature.terminal

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import javax.inject.Inject

@Serializable
data object TerminalSettingsDestination

@HiltViewModel
class TerminalSettingsViewModel @Inject constructor(private val store: TerminalPrefsStore) : ViewModel() {
    /** Null while loading. */
    val prefs: StateFlow<TerminalPrefs?> = store.prefs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setFontSize(sp: Float) {
        viewModelScope.launch { store.setFontSize(sp) }
    }

    fun setPalette(name: String) {
        viewModelScope.launch { store.setPalette(name) }
    }

    fun setKeepScreenOn(on: Boolean) {
        viewModelScope.launch { store.setKeepScreenOn(on) }
    }
}

fun NavGraphBuilder.terminalSettingsScreen(onBack: () -> Unit) {
    composable<TerminalSettingsDestination> {
        val vm: TerminalSettingsViewModel = hiltViewModel()
        val prefs by vm.prefs.collectAsStateWithLifecycle()
        prefs?.let { TerminalSettingsScreen(it, onBack, vm::setFontSize, vm::setPalette, vm::setKeepScreenOn) }
    }
}

private val mono = FontFamily(Font(io.termbridge.core.ui.R.font.jetbrains_mono_regular))

@Composable
private fun TerminalSettingsScreen(
    prefs: TerminalPrefs,
    onBack: () -> Unit,
    onFontSize: (Float) -> Unit,
    onPalette: (String) -> Unit,
    onKeepScreenOn: (Boolean) -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Text("Terminal settings", style = MaterialTheme.typography.titleMedium)
        }
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            var size by remember(prefs.fontSizeSp) { mutableFloatStateOf(prefs.fontSizeSp) }
            Section("Text size", "${size.toInt()} sp · pinching the terminal changes it too") {
                Slider(
                    value = size,
                    onValueChange = { size = it },
                    onValueChangeFinished = { onFontSize(size) },
                    valueRange = TerminalPrefsStore.MIN_FONT_SP..TerminalPrefsStore.MAX_FONT_SP,
                    steps = (TerminalPrefsStore.MAX_FONT_SP - TerminalPrefsStore.MIN_FONT_SP).toInt() - 1,
                )
                Preview(TerminalPalette.named(prefs.palette), size)
            }
            Section("Theme", null) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf(TerminalPalette.NIGHT to "Night", TerminalPalette.DAY to "Day").forEach { (name, label) ->
                        ThemeCard(
                            label = label,
                            palette = TerminalPalette.named(name),
                            selected = prefs.palette == name,
                            onClick = { onPalette(name) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Keep the screen on", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "While a terminal is open",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = prefs.keepScreenOn, onCheckedChange = onKeepScreenOn)
            }
        }
    }
}

@Composable
private fun Section(title: String, detail: String?, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        content()
    }
}

/** Two lines as the terminal would draw them, at [sizeSp]. */
@Composable
private fun Preview(palette: TerminalPalette, sizeSp: Float) {
    val text = buildAnnotatedString {
        withStyle(SpanStyle(color = Color(palette.indexed(2)))) { append("you@laptop") }
        append(":")
        withStyle(SpanStyle(color = Color(palette.indexed(4)))) { append("~/code") }
        append("$ git status\n")
        withStyle(SpanStyle(color = Color(palette.indexed(1)))) { append("\tmodified: main.go") }
    }
    Text(
        text,
        color = Color(palette.foreground),
        fontFamily = mono,
        fontSize = sizeSp.sp,
        lineHeight = (sizeSp * 1.3f).sp,
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(palette.background), MaterialTheme.shapes.medium)
            .padding(14.dp),
    )
}

@Composable
private fun ThemeCard(label: String, palette: TerminalPalette, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = Color(palette.background),
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = modifier,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                (1..6).forEach { i ->
                    Spacer(Modifier.height(10.dp).weight(1f).background(Color(palette.indexed(i)), MaterialTheme.shapes.extraSmall))
                }
            }
            Text(label, color = Color(palette.foreground), fontFamily = mono, style = MaterialTheme.typography.labelLarge)
        }
    }
}
