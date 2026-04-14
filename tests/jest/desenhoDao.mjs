/**
 * desenhoDao.mjs
 *
 * Espelha em JS puro a lógica de construção de SQL dinâmico do DesenhoDao.kt,
 * especialmente a função update() e o comportamento do campo `erro`.
 *
 * Por que testar lógica SQL em JS?
 * O DesenhoDao usa SQL dinâmico construído com template strings condicionais.
 * Essa lógica é simples mas crítica — um erro aqui faz com que campos nunca
 * sejam limpos (bug do `erro` nunca sendo apagado). Testar a lógica de
 * construção do SQL em JS permite validação rápida sem precisar rodar JVM.
 *
 * Kotlin correspondente: server/src/main/kotlin/server/db/DesenhoDao.kt
 */

// ---------------------------------------------------------------------------
// Lógica de construção do SQL dinâmico para update()
// Kotlin: DesenhoDao.update(id, status, erro, clearErro, ...)
// ---------------------------------------------------------------------------

/**
 * Simula a decisão de inclusão de cada campo no SQL de update.
 *
 * Kotlin atual (com o bug):
 *   ${if (erro != null) ", erro = ?" else ""}
 *   // erro=null → campo NÃO é atualizado → valor antigo permanece
 *
 * Kotlin corrigido (com clearErro):
 *   val deveEscreverErro = erro != null || clearErro
 *   ${if (deveEscreverErro) ", erro = ?" else ""}
 *   // clearErro=true → inclui "erro = NULL" no SQL
 *
 * @param {object} params - parâmetros do update
 * @param {string|null} params.status
 * @param {number|null} params.progresso
 * @param {string|null} params.erro - valor a gravar (não-null = atualiza)
 * @param {boolean} params.clearErro - true = gravar NULL explicitamente
 * @param {string|null} params.arquivosProcessados
 * @param {boolean} params.clearPosicaoFila
 * @returns {{ sql: string, fields: string[], erroValue: string|null }}
 */
export function buildUpdateSql(params) {
  const {
    status = null,
    progresso = null,
    erro = null,
    clearErro = false,
    arquivosProcessados = null,
    clearPosicaoFila = false,
  } = params;

  const fields = ["horario_atualizacao = ?", "atualizado_em = ?"];

  if (status !== null) fields.push("status = ?");
  if (progresso !== null) fields.push("progresso = ?");
  if (arquivosProcessados !== null) fields.push("arquivos_processados = ?");

  // Lógica corrigida: null explícito OU clearErro incluem o campo no SQL
  const deveEscreverErro = erro !== null || clearErro;
  if (deveEscreverErro) fields.push("erro = ?");

  if (clearPosicaoFila) fields.push("posicao_fila = NULL");

  const sql = `UPDATE desenho SET ${fields.join(", ")} WHERE id = ?`;

  // Valor que seria passado para o PreparedStatement (null = SQL NULL)
  const erroValue = deveEscreverErro ? erro : undefined;

  return { sql, fields, erroValue };
}

/**
 * Simula a decisão de limpeza de erros em ProcessingQueue.
 *
 * Kotlin: ProcessingQueue.kt — final do processLoop()
 *   if (concluidos >= totalFormatos && todoProcessado) errosAnteriores.clear()
 *   desenhoDao.update(id, erro = if (errosAnteriores.isEmpty()) null else ...)
 *
 * @param {string[]} errosAnteriores
 * @param {number} concluidos - formatos concluídos com sucesso
 * @param {number} totalFormatos
 * @param {boolean} todoProcessado - fila vazia para este desenho
 * @returns {{ erroParam: string|null, clearErro: boolean }}
 */
export function calcularErroUpdate(errosAnteriores, concluidos, totalFormatos, todoProcessado) {
  const erros = [...errosAnteriores];

  // Limpa erros se todos os formatos foram concluídos
  if (concluidos >= totalFormatos && todoProcessado) {
    erros.length = 0;
  }

  return {
    erroParam: erros.length > 0 ? erros.join("; ") : null,
    clearErro: erros.length === 0, // true = deve gravar NULL no banco
  };
}

// ---------------------------------------------------------------------------
// Lógica de status final após processamento
// Kotlin: ProcessingQueue.kt — val novoStatus = when { ... }
// ---------------------------------------------------------------------------

/**
 * Determina o status final de um desenho após processar um formato.
 *
 * @param {object} p
 * @param {boolean} p.todoProcessado - não há mais formatos na fila
 * @param {string[]} p.errosAnteriores - lista de mensagens de erro
 * @param {number} p.concluidos - formatos com arquivo gerado
 * @param {number} p.totalFormatos
 * @returns {string} - "processando" | "concluido" | "concluido_com_erros" | "erro"
 */
export function calcularNovoStatus({ todoProcessado, errosAnteriores, concluidos, totalFormatos }) {
  if (!todoProcessado) return "processando";
  if (errosAnteriores.length === 0 && concluidos >= totalFormatos) return "concluido";
  if (errosAnteriores.length > 0 && concluidos > 0) return "concluido_com_erros";
  if (errosAnteriores.length > 0) return "erro";
  return concluidos > 0 ? "concluido_com_erros" : "erro";
}

// ---------------------------------------------------------------------------
// Lógica de posição na fila (race condition: diagnóstico apenas)
// Documenta o problema para fins de teste
// ---------------------------------------------------------------------------

/**
 * Simula a atribuição de posição de fila.
 *
 * O bug: count() e insert() não são atômicos. Dois requests simultâneos
 * podem obter o mesmo valor de count() e acabar com a mesma posicaoFila.
 *
 * Simula dois requests simultâneos chegando ao mesmo "count":
 *
 * @param {number} countAtual - valor retornado por countPendentesEProcessando()
 * @param {number} simultaneos - quantos requests chegaram com o mesmo count
 * @returns {number[]} - posições atribuídas (com duplicatas = bug ativo)
 */
export function simularRaceConditionPosicao(countAtual, simultaneos) {
  // Sem lock correto: todos veem o mesmo count
  return Array(simultaneos).fill(countAtual + 1);
}

/**
 * Simula a atribuição correta via sequência atômica (solução).
 * Com uma sequência PostgreSQL (nextval), cada chamada retorna um valor único.
 *
 * @param {number} inicio - valor inicial da sequência
 * @param {number} count - quantos IDs gerar
 * @returns {number[]} - posições únicas e crescentes
 */
export function simularSequenciaAtomicaPosicao(inicio, count) {
  return Array.from({ length: count }, (_, i) => inicio + i);
}
