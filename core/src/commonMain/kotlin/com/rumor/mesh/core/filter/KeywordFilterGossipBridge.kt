package com.rumor.mesh.core.filter

import com.rumor.mesh.core.logging.RumorLog
import com.rumor.mesh.core.model.ContentType
import com.rumor.mesh.core.model.FilterEntry
import com.rumor.mesh.core.model.KeywordFilterList
import com.rumor.mesh.core.model.MessagePayload
import com.rumor.mesh.core.model.MessageType
import com.rumor.mesh.core.protocol.GossipEngine
import com.rumor.mesh.core.wire.WireJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString

/**
 * O67 — Wires `KeywordFilterPublisher` / `KeywordFilterSubscriber` to the
 * mesh. Mirrors `BlocklistGossipBridge`.
 *
 * Inbound: on every `KEYWORD_FILTER_PUBLISH` message that crosses
 * `gossipEngine.incomingMessages`, decode the JSON payload and hand to
 * the subscriber. Verification + version monotonicity gating happen in
 * [KeywordFilterSubscriber.applyList] — this bridge is just routing.
 *
 * Outbound: [publishList] is the local user's "share my filter list"
 * action. Composes a `KEYWORD_FILTER_PUBLISH` carrying the signed list
 * as a CONTROL payload; the gossip engine handles flood + relay.
 *
 * Side effect on apply: the bridge does NOT itself re-render existing
 * UI. The matcher reads `subscribedListsForMatcher()` on every render —
 * the next view-model refresh picks up the new list automatically.
 */
class KeywordFilterGossipBridge(
    private val gossipEngine: GossipEngine,
    private val publisher: KeywordFilterPublisher,
    private val subscriber: KeywordFilterSubscriber,
    /** Optional notification hook ("a new filter list was applied") for the UI. */
    private val onListApplied: ((KeywordFilterList) -> Unit)? = null,
) {
    private val TAG = "KeywordFilterGossipBridge"
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    init {
        scope.launch {
            gossipEngine.incomingMessages.collect { msg ->
                if (msg.type == MessageType.KEYWORD_FILTER_PUBLISH) {
                    handleInbound(msg.payload?.content ?: return@collect)
                }
            }
        }
    }

    private suspend fun handleInbound(json: String) {
        val list = runCatching { WireJson.decodeFromString<KeywordFilterList>(json) }.getOrNull() ?: return
        if (subscriber.applyList(list)) {
            RumorLog.i(TAG, "Applied v${list.version} from ${list.publisherId.take(16)}…")
            onListApplied?.invoke(list)
        }
    }

    /**
     * Sign and broadcast a new list authored by the local user. Returns
     * the signed list on success, or null if identity is locked.
     */
    suspend fun publishList(
        name: String,
        entries: List<FilterEntry>,
        userIdAllowlist: Set<String> = emptySet(),
    ): KeywordFilterList? {
        val list = publisher.publish(name, entries, userIdAllowlist) ?: return null
        gossipEngine.composeOutbound(
            type = MessageType.KEYWORD_FILTER_PUBLISH,
            payload = MessagePayload(ContentType.CONTROL, WireJson.encodeToString(list)),
        )
        RumorLog.i(TAG, "Published v${list.version} '${list.name}' (${list.entries.size} entries)")
        return list
    }
}
