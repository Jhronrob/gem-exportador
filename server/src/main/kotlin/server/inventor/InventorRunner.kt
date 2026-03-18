package server.inventor

import server.config.Config
import server.util.AppLog
import java.io.File
import java.time.Instant

/**
 * Executa o script processar-inventor.vbs para um único formato.
 * O VBS escreve comando.txt na pasta de controle; o MacroServidor.bas (rodando dentro do Inventor)
 * lê o comando, processa e grava sucesso.txt ou erro.txt.
 */
object InventorRunner {

    private const val VBS_NAME = "processar-inventor.vbs"
    private const val TIMEOUT_MINUTES = 30  // DWGs pesados de assembly podem demorar >15 min

    fun isWindows(): Boolean =
        System.getProperty("os.name").lowercase().contains("windows")

    /**
     * Localiza o script processar-inventor.vbs (em scripts/ na raiz do projeto ou em server/../scripts).
     */
    fun findScriptPath(): File? {
        val userDir = File(System.getProperty("user.dir"))
        val candidates = listOf(
            File(userDir, "scripts/$VBS_NAME"),
            File(userDir, "../scripts/$VBS_NAME"),
            File(userDir.parentFile, "scripts/$VBS_NAME"),
            File(userDir, "../../scripts/$VBS_NAME")
        )
        return candidates.firstOrNull { it.isFile }
    }

    /**
     * Pasta onde o VBS escreve comando.txt e o macro Inventor lê (jhonrob_controle_pasta.txt).
     * Lê de INVENTOR_PASTA_CONTROLE no .env ou variável de ambiente; senão usa ./data/controle.
     */
    fun pastaControle(): File {
        val fromConfig = Config.inventorPastaControle
        if (fromConfig != null) return File(fromConfig.replace("/", File.separator)).also { it.mkdirs() }
        val userDir = File(System.getProperty("user.dir"))
        return File(userDir, "data/controle").also { it.mkdirs() }
    }

    /**
     * Destino dos exportados: substitui "desenhos gerenciador 3d" por "DESENHOS GERENCIADOR".
     */
    fun destinoParaExportados(caminhoDestino: String): String {
        if (caminhoDestino.isBlank()) return caminhoDestino
        return caminhoDestino.replace(Regex("""desenhos\s+gerenciador\s+3d""", RegexOption.IGNORE_CASE), "DESENHOS GERENCIADOR")
    }

    /**
     * Resolve o arquivo de entrada: SEMPRE prioriza o IDW (arquivo principal).
     * O arquivo_original pode ser .ipt/.iam (referência), mas quem abre é o IDW.
     * Usa o diretório do arquivo_original para encontrar o IDW pelo nome_arquivo.
     */
    fun resolverArquivoEntrada(arquivoOriginal: String?, pastaProcessamento: String?, nomeArquivo: String): String {
        val raw = (arquivoOriginal ?: "").trim()
        val nome = nomeArquivo.trim().ifEmpty { return "" }

        // Loga referência (.ipt/.iam) - só para diagnóstico
        if (raw.isNotEmpty()) {
            val refFile = File(raw.replace("/", File.separator))
            if (refFile.exists()) {
                AppLog.info("Referência encontrada: ${refFile.absolutePath}")
            } else {
                val nomeSemExt = refFile.nameWithoutExtension
                val dir = refFile.parentFile
                if (dir != null) {
                    val altIam = File(dir, "$nomeSemExt.iam")
                    val altIpt = File(dir, "$nomeSemExt.ipt")
                    when {
                        altIam.exists() -> AppLog.info("Referência .iam encontrada: ${altIam.absolutePath}")
                        altIpt.exists() -> AppLog.info("Referência .ipt encontrada: ${altIpt.absolutePath}")
                        else -> AppLog.warn("Referência não encontrada: ${refFile.absolutePath} (nem .ipt nem .iam)")
                    }
                }
            }
        }

        // 1) Tenta o IDW no diretório do arquivo_original
        if (raw.isNotEmpty()) {
            val dir = File(raw.replace("/", File.separator)).parentFile
            if (dir != null) {
                val idw = File(dir, nome)
                if (idw.exists()) {
                    AppLog.info("IDW encontrado: ${idw.absolutePath}")
                    return idw.absolutePath
                }
            }
        }

        // 2) Tenta pastaProcessamento + nome_arquivo
        val base = pastaProcessamento?.trim()?.takeIf { it.isNotEmpty() }
        if (base != null) {
            val idw = File(base, nome)
            if (idw.exists()) {
                AppLog.info("IDW encontrado em pastaProcessamento: ${idw.absolutePath}")
                return idw.absolutePath
            }
        }

        // 3) Fallback: o arquivo_original direto (caso já seja IDW)
        if (raw.isNotEmpty()) {
            val f = File(raw.replace("/", File.separator))
            if (f.exists()) {
                AppLog.info("Usando arquivo_original direto: ${f.absolutePath}")
                return f.absolutePath
            }
        }

        // 4) Não encontrou nada
        val dirFallback = File(raw.replace("/", File.separator)).parentFile
        val caminhoEsperado = if (dirFallback != null) File(dirFallback, nome).absolutePath else nome
        AppLog.warn("IDW não encontrado: $caminhoEsperado")
        return caminhoEsperado
    }

