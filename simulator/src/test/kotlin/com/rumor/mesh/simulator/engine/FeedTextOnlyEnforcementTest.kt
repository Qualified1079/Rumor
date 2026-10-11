package com.rumor.mesh.simulator.engine

import com.rumor.mesh.core.model.ContentType
import com.rumor.mesh.core.model.MessagePayload
import com.rumor.mesh.core.model.MessageType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O36/O186 — "the global feed is text-only, and the RELAY drops media destined for
 * it before enqueue." That rule is the one hard protocol constraint O36 records,
 * and the 2026-07-24 audit found it had **no implementation at all**: it was
 * non-exploitable only by omission, because `composeRoomMessage` takes a String
 * and hardcodes `ContentType.TEXT`. Omission is not enforcement — the moment any
 * media-to-feed path exists there was nothing to intercept it.
 *
 * A drop rather than a display filter, because the failure mode the rule exists
 * for — media-borne illegal content in an unmoderated global stream at a scale no
 * creator-mod can police — is not fixed by a client declining to render it.
 *
 * The attack message here is composed through `composeOutbound`, so it is
 * **genuinely signed**: a `.copy(payload = …)` on a composed room message would
 * break the Ed25519 transcript (O157 binds the room tag, and the payload was
 * always signed) and the drop would then prove nothing but that signature
 * verification works. The TEXT control is built through the identical path and
 * differs ONLY in contentType, which is what rules that out.
 *
 * The enforcement point deliberately does not read the routing tag — it is a
 * (type, contentType) check ahead of all room dispatch — so the attack needs no
 * tag to exercise it.
 */
class FeedTextOnlyEnforcementTest {

    @Test
    fun `a signed non-TEXT room message is dropped and never relayed onward`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val attacker = SimNode(0, scope)
            val relay = SimNode(1, scope)
            val downstream = SimNode(2, scope)

            val media = attacker.gossipEngine.composeOutbound(
                type = MessageType.ROOM_MESSAGE,
                payload = MessagePayload(ContentType.IMAGE, "ZmFrZS1qcGVnLWJ5dGVz"),
            ) ?: error("composeOutbound null")
            val text = attacker.gossipEngine.composeOutbound(
                type = MessageType.ROOM_MESSAGE,
                payload = MessagePayload(ContentType.TEXT, "ordinary feed post"),
            ) ?: error("composeOutbound null")
            attacker.flushSchedulerToRepo()

            SimTransport(attacker, relay).exchange(kotlin.random.Random(1))

            // Control first: the TEXT twin must be accepted and stored. Identical
            // construction, identical signing path, only contentType differs — so if
            // this passes and the media one is absent, contentType is the cause and
            // not a signature or transport failure.
            awaitUntil(what = "the relay to accept the TEXT room message") {
                relay.messageRepo.getById(text.id) != null
            }
            assertFalse(
                "a signed IMAGE-payload room message must be dropped at the relay",
                relay.messageRepo.getById(media.id) != null,
            )

            // ...and dropped BEFORE enqueue, which is what O36 actually requires:
            // it must not reach a downstream node either. Relay the whole store on
            // and check the media never appears.
            relay.flushSchedulerToRepo()
            repeat(3) {
                SimTransport(relay, downstream).exchange(kotlin.random.Random(it + 2))
                delay(30)
            }
            awaitUntil(what = "the TEXT message to reach the downstream node") {
                downstream.messageRepo.getById(text.id) != null
            }
            assertFalse(
                "the media room message must not be relayed onward — O36 requires the " +
                    "drop before enqueue, not merely a local display filter",
                downstream.messageRepo.getById(media.id) != null,
            )

            // And it must not have been handed to the local inbox either.
            assertTrue(
                "the dropped media message must not be in the relay's known-id set",
                media.id !in relay.gossipEngine.knownMessageIds(),
            )
        } finally {
            scope.cancel()
        }
    }
}
