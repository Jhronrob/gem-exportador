# GEM Exportador

Sistema automatizado de exportação de desenhos Autodesk Inventor para PDF, DWF e DWG.

## Arquitetura

```
┌──────────────────┐     ┌──────────────────┐     ┌───────────────────────┐
│  Desktop App     │────>│  Servidor Ktor   │────>│  Autodesk Inventor    │
│  (Compose/KMP)   │ WS  │  (PostgreSQL)    │ VBS │  (MacroServidor.bas)  │
│  UI do usuário   │<────│  Fila + API      │<────│  Processa exportações │
└──────────────────┘     └──────────────────┘     └───────────────────────┘
```

### Componentes

| Componente | Tecnologia | Função |
|---|---|---|
| **Desktop App** | Kotlin Multiplatform + Compose | Interface do usuário (tabela de desenhos, status, ações) |
| **Servidor** | Ktor + PostgreSQL | API REST, fila de processamento, WebSocket em tempo real |
| **VBS Bridge** | `processar-inventor.vbs` | Ponte entre servidor e Inventor (escrita de comandos via arquivo) |
| **Macro Inventor** | `MacroServidor.bas` (VBA) | Roda dentro do Inventor, lê comandos, exporta arquivos |

## Fluxo de Processamento

```
1. Usuário envia desenho via macro no Inventor do seu PC
   └─> INSERT no PostgreSQL (status: pendente)

2. PostgreSQL dispara NOTIFY no canal 'desenho_changes'
   └─> Servidor recebe via LISTEN, adiciona à fila

3. Servidor processa cada formato solicitado (pdf, dwf, dwg):
   └─> Executa: cscript processar-inventor.vbs <entrada> <saida> <formato>
       └─> VBS escreve comando.txt na pasta de controle
       └─> MacroServidor.bas (loop no Inventor) lê comando.txt
           └─> Abre o arquivo .idw no Inventor
           └─> Exporta para o formato solicitado
           └─> Grava sucesso.txt ou erro.txt
       └─> VBS detecta resultado e retorna código de saída

4. Servidor atualiza status no PostgreSQL
   └─> NOTIFY dispara → Desktop App atualiza via WebSocket
```

## Comunicação VBS ↔ Macro Inventor

A comunicação entre `processar-inventor.vbs` e `MacroServidor.bas` é feita via **arquivos de controle**:

| Arquivo | Direção | Conteúdo |
|---|---|---|
| `comando.txt` | VBS → Macro | `<arquivo_entrada>\|<arquivo_saida>\|<formato>` |
| `sucesso.txt` | Macro → VBS | Caminho do arquivo gerado |
| `erro.txt` | Macro → VBS | Mensagem de erro |
| `macro_recebeu.txt` | Macro → VBS | Confirmação de recebimento |

A pasta de controle é configurada via `INVENTOR_PASTA_CONTROLE` no `.env` do servidor ou detectada automaticamente.

## Exportação de Formatos

### PDF (funciona)
- **AddIn**: `{0AC6FD96-2F4D-42CE-8BE0-8AEA580399E4}`
- Usa `TranslatorAddIn.SaveCopyAs` com opções padrão via `HasSaveCopyAsOptions`
- Não requer arquivo `.ini`

### DWF (funciona)
- **AddIn**: `{0AC6FD95-2F4D-42CE-8BE0-8AEA580399E4}`
- Usa `TranslatorAddIn.SaveCopyAs` com opções padrão
- Não requer arquivo `.ini`

### DWG (3 métodos em cascata)
- **Método 1**: `Document.SaveAs` nativo do Inventor — salva IDW como DWG (formato Inventor DWG, compatível com AutoCAD). Salva localmente em `%TEMP%` e copia para a rede.
- **Método 2**: `TranslatorAddIn.SaveCopyAs` com arquivo `.ini` — busca config em `C:\Users\Public\Documents\Autodesk\Inventor\*\Design Data\DWG-DXF\*.ini`. Requer ini válido.
- **Método 3**: `TranslatorAddIn.ShowSaveCopyAsOptions` silencioso — tenta auto-aceitar as opções padrão com `SilentOperation=True`.

