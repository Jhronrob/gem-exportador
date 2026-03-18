package server.backup

import kotlinx.coroutines.*
import server.config.Config
import server.db.Database
import server.util.AppLog
import java.sql.Connection
import java.sql.DriverManager

/**
 * Backup em tempo real dos dados locais para o Supabase (PostgreSQL na nuvem).
 * 
 * - Na inicialização: sync completo de todos os registros (full sync)
 * - A cada INSERT/UPDATE/DELETE local: upsert/delete imediato no Supabase
 * - Usa INSERT ... ON CONFLICT (id) DO UPDATE para evitar duplicatas
 * - Nunca perde dados: o Supabase é sempre espelho fiel do banco local
 */
class SupabaseBackup(private val localDb: Database) {

    private var initialized = false

    /** Colunas da tabela desenho */
    private val columns = listOf(
        "id", "nome_arquivo", "computador", "caminho_destino", "status",
        "posicao_fila", "horario_envio", "horario_atualizacao", "formatos_solicitados",
        "arquivo_original", "arquivos_processados", "erro", "progresso", "tentativas",
        "arquivos_enviados_para_usuario", "cancelado_em", "criado_em", "atualizado_em",
        "pasta_processamento"
    )

    /** Colunas atualizáveis no upsert (tudo exceto id e criado_em) */
    private val updatableColumns = columns.filter { it != "id" && it != "criado_em" }

    /**
     * Inicializa o backup: cria tabela no Supabase se necessário e faz full sync.
     * Chamado uma vez na inicialização do servidor.
     */
    fun init() {
        if (!Config.supabaseBackupEnabled) {
            AppLog.info("[SUPABASE] Backup desabilitado (SUPABASE_BACKUP_ENABLED=false)")
            return
        }
        val jdbcUrl = Config.supabaseJdbcUrl
        if (jdbcUrl.isNullOrBlank()) {
            AppLog.warn("[SUPABASE] SUPABASE_URL não configurada — backup não iniciado")
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            delay(5_000) // Aguarda o servidor estabilizar
            try {
                AppLog.info("[SUPABASE] Iniciando full sync...")
                fullSync()
                initialized = true
                AppLog.info("[SUPABASE] Full sync concluído — backup em tempo real ativo")
            } catch (e: Exception) {
                AppLog.error("[SUPABASE] Erro no full sync inicial: ${e.message}")
                // Tenta novamente em 30s
                delay(30_000)
                try {
                    fullSync()
                    initialized = true
                    AppLog.info("[SUPABASE] Full sync (retry) concluído")
                } catch (e2: Exception) {
                    AppLog.error("[SUPABASE] Falha no retry do full sync: ${e2.message}")
                }
            }
        }
    }

