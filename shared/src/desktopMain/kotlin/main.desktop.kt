import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.runtime.Composable
import config.DesktopConfig
import data.DatabaseDriverFactory
import data.DesenhoRepository
import data.IDesenhoRepository
import data.InMemoryDesenhoRepository
import ui.components.UpdateState
import util.AppVersion
import util.UpdateChecker
import util.UpdateDownloader
import util.VersionInfo
import java.io.File
import kotlin.system.exitProcess

actual fun getPlatformName(): String = "Desktop"
actual fun getServerBaseUrl(): String? = DesktopConfig.serverUrl
actual fun getSqliteDatabasePath(): String? =
    if (DesktopConfig.isViewer) "viewer (in-memory)" else DatabaseDriverFactory.getConnectionInfo()

actual suspend fun checkForUpdates(): VersionInfo? {
    return try {
        UpdateChecker.checkForUpdate()
    } catch (e: kotlinx.coroutines.CancellationException) {
        // App fechando - não logar como erro
        null
    } catch (e: Exception) {
        println("[UPDATE] Erro ao verificar atualizações: ${e.message}")
        null
    }
}

actual suspend fun performUpdate(version: VersionInfo, onStateChange: (UpdateState) -> Unit) {
    try {
        onStateChange(UpdateState.Downloading(0))
        
        val msiFile = UpdateDownloader.downloadUpdate(version.downloadUrl) { progress ->
            onStateChange(UpdateState.Downloading(progress))
        }
        
        if (msiFile == null) {
            onStateChange(UpdateState.Error("Falha no download"))
            return
        }
        
        onStateChange(UpdateState.Installing)
        
        val success = UpdateDownloader.installUpdate(msiFile)
        if (success) {
            // Fecha o app para permitir a instalação
            exitProcess(0)
        } else {
            onStateChange(UpdateState.Error("Falha ao iniciar instalador"))
        }
    } catch (e: Exception) {
        val msg = e.message?.takeIf { it.isNotBlank() }
            ?: "Erro inesperado durante atualizacao (${e::class.simpleName})"
        util.logToFile("ERROR", "performUpdate falhou: $msg\n${e.stackTraceToString().take(1500)}")
        onStateChange(UpdateState.Error(msg))
    }
}

actual fun cleanupOldUpdates() {
    UpdateDownloader.cleanupOldUpdates()
}

actual fun isViewerMode(): Boolean = DesktopConfig.isViewer

actual fun loadCurrentSettings(): Map<String, String> = mapOf(
    "DB_HOST"    to DesktopConfig.dbHost,
    "DB_PORT"    to DesktopConfig.dbPort.toString(),
    "DB_NAME"    to DesktopConfig.dbName,
    "DB_USER"    to DesktopConfig.dbUser,
    "DB_PASSWORD" to DesktopConfig.dbPassword,
    "SERVER_URL" to (DesktopConfig.serverUrl ?: "http://localhost:8080")
)

actual fun saveSettings(values: Map<String, String>): Boolean {
    return try {
        val configDir = File("C:\\gem-exportador")
        configDir.mkdirs()
        val envFile = File(configDir, ".env")

        // Lê o .env atual (de qualquer localização que o app encontrou)
        val existingLines: List<String> = if (envFile.exists()) {
            envFile.readLines()
        } else {
            // Gera um .env mínimo a partir dos valores atuais + os novos
            buildMinimalEnv(values)
            return true
        }

        // Atualiza apenas as chaves passadas, preserva o resto
        val updatedLines = existingLines.map { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("#") || !trimmed.contains("=")) return@map line
            val key = trimmed.substringBefore("=").trim()
            if (key in values) "$key=${values[key]}" else line
        }

        // Adiciona chaves que ainda não existiam no arquivo
        val existingKeys = existingLines
            .filter { !it.trim().startsWith("#") && it.contains("=") }
            .map { it.substringBefore("=").trim() }
            .toSet()
        val newLines = updatedLines.toMutableList()
        for ((k, v) in values) {
            if (k !in existingKeys) newLines.add("$k=$v")
        }

        envFile.writeText(newLines.joinToString("\r\n") + "\r\n")
        true
    } catch (e: Exception) {
        println("[SETTINGS] Erro ao salvar .env: ${e.message}")
        false
    }
}

private fun buildMinimalEnv(overrides: Map<String, String>) {
    val mode = if (DesktopConfig.isViewer) "viewer" else "server"
    val lines = mutableListOf(
        "GEM_MODE=$mode",
        "SERVER_HOST=${overrides["SERVER_HOST"] ?: "0.0.0.0"}",
        "SERVER_PORT=${overrides["SERVER_PORT"] ?: "8080"}",
        "SERVER_URL=${overrides["SERVER_URL"] ?: (DesktopConfig.serverUrl ?: "http://localhost:8080")}",
        "INVENTOR_PASTA_CONTROLE=C:\\gem-exportador\\controle",
        "LOG_LEVEL=INFO",
        "LOG_DIR=C:\\gem-exportador\\logs",
        "",
        "# PostgreSQL KSI (produção)",
        "DB_HOST=${overrides["DB_HOST"] ?: DesktopConfig.dbHost}",
        "DB_PORT=${overrides["DB_PORT"] ?: DesktopConfig.dbPort}",
        "DB_NAME=${overrides["DB_NAME"] ?: DesktopConfig.dbName}",
        "DB_USER=${overrides["DB_USER"] ?: DesktopConfig.dbUser}",
        "DB_PASSWORD=${overrides["DB_PASSWORD"] ?: DesktopConfig.dbPassword}",
        "",
        "SUPABASE_BACKUP_ENABLED=false"
    )
    val configDir = File("C:\\gem-exportador")
    configDir.mkdirs()
    File(configDir, ".env").writeText(lines.joinToString("\r\n") + "\r\n")
}

actual fun restartApp() {
    try {
        // Localiza o launch.cmd na pasta do executável (pai do runtime/bin)
        val runtimeBin = File(System.getProperty("java.home"), "bin")
        val installDir = runtimeBin.parentFile?.parentFile
        val launchCmd = if (installDir != null) File(installDir, "launch.cmd") else null

        if (launchCmd != null && launchCmd.exists()) {
            ProcessBuilder("cmd", "/c", "start", "", launchCmd.absolutePath)
                .directory(installDir)
                .start()
        } else {
            // Fallback: tenta reiniciar via ProcessHandle
            val pid = ProcessHandle.current().pid()
            val cmd = ProcessHandle.current().info().command().orElse(null)
            if (cmd != null) {
                ProcessBuilder(cmd).start()
            }
        }
    } catch (e: Exception) {
        println("[SETTINGS] Erro ao reiniciar: ${e.message}")
    } finally {
        exitProcess(0)
    }
}

private fun createRepository(): IDesenhoRepository =
    if (DesktopConfig.isViewer) {
        InMemoryDesenhoRepository()
    } else {
        DesenhoRepository(DatabaseDriverFactory())
    }

@Composable
fun MainView() {
    val repository = remember { createRepository() }
    App(repository)
}

@Preview
@Composable
fun AppPreview() {
    App(InMemoryDesenhoRepository())
}