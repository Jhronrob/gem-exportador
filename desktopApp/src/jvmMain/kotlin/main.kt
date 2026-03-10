import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import org.jetbrains.skia.Image
import java.awt.Frame
import java.awt.Graphics2D
import java.awt.MediaTracker
import java.awt.Toolkit
import java.awt.Window as AwtWindow
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.ptr.IntByReference

fun main() {
    // Handler global: captura excecoes nao tratadas para evitar fechamento silencioso
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        try {
            util.logToFile("FATAL", "Excecao nao tratada na thread '${thread.name}': ${throwable.message}")
            util.logToFile("FATAL", "Stack trace: ${throwable.stackTraceToString().take(2000)}")
        } catch (_: Exception) {
            System.err.println("[FATAL] Excecao nao tratada: ${throwable.message}")
            throwable.printStackTrace()
        }
    }

    // Forca dark mode no Windows via propriedade do sistema
    System.setProperty("sun.java2d.noddraw", "true")

    val isViewer = config.DesktopConfig.isViewer
    val windowTitle = if (isViewer) "Gem exportador (viewer)" else "Gem exportador"

    if (isViewer) {
        println("[GEM] Modo VIEWER — conectando ao servidor remoto: ${config.DesktopConfig.serverUrl}")
    } else {
        // Inicia o servidor em background antes de abrir a janela
        CoroutineScope(Dispatchers.IO).launch {
            try {
                println("[GEM] Iniciando servidor embutido...")
                val iniciou = server.startEmbeddedServer()
                if (!iniciou) {
                    val msg = "Servidor nao iniciou — porta ja em uso. Feche a instancia anterior do app e tente novamente."
                    println("[GEM] AVISO: $msg")
                    util.logToFile("WARN", msg)
                }
            } catch (e: Exception) {
                println("[GEM] Erro ao iniciar servidor: ${e.message}")
                util.logToFile("ERROR", "Erro ao iniciar servidor: ${e.message}")
            }
        }
        // Aguarda o servidor iniciar (PostgreSQL já está rodando como serviço)
        Thread.sleep(2000)
    }
    
    // Inicia o app Compose
    application {
        val icon = loadWindowIcon()
        val windowState = rememberWindowState(
            size = DpSize(900.dp, 600.dp) // Tamanho inicial
        )
        
        Window(
            title = windowTitle,
            icon = icon,
            state = windowState,
            onCloseRequest = ::exitApplication
        ) {
            window.minimumSize = java.awt.Dimension(380, 300)
            
            LaunchedEffect(Unit) {
                delay(100)
                applyDarkTitleBarByTitle(windowTitle)
            }
            MainView()
        }
    }
}

/**
 * Aplica barra de título escura no Windows usando o título da janela para encontrá-la.
 */
private fun applyDarkTitleBarByTitle(title: String) {
    if (!System.getProperty("os.name").lowercase().contains("windows")) return
    try {
        val hwnd = User32.INSTANCE.FindWindow(null, title)
        if (hwnd != null) {
            setDarkTitleBar(Pointer.nativeValue(hwnd.pointer))
        }
    } catch (e: Throwable) {
        println("[GEM] Erro ao aplicar dark title bar: ${e.message}")
    }
}

private interface Dwmapi : com.sun.jna.Library {
    fun DwmSetWindowAttribute(
        hwnd: Pointer,
        dwAttribute: Int,
        pvAttribute: Pointer,
        cbAttribute: Int
    ): Int
    companion object {
        val INSTANCE: Dwmapi = Native.load("dwmapi", Dwmapi::class.java)
    }
}

private fun setDarkTitleBar(hwnd: Long) {
    try {
        // DWMWA_USE_IMMERSIVE_DARK_MODE = 20 (Windows 10 build 18985+)
        // Também tentar 19 para builds mais antigos
        val value = IntByReference(1)
        
        // Tentar com atributo 20 primeiro (builds mais recentes)
        var result = Dwmapi.INSTANCE.DwmSetWindowAttribute(
            Pointer.createConstant(hwnd),
            20,
            value.pointer,
            4
        )
        
        // Se falhar, tentar com atributo 19 (builds mais antigos)
        if (result != 0) {
            Dwmapi.INSTANCE.DwmSetWindowAttribute(
                Pointer.createConstant(hwnd),
                19,
                value.pointer,
                4
            )
        }
    } catch (_: Throwable) { }
}

/**
 * Carrega o ícone da janela em alta resolução.
 * Ordem: tenta icon-256.png, icon-128.png, icon-48.png; senão favicon.ico (tamanho nativo, sem upscale para não ficar borrado).
 * Para ícone nítido: coloque icon-256.png (256x256) em desktopApp/src/jvmMain/resources/
 */
private fun loadWindowIcon(): BitmapPainter? {
    val loader = Thread.currentThread().contextClassLoader
    // Preferir PNG em alta resolução
    for (name in listOf("icon-256.png", "icon-128.png", "icon-48.png")) {
        val painter = loader.getResource(name)?.let { url ->
            try {
                val stream = url.openStream()
                val pngBytes = stream.readBytes()
                stream.close()
                Image.makeFromEncoded(pngBytes).toComposeImageBitmap().let { BitmapPainter(it) }
            } catch (_: Exception) {
                null
            }
        }
        if (painter != null) return painter
    }
    // Fallback: favicon.ico no tamanho nativo (evitar upscale = evita borrão)
    return try {
        val url = loader.getResource("favicon.ico") ?: return null
        val awtImage = Toolkit.getDefaultToolkit().createImage(url)
        val tracker = MediaTracker(Frame())
        tracker.addImage(awtImage, 0)
        tracker.waitForAll(1000)
        val w = awtImage.getWidth(null).coerceIn(1, 256)
        val h = awtImage.getHeight(null).coerceIn(1, 256)
        val buffered = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        (buffered.graphics as Graphics2D).apply {
            setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            drawImage(awtImage, 0, 0, w, h, null)
        }
        val pngOut = ByteArrayOutputStream()
        ImageIO.write(buffered, "png", pngOut)
        val imageBitmap = Image.makeFromEncoded(pngOut.toByteArray()).toComposeImageBitmap()
        BitmapPainter(imageBitmap)
    } catch (_: Exception) {
        null
    }
}