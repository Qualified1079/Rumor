package com.rumor.mesh.core.policy

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.rumor.mesh.core.logging.RumorLog
import com.rumor.mesh.core.model.ContentType
import com.rumor.mesh.core.model.MessageType
import com.rumor.mesh.core.model.RumorMessage
import com.rumor.mesh.core.model.TransferMetadata
import com.rumor.mesh.core.data.ContactRepository
import com.rumor.mesh.core.policy.InboxFilter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.rumor.mesh.core.wire.WireJson

private const val PREFS_NAME = "rumor_inbox_policy"
private const val KEY_CONTACTS_ONLY_MEDIA = "contacts_only_media"
private const val KEY_REJECT_UNKNOWN_TRANSFERS = "reject_unknown_transfers"
private const val KEY_MAX_INCOMING_BYTES = "max_incoming_bytes"
private const val KEY_FRIENDED_SENDERS_ONLY = "friended_senders_only"
private const val TAG = "InboxPolicyManager"

/**
 * Owns the user's [InboxPolicy] and decides whether incoming messages are
 * allowed to reach the user's inbox. Pure decision logic; the gossip engine
 * applies the verdict.
 *
 * Like [com.rumor.mesh.core.block.BlockManager], this is consulted only on the
 * inbox emit path — never on the relay path.
 */
class InboxPolicyManager(
    context: Context,
    private val contactRepo: ContactRepository,
    /** O135(1)/O136: local userId so own messages always pass the friended gate. Null = don't self-exempt. */
    private val localUserId: () -> String? = { null },
) : InboxFilter {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _policy = MutableStateFlow(load())
    override val policy: StateFlow<InboxPolicy> = _policy.asStateFlow()

    override fun update(policy: InboxPolicy) {
        prefs.edit {
            putBoolean(KEY_CONTACTS_ONLY_MEDIA, policy.contactsOnlyMedia)
            putBoolean(KEY_REJECT_UNKNOWN_TRANSFERS, policy.rejectUnknownTransfers)
            policy.maxIncomingBytes?.let { putLong(KEY_MAX_INCOMING_BYTES, it) }
                ?: remove(KEY_MAX_INCOMING_BYTES)
            putBoolean(KEY_FRIENDED_SENDERS_ONLY, policy.friendedSendersOnly)
        }
        _policy.value = policy
    }

    /**
     * Decide whether [msg] is allowed to surface in the inbox. Returns true if
     * it should be emitted; false if policy suppresses it.
     */
    override suspend fun allowsInbox(msg: RumorMessage): Boolean {
        val pol = _policy.value

        // O135(1) "known peers only": only FRIENDED senders reach the inbox
        // (own messages always pass). Relay+store untouched — display-layer
        // allowlist. "Friended" is an explicit act (O136), never a mere contact.
        if (pol.friendedSendersOnly &&
            msg.senderId != localUserId() &&
            !contactRepo.isFriended(msg.senderId)
        ) {
            RumorLog.d(TAG, "Inbox: dropping non-friend ${msg.senderId.take(8)}… (known-peers-only)")
            return false
        }

        val isContact = contactRepo.getById(msg.senderId) != null

        // Media in plain BROADCAST/DIRECT (rare, but possible).
        if (pol.contactsOnlyMedia && !isContact) {
            val ct = msg.payload?.contentType
            if (ct == ContentType.IMAGE || ct == ContentType.VOICE || ct == ContentType.FILE) {
                RumorLog.d(TAG, "Suppressing ${ct} from non-contact ${msg.senderId.take(8)}…")
                return false
            }
        }

        // Transfer metadata gating — happens before TransferAssembler stores anything.
        if (msg.type == MessageType.TRANSFER_METADATA) {
            val meta = runCatching {
                WireJson.decodeFromString<TransferMetadata>(msg.payload?.content ?: return true)
            }.getOrNull() ?: return true

            if (pol.rejectUnknownTransfers && !isContact) {
                RumorLog.d(TAG, "Rejecting transfer metadata from non-contact ${msg.senderId.take(8)}…")
                return false
            }
            val cap = pol.maxIncomingBytes
            if (cap != null && meta.totalBytes > cap) {
                RumorLog.d(TAG, "Rejecting transfer ${meta.transferId.take(8)}… exceeding ${cap}B cap")
                return false
            }
        }

        return true
    }

    private fun load(): InboxPolicy = InboxPolicy(
        contactsOnlyMedia = prefs.getBoolean(KEY_CONTACTS_ONLY_MEDIA, false),
        rejectUnknownTransfers = prefs.getBoolean(KEY_REJECT_UNKNOWN_TRANSFERS, false),
        maxIncomingBytes = if (prefs.contains(KEY_MAX_INCOMING_BYTES))
            prefs.getLong(KEY_MAX_INCOMING_BYTES, 0L) else null,
        friendedSendersOnly = prefs.getBoolean(KEY_FRIENDED_SENDERS_ONLY, false),
    )
}
