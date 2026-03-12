package data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.jhonrob.gemexportador.db.GemDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import model.ArquivoProcessado
import model.DesenhoAutodesk

/**
 * Repositório para operações com desenhos no banco de dados
 */
class DesenhoRepository(
    databaseDriverFactory: DatabaseDriverFactory
) : IDesenhoRepository {
    private val database = GemDatabase(databaseDriverFactory.createDriver())
    private val queries = database.desenhoQueries
    private val json = Json { ignoreUnknownKeys = true }
    
    /**
     * Observa todos os desenhos (Flow reativo)
     */
    override fun observeAll(): Flow<List<DesenhoAutodesk>> {
        return queries.selectAll()
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { desenhos ->
                desenhos.map { it.toDesenhoAutodesk() }
            }
    }
    
    /**
     * Observa desenhos pendentes e processando
     */
    override fun observePendentesEProcessando(): Flow<List<DesenhoAutodesk>> {
        return queries.selectPendentesEProcessando()
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { desenhos ->
                desenhos.map { it.toDesenhoAutodesk() }
            }
    }
    
    /**
     * Busca todos os desenhos
     */
    override fun getAll(): List<DesenhoAutodesk> {
        return queries.selectAll().executeAsList().map { it.toDesenhoAutodesk() }
    }
    
    /**
     * Busca desenho por ID
     */
    override fun getById(id: String): DesenhoAutodesk? {
        return queries.selectById(id).executeAsOneOrNull()?.toDesenhoAutodesk()
    }
    
    /**
     * Busca desenhos por status
     */
    override fun getByStatus(status: String): List<DesenhoAutodesk> {
        return queries.selectByStatus(status).executeAsList().map { it.toDesenhoAutodesk() }
    }
    
    /**
     * Insere ou atualiza um desenho
     */
    override fun upsert(desenho: DesenhoAutodesk) {
        database.transaction {
            queries.insert(
                id = desenho.id,
                nome_arquivo = desenho.nomeArquivo,
                computador = desenho.computador,
                caminho_destino = desenho.caminhoDestino,
                status = desenho.status,
                posicao_fila = desenho.posicaoFila,
                horario_envio = desenho.horarioEnvio,
                horario_atualizacao = desenho.horarioAtualizacao,
                formatos_solicitados = desenho.formatosSolicitadosJson,
                arquivo_original = desenho.arquivoOriginal,
                arquivos_processados = desenho.arquivosProcessadosJson,
                erro = desenho.erro,
                progresso = desenho.progresso,
                tentativas = desenho.tentativas,
                arquivos_enviados_para_usuario = desenho.arquivosEnviadosParaUsuario,
                cancelado_em = desenho.canceladoEm,
                criado_em = desenho.criadoEm ?: desenho.horarioEnvio,
                atualizado_em = desenho.atualizadoEm ?: desenho.horarioAtualizacao,
                pasta_processamento = desenho.pastaProcessamento
            )
        }
    }
    
    /**
     * Insere ou atualiza multiplos desenhos.
     * Protegido contra falhas: se a transacao em lote falhar, tenta um a um.
     */
    override fun upsertAll(desenhos: List<DesenhoAutodesk>) {
        try {
            database.transaction {
                desenhos.forEach { desenho ->
                    queries.insert(
                        id = desenho.id,
                        nome_arquivo = desenho.nomeArquivo,
                        computador = desenho.computador,
                        caminho_destino = desenho.caminhoDestino,
                        status = desenho.status,
                        posicao_fila = desenho.posicaoFila,
                        horario_envio = desenho.horarioEnvio,
                        horario_atualizacao = desenho.horarioAtualizacao,
                        formatos_solicitados = desenho.formatosSolicitadosJson,
                        arquivo_original = desenho.arquivoOriginal,
                        arquivos_processados = desenho.arquivosProcessadosJson,
                        erro = desenho.erro,
                        progresso = desenho.progresso,
                        tentativas = desenho.tentativas,
                        arquivos_enviados_para_usuario = desenho.arquivosEnviadosParaUsuario,
                        cancelado_em = desenho.canceladoEm,
                        criado_em = desenho.criadoEm ?: desenho.horarioEnvio,
                        atualizado_em = desenho.atualizadoEm ?: desenho.horarioAtualizacao,
                        pasta_processamento = desenho.pastaProcessamento
                    )
                }
            }
        } catch (e: Exception) {
            util.logToFile("ERROR", "upsertAll batch falhou (${desenhos.size} itens): ${e.message}; tentando um a um")
            for (desenho in desenhos) {
                try {
                    upsert(desenho)
                } catch (inner: Exception) {
                    util.logToFile("ERROR", "upsert individual falhou para ${desenho.nomeArquivo}: ${inner.message}")
                }
            }
        }
    }
    
    /**
     * Atualiza o status de um desenho
     */
    override fun updateStatus(id: String, status: String, horarioAtualizacao: String) {
        queries.updateStatus(
            status = status,
            horario_atualizacao = horarioAtualizacao,
            atualizado_em = horarioAtualizacao,
            id = id
        )
    }
    
    /**
     * Atualiza o progresso de um desenho
     */
    override fun updateProgresso(id: String, progresso: Int, horarioAtualizacao: String) {
        queries.updateProgresso(
            progresso = progresso,
            horario_atualizacao = horarioAtualizacao,
            atualizado_em = horarioAtualizacao,
            id = id
        )
    }
    
    /**
     * Remove um desenho
     */
    override fun delete(id: String) {
        queries.delete(id)
    }
    
    /**
     * Remove todos os desenhos
     */
    override fun deleteAll() {
        queries.deleteAll()
    }
    
    /**
     * Converte o modelo do banco para o modelo de domínio
     */
    private fun com.jhonrob.gemexportador.db.Desenho.toDesenhoAutodesk(): DesenhoAutodesk {
        return DesenhoAutodesk(
            id = this.id,
            nomeArquivo = this.nome_arquivo,
            computador = this.computador,
            caminhoDestino = this.caminho_destino,
            status = this.status,
            posicaoFila = this.posicao_fila?.toInt(),
            horarioEnvio = this.horario_envio,
            horarioAtualizacao = this.horario_atualizacao,
            formatosSolicitadosJson = this.formatos_solicitados,
            arquivoOriginal = this.arquivo_original,
            arquivosProcessadosJson = this.arquivos_processados,
            erro = this.erro,
            progresso = this.progresso?.toInt() ?: 0,
            tentativas = this.tentativas?.toInt() ?: 0,
            arquivosEnviadosParaUsuario = this.arquivos_enviados_para_usuario?.toInt() ?: 0,
            canceladoEm = this.cancelado_em,
            criadoEm = this.criado_em,
            atualizadoEm = this.atualizado_em,
            pastaProcessamento = this.pasta_processamento
        )
    }
}
