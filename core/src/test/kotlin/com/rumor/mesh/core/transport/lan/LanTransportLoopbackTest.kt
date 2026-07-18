package com.rumor.mesh.core.transport.lan

import com.rumor.mesh.core.crypto.CryptoManager
import com.rumor.mesh.core.crypto.CryptoManager.toBase64
import com.rumor.mesh.core.model.ContentType
import com.rumor.mesh.core.model.MessagePayload
import com.rumor.mesh.core.model.MessageType
import com.rumor.mesh.core.model.RumorMessage
import com.rumor.mesh.core.protocol.ExchangeSource
import com.rumor.mesh.core.protocol.PeerExchangeResult
import java.net.InetAddress
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * O93 — two [LanTransport]s on loopback with discovery injected via
 * [LanTransport.onPeerLocated] (multicast mDNS is environment-dependent; the
 * TCP session path and result mapping are what this pins). The mDNS layer
 * itself is verified on hardware.
 */
class LanTransportLoopbackTest {

    private class Node {
        val keys = CryptoManager.generateEd25519KeyPair()
        val userId = CryptoManager.publicKeyToUserId(keys.publicKeyBytes)
        val store = mutableListOf<RumorMessage>()
        val transport = LanTransport(
            LanTransport.Config(
                localUserId = userId,
                localPublicKey = keys.publicKeyBytes.toBase64(),
                signer = { bytes -> CryptoManager.sign(bytes, keys.privateKeyBytes) },
                messageProvider = { store.toList() },
                knownIdsProvider = { store.map { it.id }.toSet() },
                onlineUsersProvider = { emptyMap() },
            )
        )

        fun broadcast(id: String, text: String) = RumorMessage(
            id = id,
            senderId = userId,
            senderPublicKey = keys.publicKeyBytes.toBase64(),
            sequenceNumber = 1,
            sentAtMs = System.currentTimeMillis(),
            type = MessageType.BROADCAST,
            hopsToLive = 5,
            payload = MessagePayload(ContentType.TEXT, text),
            signature = "unverified-at-session-layer",
        )
    }

    @Test
    fun `two nodes exchange a message over loopback and report LAN source`() = runBlocking {
        val a = Node()
        val b = Node()
        a.store += a.broadcast("lan-msg-1", "hello over the LAN")

        val loopback = InetAddress.getLoopbackAddress()
        a.transport.start(loopback)
        b.transport.start(loopback)
        try {
            val aPort = awaitPort(a.transport)

            val got = CompletableDeferred<PeerExchangeResult>()
            val collector = launch {
                b.transport.exchangeResults.collect { if (!got.isCompleted) got.complete(it) }
            }
            b.transport.onPeerLocated(a.userId.take(16), loopback, aPort)

            val result = withTimeoutOrNull(15_000) { got.await() }
            collector.cancel()
            assertNotNull("exchange never completed", result)
            assertEquals(a.userId, result!!.peerUserId)
            assertEquals(ExchangeSource.LAN, result.source)
            assertEquals(listOf("lan-msg-1"), result.messagesReceived.map { it.id })
            assertEquals("hello over the LAN", result.messagesReceived.first().payload?.content)
        } finally {
            a.transport.stop()
            b.transport.stop()
        }
    }

    private suspend fun awaitPort(t: LanTransport): Int {
        repeat(100) {
            t.boundPort()?.let { return it }
            delay(50)
        }
        error("server socket never bound")
    }
}
