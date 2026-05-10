package com.rumor.mesh.core.block

import com.rumor.mesh.core.logging.RumorLog
import com.rumor.mesh.core.model.Blocklist
import com.rumor.mesh.core.model.BlocklistDiff
import com.rumor.mesh.core.model.ContentType
import com.rumor.mesh.core.model.MessagePayload
import com.rumor.mesh.core.model.MessageType
import com.rumor.mesh.core.protocol.GossipEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val TAG = "BlocklistGossipBridge"

/**
 * Wires the [BlocklistPublisher] / [BlocklistSubscriber] objects into the gossip
 * flow.
 *
 * Inbound: subscribes to [GossipEngine.incomingMessages] and routes
 *  - [MessageType.BLOCKLIST_PUBLISH] → [BlocklistSubscriber.applySnapshot]
 *  - [MessageType.BLOCKLIST_DIFF]    → [BlocklistSubscriber.applyDiff]
 * On a successful apply, [BlockManager.refresh] is triggered so the union mirror
 * is current.
 *
 * Outbound: [publishSnapshot] / [publishDiff] sign via the publisher and
 * enqueue a broadcast [com.rumor.mesh.core.model.RumorMessage].
 */
class BlocklistGossipBridge(
    private val gossipEngine: GossipEngine,
    private val publisher: BlocklistPublisher,
    private val subscriber: BlocklistSubscriber,
    private val blockManager: BlockManager,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        scope.launch {
            gossipEngine.incomingMessages.collect { msg ->
                when (msg.type) {
                    MessageType.BLOCKLIST_PUBLISH -> handleSnapshot(msg.payload?.content ?: return@collect)
                    MessageType.BLOCKLIST_DIFF    -> handleDiff(msg.payload?.content ?: return@collect)
                    else -> {}
                }
            }
        }
    }

    private suspend fun handleSnapshot(json: String) {
        val snapshot = runCatching { Json.decodeFromString<Blocklist>(json) }.getOrNull() ?: return
        if (subscriber.applySnapshot(snapshot)) {
            RumorLog.i(TAG, "Applied snapshot v${snapshot.version} from ${snapshot.publisherId.take(16)}…")
            blockManager.refreshExternal()
        }
    }

    private suspend fun handleDiff(json: String) {
        val diff = runCatching { Json.decodeFromString<BlocklistDiff>(json) }.getOrNull() ?: return
        if (subscriber.applyDiff(diff)) {
            RumorLog.i(TAG, "Applied diff ${diff.fromVersion}→${diff.toVersion} from ${diff.publisherId.take(16)}…")
            blockManager.refreshExternal()
        }
    }

    /** Publish the local block list as a signed snapshot through the gossip mesh. */
    suspend fun publishSnapshot() {
        val snapshot = publisher.publish() ?: return
        gossipEngine.composeOutbound(
            type = MessageType.BLOCKLIST_PUBLISH,
            payload = MessagePayload(ContentType.CONTROL, Json.encodeToString(snapshot)),
        )
        RumorLog.i(TAG, "Published snapshot v${snapshot.version} (${snapshot.entries.size} entries)")
    }

    /** Publish a signed diff through the gossip mesh. */
    suspend fun publishDiff(fromVersion: Long, previousEntries: List<String>) {
        val diff = publisher.publishDiff(fromVersion, previousEntries) ?: return
        gossipEngine.composeOutbound(
            type = MessageType.BLOCKLIST_DIFF,
            payload = MessagePayload(ContentType.CONTROL, Json.encodeToString(diff)),
        )
        RumorLog.i(TAG, "Published diff ${diff.fromVersion}→${diff.toVersion}")
    }
}