Logs detalhados em `C:\gem-exportador\logs\gem-dwg-debug.log`.

## Banco de Dados

**PostgreSQL** — banco `gem_exportador`, tabela `desenho`.

### Campos Principais

| Campo | Tipo | Descrição |
|---|---|---|
| `id` | TEXT (UUID) | Identificador único |
| `nome_arquivo` | TEXT | Ex: `270000044_05.idw` |
| `computador` | TEXT | Máquina que enviou |
| `caminho_destino` | TEXT | Pasta de rede destino |
| `status` | TEXT | `pendente` → `processando` → `concluido` / `concluido_com_erros` / `erro` / `cancelado` |
| `formatos_solicitados` | TEXT | JSON array: `["pdf","dwf","dwg"]` |
| `arquivo_original` | TEXT | Caminho completo do .idw/.iam/.ipt |
| `progresso` | INTEGER | 0-100 (baseado em formatos concluídos / total) |
| `arquivos_processados` | TEXT | JSON array com nome, tipo, caminho e tamanho |

### Tempo Real
Trigger `notify_desenho_changes()` dispara `pg_notify('desenho_changes', ...)` em INSERT/UPDATE/DELETE. O servidor escuta via `LISTEN desenho_changes` com conexão dedicada.

## Caminhos de Rede

| Caminho | Conteúdo |
|---|---|
| `\\192.168.1.152\Arquivos$\DESENHOS GERENCIADOR 3D\{num}\` | Arquivos fonte (.idw, .iam, .ipt) |
| `\\192.168.1.152\Arquivos$\DESENHOS GERENCIADOR\{num}\` | Arquivos exportados (.pdf, .dwf, .dwg) |

## Progresso procedural
- 0% — Na fila
- 5% — VBScript iniciando
- 10% — VBScript rodando
- 20% — Inventor abrindo documento (estimado por tempo)
- 35% — SaveCopyAs iniciado (estimado)
- 40-88% — Aguardando arquivo no disco (incremento gradual com curva logarítmica)
- 92% — Arquivo encontrado na pasta de destino
- 100% — Concluído

## Desktop App

- **Kotlin Multiplatform** + **Compose Desktop**
- Dois modos (definidos no .env ou nas Configurações):
  - **Host**: app com servidor embutido (Inventor + banco na mesma máquina ou acessível). Processa a fila e exporta.
  - **Viewer**: só visualiza; conecta ao endereço do App Host (IP:porta). Para estações que só acompanham a fila.
- Configuração é salva em `%APPDATA%\gem-exportador` (Viewer e Host), assim **atualizações in-app não apagam IP, banco, etc.**
- Conecta ao servidor via **WebSocket** para atualizações em tempo real
- Tabela mostra últimos 7 dias + pendentes/processando (sempre visíveis)
- Ações: Reenviar, Cancelar, Deletar
- **F5** = Refresh com indicador visual
- Filtro por data (Range Picker) e busca por texto

## Servidor

- **Ktor** rodando em `127.0.0.1:8080`
- Fila de processamento sequencial (um formato por vez)
- Timeout de 25 minutos por formato
- Reconexão automática do listener PostgreSQL

## Configuração do Inventor (Servidor)

1. Abrir Autodesk Inventor no servidor (ex: `SRVMTGEM1`)
2. Importar `MacroServidor.bas` para o projeto VBA do Inventor
3. Executar macro `IniciarServicoSilencioso` ou `IniciarServico`
4. O macro fica em loop lendo `comando.txt` a cada 500ms

## Build e Release

- GitHub Actions em `.github/workflows/release.yml`
- Trigger: push de tag `v*` (ex: `git tag v1.3.1 && git push origin v1.3.1`)
- Gera instalador MSI/EXE para distribuição
