package com.rumor.mesh.node

import com.rumor.mesh.core.data.RoomRepository
import com.rumor.mesh.core.data.RoomSubscription
import com.rumor.mesh.core.data.RoomSubscriptionMode
import com.rumor.mesh.core.data.RoomSubscriptionRepository
import com.rumor.mesh.core.model.MessageType
import com.rumor.mesh.core.model.RoomActionKind
import com.rumor.mesh.core.model.RoomMembershipPolicy
import com.rumor.mesh.core.model.RoomPostingPolicy
import com.rumor.mesh.core.model.RumorMessage
import com.rumor.mesh.core.protocol.GossipEngine
import com.rumor.mesh.core.protocol.RoomId
import com.rumor.mesh.core.protocol.RoomRoutingTag
import com.rumor.mesh.core.wire.roomRoutingTag
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Room controls for the node's status page: every action is one call into the
 * engine's own compose API (O252 — no room logic lives here). Shows what the
 * engine accepted; refusals are in the node's console log.
 */
class NodeRooms(
    private val engine: GossipEngine,
    private val rooms: RoomRepository,
    private val subscriptions: RoomSubscriptionRepository,
    private val localUserId: String,
) {
    private val log = ConcurrentLinkedDeque<String>()
    private val timeFmt = SimpleDateFormat("HH:mm:ss")

    private fun note(line: String) {
        log.addFirst("${timeFmt.format(Date())}  $line")
        while (log.size > 100) log.pollLast()
    }

    /** Inbox hook: a ROOM_MESSAGE here already passed the engine's authority checks. */
    suspend fun onInbound(msg: RumorMessage) {
        if (msg.type != MessageType.ROOM_MESSAGE) return
        val tag = msg.roomRoutingTag ?: return
        val room = subscriptions.getAll().firstOrNull { openTag(it.roomId) == tag }?.roomId ?: "?"
        note("[${room.take(8)}] ${msg.senderId.take(8)}: ${msg.payload?.content ?: "(encrypted)"}")
    }

    private fun openTag(roomId: String) = Base64.getEncoder().encodeToString(RoomRoutingTag.openRoomTag(roomId))

    val actions: Map<String, suspend (Map<String, String>) -> String> = mapOf(
        "create" to { p ->
            val posting = p["posting"]?.let { RoomPostingPolicy.valueOf(it) } ?: RoomPostingPolicy.MEMBER_ONLY
            val nonce = UUID.randomUUID().toString()
            val msg = engine.composeRoomCreate(nonce, p["name"] ?: "room", RoomMembershipPolicy.INVITE, posting, false)
            if (msg == null) "create failed" else {
                val id = RoomId.derive(localUserId, nonce)
                subscribe(id)
                "created $id".also(::note)
            }
        },
        "join" to { p -> val id = p.getValue("room"); subscribe(id); "joined $id".also(::note) },
        "invite" to { p ->
            val m = engine.composeRoomInvite(p.getValue("room"), p.getValue("user"), "", System.currentTimeMillis() + 7 * 86_400_000L)
            (if (m != null) "invited ${p.getValue("user").take(8)}" else "invite refused (not a moderator?)").also(::note)
        },
        "post" to { p ->
            val m = engine.composeRoomMessage(RoomRoutingTag.openRoomTag(p.getValue("room")), p.getValue("text"))
            (if (m != null) "posted to ${p.getValue("room").take(8)}: ${p.getValue("text")}" else "post failed").also(::note)
        },
        "act" to { p ->
            val kind = RoomActionKind.valueOf(p.getValue("kind"))
            val m = engine.composeRoomAction(p.getValue("room"), kind, p.getValue("user"))
            (if (m != null) "$kind ${p.getValue("user").take(8)}" else "$kind refused (not a moderator?)").also(::note)
        },
    )

    private suspend fun subscribe(roomId: String) =
        subscriptions.upsert(RoomSubscription(roomId, RoomSubscriptionMode.OPEN, ByteArray(0), System.currentTimeMillis()))

    /** Plain-text state for scripts: one line per room. */
    suspend fun stateText(): String = buildString {
        for (r in rooms.getAll()) {
            append("${r.roomId} name=${r.name} creator=${r.creatorId.take(8)} posting=${r.postingPolicy}")
            append(" joined=${subscriptions.get(r.roomId) != null}")
            append(" mods=${rooms.moderatorsOf(r.roomId).map { it.take(8) }}")
            append(" members=${rooms.membersOf(r.roomId).map { it.take(8) }}")
            appendLine(" bans=${rooms.bansOf(r.roomId).map { it.take(8) }}")
        }
    }

    suspend fun html(esc: (String) -> String): String {
        val rows = buildString {
            for (r in rooms.getAll()) {
                val me = when {
                    rooms.isBanned(r.roomId, localUserId) -> "banned"
                    rooms.isModerator(r.roomId, localUserId) -> "mod"
                    rooms.isMember(r.roomId, localUserId) -> "member"
                    else -> "—"
                }
                append("<tr><td class=mono>${r.roomId.take(16)}…</td><td>${esc(r.name)}</td><td>${r.postingPolicy}</td><td>$me</td>")
                append("<td>${if (subscriptions.get(r.roomId) != null) "yes" else "no"}</td>")
                append("<td>${rooms.membersOf(r.roomId).size} / ${rooms.bansOf(r.roomId).size}</td></tr>")
            }
        }
        val logRows = log.take(40).joinToString("\n") { "<div class=mono>${esc(it)}</div>" }
        return """
<h2>rooms</h2>
<form method="post" action="/act/create">name <input type="text" name="name" style="width:12em"> <button>create</button></form>
<table><tr><th>id</th><th>name</th><th>posting</th><th>me</th><th>joined</th><th>members / bans</th></tr>
$rows
</table>
<div class=mono>post / invite / join / ban via <code>curl -d room=ID -d text=… 127.0.0.1:PORT/act/post</code> — see /rooms for full ids</div>
<h2>room activity</h2>
<div class=box>
$logRows
</div>"""
    }
}
