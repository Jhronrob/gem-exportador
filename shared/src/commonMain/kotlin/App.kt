import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.darkColors
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import data.ApiClient
import data.IDesenhoRepository
import data.RealtimeClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import model.DesenhoAutodesk
import model.DesenhoStatus
import ui.components.DesenhoActions
import ui.components.DesenhosTable
import ui.components.SettingsDialog
import ui.components.UpdateDialog
import ui.components.UpdateState
import ui.theme.AppColors
import util.AppVersion
import util.VersionInfo
import util.getCurrentDateTime
import util.logToFile

private fun logRepositoryInit(repository: IDesenhoRepository) {
    val info = getSqliteDatabasePath()
    if (info != null) logToFile("INFO", "Repositório: $info")
}

/**
 * App principal. Viewer usa InMemoryDesenhoRepository (dados via WebSocket).
 * Servidor usa DesenhoRepository (PostgreSQL direto).
 */
@OptIn(FlowPreview::class)
@Composable
fun App(repository: IDesenhoRepository) {
    val darkColorPalette = darkColors(
        primary = AppColors.Primary,
        primaryVariant = AppColors.PrimaryVariant,
        background = AppColors.Background,
        surface = AppColors.Surface
    )
    
    val serverBaseUrl = getServerBaseUrl()
    val apiClient = if (serverBaseUrl != null) remember { ApiClient(serverBaseUrl) } else null

    // RealtimeClient para WebSocket
    val realtimeClient = if (serverBaseUrl != null) {
        val wsUrl = serverBaseUrl.replaceFirst("http", "ws") + "/ws"
        remember { RealtimeClient(wsUrl, repository) }
    } else null

    // Conecta ao WebSocket do servidor para sincronizar tabela em tempo real
    DisposableEffect(realtimeClient) {
        if (realtimeClient != null && serverBaseUrl != null) {
            logToFile("INFO", "Gem exportador (desktop) iniciado; servidor=$serverBaseUrl")
            val exHandler = kotlinx.coroutines.CoroutineExceptionHandler { _, throwable ->
                logToFile("ERROR", "Excecao na coroutine do WebSocket: ${throwable.message}")
            }
            val job = kotlinx.coroutines.CoroutineScope(
                Dispatchers.Default + kotlinx.coroutines.SupervisorJob() + exHandler
            ).launch {
                realtimeClient.connect()
            }
            onDispose {
                job.cancel()
                kotlinx.coroutines.runBlocking { 
                    try { realtimeClient.disconnect() } catch (_: Exception) {}
                }
            }
        } else {
            logToFile("INFO", "Gem exportador iniciado (modo offline/local)")
            onDispose { }
        }
    }

    LaunchedEffect(Unit) {
        logRepositoryInit(repository)
    }

    // Estado dos desenhos (observando do banco local; atualiza sozinho quando WebSocket envia INSERT/UPDATE)
    // Debounce de 250ms para evitar rajadas de recomposicao quando muitas mensagens WebSocket chegam juntas
    val desenhos by remember {
        repository.observeAll()
            .debounce(250L)
    }.collectAsState(initial = emptyList())

    val scope = rememberCoroutineScope()
    val footerVersion = remember { AppVersion.footerVersion() }
    val footerSuffix = remember { AppVersion.footerSuffix() }
    
    var showSettings by remember { mutableStateOf(false) }

    // === AUTO-UPDATE ===
    var updateAvailable by remember { mutableStateOf<VersionInfo?>(null) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var updateDismissed by remember { mutableStateOf(false) }
    var updateState by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }
    
    // Verifica se a fila está vazia (nenhum processando ou pendente)
    val queueEmpty by remember(desenhos) {
        derivedStateOf {
            desenhos.none { 
                it.statusEnum == DesenhoStatus.PROCESSANDO || 
                it.statusEnum == DesenhoStatus.PENDENTE 
            }
        }
    }
    
    // Verifica atualizações ao iniciar
    LaunchedEffect(Unit) {
        AppVersion.init()
        logToFile("INFO", "Versão atual: ${AppVersion.current}")
        
        // Limpa arquivos de atualização antigos (> 7 dias)
        cleanupOldUpdates()
        
        // Aguarda 3 segundos antes de verificar (para não atrasar startup)
        delay(3000)
        
        checkForUpdates()?.let { version ->
            logToFile("INFO", "Atualização disponível: ${version.version}")
            updateAvailable = version
            showUpdateDialog = true
        }
    }
    
    // Quando a fila esvaziar e estamos aguardando, inicia o download
    LaunchedEffect(queueEmpty, updateState) {
        if (updateState == UpdateState.WaitingQueue && queueEmpty) {
            updateAvailable?.let { version ->
                performUpdate(version) { newState ->
                    updateState = newState
                }
            }
        }
    }
    
    // Dados vêm do servidor via WebSocket (não há sincronização local → servidor)

    // Ações: a maioria usa UI otimista; regeneração aguarda aceite do servidor.
    val actions = remember(apiClient, repository) {
        DesenhoActions(
            onRegenerate = { desenho ->
                logToFile("INFO", "Regeneração solicitada: ${desenho.nomeArquivo} (${desenho.id})")
                scope.launch(Dispatchers.Default) {
                    if (apiClient != null) {
                        val result = apiClient.regenerar(desenho.id)
                        if (result.isFailure) {
                            logToFile("ERROR", "Falha ao regenerar ${desenho.nomeArquivo}: ${result.exceptionOrNull()?.message}")
                        } else {
                            logToFile("INFO", "Regeneração aceita pelo servidor: ${desenho.nomeArquivo}")
                            repository.updateStatus(desenho.id, "pendente", getCurrentDateTime())
                        }
                    } else {
                        logToFile("ERROR", "ApiClient nulo - servidor não configurado")
                    }
                }
            },
            onRetry = { desenho ->
                logToFile("INFO", "Reenviar solicitado: ${desenho.nomeArquivo} (${desenho.id})")
                repository.updateStatus(desenho.id, "pendente", getCurrentDateTime())
                scope.launch(Dispatchers.Default) {
                    if (apiClient != null) {
                        val result = apiClient.retry(desenho.id)
                        if (result.isFailure) {
                            logToFile("ERROR", "Falha ao reenviar ${desenho.nomeArquivo}: ${result.exceptionOrNull()?.message}")
                            repository.updateStatus(desenho.id, desenho.status, getCurrentDateTime())
                        } else {
                            logToFile("INFO", "Reenviar aceito pelo servidor: ${desenho.nomeArquivo}")
                        }
                    } else {
                        logToFile("ERROR", "ApiClient nulo - servidor não configurado")
                    }
                }
            },
            onCancel = { desenho ->
                logToFile("INFO", "Cancelar solicitado: ${desenho.nomeArquivo} (${desenho.id})")
                val statusAnterior = desenho.status
                repository.updateStatus(desenho.id, "cancelado", getCurrentDateTime())
                scope.launch(Dispatchers.Default) {
                    if (apiClient != null) {
                        val result = apiClient.cancelar(desenho.id)
                        if (result.isFailure) {
                            logToFile("ERROR", "Falha ao cancelar ${desenho.nomeArquivo}: ${result.exceptionOrNull()?.message}")
                            // Rollback: restaura status anterior para não deixar estado inconsistente
                            repository.updateStatus(desenho.id, statusAnterior, getCurrentDateTime())
                        }
                    }
                }
            },
            onDelete = { desenho ->
                logToFile("INFO", "Deletar solicitado: ${desenho.nomeArquivo} (${desenho.id})")
                repository.delete(desenho.id)
                scope.launch(Dispatchers.Default) {
                    if (apiClient != null) {
                        val result = apiClient.delete(desenho.id)
                        if (result.isFailure) {
                            logToFile("ERROR", "Falha ao deletar ${desenho.nomeArquivo}: ${result.exceptionOrNull()?.message}")
                            // Rollback: restaura o item localmente para não sumir sem ser deletado no servidor
                            repository.upsert(desenho)
                        }
                    }
                }
            }
        )
    }

    // Focus requester para capturar teclas (F5)
    val focusRequester = remember { FocusRequester() }
    
    // Estado de refresh (F5)
    var isRefreshing by remember { mutableStateOf(false) }
    
    MaterialTheme(colors = darkColorPalette) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(AppColors.Background)
                .focusRequester(focusRequester)
                .focusable()
                .onKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.F5) {
                        logToFile("INFO", "F5 pressionado - refresh solicitado")
                        isRefreshing = true
                        scope.launch(Dispatchers.Default) {
                            realtimeClient?.refresh()
                            delay(1200) // tempo mínimo visual do loader
                            isRefreshing = false
                        }
                        true
                    } else {
                        false
                    }
                }
        ) {
            DesenhosTable(
                desenhos = desenhos,
                actions = actions,
                updateAvailable = if (updateDismissed) updateAvailable else null,
                onUpdateClick = { showUpdateDialog = true },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp),
                isRefreshing = isRefreshing,
                onSettingsClick = { showSettings = true }
            )

            Divider(color = AppColors.Border, thickness = 0.5.dp)

            Text(
                text = buildAnnotatedString {
                    withStyle(
                        style = SpanStyle(
                            color = AppColors.Primary.copy(alpha = 0.88f),
                            fontWeight = FontWeight.SemiBold
                        )
                    ) {
                        append(footerVersion)
                    }
                    withStyle(style = SpanStyle(color = AppColors.TextMuted)) {
                        append(footerSuffix)
                    }
                },
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
            )
        }
        
        // Solicita foco ao iniciar para capturar teclas
        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
        }
        
        // Dialog de configurações
        if (showSettings) {
            SettingsDialog(onDismiss = { showSettings = false })
        }

        // Dialog de atualização
        if (showUpdateDialog && updateAvailable != null) {
            UpdateDialog(
                versionInfo = updateAvailable!!,
                updateState = updateState,
                queueEmpty = queueEmpty,
                onUpdate = {
                    scope.launch {
                        if (queueEmpty) {
                            // Fila vazia - inicia download imediatamente
                            performUpdate(updateAvailable!!) { newState ->
                                updateState = newState
                            }
                        } else {
                            // Fila não vazia - aguarda esvaziar
                            updateState = UpdateState.WaitingQueue
                        }
                    }
                },
                onDismiss = {
                    if (updateState == UpdateState.Idle || updateState is UpdateState.Error) {
                        showUpdateDialog = false
                        updateDismissed = true
                    }
                }
            )
        }
    }
}

expect fun getPlatformName(): String
/** URL base do servidor (ex: http://localhost:8080). Se null, usa só SQLite local. */
expect fun getServerBaseUrl(): String?
/** Caminho do arquivo SQLite em disco (desktop); null em outras plataformas. */
expect fun getSqliteDatabasePath(): String?
/** Verifica se há atualizações disponíveis */
expect suspend fun checkForUpdates(): VersionInfo?
/** Executa o processo de atualização (download + instalação) */
expect suspend fun performUpdate(version: VersionInfo, onStateChange: (UpdateState) -> Unit)
/** Limpa arquivos de atualização antigos */
expect fun cleanupOldUpdates()

/** Retorna true se o app está em modo viewer */
expect fun isViewerMode(): Boolean
/** Lê os valores atuais das configurações editáveis */
expect fun loadCurrentSettings(): Map<String, String>
/** Salva as configurações no .env e retorna true se precisar reiniciar */
expect fun saveSettings(values: Map<String, String>): Boolean
/** Reinicia o processo do app */
expect fun restartApp()
