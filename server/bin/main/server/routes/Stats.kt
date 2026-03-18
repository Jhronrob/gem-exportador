package server.routes

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import server.db.DesenhoDao

fun Route.apiStats(desenhoDao: DesenhoDao) {
    get("/api/stats") {
        call.respond(mapOf(
            "total" to mapOf("count" to desenhoDao.getStats()["total"]),
            "porStatus" to desenhoDao.getStats()["porStatus"],
            "porComputador" to desenhoDao.getStats()["porComputador"]
        ))
    }
}
