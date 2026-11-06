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
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O234 — a PRIORITY_LINK_ACCEPT is honoured only against a request this node
 * sent. Priority peers get persistent-link retention, so an unsolicited ACCEPT
 * would let any node promote itself. The forged ACCEPT is a genuinely signed
 * message built with composeOutbound — what a hostile client sends — so only
 * the receive-side check can refuse it.
 */
class PriorityLinkAcceptBindingTest {

    private suspend fun SimNode.isPriority(userId: String) =
        contactRepo.getById(userId)?.isPriorityPeer == true

    @Test
    fun `an unsolicited ACCEPT does not make its sender a priority peer`() = runBlocking<Unit> {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val a = SimNode(0, scope)
            val m = SimNode(1, scope)

            val forged = m.gossipEngine.composeOutbound(
                type = MessageType.PRIORITY_LINK_ACCEPT,
                payload = MessagePayload(ContentType.CONTROL, ""),
                recipientId = a.userId,
            ) ?: error("composeOutbound returned null")
            m.flushSchedulerToRepo()

            SimTransport(m, a).exchange(kotlin.random.Random(1))
            // Positive control: the ACCEPT arrived and M is a known contact, so
            // "not priority" is A refusing it, not the message or contact missing.
            awaitUntil(what = "forged ACCEPT reaching A") { a.knownMessages().any { it.id == forged.id } }
            awaitUntil(what = "M known as a contact on A") { a.contactRepo.getById(m.userId) != null }
            delay(300)

            assertTrue("an unsolicited ACCEPT must not grant priority", !a.isPriority(m.userId))
            assertThrows(AssertionError::class.java) {
                runBlocking { assertTrue("teeth", a.isPriority(m.userId)) }
            }
        } finally {
            scope.cancel()
        }
    }

    /** Discrimination control: the defence is not a blackhole for the real handshake. */
    @Test
    fun `an ACCEPT answering our own request does grant priority`() = runBlocking<Unit> {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val a = SimNode(0, scope)
            val b = SimNode(1, scope)

            val request = a.gossipEngine.composePriorityLinkRequest(b.userId)
                ?: error("composePriorityLinkRequest returned null")
            a.flushSchedulerToRepo()
            SimTransport(a, b).exchange(kotlin.random.Random(2))
            awaitUntil(what = "request reaching B") { b.knownMessages().any { it.id == request.id } }

            b.gossipEngine.acceptPriorityLink(b.knownMessages().first { it.id == request.id })
            awaitUntil(what = "B marking A priority") { b.isPriority(a.userId) }
            b.flushSchedulerToRepo()

            SimTransport(b, a).exchange(kotlin.random.Random(3))
            awaitUntil(what = "A marking B priority after B's ACCEPT") { a.isPriority(b.userId) }
        } finally {
            scope.cancel()
        }
    }
}
