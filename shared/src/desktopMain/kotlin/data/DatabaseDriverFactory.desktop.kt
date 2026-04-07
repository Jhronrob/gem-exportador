package data

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.JdbcDriver
import com.jhonrob.gemexportador.db.GemDatabase
import config.DesktopConfig
import java.sql.Connection
import java.sql.DriverManager

actual class DatabaseDriverFactory {
    actual fun createDriver(): SqlDriver {
        val conn = conectar()
        val listeners = mutableMapOf<String, MutableSet<app.cash.sqldelight.Query.Listener>>()

        var activeConn = conn

        val driver = object : JdbcDriver() {
            override fun getConnection(): Connection {
                // Reconecta automaticamente se a conexão estiver fechada ou inválida
                if (activeConn.isClosed || !activeConn.isValid(2)) {
                    println("[DB] Conexão inválida — reconectando...")
                    activeConn = conectar()
                }
                // SQLDelight exige autoCommit=true na conexão retornada pelo driver.
                // Se uma transação anterior falhou com exceção, pode ter deixado
                // autoCommit=false. Garantimos o reset aqui para evitar o erro
                // "Expected autoCommit to be true by default".
                if (!activeConn.autoCommit) {
                    try { activeConn.rollback() } catch (_: Exception) {}
                    activeConn.autoCommit = true
                }
                return activeConn
            }
            override fun closeConnection(connection: Connection) {
                // Não fecha a conexão aqui; reutilizamos a mesma instância.
            }
            override fun addListener(vararg queryKeys: String, listener: app.cash.sqldelight.Query.Listener) {
                queryKeys.forEach { key ->
                    listeners.getOrPut(key) { mutableSetOf() }.add(listener)
                }
            }
            override fun removeListener(vararg queryKeys: String, listener: app.cash.sqldelight.Query.Listener) {
                queryKeys.forEach { key ->
                    listeners[key]?.remove(listener)
                }
            }
            override fun notifyListeners(vararg queryKeys: String) {
                queryKeys.forEach { key ->
                    listeners[key]?.forEach { it.queryResultsChanged() }
                }
            }
        }

        // Verifica se a tabela existe, se não, cria o schema
        try {
            activeConn.createStatement().executeQuery("SELECT 1 FROM desenho LIMIT 1")
        } catch (e: Exception) {
            GemDatabase.Schema.create(driver)
        }

        return driver
    }

    private fun conectar(): Connection {
        var lastError: Exception? = null
        repeat(10) { attempt ->
            try {
                val c = DriverManager.getConnection(
                    DesktopConfig.jdbcUrl,
                    DesktopConfig.dbUser,
                    DesktopConfig.dbPassword
                )
                c.autoCommit = true
                return c
            } catch (e: Exception) {
                lastError = e
                println("[DB] Tentativa ${attempt + 1}/10 de conexao falhou, aguardando...")
                Thread.sleep(2000)
            }
        }
        throw RuntimeException("Nao foi possivel conectar ao PostgreSQL apos 10 tentativas: ${lastError?.message}", lastError)
    }

    companion object {
        fun getConnectionInfo(): String {
            return "${DesktopConfig.dbHost}:${DesktopConfig.dbPort}/${DesktopConfig.dbName}"
        }
    }
}
