package com.rumor.mesh.core.policy

import com.rumor.mesh.core.model.RumorMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * O135(1) / research "ship first" — the "known peers only" inbox allowlist,
 * the cheapest-highest-impact sybil mitigation (SSB `hops=1` as a toggle).
 *
 * When [InboxPolicy.friendedSendersOnly] is on, only messages whose sender the
 * user has affirmatively **friended** reach the inbox. A sybil flood — any
 * number of minted identities — is then invisible to the user: they never see
 * it, because you don't blocklist bad keys (futile against churning identities,
 * the O27/O51/O60 floor), you **allowlist good ones**.
 *
 * Load-bearing invariants:
 * - **Relay and storage are untouched.** This gates only the inbox emit path
 *   ([GossipEngine.emitToInbox]); the node still stores and relays everything
 *   (relay-never-touches-blocklist). A sybil flood costs the user nothing but
 *   is carried for the rest of the mesh.
 * - **"Friended" is an explicit act, NOT a contact.** [isFriended] must be
 *   backed by a deliberate user gesture (O136 friend action), never by the
 *   auto-`ensureContact` that fires for every exchanged/relayed sender — those
 *   include the sybils this filter exists to hide (O134).
 * - **Own messages always pass** (a node must see what it composed).
 *
 * [isFriended] is a suspend predicate so a Room/DB-backed friend store fits;
 * the simulator supplies an in-memory set.
 */
class KnownSendersInboxFilter(
    private val localUserId: () -> String?,
    private val isFriended: suspend (userId: String) -> Boolean,
    initial: InboxPolicy = InboxPolicy.DEFAULT,
) : InboxFilter {
    private val _policy = MutableStateFlow(initial)
    override val policy: StateFlow<InboxPolicy> = _policy
    override fun update(policy: InboxPolicy) { _policy.value = policy }

    override suspend fun allowsInbox(msg: RumorMessage): Boolean {
        if (!_policy.value.friendedSendersOnly) return true
        if (msg.senderId == localUserId()) return true
        return isFriended(msg.senderId)
    }
}
