package com.rumor.mesh.core.protocol

import com.rumor.mesh.core.identity.IdentityProvider
import com.rumor.mesh.core.logging.RumorLog
import com.rumor.mesh.core.model.ContentType
import com.rumor.mesh.core.model.MessagePayload
import com.rumor.mesh.core.model.MessageType
import com.rumor.mesh.core.model.RumorMessage
import com.rumor.mesh.core.model.SelfPresencePayload
import com.rumor.mesh.core.model.UserMode
import com.rumor.mesh.core.protocol.GossipEngine.Companion.DEFAULT_BROADCAST_HOPS
import com.rumor.mesh.core.routing.MeshViewTracker
import com.rumor.mesh.core.wire.WireJson
import kotlinx.serialization.encodeToString

private const val TAG = "GossipEngine"

internal class PresenceBeacons(
    private val identityProvider: IdentityProvider,
    private val clock: com.rumor.mesh.core.Clock,
    private val meshView: MeshViewTracker?,
    private val outbound: OutboundComposer,
) {
    /**
     * O124 — host-wired on-demand pulse (composes a SELF_PRESENCE with the
     * host's current mode + adjacency). Null disables solicited replies.
     * Self-echoes never reach [handleSelfPresence] (dropped upstream) and
     * dedup fires the handler at most once per beacon, so this cannot loop.
     */
    var presencePulse: (() -> Unit)? = null
    private val presenceReplyGate = com.rumor.mesh.core.routing.PresenceReplyGate(clock)

    /**
     * Compose and broadcast a [MessageType.SELF_PRESENCE] beacon (O30 + O57).
     * The sender declares its current [mode] to the mesh. Used both as the
     * entry pulse when going Static or Free, and as the symmetric exit pulse
     * (mode = MOBILE) when leaving a higher mode — without the exit pulse,
     * peers continue to route through a ghost anchor for the full presence-
     * decay window.
     *
     * [recentlyExchangedWith] is the optional O31 route advertisement payload;
     * pass an empty list (the default) unless the user has opted into Tier-3
     * broadcast routing-visibility for their own visibility (typically Free
     * mode). The receiver decides how to weight this list given its own
     * O58 visibility settings.
     */
    fun composeSelfPresence(
        mode: UserMode,
        recentlyExchangedWith: List<String> = emptyList(),
    ): RumorMessage? {
        val identity = identityProvider.identity.value ?: return null
        val payload = SelfPresencePayload(
            mode = mode,
            authorizedAtMs = clock.now(),
            recentlyExchangedWith = recentlyExchangedWith,
        )
        val msg = outbound.buildMessage(
            identity = identity,
            type = MessageType.SELF_PRESENCE,
            hopsToLive = DEFAULT_BROADCAST_HOPS,
            payload = MessagePayload(ContentType.CONTROL, WireJson.encodeToString(payload)),
        )
        outbound.enqueueImmediate(msg)
        return msg
    }

    fun handleSelfPresence(msg: RumorMessage) {
        val tracker = meshView ?: return
        val json = msg.payload?.content ?: return
        val payload = runCatching {
            WireJson.decodeFromString<SelfPresencePayload>(json)
        }.getOrNull() ?: return
        // Subject is the beacon author. authorizedAtMs is the beacon's own
        // timestamp; MeshViewTracker clamps a future value and applies decay.
        // recentlyExchangedWith is the author's advertised adjacency.
        val wasFresh = tracker.hasFresh(msg.senderId)
        tracker.record(msg.senderId, payload.mode, payload.recentlyExchangedWith, payload.authorizedAtMs)
        // O124 solicited response: a pulse from an unknown-or-stale peer gets our
        // own pulse back (they're as blind to us as we were to them), gated
        // per-peer by OUR clock so probe spam can't force reply spam.
        val pulse = presencePulse ?: return
        if (presenceReplyGate.shouldReply(msg.senderId, wasFresh)) {
            // O124/O127 instrumentation: one line per solicited pulse fire, so a
            // sybil-storm harness can count amplification directly instead of
            // inferring it from evictable downstream broadcasts.
            RumorLog.d(TAG, "presence solicit-reply → ${msg.senderId.take(8)} (wasFresh=$wasFresh)")
            pulse()
        }
    }
}
