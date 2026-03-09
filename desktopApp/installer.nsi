; GemExportador NSIS Installer
; PostgreSQL embutido no instalador - sem download em runtime

!include "MUI2.nsh"
!include "nsDialogs.nsh"
!include "LogicLib.nsh"

; --- Defines (passados pelo Gradle via /D) ---
!ifndef APP_VERSION
  !define APP_VERSION "1.0.0"
!endif
!ifndef APP_NAME
  !define APP_NAME "GemExportador"
!endif
!ifndef APP_DIR
  !define APP_DIR "build\compose\binaries\main\app\GemExportador"
!endif
!ifndef OUTPUT_DIR
  !define OUTPUT_DIR "build\compose\binaries\main\nsis"
!endif
!ifndef ICON_FILE
  !define ICON_FILE "src\jvmMain\resources\favicon.ico"
!endif
; --- Configuracao geral ---
Name "${APP_NAME} ${APP_VERSION}"
OutFile "${OUTPUT_DIR}\${APP_NAME}-${APP_VERSION}-setup.exe"
InstallDir "$PROGRAMFILES\${APP_NAME}"
InstallDirRegKey HKLM "Software\${APP_NAME}" "InstallDir"
RequestExecutionLevel admin
SetCompressor /SOLID lzma

; --- Interface ---
!define MUI_ABORTWARNING
!define MUI_ICON "${ICON_FILE}"
!define MUI_UNICON "${ICON_FILE}"

; --- Variaveis para modo de instalacao ---
Var GemMode       ; "server" ou "viewer"
Var ServerIP      ; IP do servidor (viewer)
Var RadioServer   ; Handle radio button servidor
Var RadioViewer   ; Handle radio button viewer
Var InputIP       ; Handle input IP

; --- Paginas do instalador ---
!insertmacro MUI_PAGE_WELCOME
Page custom ModeSelectionPage ModeSelectionLeave
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
!define MUI_FINISHPAGE_RUN
!define MUI_FINISHPAGE_RUN_FUNCTION "LaunchApp"
!insertmacro MUI_PAGE_FINISH

; --- Paginas do desinstalador ---
!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES

; --- Idioma ---
!insertmacro MUI_LANGUAGE "PortugueseBR"

; --- Funcao para desinstalar versao anterior ---
; Em silent mode (/S = auto-update): não roda o uninstaller antigo,
; apenas sobrescreve os arquivos. Isso evita que o uninstaller antigo
; (que não tem suporte a /SD) mostre MessageBox bloqueante.
; Também preserva .env, PostgreSQL e config existente.
Function .onInit
  ${If} ${Silent}
    ; AUTO-UPDATE: ler config existente do registro e pular uninstall
    ReadRegStr $INSTDIR HKLM "Software\${APP_NAME}" "InstallDir"
    ${If} $INSTDIR == ""
      StrCpy $INSTDIR "$PROGRAMFILES\${APP_NAME}"
    ${EndIf}
    ReadRegStr $GemMode HKLM "Software\${APP_NAME}" "GemMode"
    ${If} $GemMode == ""
      StrCpy $GemMode "server"
    ${EndIf}
    Goto done
  ${EndIf}

  ; INSTALACAO INTERATIVA: verificar versao anterior
  ReadRegStr $0 HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\${APP_NAME}" "UninstallString"
  StrCmp $0 "" done
  MessageBox MB_YESNO|MB_ICONQUESTION "Uma versao anterior do ${APP_NAME} foi encontrada.$\nDeseja desinstalar antes de continuar?" /SD IDYES IDYES uninst IDNO done
  uninst:
    ExecWait '"$0" /S'
  done:
FunctionEnd

; ==========================================
; Pagina customizada: Selecao de modo
; ==========================================
Function ModeSelectionPage
  ; Em silent mode (auto-update): pular pagina, manter config existente
  ${If} ${Silent}
    Abort
  ${EndIf}

  ; Em upgrade interativo: se GemMode ja existe no registro, pular tambem
  ReadRegStr $0 HKLM "Software\${APP_NAME}" "GemMode"
  ${If} $0 != ""
    StrCpy $GemMode $0
    ; Ler IP existente do registro para viewer
    ReadRegStr $ServerIP HKLM "Software\${APP_NAME}" "ServerIP"
    Abort
  ${EndIf}

  nsDialogs::Create 1018
  Pop $0
  ${If} $0 == error
    Abort
  ${EndIf}

  ; Titulo
  ${NSD_CreateLabel} 0 0 100% 24u "Selecione o tipo de instalacao:"
  Pop $0

  ; Radio: Servidor (padrao)
  ${NSD_CreateRadioButton} 20u 30u 100% 12u "Servidor (app completo com processamento Inventor)"
  Pop $RadioServer
  ${NSD_SetState} $RadioServer ${BST_CHECKED}

  ; Radio: Viewer
  ${NSD_CreateRadioButton} 20u 48u 100% 12u "Viewer (somente visualizacao, conecta em servidor remoto)"
  Pop $RadioViewer

  ; Label IP
  ${NSD_CreateLabel} 20u 72u 100% 12u "IP do servidor (somente para Viewer):"
  Pop $0

  ; Input IP
  ${NSD_CreateText} 20u 86u 200u 14u "192.168.1.66"
  Pop $InputIP

  StrCpy $GemMode "server"

  nsDialogs::Show