    data class Result(
        val success: Boolean,
        val arquivoGerado: String?, // caminho absoluto do arquivo gerado
        val errorMessage: String?
    )

    /**
     * Etapas de progresso estimado por formato (0-100).
     * Cada etapa tem uma porcentagem-alvo e uma descrição.
     */
    object ProgressStages {
        const val QUEUED = 0           // Na fila
        const val VBS_STARTING = 5     // VBScript iniciando
        const val VBS_RUNNING = 10     // VBScript rodando, comando.txt escrito
        const val INVENTOR_OPENING = 20 // Inventor abrindo documento (estimado)
        const val EXPORT_STARTED = 35  // SaveCopyAs chamado (estimado)
        const val EXPORT_WAITING = 40  // Aguardando arquivo no disco
        const val EXPORT_MAX_WAIT = 88 // Máximo durante espera (antes de finalizar)
        const val FILE_FOUND = 92     // Arquivo encontrado
        const val DONE = 100          // Concluído
    }

    /**
     * Mock de processamento para modo dev (sem Autodesk/Windows).
     * Simula a progressão real com delays configuráveis e cria um arquivo dummy de saída.
     */
    private fun runMock(
        nomeBase: String,
        formato: String,
        onProgress: (Int) -> Unit
    ): Result {
        val delayMs = when (formato.lowercase()) {
            "pdf" -> Config.devMockPdfMs
            "dwf" -> Config.devMockDwfMs
            "dwg" -> Config.devMockDwgMs
            else -> 5000L
        }
        AppLog.info("[DEV-MOCK] Simulando exportação $formato para '$nomeBase' (~${delayMs / 1000}s)")

        val steps = listOf(5, 10, 20, 35, 50, 65, 75, 83, 90, 97, 100)
        val stepDelay = delayMs / steps.size

        for (p in steps) {
            Thread.sleep(stepDelay)
            onProgress(p)
        }

        val outDir = File(Config.devOutputDir).apply { mkdirs() }
        val outFile = File(outDir, "$nomeBase.$formato")
        outFile.writeText(
            "[DEV-MOCK] Arquivo gerado em ${Instant.now()} | formato=$formato | arquivo=$nomeBase.$formato"
        )

        AppLog.info("[DEV-MOCK] Concluído: ${outFile.absolutePath}")
        return Result(true, outFile.absolutePath, null)
    }

