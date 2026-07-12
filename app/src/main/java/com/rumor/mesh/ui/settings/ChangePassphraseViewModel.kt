package com.rumor.mesh.ui.settings

import androidx.lifecycle.ViewModel
import com.rumor.mesh.core.identity.IdentityManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ChangePassphraseState(
    val error: String? = null,
    val success: Boolean = false,
)

class ChangePassphraseViewModel(
    private val identityManager: IdentityManager,
) : ViewModel() {

    private val _state = MutableStateFlow(ChangePassphraseState())
    val state: StateFlow<ChangePassphraseState> = _state.asStateFlow()

    /** Re-verifies [current] against the stored key before rotating to [new]. */
    fun changePassphrase(current: String, new: String, confirm: String) {
        _state.value = ChangePassphraseState()

        if (new.length < 8) {
            _state.value = ChangePassphraseState(error = "New passphrase must be at least 8 characters")
            return
        }
        if (new != confirm) {
            _state.value = ChangePassphraseState(error = "New passphrases don't match")
            return
        }
        if (!identityManager.unlock(current)) {
            _state.value = ChangePassphraseState(error = "Current passphrase is incorrect")
            return
        }
        val ok = identityManager.changePassphrase(new)
        _state.value = if (ok) {
            ChangePassphraseState(success = true)
        } else {
            ChangePassphraseState(error = "Failed to change passphrase")
        }
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }
}
