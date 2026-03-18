package server.routes

import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
data class ExploradorBody(val caminho: String)

fun Route.apiExplorador() {
    post("/api/explorador") {
        val body = call.receive<ExploradorBody>()
        val caminho = body.caminho ?: ""
        if (caminho.isBlank()) {
            call.respond(io.ktor.http.HttpStatusCode.BadRequest, mapOf("error" to "Caminho não fornecido"))
            return@post
        }
        val normalized = caminho.replace("/", "\\")
        try {
            ProcessBuilder("explorer", normalized).start()
            call.respond(mapOf("success" to true))
        } catch (e: Exception) {
            call.respond(io.ktor.http.HttpStatusCode.InternalServerError, mapOf("error" to "Erro ao abrir explorador: ${e.message}"))
        }
    }
}
