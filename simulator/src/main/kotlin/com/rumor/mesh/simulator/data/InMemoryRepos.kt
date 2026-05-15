package com.rumor.mesh.simulator.data

import com.rumor.mesh.core.data.BlockEntryRepository
import com.rumor.mesh.core.data.BlocklistEntryRepository
import com.rumor.mesh.core.data.BreadcrumbRepository
import com.rumor.mesh.core.data.ChunkRecord
import com.rumor.mesh.core.data.ChunkRepository
import com.rumor.mesh.core.data.ContactRepository
import com.rumor.mesh.core.data.MessageRepository
import com.rumor.mesh.core.data.RouteRepository
import com.rumor.mesh.core.data.SubscribedBlocklistRepository
import com.rumor.mesh.core.data.TransferRecord
import com.rumor.mesh.core.data.TransferRepository
import com.rumor.mesh.core.model.BlockEntry
import com.rumor.mesh.core.model.BlocklistEntry
import com.rumor.mesh.core.model.BlocklistMode
import com.rumor.mesh.core.model.Breadcrumb
import com.rumor.mesh.core.model.Contact
import com.rumor.mesh.core.model.Route
import com.rumor.mesh.core.model.RumorMessage
import com.rumor.mesh.core.model.TransferStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.util.concurrent.ConcurrentHashMap

// ── MessageRepository ─────────────────────────────────────────────────────────

class InMemoryMessageRepository : MessageRepository {
    private val messages = ConcurrentHashMap<String, RumorMessage>()
    private val _flow = MutableStateFlow<List<RumorMessage>>(emptyList())

    override suspend fun insert(msg: RumorMessage) {
        messages[msg.id] = msg
        _flow.value = messages.values.toList()
    }
    override suspend fun getById(id: String): RumorMessage? = messages[id]
    override suspend fun count(): Int = messages.size
    override suspend fun evictOldest(count: Int) {
        val toRemove = messages.values.sortedBy { it.receivedAtMs }.take(count).map { it.id }
        toRemove.forEach { messages.remove(it) }
        _flow.value = messages.values.toList()
    }
    override suspend fun markRead(id: String) {
        messages[id]?.let { messages[id] = it.copy(isRead = true) }
    }
    override suspend fun markRelayed(id: String) {
        messages[id]?.let { messages[id] = it.copy(wasRelayed = true) }
    }
    override fun observeBroadcasts(limit: Int): Flow<List<RumorMessage>> =
        _flow.map { it.filter { m -> m.type.name == "BROADCAST" }.take(limit) }
    override fun observeThread(localUserId: String, peerId: String, limit: Int): Flow<List<RumorMessage>> =
        _flow.map { list ->
            list.filter { m ->
                (m.senderId == localUserId && m.recipientId == peerId) ||
                (m.senderId == peerId && m.recipientId == localUserId)
            }.take(limit)
        }
    override fun observeUnread(userId: String): Flow<List<RumorMessage>> =
        _flow.map { it.filter { m -> m.recipientId == userId && !m.isRead } }
    override fun observeAllDirect(userId: String, limit: Int): Flow<List<RumorMessage>> =
        _flow.map { it.filter { m -> m.senderId == userId || m.recipientId == userId }.take(limit) }
}

// ── ContactRepository ─────────────────────────────────────────────────────────

class InMemoryContactRepository : ContactRepository {
    private val contacts = ConcurrentHashMap<String, Contact>()
    private val _flow = MutableStateFlow<List<Contact>>(emptyList())

