package server.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.http.content.*
import io.ktor.websocket.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import model.DesenhoAutodesk
import server.broadcast.Broadcast
import server.db.DesenhoDao
import server.queue.ProcessingQueue
import server.util.AppLog
import java.io.File
import java.util.UUID

@Serializable
data class ListResponse(val desenhos: List<DesenhoAutodesk>, val total: Int, val limit: Int, val offset: Int)

@Serializable
data class UpdateBody(
    val status: String? = null,
    val progresso: Int? = null,
    val arquivosProcessados: String? = null, // JSON string
    val erro: String? = null
)

@Serializable
data class QueueBody(
    val nomeArquivo: String,
    val computador: String,
    val caminhoDestino: String,
    val arquivoOriginal: String,
    val formatos: List<String> = listOf("pdf")
)

@Serializable
data class ErrorResponse(val erro: String, val mensagem: String? = null)

@Serializable
data class QueueResponse(
    val id: String,
    val status: String,
    val posicaoFila: Int,
    val mensagem: String,
    val caminhoDestino: String,
    val formatosSolicitados: List<String>
)

@Serializable
data class SuccessResponse(
    val sucesso: Boolean,
    val mensagem: String,
    val id: String? = null,
    val status: String? = null,
    val posicaoFila: Int? = null,
    val formatosRestantes: List<String>? = null
)

@Serializable
data class CancelResponse(
    val sucesso: Boolean,
    val mensagem: String,
    val id: String,
    val status: String
)

/** Lock para atribuicao atomica de posicao na fila (FIFO: primeiro recebido = menor posicao). */
private val queuePositionLock = Any()

