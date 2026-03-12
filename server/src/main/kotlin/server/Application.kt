package server

import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.util.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.postgresql.PGConnection
import server.backup.SupabaseBackup
import server.broadcast.Broadcast
import server.db.Database
import server.db.DesenhoDao
import server.queue.ProcessingQueue
import server.routes.*
import server.util.AppLog

fun Application.configureSerialization() {
    install(io.ktor.server.plugins.contentnegotiation.ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        })
    }
}

fun Application.configureStatusPages() {
    install(io.ktor.server.plugins.statuspages.StatusPages) {
        exception<Throwable> { call, cause ->
            when {
                // CancellationException é shutdown normal de coroutines — ignorar silenciosamente
                cause is kotlinx.coroutines.CancellationException -> Unit
                // Connection reset / broken pipe são desconexões normais de clientes WebSocket — ignorar
                cause is java.io.IOException && (
                    cause.message?.contains("Connection reset", ignoreCase = true) == true ||
                    cause.message?.contains("Broken pipe", ignoreCase = true) == true ||
                    cause.message?.contains("Connection closed", ignoreCase = true) == true
                ) -> Unit
                else -> {
                    AppLog.error("Exceção não tratada: ${cause.message}", cause)
                    call.respond(io.ktor.http.HttpStatusCode.InternalServerError, mapOf(
                        "erro" to (cause.message ?: "Erro interno")
                    ))
                }
            }
        }
    }
}

@OptIn(InternalAPI::class)
fun Application.configureRouting() {
    val db = Database()
    db.init()
    val desenhoDao = DesenhoDao(db)
    
    AppLog.info("Servidor gem-exportador iniciando em PRODUCAO com PostgreSQL")
    
    val broadcast = Broadcast()
    val queue = ProcessingQueue(desenhoDao, broadcast)
    
    // Adiciona desenhos pendentes e processando à fila em ordem FIFO (primeiro recebido = primeiro a processar)
    kotlinx.coroutines.runBlocking {
        // STARTUP GUARD: Reseta desenhos presos em "processando" de sessões anteriores (crash/reinicio).
        // Roda UMA vez aqui, nunca dentro do loop de processamento.
        val presosProcessando = desenhoDao.list(status = "processando", limit = 200, offset = 0)
        if (presosProcessando.isNotEmpty()) {
            AppLog.warn("[STARTUP-GUARD] ${presosProcessando.size} desenho(s) presos em 'processando' encontrados — resetando para 'pendente'...")
            for (preso in presosProcessando) {
                AppLog.warn("[STARTUP-GUARD] Resetando ${preso.nomeArquivo} (${preso.id}) para 'pendente'")
                desenhoDao.update(preso.id, status = "pendente")
            }
        }

        val retomar = desenhoDao.listPendentesEProcessandoOrderedByFila(limit = 200)
        if (retomar.isNotEmpty()) {
            AppLog.info("Adicionando ${retomar.size} desenho(s) a fila (ordem FIFO por horario_envio)...")
            for (desenho in retomar) {
                queue.add(desenho.id)
                AppLog.info("  -> ${desenho.nomeArquivo} (${desenho.status}) adicionado a fila")
            }
        }
    }
    
    // BACKUP: Sync em tempo real para Supabase (nuvem)
    val supabaseBackup = SupabaseBackup(db)
    supabaseBackup.init()

    // REALTIME: Usa PostgreSQL LISTEN/NOTIFY para detectar mudanças
    startPostgresListener(db, desenhoDao, queue, broadcast, supabaseBackup)

    routing {
        apiHealth()
        apiDesenhos(desenhoDao, queue, broadcast)
        apiExplorador()
        apiQueue(queue)
        apiStats(desenhoDao)
    }
}

fun Application.configureWebSocket() {
    install(WebSockets) {
        pingPeriod = java.time.Duration.ofSeconds(15)
        timeout = java.time.Duration.ofSeconds(15)
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }
}

