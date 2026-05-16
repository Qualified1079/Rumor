package com.rumor.mesh.plugin

import org.junit.Assert.*
import org.junit.Test

class DmEnvelopeRegistryTest {

    private fun makeEnvelope(prefix: String, id: String = "env-$prefix") = object : DmEnvelope {
        override val recipientPrefix = prefix
        override val envelopeId = id
        override val selfAuthenticating = true
        override fun encrypt(recipientUserId: String, recipientPubKey: ByteArray, plaintext: ByteArray) = plaintext
        override fun decrypt(senderUserId: String, senderPubKey: ByteArray, ciphertext: ByteArray) = ciphertext
    }

    @Test
    fun `forRecipient returns null when registry is empty`() {
        assertNull(DmEnvelopeRegistry().forRecipient("meshtastic:abc"))
    }

    @Test
    fun `forRecipient matches on prefix`() {
        val r = DmEnvelopeRegistry()
        val env = makeEnvelope("meshtastic:")
        r.register(env)
        assertSame(env, r.forRecipient("meshtastic:00aabbcc"))
    }

    @Test
    fun `forRecipient returns null for non-matching prefix`() {
        val r = DmEnvelopeRegistry()
        r.register(makeEnvelope("meshtastic:"))
        assertNull(r.forRecipient("meshcore:abc"))
    }

    @Test
    fun `register throws on prefix collision`() {
        val r = DmEnvelopeRegistry()
        r.register(makeEnvelope("meshtastic:", "env-a"))
        try {
            r.register(makeEnvelope("meshtastic:", "env-b"))
            fail("Expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("meshtastic:") == true)
        }
    }

    @Test
    fun `unregister makes prefix available again`() {
        val r = DmEnvelopeRegistry()
        r.register(makeEnvelope("meshtastic:", "env-a"))
        r.unregister("meshtastic:")
        // Should not throw after unregister
        r.register(makeEnvelope("meshtastic:", "env-b"))
        assertNotNull(r.forRecipient("meshtastic:123"))
    }

    @Test
    fun `unregister is idempotent`() {
        val r = DmEnvelopeRegistry()
        r.unregister("nonexistent:")  // should not throw
    }

    @Test
    fun `multiple prefixes coexist`() {
        val r = DmEnvelopeRegistry()
        val mesh = makeEnvelope("meshtastic:")
        val core = makeEnvelope("meshcore:")
        r.register(mesh)
        r.register(core)
        assertSame(mesh, r.forRecipient("meshtastic:abc"))
        assertSame(core, r.forRecipient("meshcore:xyz"))
    }

    @Test
    fun `concurrent registration and lookup does not throw`() {
        val r = DmEnvelopeRegistry()
        val threads = (1..8).map { i ->
            Thread {
                try {
                    r.register(makeEnvelope("prefix$i:"))
                } catch (_: IllegalStateException) { /* duplicate — acceptable */ }
                repeat(100) { r.forRecipient("prefix$i:target") }
                r.unregister("prefix$i:")
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
    }
}
