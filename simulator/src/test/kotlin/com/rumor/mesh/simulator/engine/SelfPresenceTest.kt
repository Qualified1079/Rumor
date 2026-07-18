package com.rumor.mesh.simulator.engine

import com.rumor.mesh.core.model.MessageType
import com.rumor.mesh.core.model.SelfPresencePayload
import com.rumor.mesh.core.model.UserMode
import com.rumor.mesh.core.wire.WireJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * O30 + O57. Confirms self-presence beacons compose with the right type,
 * propagate as INFRASTRUCTURE-class broadcasts, and round-trip the mode
 * field through the wire envelope. Entry and exit pulses use the same
 * primitive — exit is just a fresh beacon with mode = MOBILE.
 */
class SelfPresenceTest {

    @Test
    fun `entry pulse composes with mode=FREE and propagates over one hop`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val a = SimNode(0, scope)
        val b = SimNode(1, scope)

        val beacon = a.gossipEngine.composeSelfPresence(UserMode.FREE)
        assertNotNull("composeSelfPresence should return a message", beacon)
        assertEquals(MessageType.SELF_PRESENCE, beacon!!.type)
        a.flushSchedulerToRepo()

        SimTransport(a, b).exchange(kotlin.random.Random(1))
        awaitUntil { beacon.id in b.gossipEngine.knownMessageIds() }

        // New contract (echo-loop fix): the beacon is verified and dedup-known,
        // feeds the receiver's mesh view, but is ephemeral — never archived.
        assert(beacon.id in b.gossipEngine.knownMessageIds()) {
            "B must have verified and recorded A's entry pulse"
        }
        assert(b.knownMessages().none { it.id == beacon.id }) {
            "SELF_PRESENCE must not be persisted in B's message store"
        }
        assertEquals(
            "A's declared mode must land in B's assembled mesh view",
            UserMode.FREE,
            b.meshView.assembleView("b", UserMode.MOBILE, emptyList()).modes[a.userId],
        )
    }

    @Test
    fun `exit pulse is a fresh beacon with mode=MOBILE`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val a = SimNode(0, scope)

        val entry = a.gossipEngine.composeSelfPresence(UserMode.FREE)!!
        val exit = a.gossipEngine.composeSelfPresence(UserMode.MOBILE)!!

        // Distinct message IDs — exit is a fresh signed message, not an amendment.
        assertNotNull(entry.id)
        assertNotNull(exit.id)
        assert(entry.id != exit.id) {
            "Entry and exit pulses must be distinct signed messages"
        }
        val entryMode = WireJson.decodeFromString<SelfPresencePayload>(entry.payload!!.content).mode
        val exitMode = WireJson.decodeFromString<SelfPresencePayload>(exit.payload!!.content).mode
        assertEquals(UserMode.FREE, entryMode)
        assertEquals(UserMode.MOBILE, exitMode)
    }

    @Test
    fun `route-advertisement field is empty by default`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val a = SimNode(0, scope)

        val beacon = a.gossipEngine.composeSelfPresence(UserMode.STATIC)!!
        val payload = WireJson.decodeFromString<SelfPresencePayload>(beacon.payload!!.content)
        assertEquals(
            "Default beacon must not advertise recentlyExchangedWith (O58 Tier 3 opt-in)",
            emptyList<String>(), payload.recentlyExchangedWith,
        )
        assertNull("`_ext` reserved field is null by default", payload.ext)
    }
}