/**
 * REALTIME DATABASE LISTENER (PostgreSQL LISTEN/NOTIFY)
 * Usa o mecanismo nativo do PostgreSQL para detectar mudanças em tempo real.
 * Muito mais eficiente que polling - recebe notificação instantânea.
 */
private fun startPostgresListener(
    db: Database,
    desenhoDao: DesenhoDao,
    queue: ProcessingQueue,
    broadcast: Broadcast,
    supabaseBackup: SupabaseBackup
) {
    // Cache para debounce - evita processar notificações duplicadas
    val lastProcessed = java.util.concurrent.ConcurrentHashMap<String, Long>()
    val debounceMs = 1000L // Ignora notificações do mesmo ID dentro de 1 segundo
    
    // Cache de estado para detectar mudanças reais
    val stateCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    
    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
        AppLog.info("[REALTIME] Iniciando listener PostgreSQL LISTEN/NOTIFY...")
        
        // Carrega estado inicial
        desenhoDao.list(limit = 10000, offset = 0).forEach { d ->
            stateCache[d.id] = "${d.status}|${d.progresso}|${d.atualizadoEm}"
        }
        
        while (true) {
            try {
                // Conexão dedicada para LISTEN (precisa ficar aberta)
                val conn = db.connection()
                val pgConn = conn.unwrap(PGConnection::class.java)
                
                // Registra para receber notificações do canal 'desenho_changes'
                conn.createStatement().execute("LISTEN desenho_changes")
                AppLog.info("[REALTIME] Listener PostgreSQL ativo no canal 'desenho_changes'")
                
                while (!conn.isClosed) {
                    // Verifica notificações (timeout de 500ms)
                    val notifications = pgConn.getNotifications(500)
                    
                    if (notifications != null) {
                        for (notification in notifications) {
                            try {
                                val payload = notification.parameter
                                val json = kotlinx.serialization.json.Json.parseToJsonElement(payload)
                                val op = json.jsonObject["op"]?.toString()?.replace("\"", "") ?: continue
                                val id = json.jsonObject["id"]?.toString()?.replace("\"", "") ?: continue
                                
                                // Debounce: ignora se processou recentemente
                                val now = System.currentTimeMillis()
                                val lastTime = lastProcessed[id] ?: 0L
                                if (now - lastTime < debounceMs) {
                                    continue // Ignora notificação duplicada
                                }
                                lastProcessed[id] = now
                                
                                val desenho = desenhoDao.getById(id)
                                if (desenho != null) {
                                    // Verifica se houve mudança real
                                    val newState = "${desenho.status}|${desenho.progresso}|${desenho.atualizadoEm}"
                                    val oldState = stateCache[id]
                                    
                                    if (op == "INSERT" || oldState != newState) {
                                        stateCache[id] = newState
                                        AppLog.info("[REALTIME] $op: ${desenho.nomeArquivo} -> ${desenho.status}")
                                        
                                        when (op) {
                                            "INSERT" -> {
                                                broadcast.sendInsert(desenho)
                                                supabaseBackup.syncRecord(id)
                                                // Só adiciona à fila se pendente e não estiver já em processamento
                                                if (desenho.status == "pendente") {
                                                    queue.add(desenho.id)
                                                    AppLog.info("[REALTIME] -> Adicionado à fila de processamento")
                                                }
                                            }
                                            "UPDATE" -> {
                                                broadcast.sendUpdate(desenho)
                                                supabaseBackup.syncRecord(id)
                                            }
                                        }
                                    }
                                } else if (op == "DELETE") {
                                    stateCache.remove(id)
                                    supabaseBackup.deleteRecord(id)
                                    AppLog.info("[REALTIME] Registro deletado: $id")
                                }
                            } catch (e: Exception) {
                                AppLog.error("[REALTIME] Erro ao processar notificação: ${e.message}")
                            }
                        }
                    }
                }
                
                conn.close()
            } catch (e: Exception) {
                AppLog.error("[REALTIME] Erro no listener, reconectando em 5s: ${e.message}")
                kotlinx.coroutines.delay(5000)
            }
        }
    }
}
