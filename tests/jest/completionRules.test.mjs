/**
 * completionRules.test.mjs
 *
 * Valida que a lógica de detecção de "todos os formatos gerados" e de
 * enfileiramento sem override corresponde exatamente ao código Kotlin do servidor.
 *
 * Bug original: itens com badges 100% verdes permaneciam presos em
 * "processando" ou "pendente" porque:
 *   1. O startup guard re-enfileirava todos os formatos sem checar arquivosProcessados.
 *   2. queue.add sem override não filtrava formatos já concluídos.
 *   3. O watchdog não existia — itens presos ficavam assim até restart.
 */

import {
  formatPriority,
  sortFormatosPorPrioridade,
  normalizeSolicitados,
  jaGeradosSet,
  todosFormatosGerados,
  formatosFaltando,
  formatosParaEnfileirarSemOverride,
  formatosComOverride,
} from "./completionRules.mjs";

// ---------------------------------------------------------------------------
// formatPriority
// ---------------------------------------------------------------------------
describe("formatPriority", () => {
  test("pdf tem prioridade 1 (mais alto)", () => {
    expect(formatPriority("pdf")).toBe(1);
  });

  test("dwf tem prioridade 2", () => {
    expect(formatPriority("dwf")).toBe(2);
  });

  test("dwg tem prioridade 10 (mais baixo dos três)", () => {
    expect(formatPriority("dwg")).toBe(10);
  });

  test("formato desconhecido tem prioridade 50 (fallback)", () => {
    expect(formatPriority("step")).toBe(50);
    expect(formatPriority("")).toBe(50);
  });

  test("case insensitive: PDF = pdf = Pdf", () => {
    expect(formatPriority("PDF")).toBe(formatPriority("pdf"));
    expect(formatPriority("DWG")).toBe(formatPriority("dwg"));
  });
});

// ---------------------------------------------------------------------------
// sortFormatosPorPrioridade
// ---------------------------------------------------------------------------
describe("sortFormatosPorPrioridade", () => {
  test("ordena corretamente: pdf antes de dwf antes de dwg", () => {
    expect(sortFormatosPorPrioridade(["dwg", "pdf", "dwf"])).toEqual([
      "pdf",
      "dwf",
      "dwg",
    ]);
  });

  test("não altera lista já ordenada", () => {
    expect(sortFormatosPorPrioridade(["pdf", "dwf", "dwg"])).toEqual([
      "pdf",
      "dwf",
      "dwg",
    ]);
  });

  test("lista com um único item", () => {
    expect(sortFormatosPorPrioridade(["dwg"])).toEqual(["dwg"]);
  });

  test("lista vazia retorna vazia", () => {
    expect(sortFormatosPorPrioridade([])).toEqual([]);
  });

  test("não muta o array original", () => {
    const original = ["dwg", "pdf"];
    sortFormatosPorPrioridade(original);
    expect(original).toEqual(["dwg", "pdf"]);
  });
});

// ---------------------------------------------------------------------------
// normalizeSolicitados
// ---------------------------------------------------------------------------
describe("normalizeSolicitados", () => {
  test("lista vazia retorna ['pdf'] (padrão Kotlin)", () => {
    expect(normalizeSolicitados([])).toEqual(["pdf"]);
  });

  test("null / undefined retorna ['pdf']", () => {
    expect(normalizeSolicitados(null)).toEqual(["pdf"]);
    expect(normalizeSolicitados(undefined)).toEqual(["pdf"]);
  });

  test("normaliza para lowercase e remove espaços", () => {
    expect(normalizeSolicitados(["PDF", " DWF ", "DWG"])).toEqual([
      "pdf",
      "dwf",
      "dwg",
    ]);
  });

  test("remove entradas vazias após trim", () => {
    expect(normalizeSolicitados(["pdf", "  ", "dwg"])).toEqual(["pdf", "dwg"]);
  });
});

