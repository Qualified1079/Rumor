package com.rumor.mesh.simulator.server

import com.rumor.mesh.core.logging.RumorLog
import com.rumor.mesh.simulator.engine.ReportWriter
import com.rumor.mesh.simulator.engine.SimWorld
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.random.Random

private const val TAG = "KtorServer"

/**
 * Embedded HTTP + WebSocket server that drives the browser dashboard.
 *
 * - `GET  /`                  — the single-page dashboard
 * - `GET  /ws`                — WebSocket: pushes [DashboardState] every [pushIntervalMs]
 * - `GET  /api/init`          — param descriptors for slider generation
 * - `POST /api/start|stop|reset|step`
 * - `POST /api/param/{id}?value=…`   — set one parameter
 * - `POST /api/randomize/{id}`       — randomize one parameter
 * - `POST /api/randomize`            — randomize the whole sim
 * - `GET  /api/report`        — download a diagnostic .zip
 */
class DashboardServer(
    private val world: SimWorld,
    private val port: Int = 8080,
    private val rumorVersion: String = "dev",
    private val pushIntervalMs: Long = 200L,
) {
    private val json = Json { encodeDefaults = true }

    fun start(wait: Boolean = true) {
        RumorLog.i(TAG, "Dashboard at http://localhost:$port")
        embeddedServer(Netty, port = port) {
            install(WebSockets)
            install(ContentNegotiation) { json() }
            install(StatusPages) {
                exception<Throwable> { call, cause ->
                    RumorLog.w(TAG, "Request failed: ${cause.message}")
                    call.respondText("error: ${cause.message}", status = HttpStatusCode.InternalServerError)
                }
            }
            routing {
                get("/") {
                    val html = DashboardServer::class.java.classLoader
                        .getResourceAsStream("dashboard/index.html")
                        ?.bufferedReader()?.readText()
                        ?: "dashboard/index.html not found on classpath"
                    call.respondText(html, ContentType.Text.Html)
                }

                get("/api/init") {
                    call.respondText(
                        json.encodeToString(DashboardInit(world.params.descriptors())),
                        ContentType.Application.Json,
                    )
                }

                post("/api/start") { world.start(); call.ok() }
                post("/api/stop")  { world.stop();  call.ok() }
                post("/api/reset") { world.reset(); call.ok() }
                post("/api/step")  { world.step();  call.ok() }

                post("/api/param/{id}") {
                    val id    = call.parameters["id"].orEmpty()
                    val value = call.request.queryParameters["value"].orEmpty()
                    val param = world.params.byId(id)
                    if (param == null) {
                        call.respondText("unknown param: $id", status = HttpStatusCode.NotFound)
                    } else {
                        param.setFromString(value)
                        call.ok()
                    }
                }

                post("/api/randomize/{id}") {
                    val id = call.parameters["id"].orEmpty()
                    if (world.params.randomizeOne(id, Random.Default)) call.ok()
                    else call.respondText("unknown param: $id", status = HttpStatusCode.NotFound)
                }

                post("/api/randomize") {
                    world.params.randomizeAll(Random.Default)
                    call.ok()
                }

                get("/api/report") {
                    val bytes = ReportWriter.generate(world, world.params, rumorVersion)
                    call.respondBytes(bytes, ContentType.Application.Zip, HttpStatusCode.OK)
                }

                webSocket("/ws") {
                    while (isActive) {
                        send(Frame.Text(json.encodeToString(world.dashboardState())))
                        delay(pushIntervalMs)
                    }
                }
            }
        }.start(wait = wait)
    }

    private suspend fun io.ktor.server.application.ApplicationCall.ok() =
        respondText("ok", ContentType.Text.Plain)
}
