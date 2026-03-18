package config

import io.github.cdimascio.dotenv.dotenv
import java.io.File

/**
 * Configuração do desktop lida do .env (na raiz do projeto ou diretório de execução).
 */
object DesktopConfig {
    private val envFilename: String = System.getProperty("GemDesktopEnvFile") ?: ".env"

    private val envDir: String by lazy {
        val candidates = listOf(
            File("C:\\gem-exportador"),          // config editável pelo usuário (sem admin)
            File(System.getProperty("user.dir")),
            File(System.getProperty("user.dir")).parentFile,
            File(System.getProperty("user.dir"), ".."),
            File(System.getProperty("user.dir"), "../..")
        )
        candidates.firstOrNull { it != null && File(it, envFilename).exists() }?.absolutePath
            ?: "C:\\gem-exportador"
    }

    private val dotenv by lazy {
        dotenv {
            directory = envDir
            filename = envFilename
            ignoreIfMissing = true
        }
    }

    /**
     * Modo de execução:
     * - "server" (padrão): inicia servidor embutido + frontend (app completo)
     * - "viewer": só frontend, conecta em servidor remoto
     */
    val mode: String get() = get("GEM_MODE", "server").lowercase()

    /** True se estiver rodando como viewer (sem servidor local) */
    val isViewer: Boolean get() = mode == "viewer"

    val serverUrl: String? get() {
        val url = get("SERVER_URL", "http://localhost:8080")
        return url.ifBlank { null }
    }

    // Configurações do PostgreSQL
    val dbHost: String get() = get("DB_HOST", "localhost")
    val dbPort: Int get() = get("DB_PORT", "5432").toIntOrNull() ?: 5432
    val dbName: String get() = get("DB_NAME", "gem_exportador")
    val dbUser: String get() = get("DB_USER", "postgres")
    val dbPassword: String get() = get("DB_PASSWORD", "123")
    
    /** URL JDBC para conexão com PostgreSQL */
    val jdbcUrl: String get() = "jdbc:postgresql://$dbHost:$dbPort/$dbName"

    /** Pasta dos logs (padrão: ./logs) */
    val logDir: File get() {
        val path = get("LOG_DIR", "")
        return if (path.isNotBlank()) File(path) else File(System.getProperty("user.dir"), "logs")
    }

    private fun get(key: String, default: String): String {
        return dotenv[key]?.ifBlank { null } ?: System.getenv(key)?.ifBlank { null } ?: default
    }
}
