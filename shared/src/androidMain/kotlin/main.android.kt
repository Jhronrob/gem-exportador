import android.content.Context
import androidx.compose.runtime.Composable
import data.DatabaseDriverFactory
import ui.components.UpdateState
import util.VersionInfo

actual fun getPlatformName(): String = "Android"
actual fun getServerBaseUrl(): String? = null
actual fun getSqliteDatabasePath(): String? = null

// Auto-update não suportado no Android (usa Play Store)
actual suspend fun checkForUpdates(): VersionInfo? = null
actual suspend fun performUpdate(version: VersionInfo, onStateChange: (UpdateState) -> Unit) {}
actual fun cleanupOldUpdates() {}
actual fun isViewerMode(): Boolean = false
actual fun loadCurrentSettings(): Map<String, String> = emptyMap()
actual fun saveSettings(values: Map<String, String>): Boolean = false
actual fun restartApp() {}

@Composable 
fun MainView(context: Context) {
    val databaseDriverFactory = DatabaseDriverFactory(context)
    App(databaseDriverFactory)
}
