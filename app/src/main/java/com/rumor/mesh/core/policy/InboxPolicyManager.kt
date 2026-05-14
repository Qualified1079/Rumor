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
import kotlinx.serialization.json.Json

private const val PREFS_NAME = "rumor_inbox_policy"
private const val KEY_CONTACTS_ONLY_MEDIA = "contacts_only_media"
private const val KEY_REJECT_UNKNOWN_TRANSFERS = "reject_unknown_transfers"
private const val KEY_MAX_INCOMING_BYTES = "max_incoming_bytes"
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
) : InboxFilter {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _policy = MutableStateFlow(load())
    val policy: StateFlow<InboxPolicy> = _policy.asStateFlow()

    fun update(policy: InboxPolicy) {
        prefs.edit {
            putBoolean(KEY_CONTACTS_ONLY_MEDIA, policy.contactsOnlyMedia)
            putBoolean(KEY_REJECT_UNKNOWN_TRANSFERS, policy.rejectUnknownTransfers)
            policy.maxIncomingBytes?.let { putLong(KEY_MAX_INCOMING_BYTES, it) }
                ?: remove(KEY_MAX_INCOMING_BYTES)
        }
        _policy.value = policy
    }

    /**
     * Decide whether [msg] is allowed to surface in the inbox. Returns true if
     * it should be emitted; false if policy suppresses it.
     */
    suspend fun allowsInbox(msg: RumorMessage): Boolean {
        val pol = _policy.value
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
                Json.decodeFromString<TransferMetadata>(msg.payload?.content ?: return true)
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
    )
}
