package data

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import model.DesenhoAutodesk
import util.logToFile

/**
 * Cliente HTTP para as APIs do servidor.
 */
class ApiClient(private val baseUrl: String) {
    private val client = HttpClient()

    /**
     * Envia um desenho pendente para a fila do servidor.
     * Usa o endpoint /api/desenhos/queue (arquivo local, sem upload).
     */
    suspend fun enqueue(desenho: DesenhoAutodesk): Result<String> = runCatching {
        val response = client.post("$baseUrl/api/desenhos/queue") {
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "nomeArquivo": "${desenho.nomeArquivo}",
                    "computador": "${desenho.computador}",
                    "caminhoDestino": "${desenho.caminhoDestino.replace("\\", "\\\\")}",
                    "arquivoOriginal": "${desenho.arquivoOriginal?.replace("\\", "\\\\") ?: ""}",
                    "formatos": ${desenho.formatosSolicitadosJson?.ifBlank { "[\"pdf\"]" } ?: "[\"pdf\"]"}
                }
            """.trimIndent())
        }
        if (response.status.isSuccess()) {
            logToFile("INFO", "Desenho enviado para fila: ${desenho.nomeArquivo}")
            response.bodyAsText()
        } else {
            val erro = response.bodyAsText()
            logToFile("ERROR", "Falha ao enviar desenho ${desenho.nomeArquivo}: $erro")
            throw Exception("Erro ${response.status}: $erro")
        }
    }

    suspend fun retry(desenhoId: String): Result<Unit> = runCatching {
        val response = client.post("$baseUrl/api/desenhos/$desenhoId/retry") {
            contentType(ContentType.Application.Json)
        }
        if (!response.status.isSuccess()) {
            val erro = response.bodyAsText()
            logToFile("ERROR", "Retry rejeitado pelo servidor (${response.status}): $erro")
            throw Exception("Erro ${response.status}: $erro")
        }
        logToFile("INFO", "Retry aceito pelo servidor para $desenhoId")
    }

    suspend fun regenerar(desenhoId: String): Result<Unit> = runCatching {
        val response = client.post("$baseUrl/api/desenhos/$desenhoId/regenerar") {
            contentType(ContentType.Application.Json)
        }
        if (!response.status.isSuccess()) {
            val erro = response.bodyAsText()
            logToFile("ERROR", "Regeneração rejeitada pelo servidor (${response.status}): $erro")
            throw Exception("Erro ${response.status}: $erro")
        }
        logToFile("INFO", "Regeneração aceita pelo servidor para $desenhoId")
    }

    suspend fun cancelar(desenhoId: String): Result<Unit> = runCatching {
        val response = client.post("$baseUrl/api/desenhos/$desenhoId/cancelar") {
            contentType(ContentType.Application.Json)
        }
        if (!response.status.isSuccess()) {
            val erro = response.bodyAsText()
            logToFile("ERROR", "Cancelar rejeitado pelo servidor (${response.status}): $erro")
            throw Exception("Erro ${response.status}: $erro")
        }
    }

    suspend fun delete(desenhoId: String): Result<Unit> = runCatching {
        val response = client.delete("$baseUrl/api/desenhos/$desenhoId")
        if (!response.status.isSuccess()) {
            val erro = response.bodyAsText()
            logToFile("ERROR", "Delete rejeitado pelo servidor (${response.status}): $erro")
            throw Exception("Erro ${response.status}: $erro")
        }
    }
}
