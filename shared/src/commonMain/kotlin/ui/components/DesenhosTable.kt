package ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import model.DesenhoAutodesk
import model.DesenhoStatus
import ui.theme.AppColors
import util.getDaysAgoDate
import util.getTodayDate
import util.VersionInfo
import util.openInExplorer


/**
 * Callback para ações do context menu
 */
data class DesenhoActions(
    val onRetry: (DesenhoAutodesk) -> Unit = {},
    val onCancel: (DesenhoAutodesk) -> Unit = {},
    val onDelete: (DesenhoAutodesk) -> Unit = {}
)

/**
 * Extrai a parte da data (yyyy-MM-dd) de um timestamp ISO-8601
 */
private fun extractDate(isoTimestamp: String): String {
    return isoTimestamp.substringBefore("T").take(10)
}

/**
 * Componente de tabela para exibir desenhos
 */
@Composable
fun DesenhosTable(
    desenhos: List<DesenhoAutodesk>,
    modifier: Modifier = Modifier,
    actions: DesenhoActions = DesenhoActions(),
    updateAvailable: VersionInfo? = null,
    onUpdateClick: () -> Unit = {},
    isRefreshing: Boolean = false,
    onSettingsClick: () -> Unit = {}
) {
    // Estado para mostrar/ocultar concluídos
    var mostrarConcluidos by remember { mutableStateOf(false) }

    // Filtro unificado: data início/fim + busca por nome
    // Padrão: últimos 7 dias (preset inicial)
    var filterDateDe by remember { mutableStateOf(getDaysAgoDate(7)) }
    var filterDateAte by remember { mutableStateOf("") }
    var activePreset by remember { mutableStateOf<String?>("7 dias") }
    var searchQuery by remember { mutableStateOf("") }
    var showDateFilter by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // Filtro unificado: data + nome em uma única passagem
    val desenhosAtivos = remember(desenhos, filterDateDe, filterDateAte, searchQuery) {
        desenhos.filter { d ->
            if (d.statusEnum == DesenhoStatus.PENDENTE || d.statusEnum == DesenhoStatus.PROCESSANDO) return@filter true
            val dataEnvio = extractDate(d.horarioEnvio)
            val inDate = (filterDateDe.isBlank() || dataEnvio >= filterDateDe) &&
                         (filterDateAte.isBlank() || dataEnvio <= filterDateAte)
            val inName = searchQuery.isBlank() || d.nomeArquivo.contains(searchQuery, ignoreCase = true)
            inDate && inName
        }
    }

    // Separar desenhos por categoria
    val (emFila, concluidos, comProblema) = remember(desenhosAtivos) {
        val fila = mutableListOf<DesenhoAutodesk>()
        val ok = mutableListOf<DesenhoAutodesk>()
        val problema = mutableListOf<DesenhoAutodesk>()

        desenhosAtivos.forEach { d ->
            when (d.statusEnum) {
                DesenhoStatus.PROCESSANDO, DesenhoStatus.PENDENTE -> fila.add(d)
                DesenhoStatus.CONCLUIDO, DesenhoStatus.CONCLUIDO_COM_ERROS -> ok.add(d)
                DesenhoStatus.ERRO, DesenhoStatus.CANCELADO -> problema.add(d)
            }
        }

        fila.sortWith(compareBy(
            { if (it.statusEnum == DesenhoStatus.PROCESSANDO) 0 else 1 },
            { it.posicaoFila ?: Int.MAX_VALUE },
            { it.horarioEnvio }
        ))

        Triple(fila, ok, problema)
    }

    // Lista final: fila → erros/cancelados → todos os concluídos
    // LazyColumn já virtualiza a renderização — só desenha o que está visível na tela
    val desenhosExibidos = remember(emFila, concluidos, comProblema, mostrarConcluidos) {
        val lista = mutableListOf<DesenhoAutodesk>()
        lista.addAll(emFila)
        lista.addAll(comProblema)
        if (mostrarConcluidos) lista.addAll(concluidos)
        lista
    }

    // Dialog unificado: atalhos de período + calendário customizado
    if (showDateFilter) {
        DateRangePickerDialog(
            currentDe = filterDateDe,
            currentAte = filterDateAte,
            activePreset = activePreset,
            onApply = { de, ate, preset ->
                filterDateDe = de
                filterDateAte = ate
                activePreset = preset
                showDateFilter = false
            },
            onDismiss = { showDateFilter = false }
        )
    }
    
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val isCompact = maxWidth < 700.dp
        
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(4.dp))
                .background(AppColors.Surface)
                .border(1.dp, AppColors.Border, RoundedCornerShape(4.dp))
        ) {
            // Toolbar
            Toolbar(
                emFila = emFila.size,
                concluidos = concluidos.size,
                erros = comProblema.count { it.statusEnum == DesenhoStatus.ERRO },
                cancelados = comProblema.count { it.statusEnum == DesenhoStatus.CANCELADO },
                mostrarConcluidos = mostrarConcluidos,
                onToggleConcluidos = { mostrarConcluidos = !mostrarConcluidos },
                periodoLabel = activePreset ?: "Personalizado",
                hasDateFilter = filterDateDe.isNotBlank() || filterDateAte.isNotBlank(),
                onDateFilterClick = { showDateFilter = true },
                updateAvailable = updateAvailable,
                onUpdateClick = onUpdateClick,
                isCompact = isCompact,
                searchQuery = searchQuery,
                onSearchChange = { searchQuery = it },
                onClearSearch = { searchQuery = "" },
                onSettingsClick = onSettingsClick
            )
            
            Divider(color = AppColors.Border, thickness = 1.dp)
            
            // Header
            TableHeader(isCompact = isCompact)
            
            Divider(color = AppColors.Border, thickness = 1.dp)
            
            // Rows com overlay de refresh
            Box(modifier = Modifier.fillMaxSize()) {
                if (desenhosExibidos.isEmpty() && !isRefreshing) {
                    EmptyState()
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(desenhosExibidos, key = { it.id }) { desenho ->
                            TableRowWithContextMenu(
                                desenho = desenho,
                                actions = actions,
                                isCompact = isCompact
                            )
                            Divider(color = AppColors.Border, thickness = 1.dp)
                        }
                    }
                }
                
                // Overlay de loading (F5 refresh)
                if (isRefreshing) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(AppColors.Background.copy(alpha = 0.6f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(
                                color = AppColors.Primary,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(28.dp)
                            )
                            Text(
                                text = "Atualizando...",
                                color = AppColors.TextSecondary,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }

            // Rodapé: contagem de registros exibidos
            Divider(color = AppColors.Border, thickness = 1.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AppColors.SurfaceVariant)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val totalExibido = desenhosExibidos.size
                val totalConcluidos = concluidos.size
                Text(
                    text = if (mostrarConcluidos)
                        "$totalExibido registros exibidos ($totalConcluidos concluídos)"
                    else
                        "$totalExibido registros exibidos",
                    color = AppColors.TextMuted,
                    fontSize = 11.sp
                )
                if (filterDateDe.isNotBlank() || filterDateAte.isNotBlank() || searchQuery.isNotBlank()) {
                    Text(
                        text = "filtro ativo",
                        color = AppColors.Primary,
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun Toolbar(
    emFila: Int,
    concluidos: Int,
    erros: Int,
    cancelados: Int,
    mostrarConcluidos: Boolean,
    onToggleConcluidos: () -> Unit,
    periodoLabel: String = "7 dias",
    hasDateFilter: Boolean = false,
    onDateFilterClick: () -> Unit = {},
    updateAvailable: VersionInfo? = null,
    onUpdateClick: () -> Unit = {},
    isCompact: Boolean = false,
    searchQuery: String = "",
    onSearchChange: (String) -> Unit = {},
    onClearSearch: () -> Unit = {},
    onSettingsClick: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppColors.SurfaceVariant)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Contadores
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusBadge(label = "Em fila", count = emFila, color = AppColors.BadgeBlue)
            if (erros > 0) {
                StatusBadge(label = "Erros", count = erros, color = AppColors.BadgeRed)
            }
            if (cancelados > 0) {
                StatusBadge(label = "Cancelados", count = cancelados, color = AppColors.BadgeOrange)
            }
        }

        Spacer(Modifier.width(10.dp))

        // Input de busca
        Row(
            modifier = Modifier
                .widthIn(max = 200.dp)
                .height(32.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(AppColors.Surface)
                .border(1.dp, AppColors.Border, RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = "Pesquisar",
                tint = AppColors.TextMuted,
                modifier = Modifier.size(14.dp)
            )

            androidx.compose.foundation.text.BasicTextField(
                value = searchQuery,
                onValueChange = onSearchChange,
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(
                    color = AppColors.TextPrimary,
                    fontSize = 12.sp
                ),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(AppColors.Primary),
                modifier = Modifier.weight(1f),
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (searchQuery.isEmpty()) {
                            Text("Pesquisar...", color = AppColors.TextMuted, fontSize = 12.sp)
                        }
                        innerTextField()
                    }
                }
            )

            if (searchQuery.isNotEmpty()) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Limpar",
                    tint = AppColors.TextMuted,
                    modifier = Modifier
                        .size(14.dp)
                        .clickable { onClearSearch() }
                        .pointerHoverIcon(PointerIcon.Hand)
                )
            }
        }

        Spacer(Modifier.width(8.dp))

        // Botão de período/calendário unificado — à direita da busca
        Row(
            modifier = Modifier
                .height(32.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (hasDateFilter) AppColors.Primary.copy(alpha = 0.12f) else AppColors.Surface)
                .border(
                    1.dp,
                    if (hasDateFilter) AppColors.Primary.copy(alpha = 0.5f) else AppColors.Border,
                    RoundedCornerShape(6.dp)
                )
                .clickable { onDateFilterClick() }
                .pointerHoverIcon(PointerIcon.Hand)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.DateRange,
                contentDescription = "Filtro de período",
                tint = if (hasDateFilter) AppColors.Primary else AppColors.TextSecondary,
                modifier = Modifier.size(13.dp)
            )
            Text(
                text = periodoLabel,
                color = if (hasDateFilter) AppColors.Primary else AppColors.TextPrimary,
                fontSize = 12.sp
            )
            Icon(
                imageVector = Icons.Filled.ArrowDropDown,
                contentDescription = null,
                tint = if (hasDateFilter) AppColors.Primary else AppColors.TextMuted,
                modifier = Modifier.size(16.dp)
            )
        }

        // Spacer empurra concluídos para a direita
        Spacer(Modifier.weight(1f))

        // Update button (se disponível)
        if (updateAvailable != null) {
            UpdateButton(onClick = onUpdateClick)
            Spacer(Modifier.width(8.dp))
        }

        // Botão de configurações
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(AppColors.Surface)
                .border(1.dp, AppColors.Border, RoundedCornerShape(6.dp))
                .clickable(onClick = onSettingsClick)
                .pointerHoverIcon(PointerIcon.Hand),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = "Configurações",
                tint = AppColors.TextSecondary,
                modifier = Modifier.size(16.dp)
            )
        }

        Spacer(Modifier.width(8.dp))
        
        // Toggle concluídos (alinhado à direita)
        Row(
            modifier = Modifier
                .height(32.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (mostrarConcluidos) AppColors.BadgeGreenBg else AppColors.BadgeGrayBg)
                .border(
                    1.dp,
                    if (mostrarConcluidos) AppColors.BadgeGreen.copy(alpha = 0.5f) else AppColors.Border,
                    RoundedCornerShape(6.dp)
                )
                .clickable { onToggleConcluidos() }
                .pointerHoverIcon(PointerIcon.Hand)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (mostrarConcluidos) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = "Ativo",
                    tint = AppColors.BadgeGreen,
                    modifier = Modifier.size(13.dp)
                )
            }
            Text(
                text = "Concluídos ($concluidos)",
                color = if (mostrarConcluidos) AppColors.BadgeGreen else AppColors.TextSecondary,
                fontSize = 12.sp,
                fontWeight = if (mostrarConcluidos) FontWeight.Medium else FontWeight.Normal
            )
        }
    }
}

