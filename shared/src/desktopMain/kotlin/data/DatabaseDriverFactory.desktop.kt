package data

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.JdbcDriver
import com.jhonrob.gemexportador.db.GemDatabase
import config.DesktopConfig
import java.sql.Connection
import java.sql.DriverManager

actual class DatabaseDriverFactory {
    actual fun createDriver(): SqlDriver {
        // Conecta ao PostgreSQL de produção KSI (192.168.1.152)
        var connection: Connection? = null
        var lastError: Exception? = null
        
        repeat(10) { attempt ->
            try {
                connection = DriverManager.getConnection(
                    DesktopConfig.jdbcUrl,
                    DesktopConfig.dbUser,
                    DesktopConfig.dbPassword
                )
                return@repeat
            } catch (e: Exception) {
                lastError = e
                println("[DB] Tentativa ${attempt + 1}/10 de conexao falhou, aguardando...")
                Thread.sleep(2000)
            }
        }
        
        if (connection == null) {
            throw RuntimeException("Nao foi possivel conectar ao PostgreSQL apos 10 tentativas: ${lastError?.message}", lastError)
        }
        
        val conn = connection!!
        val listeners = mutableMapOf<String, MutableSet<app.cash.sqldelight.Query.Listener>>()
        val driver = object : JdbcDriver() {
            override fun getConnection() = conn
            override fun closeConnection(connection: Connection) {
                // Não fecha a conexão aqui, mantém aberta para reutilização
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
            conn.createStatement().executeQuery("SELECT 1 FROM desenho LIMIT 1")
        } catch (e: Exception) {
            GemDatabase.Schema.create(driver)
        }
        
        return driver
    }

    companion object {
        fun getConnectionInfo(): String {
            return "${DesktopConfig.dbHost}:${DesktopConfig.dbPort}/${DesktopConfig.dbName}"
        }
    }
}
