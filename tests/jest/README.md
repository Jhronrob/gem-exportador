# Testes unitários Jest — lógica de fila

Valida as regras de negócio do servidor que detectam e resolvem itens presos em
`processando`/`pendente` com todos os formatos já gerados (badges 100% verdes).

## Como rodar

```bash
cd tests/jest
npm ci
npm test
```

Para modo verbose:

```bash
npm run test:verbose
```

## O que é testado

As funções em `completionRules.mjs` espelham a lógica Kotlin de três lugares:

| Função JS | Trecho Kotlin correspondente |
|-----------|------------------------------|
| `todosFormatosGerados` | `Application.kt` startup guard + `ProcessingQueue.watchdogLoop` |
| `formatosFaltando` | `Application.kt` startup guard — decide `concluido` vs `pendente` |
| `formatosParaEnfileirarSemOverride` | `ProcessingQueue.add()` quando `formatosOverride == null` |
| `formatosComOverride` | `ProcessingQueue.add()` quando `formatosOverride != null` (retry) |
| `formatPriority` / `sortFormatosPorPrioridade` | `ProcessingQueue.FORMAT_ORDER` + `formatPriority()` |

## Bug que os testes documentam

Itens com `arquivosProcessados` completo ficavam presos em `processando`/`pendente`
porque o startup guard e `queue.add` não checavam esse campo antes de re-enfileirar.
Os testes marcados com `[BUG]` e `[BUG-PRINCIPAL]` reproduzem o cenário problemático
e validam que a lógica corrigida os trata como `concluido`.

## Nota sobre cobertura

Estes testes cobrem a **especificação das regras de negócio** em JavaScript puro,
sem banco de dados ou rede. Não testam a integração com o DAO Kotlin nem o broadcast
WebSocket. Para testes de integração end-to-end, é necessário iniciar o servidor em
`GEM_MODE=dev` e usar os endpoints `/api/dev/*`.
