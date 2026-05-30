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
        runCatching { MeshtasticMessages.decodeFromRadioPacket(bytes) }
    }

    @FuzzTest
    fun fuzzMeshCoreChannelMessage(data: FuzzedDataProvider) {
        val bytes = data.consumeRemainingAsBytes()
        runCatching { MeshCoreFrames.decodeChannelMessage(bytes) }
    }
}