fun Route.apiDesenhos(desenhoDao: DesenhoDao, queue: ProcessingQueue, broadcast: Broadcast) {
    // GET /api/desenhos - lista com filtros e paginação
    get("/api/desenhos") {
        val status = call.request.queryParameters["status"]
        val computador = call.request.queryParameters["computador"]
        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
        val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0
        val desenhos = desenhoDao.list(status = status, computador = computador, limit = limit, offset = offset)
        val total = desenhoDao.count(status)
        call.respond(ListResponse(desenhos, total, limit, offset))
    }

    // GET /api/desenhos/all - retorna TODOS os registros sem limite (usado pelo viewer na carga inicial)
    get("/api/desenhos/all") {
        call.respond(desenhoDao.listAll())
    }

    // POST /api/desenhos/upload - upload (multipart)
    post("/api/desenhos/upload") {
        val multipart = call.receiveMultipart()
        var nomeArquivo: String? = null
        var computador: String? = null
        var caminhoDestino: String? = null
        var formatos: String? = null
        var fileBytes: ByteArray? = null
        var fileName: String? = null

        multipart.forEachPart { part ->
            when (part) {
                is PartData.FormItem -> {
                    when (part.name) {
                        "nomeArquivo" -> nomeArquivo = part.value
                        "computador" -> computador = part.value
                        "caminhoDestino" -> caminhoDestino = part.value
                        "formatos" -> formatos = part.value
                    }
                }
                is PartData.FileItem -> {
                    if (part.name == "arquivo") {
                        fileName = part.originalFileName
                        val tempFile = java.io.File.createTempFile("upload_", ".tmp")
                        try {
                            val stream = part.streamProvider()
                            stream.use { input ->
                                tempFile.outputStream().use { input.copyTo(it) }
                            }
                            fileBytes = tempFile.readBytes()
                        } finally {
                            tempFile.delete()
                        }
                    }
                }
                else -> {}
            }
            part.dispose()
        }

        if (fileBytes == null || nomeArquivo.isNullOrBlank() || computador.isNullOrBlank() || caminhoDestino.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(erro = "Campos obrigatórios faltando", mensagem = "nomeArquivo, computador, caminhoDestino e arquivo são obrigatórios"))
            return@post
        }

        val formatosList = formatos?.let { kotlinx.serialization.json.Json.decodeFromString<List<String>>(it) } ?: listOf("pdf")
val validFormats = setOf("pdf", "dwf", "dwg")
            val formatosValidados = formatosList.map { it.trim().lowercase() }.filter { it in validFormats }
            if (formatosValidados.isEmpty()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(erro = "Nenhum formato válido", mensagem = "Especifique pelo menos um: pdf, dwf, dwg"))
            return@post
        }

        val id = UUID.randomUUID().toString()
        val now = java.time.Instant.now().toString()
        val pastaProcessamento = File(System.getProperty("user.dir"), "processados").resolve(id).apply { mkdirs() }
        val arquivoFinal = pastaProcessamento.resolve(fileName ?: nomeArquivo!!)
        File(arquivoFinal.toURI()).writeBytes(fileBytes!!)

        val pos = synchronized(queuePositionLock) {
            desenhoDao.countPendentesEProcessando() + 1
        }
        val desenho = DesenhoAutodesk(
            id = id,
            nomeArquivo = nomeArquivo!!,
            computador = computador!!,
            caminhoDestino = caminhoDestino!!,
            status = "pendente",
            posicaoFila = pos,
            horarioEnvio = now,
            horarioAtualizacao = now,
            formatosSolicitadosJson = """["${formatosValidados.joinToString("\",\"")}"]""",
            arquivoOriginal = arquivoFinal.absolutePath,
            pastaProcessamento = pastaProcessamento.absolutePath,
            criadoEm = now,
            atualizadoEm = now
        )
        desenhoDao.insert(desenho)
        queue.add(id)
        desenhoDao.getById(id)?.let { broadcast.sendInsert(it) }

        call.respond(HttpStatusCode.Created, QueueResponse(
            id = id,
            status = "pendente",
            posicaoFila = pos,
            mensagem = "Arquivo recebido e adicionado à fila",
            caminhoDestino = caminhoDestino!!,
            formatosSolicitados = formatosValidados
        ))
    }

    // POST /api/desenhos/queue - adiciona arquivo LOCAL à fila (sem upload, mesmo servidor)
    post("/api/desenhos/queue") {
        val body = call.receive<QueueBody>()
        AppLog.info("[POST queue] recebido: ${body.nomeArquivo} (${body.computador})")

        if (body.nomeArquivo.isBlank() || body.computador.isBlank() || 
            body.caminhoDestino.isBlank() || body.arquivoOriginal.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(
                erro = "Campos obrigatórios faltando",
                mensagem = "nomeArquivo, computador, caminhoDestino e arquivoOriginal são obrigatórios"
            ))
            return@post
        }
        
        val arquivoFile = File(body.arquivoOriginal)
        if (!arquivoFile.exists()) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(
                erro = "Arquivo não encontrado",
                mensagem = "O arquivo ${body.arquivoOriginal} não existe no servidor"
            ))
            return@post
        }
        
        val validFormats = setOf("pdf", "dwf", "dwg")
        val formatosValidados = body.formatos.map { it.trim().lowercase() }.filter { it in validFormats }
        if (formatosValidados.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(
                erro = "Nenhum formato válido",
                mensagem = "Especifique pelo menos um: pdf, dwf, dwg"
            ))
            return@post
        }
        
        val id = UUID.randomUUID().toString()
        val now = java.time.Instant.now().toString()
        val pos = synchronized(queuePositionLock) {
            desenhoDao.countPendentesEProcessando() + 1
        }
        val pastaProcessamento = File(System.getProperty("user.dir"), "processados").resolve(id).apply { mkdirs() }
        
        val desenho = DesenhoAutodesk(
            id = id,
            nomeArquivo = body.nomeArquivo,
            computador = body.computador,
            caminhoDestino = body.caminhoDestino,
            status = "pendente",
            posicaoFila = pos,
            horarioEnvio = now,
            horarioAtualizacao = now,
            formatosSolicitadosJson = """["${formatosValidados.joinToString("\",\"")}"]""",
            arquivoOriginal = body.arquivoOriginal,
            pastaProcessamento = pastaProcessamento.absolutePath,
            criadoEm = now,
            atualizadoEm = now
        )
        desenhoDao.insert(desenho)
        queue.add(id)
        desenhoDao.getById(id)?.let { broadcast.sendInsert(it) }
        
        call.respond(HttpStatusCode.Created, QueueResponse(
            id = id,
            status = "pendente",
            posicaoFila = pos,
            mensagem = "Arquivo adicionado à fila de processamento",
            caminhoDestino = body.caminhoDestino,
            formatosSolicitados = formatosValidados
        ))
    }

    // POST /api/desenhos/queue/batch - adiciona ate 100 arquivos em uma unica requisicao (FIFO: posicoes atribuidas em sequencia)
    post("/api/desenhos/queue/batch") {
        val body = call.receive<List<QueueBody>>()
        val limit = body.take(100)
        if (body.size > 100) AppLog.warn("[POST queue/batch] lista com ${body.size} itens; processando apenas os 100 primeiros")
        val validFormats = setOf("pdf", "dwf", "dwg")
        val ids = mutableListOf<String>()
        val erros = mutableListOf<String>()
        val toEnqueue = mutableListOf<String>()
        synchronized(queuePositionLock) {
            for (item in limit) {
                try {
                    if (item.nomeArquivo.isBlank() || item.computador.isBlank() || item.caminhoDestino.isBlank() || item.arquivoOriginal.isBlank()) {
                        erros.add("${item.nomeArquivo}: campos obrigatorios faltando")
                        continue
                    }
                    val arquivoFile = File(item.arquivoOriginal)
                    if (!arquivoFile.exists()) {
                        erros.add("${item.nomeArquivo}: arquivo nao encontrado")
                        continue
                    }
                    val formatosValidados = item.formatos.map { it.trim().lowercase() }.filter { it in validFormats }
                    if (formatosValidados.isEmpty()) {
                        erros.add("${item.nomeArquivo}: nenhum formato valido")
                        continue
                    }
                    val id = UUID.randomUUID().toString()
                    val now = java.time.Instant.now().toString()
                    val pos = desenhoDao.countPendentesEProcessando() + 1
                    val pastaProcessamento = File(System.getProperty("user.dir"), "processados").resolve(id).apply { mkdirs() }
                    val desenho = DesenhoAutodesk(
                        id = id,
                        nomeArquivo = item.nomeArquivo,
                        computador = item.computador,
                        caminhoDestino = item.caminhoDestino,
                        status = "pendente",
                        posicaoFila = pos,
                        horarioEnvio = now,
                        horarioAtualizacao = now,
                        formatosSolicitadosJson = """["${formatosValidados.joinToString("\",\"")}"]""",
                        arquivoOriginal = item.arquivoOriginal,
                        pastaProcessamento = pastaProcessamento.absolutePath,
                        criadoEm = now,
                        atualizadoEm = now
                    )
                    desenhoDao.insert(desenho)
                    ids.add(id)
                    toEnqueue.add(id)
                    AppLog.info("[POST queue/batch] inserido: ${item.nomeArquivo} (${item.computador})")
                } catch (e: Exception) {
                    AppLog.error("Erro ao inserir ${item.nomeArquivo} no batch: ${e.message}", e)
                    erros.add("${item.nomeArquivo}: ${e.message}")
                }
            }
        }
        for (id in toEnqueue) {
            queue.add(id)
            desenhoDao.getById(id)?.let { broadcast.sendInsert(it) }
        }
        AppLog.info("[POST queue/batch] concluido: ${ids.size} inseridos, ${erros.size} erros")
        call.respond(io.ktor.http.HttpStatusCode.OK, mapOf(
            "inseridos" to ids.size,
            "ids" to ids,
            "erros" to erros
        ))
    }

    // GET /api/desenhos/{id}
    get("/api/desenhos/{id}") {
        val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        val d = desenhoDao.getById(id) ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse(erro = "Desenho não encontrado"))
        call.respond(d)
    }

    // PATCH /api/desenhos/{id}
    patch("/api/desenhos/{id}") {
        val id = call.parameters["id"] ?: return@patch call.respond(HttpStatusCode.BadRequest)
        val body = call.receive<UpdateBody>()
        desenhoDao.getById(id) ?: return@patch call.respond(HttpStatusCode.NotFound)
        val now = java.time.Instant.now().toString()
        desenhoDao.update(
            id,
            status = body.status,
            horarioAtualizacao = now,
            progresso = body.progresso,
            arquivosProcessados = body.arquivosProcessados,
            erro = body.erro
        )
        desenhoDao.getById(id)?.let { broadcast.sendUpdate(it) }
        call.respond(SuccessResponse(sucesso = true, mensagem = "Desenho atualizado", id = id))
    }

    // POST /api/desenhos/{id}/cancelar
    post("/api/desenhos/{id}/cancelar") {
        val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
        val d = desenhoDao.getById(id) ?: return@post call.respond(HttpStatusCode.NotFound)
        if (d.status == "concluido") {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(erro = "Desenho já concluído"))
            return@post
        }
        if (d.status == "cancelado") {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(erro = "Desenho já cancelado"))
            return@post
        }
        queue.remove(id)
        val now = java.time.Instant.now().toString()
        desenhoDao.update(id, status = "cancelado", horarioAtualizacao = now, canceladoEm = now)
        desenhoDao.getById(id)?.let { broadcast.sendUpdate(it) }
        call.respond(CancelResponse(sucesso = true, mensagem = "Desenho cancelado", id = id, status = "cancelado"))
    }

    // POST /api/desenhos/{id}/retry
    post("/api/desenhos/{id}/retry") {
        val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
        AppLog.info("[RETRY] Recebido retry para desenho $id")
        val d = desenhoDao.getById(id)
        if (d == null) {
            AppLog.warn("[RETRY] Desenho $id não encontrado")
            call.respond(HttpStatusCode.NotFound)
            return@post
        }
        AppLog.info("[RETRY] ${d.nomeArquivo} status=${d.status} solicitados=${d.formatosSolicitados} processados=${d.arquivosProcessados.map { it.tipo }}")
        val solicitados = d.formatosSolicitados.ifEmpty { listOf("pdf") }
        val jaGerados = d.arquivosProcessados.map { it.tipo.lowercase() }.toSet()
        val restantes = solicitados.map { it.lowercase() }.filter { !jaGerados.contains(it) }
            .sortedBy { ProcessingQueue.formatPriority(it) } // DWG sempre por último
        if (restantes.isEmpty()) {
            AppLog.warn("[RETRY] Nada a reprocessar para ${d.nomeArquivo}: solicitados=$solicitados jaGerados=$jaGerados")
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(erro = "Nada a reprocessar: todos os formatos já gerados"))
            return@post
        }
        AppLog.info("[RETRY] Reprocessando ${d.nomeArquivo}: formatos restantes=$restantes")
        val pos = synchronized(queuePositionLock) {
            desenhoDao.countPendentesEProcessando() + 1
        }
        val now = java.time.Instant.now().toString()
        desenhoDao.update(id, status = "pendente", horarioAtualizacao = now, progresso = 0, erro = null, canceladoEm = null, posicaoFila = pos)
        queue.add(id, restantes)
        desenhoDao.getById(id)?.let { broadcast.sendUpdate(it) }
        call.respond(SuccessResponse(sucesso = true, mensagem = "Desenho na fila para reprocessamento", id = id, status = "pendente", posicaoFila = pos, formatosRestantes = restantes))
    }

    // DELETE /api/desenhos/{id} - deleta um desenho
    delete("/api/desenhos/{id}") {
        val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
        desenhoDao.getById(id) ?: return@delete call.respond(HttpStatusCode.NotFound, ErrorResponse(erro = "Desenho não encontrado"))
        queue.remove(id)
        desenhoDao.delete(id)
        broadcast.sendDelete(id)
        call.respond(SuccessResponse(sucesso = true, mensagem = "Desenho deletado", id = id))
    }

    // GET /api/desenhos/{id}/download/{tipo}
    get("/api/desenhos/{id}/download/{tipo}") {
        val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        val tipo = call.parameters["tipo"]?.lowercase() ?: return@get call.respond(HttpStatusCode.BadRequest)
        val d = desenhoDao.getById(id) ?: return@get call.respond(HttpStatusCode.NotFound)
        if (d.status != "concluido" && d.status != "concluido_com_erros") {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(erro = "Desenho ainda não processado. Status: ${d.status}"))
            return@get
        }
        val arquivo = d.arquivosProcessados.find { it.tipo.equals(tipo, true) }
        if (arquivo == null || !File(arquivo.caminho).exists()) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse(erro = "Arquivo do tipo $tipo não encontrado"))
            return@get
        }
        val file = File(arquivo.caminho)
        call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"${arquivo.nome}\"")
        call.respondBytes(file.readBytes(), ContentType.Application.OctetStream)
    }

    // WebSocket /ws - clientes recebem initial + INSERT/UPDATE/DELETE
    webSocket("/ws") {
        broadcast.addSession(this)
        try {
            // Envia dados iniciais apenas para esta sessão (não broadcast global)
            val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }
            fun buildInitial() = """{"type":"initial","data":${json.encodeToString(desenhoDao.list(limit = 10000, offset = 0))}}"""
            send(io.ktor.websocket.Frame.Text(buildInitial()))

            for (frame in incoming) {
                if (frame is io.ktor.websocket.Frame.Text) {
                    val text = frame.readText()
                    // Cliente pode solicitar reenvio dos dados iniciais (ex: refresh manual)
                    if (text.contains("subscribe")) {
                        send(io.ktor.websocket.Frame.Text(buildInitial()))
                    }
                }
            }
        } finally {
            broadcast.removeSession(this)
        }
    }
}
