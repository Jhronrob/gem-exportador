/**
 * api.integration.test.mjs
 *
 * Testes de integração HTTP contra o servidor gem-exportador rodando localmente.
 * Valida comportamento end-to-end que a lógica pura em desenhoDao.test.mjs
 * não consegue cobrir, incluindo o novo fluxo de regeneração de concluídos.
 *
 * Pré-requisito:
 *   O servidor deve estar rodando antes de executar estes testes.
 *   Exemplo no modo dev:
 *     ./gradlew :server:run -PgemEnvFile=.env.dev
 */

import { mkdtemp, rm, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";

const SERVER_URL = process.env.SERVER_URL || "http://localhost:8080";
const TIMEOUT_MS = 60_000;
const tempDirs = new Set();

async function serverAtivo() {
  try {
    const res = await fetch(`${SERVER_URL}/api/health`, {
      signal: AbortSignal.timeout(3000),
    });
    return res.ok;
  } catch {
    return false;
  }
}

async function criarArquivoOriginalTemporario(nomeArquivo = "teste-unitario.idw") {
  const dir = await mkdtemp(path.join(os.tmpdir(), "gem-exportador-jest-"));
  tempDirs.add(dir);
  const arquivoOriginal = path.join(dir, nomeArquivo);
  await writeFile(arquivoOriginal, "[JEST] desenho temporario para integracao");
  return arquivoOriginal;
}

async function criarDesenho(overrides = {}) {
  const nomeArquivo = overrides.nomeArquivo || "teste-unitario.idw";
  const arquivoOriginal = overrides.arquivoOriginal || await criarArquivoOriginalTemporario(nomeArquivo);
  const body = {
    nomeArquivo,
    computador: "PC-JEST-TEST",
    caminhoDestino: "C:\\\\desenhos gerenciador 3d\\\\jest",
    arquivoOriginal,
    formatos: ["pdf"],
    ...overrides,
  };
  const res = await fetch(`${SERVER_URL}/api/desenhos/queue`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  return { res, body: await res.json() };
}

async function getDesenho(id) {
  const res = await fetch(`${SERVER_URL}/api/desenhos/${id}`);
  if (!res.ok) return null;
  return res.json();
}

function arquivosProcessadosOf(desenho) {
  if (!desenho) return [];
  if (Array.isArray(desenho.arquivosProcessados)) return desenho.arquivosProcessados;
  if (typeof desenho.arquivos_processados === "string" && desenho.arquivos_processados.trim() !== "") {
    try {
      return JSON.parse(desenho.arquivos_processados);
    } catch {
      return [];
    }
  }
  return [];
}

async function aguardarStatus(id, statusEsperado, timeoutMs = TIMEOUT_MS) {
  const inicio = Date.now();
  while (Date.now() - inicio < timeoutMs) {
    const d = await getDesenho(id);
    if (!d) return null;
    if (d.status === statusEsperado) return d;
    await new Promise((r) => setTimeout(r, 1000));
  }
  return await getDesenho(id);
}

async function deletarDesenho(id) {
  await fetch(`${SERVER_URL}/api/desenhos/${id}`, { method: "DELETE" });
}

let skipAll = false;

beforeAll(async () => {
  const ativo = await serverAtivo();
  if (!ativo) {
    console.warn(
      `\n[SKIP] Servidor não acessível em ${SERVER_URL}.\n` +
      `Execute "./gradlew :server:run -PgemEnvFile=.env.dev" antes de rodar os testes de integração.\n`
    );
    skipAll = true;
  }
}, 5000);

afterEach(async () => {
  for (const dir of tempDirs) {
    await rm(dir, { recursive: true, force: true });
  }
  tempDirs.clear();
});

const it = (name, fn, timeout) => {
  global.it(name, async () => {
    if (skipAll) return;
    await fn();
  }, timeout);
};

describe("POST /api/desenhos/queue - criação de desenho", () => {
  let idCriado;

  afterAll(async () => {
    if (idCriado) await deletarDesenho(idCriado);
  });

  it("cria um desenho e retorna id, status e posicaoFila", async () => {
    const { res, body } = await criarDesenho();
    expect(res.status).toBe(201);
    expect(body).toHaveProperty("id");
    expect(body).toHaveProperty("status", "pendente");
    expect(body).toHaveProperty("posicaoFila");
    expect(typeof body.posicaoFila).toBe("number");
    idCriado = body.id;
  });

  it("rejeita request sem nomeArquivo", async () => {
    const { res } = await criarDesenho({ nomeArquivo: "" });
    expect(res.status).toBe(400);
  });

  it("rejeita request sem arquivoOriginal", async () => {
    const { res } = await criarDesenho({ arquivoOriginal: "" });
    expect(res.status).toBe(400);
  });

  it("formatos inválidos são rejeitados", async () => {
    const { res } = await criarDesenho({ formatos: ["xlsx", "doc"] });
    expect(res.status).toBe(400);
  });

  it("formato misto mantém só os válidos", async () => {
    const { res, body } = await criarDesenho({ formatos: ["pdf", "step"] });
    if (res.ok) {
      expect(body.formatosSolicitados).not.toContain("step");
      if (body.id) await deletarDesenho(body.id);
    } else {
      expect(res.status).toBe(400);
    }
  });
});

describe("[BUG-1] campo erro - persistência e limpeza no banco", () => {
  it(
    "após processamento com sucesso em modo dev, campo erro deve ser null",
    async () => {
      const { res, body } = await criarDesenho({ formatos: ["pdf"] });
      expect(res.ok).toBe(true);
      const id = body.id;

      try {
        const final = await aguardarStatus(id, "concluido");
        expect(final).not.toBeNull();
        expect(final.status).toBe("concluido");
        expect(final.erro).toBeNull();
      } finally {
        await deletarDesenho(id);
      }
    },
    TIMEOUT_MS + 5000
  );

  it(
    "desenho concluido não deve ter campo erro preenchido após conclusão",
    async () => {
      const { body } = await criarDesenho();
      const id = body.id;

      try {
        await aguardarStatus(id, "concluido");
        const final = await getDesenho(id);
        if (final?.status === "concluido") {
          expect(final.erro).toBeNull();
        }
      } finally {
        await deletarDesenho(id);
      }
    },
    TIMEOUT_MS * 2 + 5000
  );
});

describe("POST /api/desenhos/{id}/regenerar", () => {
  it(
    "reenfileira um desenho concluido no mesmo registro e regenera os formatos originais",
    async () => {
      const { res, body } = await criarDesenho({ formatos: ["pdf", "dwf"] });
      expect(res.ok).toBe(true);
      const id = body.id;

      try {
        const concluido = await aguardarStatus(id, "concluido");
        expect(concluido).not.toBeNull();
        const tiposAntes = arquivosProcessadosOf(concluido).map((a) => a.tipo.toLowerCase()).sort();
        expect(tiposAntes).toEqual(["dwf", "pdf"]);

        const regenRes = await fetch(`${SERVER_URL}/api/desenhos/${id}/regenerar`, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
        });
        expect(regenRes.ok).toBe(true);
        const regenBody = await regenRes.json();
        expect(regenBody).toHaveProperty("id", id);
        expect(regenBody).toHaveProperty("status", "pendente");
        expect(regenBody).toHaveProperty("formatosRestantes");
        expect(regenBody.formatosRestantes).toEqual(["pdf", "dwf"]);

        const aposSolicitacao = await getDesenho(id);
        expect(aposSolicitacao?.id).toBe(id);
        expect(["pendente", "processando", "concluido"]).toContain(aposSolicitacao?.status);
        const tiposDuranteRegeneracao = arquivosProcessadosOf(aposSolicitacao).map((a) => a.tipo.toLowerCase()).sort();
        expect(tiposDuranteRegeneracao).toEqual(["dwf", "pdf"]);

        const final = await aguardarStatus(id, "concluido");
        expect(final).not.toBeNull();
        expect(final.id).toBe(id);
        expect(final.status).toBe("concluido");
        const tiposDepois = arquivosProcessadosOf(final).map((a) => a.tipo.toLowerCase()).sort();
        expect(tiposDepois).toEqual(["dwf", "pdf"]);
      } finally {
        await deletarDesenho(id);
      }
    },
    TIMEOUT_MS * 2 + 5000
  );

  it(
    "nao aceita duas regenerações sequenciais para o mesmo concluido",
    async () => {
      const { body } = await criarDesenho({ formatos: ["pdf"] });
      const id = body.id;

      try {
        const concluido = await aguardarStatus(id, "concluido");
        expect(concluido).not.toBeNull();
        expect(concluido.status).toBe("concluido");

        const primeira = await fetch(`${SERVER_URL}/api/desenhos/${id}/regenerar`, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
        });
        expect(primeira.ok).toBe(true);

        const segunda = await fetch(`${SERVER_URL}/api/desenhos/${id}/regenerar`, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
        });
        expect([400, 409]).toContain(segunda.status);
      } finally {
        await deletarDesenho(id);
      }
    },
    TIMEOUT_MS * 2 + 5000
  );

  it("rejeita regeneração para desenho que ainda nao concluiu", async () => {
    const { body } = await criarDesenho({ formatos: ["pdf"] });
    const id = body.id;

    try {
      const regenRes = await fetch(`${SERVER_URL}/api/desenhos/${id}/regenerar`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
      });
      expect(regenRes.status).toBe(400);
    } finally {
      await deletarDesenho(id);
    }
  });
});

