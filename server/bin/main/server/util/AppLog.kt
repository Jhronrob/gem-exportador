package server.util

import server.config.Config
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Log do servidor gem-exportador.
 * Grava em LOG_DIR/gem-exportador.log (padrão: ./logs/)
 * Thread-safe (synchronized).
 */
object AppLog {
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
        .withZone(ZoneId.systemDefault())
    private val logDir = Config.logDir.apply { mkdirs() }
    private val logFile = File(logDir, "gem-exportador.log")
    private val lock = Any()

    fun getLogPath(): String = logFile.absolutePath

    fun info(message: String) = write("INFO", message)
    fun warn(message: String) = write("WARN", message)
    fun error(message: String) = write("ERROR", message)
    fun error(message: String, t: Throwable) = write("ERROR", "$message - ${t.message}")

    private fun write(level: String, message: String) {
        val line = "${formatter.format(Instant.now())} [$level] $message"
        // Sempre imprime no console
        println(line)
        // Também grava em arquivo
        synchronized(lock) {
            try {
                logFile.appendText("$line\n")
            } catch (e: Exception) {
                System.err.println(line)
            }
        }
    }
}
