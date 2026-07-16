package com.rumor.mesh.plugin

import com.rumor.mesh.core.logging.LogLevel
import com.rumor.mesh.core.model.RumorMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow

/**
 * The complete API surface available to a plugin.
 *
 * This is the only thing a plugin developer needs to understand — everything
 * else (GossipEngine, WifiDirectTransport, Room, etc.) is internal to the
 * core and invisible to plugins.
 *
 * Obtained via [RumorPlugin.onAttach]. Store it; the same instance remains
 * valid for the lifetime of the plugin.
 */
interface PluginContext {

    // ── Identity ──────────────────────────────────────────────────────────────

    /** User ID of the local node. Null if the identity is locked. */
    val localUserId: String?

    /** Base64-encoded Ed25519 public key of the local node. */
    val localPublicKey: String?

    /**
     * Sign [bytes] with the local Ed25519 identity key. Returns the
     * raw 64-byte detached signature.
     *
     * **Use case:** prove "this plugin-produced artifact belongs to
     * userId X" cryptographically — Cashu wallet ownership, Lightning
     * gateway attestation, generic credential challenges. The
     * companion app calls this to anchor an artifact to the local
     * Rumor identity without needing its own keypair.
     *
     * **Hard rule (documented contract, not enforced):** plugins may
     * pass ONLY their own bytes (a challenge nonce the plugin
     * generated, a wallet token the plugin produced, etc.). Passing
     * host-controlled bytes (e.g. an inbound message, a transcript
     * the host built) could let a malicious plugin forge a sig over
     * arbitrary content. A malicious plugin can already do worse
     * with the existing API surface (read every inbound message,
     * compose outbound messages with arbitrary content), so the rule
     * is "documented contract, not enforced." A future Keystore
     * migration (O20 / O44) makes the surface this small for a
     * reason — when signing moves into the TEE, this is the only
     * Kotlin-side call site that needs to change.
     *
     * **Throws** [IllegalStateException] if the local identity is
     * locked (no keypair available to sign with).
     */
    fun signWithLocalKey(bytes: ByteArray): ByteArray

    // ── Messaging ─────────────────────────────────────────────────────────────

    /**
     * Inject a message received from the plugin's external network into the
     * local mesh. The gossip engine will store, verify, and flood it normally.
     *
     * @param message A fully-formed [RumorMessage]. For bridge traffic where
     *   the original sender used a different signing scheme, set [RumorMessage.signature]
     *   to [BRIDGE_UNSIGNED] and the engine will skip signature verification.
     * @param sourceDescription A short label for log output, e.g. "meshtastic node 1a2b".
     */
    fun sendMessage(message: RumorMessage, sourceDescription: String = pluginId)

    /**
     * A [Flow] of every new message that arrives on the local mesh.
     * The plugin can filter by [RumorMessage.type] or [RumorMessage.senderId].
     * This flow is hot — collect it inside a coroutine launched in your plugin scope.
     */
    fun observeIncoming(): Flow<RumorMessage>

    // ── Encrypted DM bridging (O5a) ───────────────────────────────────────────

    /**
     * Register a [DmEnvelope] so that outbound DMs to recipients matching
     * [DmEnvelope.recipientPrefix] use this envelope's crypto instead of the
     * native Rumor X25519+AES-GCM path.
     *
     * @throws IllegalStateException if the prefix is already owned by another envelope.
     *
     * The host unregisters this envelope atomically when the plugin is detached,
     * before [RumorPlugin.onDetach] is called.
     */
    fun registerDmEnvelope(envelope: DmEnvelope)

    /**
     * Deliver an encrypted DM received from the bridge's external network to the
     * local recipient's inbox. The engine routes the ciphertext to the recipient
     * without decrypting — the recipient's UI calls [DmEnvelope.decrypt] at read time.
     *
     * The engine looks up the decryption envelope by [senderUserId] prefix locally.
     * [envelopeId] is only a sanity assertion at the bridge boundary — if it does not
     * match the registered envelope for the prefix, the message is dropped. This
     * prevents an attacker from coercing decryption with a different envelope by
     * injecting a fabricated envelope id.
     *
     * The resulting message gets [com.rumor.mesh.core.model.TrustLevel.BRIDGED] and
     * is never re-relayed onto the signed Rumor mesh.
     */
    fun injectBridgedDm(
        recipientUserId: String,
        senderUserId: String,
        senderPubKey: ByteArray,
        ciphertext: ByteArray,
        envelopeId: String,
    )

    /**
     * A hot [Flow] of outbound DIRECT messages composed using a registered [DmEnvelope].
     * Collect this inside your plugin scope to pick up DMs that the local user addressed
     * to a bridged contact, then forward [BridgedDmOutbound.ciphertext] to the external
     * network using your radio framing. The raw ciphertext is the output of
     * [DmEnvelope.encrypt] — no Rumor message framing is included.
     */
    fun observeOutboundBridgedDm(): Flow<BridgedDmOutbound>

    // ── Contacts ──────────────────────────────────────────────────────────────

    /**
     * Live list of known contacts. Updates whenever a new peer is discovered
     * or an existing contact's data changes.
     */
    val contacts: Flow<List<ContactSummary>>

    // ── Logging ───────────────────────────────────────────────────────────────

    /**
     * Write a structured log entry attributed to this plugin.
     * Appears in the in-app log viewer and logcat (in debug mode).
     */
    fun log(level: LogLevel, message: String, throwable: Throwable? = null)

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Stable plugin ID — same as [RumorPlugin.pluginId]. Provided for convenience inside [sendMessage]. */
    val pluginId: String

    /**
     * Host-owned [CoroutineScope] tied to this plugin's enabled lifetime.
     *
     * Plugins should launch all background work here. The host cancels this
     * scope when the plugin is disabled or the service is shutting down,
     * which kills every coroutine the plugin started — regardless of whether
     * the plugin's [RumorPlugin.onDetach] cleans up. This makes toggleability
     * a property of the *wrapper*, not something every plugin author has to
     * implement correctly.
     */
    val scope: CoroutineScope

    companion object {
        /**
         * Sentinel signature value for bridge messages where the originating
         * node did not use Ed25519 (e.g., Meshtastic nodes).
         * The gossip engine skips signature verification for messages with this value.
         */
        const val BRIDGE_UNSIGNED = "bridge_unsigned"
    }
}

/**
 * Outbound bridged DM payload emitted by [PluginContext.observeOutboundBridgedDm].
 * The bridge plugin wraps [ciphertext] in its target-network framing and transmits it.
 */
data class BridgedDmOutbound(
    val recipientUserId: String,
    val envelopeId: String,
    /** Raw ciphertext as returned by [DmEnvelope.encrypt]. Ready for target-network framing. */
    val ciphertext: ByteArray,
)

/**
 * Simplified contact view exposed to plugins.
 * Plugins do not need access to Room entities or storage internals.
 */
data class ContactSummary(
    val userId: String,
    val publicKey: String,
    val displayName: String?,
    val isVerified: Boolean,
)
