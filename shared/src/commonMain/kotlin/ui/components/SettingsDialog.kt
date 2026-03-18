package ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import isViewerMode
import loadCurrentSettings
import restartApp
import saveSettings
import ui.theme.AppColors

@Composable
fun SettingsDialog(onDismiss: () -> Unit) {
    val isViewer = remember { isViewerMode() }
    val current = remember { loadCurrentSettings() }

    var dbHost by remember { mutableStateOf(current["DB_HOST"] ?: "192.168.1.152") }
    var dbPort by remember { mutableStateOf(current["DB_PORT"] ?: "5432") }
    var dbName by remember { mutableStateOf(current["DB_NAME"] ?: "gem_jhonrob") }
    var dbUser by remember { mutableStateOf(current["DB_USER"] ?: "ksi") }
    var dbPassword by remember { mutableStateOf(current["DB_PASSWORD"] ?: "ksi") }
    var serverUrl by remember { mutableStateOf(current["SERVER_URL"] ?: "http://192.168.1.66:8080") }

    var saved by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(420.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(AppColors.Surface)
                .border(1.dp, AppColors.Border, RoundedCornerShape(12.dp))
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AppColors.SurfaceVariant)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = null,
                        tint = AppColors.Primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = if (isViewer) "Configurações — Viewer" else "Configurações — Host",
                        color = AppColors.TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Fechar",
                    tint = AppColors.TextMuted,
                    modifier = Modifier
                        .size(18.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .clickable(onClick = onDismiss)
                        .pointerHoverIcon(PointerIcon.Hand)
                )
            }

            Divider(color = AppColors.Border)

            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (saved) {
                    // Mensagem de sucesso — reiniciando
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(AppColors.BadgeGreenBg)
                            .border(1.dp, AppColors.BadgeGreen.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                            .padding(12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "Configurações salvas!",
                                color = AppColors.BadgeGreen,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "O app será reiniciado agora...",
                                color = AppColors.TextSecondary,
                                fontSize = 12.sp
                            )
                        }
                    }
                } else {
                    // Descrição do modo
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(AppColors.Primary.copy(alpha = 0.08f))
                            .border(1.dp, AppColors.Primary.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                            .padding(10.dp)
                    ) {
                        Text(
                            text = if (isViewer)
                                "Viewer: só visualiza. Conecte ao endereço do App Host (IP:porta). Altere se o Host mudou de máquina. Config é guardada na pasta do usuário (sobrevive a atualizações)."
                            else
                                "Host: app com servidor embutido. Banco PostgreSQL (IP/porta) fica na máquina KSI. Config é guardada na pasta do usuário (sobrevive a atualizações).",
                            color = AppColors.TextSecondary,
                            fontSize = 11.sp,
                            lineHeight = 16.sp
                        )
                    }

                    if (isViewer) {
                        // Viewer: só URL do servidor
                        SettingsField(
                            label = "Endereço do Servidor",
                            hint = "http://192.168.1.66:8080",
                            value = serverUrl,
                            onValueChange = { serverUrl = it }
                        )
                    } else {
                        // Server: campos do banco
                        SettingsField(
                            label = "IP do Banco de Dados",
                            hint = "192.168.1.152",
                            value = dbHost,
                            onValueChange = { dbHost = it }
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            SettingsField(
                                label = "Porta",
                                hint = "5432",
                                value = dbPort,
                                onValueChange = { dbPort = it },
                                modifier = Modifier.weight(0.4f)
                            )
                            SettingsField(
                                label = "Nome do Banco",
                                hint = "gem_jhonrob",
                                value = dbName,
                                onValueChange = { dbName = it },
                                modifier = Modifier.weight(0.6f)
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            SettingsField(
                                label = "Usuário",
                                hint = "ksi",
                                value = dbUser,
                                onValueChange = { dbUser = it },
                                modifier = Modifier.weight(1f)
                            )
                            SettingsField(
                                label = "Senha",
                                hint = "••••••",
                                value = dbPassword,
                                onValueChange = { dbPassword = it },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    errorMsg?.let { msg ->
                        Text(
                            text = msg,
                            color = AppColors.BadgeRed,
                            fontSize = 11.sp
                        )
                    }
                }
            }

            if (!saved) {
                Divider(color = AppColors.Border)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
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
                            .clickable(onClick = onDismiss)
                            .pointerHoverIcon(PointerIcon.Hand)
                            .padding(horizontal = 14.dp, vertical = 7.dp)
                    )

                    Text(
                        text = "Salvar e Reiniciar",
                        color = AppColors.TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(AppColors.Primary)
                            .clickable {
                                val values = if (isViewer) {
                                    mapOf("SERVER_URL" to serverUrl.trim())
                                } else {
                                    mapOf(
                                        "DB_HOST" to dbHost.trim(),
                                        "DB_PORT" to dbPort.trim(),
                                        "DB_NAME" to dbName.trim(),
                                        "DB_USER" to dbUser.trim(),
                                        "DB_PASSWORD" to dbPassword.trim()
                                    )
                                }
                                val ok = saveSettings(values)
                                if (ok) {
                                    saved = true
                                    errorMsg = null
                                    restartApp()
                                } else {
                                    errorMsg = "Não foi possível salvar. Verifique se C:\\gem-exportador existe."
                                }
                            }
                            .pointerHoverIcon(PointerIcon.Hand)
                            .padding(horizontal = 14.dp, vertical = 7.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsField(
    label: String,
    hint: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            color = AppColors.TextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(AppColors.Background)
                .border(1.dp, AppColors.Border, RoundedCornerShape(6.dp))
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(
                    color = AppColors.TextPrimary,
                    fontSize = 13.sp
                ),
                cursorBrush = SolidColor(AppColors.Primary),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) {
                            Text(hint, color = AppColors.TextMuted, fontSize = 13.sp)
                        }
                        innerTextField()
                    }
                }
            )
        }
    }
}