FunctionEnd

Function ModeSelectionLeave
  ; Em silent mode: nada a validar
  ${If} ${Silent}
    Return
  ${EndIf}

  ; Verifica qual radio esta selecionado
  ${NSD_GetState} $RadioServer $0
  ${If} $0 == ${BST_CHECKED}
    StrCpy $GemMode "server"
  ${Else}
    StrCpy $GemMode "viewer"
    ; Le o IP digitado
    ${NSD_GetText} $InputIP $ServerIP
    ${If} $ServerIP == ""
      MessageBox MB_OK|MB_ICONEXCLAMATION "Informe o IP do servidor para o modo Viewer."
      Abort
    ${EndIf}
  ${EndIf}
FunctionEnd

; --- Secao de instalacao ---
Section "Install"
  SetOutPath "$INSTDIR"

  ; ============================================
  ; Em silent mode (auto-update): limpar binarios antigos antes de copiar
  ; Isso garante que arquivos obsoletos não fiquem residuais
  ; ============================================
  ${If} ${Silent}
    DetailPrint "Atualizacao: limpando binarios antigos..."
    RMDir /r "$INSTDIR\app"
    RMDir /r "$INSTDIR\runtime"
    RMDir /r "$INSTDIR\scripts"
  ${EndIf}

  ; Icone do app
  File "/oname=${APP_NAME}.ico" "${ICON_FILE}"

  ; Diretorio app (JARs, DLLs, resources, config)
  SetOutPath "$INSTDIR\app"
  File /r "${APP_DIR}\app\*.*"

  ; Diretorio runtime (JRE com javaw.exe injetado)
  SetOutPath "$INSTDIR\runtime"
  File /r "${APP_DIR}\runtime\*.*"

  ; Scripts (processar-inventor.vbs e outros)
  SetOutPath "$INSTDIR\scripts"
  File /r "..\scripts\*.*"

  SetOutPath "$INSTDIR"

  ; ============================================
  ; Prepara diretorios
  ; ============================================
  DetailPrint "Preparando diretorios..."
  CreateDirectory "C:\gem-exportador"
  CreateDirectory "C:\gem-exportador\logs"

  ; ============================================
  ; Em silent mode (auto-update): PRESERVAR .env existente
  ; Não regenerar configuração — manter server/viewer, IP, banco, etc.
  ; Também não re-executar setup-postgres.cmd (PostgreSQL já está instalado)
  ; ============================================
  ${If} ${Silent}
    DetailPrint "Atualizacao: preservando configuracao existente (.env)"
    Goto env_done
  ${EndIf}

  ; ============================================
  ; Instalacao interativa: gera .env conforme modo selecionado
  ; ============================================

  ; Se .env ja existe em C:\gem-exportador (upgrade interativo), perguntar se deseja manter
  IfFileExists "C:\gem-exportador\.env" 0 env_generate
    MessageBox MB_YESNO|MB_ICONQUESTION "Configuracao existente encontrada.$\nDeseja manter a configuracao atual?" /SD IDYES IDYES env_done IDNO env_generate

  env_generate:
  StrCmp $GemMode "viewer" env_viewer env_server

  env_server:
    DetailPrint "Configurando modo SERVIDOR..."
    CreateDirectory "C:\gem-exportador\controle"
    ; Config em C:\gem-exportador\.env (editavel pelo usuario sem admin)
    FileOpen $0 "C:\gem-exportador\.env" w
    FileWrite $0 'GEM_MODE=server$\r$\n'
    FileWrite $0 'SERVER_HOST=0.0.0.0$\r$\n'
    FileWrite $0 'SERVER_PORT=8080$\r$\n'
    FileWrite $0 'SERVER_URL=http://localhost:8080$\r$\n'
    FileWrite $0 'INVENTOR_PASTA_CONTROLE=C:\gem-exportador\controle$\r$\n'
    FileWrite $0 'LOG_LEVEL=INFO$\r$\n'
    FileWrite $0 'LOG_DIR=C:\gem-exportador\logs$\r$\n'
    FileWrite $0 '$\r$\n'
    FileWrite $0 '# PostgreSQL KSI (producao) - altere pelo app em Configuracoes$\r$\n'
    FileWrite $0 'DB_HOST=192.168.1.152$\r$\n'
    FileWrite $0 'DB_PORT=5432$\r$\n'
    FileWrite $0 'DB_NAME=gem_jhonrob$\r$\n'
    FileWrite $0 'DB_USER=ksi$\r$\n'
    FileWrite $0 'DB_PASSWORD=ksi$\r$\n'
    FileWrite $0 '$\r$\n'
    FileWrite $0 'SUPABASE_BACKUP_ENABLED=false$\r$\n'
    FileClose $0
    Goto env_done

  env_viewer:
    DetailPrint "Configurando modo VIEWER (servidor: $ServerIP)..."
    ; Config em C:\gem-exportador\.env (editavel pelo usuario sem admin)
    FileOpen $0 "C:\gem-exportador\.env" w
    FileWrite $0 'GEM_MODE=viewer$\r$\n'
    FileWrite $0 'SERVER_URL=http://$ServerIP:8080$\r$\n'
    FileWrite $0 'LOG_LEVEL=INFO$\r$\n'
    FileWrite $0 'LOG_DIR=C:\gem-exportador\logs$\r$\n'
    FileWrite $0 '$\r$\n'
    FileWrite $0 '# PostgreSQL (banco no servidor - nao usado pelo viewer)$\r$\n'
    FileWrite $0 'DB_HOST=$ServerIP$\r$\n'
    FileWrite $0 'DB_PORT=5432$\r$\n'
    FileWrite $0 'DB_NAME=gem_jhonrob$\r$\n'
    FileWrite $0 'DB_USER=ksi$\r$\n'
    FileWrite $0 'DB_PASSWORD=ksi$\r$\n'
    FileClose $0

  env_done:

  ; Script de lancamento
  FileOpen $0 "$INSTDIR\launch.cmd" w
  FileWrite $0 '@echo off$\r$\n'
  FileWrite $0 'cd /d "%~dp0"$\r$\n'
  FileWrite $0 'start "" "runtime\bin\javaw.exe" ^$\r$\n'
  FileWrite $0 '  --add-opens java.desktop/sun.awt=ALL-UNNAMED ^$\r$\n'
  FileWrite $0 '  --add-opens java.desktop/java.awt.peer=ALL-UNNAMED ^$\r$\n'
  FileWrite $0 '  -Dskiko.library.path="app" ^$\r$\n'
  FileWrite $0 '  -Dcompose.application.resources.dir="app\resources" ^$\r$\n'
  FileWrite $0 '  -Dcompose.application.configure.swing.globals=true ^$\r$\n'
  FileWrite $0 '  -Djpackage.app-version=${APP_VERSION} ^$\r$\n'
  FileWrite $0 '  -cp "app\*" MainKt$\r$\n'
  FileClose $0

  ; Desinstalador
  WriteUninstaller "$INSTDIR\uninstall.exe"

  ; Shortcuts
  CreateShortCut "$DESKTOP\${APP_NAME}.lnk" \
    "$INSTDIR\launch.cmd" "" \
    "$INSTDIR\${APP_NAME}.ico" 0 \
    SW_SHOWMINIMIZED

  CreateDirectory "$SMPROGRAMS\${APP_NAME}"
  CreateShortCut "$SMPROGRAMS\${APP_NAME}\${APP_NAME}.lnk" \
    "$INSTDIR\launch.cmd" "" \
    "$INSTDIR\${APP_NAME}.ico" 0 \
    SW_SHOWMINIMIZED
  CreateShortCut "$SMPROGRAMS\${APP_NAME}\Desinstalar ${APP_NAME}.lnk" \
    "$INSTDIR\uninstall.exe"

  ; Registro
  WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\${APP_NAME}" \
    "DisplayName" "${APP_NAME}"
  WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\${APP_NAME}" \
    "DisplayVersion" "${APP_VERSION}"
  WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\${APP_NAME}" \
    "Publisher" "JhonRob"
  WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\${APP_NAME}" \
    "UninstallString" '"$INSTDIR\uninstall.exe"'
  WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\${APP_NAME}" \
    "DisplayIcon" '"$INSTDIR\${APP_NAME}.ico"'
  WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\${APP_NAME}" \
    "InstallLocation" "$INSTDIR"
  WriteRegDWORD HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\${APP_NAME}" \
    "NoModify" 1
  WriteRegDWORD HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\${APP_NAME}" \
    "NoRepair" 1
  WriteRegStr HKLM "Software\${APP_NAME}" "InstallDir" "$INSTDIR"
  WriteRegStr HKLM "Software\${APP_NAME}" "GemMode" "$GemMode"
  WriteRegStr HKLM "Software\${APP_NAME}" "ServerIP" "$ServerIP"