@Composable
private fun StatusBadge(
    label: String,
    count: Int,
    color: androidx.compose.ui.graphics.Color
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = label,
            color = AppColors.TextSecondary,
            fontSize = 12.sp
        )
        Text(
            text = count.toString(),
            color = color,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun TableHeader(isCompact: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppColors.SurfaceVariant)

            .padding(horizontal = if (isCompact) 8.dp else 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HeaderCell(text = "ARQUIVO", modifier = Modifier.weight(1.0f))
        HeaderCell(text = "FORMATOS", modifier = Modifier.weight(if (isCompact) 1.0f else 1.5f))
        if (!isCompact) {
            HeaderCell(text = "COMPUTADOR", modifier = Modifier.weight(0.8f))
            HeaderCell(text = "PASTA", modifier = Modifier.weight(1.8f))
            HeaderCell(text = "ENVIADO", modifier = Modifier.weight(0.8f))
        }
        // Em modo compacto, usa ícone em vez de texto para AÇÕES
        HeaderCell(text = if (isCompact) "" else "AÇÕES", modifier = Modifier.weight(if (isCompact) 0.2f else 0.3f))
    }
}

@Composable
private fun HeaderCell(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = AppColors.TextSecondary,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.5.sp,
        modifier = modifier
    )
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun TableRowWithContextMenu(
    desenho: DesenhoAutodesk,
    actions: DesenhoActions,
    isCompact: Boolean = false
) {
    var showMenu by remember { mutableStateOf(false) }
    
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(AppColors.Surface)
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null
                ) { }
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type == PointerEventType.Press &&
                                event.button == PointerButton.Secondary) {
                                showMenu = true
                            }
                        }
                    }
                }
                .padding(horizontal = if (isCompact) 8.dp else 16.dp, vertical = if (isCompact) 10.dp else 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ArquivoCell(desenho, modifier = Modifier.weight(1.0f), isCompact = isCompact)
            FormatosCell(desenho, modifier = Modifier.weight(if (isCompact) 1.0f else 1.5f), isCompact = isCompact)
            if (!isCompact) {
                ComputadorCell(desenho.computador, modifier = Modifier.weight(0.8f))
                PastaCell(desenho, modifier = Modifier.weight(1.8f))
                EnviadoCell(desenho.horarioEnvio, modifier = Modifier.weight(0.8f), isCompact = isCompact)
            }
            AcoesCell(
                desenho = desenho,
                actions = actions,
                modifier = Modifier.weight(if (isCompact) 0.2f else 0.3f),
                isCompact = isCompact,
                showMenuExternal = showMenu,
                onMenuDismiss = { showMenu = false }
            )
        }
    }
}

