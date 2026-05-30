package com.rumor.mesh.simulator.server

import com.rumor.mesh.core.logging.RumorLog
import com.rumor.mesh.simulator.engine.ReportWriter
import com.rumor.mesh.simulator.engine.SimWorld
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.http.HttpHeaders
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.content.streamProvider
import io.ktor.server.response.header
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondFile
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
    private val scenarioRunner: ScenarioRunnerState = ScenarioRunnerState(),
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
                        world.params.enforceInvariants()
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

                /**
                 * Download the per-tick trace of the live dashboard sim. This
                 * is the recorded *history* of the run (one sample per sim-
                 * second, latest 10 minutes kept) — distinct from /api/report
                 * which is a point-in-time snapshot. Same shape as the
                 * headless scenarios' trace.json so the same downstream
                 * analysis tooling works for both.
                 */
                get("/api/trace") {
                    val samples = world.snapshotTrace()
                    val payload = json.encodeToString(samples)
                    call.response.header(
                        HttpHeaders.ContentDisposition,
                        "attachment; filename=\"rumor-live-trace.json\"",
                    )
                    call.respondText(payload, ContentType.Application.Json)
                }

                // ── Scenario runner ─────────────────────────────────────────
                get("/api/scenarios") {
                    call.respondText(
                        json.encodeToString(scenarioRunner.listScenarios()),
                        ContentType.Application.Json,
                    )
                }

                get("/api/scenarios/preview") {
                    val name = call.request.queryParameters["name"].orEmpty()
                    val preview = scenarioRunner.preview(name)
                    if (preview == null) {
                        call.respondText("unknown scenario", status = HttpStatusCode.NotFound)
                    } else {
                        call.respondText(json.encodeToString(preview), ContentType.Application.Json)
                    }
                }

                post("/api/scenarios/upload") {
                    val multipart = call.receiveMultipart()
                    val accepted = mutableListOf<String>()
                    multipart.forEachPart { part ->
                        if (part is PartData.FileItem) {
                            val name = part.originalFileName ?: "upload-${System.currentTimeMillis()}.json"
                            val content = part.streamProvider().bufferedReader().readText()
                            runCatching { scenarioRunner.acceptUpload(name, content) }
                                .onSuccess { accepted.add(name) }
                        }
                        part.dispose()
                    }
                    call.respondText(
                        """{"accepted":${json.encodeToString(accepted)}}""",
                        ContentType.Application.Json,
                    )
                }

                post("/api/scenarios/run") {
                    val body = call.request.queryParameters["names"].orEmpty()
                    val names = body.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    if (names.isEmpty()) {
                        call.respondText("no scenarios selected", status = HttpStatusCode.BadRequest)
                    } else {
                        val id = runCatching { scenarioRunner.startRun(names) }
                            .getOrElse { e ->
                                call.respondText("error: ${e.message}", status = HttpStatusCode.Conflict)
                                return@post
                            }
                        call.respondText("""{"id":"$id"}""", ContentType.Application.Json)
                    }
                }

                get("/api/scenarios/job") {
                    val record = scenarioRunner.currentJob
                    if (record == null) {
                        call.respondText("""{"id":null}""", ContentType.Application.Json)
                    } else {
                        val dto = JobStatusDto(
                            id = record.id,
                            status = record.status.name,
                            selected = record.selected,
                            startedAtMs = record.startedAtMs,
                            finishedAtMs = record.finishedAtMs,
                            error = record.error,
                            hasZip = record.status != JobStatus.RUNNING && record.outputZip.exists(),
                        )
                        call.respondText(json.encodeToString(dto), ContentType.Application.Json)
                    }
                }

                get("/api/scenarios/job/zip") {
                    val record = scenarioRunner.currentJob
                    if (record == null || record.status == JobStatus.RUNNING || !record.outputZip.exists()) {
                        call.respondText("no result available", status = HttpStatusCode.NotFound)
                    } else {
                        // Force browser download (not inline view) — some browsers
                        // navigate to application/zip inline otherwise.
                        call.response.header(
                            HttpHeaders.ContentDisposition,
                            "attachment; filename=\"${record.id}.zip\"",
                        )
                        call.respondFile(record.outputZip)
                    }
                }

                post("/api/scenarios/cancel") {
                    val cancelled = scenarioRunner.cancelCurrent()
                    if (cancelled) call.ok()
                    else call.respondText("no running job", status = HttpStatusCode.NotFound)
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