SectionEnd

; --- Secao de desinstalacao ---
Section "Uninstall"
  ; Verifica o modo de instalacao (server ou viewer)
  ReadRegStr $0 HKLM "Software\${APP_NAME}" "GemMode"

  ; ============================================
  ; Em silent mode (chamado durante auto-update):
  ; - NAO parar PostgreSQL (continua rodando)
  ; - NAO perguntar sobre dados
  ; - NAO remover .env (preservar config)
  ; - Apenas remover binarios do app
  ; ============================================
  ${If} ${Silent}
    ; Apenas remover binarios para o novo instalador sobrescrever
    RMDir /r "$INSTDIR\app"
    RMDir /r "$INSTDIR\runtime"
    RMDir /r "$INSTDIR\scripts"
    Delete "$INSTDIR\${APP_NAME}.ico"
    Delete "$INSTDIR\launch.cmd"
    Delete "$INSTDIR\setup-postgres.cmd"
    Delete "$INSTDIR\uninstall.exe"
    ; NÃO remove .env, .registro, atalhos (novo instalador recria)
    Goto uninstall_done
  ${EndIf}

  ; ============================================
  ; Desinstalacao interativa completa
  ; ============================================

  ; Somente servidor: para o servico PostgreSQL
  StrCmp $0 "viewer" skip_pg_stop
    nsExec::ExecToLog 'net stop GemPostgreSQL'
    nsExec::ExecToLog '"C:\gem-exportador\pgsql\bin\pg_ctl.exe" unregister -N GemPostgreSQL'
  skip_pg_stop:

  ; Remove arquivos do app
  RMDir /r "$INSTDIR\app"
  RMDir /r "$INSTDIR\runtime"
  RMDir /r "$INSTDIR\scripts"
  Delete "$INSTDIR\${APP_NAME}.ico"
  Delete "$INSTDIR\launch.cmd"
  Delete "$INSTDIR\setup-postgres.cmd"
  Delete "$INSTDIR\.env"
  Delete "$INSTDIR\uninstall.exe"
  RMDir "$INSTDIR"

  ; Somente servidor: pergunta se deseja remover dados (banco, logs, PostgreSQL)
  ; Viewer NUNCA deve ter esta opcao — é instalado em muitas maquinas de usuarios
  StrCmp $0 "viewer" skip_removedata
    MessageBox MB_YESNO|MB_ICONQUESTION "Deseja remover TODOS os dados (banco de dados, logs, PostgreSQL)?" /SD IDNO IDYES removedata IDNO skip_removedata
    removedata:
      RMDir /r "C:\gem-exportador"
  skip_removedata:

  ; Viewer: remove apenas logs locais (nao banco, nao PostgreSQL)
  StrCmp $0 "viewer" 0 skip_viewer_logs
    RMDir /r "C:\gem-exportador\logs"
    RMDir "C:\gem-exportador"
  skip_viewer_logs:

  ; Somente servidor: remove ODBC DSN
  StrCmp $0 "viewer" skip_odbc
    nsExec::ExecToLog 'powershell -Command "Remove-OdbcDsn -Name gem_exportador -DsnType System -Platform 32-bit -ErrorAction SilentlyContinue"'
    nsExec::ExecToLog 'powershell -Command "Remove-OdbcDsn -Name gem_exportador -DsnType System -Platform 64-bit -ErrorAction SilentlyContinue"'
  skip_odbc:

  ; Remove shortcuts
  Delete "$DESKTOP\${APP_NAME}.lnk"
  RMDir /r "$SMPROGRAMS\${APP_NAME}"

  ; Remove registro
  DeleteRegKey HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\${APP_NAME}"
  DeleteRegKey HKLM "Software\${APP_NAME}"

  uninstall_done:
SectionEnd

; --- Funcao para lancar o app ---
Function LaunchApp
  SetOutPath "$INSTDIR"
  Exec '"$INSTDIR\launch.cmd"'
FunctionEnd
