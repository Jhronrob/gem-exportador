package util

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Formato do version.json hospedado no Gist público.
 * Atualizado automaticamente pelo GitHub Actions a cada release.
 */
@Serializable
private data class VersionJson(
    val version: String,
    val downloadUrl: String,
    val releaseNotes: String = "",
    val mandatory: Boolean = false
)

/**
 * Verifica atualizações disponíveis via Gist público (sem autenticação no app).
 * O Gist é atualizado automaticamente pelo GitHub Actions a cada release.
 * Gist: https://gist.github.com/afonsoburginski/c6b0d49af57e8869284b86bc45df8519
 */
object UpdateChecker {
    private const val VERSION_JSON_URL =
        "https://gist.githubusercontent.com/afonsoburginski/c6b0d49af57e8869284b86bc45df8519/raw/version.json"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val client = HttpClient(CIO)

    /**
     * Verifica se há uma versão mais nova disponível.
     * @return VersionInfo se há atualização, null se está na versão mais recente ou erro.
     */
    suspend fun checkForUpdate(): VersionInfo? {
        return try {
            val text = client.get(VERSION_JSON_URL) {
                header("Cache-Control", "no-cache")
                header("User-Agent", "GemExportador/${AppVersion.current}")
            }.bodyAsText()
            val versionJson = json.decodeFromString<VersionJson>(text)

            val remoteVersion = versionJson.version.removePrefix("v")

            if (AppVersion.isNewerVersion(remoteVersion)) {
                VersionInfo(
                    version = remoteVersion,
                    downloadUrl = versionJson.downloadUrl,
                    releaseNotes = versionJson.releaseNotes.ifBlank { "Nova versão $remoteVersion disponível" },
                    mandatory = versionJson.mandatory
                )
            } else {
                null
            }
        } catch (e: Exception) {
            println("[UPDATE] Erro ao verificar atualizações: ${e.message}")
            null
        }
    }

    fun close() {
        client.close()
    }
}