@Composable
private fun AcoesCell(
    desenho: DesenhoAutodesk,
    actions: DesenhoActions,
    modifier: Modifier = Modifier,
    isCompact: Boolean = false,
    showMenuExternal: Boolean = false,
    onMenuDismiss: () -> Unit = {}
) {
    var showDropdown by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val status = desenho.statusEnum
    val podeRetry = status == DesenhoStatus.ERRO || status == DesenhoStatus.CANCELADO || status == DesenhoStatus.CONCLUIDO_COM_ERROS
    val podeCancelar = status == DesenhoStatus.PENDENTE || status == DesenhoStatus.PROCESSANDO
    
    // Sincroniza com menu externo (botão direito)
    LaunchedEffect(showMenuExternal) {
        if (showMenuExternal) showDropdown = true
    }
    
    // Dialog de confirmação para deletar
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = {
                Text(
                    text = "Deletar desenho?",
                    color = AppColors.TextPrimary,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "Tem certeza que deseja deletar \"${desenho.nomeArquivo}\"?\nEsta ação não pode ser desfeita.",
                    color = AppColors.TextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirm = false
                        actions.onDelete(desenho)
                    },
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = AppColors.BadgeRed
                    )
                ) {
                    Text("Deletar", color = AppColors.TextPrimary)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showDeleteConfirm = false }
                ) {
                    Text("Cancelar", color = AppColors.TextSecondary)
                }
            },
            backgroundColor = AppColors.Surface,
            shape = RoundedCornerShape(12.dp)
        )
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.CenterStart
    ) {
        // Botão 3 pontinhos
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(AppColors.SurfaceVariant)
                .border(1.dp, AppColors.Border, RoundedCornerShape(6.dp))
                .clickable { showDropdown = true }
                .pointerHoverIcon(PointerIcon.Hand)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "⋮",
                color = AppColors.TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }
        
        // Menu dropdown estilizado
        DropdownMenu(
            expanded = showDropdown,
            onDismissRequest = { 
                showDropdown = false
                onMenuDismiss()
            },
            modifier = Modifier
                .background(AppColors.Surface)
                .border(1.dp, AppColors.Border, RoundedCornerShape(8.dp))
                .widthIn(min = 220.dp)
        ) {
            // Header com nome do arquivo
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AppColors.SurfaceVariant)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    text = desenho.nomeArquivo,
                    color = AppColors.TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            
            Divider(color = AppColors.Border)
            
            // Data de envio
            val horarioFormatado = desenho.horarioEnvio
                .replace(Regex(":\\d{2}\\..*"), "")
                .replace(Regex("\\+\\d{2}$"), "")
            
            MenuItemStyled(
                icon = Icons.Filled.Schedule,
                label = "Enviado",
                value = horarioFormatado.ifEmpty { "—" },
                onClick = { showDropdown = false; onMenuDismiss() }
            )
            
            // Computador
            if (desenho.computador.isNotEmpty()) {
                MenuItemStyled(
                    icon = Icons.Filled.Computer,
                    label = "Computador",
                    value = desenho.computador,
                    onClick = { showDropdown = false; onMenuDismiss() }
                )
            }
            
            Divider(color = AppColors.Border, modifier = Modifier.padding(vertical = 4.dp))
            
            // Origem
            desenho.pastaOrigem?.takeIf { it.isNotEmpty() }?.let { origem ->
                MenuItemStyled(
                    icon = Icons.Filled.FolderOpen,
                    label = "Abrir Origem",
                    value = truncatePath(origem, 35),
                    isClickable = true,
                    onClick = { 
                        openInExplorer(origem)
                        showDropdown = false
                        onMenuDismiss()
                    }
                )
            }
            
            // Destino
            desenho.caminhoDestino.takeIf { it.isNotEmpty() }?.let { destino ->
                MenuItemStyled(
                    icon = Icons.Filled.Folder,
                    label = "Abrir Destino",
                    value = truncatePath(destino, 35),
                    isClickable = true,
                    onClick = { 
                        openInExplorer(destino)
                        showDropdown = false
                        onMenuDismiss()
                    }
                )
            }
            
            // Ações
            if (podeRetry || podeCancelar) {
                Divider(color = AppColors.Border, modifier = Modifier.padding(vertical = 4.dp))
                
                if (podeRetry) {
                    MenuItemStyled(
                        icon = Icons.Filled.Refresh,
                        label = "Reenviar",
                        isAction = true,
                        onClick = {
                            actions.onRetry(desenho)
                            showDropdown = false
                            onMenuDismiss()
                        }
                    )
                }
                if (podeCancelar) {
                    MenuItemStyled(
                        icon = Icons.Filled.Close,
                        label = "Cancelar",
                        isAction = true,
                        isDestructive = true,
                        onClick = {
                            actions.onCancel(desenho)
                            showDropdown = false
                            onMenuDismiss()
                        }
                    )
                }
            }
            
            // Deletar (sempre disponível)
            Divider(color = AppColors.Border, modifier = Modifier.padding(vertical = 4.dp))
            MenuItemStyled(
                icon = Icons.Filled.Delete,
                label = "Deletar",
                isAction = true,
                isDestructive = true,
                onClick = {
                    showDropdown = false
                    onMenuDismiss()
                    showDeleteConfirm = true
                }
            )
        }
    }
}

