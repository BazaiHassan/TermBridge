package io.termbridge.feature.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.termbridge.core.crypto.MachineStore
import io.termbridge.core.crypto.PairedMachine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DevicesViewModel @Inject constructor(private val store: MachineStore) : ViewModel() {

    /** Null while loading, so the empty state doesn't flash. */
    val machines: StateFlow<List<PairedMachine>?> =
        store.machines.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Forgets the machine on this phone; the computer revokes with `termbridge revoke`. */
    fun forget(machine: PairedMachine) {
        viewModelScope.launch { store.remove(machine.agentId) }
    }
}