// ---------------------------------------------------------------------------
// jaGeradosSet
// ---------------------------------------------------------------------------
describe("jaGeradosSet", () => {
  test("extrai tipos lowercase como Set", () => {
    const arquivos = [
      { tipo: "PDF", nome: "a.pdf", caminho: "/x", tamanho: 100 },
      { tipo: "DWF", nome: "a.dwf", caminho: "/x", tamanho: 200 },
    ];
    const s = jaGeradosSet(arquivos);
    expect(s.has("pdf")).toBe(true);
    expect(s.has("dwf")).toBe(true);
    expect(s.has("dwg")).toBe(false);
  });

  test("lista vazia retorna Set vazio", () => {
    expect(jaGeradosSet([])).toEqual(new Set());
  });

  test("null / undefined retorna Set vazio", () => {
    expect(jaGeradosSet(null)).toEqual(new Set());
    expect(jaGeradosSet(undefined)).toEqual(new Set());
  });
});

// ---------------------------------------------------------------------------
// todosFormatosGerados — regra central do watchdog e startup guard
// ---------------------------------------------------------------------------
describe("todosFormatosGerados", () => {
  // Cenário do bug: item preso com todos os arquivos presentes
  test("[BUG] item preso: solicitados pdf+dwf, gerados pdf+dwf → deve ser concluido", () => {
    const solicitados = ["pdf", "dwf"];
    const jaGerados = new Set(["pdf", "dwf"]);
    expect(todosFormatosGerados(solicitados, jaGerados)).toBe(true);
  });

  test("[BUG] item preso: case misto no banco — PDF e DWF como uppercase", () => {
    const solicitados = normalizeSolicitados(["pdf", "dwf"]);
    const jaGerados = jaGeradosSet([
      { tipo: "PDF" },
      { tipo: "DWF" },
    ]);
    expect(todosFormatosGerados(solicitados, jaGerados)).toBe(true);
  });

  test("[BUG] item preso: todos os três formatos gerados", () => {
    const solicitados = normalizeSolicitados(["pdf", "dwf", "dwg"]);
    const jaGerados = jaGeradosSet([
      { tipo: "pdf" },
      { tipo: "dwf" },
      { tipo: "dwg" },
    ]);
    expect(todosFormatosGerados(solicitados, jaGerados)).toBe(true);
  });

  test("incompleto: gerado só pdf, falta dwf", () => {
    const solicitados = ["pdf", "dwf"];
    const jaGerados = new Set(["pdf"]);
    expect(todosFormatosGerados(solicitados, jaGerados)).toBe(false);
  });

  test("incompleto: nenhum formato gerado ainda", () => {
    const solicitados = ["pdf", "dwf", "dwg"];
    const jaGerados = new Set();
    expect(todosFormatosGerados(solicitados, jaGerados)).toBe(false);
  });

  test("solicitados vazio normalizado para pdf: se pdf gerado → completo", () => {
    const solicitados = normalizeSolicitados([]);
    const jaGerados = new Set(["pdf"]);
    expect(todosFormatosGerados(solicitados, jaGerados)).toBe(true);
  });

  test("solicitados vazio normalizado para pdf: sem nenhum gerado → incompleto", () => {
    const solicitados = normalizeSolicitados([]);
    const jaGerados = new Set();
    expect(todosFormatosGerados(solicitados, jaGerados)).toBe(false);
  });
});

