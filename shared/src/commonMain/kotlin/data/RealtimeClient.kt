package data

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import model.DesenhoAutodesk
import util.logToFile

/**
 * Tipos de eventos realtime
 */
sealed class RealtimeEvent {
    data class Insert(val desenho: DesenhoAutodesk) : RealtimeEvent()
    data class Update(val desenho: DesenhoAutodesk) : RealtimeEvent()
    data class Delete(val id: String) : RealtimeEvent()
    data class InitialData(val desenhos: List<DesenhoAutodesk>) : RealtimeEvent()
    data class Error(val message: String) : RealtimeEvent()
    object Connected : RealtimeEvent()
    object Disconnected : RealtimeEvent()
}

/**
 * Mensagem recebida do WebSocket
 */
@Serializable
private data class WebSocketMessage(
    val type: String,
    val table: String? = null,
    val data: JsonElement? = null,
    val id: String? = null
)

/**
 * Operacao pendente para batch de escritas no banco
 */
private sealed class PendingOp {
    data class Upsert(val desenho: DesenhoAutodesk) : PendingOp()
    data class Remove(val id: String) : PendingOp()
}

/**
 * Cliente WebSocket para sincronizacao em tempo real.
 * Usa batching interno: acumula operacoes por DEBOUNCE_MS antes de gravar
 * no banco, evitando rajadas de recomposicao no Compose.
 */