@Composable
private fun MenuItemStyled(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String? = null,
    isClickable: Boolean = false,
    isAction: Boolean = false,
    isDestructive: Boolean = false,
    onClick: () -> Unit
) {
    val iconColor = when {
        isDestructive -> AppColors.BadgeRed
        isClickable -> AppColors.Primary
        isAction -> AppColors.TextPrimary
        else -> AppColors.TextSecondary
    }
    
    val textColor = when {
        isDestructive -> AppColors.BadgeRed
        isClickable -> AppColors.Primary
        isAction -> AppColors.TextPrimary
        else -> AppColors.TextPrimary // Cor mais clara para valores
    }
    
    val labelColor = when {
        isAction -> textColor
        else -> AppColors.TextSecondary // Cor mais visível para labels
    }
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .pointerHoverIcon(if (isClickable || isAction) PointerIcon.Hand else PointerIcon.Default)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = iconColor,
            modifier = Modifier.size(16.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = labelColor,
                fontSize = if (isAction) 13.sp else 11.sp,
                fontWeight = if (isAction) FontWeight.Medium else FontWeight.Normal
            )
            if (value != null) {
                Text(
                    text = value,
                    color = textColor,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textDecoration = if (isClickable) TextDecoration.Underline else TextDecoration.None
                )
            }
        }
        if (isClickable) {
            Icon(
                imageVector = Icons.Filled.OpenInNew,
                contentDescription = "Abrir",
                tint = AppColors.TextSecondary,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

@Composable
private fun ArquivoCell(desenho: DesenhoAutodesk, modifier: Modifier = Modifier, isCompact: Boolean = false) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        val status = desenho.statusEnum
        
        // Posição na fila (se pendente ou processando)
        if ((status == DesenhoStatus.PENDENTE || status == DesenhoStatus.PROCESSANDO) 
            && desenho.posicaoFila != null) {
            Text(
                text = "#${desenho.posicaoFila}",
                color = AppColors.TextMuted,
                fontSize = if (isCompact) 10.sp else 12.sp,
                fontFamily = FontFamily.Monospace
            )
        }
        
        // Nome do arquivo
        Text(
            text = desenho.nomeArquivo.ifEmpty { "—" },
            color = AppColors.TextPrimary,
            fontSize = if (isCompact) 12.sp else 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun FormatosCell(desenho: DesenhoAutodesk, modifier: Modifier = Modifier, isCompact: Boolean = false) {
    val formatos = desenho.formatosSolicitados
    val status = desenho.statusEnum
    val progresso = desenho.progresso
    
    // Índice do primeiro formato ainda não gerado (o que está sendo processado)
    val primeiroPendenteIdx = remember(formatos, desenho.arquivosProcessados) {
        formatos.indexOfFirst { !desenho.formatoJaGerado(it) }
    }
    
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(if (isCompact) 3.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (formatos.isEmpty()) {
            Text(
                text = "—",
                color = AppColors.TextMuted,
                fontSize = if (isCompact) 12.sp else 14.sp
            )
        } else {
            formatos.forEachIndexed { idx, formato ->
                val gerado = desenho.formatoJaGerado(formato)
                val emProcessamento = status == DesenhoStatus.PROCESSANDO && !gerado
                val mostrarSpinner = emProcessamento && idx == primeiroPendenteIdx
                
                FormatoBadge(
                    formato = formato,
                    gerado = gerado,
                    status = status,
                    emProcessamento = emProcessamento,
                    mostrarSpinner = mostrarSpinner,
                    progresso = progresso,
                    isCompact = isCompact
                )
            }
        }
    }
}

@Composable
private fun FormatoBadge(
    formato: String,
    gerado: Boolean,
    status: DesenhoStatus,
    emProcessamento: Boolean = false,
    mostrarSpinner: Boolean = false,
    progresso: Int = 0,
    isCompact: Boolean = false
) {
    val (bgColor, textColor, borderColor) = when {
        gerado -> Triple(AppColors.BadgeGreenBg, AppColors.BadgeGreen, AppColors.BadgeGreen.copy(alpha = 0.5f))
        status == DesenhoStatus.CONCLUIDO_COM_ERROS -> Triple(AppColors.BadgeOrangeBg, AppColors.BadgeOrange, AppColors.BadgeOrange.copy(alpha = 0.5f))
        status == DesenhoStatus.ERRO -> Triple(AppColors.BadgeRedBg, AppColors.BadgeRed, AppColors.BadgeRed.copy(alpha = 0.5f))
        status == DesenhoStatus.CANCELADO -> Triple(AppColors.BadgeOrangeBg, AppColors.BadgeOrange, AppColors.BadgeOrange.copy(alpha = 0.5f))
        emProcessamento -> Triple(AppColors.BadgeBlueBg, AppColors.BadgeBlue, AppColors.BadgeBlue.copy(alpha = 0.5f))
        status == DesenhoStatus.PENDENTE || status == DesenhoStatus.PROCESSANDO -> Triple(AppColors.BadgeYellowBg, AppColors.BadgeYellow, AppColors.BadgeYellow.copy(alpha = 0.5f))
        else -> Triple(AppColors.BadgeGrayBg, AppColors.BadgeGray, AppColors.BadgeGray.copy(alpha = 0.3f))
    }
    
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(if (isCompact) 3.dp else 4.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(if (isCompact) 3.dp else 4.dp))
            .padding(horizontal = if (isCompact) 4.dp else 6.dp, vertical = if (isCompact) 1.dp else 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (isCompact) 2.dp else 4.dp)
    ) {
        if (gerado) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "Concluído",
                tint = textColor,
                modifier = Modifier.size(if (isCompact) 10.dp else 12.dp)
            )
        }
        if (mostrarSpinner && !isCompact) {
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 1.5.dp,
                color = textColor
            )
            Text(
                text = "${progresso}%",
                color = textColor,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
        }
        Text(
            text = formato.uppercase(),
            color = textColor,
            fontSize = if (isCompact) 9.sp else 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun ComputadorCell(computador: String, modifier: Modifier = Modifier) {
    Text(
        text = computador.ifEmpty { "—" },
        color = AppColors.TextPrimary,
        fontSize = 14.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}

@Composable
private fun PastaCell(desenho: DesenhoAutodesk, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        val pastaOrigem = desenho.pastaOrigem ?: ""
        val pastaDestino = desenho.caminhoDestino
        
        if (pastaOrigem.isNotEmpty()) {
            ClickablePath(
                label = "Origem",
                path = pastaOrigem
            )
        }
        
        if (pastaDestino.isNotEmpty()) {
            ClickablePath(
                label = "Destino",
                path = pastaDestino
            )
        }
        
        if (pastaOrigem.isEmpty() && pastaDestino.isEmpty()) {
            Text(
                text = "—",
                color = AppColors.TextMuted,
                fontSize = 14.sp
            )
        }
    }
}

@Composable
private fun ClickablePath(
    label: String,
    path: String
) {
    Text(
        text = "$label: ${truncatePath(path)}",
        color = AppColors.Primary,
        fontSize = 12.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textDecoration = TextDecoration.Underline,
        modifier = Modifier
            .clip(RoundedCornerShape(2.dp))
            .clickable { openInExplorer(path) }
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(vertical = 1.dp)
    )
}

@Composable
private fun EnviadoCell(horarioEnvio: String, modifier: Modifier = Modifier, isCompact: Boolean = false) {
    val horarioFormatado = if (isCompact) {
        // Em modo compacto, mostra apenas hora:minuto
        horarioEnvio
            .replace(Regex("^\\d{4}-\\d{2}-\\d{2}[T ]?"), "")
            .replace(Regex(":\\d{2}\\..*"), "")
            .replace(Regex("\\+\\d{2}$"), "")
    } else {
        horarioEnvio
            .replace(Regex(":\\d{2}\\..*"), "")
            .replace(Regex("\\+\\d{2}$"), "")
    }
    
    Text(
        text = horarioFormatado.ifEmpty { "—" },
        color = AppColors.TextPrimary,
        fontSize = if (isCompact) 12.sp else 13.sp,
        modifier = modifier
    )
}

@Composable
private fun EmptyState() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(48.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Nenhum desenho na fila",
                color = AppColors.TextSecondary,
                fontSize = 16.sp
            )
            Text(
                text = "Os desenhos enviados aparecerão aqui",
                color = AppColors.TextMuted,
                fontSize = 14.sp
            )
        }
    }
}