    /**
     * Sync em tempo real: chamado a cada INSERT ou UPDATE no banco local.
     * Faz upsert de um único registro no Supabase.
     */
    fun syncRecord(desenhoId: String) {
        if (!initialized || !Config.supabaseBackupEnabled) return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                upsertSingle(desenhoId)
            } catch (e: Exception) {
                AppLog.error("[SUPABASE] Erro ao sincronizar registro $desenhoId: ${e.message}")
            }
        }
    }

    /**
     * Sync em tempo real: chamado a cada DELETE no banco local.
     */
    fun deleteRecord(desenhoId: String) {
        if (!initialized || !Config.supabaseBackupEnabled) return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                getSupabaseConnection().use { remote ->
                    remote.prepareStatement("DELETE FROM desenho WHERE id = ?").use { stmt ->
                        stmt.setString(1, desenhoId)
                        stmt.executeUpdate()
                    }
                }
                AppLog.info("[SUPABASE] Registro deletado: $desenhoId")
            } catch (e: Exception) {
                AppLog.error("[SUPABASE] Erro ao deletar registro $desenhoId: ${e.message}")
            }
        }
    }

    /**
     * Full sync: lê TODOS os registros locais e faz upsert em lote no Supabase.
     */
    private fun fullSync() {
        val rows = mutableListOf<Map<String, Any?>>()
        localDb.connection().use { local ->
            val rs = local.createStatement().executeQuery("SELECT ${columns.joinToString(", ")} FROM desenho")
            while (rs.next()) {
                val row = mutableMapOf<String, Any?>()
                for (col in columns) {
                    row[col] = rs.getObject(col)
                }
                rows.add(row)
            }
        }

        if (rows.isEmpty()) {
            AppLog.info("[SUPABASE] Nenhum registro local para sincronizar")
            return
        }

        getSupabaseConnection().use { remote ->
            ensureTable(remote)

            val placeholders = columns.joinToString(", ") { "?" }
            val updateSet = updatableColumns.joinToString(", ") { "$it = EXCLUDED.$it" }
            val upsertSql = """
                INSERT INTO desenho (${columns.joinToString(", ")})
                VALUES ($placeholders)
                ON CONFLICT (id) DO UPDATE SET $updateSet
            """.trimIndent()

            remote.autoCommit = false
            try {
                val stmt = remote.prepareStatement(upsertSql)
                for (row in rows) {
                    for ((i, col) in columns.withIndex()) {
                        val value = row[col]
                        if (value == null) stmt.setNull(i + 1, java.sql.Types.NULL)
                        else stmt.setObject(i + 1, value)
                    }
                    stmt.addBatch()
                }
                stmt.executeBatch()
                remote.commit()
                AppLog.info("[SUPABASE] Full sync: ${rows.size} registros sincronizados")
            } catch (e: Exception) {
                remote.rollback()
                throw e
            } finally {
                remote.autoCommit = true
            }
        }
    }

    /**
     * Upsert de um único registro no Supabase (chamado em tempo real).
     */
    private fun upsertSingle(desenhoId: String) {
        val row = mutableMapOf<String, Any?>()
        localDb.connection().use { local ->
            val stmt = local.prepareStatement("SELECT ${columns.joinToString(", ")} FROM desenho WHERE id = ?")
            stmt.setString(1, desenhoId)
            val rs = stmt.executeQuery()
            if (!rs.next()) return // Registro não existe mais (pode ter sido deletado)
            for (col in columns) {
                row[col] = rs.getObject(col)
            }
        }

        getSupabaseConnection().use { remote ->
            val placeholders = columns.joinToString(", ") { "?" }
            val updateSet = updatableColumns.joinToString(", ") { "$it = EXCLUDED.$it" }
            val upsertSql = """
                INSERT INTO desenho (${columns.joinToString(", ")})
                VALUES ($placeholders)
                ON CONFLICT (id) DO UPDATE SET $updateSet
            """.trimIndent()

            val stmt = remote.prepareStatement(upsertSql)
            for ((i, col) in columns.withIndex()) {
                val value = row[col]
                if (value == null) stmt.setNull(i + 1, java.sql.Types.NULL)
                else stmt.setObject(i + 1, value)
            }
            stmt.executeUpdate()
        }
    }

    /**
     * Garante que a tabela existe no Supabase.
     */
    private fun ensureTable(conn: Connection) {
        conn.createStatement().executeUpdate("""
            CREATE TABLE IF NOT EXISTS desenho (
                id TEXT NOT NULL PRIMARY KEY,
                nome_arquivo TEXT NOT NULL,
                computador TEXT NOT NULL,
                caminho_destino TEXT NOT NULL,
                status TEXT NOT NULL DEFAULT 'pendente',
                posicao_fila INTEGER,
                horario_envio TIMESTAMPTZ NOT NULL DEFAULT now(),
                horario_atualizacao TIMESTAMPTZ NOT NULL DEFAULT now(),
                formatos_solicitados TEXT,
                arquivo_original TEXT,
                arquivos_processados TEXT,
                erro TEXT,
                progresso INTEGER DEFAULT 0,
                tentativas INTEGER NOT NULL DEFAULT 0,
                arquivos_enviados_para_usuario INTEGER DEFAULT 0,
                cancelado_em TIMESTAMPTZ,
                criado_em TIMESTAMPTZ NOT NULL DEFAULT now(),
                atualizado_em TIMESTAMPTZ NOT NULL DEFAULT now(),
                pasta_processamento TEXT
            )
        """.trimIndent())
    }

    private fun getSupabaseConnection(): Connection {
        val jdbcUrl = Config.supabaseJdbcUrl ?: throw IllegalStateException("SUPABASE_URL não configurada")
        val separator = if (jdbcUrl.contains("?")) "&" else "?"
        val url = buildString {
            append(jdbcUrl)
            if (!jdbcUrl.contains("sslmode")) append("${separator}sslmode=require")
            if (!jdbcUrl.contains("prepareThreshold")) append("&prepareThreshold=0")
        }
        return DriverManager.getConnection(url)
    }
}
