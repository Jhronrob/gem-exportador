package data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import model.DesenhoAutodesk
import model.DesenhoStatus

/**
 * Repositório em memória usado pelo viewer.
 * Não conecta ao banco — dados chegam exclusivamente via WebSocket.
 */
class InMemoryDesenhoRepository : IDesenhoRepository {

    private val _state = MutableStateFlow<Map<String, DesenhoAutodesk>>(emptyMap())

    override fun observeAll(): Flow<List<DesenhoAutodesk>> =
        _state.map { it.values.sortedByDescending { d -> d.criadoEm ?: d.horarioEnvio } }

    override fun observePendentesEProcessando(): Flow<List<DesenhoAutodesk>> =
        _state.map { map ->
            map.values.filter {
                it.statusEnum == DesenhoStatus.PENDENTE || it.statusEnum == DesenhoStatus.PROCESSANDO
            }
        }

    override fun getAll(): List<DesenhoAutodesk> = _state.value.values.toList()

    override fun getById(id: String): DesenhoAutodesk? = _state.value[id]

    override fun getByStatus(status: String): List<DesenhoAutodesk> =
        _state.value.values.filter { it.status == status }

    override fun upsert(desenho: DesenhoAutodesk) {
        _state.value = _state.value + (desenho.id to desenho)
    }

    override fun upsertAll(desenhos: List<DesenhoAutodesk>) {
        _state.value = _state.value + desenhos.associateBy { it.id }
    }

    override fun updateStatus(id: String, status: String, horarioAtualizacao: String) {
        val current = _state.value[id] ?: return
        _state.value = _state.value + (id to current.copy(
            status = status,
            horarioAtualizacao = horarioAtualizacao,
            atualizadoEm = horarioAtualizacao
        ))
    }

    override fun updateProgresso(id: String, progresso: Int, horarioAtualizacao: String) {
        val current = _state.value[id] ?: return
        _state.value = _state.value + (id to current.copy(
            progresso = progresso,
            horarioAtualizacao = horarioAtualizacao,
            atualizadoEm = horarioAtualizacao
        ))
    }

    override fun delete(id: String) {
        _state.value = _state.value - id
    }

    override fun deleteAll() {
        _state.value = emptyMap()
    }
}