private fun truncatePath(path: String, maxLength: Int = 28): String {
    if (path.length <= maxLength) return path
    return "...${path.takeLast(maxLength - 3)}"
}

/**
 * Formata data yyyy-MM-dd para dd/MM/yyyy
 */
private fun formatDateBr(isoDate: String): String {
    if (isoDate.length < 10) return isoDate
    val parts = isoDate.take(10).split("-")
    if (parts.size != 3) return isoDate
    return "${parts[2]}/${parts[1]}/${parts[0]}"
}


private fun isLeapYear(year: Int): Boolean =
    (year % 4 == 0 && year % 100 != 0) || (year % 400 == 0)

// ==================== Custom Date Range Picker (shadcn/ui style) ====================

/**
 * Data class representando ano/mês
 */
private data class YearMonth(val year: Int, val month: Int) {
    fun next(): YearMonth = if (month == 12) YearMonth(year + 1, 1) else YearMonth(year, month + 1)
    fun prev(): YearMonth = if (month == 1) YearMonth(year - 1, 12) else YearMonth(year, month - 1)
    fun daysInMonth(): Int {
        val leap = isLeapYear(year)
        return when (month) {
            1 -> 31; 2 -> if (leap) 29 else 28; 3 -> 31; 4 -> 30
            5 -> 31; 6 -> 30; 7 -> 31; 8 -> 31; 9 -> 30; 10 -> 31; 11 -> 30; 12 -> 31
            else -> 30
        }
    }
    /** Dia da semana do primeiro dia (0=Dom, 1=Seg, ..., 6=Sab) */
    fun firstDayOfWeek(): Int {
        // Zeller/Tomohiko Sakamoto
        val y0 = if (month <= 2) year - 1 else year
        val m0 = if (month <= 2) month + 12 else month
        val t = intArrayOf(0, 3, 2, 5, 0, 3, 5, 1, 4, 6, 2, 4)
        return (y0 + y0 / 4 - y0 / 100 + y0 / 400 + t[m0 - 3] + 1) % 7
    }
    fun toTriple(day: Int) = Triple(year, month, day)
}