describe("progressão de status durante processamento", () => {
  it("status começa em 'pendente' ou 'processando' logo após criação", async () => {
    const { body } = await criarDesenho();
    const id = body.id;

    try {
      const d = await getDesenho(id);
      expect(["pendente", "processando"]).toContain(d.status);
      expect(d.progresso).toBeGreaterThanOrEqual(0);
      expect(d.progresso).toBeLessThanOrEqual(100);
    } finally {
      await deletarDesenho(id);
    }
  });

  it(
    "progresso chega a 100 quando status é 'concluido'",
    async () => {
      const { body } = await criarDesenho();
      const id = body.id;

      try {
        const final = await aguardarStatus(id, "concluido");
        expect(final?.progresso).toBe(100);
      } finally {
        await deletarDesenho(id);
      }
    },
    TIMEOUT_MS + 5000
  );

  it(
    "arquivosProcessados contém o formato solicitado após conclusão",
    async () => {
      const { body } = await criarDesenho({ formatos: ["pdf"] });
      const id = body.id;

      try {
        const final = await aguardarStatus(id, "concluido");
        const tipos = arquivosProcessadosOf(final).map((a) => a.tipo.toLowerCase());
        expect(tipos).toContain("pdf");
      } finally {
        await deletarDesenho(id);
      }
    },
    TIMEOUT_MS + 5000
  );
});

