package com.rumor.mesh.plugin.fuzz

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.code_intelligence.jazzer.junit.FuzzTest
import com.rumor.mesh.plugin.meshcore.MeshCoreFrames
import com.rumor.mesh.plugin.meshtastic.MeshtasticMessages

/**
 * O28 — fuzz the Meshtastic and MeshCore protobuf/frame decoders.
 *
 * These are the highest-risk untrusted-bytes surfaces in the app: a
 * malicious or buggy LoRa radio (or a malicious plugin upstream of one)
 * can feed any byte pattern to these decoders. Unlike the JSON parsers
 * in :core, the protobuf paths here are hand-rolled — varint length
 * fields, opaque opcodes, partial frames — exactly the shape where
 * length confusion and integer overflow live.
 *
 * Each @FuzzTest runs once on a Jazzer-seeded corpus during a normal
 * `:app:testDebugUnitTest`; `JAZZER_FUZZ=1` puts them into in-process
 * mutating-fuzz mode. Crashes here (StackOverflowError, OOM, unchecked
 * NPE) are real bugs — the production codec is supposed to swallow any
 * malformed input as `null`.
 */
class BridgeCodecFuzzers {

    @FuzzTest
    fun fuzzMeshtasticFromRadio(data: FuzzedDataProvider) {
        val bytes = data.consumeRemainingAsBytes()
        // Catch Exception, NOT Throwable. The Meshtastic decoder legitimately
        // throws on malformed input and prod wraps it in runCatching
        // (MeshtasticBridge:97), so ordinary exceptions are its contract — but a
        // StackOverflowError / OutOfMemoryError from length confusion is a real
        // bug, and the old runCatching{} swallowed those too, so JAZZER_FUZZ=1
        // could never report one. Errors now propagate to Jazzer.
        try { MeshtasticMessages.decodeFromRadioPacket(bytes) } catch (e: Exception) {}
    }

    @FuzzTest
    fun fuzzMeshCoreChannelMessage(data: FuzzedDataProvider) {
        val bytes = data.consumeRemainingAsBytes()
        // MeshCore's prod call site does NOT wrap the decoder (MeshCoreBridge:139),
        // so it must be total — return null or a message, never throw. Let ANY
        // throwable surface to Jazzer; an exception here is a real prod crash
        // (a bridge collector throw takes down the process — see O169).
        MeshCoreFrames.decodeChannelMessage(bytes)
    }
}
