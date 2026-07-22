package com.rumor.mesh.node

import com.rumor.mesh.core.model.ContentType
import com.rumor.mesh.core.model.MessageType
import com.rumor.mesh.core.model.RumorMessage
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Human-eyes window into the headless node (localhost only, JDK HttpServer —
 * no new deps, no CDN, inline everything per house rules). Exists so a person
 * can verify the node's view of the mesh independently of its logs: peers,
 * exchanges, stored messages, and a send box to originate traffic by hand.
 *
 * GET  /        HTML status page (auto-refreshing)
 * GET  /status  plain-text one-liner (scriptable)
 * POST /send    form or raw body → composeBroadcast
 */
class StatusServer(
    private val port: Int,
    private val userId: String,
    private val lanInfo: () -> String,
    private val peerCount: () -> Int,
    private val storedMessages: () -> List<RumorMessage>,
    private val sendBroadcast: (String) -> Boolean,
) {
    /** Rolling operational event log (exchanges, inbox arrivals) — newest first. */
    val events = ConcurrentLinkedDeque<String>()

    /** Cumulative distinct-message tally keyed by "TYPE" and "TYPE from <prefix>". */
    private val counts = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val seenIds = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>(),
    )

    /** Tally a distinct inbound message. Ignores re-deliveries of the same id. */
    fun tally(id: String, type: String, senderPrefix: String) {
        if (!seenIds.add(id)) return
        counts.merge(type, 1, Int::plus)
        counts.merge("$type from $senderPrefix", 1, Int::plus)
    }

    private val timeFmt = SimpleDateFormat("HH:mm:ss")
    private var server: HttpServer? = null

    fun record(event: String) {
        events.addFirst("${timeFmt.format(Date())}  $event")
        while (events.size > EVENT_CAP) events.pollLast()
    }

    fun start() {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
        s.createContext("/") { ex ->
            val bytes = renderHtml().toByteArray()
            ex.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        s.createContext("/counts") { ex ->
            val body = counts.entries.sortedByDescending { it.value }
                .joinToString("\n") { "${it.value}\t${it.key}" }
                .plus("\n").toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        s.createContext("/status") { ex ->
            val body = "userId=$userId lan=${lanInfo()} peers=${peerCount()} stored=${storedMessages().size}\n"
                .toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        s.createContext("/send") { ex ->
            if (ex.requestMethod != "POST") {
                ex.sendResponseHeaders(405, -1); return@createContext
            }
            val raw = ex.requestBody.readBytes().decodeToString()
            val text = if (raw.startsWith("text=")) {
                URLDecoder.decode(raw.removePrefix("text="), "UTF-8")
            } else raw
            val ok = text.isNotBlank() && sendBroadcast(text)
            if (ok) record("SENT broadcast: ${text.take(80)}")
            // Redirect back so the form flow reads naturally in a browser.
            ex.responseHeaders.add("Location", "/")
            ex.sendResponseHeaders(303, -1)
            ex.close()
        }
        s.start()
        server = s
    }

    fun stop() {
        server?.stop(0)
        server = null
    }

    private fun renderHtml(): String {
        val msgs = storedMessages()
            .sortedByDescending { it.sentAtMs }
            .take(50)
        val msgRows = msgs.joinToString("\n") { m ->
            val text = if (m.type == MessageType.BROADCAST && m.payload?.contentType == ContentType.TEXT) {
                m.payload?.content?.take(120) ?: ""
            } else "[${m.type}]"
            "<tr><td>${timeFmt.format(Date(m.sentAtMs))}</td>" +
                "<td class=mono>${m.senderId.take(12)}…</td>" +
                "<td>${m.type}</td><td>${escape(text)}</td></tr>"
        }
        val eventRows = events.take(60).joinToString("\n") { "<div class=mono>${escape(it)}</div>" }
        return """
<!DOCTYPE html>
<html><head><meta charset="utf-8"><meta http-equiv="refresh" content="3">
<title>rumor node</title>
<style>
 body { font-family: sans-serif; margin: 1.5em; background: #14161a; color: #dde; }
 .mono { font-family: monospace; font-size: 0.85em; }
 table { border-collapse: collapse; width: 100%; }
 td, th { border-bottom: 1px solid #333; padding: 3px 8px; text-align: left; }
 h2 { margin-top: 1.2em; font-size: 1.05em; color: #9ab; }
 .hdr { color: #7c8; }
 input[type=text] { width: 30em; background: #222; color: #dde; border: 1px solid #444; padding: 4px; }
 .box { max-height: 22em; overflow-y: auto; border: 1px solid #333; padding: 6px; }
</style></head><body>
<h1>rumor <span class=hdr>:node</span></h1>
<div class=mono>id ${userId.take(16)}… &nbsp;|&nbsp; ${escape(lanInfo())} &nbsp;|&nbsp; peers: <b>${peerCount()}</b> &nbsp;|&nbsp; stored: ${storedMessages().size}</div>
<h2>send broadcast</h2>
<form method="post" action="/send"><input type="text" name="text" autofocus> <button>send</button></form>
<h2>messages (newest 50)</h2>
<table><tr><th>sent</th><th>sender</th><th>type</th><th>text</th></tr>
$msgRows
</table>
<h2>events</h2>
<div class=box>
$eventRows
</div>
</body></html>"""
    }

    private fun escape(s: String) = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    companion object {
        private const val EVENT_CAP = 200
    }
}
