package com.rumor.mesh.simulator.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the O29 routing bias in `GossipEngine.messagesForExchange`. After
 * an A → B → C broadcast leg records the C-side breadcrumb "to reach A, go
 * via B," any DM from C addressed to A must appear ahead of unrelated
 * traffic in the batch offered to peer B.
 *
 * This is the *offer-ordering* half of O29 — bias only, never exclusion.
 * The simulator exercise here is purely functional: assertion is on the
 * order of `messagesForExchange(b.userId)` from C's side.
 */
class BreadcrumbBiasTest {

    @Test
    fun `DM whose breadcrumb points to peer floats to front of offer batch`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val a = SimNode(0, scope)
        val b = SimNode(1, scope)
        val c = SimNode(2, scope)

        // Phase 1: A broadcasts; A→B→C lays a breadcrumb on C.
        a.gossipEngine.composeBroadcast("hello")!!
        a.flushSchedulerToRepo()
        SimTransport(a, b).exchange(kotlin.random.Random(1))
        awaitUntil { b.knownMessages().isNotEmpty() }
        b.flushSchedulerToRepo()
        SimTransport(b, c).exchange(kotlin.random.Random(2))
        awaitUntil { c.breadcrumbs.candidatePeersSync(a.userId).isNotEmpty() }
        assertEquals(
            "Breadcrumb substrate must record A↔B on C before the bias test runs",
            listOf(b.userId), c.breadcrumbs.candidatePeersSync(a.userId),
        )

        // Phase 2: C composes a DM to A (routed) and several broadcasts
        // (no recipient → never routed). C does NOT exchange yet.
        // We need A's public key on file at C to compose the DM. The simplest
        // route is to register A's identity in C's contact repo manually.
        registerContact(c, a)
        val dmToA = c.gossipEngine.composeDirect(
            recipientId = a.userId,
            recipientPublicKey = a.identityProvider.identity.value!!.publicKeyBytes,
            text = "private note",
        ) ?: error("composeDirect returned null — recipient pubkey missing?")

        val noise = (0 until 5).map { c.gossipEngine.composeBroadcast("noise $it")!! }

        // Phase 3: ask C for the batch it would offer peer B. Do NOT flush the
        // scheduler first — messagesForExchange reads (and destructively drains)
        // the scheduler directly; flushing would empty it before the query.
        val offer = c.gossipEngine.messagesForExchange(b.userId)
        assertTrue("Offer must contain the DM", offer.any { it.id == dmToA.id })

        val dmIndex = offer.indexOfFirst { it.id == dmToA.id }
        val firstNoiseIndex = offer.indexOfFirst { it.id in noise.map { n -> n.id } }
        assertTrue(
            "DM addressed to A (breadcrumb → B) must float ahead of unrelated " +
                "broadcasts when offered to peer B " +
                "(dm at $dmIndex, first noise at $firstNoiseIndex)",
            dmIndex >= 0 && dmIndex < firstNoiseIndex,
        )
    }

    /** Inject [other]'s identity into [host]'s contact repo so composeDirect can find the pubkey. */
    private suspend fun registerContact(host: SimNode, other: SimNode) {
        val otherIdentity = other.identityProvider.identity.value!!
        host.contactRepoForTest().upsert(
            com.rumor.mesh.core.model.Contact(
                userId = otherIdentity.userId,
                publicKey = com.rumor.mesh.core.crypto.CryptoManager.run { otherIdentity.publicKeyBytes.toBase64() },
                displayName = "n${other.index}",
                isVerified = false,
                autoRelay = false,
                alwaysSave = false,
                willingToCache = false,
                firstSeenMs = 0L,
                lastSeenMs = System.currentTimeMillis(),
                isPriorityPeer = false,
            )
        )
    }

    /** Test-only accessor for the in-memory contact repo. */
    private fun SimNode.contactRepoForTest(): com.rumor.mesh.core.data.memory.InMemoryContactRepository =
        this::class.java.getDeclaredField("contactRepo").apply { isAccessible = true }
            .get(this) as com.rumor.mesh.core.data.memory.InMemoryContactRepository
}