    override suspend fun upsert(contact: Contact) {
        contacts[contact.userId] = contact
        _flow.value = contacts.values.toList()
    }
    override fun observeAll(): Flow<List<Contact>> = _flow
    override suspend fun getById(userId: String): Contact? = contacts[userId]
    override fun observeById(userId: String): Flow<Contact?> = _flow.map { it.find { c -> c.userId == userId } }
    override suspend fun getPublicKey(userId: String): String? = contacts[userId]?.publicKey
    override suspend fun setAutoRelay(userId: String, enabled: Boolean) {
        contacts[userId]?.let { contacts[userId] = it.copy(autoRelay = enabled) }
    }
    override suspend fun setAlwaysSave(userId: String, enabled: Boolean) {
        contacts[userId]?.let { contacts[userId] = it.copy(alwaysSave = enabled) }
    }
    override suspend fun setWillingToCache(userId: String, enabled: Boolean) {
        contacts[userId]?.let { contacts[userId] = it.copy(willingToCache = enabled) }
    }
    override suspend fun updateLastSeen(userId: String, ms: Long) {
        contacts[userId]?.let { contacts[userId] = it.copy(lastSeenMs = ms) }
    }
    override suspend fun setVerified(userId: String, verified: Boolean) {
        contacts[userId]?.let { contacts[userId] = it.copy(isVerified = verified) }
    }
    override suspend fun setDisplayName(userId: String, name: String) {
        contacts[userId]?.let { contacts[userId] = it.copy(displayName = name) }
    }
    override suspend fun delete(userId: String) {
        contacts.remove(userId)
        _flow.value = contacts.values.toList()
    }
    override suspend fun getAutoRelayContacts(): List<Contact> = contacts.values.filter { it.autoRelay }
    override suspend fun setPriorityPeer(userId: String, enabled: Boolean) {
        contacts[userId]?.let { contacts[userId] = it.copy(isPriorityPeer = enabled) }
    }
    override suspend fun getPriorityPeers(): List<Contact> = contacts.values.filter { it.isPriorityPeer }
}

// ── RouteRepository ───────────────────────────────────────────────────────────

class InMemoryRouteRepository : RouteRepository {
    private val routes = ConcurrentHashMap<String, Route>()
    private val _flow = MutableStateFlow<List<Route>>(emptyList())

    override suspend fun upsert(route: Route) {
        routes[route.peerId] = route
        _flow.value = routes.values.toList()
    }
    override suspend fun getPreferred(limit: Int): List<Route> =
        routes.values
            .sortedWith(
                compareByDescending<Route> { it.bytesRelayed }
                    .thenByDescending { it.sessionCount }
                    .thenByDescending { it.lastUpdatedMs }
            )
            .take(limit)
    override fun observeAll(): Flow<List<Route>> = _flow
    override suspend fun getForPeer(peerId: String): Route? = routes[peerId]
    override suspend fun pruneStale(olderThanMs: Long) {
        routes.values.filter { it.lastUpdatedMs < olderThanMs }.forEach { routes.remove(it.peerId) }
        _flow.value = routes.values.toList()
    }
    override suspend fun delete(peerId: String) {
        routes.remove(peerId)
        _flow.value = routes.values.toList()
    }
}

// ── BreadcrumbRepository ──────────────────────────────────────────────────────

class InMemoryBreadcrumbRepository : BreadcrumbRepository {
    private val crumbs = ConcurrentHashMap<String, MutableList<Breadcrumb>>()

    override suspend fun upsert(crumb: Breadcrumb) {
        crumbs.getOrPut(crumb.targetUserId) { mutableListOf() }.add(crumb)
    }
    override suspend fun getLatest(targetUserId: String): Breadcrumb? =
        crumbs[targetUserId]?.maxByOrNull { it.recordedAtMs }
    override suspend fun pruneForTarget(targetUserId: String) {
        crumbs[targetUserId]?.let { list ->
            if (list.size > 5) {
                val sorted = list.sortedByDescending { it.recordedAtMs }
                crumbs[targetUserId] = sorted.take(5).toMutableList()
            }
        }
    }
    override suspend fun pruneOld(olderThanMs: Long) {
        crumbs.forEach { (key, list) ->
            list.removeAll { it.recordedAtMs < olderThanMs }
            if (list.isEmpty()) crumbs.remove(key)
        }
    }
}

// ── Block repositories ────────────────────────────────────────────────────────

class InMemoryBlockEntryRepository : BlockEntryRepository {
    private val entries = ConcurrentHashMap<String, BlockEntry>()
    override suspend fun upsert(entry: BlockEntry) { entries[entry.userId] = entry }
    override suspend fun delete(userId: String) { entries.remove(userId) }
    override suspend fun getActive(now: Long): List<BlockEntry> =
        entries.values.filter { it.expiresAtMs == null || it.expiresAtMs > now }
    override suspend fun getActiveIds(now: Long): List<String> = getActive(now).map { it.userId }
    override suspend fun pruneExpired(now: Long): Int {
        val expired = entries.values.filter { it.expiresAtMs != null && it.expiresAtMs <= now }
        expired.forEach { entries.remove(it.userId) }
        return expired.size
    }
}

