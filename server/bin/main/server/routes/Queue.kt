package server.routes

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import server.queue.ProcessingQueue
import server.queue.QueueStatus

fun Route.apiQueue(queue: ProcessingQueue) {
    get("/api/queue") {
        val status: QueueStatus = queue.getStatus()
        call.respond(status)
    }
}
