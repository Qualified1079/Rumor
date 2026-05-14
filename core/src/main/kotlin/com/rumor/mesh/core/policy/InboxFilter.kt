package com.rumor.mesh.core.policy

import com.rumor.mesh.core.model.RumorMessage
import kotlinx.coroutines.flow.StateFlow

/** Inbox-layer gating for incoming messages. Never consulted on the relay path. */
interface InboxFilter {
    val policy: StateFlow<InboxPolicy>
    fun update(policy: InboxPolicy)
    suspend fun allowsInbox(msg: RumorMessage): Boolean
}

/** Accepts everything. Used in tests and the simulator. */
class PermissiveInboxFilter : InboxFilter {
    private val _policy = kotlinx.coroutines.flow.MutableStateFlow(InboxPolicy.DEFAULT)
    override val policy: StateFlow<InboxPolicy> = _policy
    override fun update(policy: InboxPolicy) { _policy.value = policy }
    override suspend fun allowsInbox(msg: RumorMessage): Boolean = true
}
