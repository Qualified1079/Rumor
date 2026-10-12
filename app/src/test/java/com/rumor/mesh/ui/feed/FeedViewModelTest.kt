package com.rumor.mesh.ui.feed

import com.rumor.mesh.core.data.memory.InMemoryContactRepository
import com.rumor.mesh.core.data.memory.InMemoryMessageRepository
import com.rumor.mesh.core.identity.IdentityProvider
import com.rumor.mesh.core.identity.LocalIdentity
import com.rumor.mesh.core.model.MessageType
import com.rumor.mesh.core.model.RumorMessage
import com.rumor.mesh.core.protocol.DuplicateFilter
import com.rumor.mesh.core.protocol.MessageStore
import com.rumor.mesh.data.ContactDao
import com.rumor.mesh.service.MeshController
import com.rumor.mesh.service.MeshControllerHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * O164 / O184 at the ViewModel boundary. O164's unbound window (onStart's
 * rebind is async) is milliseconds wide and cannot be hit by hand on a device,
 * so this is the only place its failure path is exercised.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FeedViewModelTest {

    private val calls = mutableListOf<String>()

    private val controller = Proxy.newProxyInstance(
        MeshController::class.java.classLoader, arrayOf(MeshController::class.java),
    ) { _, method, _ -> calls.add(method.name); null } as MeshController

    private val contactDao = Proxy.newProxyInstance(
        ContactDao::class.java.classLoader, arrayOf(ContactDao::class.java),
    ) { _, method, _ ->
        if (method.name == "observeAll") flowOf(emptyList<Any>()) else error("unexpected ${method.name}")
    } as ContactDao

    private val identity = object : IdentityProvider {
        override val identity: StateFlow<LocalIdentity?> = MutableStateFlow(null)
        override val isUnlocked: Boolean = false
    }

    private val holder = MeshControllerHolder()

    private fun viewModel() = FeedViewModel(
        MessageStore(InMemoryMessageRepository(), InMemoryContactRepository(), DuplicateFilter()),
        holder, identity, contactDao,
    )

    private val post = RumorMessage(
        id = "m1", senderId = "s", senderPublicKey = "k", sequenceNumber = 1,
        sentAtMs = 0, type = MessageType.BROADCAST, hopsToLive = 3, signature = "sig",
    )

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `unbound service - send and relay report failure and reach nothing`() {
        val vm = viewModel()
        assertFalse(vm.sendBroadcast("hello"))
        assertFalse(vm.relay(post))
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun `bound service - a double tap relays once and both taps report success`() {
        holder.set(controller)
        val vm = viewModel()
        assertTrue(vm.relay(post))
        assertTrue("a repeat tap must not read as 'not connected'", vm.relay(post))
        assertEquals(listOf("manualRelay"), calls)
    }

    @Test
    fun `bound service - a send reaches the controller`() {
        holder.set(controller)
        assertTrue(viewModel().sendBroadcast("hello"))
        assertEquals(listOf("sendBroadcast"), calls)
    }
}
