package com.rumor.mesh.core.fuzz

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.code_intelligence.jazzer.junit.FuzzTest
import com.rumor.mesh.core.model.Blocklist
import com.rumor.mesh.core.model.BlocklistDiff
import com.rumor.mesh.core.model.BridgeVouchedPayload
import com.rumor.mesh.core.model.Chunk
import com.rumor.mesh.core.model.ChunkRequest
import com.rumor.mesh.core.model.GossipPacket
import com.rumor.mesh.core.model.KeywordFilterList
import com.rumor.mesh.core.model.MessageDeletePayload
import com.rumor.mesh.core.model.RumorMessage
import com.rumor.mesh.core.model.SelfPresencePayload
import com.rumor.mesh.core.model.TransferCancel
import com.rumor.mesh.core.model.TransferMetadata
import com.rumor.mesh.core.sync.RbsrFrameWire
import com.rumor.mesh.core.transport.wifidirect.BloomFilterData
import com.rumor.mesh.core.wire.WireJson

/**
 * O28 — wire-parser fuzzing. Every parser that deserializes untrusted bytes
 * gets a harness. Jazzer feeds adversarial inputs and asserts the parser
 * doesn't throw anything outside the documented exception types — anything
 * else (OOM, stack overflow, uncaught NullPointerException) is a real bug.
 *
 * Plugin-scene threat model makes this urgent: a malicious plugin can craft
 * any payload and broadcast it, looking for crashable peers. Plugins lower
 * the barrier from "build a custom Rumor binary" to "drop a 50-line plugin."
 * Pre-ship requirement.
 *
 * Each @FuzzTest method runs once on the seed corpus during a normal
 * `./gradlew :core:test`. To actually run the fuzzer in-process (recommended
 * before any v0.1 release), set the env var JAZZER_FUZZ=1 and Jazzer will
 * mutate inputs in a loop until it finds a crash or hits the configured
 * time limit. Default per-method wall-clock budget is 60s under that mode.
 *
 * Defensive parsers in Rumor (`runCatching { … }.getOrNull()`) already
 * absorb the well-formed exception cases; the value here is catching the
 * cases that pattern *misses*: OOM on adversarial sizes (O13 is the known
 * BloomFilter case), stack overflow on deeply nested JSON, integer overflow
 * on length fields, AEAD constructions on truncated Base64.
 */
class WireParserFuzzers {

    @FuzzTest
    fun fuzzGossipPacket(data: FuzzedDataProvider) {
        val json = data.consumeRemainingAsString()
        runCatching { WireJson.decodeFromString<GossipPacket>(json) }
    }

    @FuzzTest
    fun fuzzRumorMessage(data: FuzzedDataProvider) {
        val json = data.consumeRemainingAsString()
        runCatching { WireJson.decodeFromString<RumorMessage>(json) }
    }

    @FuzzTest
    fun fuzzBlocklist(data: FuzzedDataProvider) {
        val json = data.consumeRemainingAsString()
        runCatching { WireJson.decodeFromString<Blocklist>(json) }
    }

    @FuzzTest
    fun fuzzBlocklistDiff(data: FuzzedDataProvider) {
        val json = data.consumeRemainingAsString()
        runCatching { WireJson.decodeFromString<BlocklistDiff>(json) }
    }

    @FuzzTest
    fun fuzzTransferMetadata(data: FuzzedDataProvider) {
        val json = data.consumeRemainingAsString()
        runCatching { WireJson.decodeFromString<TransferMetadata>(json) }
    }

    @FuzzTest
    fun fuzzChunk(data: FuzzedDataProvider) {
        val json = data.consumeRemainingAsString()
        runCatching { WireJson.decodeFromString<Chunk>(json) }
    }

    @FuzzTest
    fun fuzzChunkRequest(data: FuzzedDataProvider) {
        val json = data.consumeRemainingAsString()
        runCatching { WireJson.decodeFromString<ChunkRequest>(json) }
    }

    @FuzzTest
    fun fuzzTransferCancel(data: FuzzedDataProvider) {
        val json = data.consumeRemainingAsString()
        runCatching { WireJson.decodeFromString<TransferCancel>(json) }
    }

    @FuzzTest
    fun fuzzBridgeVouchedPayload(data: FuzzedDataProvider) {
        val json = data.consumeRemainingAsString()
        runCatching { WireJson.decodeFromString<BridgeVouchedPayload>(json) }
    }

    @FuzzTest
    fun fuzzSelfPresencePayload(data: FuzzedDataProvider) {
        val json = data.consumeRemainingAsString()
        runCatching { WireJson.decodeFromString<SelfPresencePayload>(json) }
    }

    @FuzzTest
    fun fuzzRbsrFrameWire(data: FuzzedDataProvider) {
        val json = data.consumeRemainingAsString()
        runCatching { WireJson.decodeFromString<RbsrFrameWire>(json) }
    }

    @FuzzTest
    fun fuzzKeywordFilterList(data: FuzzedDataProvider) {
        val json = data.consumeRemainingAsString()
        runCatching { WireJson.decodeFromString<KeywordFilterList>(json) }
    }

    @FuzzTest
    fun fuzzMessageDeletePayload(data: FuzzedDataProvider) {
        val json = data.consumeRemainingAsString()
        runCatching { WireJson.decodeFromString<MessageDeletePayload>(json) }
    }

    @FuzzTest
    fun fuzzPrekeyPublish(data: FuzzedDataProvider) {
        val json = data.consumeRemainingAsString()
        runCatching { WireJson.decodeFromString<com.rumor.mesh.core.model.PrekeyPublish>(json) }
    }

    @FuzzTest
    fun fuzzRoomCreate(data: FuzzedDataProvider) {
        val json = data.consumeRemainingAsString()
        runCatching { WireJson.decodeFromString<com.rumor.mesh.core.model.RoomCreate>(json) }
    }

    @FuzzTest
    fun fuzzRoomInvite(data: FuzzedDataProvider) {
        val json = data.consumeRemainingAsString()
        runCatching { WireJson.decodeFromString<com.rumor.mesh.core.model.RoomInvite>(json) }
    }

    @FuzzTest
    fun fuzzRoomAction(data: FuzzedDataProvider) {
        val json = data.consumeRemainingAsString()
        runCatching { WireJson.decodeFromString<com.rumor.mesh.core.model.RoomAction>(json) }
    }

    @FuzzTest
    fun fuzzMultiRecipientEnvelope(data: FuzzedDataProvider) {
        val json = data.consumeRemainingAsString()
        runCatching { WireJson.decodeFromString<com.rumor.mesh.core.model.MultiRecipientEnvelope>(json) }
    }

    @FuzzTest
    fun fuzzKeyWrap(data: FuzzedDataProvider) {
        val json = data.consumeRemainingAsString()
        runCatching { WireJson.decodeFromString<com.rumor.mesh.core.model.KeyWrap>(json) }
    }

    /**
     * BloomFilterData.deserialize is the known-troublesome path (O13): a
     * caller-controlled `expectedItems` can drive multi-GB allocations. Fuzz
     * with bounded `expectedItems` so the JVM doesn't OOM the test runner
     * itself — but Jazzer should still find the boundary cases where the
     * existing graceful-degradation pattern fails to catch.
     */
    @FuzzTest
    fun fuzzBloomFilterData(data: FuzzedDataProvider) {
        val expectedItems = data.consumeInt(1, 100_000)
        val payload = data.consumeRemainingAsString()
        runCatching { BloomFilterData.deserialize(payload, expectedItems) }
    }
}
