package util

import kotlinx.serialization.Serializable

/**
 * Informacoes sobre uma versao disponivel para atualizacao
 */
@Serializable
data class VersionInfo(
    val version: String,
    val downloadUrl: String,
    val releaseNotes: String = "",
    val mandatory: Boolean = false
)

/**
 * Utilitarios para gerenciamento de versoes
 */
object AppVersion {
    private const val companyLegalName = "JHONROB SILOS E SECADORES LTDA" // CORRIGIDO: SILOES → SILOS
    private const val copyrightNotice = "TODOS OS DIREITOS RESERVADOS."

    var current: String = "1.0.0"
        private set

    fun init() {
        try {
            val versionText = readVersionFromResources()
            if (!versionText.isNullOrBlank()) {
                current = versionText
            }
        } catch (e: Exception) {
            // Mantem versao padrao
        }
    }

    fun footerVersion(): String {
        val resolvedVersion = readVersionFromResources().orEmpty().ifBlank { current }
        return if (resolvedVersion.startsWith("v", ignoreCase = true)) {
            resolvedVersion
        } else {
            "v$resolvedVersion"
        }
    }

    fun footerSuffix(): String {
        return " - $companyLegalName \u00A9 $copyrightNotice"
    }

    fun footerMessage(): String {
        return footerVersion() + footerSuffix()
    }

    private fun readVersionFromResources(): String? {
        return Thread.currentThread().contextClassLoader
            ?.getResourceAsStream("version.txt")
            ?.bufferedReader()
            ?.readText()
            ?.trim()
    }

    fun compare(v1: String, v2: String): Int {
        val parts1 = v1.removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
        val parts2 = v2.removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
        val maxLen = maxOf(parts1.size, parts2.size)
        for (i in 0 until maxLen) {
            val p1 = parts1.getOrElse(i) { 0 }
            val p2 = parts2.getOrElse(i) { 0 }
            if (p1 != p2) return p1 - p2
        }
        return 0
    }

    fun isNewerVersion(remoteVersion: String): Boolean {
        return compare(remoteVersion, current) > 0
    }
}
