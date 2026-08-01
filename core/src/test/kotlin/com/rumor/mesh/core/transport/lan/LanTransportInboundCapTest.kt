package com.rumor.mesh.core.transport.lan

import com.rumor.mesh.core.crypto.CryptoManager
import com.rumor.mesh.core.crypto.CryptoManager.toBase64
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O166 — the inbound accept loop must bound pre-HELLO concurrency so an
 * unauthenticated flooder can't force unbounded Ed25519-verification work.
 * All loopback connections share source 127.0.0.1, so the per-source cap is
 * the binding constraint here: opening many raw sockets that never send HELLO,
 * only [MAX_INBOUND_PER_SOURCE] may be admitted concurrently; the rest are
 * closed immediately by the server.
 *
 * An admitted connection is handed to GossipSession, which sends its HELLO
 * immediately — so the client sees a readable byte. A rejected connection is
 * closed by the server before any HELLO, so the client reads EOF (-1). That
 * difference classifies each connection without waiting on the 5s HELLO timeout.
 */
class LanTransportInboundCapTest {

    private fun transport(): LanTransport {
        val keys = CryptoManager.generateEd25519KeyPair()
        return LanTransport(
            LanTransport.Config(
                localUserId = CryptoManager.publicKeyToUserId(keys.publicKeyBytes),
                localPublicKey = keys.publicKeyBytes.toBase64(),
                signer = { bytes -> CryptoManager.sign(bytes, keys.privateKeyBytes) },
                messageProvider = { emptyList() },
                knownIdsProvider = { emptySet() },
                onlineUsersProvider = { emptyMap() },
            ),
        )
    }

    @Test
    fun `per-source cap bounds concurrent unauthenticated inbound connections`() = runBlocking {
        val loop = InetAddress.getByName("127.0.0.1")
        val t = transport()
        t.start(loop)
        try {
            // Wait for the server socket to bind.
            var port = -1
            repeat(50) { if (port <= 0) { port = t.boundPort() ?: -1; if (port <= 0) delay(20) } }
            assertTrue("server must bind", port > 0)

            val attempts = LanTransport.MAX_INBOUND_PER_SOURCE + 4
            val sockets = (0 until attempts).map {
                Socket().apply { connect(InetSocketAddress(loop, port), 1000) }
            }
            // Let the accept loop admit/reject them.
            delay(300)

            // Classify: an admitted socket receives the server's HELLO immediately
            // (read returns a byte >= 0); a rejected one is closed before any HELLO
            // (read returns EOF, -1).
            var admitted = 0
            withContext(Dispatchers.IO) {
                for (s in sockets) {
                    s.soTimeout = 800
                    val firstByte = try {
                        s.getInputStream().read()
                    } catch (e: SocketTimeoutException) {
                        -2 // neither HELLO nor close within the window — treat as not-admitted
                    } catch (e: Exception) {
                        -1
                    }
                    if (firstByte >= 0) admitted++
                    runCatching { s.close() }
                }
            }

            assertTrue(
                "at most MAX_INBOUND_PER_SOURCE concurrent inbound may be admitted " +
                    "from one source (admitted=$admitted, cap=${LanTransport.MAX_INBOUND_PER_SOURCE})",
                admitted <= LanTransport.MAX_INBOUND_PER_SOURCE,
            )
            // Discrimination: the cap admits a real connection, it's not a blackhole.
            assertTrue("the cap must admit at least one connection", admitted >= 1)
        } finally {
            t.stop()
        }
    }
}
