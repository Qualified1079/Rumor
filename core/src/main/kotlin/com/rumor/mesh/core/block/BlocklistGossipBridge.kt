package com.rumor.mesh.core.block

import com.rumor.mesh.core.logging.RumorLog
import com.rumor.mesh.core.model.Blocklist
import com.rumor.mesh.core.model.BlocklistDiff
import com.rumor.mesh.core.model.ContentType
import com.rumor.mesh.core.model.MessagePayload
import com.rumor.mesh.core.model.MessageType
import com.rumor.mesh.core.protocol.GossipEngine
import com.rumor.mesh.core.wire.WireJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString

private const val TAG = "BlocklistGossipBridge"

/**
 * Glue layer between [GossipEngine]'s message flow and the
 * blocklist publisher/subscriber machinery. Subscribes to
 * [GossipEngine.incomingMessages] at construction time and routes
 * BLOCKLIST_PUBLISH / BLOCKLIST_DIFF messages into the subscriber;
 * exposes [publishSnapshot] / [publishDiff] for the local user to
 * publish their own list.
 *
 * Same pattern as `KeywordFilterGossipBridge` (O67) — sit between
 * the engine's content flow and a self-contained pub/sub module, so
 * neither side knows about the other.
 *
 * Tag prefixing on `publisherId.take(16)` in logs is deliberate —
 * full 64-char userIds blow up log lines and we already have the
 * full prefix in `RumorMessage.senderId` for the relevant message.
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
        val snapshot = runCatching { WireJson.decodeFromString<Blocklist>(json) }.getOrNull() ?: return
        if (subscriber.applySnapshot(snapshot)) {
            RumorLog.i(TAG, "Applied snapshot v${snapshot.version} from ${snapshot.publisherId.take(16)}…")
            blockManager.refreshExternal()
        }
    }

    private suspend fun handleDiff(json: String) {
        val diff = runCatching { WireJson.decodeFromString<BlocklistDiff>(json) }.getOrNull() ?: return
        if (subscriber.applyDiff(diff)) {
            RumorLog.i(TAG, "Applied diff ${diff.fromVersion}→${diff.toVersion} from ${diff.publisherId.take(16)}…")
            blockManager.refreshExternal()
        }
    }

    suspend fun publishSnapshot() {
        val snapshot = publisher.publish() ?: return
        gossipEngine.composeOutbound(
            type = MessageType.BLOCKLIST_PUBLISH,
            payload = MessagePayload(ContentType.CONTROL, WireJson.encodeToString(snapshot)),
        )
        RumorLog.i(TAG, "Published snapshot v${snapshot.version} (${snapshot.entries.size} entries)")
    }

    suspend fun publishDiff(fromVersion: Long, previousEntries: List<String>) {
        val diff = publisher.publishDiff(fromVersion, previousEntries) ?: return
        gossipEngine.composeOutbound(
            type = MessageType.BLOCKLIST_DIFF,
            payload = MessagePayload(ContentType.CONTROL, WireJson.encodeToString(diff)),
        )
        RumorLog.i(TAG, "Published diff ${diff.fromVersion}→${diff.toVersion}")
    }
}
