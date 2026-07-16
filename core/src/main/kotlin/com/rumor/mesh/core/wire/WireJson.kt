package com.rumor.mesh.core.wire

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

/**
 * Single source of truth for every byte that crosses a Rumor wire.
 *
 * Forward-compat policy is encoded in the config:
 *  - `ignoreUnknownKeys = true` — a v0.1 node parsing a v0.2 frame does not throw
 *    on new top-level fields. Future-additive fields land inside the reserved
 *    `_ext` map on each wire object (see [com.rumor.mesh.core.model.GossipPacket],
 *    [com.rumor.mesh.core.model.RumorMessage], etc.) so they ride opaquely through
 *    older relays.
 *  - `encodeDefaults = true` — defaults are always emitted on the wire. A v0.1
 *    sender always carries every documented field even when at its default, so
 *    v0.2 receivers see a stable shape and don't have to guess.
 *  - `explicitNulls = false` — `null` optional fields are omitted from output,
 *    shrinking the wire envelope. Combines with `encodeDefaults = true`:
 *    non-null defaults emit, null defaults don't.
 *  - `classDiscriminator = "type"` — sealed-class discriminator matches the
 *    `@SerialName` on [com.rumor.mesh.core.model.GossipPacket] subclasses.
 *
 * **All wire I/O must route through this instance.** Any other `Json {}` block
 * in code that touches wire bytes is a forward-compat landmine — its config
 * choices (strict parsing, omitted defaults, alternate discriminators) silently
 * break interop with current and future Rumor versions. CI grep-fails any
 * `Json {` outside this file.
 *
 * Internal serialization (the simulator's dashboard JSON, on-disk diagnostic
 * dumps, anything that never travels between Rumor nodes) may use a separate
 * `Json` instance — `WireJson` is reserved for the wire format itself.
 */
@OptIn(ExperimentalSerializationApi::class)
val WireJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    classDiscriminator = "type"
}