private val MONTH_NAMES = arrayOf(
    "Janeiro", "Fevereiro", "Março", "Abril", "Maio", "Junho",
    "Julho", "Agosto", "Setembro", "Outubro", "Novembro", "Dezembro"
)

private fun tripleToIso(t: Triple<Int, Int, Int>): String =
    "%04d-%02d-%02d".format(t.first, t.second, t.third)

private fun isoToTriple(iso: String): Triple<Int, Int, Int>? {
    if (iso.length < 10) return null
    val p = iso.take(10).split("-")
    if (p.size != 3) return null
    val y = p[0].toIntOrNull() ?: return null
    val m = p[1].toIntOrNull() ?: return null
    val d = p[2].toIntOrNull() ?: return null
    return Triple(y, m, d)
}

private fun tripleCompare(a: Triple<Int, Int, Int>, b: Triple<Int, Int, Int>): Int {
    if (a.first != b.first) return a.first - b.first
    if (a.second != b.second) return a.second - b.second
    return a.third - b.third
}

private fun isBetween(
    day: Triple<Int, Int, Int>,
    start: Triple<Int, Int, Int>,
    end: Triple<Int, Int, Int>
): Boolean = tripleCompare(day, start) >= 0 && tripleCompare(day, end) <= 0

/** Atalho de período rápido exibido no picker */
private data class PeriodoShortcut(val label: String, val de: String, val ate: String)

/**
 * Date Range Picker unificado — atalhos de período rápido + calendário customizado.
 * Atalhos NÃO fecham o dialog: apenas pré-selecionam o intervalo no calendário.
 * O usuário confirma com "Aplicar".
 */
