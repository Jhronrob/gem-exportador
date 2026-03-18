package server.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import model.ArquivoProcessado
import model.DesenhoAutodesk
import server.broadcast.Broadcast
import server.db.Database
import server.db.DesenhoDao
import server.queue.ProcessingQueue
import server.util.AppLog
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID

@Serializable
private data class DevSeedResponse(val sucesso: Boolean, val inseridos: Int, val mensagem: String, val naFila: Int)
@Serializable
private data class DevClearResponse(val sucesso: Boolean, val mensagem: String)
@Serializable
private data class DevEnqueueResponse(val id: String, val nomeArquivo: String, val formatos: List<String>, val posicaoFila: Int)
@Serializable
private data class DevStatusResponse(val gemMode: String, val mockAtivo: Boolean, val banco: String, val outputDir: String)
@Serializable
private data class DevErrorResponse(val sucesso: Boolean, val erro: String)

/**
 * Rotas disponíveis apenas em GEM_MODE=dev.
 * Permite aplicar seed, limpar banco e verificar status via HTTP.
 */
fun Route.apiDevSeed(
    db: Database,
    desenhoDao: DesenhoDao,
    queue: ProcessingQueue,
    broadcast: Broadcast
) {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // POST /api/dev/seed - aplica seed de dados de teste
    post("/api/dev/seed") {
        AppLog.info("[DEV] Aplicando seed de desenvolvimento...")
        try {
            db.connection().use { conn ->
                conn.createStatement().executeUpdate("TRUNCATE TABLE desenho")
                AppLog.info("[DEV] Tabela truncada")
            }

            val agora = Instant.now()
            val fmt = DateTimeFormatter.ISO_INSTANT

            fun ts(minusMinutes: Long) = fmt.format(agora.minusSeconds(minusMinutes * 60))
            fun ts(minusHours: Int, minusMinutes: Long = 0) =
                fmt.format(agora.minusSeconds(minusHours * 3600L + minusMinutes * 60))

            val registros = listOf(
                // --- EM FILA ---
                DesenhoAutodesk(
                    id = "aaaaaaaa-0001-0001-0001-000000000001",
                    nomeArquivo = "241004940_01.idw",
                    computador = "ANDRE",
                    caminhoDestino = "\\\\192.168.1.152\\Arquivos\$\\DESENHOS GERENCIADOR\\241",
                    status = "processando",
                    posicaoFila = 1,
                    horarioEnvio = ts(5),
                    horarioAtualizacao = ts(0),
                    formatosSolicitadosJson = """["pdf","dwf","dwg"]""",
                    arquivoOriginal = "/tmp/gem-dev/idw/241004940_01.idw",
                    arquivosProcessadosJson = "[]",
                    progresso = 42,
                    criadoEm = ts(5),
                    atualizadoEm = ts(0)
                ),
                DesenhoAutodesk(
                    id = "aaaaaaaa-0002-0002-0002-000000000002",
                    nomeArquivo = "140000166_00.idw",
                    computador = "ANDRE",
                    caminhoDestino = "\\\\192.168.1.152\\Arquivos\$\\DESENHOS GERENCIADOR\\140",
                    status = "pendente",
                    posicaoFila = 2,
                    horarioEnvio = ts(4),
                    horarioAtualizacao = ts(4),
                    formatosSolicitadosJson = """["pdf","dwf","dwg"]""",
                    arquivoOriginal = "/tmp/gem-dev/idw/140000166_00.idw",
                    arquivosProcessadosJson = "[]",
                    progresso = 0,
                    criadoEm = ts(4),
                    atualizadoEm = ts(4)
                ),
                DesenhoAutodesk(
                    id = "aaaaaaaa-0003-0003-0003-000000000003",
                    nomeArquivo = "750013114_00.idw",
                    computador = "DANIEL BIO",
                    caminhoDestino = "\\\\192.168.1.152\\Arquivos\$\\DESENHOS GERENCIADOR\\750",
                    status = "pendente",
                    posicaoFila = 3,
                    horarioEnvio = ts(3),
                    horarioAtualizacao = ts(3),
                    formatosSolicitadosJson = """["pdf","dwf"]""",
                    arquivoOriginal = "/tmp/gem-dev/idw/750013114_00.idw",
                    arquivosProcessadosJson = "[]",
                    progresso = 0,
                    criadoEm = ts(3),
                    atualizadoEm = ts(3)
                ),
                DesenhoAutodesk(
                    id = "aaaaaaaa-0004-0004-0004-000000000004",
                    nomeArquivo = "750013115_00.idw",
                    computador = "DANIEL BIO",
                    caminhoDestino = "\\\\192.168.1.152\\Arquivos\$\\DESENHOS GERENCIADOR\\750",
                    status = "pendente",
                    posicaoFila = 4,
                    horarioEnvio = ts(2),
                    horarioAtualizacao = ts(2),
                    formatosSolicitadosJson = """["pdf"]""",
                    arquivoOriginal = "/tmp/gem-dev/idw/750013115_00.idw",
                    arquivosProcessadosJson = "[]",
                    progresso = 0,
                    criadoEm = ts(2),
                    atualizadoEm = ts(2)
                ),
                // PDF concluído, DWF em andamento, DWG aguardando
                DesenhoAutodesk(
                    id = "aaaaaaaa-0005-0005-0005-000000000005",
                    nomeArquivo = "181003346_00.idw",
                    computador = "DANIEL BIO",
                    caminhoDestino = "\\\\192.168.1.152\\Arquivos\$\\DESENHOS GERENCIADOR\\181",
                    status = "processando",
                    posicaoFila = 5,
                    horarioEnvio = ts(8),
                    horarioAtualizacao = ts(0),
                    formatosSolicitadosJson = """["pdf","dwf","dwg"]""",
                    arquivoOriginal = "/tmp/gem-dev/idw/181003346_00.idw",
                    arquivosProcessadosJson = json.encodeToString(listOf(
                        ArquivoProcessado("181003346_00.pdf", "pdf", "/tmp/gem-dev/output/181003346_00.pdf", 102400)
                    )),
                    progresso = 66,
                    criadoEm = ts(8),
                    atualizadoEm = ts(0)
                ),
                DesenhoAutodesk(
                    id = "aaaaaaaa-0006-0006-0006-000000000006",
                    nomeArquivo = "180004470_01.idw",
                    computador = "JUNIOR VENSON",
                    caminhoDestino = "\\\\192.168.1.152\\Arquivos\$\\DESENHOS GERENCIADOR\\180",
                    status = "pendente",
                    posicaoFila = 6,
                    horarioEnvio = ts(1),
                    horarioAtualizacao = ts(1),
                    formatosSolicitadosJson = """["pdf","dwf","dwg"]""",
                    arquivoOriginal = "/tmp/gem-dev/idw/180004470_01.idw",
                    arquivosProcessadosJson = "[]",
                    progresso = 0,
                    criadoEm = ts(1),
                    atualizadoEm = ts(1)
                ),
                // --- CONCLUÍDOS ---
                DesenhoAutodesk(
                    id = "bbbbbbbb-0001-0001-0001-000000000001",
                    nomeArquivo = "750013106_00.idw",
                    computador = "ANDRE",
                    caminhoDestino = "\\\\192.168.1.152\\Arquivos\$\\DESENHOS GERENCIADOR\\750",
                    status = "concluido",
                    posicaoFila = null,
                    horarioEnvio = ts(2, 0),
                    horarioAtualizacao = ts(1, 50),
                    formatosSolicitadosJson = """["pdf","dwf","dwg"]""",
                    arquivoOriginal = "/tmp/gem-dev/idw/750013106_00.idw",
                    arquivosProcessadosJson = json.encodeToString(listOf(
                        ArquivoProcessado("750013106_00.pdf", "pdf", "/tmp/gem-dev/output/750013106_00.pdf", 204800),
                        ArquivoProcessado("750013106_00.dwf", "dwf", "/tmp/gem-dev/output/750013106_00.dwf", 153600),
                        ArquivoProcessado("750013106_00.dwg", "dwg", "/tmp/gem-dev/output/750013106_00.dwg", 307200)
                    )),
                    progresso = 100,
                    criadoEm = ts(2, 0),
                    atualizadoEm = ts(1, 50)
                ),
                DesenhoAutodesk(
                    id = "bbbbbbbb-0002-0002-0002-000000000002",
                    nomeArquivo = "180003322_04.idw",
                    computador = "JUNIOR VENSON",
                    caminhoDestino = "\\\\192.168.1.152\\Arquivos\$\\DESENHOS GERENCIADOR\\180",
                    status = "concluido",
                    posicaoFila = null,
                    horarioEnvio = ts(3, 0),
                    horarioAtualizacao = ts(2, 50),
                    formatosSolicitadosJson = """["pdf","dwf"]""",
                    arquivoOriginal = "/tmp/gem-dev/idw/180003322_04.idw",
                    arquivosProcessadosJson = json.encodeToString(listOf(
                        ArquivoProcessado("180003322_04.pdf", "pdf", "/tmp/gem-dev/output/180003322_04.pdf", 102400),
                        ArquivoProcessado("180003322_04.dwf", "dwf", "/tmp/gem-dev/output/180003322_04.dwf", 76800)
                    )),
                    progresso = 100,
                    criadoEm = ts(3, 0),
                    atualizadoEm = ts(2, 50)
                ),
                // --- CONCLUÍDO COM ERROS ---
                DesenhoAutodesk(
                    id = "cccccccc-0001-0001-0001-000000000001",
                    nomeArquivo = "180003326_04.idw",
                    computador = "JUNIOR VENSON",
                    caminhoDestino = "\\\\192.168.1.152\\Arquivos\$\\DESENHOS GERENCIADOR\\180",
                    status = "concluido_com_erros",
                    posicaoFila = null,
                    horarioEnvio = ts(6, 0),
                    horarioAtualizacao = ts(5, 30),
                    formatosSolicitadosJson = """["pdf","dwf","dwg"]""",
                    arquivoOriginal = "/tmp/gem-dev/idw/180003326_04.idw",
                    arquivosProcessadosJson = json.encodeToString(listOf(
                        ArquivoProcessado("180003326_04.pdf", "pdf", "/tmp/gem-dev/output/180003326_04.pdf", 102400),
                        ArquivoProcessado("180003326_04.dwg", "dwg", "/tmp/gem-dev/output/180003326_04.dwg", 286720)
                    )),
                    erro = "dwf: falhou após 3 tentativas",
                    progresso = 66,
                    criadoEm = ts(6, 0),
                    atualizadoEm = ts(5, 30)
                ),
                // --- ERRO ---
                DesenhoAutodesk(
                    id = "dddddddd-0001-0001-0001-000000000001",
                    nomeArquivo = "180004482_01.idw",
                    computador = "JUNIOR VENSON",
                    caminhoDestino = "\\\\192.168.1.152\\Arquivos\$\\DESENHOS GERENCIADOR\\180",
                    status = "erro",
                    posicaoFila = null,
                    horarioEnvio = ts(7, 0),
                    horarioAtualizacao = ts(6, 55),
                    formatosSolicitadosJson = """["pdf","dwf","dwg"]""",
                    arquivoOriginal = "/tmp/gem-dev/idw/180004482_01.idw",
                    arquivosProcessadosJson = "[]",
                    erro = "pdf: falhou após 3 tentativas; dwf: falhou após 3 tentativas; dwg: falhou após 3 tentativas",
                    progresso = 0,
                    criadoEm = ts(7, 0),
                    atualizadoEm = ts(6, 55)
                ),
                // --- CANCELADO ---
                DesenhoAutodesk(
                    id = "eeeeeeee-0001-0001-0001-000000000001",
                    nomeArquivo = "181003343_00.idw",
                    computador = "DANIEL BIO",
                    caminhoDestino = "\\\\192.168.1.152\\Arquivos\$\\DESENHOS GERENCIADOR\\181",
                    status = "cancelado",
                    posicaoFila = null,
                    horarioEnvio = ts(8, 0),
                    horarioAtualizacao = ts(7, 30),
                    formatosSolicitadosJson = """["pdf","dwf","dwg"]""",
                    arquivoOriginal = "/tmp/gem-dev/idw/181003343_00.idw",
                    arquivosProcessadosJson = "[]",
                    progresso = 0,
                    canceladoEm = ts(7, 30),
                    criadoEm = ts(8, 0),
                    atualizadoEm = ts(7, 30)
                )
            )

            var inseridos = 0
            for (d in registros) {
                try {
                    desenhoDao.insert(d)
                    inseridos++
                } catch (e: Exception) {
                    AppLog.warn("[DEV] Falha ao inserir ${d.nomeArquivo}: ${e.message}")
                }
            }

            // Cria arquivos dummy de saída para os concluídos
            val outDir = java.io.File("/tmp/gem-dev/output").apply { mkdirs() }
            val idwDir = java.io.File("/tmp/gem-dev/idw").apply { mkdirs() }
            listOf(
                "241004940_01", "140000166_00", "750013114_00", "750013115_00",
                "181003346_00", "180004470_01", "750013106_00", "180003322_04",
                "180003326_04", "180004482_01", "181003343_00"
            ).forEach { base ->
                java.io.File(idwDir, "$base.idw").apply { if (!exists()) writeText("[DEV] dummy IDW") }
            }
            listOf(
                "750013106_00.pdf", "750013106_00.dwf", "750013106_00.dwg",
                "180003322_04.pdf", "180003322_04.dwf",
                "181003346_00.pdf",
                "180003326_04.pdf", "180003326_04.dwg"
            ).forEach { arquivo ->
                java.io.File(outDir, arquivo).apply { if (!exists()) writeText("[DEV] dummy $arquivo") }
            }

            // Re-adiciona itens pendentes à fila de processamento
            val pendentes = desenhoDao.listPendentesEProcessandoOrderedByFila(limit = 50)
            for (d in pendentes) {
                if (d.status == "pendente") queue.add(d.id)
                broadcast.sendInsert(d)
            }

            AppLog.info("[DEV] Seed aplicado: $inseridos registros inseridos")
            call.respond(HttpStatusCode.OK, DevSeedResponse(
                sucesso = true,
                inseridos = inseridos,
                mensagem = "Seed de desenvolvimento aplicado com sucesso",
                naFila = pendentes.size
            ))
        } catch (e: Exception) {
            AppLog.error("[DEV] Erro ao aplicar seed: ${e.message}", e)
            call.respond(HttpStatusCode.InternalServerError, DevErrorResponse(
                sucesso = false,
                erro = e.message ?: "Erro desconhecido"
            ))
        }
    }

    // DELETE /api/dev/clear - limpa todos os registros e a fila
    delete("/api/dev/clear") {
        AppLog.info("[DEV] Limpando banco de dados...")
        db.connection().use { conn ->
            conn.createStatement().executeUpdate("TRUNCATE TABLE desenho")
        }
        AppLog.info("[DEV] Banco limpo")
        call.respond(HttpStatusCode.OK, DevClearResponse(sucesso = true, mensagem = "Banco limpo"))
    }

    // GET /api/dev/status - informações do ambiente dev
    get("/api/dev/status") {
        call.respond(HttpStatusCode.OK, DevStatusResponse(
            gemMode = "dev",
            mockAtivo = true,
            banco = "PostgreSQL local (localhost/gem_dev)",
            outputDir = "/tmp/gem-dev/output"
        ))
    }

    // POST /api/dev/enqueue - enfileira um desenho fake rápido para testar o processamento
    post("/api/dev/enqueue") {
        val formatos = call.request.queryParameters["formatos"]?.split(",")?.map { it.trim() }
            ?: listOf("pdf", "dwf", "dwg")
        val computador = call.request.queryParameters["computador"] ?: "DEV-TEST"
        val id = UUID.randomUUID().toString()
        val now = Instant.now().toString()
        val nomeArquivo = "dev_test_${id.take(8)}.idw"

        java.io.File("/tmp/gem-dev/idw/$nomeArquivo").apply {
            parentFile.mkdirs()
            writeText("[DEV] teste enfileirado em $now")
        }

        val pos = desenhoDao.countPendentesEProcessando() + 1
        val d = DesenhoAutodesk(
            id = id,
            nomeArquivo = nomeArquivo,
            computador = computador,
            caminhoDestino = "/tmp/gem-dev/output",
            status = "pendente",
            posicaoFila = pos,
            horarioEnvio = now,
            horarioAtualizacao = now,
            formatosSolicitadosJson = """["${formatos.joinToString("\",\"")}"]""",
            arquivoOriginal = "/tmp/gem-dev/idw/$nomeArquivo",
            arquivosProcessadosJson = "[]",
            progresso = 0,
            criadoEm = now,
            atualizadoEm = now
        )
        desenhoDao.insert(d)
        queue.add(id)
        broadcast.sendInsert(d)

        AppLog.info("[DEV] Enfileirado: $nomeArquivo formatos=$formatos")
        call.respond(HttpStatusCode.Created, DevEnqueueResponse(
            id = id,
            nomeArquivo = nomeArquivo,
            formatos = formatos,
            posicaoFila = pos
        ))
    }
}
