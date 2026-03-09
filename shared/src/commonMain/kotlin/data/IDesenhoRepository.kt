package data

import kotlinx.coroutines.flow.Flow
import model.DesenhoAutodesk

interface IDesenhoRepository {
    fun observeAll(): Flow<List<DesenhoAutodesk>>
    fun observePendentesEProcessando(): Flow<List<DesenhoAutodesk>>
    fun getAll(): List<DesenhoAutodesk>
    fun getById(id: String): DesenhoAutodesk?
    fun getByStatus(status: String): List<DesenhoAutodesk>
    fun upsert(desenho: DesenhoAutodesk)
    fun upsertAll(desenhos: List<DesenhoAutodesk>)
    fun updateStatus(id: String, status: String, horarioAtualizacao: String)
    fun updateProgresso(id: String, progresso: Int, horarioAtualizacao: String)
    fun delete(id: String)
    fun deleteAll()
}