// ---------------------------------------------------------------------------
// formatosFaltando — startup guard: decide concluido vs pendente
// ---------------------------------------------------------------------------
describe("formatosFaltando", () => {
  test("[BUG] todos gerados → lista vazia → startup guard marca concluido", () => {
    const solicitados = ["pdf", "dwf"];
    const jaGerados = new Set(["pdf", "dwf"]);
    expect(formatosFaltando(solicitados, jaGerados)).toEqual([]);
  });

  test("parcialmente gerado → retorna apenas os que faltam", () => {
    const solicitados = ["pdf", "dwf", "dwg"];
    const jaGerados = new Set(["pdf"]);
    expect(formatosFaltando(solicitados, jaGerados)).toEqual(["dwf", "dwg"]);
  });

  test("nenhum gerado → retorna todos", () => {
    const solicitados = ["pdf", "dwf"];
    const jaGerados = new Set();
    expect(formatosFaltando(solicitados, jaGerados)).toEqual(["pdf", "dwf"]);
  });

  test("case insensitive: gerado como DWF deve remover dwf dos faltantes", () => {
    const solicitados = normalizeSolicitados(["pdf", "dwf"]);
    const jaGerados = jaGeradosSet([{ tipo: "DWF" }]);
    const faltando = formatosFaltando(solicitados, jaGerados);
    expect(faltando).toEqual(["pdf"]);
    expect(faltando).not.toContain("dwf");
  });
});

// ---------------------------------------------------------------------------
// formatosParaEnfileirarSemOverride — queue.add sem override
// ---------------------------------------------------------------------------
describe("formatosParaEnfileirarSemOverride", () => {
  // Cenário do bug: startup chama queue.add sem override; devia filtrar já gerados
  test("[BUG] sem override: todos gerados → lista vazia (sem re-enfileirar)", () => {
    const solicitados = normalizeSolicitados(["pdf", "dwf"]);
    const jaGerados = jaGeradosSet([{ tipo: "pdf" }, { tipo: "dwf" }]);
    const paraEnfileirar = formatosParaEnfileirarSemOverride(solicitados, jaGerados);
    expect(paraEnfileirar).toEqual([]);
  });

  test("[BUG] sem override: parcialmente gerado → só enfileira os faltantes", () => {
    const solicitados = normalizeSolicitados(["pdf", "dwf", "dwg"]);
    const jaGerados = jaGeradosSet([{ tipo: "pdf" }]);
    const paraEnfileirar = formatosParaEnfileirarSemOverride(solicitados, jaGerados);
    // Deve conter apenas dwf e dwg, em ordem de prioridade
    expect(paraEnfileirar).toEqual(["dwf", "dwg"]);
  });

  test("sem override: nenhum gerado → enfileira todos em ordem", () => {
    const solicitados = normalizeSolicitados(["dwg", "pdf", "dwf"]);
    const jaGerados = jaGeradosSet([]);
    const paraEnfileirar = formatosParaEnfileirarSemOverride(solicitados, jaGerados);
    expect(paraEnfileirar).toEqual(["pdf", "dwf", "dwg"]);
  });

  test("sem override: retorno já ordenado por prioridade (DWG por último)", () => {
    const solicitados = normalizeSolicitados(["dwg", "dwf"]);
    const jaGerados = new Set();
    const paraEnfileirar = formatosParaEnfileirarSemOverride(solicitados, jaGerados);
    expect(paraEnfileirar.indexOf("dwf")).toBeLessThan(
      paraEnfileirar.indexOf("dwg")
    );
  });
});

// ---------------------------------------------------------------------------
// formatosComOverride — retry/reenviar: não filtra jaGerados
// ---------------------------------------------------------------------------
describe("formatosComOverride (retry / reenviar)", () => {
  test("override explícito ignora jaGerados — enfileira tudo que foi pedido", () => {
    // Mesmo que pdf já esteja em arquivosProcessados, o retry decide os formatos
    const override = ["pdf", "dwg"];
    const resultado = formatosComOverride(override);
    // Deve conter pdf e dwg (não filtrado)
    expect(resultado).toContain("pdf");
    expect(resultado).toContain("dwg");
  });

  test("override normaliza case e espaços", () => {
    expect(formatosComOverride(["PDF", " DWF "])).toEqual(["pdf", "dwf"]);
  });

  test("override ordena por prioridade (pdf antes de dwg)", () => {
    const resultado = formatosComOverride(["dwg", "pdf"]);
    expect(resultado).toEqual(["pdf", "dwg"]);
  });

  test("override remove entradas vazias", () => {
    expect(formatosComOverride(["pdf", ""])).toEqual(["pdf"]);
  });
});

