package com.rumor.mesh.ui.inbox

import androidx.lifecycle.ViewModel
import com.rumor.mesh.core.policy.InboxPolicy
import com.rumor.mesh.core.policy.InboxPolicyManager
import kotlinx.coroutines.flow.StateFlow

class InboxPolicyViewModel(
    private val policyManager: InboxPolicyManager,
) : ViewModel() {

    val policy: StateFlow<InboxPolicy> = policyManager.policy

    fun setContactsOnlyMedia(enabled: Boolean) {
        policyManager.update(policy.value.copy(contactsOnlyMedia = enabled))
    }

    fun setRejectUnknownTransfers(enabled: Boolean) {
        policyManager.update(policy.value.copy(rejectUnknownTransfers = enabled))
    }

    /** [megabytes] = null clears the cap. */
    fun setMaxIncomingMegabytes(megabytes: Long?) {
        val bytes = megabytes?.let { it * 1_000_000L }
        policyManager.update(policy.value.copy(maxIncomingBytes = bytes))
    }
}