@Composable
private fun DateRangePickerDialog(
    currentDe: String,
    currentAte: String,
    activePreset: String?,
    onApply: (de: String, ate: String, preset: String?) -> Unit,
    onDismiss: () -> Unit
) {
    val today = getTodayDate()
    val year = today.take(4)
    val todayTriple = isoToTriple(today) ?: Triple(2026, 1, 1)

    // Atalhos definidos uma vez
    val shortcuts = remember {
        listOf(
            PeriodoShortcut("7 dias",       getDaysAgoDate(7),  today),
            PeriodoShortcut("30 dias",      getDaysAgoDate(30), today),
            PeriodoShortcut("Este ano",     "$year-01-01",      today),
            PeriodoShortcut("Todo período", "",                 "")
        )
    }

    // Estado interno do picker
    val initStart = if (currentDe.isNotBlank()) isoToTriple(currentDe) else null
    val initEnd   = if (currentAte.isNotBlank()) isoToTriple(currentAte) else null

    var rangeStart   by remember { mutableStateOf(initStart) }
    var rangeEnd     by remember { mutableStateOf(initEnd) }
    var pendingPreset by remember { mutableStateOf(activePreset) }
    var hoveredDay   by remember { mutableStateOf<Triple<Int, Int, Int>?>(null) }

    val initYm = if (initStart != null) YearMonth(initStart.first, initStart.second)
                 else YearMonth(todayTriple.first, todayTriple.second)
    var leftMonth by remember { mutableStateOf(initYm) }
    val rightMonth = leftMonth.next()

    // Clique num atalho: pré-seleciona o intervalo e navega o calendário, mas NÃO fecha
    fun applyShortcutPreview(shortcut: PeriodoShortcut) {
        pendingPreset = shortcut.label
        if (shortcut.de.isBlank() && shortcut.ate.isBlank()) {
            // "Todo período" → limpa seleção no calendário
            rangeStart = null
            rangeEnd = null
        } else {
            rangeStart = isoToTriple(shortcut.de)
            rangeEnd   = if (shortcut.ate.isNotBlank()) isoToTriple(shortcut.ate) else null
            // Navega calendário para o início do intervalo
            rangeStart?.let { leftMonth = YearMonth(it.first, it.second) }
        }
    }

    // Clique manual no calendário: limpa preset
    fun onDayClick(day: Triple<Int, Int, Int>) {
        pendingPreset = null
        if (rangeStart == null || rangeEnd != null) {
            rangeStart = day; rangeEnd = null
        } else {
            if (tripleCompare(day, rangeStart!!) < 0) {
                rangeEnd = rangeStart; rangeStart = day
            } else {
                rangeEnd = day
            }
        }
    }

    // Pode aplicar se: atalho selecionado OU range manual completo
    val canApply = pendingPreset != null || (rangeStart != null && rangeEnd != null)

    val effectiveEnd: Triple<Int, Int, Int>? = rangeEnd ?: if (rangeStart != null && hoveredDay != null &&
        tripleCompare(hoveredDay!!, rangeStart!!) >= 0) hoveredDay else null
    val effectiveStart: Triple<Int, Int, Int>? = if (rangeEnd == null && rangeStart != null &&
        hoveredDay != null && tripleCompare(hoveredDay!!, rangeStart!!) < 0) hoveredDay else rangeStart

    // Texto de prévia da seleção
    val previewText = when {
        pendingPreset == "Todo período" -> "Todo o histórico"
        pendingPreset != null           -> pendingPreset!!
        rangeStart != null && rangeEnd != null -> {
            "${formatDateBr(tripleToIso(rangeStart!!))}  →  ${formatDateBr(tripleToIso(rangeEnd!!))}"
        }
        rangeStart != null -> "${formatDateBr(tripleToIso(rangeStart!!))}  →  ..."
        else -> "Selecione um período"
    }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(600.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(AppColors.Surface)
                .border(1.dp, AppColors.Border, RoundedCornerShape(12.dp))
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Filtrar período",
                        color = AppColors.TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = previewText,
                        color = if (canApply) AppColors.Primary else AppColors.TextMuted,
                        fontSize = 12.sp
                    )
                }
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Fechar",
                    tint = AppColors.TextMuted,
                    modifier = Modifier
                        .size(20.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { onDismiss() }
                        .pointerHoverIcon(PointerIcon.Hand)
                )
            }

            Divider(color = AppColors.Border)

            // Atalhos de período rápido — NÃO fecham o dialog
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AppColors.SurfaceVariant)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                shortcuts.forEach { shortcut ->
                    val isActive = pendingPreset == shortcut.label
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (isActive) AppColors.Primary.copy(alpha = 0.15f) else AppColors.Surface
                            )
                            .border(
                                1.dp,
                                if (isActive) AppColors.Primary.copy(alpha = 0.6f) else AppColors.Border,
                                RoundedCornerShape(6.dp)
                            )
                            .clickable { applyShortcutPreview(shortcut) }
                            .pointerHoverIcon(PointerIcon.Hand)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = shortcut.label,
                            color = if (isActive) AppColors.Primary else AppColors.TextPrimary,
                            fontSize = 12.sp,
                            fontWeight = if (isActive) FontWeight.Medium else FontWeight.Normal
                        )
                    }
                }
            }

            Divider(color = AppColors.Border)

            // Divisor com título
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(modifier = Modifier.weight(1f).height(1.dp).background(AppColors.Border))
                Text(text = "ou selecione manualmente", color = AppColors.TextMuted, fontSize = 11.sp)
                Box(modifier = Modifier.weight(1f).height(1.dp).background(AppColors.Border))
            }

            // Calendários
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CalendarMonth(
                    yearMonth = leftMonth,
                    rangeStart = effectiveStart,
                    rangeEnd = effectiveEnd,
                    hoveredDay = hoveredDay,
                    onDayClick = ::onDayClick,
                    onDayHover = { hoveredDay = it },
                    onPrevMonth = { leftMonth = leftMonth.prev() },
                    onNextMonth = null,
                    modifier = Modifier.weight(1f)
                )
                Box(modifier = Modifier.width(1.dp).height(260.dp).background(AppColors.Border))
                CalendarMonth(
                    yearMonth = rightMonth,
                    rangeStart = effectiveStart,
                    rangeEnd = effectiveEnd,
                    hoveredDay = hoveredDay,
                    onDayClick = ::onDayClick,
                    onDayHover = { hoveredDay = it },
                    onPrevMonth = null,
                    onNextMonth = { leftMonth = leftMonth.next() },
                    modifier = Modifier.weight(1f)
                )
            }

            Divider(color = AppColors.Border)

            // Footer
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(Modifier.weight(1f))
                Text(
                    text = "Cancelar",
                    color = AppColors.TextSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .border(1.dp, AppColors.Border, RoundedCornerShape(6.dp))
                        .clickable { onDismiss() }
                        .pointerHoverIcon(PointerIcon.Hand)
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                )
                Text(
                    text = "Aplicar",
                    color = if (canApply) AppColors.TextPrimary else AppColors.TextMuted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (canApply) AppColors.Primary else AppColors.Primary.copy(alpha = 0.3f))
                        .clickable(enabled = canApply) {
                            if (pendingPreset != null) {
                                // Aplica atalho selecionado
                                val s = shortcuts.find { it.label == pendingPreset }
                                onApply(s?.de ?: "", s?.ate?.let { if (it == today) "" else it } ?: "", pendingPreset)
                            } else {
                                val de  = rangeStart?.let { tripleToIso(it) } ?: ""
                                val ate = rangeEnd?.let   { tripleToIso(it) } ?: ""
                                onApply(de, ate, null)
                            }
                        }
                        .pointerHoverIcon(if (canApply) PointerIcon.Hand else PointerIcon.Default)
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                )
            }
        }
    }
}