class RealtimeClient(
    private val serverUrl: String,
    private val repository: IDesenhoRepository
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** URL HTTP base derivada da URL WebSocket: ws://host:port/ws → http://host:port */
    private val httpBaseUrl: String = serverUrl
        .replace(Regex("^ws://"), "http://")
        .replace(Regex("^wss://"), "https://")
        .replace(Regex("/ws$"), "")

    private val client = HttpClient {
        install(WebSockets)
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; isLenient = true })
        }
    }

    private val _events = MutableSharedFlow<RealtimeEvent>(replay = 1)
    val events: SharedFlow<RealtimeEvent> = _events.asSharedFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private var reconnectJob: Job? = null
    private var wsSession: WebSocketSession? = null
    private var shouldReconnect = true

    // Batching: acumula operacoes e grava de uma vez apos um curto intervalo
    companion object {
        private const val DEBOUNCE_MS = 300L
    }
    private val pendingOps = mutableListOf<PendingOp>()
    private val opsMutex = Mutex()
    private var flushJob: Job? = null
    private val flushScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        RECONNECTING
    }

    /**
     * Conecta ao servidor.
     * Passo 1: busca TODOS os registros via HTTP GET /api/desenhos/all (sem limite).
     * Passo 2: conecta o WebSocket apenas para receber INSERT/UPDATE/DELETE em tempo real.
     */
    suspend fun connect() {
        if (_connectionState.value == ConnectionState.CONNECTED) return

        _connectionState.value = ConnectionState.CONNECTING

        // --- Carga inicial via HTTP (sem limite de registros) ---
        try {
            val url = "$httpBaseUrl/api/desenhos/all"
            logToFile("INFO", "Buscando dados iniciais via HTTP: $url")
            val desenhos: List<DesenhoAutodesk> = client.get(url).body()
            repository.upsertAll(desenhos)
            logToFile("INFO", "Carga inicial HTTP: ${desenhos.size} desenhos carregados")
            _events.emit(RealtimeEvent.InitialData(desenhos))
        } catch (e: Exception) {
            logToFile("WARN", "Carga inicial HTTP falhou (tentando via WS): ${e.message}")
        }

        // --- WebSocket apenas para atualizações em tempo real ---
        try {
            client.webSocket(serverUrl) {
                wsSession = this
                _connectionState.value = ConnectionState.CONNECTED
                logToFile("INFO", "WebSocket conectado a $serverUrl")
                _events.emit(RealtimeEvent.Connected)

                for (frame in incoming) {
                    when (frame) {
                        is Frame.Text -> {
                            val text = frame.readText()
                            processMessage(text)
                        }
                        is Frame.Close -> {
                            _connectionState.value = ConnectionState.DISCONNECTED
                            _events.emit(RealtimeEvent.Disconnected)
                        }
                        else -> {}
                    }
                }
            }
        } catch (e: CancellationException) {
            _connectionState.value = ConnectionState.DISCONNECTED
            throw e
        } catch (e: Exception) {
            _connectionState.value = ConnectionState.DISCONNECTED
            if (shouldReconnect) {
                logToFile("ERROR", "WebSocket falhou: ${e.message}")
                _events.emit(RealtimeEvent.Error("Erro de conexao: ${e.message}"))
                scheduleReconnect()
            }
        }
    }

    /**
     * Força refresh dos dados via HTTP (F5 no viewer).
     */
    suspend fun refresh() {
        try {
            val url = "$httpBaseUrl/api/desenhos/all"
            val desenhos: List<DesenhoAutodesk> = client.get(url).body()
            repository.upsertAll(desenhos)
            logToFile("INFO", "Refresh HTTP: ${desenhos.size} desenhos recarregados")
            _events.emit(RealtimeEvent.InitialData(desenhos))
        } catch (e: Exception) {
            logToFile("ERROR", "Erro ao solicitar refresh: ${e.message}")
        }
    }

    /**
     * Desconecta do servidor
     */
    suspend fun disconnect() {
        shouldReconnect = false
        reconnectJob?.cancel()
        reconnectJob = null
        flushJob?.cancel()
        // Grava operacoes restantes antes de cancelar o scope
        try { flushPendingOps() } catch (_: Exception) { }
        flushScope.cancel()
        try {
            wsSession?.close()
        } catch (_: Exception) { }
        wsSession = null
        _connectionState.value = ConnectionState.DISCONNECTED
        try {
            _events.emit(RealtimeEvent.Disconnected)
        } catch (_: Exception) { }
    }

    /**
     * Enfileira uma operacao e agenda o flush com debounce.
     * Varias mensagens que chegam em rajada sao agrupadas em uma unica transacao.
     */
    private suspend fun enqueueOp(op: PendingOp) {
        opsMutex.withLock {
            pendingOps.add(op)
        }
        scheduleFlush()
    }

    private fun scheduleFlush() {
        flushJob?.cancel()
        flushJob = flushScope.launch {
            delay(DEBOUNCE_MS)
            flushPendingOps()
        }
    }

    /**
     * Grava todas as operacoes pendentes no banco em uma unica transacao.
     */
    private suspend fun flushPendingOps() {
        val ops: List<PendingOp>
        opsMutex.withLock {
            if (pendingOps.isEmpty()) return
            ops = pendingOps.toList()
            pendingOps.clear()
        }
        try {
            val upserts = mutableListOf<DesenhoAutodesk>()
            val deletes = mutableListOf<String>()
            for (op in ops) {
                when (op) {
                    is PendingOp.Upsert -> upserts.add(op.desenho)
                    is PendingOp.Remove -> deletes.add(op.id)
                }
            }
            if (upserts.isNotEmpty()) {
                repository.upsertAll(upserts)
            }
            for (id in deletes) {
                repository.delete(id)
            }
            logToFile("DEBUG", "Flush: ${upserts.size} upserts, ${deletes.size} deletes em batch")
        } catch (e: Exception) {
            logToFile("ERROR", "Erro no flush de operacoes pendentes: ${e.message}")
        }
    }

    /**
     * Processa mensagem recebida do WebSocket
     */
    private suspend fun processMessage(text: String) {
        try {
            logToFile("DEBUG", "WebSocket recebeu: ${text.take(200)}...")
            val message = json.decodeFromString<WebSocketMessage>(text)

            when (message.type) {
                "initial" -> {
                    message.data?.let { dataElement ->
                        val dataStr = dataElement.toString()
                        val desenhos = json.decodeFromString<List<DesenhoAutodesk>>(dataStr)
                        logToFile("INFO", "WebSocket initial: ${desenhos.size} desenhos recebidos")
                        repository.upsertAll(desenhos)
                        _events.emit(RealtimeEvent.InitialData(desenhos))
                    }
                }
                "INSERT" -> {
                    message.data?.let { dataElement ->
                        val dataStr = dataElement.toString()
                        val desenho = json.decodeFromString<DesenhoAutodesk>(dataStr)
                        logToFile("INFO", "WebSocket INSERT: ${desenho.nomeArquivo} (${desenho.status})")
                        enqueueOp(PendingOp.Upsert(desenho))
                        _events.emit(RealtimeEvent.Insert(desenho))
                    }
                }
                "UPDATE" -> {
                    message.data?.let { dataElement ->
                        val dataStr = dataElement.toString()
                        val desenho = json.decodeFromString<DesenhoAutodesk>(dataStr)
                        logToFile("INFO", "WebSocket UPDATE: ${desenho.nomeArquivo} -> ${desenho.status} (${desenho.progresso}%)")
                        enqueueOp(PendingOp.Upsert(desenho))
                        _events.emit(RealtimeEvent.Update(desenho))
                    }
                }
                "DELETE" -> {
                    message.id?.let { id ->
                        logToFile("INFO", "WebSocket DELETE: $id")
                        enqueueOp(PendingOp.Remove(id))
                        _events.emit(RealtimeEvent.Delete(id))
                    }
                }
                else -> {
                    logToFile("DEBUG", "WebSocket tipo desconhecido: ${message.type}")
                }
            }
        } catch (e: Exception) {
            logToFile("ERROR", "Erro ao processar mensagem WebSocket: ${e.message}")
        }
    }

    /**
     * Agenda reconexao apos falha
     */
    private fun scheduleReconnect() {
        if (!shouldReconnect) return
        reconnectJob?.cancel()
        reconnectJob = CoroutineScope(Dispatchers.Default).launch {
            delay(5000)
            if (shouldReconnect) {
                _connectionState.value = ConnectionState.RECONNECTING
                connect()
            }
        }
    }
}
