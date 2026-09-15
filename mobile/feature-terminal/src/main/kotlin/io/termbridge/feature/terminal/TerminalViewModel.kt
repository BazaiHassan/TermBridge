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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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

data class TerminalUiState(
    val title: String,
    val endpoint: String = "",
    val status: TerminalStatus = TerminalStatus.Connecting,
    val rttMillis: Long? = null,
    val ctrl: Sticky = Sticky.OFF,
    val alt: Sticky = Sticky.OFF,
)

private data class StickyKeys(val ctrl: Sticky = Sticky.OFF, val alt: Sticky = Sticky.OFF)

/**
 * The terminal screen's view of a [MachineSession]. The session, its shell and its emulator
 * belong to [TerminalSessions]; leaving the screen leaves the shell running.
 */
@HiltViewModel
class TerminalViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    sessions: TerminalSessions,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<TerminalDestination>()
    private val session = sessions.open(route.agentId, route.name)
    private val sticky = MutableStateFlow(StickyKeys())

    val emulator get() = session.emulator

    var renderRequest: (() -> Unit)? by session::renderRequest

    var fontSizeSp: Float by session::fontSizeSp

    val ui: StateFlow<TerminalUiState> = combine(session.state, sticky) { s, keys ->
        TerminalUiState(s.title, s.endpoint, s.status, s.rttMillis, keys.ctrl, keys.alt)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, TerminalUiState(title = route.name))

    /** Opens a new shell after an exit, or retries now while reconnecting. */
    fun restart() = session.start()

    /** Ends the shell on the computer. */
    fun disconnect() = session.disconnect()

    fun onViewportChanged(cols: Int, rows: Int) = session.onViewportChanged(cols, rows)

    fun sendInput(bytes: ByteArray) = session.sendInput(bytes)

    fun toggleCtrl() = sticky.update { it.copy(ctrl = it.ctrl.tapped()) }
    fun toggleAlt() = sticky.update { it.copy(alt = it.alt.tapped()) }

    fun stickyModifiers(): Int = sticky.value.let {
        (if (it.ctrl.active) Mods.CTRL else 0) or (if (it.alt.active) Mods.ALT else 0)
    }

    fun consumeSticky() = sticky.update { StickyKeys(it.ctrl.consumed(), it.alt.consumed()) }
}
