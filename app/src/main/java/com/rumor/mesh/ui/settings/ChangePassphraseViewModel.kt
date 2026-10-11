package com.rumor.mesh.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rumor.mesh.core.identity.IdentityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ChangePassphraseState(
    val error: String? = null,
    val success: Boolean = false,
    val busy: Boolean = false,
)

class ChangePassphraseViewModel(
    private val identityManager: IdentityManager,
) : ViewModel() {

    private val _state = MutableStateFlow(ChangePassphraseState())
    val state: StateFlow<ChangePassphraseState> = _state.asStateFlow()

    /** Re-verifies [current] against the stored key before rotating to [new]. */
    fun changePassphrase(current: String, new: String, confirm: String) {
        if (_state.value.busy) return
        _state.value = ChangePassphraseState()

        if (new.length < 8) {
            _state.value = ChangePassphraseState(error = "New passphrase must be at least 8 characters")
            return
        }
        if (new != confirm) {
            _state.value = ChangePassphraseState(error = "New passphrases don't match")
            return
        }
        _state.value = ChangePassphraseState(busy = true)
        // Two 600k-iteration PBKDF2 runs (verify + rewrap): ~8s on an S10e, an ANR on the main thread.
        viewModelScope.launch {
            _state.value = withContext(Dispatchers.Default) {
                if (!identityManager.unlock(current)) {
                    ChangePassphraseState(error = "Current passphrase is incorrect")
                } else if (identityManager.changePassphrase(new)) {
                    ChangePassphraseState(success = true)
                } else {
                    ChangePassphraseState(error = "Failed to change passphrase")
                }
            }
        }
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }
}
