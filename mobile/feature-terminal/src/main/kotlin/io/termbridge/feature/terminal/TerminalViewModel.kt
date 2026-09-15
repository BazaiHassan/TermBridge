package io.termbridge.feature.terminal

import android.content.Intent
import android.os.Bundle
import androidx.core.os.bundleOf
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import io.termbridge.core.terminal.Mods
import io.termbridge.core.terminal.Sticky
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import javax.inject.Inject

/** Opens a shell on a paired machine, identified by its agent ID. */
@Serializable
data class TerminalDestination(val agentId: String, val name: String) {
    /** For intents that open this terminal, e.g. the session notification. */
    fun toExtras(): Bundle = bundleOf(EXTRA_AGENT_ID to agentId, EXTRA_NAME to name)

    companion object {
        private const val EXTRA_AGENT_ID = "io.termbridge.extra.AGENT_ID"
        private const val EXTRA_NAME = "io.termbridge.extra.NAME"

        /** The terminal an intent asks to open, if any. */
        fun from(intent: Intent?): TerminalDestination? {
            val agentId = intent?.getStringExtra(EXTRA_AGENT_ID) ?: return null
            return TerminalDestination(agentId, intent.getStringExtra(EXTRA_NAME).orEmpty())
        }
    }
}

/** One shell's tab. */
data class TabUi(val key: Int, val label: String, val open: Boolean)

data class TerminalUiState(
    val title: String,
    val endpoint: String = "",
    val status: TerminalStatus = TerminalStatus.Connecting,
    val rttMillis: Long? = null,
    val ctrl: Sticky = Sticky.OFF,
    val alt: Sticky = Sticky.OFF,
    val tabs: List<TabUi> = emptyList(),
    val selectedKey: Int = 0,
) {
    val canAddShell: Boolean get() = tabs.size < MAX_SHELLS

    companion object {
        /** The agent's default limit (`--max-sessions`); it refuses more with too_many_sessions. */
        const val MAX_SHELLS = 8
    }
}

private data class StickyKeys(val ctrl: Sticky = Sticky.OFF, val alt: Sticky = Sticky.OFF)

/**
 * The terminal screen's view of a [MachineSession]. The session, its shells and their emulators
 * belong to [TerminalSessions]; leaving the screen leaves them running.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TerminalViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    sessions: TerminalSessions,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<TerminalDestination>()
    private val session = sessions.open(route.agentId, route.name)
    private val sticky = MutableStateFlow(StickyKeys())

    /** The emulator of the selected tab. */
    val emulator get() = session.selected.value.emulator

    var renderRequest: (() -> Unit)? by session::renderRequest

    var fontSizeSp: Float by session::fontSizeSp

    private val tabs = session.shells.flatMapLatest { shells ->
        if (shells.isEmpty()) flowOf(emptyList()) else combine(shells.map { s -> s.state.map { s to it } }) { it.toList() }
    }

    val ui: StateFlow<TerminalUiState> = combine(session.state, tabs, session.selected, sticky) { s, tabs, selected, keys ->
        val shell = tabs.firstOrNull { it.first === selected }?.second ?: selected.state.value
        TerminalUiState(
            title = shell.title.ifBlank { route.name },
            endpoint = s.endpoint,
            status = display(s.status, shell.phase),
            rttMillis = s.rttMillis,
            ctrl = keys.ctrl,
            alt = keys.alt,
            tabs = tabs.mapIndexed { i, (tab, st) -> TabUi(tab.key, st.title.ifBlank { "shell ${i + 1}" }.take(TAB_LABEL_MAX), st.isOpen) },
            selectedKey = selected.key,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, TerminalUiState(title = route.name))

    /** New shell in this tab after an exit, or retry now while reconnecting. */
    fun restart() = session.start()

    /** Ends every shell on this computer. */
    fun disconnect() = session.disconnect()

    fun newShell() = session.newShell()

    fun selectShell(key: Int) {
        session.shells.value.firstOrNull { it.key == key }?.let(session::select)
    }

    /** Closes the selected shell. False when it was the last one and the session ended. */
    fun closeShell(): Boolean = session.closeShell(session.selected.value)

    fun onViewportChanged(cols: Int, rows: Int) = session.onViewportChanged(cols, rows)

    fun sendInput(bytes: ByteArray) = session.sendInput(bytes)

    fun toggleCtrl() = sticky.update { it.copy(ctrl = it.ctrl.tapped()) }
    fun toggleAlt() = sticky.update { it.copy(alt = it.alt.tapped()) }

    fun stickyModifiers(): Int = sticky.value.let {
        (if (it.ctrl.active) Mods.CTRL else 0) or (if (it.alt.active) Mods.ALT else 0)
    }

    fun consumeSticky() = sticky.update { StickyKeys(it.ctrl.consumed(), it.alt.consumed()) }

    private companion object {
        const val TAB_LABEL_MAX = 24
    }
}

/** What the screen shows: the selected shell's fate wins over the connection's state. */
internal fun display(connection: TerminalStatus, shell: ShellPhase): TerminalStatus = when {
    shell is ShellPhase.Exited -> TerminalStatus.Exited(shell.code)
    shell is ShellPhase.Failed -> TerminalStatus.ShellFailed(shell.reason)
    connection is TerminalStatus.Live && shell == ShellPhase.Starting -> TerminalStatus.Connecting
    else -> connection
}
