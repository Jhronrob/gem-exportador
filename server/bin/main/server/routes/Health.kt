package server.routes

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.apiHealth() {
    get("/api/health") {
        call.respond(mapOf(
            "status" to "ok",
            "timestamp" to java.time.Instant.now().toString(),
            "uptime" to (System.currentTimeMillis() - startTime) / 1000.0
        ))
    }
}

private val startTime = System.currentTimeMillis()
