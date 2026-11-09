package com.rumor.mesh.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rumor.mesh.core.data.RoomRecord
import com.rumor.mesh.core.data.RoomRepository
import com.rumor.mesh.core.data.memory.InMemoryRoomRepository
import com.rumor.mesh.core.model.RoomMembershipPolicy
import com.rumor.mesh.core.model.RoomPostingPolicy
import com.rumor.mesh.core.protocol.RoomAuthorityResolver
import com.rumor.mesh.data.adapter.RoomRepositoryAdapter
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * O187 — the Room adapter must behave exactly like `InMemoryRoomRepository`,
 * which every `:core`/`:simulator` room test runs against. Each check runs on
 * both; the in-memory run proves the expectation itself is right, the SQLite
 * run proves the adapter matches it. Instrumented because the rules live in
 * SQL (REPLACE semantics, transactions) that no JVM test can see.
 */
@RunWith(AndroidJUnit4::class)
class RoomRepositoryParityTest {

    private lateinit var db: RumorDatabase

    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            RumorDatabase::class.java,
        ).build()
    }

    @After fun close() = db.close()

    private fun both(check: suspend (RoomRepository) -> Unit) = runBlocking {
        check(InMemoryRoomRepository())
        check(RoomRepositoryAdapter(db.roomDao()))
    }

    private val room = RoomRecord(
        roomId = "room-1", name = "r", creatorId = "creator", createdAtMs = 1L,
        membershipPolicy = RoomMembershipPolicy.INVITE,
        postingPolicy = RoomPostingPolicy.MOD_APPROVED,
        allowMedia = false,
    )

    @Test fun recordRoundTripsEveryField() = both { repo ->
        repo.upsert(room)
        assertEquals(room, repo.get("room-1"))
        assertEquals(listOf(room), repo.getAll())
        assertNull(repo.get("other"))
    }

    @Test fun creatorModeratesByConstructionAndCannotBeRemoved() = both { repo ->
        repo.upsert(room)
        assertEquals(setOf("creator"), repo.moderatorsOf("room-1"))
        repo.removeModerator("room-1", "creator")
        assertTrue(repo.isModerator("room-1", "creator"))
        // Discrimination: an ordinary moderator IS removable.
        repo.addModerator("room-1", "mod")
        repo.removeModerator("room-1", "mod")
        assertFalse(repo.isModerator("room-1", "mod"))
    }

    @Test fun redeliveredCreateKeepsTheRoster() = both { repo ->
        repo.upsert(room)
        repo.addMember("room-1", "alice")
        repo.addModerator("room-1", "mod")
        repo.addBan("room-1", "mallory")
        repo.upsert(room.copy(name = "renamed"))
        assertEquals("renamed", repo.get("room-1")?.name)
        assertEquals(setOf("alice"), repo.membersOf("room-1"))
        assertEquals(setOf("creator", "mod"), repo.moderatorsOf("room-1"))
        assertEquals(setOf("mallory"), repo.bansOf("room-1"))
    }

    @Test fun banRemovesMembershipAndUnbanDoesNotRestoreIt() = both { repo ->
        repo.upsert(room)
        repo.addMember("room-1", "bob")
        repo.addBan("room-1", "bob")
        assertTrue(repo.isBanned("room-1", "bob"))
        assertFalse(repo.isMember("room-1", "bob"))
        repo.removeBan("room-1", "bob")
        assertFalse(repo.isBanned("room-1", "bob"))
        assertFalse(repo.isMember("room-1", "bob"))
    }

    @Test fun rolesAreScopedPerRoomAndPerRole() = both { repo ->
        repo.upsert(room)
        repo.upsert(room.copy(roomId = "room-2", creatorId = "other"))
        repo.addMember("room-1", "alice")
        assertFalse(repo.isMember("room-2", "alice"))
        assertFalse(repo.isModerator("room-1", "alice"))
        assertFalse(repo.isBanned("room-1", "alice"))
        repo.addMember("room-1", "alice")   // idempotent
        assertEquals(setOf("alice"), repo.membersOf("room-1"))
    }

    @Test fun deleteRemovesRoomAndWholeRoster() = both { repo ->
        repo.upsert(room)
        repo.addMember("room-1", "alice")
        repo.addBan("room-1", "mallory")
        repo.delete("room-1")
        assertNull(repo.get("room-1"))
        assertTrue(repo.moderatorsOf("room-1").isEmpty())
        assertTrue(repo.membersOf("room-1").isEmpty())
        assertTrue(repo.bansOf("room-1").isEmpty())
    }

    /** The resolver the engine actually consults, over SQLite. */
    @Test fun resolverOverSqliteEnforcesTheGovernedRoom() = runBlocking<Unit> {
        val repo = RoomRepositoryAdapter(db.roomDao())
        repo.upsert(room)
        repo.addMember("room-1", "alice")
        repo.addBan("room-1", "mallory")
        val r = RoomAuthorityResolver(repo)
        assertTrue(r.requiresPostingCert("room-1"))
        assertTrue(r.requiresMembershipToPost("room-1"))
        assertTrue(r.isMember("room-1", "alice"))
        assertTrue(r.isMember("room-1", "creator"))
        assertFalse(r.isMember("room-1", "stranger"))
        assertTrue(r.isBanned("room-1", "mallory"))
        // Unknown room stays ungoverned (the feed must keep working).
        assertFalse(r.requiresPostingCert("unknown"))
        assertFalse(r.requiresMembershipToPost("unknown"))
    }

    /** A corrupt policy must not read back as "unknown room" (= ungoverned). */
    @Test fun corruptPolicyRowThrowsInsteadOfVanishing() = runBlocking<Unit> {
        db.roomDao().upsertRoom(
            RoomEntity("room-x", "x", "creator", 1L, "NOT_A_POLICY", "MOD_APPROVED", false),
        )
        val repo = RoomRepositoryAdapter(db.roomDao())
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.get("room-x") } }
    }
}
