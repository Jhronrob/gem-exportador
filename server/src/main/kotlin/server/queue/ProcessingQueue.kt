package server.queue

// ============================================================
// CORREÇÕES 3a e 3b — ProcessingQueue.kt
//
// CORREÇÃO 3a: mutableMapOf → ConcurrentHashMap (linha 54)
//   PROBLEMA: progressoPorFormato era um LinkedHashMap comum,
//     não thread-safe. Acessado simultaneamente pelo processLoop,
//     pelo callback de progresso do Inventor (thread IO) e por
//     remove() (thread de request Ktor). Risco de
//     ConcurrentModificationException ou dados corrompidos.
//   SOLUÇÃO: java.util.concurrent.ConcurrentHashMap
//     Mesma interface MutableMap — zero mudanças nos callers.
//
// CORREÇÃO 3b: passar clearErro = true quando sem erros (linha ~428)
//   PROBLEMA: desenhoDao.update(erro = null) não incluía o campo
//     no SQL → valor antigo permanecia no banco após retry com sucesso.
//   SOLUÇÃO: passar clearErro = errosAnteriores.isEmpty()
//     Quando não há erros, instrui o DAO a gravar NULL explícito.
//
// Linhas alteradas marcadas com: // ← CORRIGIDO
// ============================================================

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
import java.util.concurrent.ConcurrentHashMap  // ← CORRIGIDO 3a: import adicionado

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
     *
     * CORREÇÃO 3a: ConcurrentHashMap em vez de mutableMapOf (LinkedHashMap).
     * Thread-safe para leituras e escritas simultâneas de múltiplos contextos.
     */
    private val progressoPorFormato = ConcurrentHashMap<String, Int>()  // ← CORRIGIDO 3a

    /** Liberado pelo startup guard após resetar todos os itens stale e reindexar posições. */
    private val startupReady = CompletableDeferred<Unit>()

    init {
        processorJob = CoroutineScope(Dispatchers.Default).launch {
            processLoop()
        }
        CoroutineScope(Dispatchers.Default).launch {
            watchdogLoop()
        }
    }

    /**
     * Watchdog periódico: a cada 2 minutos varre o DB em busca de itens "processando" ou "pendente"
     * que já têm todos os formatos em arquivosProcessados.
     */
    private suspend fun watchdogLoop() {
        delay(60_000L)
        while (true) {
            try {
                val candidates = withContext(Dispatchers.IO) {
                    desenhoDao.list(status = "processando", limit = 200, offset = 0) +
                    desenhoDao.list(status = "pendente",    limit = 200, offset = 0)
                }
                for (d in candidates) {
                    val solicitados = d.formatosSolicitados.ifEmpty { listOf("pdf") }
                    val jaGerados = d.arquivosProcessados.map { it.tipo.lowercase() }.toSet()
                    if (solicitados.all { it.lowercase() in jaGerados }) {
                        val estaAtivo = mutex.withLock {
                            currentItem?.desenhoId == d.id || queue.any { it.desenhoId == d.id }
                        }
                        if (!estaAtivo) {
                            AppLog.warn("[WATCHDOG] Item preso detectado: ${d.nomeArquivo} (${d.id}) status='${d.status}' mas todos os formatos já gerados — concluindo e limpando posicao_fila")
                            withContext(Dispatchers.IO) {
                                desenhoDao.update(d.id, status = "concluido", progresso = 100, clearPosicaoFila = true)
                                desenhoDao.getById(d.id)?.let { broadcast.sendUpdate(it) }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                AppLog.error("[WATCHDOG] Erro no scan: ${e.message}", e)
            }
            delay(2 * 60_000L)
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
     */
    private fun atualizarProgressoFormato(desenhoId: String, formato: String, progresso: Int, formatosSolicitados: List<String>) {
        val key = "$desenhoId:$formato"
        val anterior = progressoPorFormato[key] ?: 0
        if (progresso <= anterior) return
        progressoPorFormato[key] = progresso
        val global = calcularProgressoGlobal(desenhoId, formatosSolicitados)
        desenhoDao.update(desenhoId, progresso = global)
        val updated = desenhoDao.getById(desenhoId)
        if (updated != null) {
            runBlocking { broadcast.sendUpdate(updated) }
        }
    }

    companion object {
        const val MAX_TENTATIVAS = 3
        const val DELAY_RETRY_MS = 15_000L

        private val FORMAT_ORDER = mapOf(
            "pdf" to 1,
            "dwf" to 2,
            "dwg" to 10
        )

        fun formatPriority(formato: String): Int = FORMAT_ORDER[formato.lowercase()] ?: 50
    }

    suspend fun add(desenhoId: String, formatosOverride: List<String>? = null) {
        val desenho = desenhoDao.getById(desenhoId) ?: return

        val jaGerados: Set<String> = if (formatosOverride == null) {
            desenho.arquivosProcessados.map { it.tipo.lowercase() }.toSet()
        } else {
            emptySet()
        }

        val formatos = (formatosOverride?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }
            ?: desenho.formatosSolicitados.ifEmpty { listOf("pdf") })
            .filter { it !in jaGerados }
            .sortedBy { formatPriority(it) }

        if (formatos.isEmpty()) {
            AppLog.info("[QUEUE] Ignorando add para ${desenho.nomeArquivo}: todos os formatos já gerados ${desenho.arquivosProcessados.map { it.tipo }}")
            return
        }
        val pos = desenhoDao.countPendentesEProcessando()
        AppLog.info("[QUEUE] Adicionando ${desenho.nomeArquivo}: formatos=${formatos} (ordenados por prioridade)")
        mutex.withLock {
            if (currentItem?.desenhoId == desenhoId) {
                AppLog.info("[QUEUE] Ignorando add para ${desenho.nomeArquivo}: já está em processamento ativo")
                return
            }
            formatos.forEach { f ->
                if (!queue.any { it.desenhoId == desenhoId && it.formato == f })
                    queue.add(Item(desenhoId, f, desenho.posicaoFila ?: pos, 1, desenho.horarioEnvio))
            }
            sortQueue()
        }
        progressoPorFormato.keys.filter { it.startsWith("$desenhoId:") }.forEach { progressoPorFormato.remove(it) }
    }

    private fun sortQueue() {
        queue.sortWith(compareBy(
            { it.posicaoFila },
            { it.horarioEnvio },
            { it.desenhoId },
            { formatPriority(it.formato) }
        ))
    }

    fun markStartupComplete() {
        startupReady.complete(Unit)
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
        startupReady.await()
        AppLog.info("[QUEUE] Startup concluído — iniciando processamento da fila")

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

                val formatosSolicitados = desenho.formatosSolicitados.ifEmpty { listOf("pdf") }
                formatosSolicitados.forEach { fmt ->
                    val key = "${item.desenhoId}:$fmt"
                    if (!progressoPorFormato.containsKey(key)) progressoPorFormato[key] = 0
                }
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
                val errosAnteriores = desenho.erro?.split(";")?.filter { it.isNotBlank() }?.toMutableList() ?: mutableListOf()

                val arquivoReconciliado: String? = if (!result.success || result.arquivoGerado == null) {
                    val nomeBase = File(arquivoEntrada).nameWithoutExtension
                    val ext = item.formato.lowercase()
                    val esperado = File(pastaSaida.trim(), "$nomeBase.$ext")
                    if (esperado.exists()) {
                        AppLog.info("[RECONCILE] Arquivo encontrado no destino apesar de falha reportada: ${esperado.absolutePath}")
                        esperado.absolutePath
                    } else null
                } else null

                val arquivoGeradoFinal = result.arquivoGerado ?: arquivoReconciliado
                val sucessoFinal = result.success || arquivoReconciliado != null

                if (sucessoFinal && arquivoGeradoFinal != null) {
                    AppLog.info("Formato ${item.formato} concluído para ${item.desenhoId}: $arquivoGeradoFinal")
                    progressoPorFormato["${item.desenhoId}:${item.formato}"] = 100
                    val novo = ArquivoProcessado(
                        nome = File(arquivoGeradoFinal).name,
                        tipo = item.formato,
                        caminho = arquivoGeradoFinal,
                        tamanho = File(arquivoGeradoFinal).length()
                    )
                    existentes.removeAll { it.tipo.equals(item.formato, ignoreCase = true) }
                    existentes.add(novo)
                    errosAnteriores.removeAll { it.startsWith("${item.formato}:") }
                } else {
                    val errMsg = result.errorMessage ?: "Erro no processamento"
                    AppLog.error("Erro ao processar ${item.desenhoId} formato ${item.formato} (tentativa ${item.tentativa}/${MAX_TENTATIVAS}): $errMsg")

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

                    AppLog.error("[AUTO-RETRY] ${desenho.nomeArquivo} formato ${item.formato} FALHOU após $MAX_TENTATIVAS tentativas")
                    errosAnteriores.removeAll { it.startsWith("${item.formato}:") }
                    errosAnteriores.add("${item.formato}: falhou após ${MAX_TENTATIVAS} tentativas")
                }

                val totalFormatos = formatosSolicitados.size
                val concluidos = formatosSolicitados.count { fmt ->
                    existentes.any { it.tipo.equals(fmt, ignoreCase = true) }
                }

                val formatosPendentes = mutex.withLock {
                    queue.filter { it.desenhoId == item.desenhoId }.map { it.formato }
                }
                val todoProcessado = formatosPendentes.isEmpty()

                if (concluidos >= totalFormatos && todoProcessado) {
                    errosAnteriores.clear()
                }

                val novoStatus = when {
                    !todoProcessado -> "processando"
                    errosAnteriores.isEmpty() && concluidos >= totalFormatos -> "concluido"
                    errosAnteriores.isNotEmpty() && concluidos > 0 -> "concluido_com_erros"
                    errosAnteriores.isNotEmpty() -> "erro"
                    else -> if (concluidos > 0) "concluido_com_erros" else "erro"
                }

                val progresso = calcularProgressoGlobal(item.desenhoId, formatosSolicitados)
                val statusTerminal = novoStatus in setOf("concluido", "concluido_com_erros", "erro", "cancelado")

                desenhoDao.update(
                    item.desenhoId,
                    status = novoStatus,
                    progresso = progresso,
                    arquivosProcessados = json.encodeToString(existentes),
                    erro = if (errosAnteriores.isEmpty()) null else errosAnteriores.joinToString("; "),
                    clearErro = errosAnteriores.isEmpty(),   // ← CORRIGIDO 3b: limpa NULL no banco quando sem erros
                    clearPosicaoFila = statusTerminal && todoProcessado
                )
                desenhoDao.getById(item.desenhoId)?.let { broadcast.sendUpdate(it) }

                if (todoProcessado) {
                    lastDesenhoId = null
                    formatosSolicitados.forEach { fmt ->
                        progressoPorFormato.remove("${item.desenhoId}:$fmt")
                    }
                }
                currentItem = null

            } catch (e: Exception) {
                AppLog.error("[QUEUE] Erro crítico no loop de processamento do desenho ${itemVar?.desenhoId}: ${e.message}", e)
                val itemFailed = itemVar
                if (itemFailed != null) {
                    try {
                        withContext(Dispatchers.IO) {
                            val d = desenhoDao.getById(itemFailed.desenhoId)
                            if (d?.status == "processando") {
                                val msg = "Erro interno no processamento: ${e.message?.take(200) ?: "exceção desconhecida"}"
                                AppLog.warn("[QUEUE] Marcando ${d.nomeArquivo} como 'erro' após exceção crítica")
                                desenhoDao.update(itemFailed.desenhoId, status = "erro", erro = msg, clearPosicaoFila = true)
                                desenhoDao.getById(itemFailed.desenhoId)?.let { broadcast.sendUpdate(it) }
                            }
                        }
                    } catch (inner: Exception) {
                        AppLog.error("[QUEUE] Falha ao atualizar status após exceção: ${inner.message}", inner)
                    }
                }
                lastDesenhoId = null
                currentItem = null
                delay(2000)
            }
        }
    }
}
