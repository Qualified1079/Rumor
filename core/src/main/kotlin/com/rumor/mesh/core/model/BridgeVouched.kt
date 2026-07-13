package com.rumor.mesh.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Body of an [MessageType.BRIDGE_VOUCHED] message (O17). A bridge node
 * receives a message from a foreign network (Meshtastic, MeshCore, …),
 * wraps it in this envelope, and signs the whole thing with its own
 * Rumor Ed25519 key. The signature certifies "I, this bridge, received
 * this content from {originNetwork}." It does NOT certify the content
 * itself — the foreign sender is not a Rumor user and may have produced
 * the content under any threat model.
 *
 * Critically distinct from [TrustLevel.BRIDGED]:
 *   - BRIDGED — no Rumor signature. Reaches only the bridge's direct
 *     peers. Used when the bridge holds the foreign content but doesn't
 *     want to vouch for it across the mesh.
 *   - BRIDGE_VOUCHED — outer Rumor signature exists; relays normally.
 *     Other Rumor nodes can verify the OUTER envelope and relay it
 *     because the bridge stands behind delivery (not content). UI must
 *     render this as "via {bridge} from {originNetwork}:{senderId}" so
 *     users don't conflate it with native Rumor authenticity (O47).
 *
 * Anti-laundering invariant: the outer [RumorMessage.senderId] is the
 * bridge's Rumor userId. The bridge cannot claim someone else originated
 * the content, only that it received content from a particular foreign
 * source. Receivers can per-bridge toggle whether to display vouched
 * traffic from that bridge — out of scope for this commit; the wire
 * format permits it.
 */
@Serializable
data class BridgeVouchedPayload(
    /** Foreign network identifier — e.g. `"meshtastic"`, `"meshcore"`, `"aprs"`. */
    val originNetwork: String,
    /**
     * The foreign network's identifier for the original sender. Synthetic
     * userId in the bridge's prefix space — e.g. `"meshtastic:abc123"`. Per
     * O48, the synthetic id is derived from the radio-asserted pubkey when
     * available, so a compromised radio can't substitute keys for existing
     * pinned contacts.
     */
    val originSenderId: String,
    /**
     * Foreign sender's signature over [payload], if the foreign network
     * provides one. Opaque bytes; verifier-of-record is whoever later
     * decides to honor that network's authenticity scheme. Base64. Null
     * when the foreign network has no signing (channel-PSK-only networks).
     */
    val originSignatureIfAny: String? = null,
    /** Foreign message content type — `"text"`, `"image"`, etc. Display hint. */
    val originContentType: ContentType,
    /**
     * The foreign payload itself — text content for TEXT, Base64 bytes for
     * media. Receivers can render or store without involving foreign-network
     * crypto if the bridge passes plaintext (Meshtastic channel broadcasts).
     */
    val payload: String,
    /** Wall-clock epoch ms when the bridge received this from the foreign network. */
    val receivedAtMs: Long,
    /** Reserved forward-compat carrier. See [RumorMessage.ext]. */
    @SerialName("_ext") val ext: Map<String, JsonElement>? = null,
)

/**
 * Domain-tagged canonical bytes the bridge signs as the outer Rumor
 * signature for a BRIDGE_VOUCHED message. The bridge's [RumorMessage]
 * outer signature is the one a normal verifier checks; this function
 * just produces the canonical input scope. Field order is fixed — never
 * reorder without bumping the `rumor-bridge-vouched-vN:` tag.
 */
fun bridgeVouchedSignableBytes(
    bridgeUserId: String,
    originNetwork: String,
    originSenderId: String,
    payload: String,
    receivedAtMs: Long,
): ByteArray = buildString {
    append("rumor-bridge-vouched-v1:")
    append(bridgeUserId); append('|')
    append(originNetwork); append('|')
    append(originSenderId); append('|')
    append(receivedAtMs); append('|')
    append(payload)
}.toByteArray(Charsets.UTF_8)
