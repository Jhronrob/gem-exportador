package server.db

import model.DesenhoAutodesk
import java.sql.ResultSet

class DesenhoDao(private val database: Database) {

    /** Retorna TODOS os registros sem LIMIT — usado pelo endpoint /api/desenhos/all para o viewer. */
    fun listAll(): List<DesenhoAutodesk> {
        return database.connection().use { conn ->
            conn.prepareStatement("SELECT * FROM desenho ORDER BY horario_envio DESC").use { stmt ->
                stmt.executeQuery().use { rs -> rs.map { it.toDesenho() } }
            }
        }
    }

    fun list(status: String? = null, computador: String? = null, limit: Int = 50, offset: Int = 0): List<DesenhoAutodesk> {
        val sql = buildString {
            append("SELECT * FROM desenho WHERE 1=1 ")
            if (status != null) append("AND status = ? ")
            if (computador != null) append("AND computador = ? ")
            append("ORDER BY horario_envio DESC LIMIT ? OFFSET ?")
        }
        return database.connection().use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                var i = 1
                if (status != null) stmt.setString(i++, status)
                if (computador != null) stmt.setString(i++, computador)
                stmt.setInt(i++, limit)
                stmt.setInt(i, offset)
                stmt.executeQuery().use { rs -> rs.map { it.toDesenho() } }
            }
        }
    }

    /** Pendentes + processando em ordem FIFO (primeiro recebido = primeiro na fila). Usado no startup da fila. */
    fun listPendentesEProcessandoOrderedByFila(limit: Int = 200): List<DesenhoAutodesk> {
        val sql = """
            SELECT * FROM desenho 
            WHERE status IN ('pendente', 'processando') 
            ORDER BY (posicao_fila IS NULL), posicao_fila ASC NULLS LAST, horario_envio ASC 
            LIMIT ?
        """.trimIndent()
        return database.connection().use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setInt(1, limit)
                stmt.executeQuery().use { rs -> rs.map { it.toDesenho() } }
            }
        }
    }

    fun count(status: String? = null): Int {
        val sql = if (status != null) "SELECT COUNT(*) FROM desenho WHERE status = ?" else "SELECT COUNT(*) FROM desenho"
        return database.connection().use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                if (status != null) stmt.setString(1, status)
                stmt.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
            }
        }
    }

    fun delete(id: String) {
        database.connection().use { conn ->
            conn.prepareStatement("DELETE FROM desenho WHERE id = ?").use { stmt ->
                stmt.setString(1, id)
                stmt.executeUpdate()
            }
        }
    }

    fun deleteAll() {
        database.connection().use { conn ->
            conn.createStatement().executeUpdate("DELETE FROM desenho")
        }
    }

    fun getById(id: String): DesenhoAutodesk? {
        return database.connection().use { conn ->
            conn.prepareStatement("SELECT * FROM desenho WHERE id = ?").use { stmt ->
                stmt.setString(1, id)
                stmt.executeQuery().use { rs -> if (rs.next()) rs.toDesenho() else null }
            }
        }
    }

    /**
     * Insere um desenho no banco. Se o ID for null ou vazio, gera UUID automaticamente.
     * Retorna o ID do desenho inserido (gerado ou original).
     */
    fun insert(d: DesenhoAutodesk): String {
        // Gera UUID se ID for null ou vazio
        val finalId = if (d.id.isNullOrBlank()) java.util.UUID.randomUUID().toString() else d.id
        val desenhoComId = d.copy(id = finalId)
        
        database.connection().use { conn ->
            conn.prepareStatement("""
                INSERT INTO desenho (id, nome_arquivo, computador, caminho_destino, status, posicao_fila,
                    horario_envio, horario_atualizacao, formatos_solicitados, arquivo_original, arquivos_processados,
                    erro, progresso, tentativas, arquivos_enviados_para_usuario, cancelado_em, criado_em, atualizado_em, pasta_processamento)
                VALUES (?, ?, ?, ?, ?, ?, ?::TIMESTAMPTZ, ?::TIMESTAMPTZ, ?, ?, ?, ?, ?, ?, ?, ?::TIMESTAMPTZ, ?::TIMESTAMPTZ, ?::TIMESTAMPTZ, ?)
                ON CONFLICT (id) DO UPDATE SET
                    nome_arquivo = EXCLUDED.nome_arquivo,
                    computador = EXCLUDED.computador,
                    caminho_destino = EXCLUDED.caminho_destino,
                    status = EXCLUDED.status,
                    posicao_fila = EXCLUDED.posicao_fila,
                    horario_envio = EXCLUDED.horario_envio,
                    horario_atualizacao = EXCLUDED.horario_atualizacao,
                    formatos_solicitados = EXCLUDED.formatos_solicitados,
                    arquivo_original = EXCLUDED.arquivo_original,
                    arquivos_processados = EXCLUDED.arquivos_processados,
                    erro = EXCLUDED.erro,
                    progresso = EXCLUDED.progresso,
                    tentativas = EXCLUDED.tentativas,
                    arquivos_enviados_para_usuario = EXCLUDED.arquivos_enviados_para_usuario,
                    cancelado_em = EXCLUDED.cancelado_em,
                    criado_em = EXCLUDED.criado_em,
                    atualizado_em = EXCLUDED.atualizado_em,
                    pasta_processamento = EXCLUDED.pasta_processamento
            """.trimIndent()).use { stmt ->
                bindDesenho(stmt, desenhoComId)
                stmt.executeUpdate()
            }
        }
        return finalId
    }

    fun update(id: String, status: String? = null, horarioAtualizacao: String? = null, progresso: Int? = null,
               arquivosProcessados: String? = null, erro: String? = null, canceladoEm: String? = null,
               posicaoFila: Int? = null) {
        val now = horarioAtualizacao ?: java.time.Instant.now().toString()
        database.connection().use { conn ->
            conn.prepareStatement("""
                UPDATE desenho SET
                    horario_atualizacao = ?::TIMESTAMPTZ, atualizado_em = ?::TIMESTAMPTZ
                    ${if (status != null) ", status = ?" else ""}
                    ${if (progresso != null) ", progresso = ?" else ""}
                    ${if (arquivosProcessados != null) ", arquivos_processados = ?" else ""}
                    ${if (erro != null) ", erro = ?" else ""}
                    ${if (canceladoEm != null) ", cancelado_em = ?::TIMESTAMPTZ" else ""}
                    ${if (posicaoFila != null) ", posicao_fila = ?" else ""}
                WHERE id = ?
            """.trimIndent()).use { stmt ->
                var i = 1
                stmt.setString(i++, now)
                stmt.setString(i++, now)
                if (status != null) stmt.setString(i++, status)
                if (progresso != null) stmt.setInt(i++, progresso)
                if (arquivosProcessados != null) stmt.setString(i++, arquivosProcessados)
                if (erro != null) stmt.setString(i++, erro)
                if (canceladoEm != null) stmt.setString(i++, canceladoEm)
                if (posicaoFila != null) stmt.setInt(i++, posicaoFila)
                stmt.setString(i, id)
                stmt.executeUpdate()
            }
        }
    }

    fun countPendentesEProcessando(): Int {
        return database.connection().use { conn ->
            conn.prepareStatement("SELECT COUNT(*) FROM desenho WHERE status IN ('pendente', 'processando')").use { stmt ->
                stmt.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
            }
        }
    }

    fun getStats(): Map<String, Any> {
        val porStatus = mutableMapOf<String, Int>()
        val porComputador = mutableMapOf<String, Int>()
        database.connection().use { conn ->
            conn.prepareStatement("SELECT status, COUNT(*) FROM desenho GROUP BY status").use { stmt ->
                stmt.executeQuery().use { rs ->
                    while (rs.next()) porStatus[rs.getString(1)] = rs.getInt(2)
                }
            }
            conn.prepareStatement("SELECT computador, COUNT(*) FROM desenho GROUP BY computador").use { stmt ->
                stmt.executeQuery().use { rs ->
                    while (rs.next()) porComputador[rs.getString(1)] = rs.getInt(2)
                }
            }
        }
        return mapOf(
            "total" to count(),
            "porStatus" to porStatus,
            "porComputador" to porComputador
        )
    }

    private fun bindDesenho(stmt: java.sql.PreparedStatement, d: DesenhoAutodesk) {
        var i = 1
        stmt.setString(i++, d.id)
        stmt.setString(i++, d.nomeArquivo)
        stmt.setString(i++, d.computador)
        stmt.setString(i++, d.caminhoDestino)
        stmt.setString(i++, d.status)
        stmt.setObject(i++, d.posicaoFila?.toLong())
        stmt.setString(i++, d.horarioEnvio)
        stmt.setString(i++, d.horarioAtualizacao)
        stmt.setString(i++, d.formatosSolicitadosJson)
        stmt.setString(i++, d.arquivoOriginal)
        stmt.setString(i++, d.arquivosProcessadosJson)
        stmt.setString(i++, d.erro)
        stmt.setInt(i++, d.progresso)
        stmt.setInt(i++, d.tentativas)
        stmt.setInt(i++, d.arquivosEnviadosParaUsuario)
        stmt.setString(i++, d.canceladoEm)
        stmt.setString(i++, d.criadoEm ?: d.horarioEnvio)
        stmt.setString(i++, d.atualizadoEm ?: d.horarioAtualizacao)
        stmt.setString(i, d.pastaProcessamento)
    }

    private fun ResultSet.toDesenho(): DesenhoAutodesk = DesenhoAutodesk(
        id = getString("id"),
        nomeArquivo = getString("nome_arquivo"),
        computador = getString("computador"),
        caminhoDestino = getString("caminho_destino"),
        status = getString("status"),
        posicaoFila = getObject("posicao_fila")?.let { (it as Number).toInt() },
        horarioEnvio = getString("horario_envio"),
        horarioAtualizacao = getString("horario_atualizacao"),
        formatosSolicitadosJson = getString("formatos_solicitados"),
        arquivoOriginal = getString("arquivo_original"),
        arquivosProcessadosJson = getString("arquivos_processados"),
        erro = getString("erro"),
        progresso = getInt("progresso"),
        tentativas = getInt("tentativas"),
        arquivosEnviadosParaUsuario = getInt("arquivos_enviados_para_usuario"),
        canceladoEm = getString("cancelado_em"),
        criadoEm = getString("criado_em"),
        atualizadoEm = getString("atualizado_em"),
        pastaProcessamento = getString("pasta_processamento")
    )

    private inline fun <T> ResultSet.map(block: (ResultSet) -> T): List<T> {
        val list = mutableListOf<T>()
        while (next()) list.add(block(this))
        return list
    }
}