    /**
     * Executa cscript processar-inventor.vbs para um formato.
     * Em modo dev (GEM_MODE=dev), usa runMock() sem precisar de Windows/Autodesk.
     *
     * @param onProgress callback chamado com progresso estimado (0-100) durante a execução
     */
    fun run(
        arquivoEntrada: String,
        pastaSaida: String,
        formato: String,
        pastaControle: File,
        onProgress: (Int) -> Unit = {}
    ): Result {
        onProgress(ProgressStages.VBS_STARTING)

        // Modo dev: simula processamento sem precisar do Inventor/Windows
        if (Config.isDevMode) {
            val nomeBase = File(arquivoEntrada).nameWithoutExtension.ifBlank { "arquivo_dev" }
            return runMock(nomeBase, formato, onProgress)
        }

        if (!isWindows()) {
            AppLog.warn("InventorRunner: processamento só disponível no Windows")
            return Result(false, null, "Processamento Inventor só disponível no Windows")
        }
        val script = findScriptPath()
        if (script == null || !script.exists()) {
            AppLog.error("Script processar-inventor.vbs não encontrado")
            return Result(
                false,
                null,
                "Script processar-inventor.vbs não encontrado. Procurou em: ${listOf(File(System.getProperty("user.dir"), "scripts/$VBS_NAME").absolutePath)}"
            )
        }
        // Normaliza o caminho: preserva UNC (\\servidor), remove barras duplicadas no meio
        var pastaSaidaNorm = pastaSaida.replace("/", "\\")
        
        // Verifica se é caminho UNC (começa com \\)
        val isUnc = pastaSaidaNorm.startsWith("\\\\")
        if (isUnc) {
            // Remove o prefixo UNC temporariamente
            pastaSaidaNorm = pastaSaidaNorm.substring(2)
        }
        
        // Remove barras duplicadas no meio do caminho
        while (pastaSaidaNorm.contains("\\\\")) {
            pastaSaidaNorm = pastaSaidaNorm.replace("\\\\", "\\")
        }
        
        // Restaura o prefixo UNC se necessário
        if (isUnc) {
            pastaSaidaNorm = "\\\\$pastaSaidaNorm"
        }
        
        // Remove barra final (o VBS vai adicionar se necessário)
        while (pastaSaidaNorm.endsWith("\\")) {
            pastaSaidaNorm = pastaSaidaNorm.dropLast(1)
        }
        val formatoNorm = formato.trim().lowercase()
        val controlePath = pastaControle.absolutePath

        onProgress(ProgressStages.VBS_RUNNING)

        val pb = ProcessBuilder(
            "cscript",
            "//Nologo",
            script.absolutePath,
            arquivoEntrada,
            pastaSaidaNorm,
            formatoNorm,
            controlePath
        )
        pb.redirectErrorStream(false)
        pb.directory(script.parentFile)
        AppLog.info("Executando VBS: ${script.absolutePath} entrada=$arquivoEntrada saida=$pastaSaidaNorm formato=$formatoNorm")
        val process = pb.start()

        // Lê stdout/stderr em threads separadas para não bloquear
        var stderrContent = ""
        var stdoutContent = ""
        val stderrThread = Thread { stderrContent = process.errorStream.bufferedReader().readText() }.also { it.start() }
        val stdoutThread = Thread { stdoutContent = process.inputStream.bufferedReader().readText() }.also { it.start() }

        // =============================================
        // Loop de progresso suave enquanto VBS roda
        // =============================================
        val timeoutMs = TIMEOUT_MINUTES * 60_000L
        val startTime = System.currentTimeMillis()
        var currentProgress = ProgressStages.VBS_RUNNING  // 10
        val isDwg = formatoNorm == "dwg"

        // Tempos estimados por etapa (ms desde o início)
        // DWG demora muito mais, então as etapas são mais espaçadas
        val estimatedOpenMs  = if (isDwg) 15_000L else 5_000L    // Inventor abrindo doc
        val estimatedExportMs = if (isDwg) 30_000L else 10_000L  // SaveCopyAs iniciado
        val estimatedWaitMs  = if (isDwg) 60_000L else 20_000L   // Aguardando arquivo

        while (process.isAlive) {
            val elapsed = System.currentTimeMillis() - startTime

            // Timeout check
            if (elapsed >= timeoutMs) {
                process.destroyForcibly()
                stderrThread.join(2000)
                stdoutThread.join(2000)
                val errInfo = stderrContent.ifBlank { stdoutContent }.trim().take(300)
                AppLog.error("Timeout Inventor: processamento excedeu $TIMEOUT_MINUTES minutos. $errInfo")
                return Result(false, null, "Timeout: processamento excedeu $TIMEOUT_MINUTES minutos. $errInfo")
            }

            // Estimativa de progresso por tempo decorrido
            val newProgress = when {
                elapsed < estimatedOpenMs ->
                    ProgressStages.VBS_RUNNING + ((ProgressStages.INVENTOR_OPENING - ProgressStages.VBS_RUNNING) * elapsed / estimatedOpenMs).toInt()
                elapsed < estimatedExportMs ->
                    ProgressStages.INVENTOR_OPENING + ((ProgressStages.EXPORT_STARTED - ProgressStages.INVENTOR_OPENING) * (elapsed - estimatedOpenMs) / (estimatedExportMs - estimatedOpenMs)).toInt()
                elapsed < estimatedWaitMs ->
                    ProgressStages.EXPORT_STARTED + ((ProgressStages.EXPORT_WAITING - ProgressStages.EXPORT_STARTED) * (elapsed - estimatedExportMs) / (estimatedWaitMs - estimatedExportMs)).toInt()
                else -> {
                    // Após o tempo de espera estimado, cresce lentamente até EXPORT_MAX_WAIT
                    // Usa curva logarítmica para desacelerar conforme demora mais
                    val extraElapsed = elapsed - estimatedWaitMs
                    val extraRange = ProgressStages.EXPORT_MAX_WAIT - ProgressStages.EXPORT_WAITING
                    val factor = (Math.log10(1.0 + extraElapsed / 10_000.0) / Math.log10(1.0 + timeoutMs / 10_000.0)).coerceIn(0.0, 1.0)
                    (ProgressStages.EXPORT_WAITING + (extraRange * factor).toInt())
                }
            }.coerceIn(currentProgress, ProgressStages.EXPORT_MAX_WAIT)  // Nunca regride

            if (newProgress > currentProgress) {
                currentProgress = newProgress
                onProgress(currentProgress)
            }

            Thread.sleep(3_000)  // Atualiza a cada 3 segundos
        }

        // Processo terminou — aguarda threads de leitura
        stderrThread.join(5000)
        stdoutThread.join(5000)

        val exitCode = process.exitValue()
        if (exitCode != 0) {
            val msg = (stderrContent.ifBlank { stdoutContent }).trim().take(500)
            AppLog.error("VBS retornou código $exitCode: $msg")
            return Result(false, null, "Script VBS retornou erro (código $exitCode): $msg")
        }

        onProgress(ProgressStages.FILE_FOUND)

        val nomeBase = File(arquivoEntrada).nameWithoutExtension
        val arquivoEsperado = File(pastaSaidaNorm.trim(), "$nomeBase.$formatoNorm")
        val caminhoReal = if (arquivoEsperado.exists()) arquivoEsperado.absolutePath else null

        onProgress(ProgressStages.DONE)
        return Result(true, caminhoReal, null)
    }
}