// ---------------------------------------------------------------------------
// Cenários integrados — fluxo completo do startup guard e watchdog
// ---------------------------------------------------------------------------
describe("Cenário integrado: startup guard e watchdog", () => {
  /**
   * Reproduz o bug original:
   *   - Desenho com formatosSolicitados: ["pdf", "dwf"]
   *   - arquivosProcessados já tem pdf e dwf
   *   - status ainda era "processando" (crash antes do update final)
   *
   * A correção: startup guard e watchdog devem marcar como "concluido"
   * sem re-enfileirar nada.
   */
  test("[BUG-PRINCIPAL] item processando com todos formatos já gerados → deve ir para concluido", () => {
    const desenho = {
      status: "processando",
      formatosSolicitados: ["pdf", "dwf"],
      arquivosProcessados: [
        { tipo: "pdf", nome: "a.pdf", caminho: "/out/a.pdf", tamanho: 1000 },
        { tipo: "dwf", nome: "a.dwf", caminho: "/out/a.dwf", tamanho: 2000 },
      ],
    };

    const solicitados = normalizeSolicitados(desenho.formatosSolicitados);
    const jaGerados = jaGeradosSet(desenho.arquivosProcessados);
    const faltando = formatosFaltando(solicitados, jaGerados);
    const paraEnfileirar = formatosParaEnfileirarSemOverride(solicitados, jaGerados);

    // Startup guard: se faltando está vazio → marcar concluido
    expect(faltando).toEqual([]);
    // queue.add sem override: não enfileira nada
    expect(paraEnfileirar).toEqual([]);
    // Watchdog: todos gerados → item preso deve ser resolvido
    expect(todosFormatosGerados(solicitados, jaGerados)).toBe(true);
  });

  test("[BUG-PRINCIPAL] item pendente com todos formatos já gerados → mesma lógica", () => {
    const desenho = {
      status: "pendente",
      formatosSolicitados: ["pdf", "dwf", "dwg"],
      arquivosProcessados: [
        { tipo: "PDF" },
        { tipo: "DWF" },
        { tipo: "DWG" },
      ],
    };

    const solicitados = normalizeSolicitados(desenho.formatosSolicitados);
    const jaGerados = jaGeradosSet(desenho.arquivosProcessados);

    expect(todosFormatosGerados(solicitados, jaGerados)).toBe(true);
    expect(formatosFaltando(solicitados, jaGerados)).toEqual([]);
    expect(formatosParaEnfileirarSemOverride(solicitados, jaGerados)).toEqual([]);
  });

  test("item legítimo incompleto: somente pdf gerado, falta dwf → não marca concluido", () => {
    const desenho = {
      status: "processando",
      formatosSolicitados: ["pdf", "dwf"],
      arquivosProcessados: [
        { tipo: "pdf", nome: "a.pdf", caminho: "/out/a.pdf", tamanho: 1000 },
      ],
    };

    const solicitados = normalizeSolicitados(desenho.formatosSolicitados);
    const jaGerados = jaGeradosSet(desenho.arquivosProcessados);

    expect(todosFormatosGerados(solicitados, jaGerados)).toBe(false);
    expect(formatosFaltando(solicitados, jaGerados)).toEqual(["dwf"]);
    expect(formatosParaEnfileirarSemOverride(solicitados, jaGerados)).toEqual(["dwf"]);
  });

  test("reenviar (retry) após erro: override força reprocessamento mesmo com arquivos presentes", () => {
    // Cenário: falso negativo — arquivo existe no disco mas status é "erro"
    // O retry recebe override com todos os formatos solicitados
    const formatosSolicitados = ["pdf", "dwg"];
    const override = formatosSolicitados; // reenviar todos

    const resultado = formatosComOverride(override);

    // override não filtra por jaGerados — processa tudo que foi pedido
    expect(resultado).toEqual(["pdf", "dwg"]);
  });
});
