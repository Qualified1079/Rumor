package com.rumor.mesh.plugin

/**
 * Pluggable DM encryption envelope registered by bridge plugins.
 *
 * Enables Architecture B (envelope passthrough): the local node encrypts/decrypts
 * using the target network's native crypto so the bridge never holds plaintext.
 *
 * The engine selects the envelope by matching the recipient userId prefix locally —
 * never from anything on the wire (security constraint: envelope id is derived, not
 * asserted). Replay protection is the envelope's responsibility; document the mechanism
 * in each implementation (e.g. Meshtastic: packet id + timestamp window;
 * MeshCore: sender_timestamp monotonicity).
 */
interface DmEnvelope {
    /**
     * Matches recipient userIds: any userId starting with this prefix uses this envelope.
     * Example: "meshtastic:", "meshcore:". Must be unique across all registered envelopes.
     */
    val recipientPrefix: String

    /**
     * Stable wire-format identifier stored alongside the ciphertext for sanity checks.
     * NOT the decryption selector — the engine always derives the envelope from the
     * sender userId prefix, ignoring any wire-asserted id.
     */
    val envelopeId: String

    /**
     * True when this envelope's AEAD/MAC provides authentication, making the outer
     * Rumor Ed25519 signature redundant for bridged DMs.
     *
     * Honored ONLY for messages that arrive via [PluginContext.injectBridgedDm]
     * (source: LOCAL_BRIDGE). Over peer transport this flag is ignored — every
     * peer-received message must pass Ed25519 verification regardless of any
     * envelope-id claim. Violating this rule is a downgrade attack surface.
     */
    val selfAuthenticating: Boolean

    /**
     * Encrypt [plaintext] for [recipientUserId] whose public key is [recipientPubKey].
     * Returns the raw ciphertext; no Rumor framing is applied here.
     */
    fun encrypt(recipientUserId: String, recipientPubKey: ByteArray, plaintext: ByteArray): ByteArray

    /**
     * Decrypt [ciphertext] from [senderUserId] whose public key is [senderPubKey].
     * Returns null on any authentication or format failure — never throws.
     */
    fun decrypt(senderUserId: String, senderPubKey: ByteArray, ciphertext: ByteArray): ByteArray?
}
