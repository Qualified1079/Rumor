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
 * Simplified contact view exposed to plugins.
 * Plugins do not need access to Room entities or storage internals.
 */
data class ContactSummary(
    val userId: String,
    val publicKey: String,
    val displayName: String?,
    val isVerified: Boolean,
)
