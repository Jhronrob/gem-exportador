package server.broadcast

import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import model.DesenhoAutodesk

/**
 * Gerencia sessões WebSocket e envia broadcast de eventos (novo, atualizado) para a tabela.
 */
class Broadcast {
    private val sessions = mutableSetOf<WebSocketSession>()
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun addSession(session: WebSocketSession) {
        mutex.withLock { sessions.add(session) }
    }

    suspend fun removeSession(session: WebSocketSession) {
        mutex.withLock { sessions.remove(session) }
    }

    suspend fun sendInitial(desenhos: List<DesenhoAutodesk>) {
        val msg = """{"type":"initial","data":${json.encodeToString(desenhos)}}"""
        sendToAll(msg)
    }

    suspend fun sendInsert(desenho: DesenhoAutodesk) {
        val msg = """{"type":"INSERT","data":${json.encodeToString(desenho)}}"""
        sendToAll(msg)
    }

    suspend fun sendUpdate(desenho: DesenhoAutodesk) {
        val msg = """{"type":"UPDATE","data":${json.encodeToString(desenho)}}"""
        server.util.AppLog.info("Broadcast UPDATE: ${desenho.nomeArquivo} -> ${desenho.status} (${desenho.progresso}%) para ${sessions.size} cliente(s)")
        sendToAll(msg)
    }

    suspend fun sendDelete(id: String) {
        val msg = """{"type":"DELETE","id":"$id"}"""
        sendToAll(msg)
    }

    private suspend fun sendToAll(text: String) {
        val toRemove = mutex.withLock {
            val failed = mutableListOf<WebSocketSession>()
            for (session in sessions) {
                try {
                    session.send(Frame.Text(text))
                } catch (_: Exception) {
                    failed.add(session)
                }
            }
            failed
        }
        mutex.withLock { sessions.removeAll(toRemove) }
    }
}