class InMemorySubscribedBlocklistRepository : SubscribedBlocklistRepository {
    private val subs = ConcurrentHashMap<String, com.rumor.mesh.core.model.SubscribedBlocklist>()
    override suspend fun upsert(sub: com.rumor.mesh.core.model.SubscribedBlocklist) { subs[sub.publisherId] = sub }
    override suspend fun delete(publisherId: String) { subs.remove(publisherId) }
    override suspend fun get(publisherId: String) = subs[publisherId]
    override suspend fun getByMode(mode: BlocklistMode) = subs.values.filter { it.mode == mode }
    override suspend fun getAll() = subs.values.toList()
}

class InMemoryBlocklistEntryRepository : BlocklistEntryRepository {
    private val entries = mutableListOf<BlocklistEntry>()
    @Synchronized override suspend fun insertAll(new: List<BlocklistEntry>) { entries.addAll(new) }
    @Synchronized override suspend fun deleteAllForPublisher(publisherId: String) {
        entries.removeAll { it.publisherId == publisherId }
    }
    @Synchronized override suspend fun deleteEntries(publisherId: String, userIds: List<String>) {
        entries.removeAll { it.publisherId == publisherId && it.blockedUserId in userIds }
    }
    @Synchronized override suspend fun getAllBlockedIds() = entries.map { it.blockedUserId }.distinct()
    @Synchronized override suspend fun getBlockedIdsForPublisher(publisherId: String) =
        entries.filter { it.publisherId == publisherId }.map { it.blockedUserId }
}

// ── TransferRepository ────────────────────────────────────────────────────────

class InMemoryTransferRepository : TransferRepository {
    private val transfers = ConcurrentHashMap<String, TransferRecord>()
    private val _flow = MutableStateFlow<List<TransferRecord>>(emptyList())
    override suspend fun upsert(transfer: TransferRecord) {
        transfers[transfer.transferId] = transfer
        _flow.value = transfers.values.toList()
    }
    override suspend fun getById(transferId: String) = transfers[transferId]
    override fun observeByStatus(status: TransferStatus) = _flow.map { it.filter { t -> t.status == status } }
    override fun observeRecent(limit: Int) = _flow.map { it.sortedByDescending { t -> t.startedAtMs }.take(limit) }
    override suspend fun updateStatus(transferId: String, status: TransferStatus, completedAtMs: Long?) {
        transfers[transferId]?.let { transfers[transferId] = it.copy(status = status, completedAtMs = completedAtMs) }
        _flow.value = transfers.values.toList()
    }
    override suspend fun delete(transferId: String) { transfers.remove(transferId); _flow.value = transfers.values.toList() }
    override suspend fun pruneOlderThan(beforeMs: Long) {
        transfers.values.filter {
            it.status in listOf(TransferStatus.COMPLETE, TransferStatus.ABANDONED, TransferStatus.FAILED) &&
            (it.completedAtMs ?: 0) < beforeMs
        }.forEach { transfers.remove(it.transferId) }
        _flow.value = transfers.values.toList()
    }
}

class InMemoryChunkRepository : ChunkRepository {
    private val chunks = ConcurrentHashMap<String, ConcurrentHashMap<Int, ChunkRecord>>()
    private fun forTransfer(id: String) = chunks.getOrPut(id) { ConcurrentHashMap() }
    override suspend fun insertOrReplace(chunk: ChunkRecord) { forTransfer(chunk.transferId)[chunk.chunkIndex] = chunk }
    override suspend fun insertAll(cs: List<ChunkRecord>) { cs.forEach { insertOrReplace(it) } }
    override suspend fun getByTransfer(transferId: String) = forTransfer(transferId).values.sortedBy { it.chunkIndex }
    override suspend fun getReceivedIndices(transferId: String) = forTransfer(transferId).keys.sorted()
    override suspend fun markAcked(transferId: String, chunkIndex: Int, ackedAtMs: Long) {
        forTransfer(transferId)[chunkIndex]?.let { forTransfer(transferId)[chunkIndex] = it.copy(ackedAtMs = ackedAtMs) }
    }
    override suspend fun deleteAllForTransfer(transferId: String) { chunks.remove(transferId) }
}
