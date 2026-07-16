package com.rumor.mesh.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Device-behavior mode declared by a self-presence beacon (O57). Routing
 * engines weight a node as a more durable breadcrumb anchor when it
 * announces [STATIC] or [FREE]; the [MOBILE] declaration is the implicit
 * default and is what the sender emits on the symmetric *exit pulse* when
 * leaving a higher mode, so peers can downgrade routing weight immediately
 * rather than wait for stale signals to decay.
 */
@Serializable
enum class UserMode {
    /** Device assumed to be moving; low routing-weight as an anchor. Default. */
    @SerialName("mobile") MOBILE,
    /** Device treated as still for the duration (manual or "plugged in"). Eligible routing anchor. */
    @SerialName("static") STATIC,
    /** Dedicate bandwidth/CPU/battery (manual or "plugged in + screen off"). Subsumes STATIC. */
    @SerialName("free")   FREE,
}

/**
 * Body of a [MessageType.SELF_PRESENCE] message. The sender declares its own
 * mode; relays never speak for someone else's presence (O30 refined per O58
 * Tier-3 self-only constraint). The outer [RumorMessage.signature] is by
 * the sender's long-term Ed25519 key in the usual way; no inner sig needed
 * because the beacon's authority is the same as any other signed message
 * from this sender.
 *
 * Symmetric exit pulse: when a node leaves STATIC or FREE (unplug, screen-on
 * transition, manual toggle off), it emits a SELF_PRESENCE with mode=MOBILE
 * so peers downgrade routing weight immediately. Without it, peers continue
 * routing through a ghost anchor for the full presence-decay window.
 */
@Serializable
data class SelfPresencePayload(
    val mode: UserMode,
    /** Wall-clock epoch ms when the mode transition was authorized locally. */
    val authorizedAtMs: Long,
    /**
     * Optional `recentlyExchangedWith` list (top-N userIds, per O31 route
     * advertisements). Present only when the node has opted into Tier-3
     * broadcast routing-visibility (typically Free-mode). Empty otherwise.
     */
    val recentlyExchangedWith: List<String> = emptyList(),
    /** Reserved forward-compat carrier. See [RumorMessage.ext]. */
    @SerialName("_ext") val ext: Map<String, JsonElement>? = null,
)
