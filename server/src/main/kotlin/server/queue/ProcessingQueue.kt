package server.queue

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import model.ArquivoProcessado
import model.DesenhoAutodesk
import server.broadcast.Broadcast
import server.config.Config
import server.db.DesenhoDao
import server.inventor.InventorRunner
import server.util.AppLog
import java.io.File

@Serializable
data class QueueItemDetalhe(val desenhoId: String, val formato: String, val posicaoFila: Int, val tentativa: Int)

@Serializable
data class QueueStatus(
    val tamanho: Int,
    val processando: Boolean,
    val processoAtual: String?,
    val processoAtualDetalhe: QueueItemDetalhe?,
    val proximos: List<String>,
    val proximosItens: List<QueueItemDetalhe>
)

/**
 * Fila de processamento (um item = desenhoId + formato).
 * O servidor chama processar-inventor.vbs; o VBS escreve comando.txt e o MacroServidor.bas
 * (rodando dentro do Inventor) faz o processamento pesado e grava sucesso.txt/erro.txt.
 */
class ProcessingQueue(
    private val desenhoDao: DesenhoDao,
    private val broadcast: Broadcast
) {
    private val json = Json { ignoreUnknownKeys = true }
    data class Item(val desenhoId: String, val formato: String, val posicaoFila: Int, val tentativa: Int = 1, val horarioEnvio: String = "")

    private val queue = mutableListOf<Item>()
    private val mutex = Mutex()
    private var processing = false
    private var currentItem: Item? = null
    private var processorJob: Job? = null
    private var lastDesenhoId: String? = null

    /**
     * Progresso individual por formato: chave = "desenhoId:formato", valor = 0..100
     * Formatos concluídos = 100, na fila = 0, em processamento = valor intermediário
     */
    private val progressoPorFormato = mutableMapOf<String, Int>()

    init {
        processorJob = CoroutineScope(Dispatchers.Default).launch {
            processLoop()
        }
    }

    /**
     * Calcula progresso global (0..100) como média dos progressos individuais por formato.
     */
    private fun calcularProgressoGlobal(desenhoId: String, formatosSolicitados: List<String>): Int {
        if (formatosSolicitados.isEmpty()) return 100
        val soma = formatosSolicitados.sumOf { fmt ->
            progressoPorFormato["$desenhoId:$fmt"] ?: 0
        }
        return (soma / formatosSolicitados.size).coerceIn(0, 100)
    }

    /**
     * Atualiza o progresso de um formato e faz broadcast do progresso global.
     * Chamado de dentro de uma thread bloqueante (InventorRunner), então usa runBlocking para broadcast.
     */
    private fun atualizarProgressoFormato(desenhoId: String, formato: String, progresso: Int, formatosSolicitados: List<String>) {
        val key = "$desenhoId:$formato"
        val anterior = progressoPorFormato[key] ?: 0
        if (progresso <= anterior) return // Nunca regride
        progressoPorFormato[key] = progresso
        val global = calcularProgressoGlobal(desenhoId, formatosSolicitados)
        desenhoDao.update(desenhoId, progresso = global)
        val updated = desenhoDao.getById(desenhoId)
        if (updated != null) {
            runBlocking { broadcast.sendUpdate(updated) }
        }
    }

    companion object {
        /** Máximo de tentativas automáticas por formato */
        const val MAX_TENTATIVAS = 3
        /** Delay (ms) entre tentativas para o Inventor se recuperar */
        const val DELAY_RETRY_MS = 15_000L // 15 segundos

        /**
         * Ordem de processamento: PDF > DWF > DWG (somente esses três).
         * DWG sempre por último (mais pesado).
         */
        private val FORMAT_ORDER = mapOf(
            "pdf" to 1,
            "dwf" to 2,
            "dwg" to 10
        )

        fun formatPriority(formato: String): Int = FORMAT_ORDER[formato.lowercase()] ?: 50
    }

    suspend fun add(desenhoId: String, formatosOverride: List<String>? = null) {
        val desenho = desenhoDao.getById(desenhoId) ?: return
        // Ordena formatos por prioridade ANTES de adicionar (DWG sempre por último)
        val formatos = (formatosOverride?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }
            ?: desenho.formatosSolicitados.ifEmpty { listOf("pdf") })
            .sortedBy { formatPriority(it) }
        val pos = desenhoDao.countPendentesEProcessando()
        AppLog.info("[QUEUE] Adicionando ${desenho.nomeArquivo}: formatos=${formatos} (ordenados por prioridade)")
        mutex.withLock {
            // Ignora se este desenho já está sendo processado agora
            if (currentItem?.desenhoId == desenhoId) {
                AppLog.info("[QUEUE] Ignorando add para ${desenho.nomeArquivo}: já está em processamento ativo")
                return
            }
            formatos.forEach { f ->
                if (!queue.any { it.desenhoId == desenhoId && it.formato == f })
                    queue.add(Item(desenhoId, f, desenho.posicaoFila ?: pos, 1, desenho.horarioEnvio))
            }
            // Ordena fila: posicao (FIFO) > horario_envio (primeiro recebido) > formato (DWG por ultimo)
            sortQueue()
        }
        // Limpa progresso antigo ao adicionar (reenviar)
        progressoPorFormato.keys.filter { it.startsWith("$desenhoId:") }.forEach { progressoPorFormato.remove(it) }
    }

    /** Ordena a fila por ordem de recebimento (FIFO): posicao_fila, depois horario_envio, depois formato (DWG por ultimo). */
    private fun sortQueue() {
        queue.sortWith(compareBy(
            { it.posicaoFila },
            { it.horarioEnvio },
            { it.desenhoId },
            { formatPriority(it.formato) }
        ))
    }

    fun remove(desenhoId: String): Boolean {
        var removed = false
        runBlocking {
            mutex.withLock {
                val before = queue.size
                queue.removeAll { it.desenhoId == desenhoId }
                removed = queue.size < before
            }
        }
        // Limpa progresso deste desenho
        progressoPorFormato.keys.filter { it.startsWith("$desenhoId:") }.forEach { progressoPorFormato.remove(it) }
        return removed
    }

    fun getStatus(): QueueStatus = runBlocking {
        mutex.withLock {
            QueueStatus(
                tamanho = queue.size,
                processando = processing,
                processoAtual = currentItem?.let { "${it.desenhoId} (${it.formato}) tentativa ${it.tentativa}" },
                processoAtualDetalhe = currentItem?.let { QueueItemDetalhe(it.desenhoId, it.formato, it.posicaoFila, it.tentativa) },
                proximos = queue.take(10).map { "pos${it.posicaoFila} ${it.desenhoId}:${it.formato} t${it.tentativa}" },
                proximosItens = queue.take(50).map { QueueItemDetalhe(it.desenhoId, it.formato, it.posicaoFila, it.tentativa) }
            )
        }
    }

    private suspend fun processLoop() {
        while (true) {
            var itemVar: Item? = null
            try {
                itemVar = mutex.withLock {
                if (queue.isEmpty()) {
                    processing = false
                    currentItem = null
                    lastDesenhoId = null
                    null
                } else {
                    processing = true
                    val sameDesenhoIdx = lastDesenhoId?.let { did ->
                        queue.indexOfFirst { it.desenhoId == did }.takeIf { it >= 0 }
                    }
                    val nextIdx = sameDesenhoIdx ?: 0
                    currentItem = queue.removeAt(nextIdx)
                    currentItem
                }
            }
            if (itemVar == null) {
                delay(1000)
                continue
            }
            val item = itemVar!!
            
            if (lastDesenhoId != null && lastDesenhoId != item.desenhoId) {
                val prevId = lastDesenhoId!!
                val prevStillPending = mutex.withLock { queue.any { it.desenhoId == prevId } }
                if (prevStillPending) {
                    AppLog.info("[QUEUE] Trocando de desenho: $prevId ainda tem formatos pendentes -> voltando para 'pendente'")
                    desenhoDao.update(prevId, status = "pendente")
                    desenhoDao.getById(prevId)?.let { runBlocking { broadcast.sendUpdate(it) } }
                }
            }
            lastDesenhoId = item.desenhoId

            var desenho = desenhoDao.getById(item.desenhoId)
            if (desenho == null || desenho.status == "cancelado") {
                lastDesenhoId = null
                currentItem = null
                continue
            }

            // Inicializa progresso dos formatos para este desenho
            val formatosSolicitados = desenho.formatosSolicitados.ifEmpty { listOf("pdf") }
            formatosSolicitados.forEach { fmt ->
                val key = "${item.desenhoId}:$fmt"
                if (key !in progressoPorFormato) progressoPorFormato[key] = 0
            }
            // Marca formatos já concluídos anteriormente como 100%
            desenho.arquivosProcessados.forEach { arq ->
                val key = "${item.desenhoId}:${arq.tipo.lowercase()}"
                progressoPorFormato[key] = 100
            }

            desenhoDao.update(item.desenhoId, status = "processando", progresso = calcularProgressoGlobal(item.desenhoId, formatosSolicitados))
            desenhoDao.getById(item.desenhoId)?.let { broadcast.sendUpdate(it) }
            val tentativaInfo = if (item.tentativa > 1) " [tentativa ${item.tentativa}/$MAX_TENTATIVAS]" else ""
            AppLog.info("Processando desenho ${item.desenhoId} formato ${item.formato}$tentativaInfo (entrada: ${desenho.nomeArquivo})")

            val arquivoEntrada = InventorRunner.resolverArquivoEntrada(
                desenho.arquivoOriginal,
                desenho.pastaProcessamento,
                desenho.nomeArquivo
            )
            // Em modo dev, bypassa verificação de arquivo (IDW não existe localmente)
            if (!Config.isDevMode && (arquivoEntrada.isEmpty() || !File(arquivoEntrada).exists())) {
                val msg = "Arquivo original não encontrado: ${desenho.arquivoOriginal}"
                AppLog.warn("$msg [desenho=${item.desenhoId}]")
                desenhoDao.update(item.desenhoId, status = "erro", erro = msg)
                broadcast.sendUpdate(desenhoDao.getById(item.desenhoId)!!)
                lastDesenhoId = null
                currentItem = null
                continue
            }

            val pastaSaida = InventorRunner.destinoParaExportados(desenho.caminhoDestino)
            val pastaControle = InventorRunner.pastaControle()

            val result = withContext(Dispatchers.IO) {
                InventorRunner.run(arquivoEntrada, pastaSaida, item.formato, pastaControle) { progressoFormato ->
                    atualizarProgressoFormato(item.desenhoId, item.formato, progressoFormato, formatosSolicitados)
                }
            }

            val reloaded = desenhoDao.getById(item.desenhoId)
            if (reloaded == null) { lastDesenhoId = null; currentItem = null; continue }
            desenho = reloaded
            if (desenho.status == "cancelado") {
                lastDesenhoId = null
                currentItem = null
                continue
            }

            val existentes = desenho.arquivosProcessados.toMutableList()
            
            // Rastrear formatos com erro
            val errosAnteriores = desenho.erro?.split(";")?.filter { it.isNotBlank() }?.toMutableList() ?: mutableListOf()
            
            if (result.success && result.arquivoGerado != null) {
                AppLog.info("Formato ${item.formato} concluído para ${item.desenhoId}: ${result.arquivoGerado}")
                // Marca formato como 100%
                progressoPorFormato["${item.desenhoId}:${item.formato}"] = 100
                val novo = ArquivoProcessado(
                    nome = File(result.arquivoGerado).name,
                    tipo = item.formato,
                    caminho = result.arquivoGerado,
                    tamanho = File(result.arquivoGerado).length()
                )
                existentes.removeAll { it.tipo.equals(item.formato, ignoreCase = true) }
                existentes.add(novo)
                // Remove erro deste formato se existia
                errosAnteriores.removeAll { it.startsWith("${item.formato}:") }
            } else {
                val errMsg = result.errorMessage ?: "Erro no processamento"
                AppLog.error("Erro ao processar ${item.desenhoId} formato ${item.formato} (tentativa ${item.tentativa}/${MAX_TENTATIVAS}): $errMsg")

                // Auto-retry: re-adicionar à fila se não esgotou tentativas
                if (item.tentativa < MAX_TENTATIVAS) {
                    val proxTentativa = item.tentativa + 1
                    AppLog.info("[AUTO-RETRY] Reagendando ${desenho.nomeArquivo} formato ${item.formato} -> tentativa $proxTentativa/$MAX_TENTATIVAS (aguardando ${DELAY_RETRY_MS / 1000}s)")
                    delay(DELAY_RETRY_MS)
                    mutex.withLock {
                        queue.add(Item(item.desenhoId, item.formato, item.posicaoFila, proxTentativa, item.horarioEnvio))
                        sortQueue()
                    }
                    desenhoDao.update(item.desenhoId, status = "processando")
                    desenhoDao.getById(item.desenhoId)?.let { broadcast.sendUpdate(it) }
                    currentItem = null
                    continue
                }

                // Esgotou tentativas — registra erro definitivo
                AppLog.error("[AUTO-RETRY] ${desenho.nomeArquivo} formato ${item.formato} FALHOU após $MAX_TENTATIVAS tentativas")
                errosAnteriores.removeAll { it.startsWith("${item.formato}:") }
                errosAnteriores.add("${item.formato}: falhou após ${MAX_TENTATIVAS} tentativas")
            }
            
            // Calcula progresso via sistema de progresso por formato
            val totalFormatos = formatosSolicitados.size
            val concluidos = formatosSolicitados.count { fmt ->
                existentes.any { it.tipo.equals(fmt, ignoreCase = true) }
            }
            
            // Verifica se ainda há formatos pendentes na fila para este desenho
            val formatosPendentes = mutex.withLock {
                queue.filter { it.desenhoId == item.desenhoId }.map { it.formato }
            }
            val todoProcessado = formatosPendentes.isEmpty()
            
            // Se TODOS os formatos foram concluídos com sucesso, limpa erros antigos (retry resolveu)
            if (concluidos >= totalFormatos && todoProcessado) {
                errosAnteriores.clear()
            }

            // Determina status final
            val novoStatus = when {
                !todoProcessado -> "processando" // Ainda há formatos na fila
                errosAnteriores.isEmpty() && concluidos >= totalFormatos -> "concluido"
                errosAnteriores.isNotEmpty() && concluidos > 0 -> "concluido_com_erros"
                errosAnteriores.isNotEmpty() -> "erro"
                else -> if (concluidos > 0) "concluido_com_erros" else "erro" // Fallback: se terminou tudo e não é sucesso 100%, é erro
            }
            
            val progresso = calcularProgressoGlobal(item.desenhoId, formatosSolicitados)
            
            desenhoDao.update(
                item.desenhoId,
                status = novoStatus,
                progresso = progresso,
                arquivosProcessados = json.encodeToString(existentes),
                erro = if (errosAnteriores.isEmpty()) null else errosAnteriores.joinToString("; ")
            )
            desenhoDao.getById(item.desenhoId)?.let { broadcast.sendUpdate(it) }

            // Limpa progresso do mapa quando desenho termina completamente
            if (todoProcessado) {
                lastDesenhoId = null
                formatosSolicitados.forEach { fmt ->
                    progressoPorFormato.remove("${item.desenhoId}:$fmt")
                }
            }
            currentItem = null
        } catch (e: Exception) {
            AppLog.error("[QUEUE] Erro crítico no loop de processamento do desenho ${itemVar?.desenhoId}: ${e.message}", e)
            lastDesenhoId = null
            currentItem = null
            delay(2000)
        }
        }
    }
}
