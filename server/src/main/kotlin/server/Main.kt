package server

import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import server.config.Config
import server.util.AppLog
import java.net.BindException
import java.net.ServerSocket

private var serverInstance: CIOApplicationEngine? = null

fun main() {
    startEmbeddedServer(wait = true)
}

/**
 * Verifica se a porta está disponível antes de tentar iniciar.
 */
private fun isPortAvailable(host: String, port: Int): Boolean {
    return try {
        ServerSocket(port, 1, java.net.InetAddress.getByName(host)).use { true }
    } catch (e: Exception) {
        false
    }
}

/**
 * Inicia o servidor Ktor.
 * @param wait Se true, bloqueia a thread atual até o servidor parar.
 * @return true se iniciou com sucesso, false se a porta já estiver em uso.
 */
fun startEmbeddedServer(wait: Boolean = false): Boolean {
    if (serverInstance != null) {
        AppLog.info("Servidor já está rodando")
        return true
    }

    val host = Config.serverHost
    val port = Config.serverPort

    if (!isPortAvailable(host, port)) {
        AppLog.error("[STARTUP] ERRO: Porta $port já está em uso. Outra instância do servidor pode estar rodando.")
        AppLog.error("[STARTUP] Encerrando para evitar conflito. Feche a instância anterior e tente novamente.")
        return false
    }

    if (Config.isDevMode) {
        AppLog.info("╔══════════════════════════════════════════════╗")
        AppLog.info("║   GEM EXPORTADOR - MODO DESENVOLVIMENTO (dev) ║")
        AppLog.info("║   Mock do Inventor ATIVO - sem Autodesk       ║")
        AppLog.info("║   Banco: Supabase (nuvem)                     ║")
        AppLog.info("╚══════════════════════════════════════════════╝")
    }
    AppLog.info("Iniciando servidor em $host:$port")
    try {
        serverInstance = embeddedServer(CIO, port = port, host = host) {
            configureSerialization()
            configureStatusPages()
            configureWebSocket()
            configureRouting()
        }
        serverInstance!!.start(wait = wait)
        return true
    } catch (e: BindException) {
        AppLog.error("[STARTUP] Falha ao iniciar: porta $port já está ocupada — ${e.message}")
        serverInstance = null
        return false
    } catch (e: Exception) {
        AppLog.error("[STARTUP] Falha inesperada ao iniciar servidor: ${e.message}", e)
        serverInstance = null
        return false
    }
}

/**
 * Para o servidor (se estiver rodando).
 */
fun stopEmbeddedServer() {
    serverInstance?.stop(1000, 2000)
    serverInstance = null
    AppLog.info("Servidor parado")
}
