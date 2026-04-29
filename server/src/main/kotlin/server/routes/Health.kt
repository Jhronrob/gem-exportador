package server.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
private data class HealthResponse(
    val status: String,
    val timestamp: String,
    val uptime: Double
)

fun Route.apiHealth() {
    get("/api/health") {
        call.respond(
            HttpStatusCode.OK,
            HealthResponse(
                status = "ok",
                timestamp = java.time.Instant.now().toString(),
                uptime = (System.currentTimeMillis() - startTime) / 1000.0
            )
        )
    }
}

private val startTime = System.currentTimeMillis()