/**
 * Mês de calendário individual estilo shadcn/ui
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun CalendarMonth(
    yearMonth: YearMonth,
    rangeStart: Triple<Int, Int, Int>?,
    rangeEnd: Triple<Int, Int, Int>?,
    hoveredDay: Triple<Int, Int, Int>?,
    onDayClick: (Triple<Int, Int, Int>) -> Unit,
    onDayHover: (Triple<Int, Int, Int>?) -> Unit,
    onPrevMonth: (() -> Unit)?,
    onNextMonth: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val daysInMonth = yearMonth.daysInMonth()
    val firstDow = yearMonth.firstDayOfWeek() // 0=Dom
    val weekDays = listOf("Do", "Se", "Te", "Qu", "Qi", "Se", "Sa")

    Column(modifier = modifier) {
        // Month/Year header with nav
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Prev arrow
            if (onPrevMonth != null) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowLeft,
                    contentDescription = "Mês anterior",
                    tint = AppColors.TextSecondary,
                    modifier = Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { onPrevMonth() }
                        .pointerHoverIcon(PointerIcon.Hand)
                )
            } else {
                Spacer(Modifier.size(24.dp))
            }

            Text(
                text = "${MONTH_NAMES[yearMonth.month - 1]} ${yearMonth.year}",
                color = AppColors.TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )

            if (onNextMonth != null) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowRight,
                    contentDescription = "Próximo mês",
                    tint = AppColors.TextSecondary,
                    modifier = Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { onNextMonth() }
                        .pointerHoverIcon(PointerIcon.Hand)
                )
            } else {
                Spacer(Modifier.size(24.dp))
            }
        }

        // Week day headers
        Row(modifier = Modifier.fillMaxWidth()) {
            weekDays.forEach { d ->
                Box(
                    modifier = Modifier.weight(1f).height(28.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = d,
                        color = AppColors.TextMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        // Day grid (6 rows max)
        var dayCounter = 1
        for (week in 0..5) {
            if (dayCounter > daysInMonth) break
            Row(modifier = Modifier.fillMaxWidth()) {
                for (dow in 0..6) {
                    val cellIndex = week * 7 + dow
                    if (cellIndex < firstDow || dayCounter > daysInMonth) {
                        // Empty cell
                        Box(modifier = Modifier.weight(1f).height(32.dp))
                    } else {
                        val day = dayCounter
                        val triple = yearMonth.toTriple(day)
                        val isStart = rangeStart != null && tripleCompare(triple, rangeStart) == 0
                        val isEnd = rangeEnd != null && tripleCompare(triple, rangeEnd) == 0
                        val inRange = rangeStart != null && rangeEnd != null &&
                                isBetween(triple, rangeStart, rangeEnd)
                        val isHovered = hoveredDay != null && tripleCompare(triple, hoveredDay) == 0

                        val bgColor = when {
                            isStart || isEnd -> AppColors.Primary
                            inRange -> AppColors.Primary.copy(alpha = 0.15f)
                            isHovered -> AppColors.SurfaceVariant
                            else -> androidx.compose.ui.graphics.Color.Transparent
                        }
                        val textColor = when {
                            isStart || isEnd -> androidx.compose.ui.graphics.Color.White
                            inRange -> AppColors.TextPrimary
                            else -> AppColors.TextPrimary
                        }
                        val shape = when {
                            isStart && isEnd -> RoundedCornerShape(6.dp)
                            isStart -> RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp)
                            isEnd -> RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp)
                            inRange -> RoundedCornerShape(0.dp)
                            else -> RoundedCornerShape(6.dp)
                        }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(32.dp)
                                .clip(shape)
                                .background(bgColor)
                                .clickable { onDayClick(triple) }
                                .pointerHoverIcon(PointerIcon.Hand)
                                .pointerInput(triple) {
                                    awaitPointerEventScope {
                                        while (true) {
                                            val event = awaitPointerEvent()
                                            when (event.type) {
                                                PointerEventType.Enter -> onDayHover(triple)
                                                PointerEventType.Exit -> onDayHover(null)
                                            }
                                        }
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = day.toString(),
                                color = textColor,
                                fontSize = 12.sp,
                                fontWeight = if (isStart || isEnd) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                        dayCounter++
                    }
                }
            }
        }
    }
}