describe("GET /api/queue", () => {
  it("retorna estrutura de status da fila", async () => {
    const res = await fetch(`${SERVER_URL}/api/queue`);
    expect(res.ok).toBe(true);
    const body = await res.json();
    expect(body).toHaveProperty("tamanho");
    expect(body).toHaveProperty("processando");
    expect(typeof body.tamanho).toBe("number");
    expect(typeof body.processando).toBe("boolean");
  });

  it("tamanho da fila é >= 0", async () => {
    const res = await fetch(`${SERVER_URL}/api/queue`);
    const body = await res.json();
    expect(body.tamanho).toBeGreaterThanOrEqual(0);
  });
});

describe("GET /api/desenhos - listagem", () => {
  it("retorna estrutura paginada válida", async () => {
    const res = await fetch(`${SERVER_URL}/api/desenhos?limit=5&offset=0`);
    expect(res.ok).toBe(true);
    const body = await res.json();
    expect(body).toHaveProperty("desenhos");
    expect(body).toHaveProperty("total");
    expect(body).toHaveProperty("limit", 5);
    expect(body).toHaveProperty("offset", 0);
    expect(Array.isArray(body.desenhos)).toBe(true);
    expect(body.desenhos.length).toBeLessThanOrEqual(5);
  });

  it("filtro por status retorna apenas desenhos com aquele status", async () => {
    const res = await fetch(`${SERVER_URL}/api/desenhos?status=concluido&limit=10`);
    const body = await res.json();
    for (const d of body.desenhos) {
      expect(d.status).toBe("concluido");
    }
  });

  it("limit=0 retorna lista vazia", async () => {
    const res = await fetch(`${SERVER_URL}/api/desenhos?limit=0`);
    const body = await res.json();
    expect(body.desenhos).toHaveLength(0);
  });
});

describe("GET /api/health", () => {
  it("servidor responde 200 com indicação de saúde", async () => {
    const res = await fetch(`${SERVER_URL}/api/health`);
    expect(res.status).toBe(200);
  });
});
