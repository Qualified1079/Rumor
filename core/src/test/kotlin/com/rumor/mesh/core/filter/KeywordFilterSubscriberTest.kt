package com.rumor.mesh.core.filter

import com.rumor.mesh.core.crypto.CryptoManager
import com.rumor.mesh.core.data.FilterSubscriptionRepository
import com.rumor.mesh.core.data.KeywordFilterListRepository
import com.rumor.mesh.core.identity.IdentityProvider
import com.rumor.mesh.core.identity.LocalIdentity
import com.rumor.mesh.core.model.FilterAction
import com.rumor.mesh.core.model.FilterEntry
import com.rumor.mesh.core.model.FilterSubscription
import com.rumor.mesh.core.model.FilterSubscriptionMode
import com.rumor.mesh.core.model.KeywordFilterList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeywordFilterSubscriberTest {

    private class FakeListRepo : KeywordFilterListRepository {
        private val store = mutableMapOf<String, KeywordFilterList>()
        override suspend fun upsert(list: KeywordFilterList) { store[list.publisherId] = list }
        override suspend fun get(publisherId: String) = store[publisherId]
        override suspend fun getAll() = store.values.toList()
        override suspend fun delete(publisherId: String) { store.remove(publisherId) }
    }

    private class FakeSubRepo : FilterSubscriptionRepository {
        private val store = mutableMapOf<String, FilterSubscription>()
        override suspend fun upsert(sub: FilterSubscription) { store[sub.listPublisherId] = sub }
        override suspend fun get(publisherId: String) = store[publisherId]
        override suspend fun getAll() = store.values.toList()
        override suspend fun delete(publisherId: String) { store.remove(publisherId) }
    }

    private class FakeIdentity(id: LocalIdentity?) : IdentityProvider {
        private val flow = MutableStateFlow(id)
        override val identity: StateFlow<LocalIdentity?> = flow
        override val isUnlocked: Boolean get() = flow.value != null
    }

    private fun newIdentity(): LocalIdentity {
        val (priv, pub) = CryptoManager.generateEd25519KeyPair()
        return LocalIdentity(CryptoManager.publicKeyToUserId(pub), "dev", pub, priv)
    }

    private fun newPublisher(id: LocalIdentity) = KeywordFilterPublisher(FakeIdentity(id))

    @Test
    fun `applyList without prior subscription is rejected`() = runTest {
        val (lr, sr) = FakeListRepo() to FakeSubRepo()
        val sub = KeywordFilterSubscriber(lr, sr)
        val id = newIdentity()
        val list = newPublisher(id).publish("test", listOf(FilterEntry("foo", FilterAction.WARN)))!!
        assertFalse(sub.applyList(list))
        assertNull(lr.get(id.userId))
    }

    @Test
    fun `subscribe then applyList stores the list and bumps version`() = runTest {
        val (lr, sr) = FakeListRepo() to FakeSubRepo()
        val sub = KeywordFilterSubscriber(lr, sr)
        val id = newIdentity()
        sub.subscribe(id.userId, id.publicKeyBytes, "test")
        val list = newPublisher(id).publish("test", listOf(FilterEntry("foo", FilterAction.WARN)), version = 100L)!!
        assertTrue(sub.applyList(list))
        assertNotNull(lr.get(id.userId))
        assertEquals(100L, sr.get(id.userId)!!.lastAppliedVersion)
    }

    @Test
    fun `stale version is rejected`() = runTest {
        val (lr, sr) = FakeListRepo() to FakeSubRepo()
        val sub = KeywordFilterSubscriber(lr, sr)
        val id = newIdentity()
        sub.subscribe(id.userId, id.publicKeyBytes, "test")
        val pub = newPublisher(id)
        val v1 = pub.publish("test", listOf(FilterEntry("x", FilterAction.WARN)), version = 200L)!!
        val v0 = pub.publish("test", listOf(FilterEntry("y", FilterAction.WARN)), version = 100L)!!
        assertTrue(sub.applyList(v1))
        assertFalse(sub.applyList(v0), "v=100 should be rejected after v=200 applied")
        // The stored list is still v1's content.
        assertEquals("x", lr.get(id.userId)!!.entries.first().pattern)
    }

    @Test
    fun `equal version is rejected (strict gt only)`() = runTest {
        val (lr, sr) = FakeListRepo() to FakeSubRepo()
        val sub = KeywordFilterSubscriber(lr, sr)
        val id = newIdentity()
        sub.subscribe(id.userId, id.publicKeyBytes, "test")
        val list = newPublisher(id).publish("test", emptyList(), version = 100L)!!
        assertTrue(sub.applyList(list))
        // Same version, different content — must be rejected (replay protection).
        val same = newPublisher(id).publish("test", listOf(FilterEntry("x", FilterAction.BLOCK)), version = 100L)!!
        assertFalse(sub.applyList(same))
    }

    @Test
    fun `ONE_TIME subscription accepts first list then rejects updates`() = runTest {
        val (lr, sr) = FakeListRepo() to FakeSubRepo()
        val sub = KeywordFilterSubscriber(lr, sr)
        val id = newIdentity()
        sub.subscribe(id.userId, id.publicKeyBytes, "test", FilterSubscriptionMode.ONE_TIME)
        val pub = newPublisher(id)
        val v1 = pub.publish("test", listOf(FilterEntry("a", FilterAction.WARN)), version = 100L)!!
        assertTrue(sub.applyList(v1))
        val v2 = pub.publish("test", listOf(FilterEntry("b", FilterAction.WARN)), version = 200L)!!
        assertFalse(sub.applyList(v2), "ONE_TIME should reject any subsequent update")
        assertEquals("a", lr.get(id.userId)!!.entries.first().pattern)
    }

    @Test
    fun `tampered list is rejected at signature verify`() = runTest {
        val (lr, sr) = FakeListRepo() to FakeSubRepo()
        val sub = KeywordFilterSubscriber(lr, sr)
        val id = newIdentity()
        sub.subscribe(id.userId, id.publicKeyBytes, "test")
        val list = newPublisher(id).publish("test", listOf(FilterEntry("x", FilterAction.WARN)))!!
        val tampered = list.copy(entries = list.entries + FilterEntry("evil", FilterAction.BLOCK))
        assertFalse(sub.applyList(tampered))
        assertNull(lr.get(id.userId))
    }

    @Test
    fun `unsubscribe drops the list and the subscription record`() = runTest {
        val (lr, sr) = FakeListRepo() to FakeSubRepo()
        val sub = KeywordFilterSubscriber(lr, sr)
        val id = newIdentity()
        sub.subscribe(id.userId, id.publicKeyBytes, "test")
        sub.applyList(newPublisher(id).publish("test", emptyList(), version = 1L)!!)
        sub.unsubscribe(id.userId)
        assertNull(lr.get(id.userId))
        assertNull(sr.get(id.userId))
    }

    @Test
    fun `subscribedListsForMatcher skips disabled subscriptions`() = runTest {
        val (lr, sr) = FakeListRepo() to FakeSubRepo()
        val sub = KeywordFilterSubscriber(lr, sr)
        val id = newIdentity()
        sub.subscribe(id.userId, id.publicKeyBytes, "test")
        sub.applyList(newPublisher(id).publish("test", listOf(FilterEntry("x", FilterAction.WARN)), version = 1L)!!)
        // Confirm visible by default.
        assertEquals(1, sub.subscribedListsForMatcher().size)
        // Disable and confirm dropped.
        sub.updateSubscription(sr.get(id.userId)!!.copy(enabled = false))
        assertEquals(0, sub.subscribedListsForMatcher().size)
    }

    @Test
    fun `subscribedListsForMatcher skips lists with no subscription record`() = runTest {
        val (lr, sr) = FakeListRepo() to FakeSubRepo()
        val sub = KeywordFilterSubscriber(lr, sr)
        val id = newIdentity()
        // Force a list into the list repo without going through subscribe (simulate stale state).
        val orphan = newPublisher(id).publish("orphan", emptyList(), version = 1L)!!
        lr.upsert(orphan)
        assertEquals(0, sub.subscribedListsForMatcher().size)
    }

    @Test
    fun `publisher rename propagates through listName cached on subscription`() = runTest {
        val (lr, sr) = FakeListRepo() to FakeSubRepo()
        val sub = KeywordFilterSubscriber(lr, sr)
        val id = newIdentity()
        sub.subscribe(id.userId, id.publicKeyBytes, "old-name")
        val renamed = newPublisher(id).publish("new-name", emptyList(), version = 1L)!!
        sub.applyList(renamed)
        assertEquals("new-name", sr.get(id.userId)!!.listName)
    }
}
