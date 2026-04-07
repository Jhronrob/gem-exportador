/**
 * completionRules.mjs
 *
 * Funções puras que espelham a lógica de negócio do servidor Kotlin
 * para determinar quais formatos estão gerados, quais faltam e como
 * ordenar a fila de processamento.
 *
 * Cada função documenta o trecho Kotlin correspondente para manter
 * a especificação sincronizada entre servidor e testes.
 */

// ---------------------------------------------------------------------------
// Tabela de prioridade de formatos
// Kotlin: ProcessingQueue.FORMAT_ORDER (ProcessingQueue.kt)
// ---------------------------------------------------------------------------
const FORMAT_ORDER = {
  pdf: 1,
  dwf: 2,
  dwg: 10,
};

/**
 * Retorna a prioridade numérica de um formato.
 * Kotlin: ProcessingQueue.formatPriority(formato)
 *   FORMAT_ORDER[formato.lowercase()] ?: 50
 */
export function formatPriority(formato) {
  return FORMAT_ORDER[formato.toLowerCase()] ?? 50;
}

/**
 * Ordena uma lista de formatos pela prioridade (PDF primeiro, DWG por último).
 * Kotlin: .sortedBy { formatPriority(it) }
 */
export function sortFormatosPorPrioridade(formatos) {
  return [...formatos].sort((a, b) => formatPriority(a) - formatPriority(b));
}

// ---------------------------------------------------------------------------
// Normalização de dados vindos do banco
// ---------------------------------------------------------------------------

/**
 * Normaliza a lista de formatos solicitados: se vazia, retorna ["pdf"].
 * Kotlin: desenho.formatosSolicitados.ifEmpty { listOf("pdf") }
 *   Aplicado em Application.kt (startup guard) e ProcessingQueue.add()
 */
export function normalizeSolicitados(formatos) {
  if (!Array.isArray(formatos) || formatos.length === 0) return ["pdf"];
  return formatos.map((f) => f.trim().toLowerCase()).filter((f) => f.length > 0);
}

/**
 * Constrói o Set de tipos já gerados a partir de arquivosProcessados.
 * Kotlin: desenho.arquivosProcessados.map { it.tipo.lowercase() }.toSet()
 *   Aplicado em Application.kt, ProcessingQueue.add(), watchdogLoop()
 *
 * @param {Array<{tipo: string}>} arquivosProcessados
 * @returns {Set<string>}
 */
export function jaGeradosSet(arquivosProcessados) {
  if (!Array.isArray(arquivosProcessados)) return new Set();
  return new Set(arquivosProcessados.map((a) => a.tipo.toLowerCase()));
}

// ---------------------------------------------------------------------------
// Regras de conclusão (startup guard + watchdog)
// ---------------------------------------------------------------------------

/**
 * Verifica se TODOS os formatos solicitados já foram gerados.
 *
 * Kotlin (watchdogLoop / startup guard):
 *   solicitados.all { it.lowercase() in jaGerados }
 *
 * @param {string[]} solicitados  - lista normalizada (resultado de normalizeSolicitados)
 * @param {Set<string>} jaGerados - set lowercase dos tipos já gerados
 * @returns {boolean}
 */
export function todosFormatosGerados(solicitados, jaGerados) {
  return solicitados.every((f) => jaGerados.has(f.toLowerCase()));
}

/**
 * Retorna os formatos que ainda faltam ser gerados.
 *
 * Kotlin (startup guard em Application.kt):
 *   val faltando = solicitados.filter { it.lowercase() !in jaGerados }
 *   if (faltando.isEmpty()) → concluido  else → pendente
 *
 * @param {string[]} solicitados
 * @param {Set<string>} jaGerados
 * @returns {string[]}
 */
export function formatosFaltando(solicitados, jaGerados) {
  return solicitados.filter((f) => !jaGerados.has(f.toLowerCase()));
}

// ---------------------------------------------------------------------------
// Regra de enfileiramento sem override (startup / realtime INSERT)
// ---------------------------------------------------------------------------

/**
 * Retorna os formatos a enfileirar quando NÃO há override explícito.
 * Filtra os que já estão em jaGerados e ordena por prioridade.
 *
 * Kotlin (ProcessingQueue.add, quando formatosOverride == null):
 *   val jaGerados = if (formatosOverride == null) {
 *       desenho.arquivosProcessados.map { it.tipo.lowercase() }.toSet()
 *   } else emptySet()
 *   val formatos = desenho.formatosSolicitados
 *       .filter { it !in jaGerados }
 *       .sortedBy { formatPriority(it) }
 *   if (formatos.isEmpty()) return  // nada a fazer
 *
 * @param {string[]} solicitados  - lista normalizada
 * @param {Set<string>} jaGerados
 * @returns {string[]}
 */
export function formatosParaEnfileirarSemOverride(solicitados, jaGerados) {
  const faltando = solicitados.filter((f) => !jaGerados.has(f.toLowerCase()));
  return sortFormatosPorPrioridade(faltando);
}

/**
 * Retorna os formatos a enfileirar quando HÁ override explícito (ex.: retry/reenviar).
 * Override não é filtrado por jaGerados — respeita exatamente a lista passada.
 *
 * Kotlin (ProcessingQueue.add, quando formatosOverride != null):
 *   val jaGerados = emptySet()  ← não filtra
 *   val formatos = formatosOverride.map { it.trim().lowercase() }
 *       .filter { it.isNotEmpty() }
 *       .sortedBy { formatPriority(it) }
 *
 * @param {string[]} overrideList - lista explícita de formatos do retry
 * @returns {string[]}
 */
export function formatosComOverride(overrideList) {
  const normalizado = overrideList
    .map((f) => f.trim().toLowerCase())
    .filter((f) => f.length > 0);
  return sortFormatosPorPrioridade(normalizado);
}
