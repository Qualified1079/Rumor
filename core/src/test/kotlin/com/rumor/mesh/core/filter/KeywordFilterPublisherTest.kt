package com.rumor.mesh.core.filter

import com.rumor.mesh.core.crypto.CryptoManager
import com.rumor.mesh.core.identity.IdentityProvider
import com.rumor.mesh.core.identity.LocalIdentity
import com.rumor.mesh.core.model.FilterAction
import com.rumor.mesh.core.model.FilterEntry
import com.rumor.mesh.core.model.KeywordFilterList
import com.rumor.mesh.core.model.MatchKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeywordFilterPublisherTest {

    private class FakeIdentity(initial: LocalIdentity?) : IdentityProvider {
        private val flow = MutableStateFlow(initial)
        override val identity: StateFlow<LocalIdentity?> = flow
        override val isUnlocked: Boolean get() = flow.value != null
    }

    private fun newIdentity(): LocalIdentity {
        val (priv, pub) = CryptoManager.generateEd25519KeyPair()
        return LocalIdentity(
            userId = CryptoManager.publicKeyToUserId(pub),
            deviceId = "dev-1",
            publicKeyBytes = pub,
            privateKeyBytes = priv,
        )
    }

    @Test
    fun `publish returns null when identity is locked`() {
        val publisher = KeywordFilterPublisher(FakeIdentity(null))
        val result = publisher.publish(
            name = "test",
            entries = listOf(FilterEntry("foo", FilterAction.WARN)),
        )
        assertNull(result)
    }

    @Test
    fun `publish round-trips through verifier`() {
        val id = newIdentity()
        val publisher = KeywordFilterPublisher(FakeIdentity(id))
        val list = publisher.publish(
            name = "my-list",
            entries = listOf(
                FilterEntry("badword", FilterAction.BLOCK),
                FilterEntry("nsfw", FilterAction.WARN, MatchKind.WORD_CI, warnLabel = "sexual"),
            ),
            userIdAllowlist = setOf("trusted-friend"),
        )
        assertNotNull(list)
        assertEquals(id.userId, list.publisherId)
        assertEquals("my-list", list.name)
        assertEquals(2, list.entries.size)
        assertTrue(KeywordFilterVerifier.verify(list, id.publicKeyBytes))
    }

    @Test
    fun `verifier rejects tampered entries`() {
        val id = newIdentity()
        val publisher = KeywordFilterPublisher(FakeIdentity(id))
        val list = publisher.publish(
            name = "test",
            entries = listOf(FilterEntry("foo", FilterAction.WARN)),
        )
        assertNotNull(list)
        // Tamper: add an entry without re-signing.
        val tampered = list.copy(entries = list.entries + FilterEntry("evil", FilterAction.BLOCK))
        assertFalse(KeywordFilterVerifier.verify(tampered, id.publicKeyBytes))
    }

    @Test
    fun `verifier rejects tampered name`() {
        val id = newIdentity()
        val publisher = KeywordFilterPublisher(FakeIdentity(id))
        val list = publisher.publish(name = "original", entries = listOf(FilterEntry("x", FilterAction.WARN)))
        assertNotNull(list)
        val tampered = list.copy(name = "renamed")
        assertFalse(KeywordFilterVerifier.verify(tampered, id.publicKeyBytes))
    }

    @Test
    fun `verifier rejects tampered allowlist`() {
        val id = newIdentity()
        val publisher = KeywordFilterPublisher(FakeIdentity(id))
        val list = publisher.publish(
            name = "test",
            entries = listOf(FilterEntry("x", FilterAction.BLOCK)),
            userIdAllowlist = setOf("a", "b"),
        )
        assertNotNull(list)
        // Add an attacker's userId to the allowlist without re-signing — they'd skip the filter.
        val tampered = list.copy(userIdAllowlist = list.userIdAllowlist + "attacker")
        assertFalse(KeywordFilterVerifier.verify(tampered, id.publicKeyBytes))
    }

    @Test
    fun `verifier rejects when publisherId does not match supplied public key`() {
        val id = newIdentity()
        val other = newIdentity()
        val publisher = KeywordFilterPublisher(FakeIdentity(id))
        val list = publisher.publish(name = "test", entries = listOf(FilterEntry("x", FilterAction.WARN)))
        assertNotNull(list)
        // Try to verify with someone else's public key.
        assertFalse(KeywordFilterVerifier.verify(list, other.publicKeyBytes))
    }

    @Test
    fun `verifier rejects malformed signature`() {
        val id = newIdentity()
        val publisher = KeywordFilterPublisher(FakeIdentity(id))
        val list = publisher.publish(name = "test", entries = listOf(FilterEntry("x", FilterAction.WARN)))
        assertNotNull(list)
        val tampered = list.copy(signature = "not-valid-base64-!!!")
        assertFalse(KeywordFilterVerifier.verify(tampered, id.publicKeyBytes))
    }

    @Test
    fun `monotonic version distinguishes successive publishes`() {
        val id = newIdentity()
        val publisher = KeywordFilterPublisher(FakeIdentity(id))
        val v1 = publisher.publish(name = "list", entries = emptyList(), version = 1000L)
        val v2 = publisher.publish(name = "list", entries = emptyList(), version = 2000L)
        assertNotNull(v1); assertNotNull(v2)
        assertTrue(v2.version > v1.version)
        // Both verify independently.
        assertTrue(KeywordFilterVerifier.verify(v1, id.publicKeyBytes))
        assertTrue(KeywordFilterVerifier.verify(v2, id.publicKeyBytes))
        // Signatures differ (version is part of the signed bytes).
        assertTrue(v1.signature != v2.signature)
    }
}
