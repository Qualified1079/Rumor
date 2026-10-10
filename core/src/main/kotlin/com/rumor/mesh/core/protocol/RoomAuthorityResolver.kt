package com.rumor.mesh.core.protocol

import com.rumor.mesh.core.data.RoomRepository
import com.rumor.mesh.core.model.RoomPostingPolicy
import kotlinx.coroutines.runBlocking

/**
 * O187 — the real implementation of [GossipEngine.RoomAuthorityProvider],
 * backed by [RoomRepository].
 *
 * O89 shipped the enforcement with this interface deliberately unimplemented:
 * the checks were correct but nothing could answer them from storage, so
 * `roomAuthorityProvider` was null everywhere and governed rooms could not
 * exist. This closes that.
 *
 * **Which rooms require a cert.** A room requires a posting certificate when
 * its posting policy is anything other than [RoomPostingPolicy.MEMBER_ONLY]'s
 * open counterpart — concretely, when it is [RoomPostingPolicy.MOD_APPROVED].
 * MEMBER_ONLY is enforced by membership instead (and, for ENCRYPTED rooms,
 * structurally: a non-member has no key wrap to decrypt). A room this node has
 * never heard of requires nothing, because refusing traffic for an unknown room
 * would let any node be silenced simply by being behind on room metadata — the
 * ungoverned feed is exactly that case (O154/O186) and must keep working.
 *
 * **Why the blocking read is acceptable here, narrowly.** The interface is
 * synchronous because it is consulted inside the receive path. Each call is a
 * single keyed lookup, and the in-memory implementation never touches IO.
 * O185 already tracks the two blocking SQLite reads per inbound ROOM_MESSAGE
 * and is the row that fixes this properly with a cached snapshot (the same
 * shape `RoomTagMatcher` already uses for subscriptions). Do not add more
 * blocking reads to this path in the meantime.
 */
class RoomAuthorityResolver(
    private val roomRepo: RoomRepository,
) : GossipEngine.RoomAuthorityProvider {

    override fun requiresPostingCert(roomId: String): Boolean = runBlocking {
        val room = roomRepo.get(roomId) ?: return@runBlocking false
        room.postingPolicy == RoomPostingPolicy.MOD_APPROVED
    }

    override fun isModerator(roomId: String, moderatorId: String): Boolean = runBlocking {
        roomRepo.isModerator(roomId, moderatorId)
    }
}
