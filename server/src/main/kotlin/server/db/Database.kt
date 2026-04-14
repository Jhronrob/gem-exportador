package server.db

// ============================================================
// CORREÇÃO 1 de 3 — Connection Pool (HikariCP)
// Arquivo: server/src/main/kotlin/server/db/Database.kt
//
// PROBLEMA: cada query abria e fechava uma conexão JDBC nova.
//   Com watchdog + fila + backup + requests HTTP simultâneos,
//   o servidor podia atingir o limite de conexões do PostgreSQL
//   e derrubar tudo com: "FATAL: sorry, too many clients already"
//
// SOLUÇÃO: HikariCP mantém um pool de conexões reutilizadas.
//   A interface fun connection(): Connection não muda —
//   todos os DAOs continuam funcionando sem nenhuma alteração.
//
// MUDANÇAS:
//   - imports: removido DriverManager, adicionado HikariCP
//   - adicionado dataSource como propriedade da classe
//   - fun connection() agora busca do pool em vez de criar nova
//   - fun close() adicionado para shutdown limpo
// ============================================================

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import server.config.Config
import server.util.AppLog
import java.sql.Connection

/**
 * PostgreSQL JDBC para o servidor — com connection pool (HikariCP).
 * Conecta ao PostgreSQL de produção da KSI (192.168.1.152).
 * Configuração via variáveis de ambiente: DB_HOST, DB_PORT, DB_NAME, DB_USER, DB_PASSWORD
 */
class Database {

    // Pool de conexões: reutiliza até 10 conexões abertas.
    // Cada chamada a connection() pega uma do pool (< 1ms).
    // O .use { } nos DAOs devolve a conexão ao pool automaticamente.
    private val dataSource: HikariDataSource by lazy {
        HikariDataSource(HikariConfig().apply {
            jdbcUrl         = Config.jdbcUrl
            username        = Config.dbUser
            password        = Config.dbPassword
            maximumPoolSize = 10   // máximo de conexões simultâneas
            minimumIdle     = 2    // conexões mantidas abertas em repouso
            connectionTimeout   = 30_000  // ms para conseguir uma conexão do pool
            idleTimeout         = 600_000 // ms antes de fechar conexão ociosa
            maxLifetime         = 1_800_000 // ms máximo de vida de uma conexão (30min)
            poolName        = "gem-pool"
            isAutoCommit    = true
        })
    }

    // Interface idêntica ao código anterior — nenhum DAO precisa mudar.
    fun connection(): Connection = dataSource.connection

    // Fecha o pool no shutdown do servidor.
    fun close() {
        try {
            if (!dataSource.isClosed) {
                AppLog.info("[DB] Fechando connection pool...")
                dataSource.close()
            }
        } catch (e: Exception) {
            AppLog.error("[DB] Erro ao fechar pool: ${e.message}")
        }
    }

    fun init() {
        AppLog.info("Conectando ao PostgreSQL: ${Config.dbHost}:${Config.dbPort}/${Config.dbName}")

        // Retry de conexão (PostgreSQL pode estar iniciando).
        // Com HikariCP, a primeira chamada a connection() já valida a conectividade.
        var lastError: Exception? = null
        repeat(10) { attempt ->
            try {
                connection().use { conn ->
                    conn.createStatement().executeUpdate("""
                        CREATE TABLE IF NOT EXISTS desenho (
                            id TEXT NOT NULL PRIMARY KEY,
                            nome_arquivo TEXT NOT NULL,
                            computador TEXT NOT NULL,
                            caminho_destino TEXT NOT NULL,
                            status TEXT NOT NULL DEFAULT 'pendente'
                                CHECK (status = ANY (ARRAY['pendente'::TEXT, 'processando'::TEXT, 'concluido'::TEXT, 'concluido_com_erros'::TEXT, 'erro'::TEXT, 'cancelado'::TEXT])),
                            posicao_fila INTEGER,
                            horario_envio TIMESTAMPTZ NOT NULL DEFAULT now(),
                            horario_atualizacao TIMESTAMPTZ NOT NULL DEFAULT now(),
                            formatos_solicitados TEXT,
                            arquivo_original TEXT,
                            arquivos_processados TEXT,
                            erro TEXT,
                            progresso INTEGER DEFAULT 0
                                CHECK (progresso >= 0 AND progresso <= 100),
                            tentativas INTEGER NOT NULL DEFAULT 0,
                            arquivos_enviados_para_usuario INTEGER DEFAULT 0
                                CHECK (arquivos_enviados_para_usuario = ANY (ARRAY[0, 1])),
                            cancelado_em TIMESTAMPTZ,
                            criado_em TIMESTAMPTZ NOT NULL DEFAULT now(),
                            atualizado_em TIMESTAMPTZ NOT NULL DEFAULT now(),
                            pasta_processamento TEXT
                        )
                    """.trimIndent())

                    // Índices
                    conn.createStatement().executeUpdate("CREATE INDEX IF NOT EXISTS idx_desenho_status ON desenho(status)")
                    conn.createStatement().executeUpdate("CREATE INDEX IF NOT EXISTS idx_desenho_computador ON desenho(computador)")
                    conn.createStatement().executeUpdate("CREATE INDEX IF NOT EXISTS idx_desenho_horario_envio ON desenho(horario_envio)")

                    // Trigger para notificações em tempo real (LISTEN/NOTIFY)
                    // Operações de trigger agora dentro de transação para evitar estado
                    // inconsistente caso o servidor seja interrompido entre DROP e CREATE.
                    conn.autoCommit = false
                    try {
                        conn.createStatement().executeUpdate("""
                            CREATE OR REPLACE FUNCTION notify_desenho_changes()
                            RETURNS TRIGGER AS ${'$'}${'$'}
                            BEGIN
                                IF TG_OP = 'INSERT' THEN
                                    PERFORM pg_notify('desenho_changes', json_build_object('op', 'INSERT', 'id', NEW.id)::text);
                                ELSIF TG_OP = 'UPDATE' THEN
                                    PERFORM pg_notify('desenho_changes', json_build_object('op', 'UPDATE', 'id', NEW.id)::text);
                                ELSIF TG_OP = 'DELETE' THEN
                                    PERFORM pg_notify('desenho_changes', json_build_object('op', 'DELETE', 'id', OLD.id)::text);
                                END IF;
                                RETURN COALESCE(NEW, OLD);
                            END;
                            ${'$'}${'$'} LANGUAGE plpgsql
                        """.trimIndent())

                        conn.createStatement().executeUpdate("DROP TRIGGER IF EXISTS desenho_changes_trigger ON desenho")
                        conn.createStatement().executeUpdate("""
                            CREATE TRIGGER desenho_changes_trigger
                            AFTER INSERT OR UPDATE OR DELETE ON desenho
                            FOR EACH ROW EXECUTE PROCEDURE notify_desenho_changes()
                        """.trimIndent())

                        conn.commit()
                    } catch (e: Exception) {
                        conn.rollback()
                        throw e
                    } finally {
                        conn.autoCommit = true
                    }

                    AppLog.info("PostgreSQL inicializado com sucesso! Pool: ${Config.dbHost}:${Config.dbPort}/${Config.dbName}")
                    return
                }
            } catch (e: Exception) {
                lastError = e
                AppLog.info("Tentativa ${attempt + 1}/10 de conexão falhou, aguardando...")
                Thread.sleep(2000)
            }
        }

        throw RuntimeException("Não foi possível conectar ao PostgreSQL após 10 tentativas: ${lastError?.message}", lastError)
    }
}
