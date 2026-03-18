package server.config

import io.github.cdimascio.dotenv.Dotenv
import io.github.cdimascio.dotenv.dotenv
import java.io.File

/**
 * Configuração do servidor lida do .env (na raiz do projeto ou diretório de execução).
 *
 * Para dev: passe -DGemEnvFile=.env.dev como JVM arg ou use o start-dev.sh.
 * Precedência: variável de sistema (-D) > arquivo .env/<custom> > default.
 */
object Config {
    private val envFilename: String = System.getProperty("GemEnvFile") ?: ".env"

    private val dotenv: Dotenv = dotenv {
        directory = findEnvDirectory()
        filename = envFilename
        ignoreIfMissing = true
    }

    val gemMode: String get() = get("GEM_MODE", "server")
    val isDevMode: Boolean get() = gemMode == "dev"

    val serverHost: String get() = get("SERVER_HOST", "0.0.0.0")
    val serverPort: Int get() = get("SERVER_PORT", "8080").toIntOrNull() ?: 8080
    val serverUrl: String get() = get("SERVER_URL", "http://localhost:8080")
    val inventorPastaControle: String? get() = get("INVENTOR_PASTA_CONTROLE", "").ifBlank { null }
    val logLevel: String get() = get("LOG_LEVEL", "INFO")

    // Configurações do PostgreSQL
    val dbHost: String get() = get("DB_HOST", "localhost")
    val dbPort: Int get() = get("DB_PORT", "5432").toIntOrNull() ?: 5432
    val dbName: String get() = get("DB_NAME", "gem_exportador")
    val dbUser: String get() = get("DB_USER", "postgres")
    val dbPassword: String get() = get("DB_PASSWORD", "123")
    val dbSslMode: String get() = get("DB_SSL_MODE", "")

    /** URL JDBC para conexão com PostgreSQL (suporta SSL para Supabase) */
    val jdbcUrl: String get() {
        val base = "jdbc:postgresql://$dbHost:$dbPort/$dbName"
        return if (dbSslMode.isNotBlank()) "$base?sslmode=$dbSslMode" else base
    }

    // Supabase (backup na nuvem)
    val supabaseUrl: String? get() = get("SUPABASE_URL", "").ifBlank { null }
    val supabaseJdbcUrl: String? get() = supabaseUrl?.let { "jdbc:$it" }
    val supabaseBackupEnabled: Boolean get() = get("SUPABASE_BACKUP_ENABLED", "false").toBoolean()

    /** Pasta dos logs (padrão: ./logs) */
    val logDir: File get() {
        val path = get("LOG_DIR", "")
        return if (path.isNotBlank()) File(path) else File(System.getProperty("user.dir"), "logs")
    }

    // Configurações de mock dev (modo dev sem Autodesk)
    val devMockPdfMs: Long get() = get("DEV_MOCK_PDF_MS", "8000").toLongOrNull() ?: 8000L
    val devMockDwfMs: Long get() = get("DEV_MOCK_DWF_MS", "6000").toLongOrNull() ?: 6000L
    val devMockDwgMs: Long get() = get("DEV_MOCK_DWG_MS", "12000").toLongOrNull() ?: 12000L
    val devOutputDir: String get() = get("DEV_OUTPUT_DIR", "/tmp/gem-dev/output")

    private fun get(key: String, default: String): String {
        return dotenv[key]?.ifBlank { null } ?: System.getenv(key)?.ifBlank { null } ?: default
    }

    private fun findEnvDirectory(): String {
        val candidates = listOf(
            File("C:\\gem-exportador"),
            File(System.getProperty("user.dir")),
            File(System.getProperty("user.dir")).parentFile,
            File(System.getProperty("user.dir"), ".."),
            File(System.getProperty("user.dir"), "../..")
        )
        for (dir in candidates) {
            if (dir != null && File(dir, envFilename).exists()) return dir.absolutePath
        }
        return System.getProperty("user.dir")
    }
}
