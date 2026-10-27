package com.copilotodriverpro.shifts

import com.copilotodriverpro.database.ShiftDao
import com.copilotodriverpro.database.ShiftEntity
import kotlinx.coroutines.flow.Flow

/**
 * Repositório de turnos com guarda contra duplicidade.
 */
class ShiftRepository(private val dao: ShiftDao) {

    val all: Flow<List<ShiftEntity>> = dao.observeAll()
    val active: Flow<ShiftEntity?> = dao.observeActive()

    suspend fun byId(id: Long): ShiftEntity? = dao.byId(id)

    /** Inicia um turno apenas se não houver outro ativo. */
    suspend fun start(note: String = ""): Boolean {
        if (dao.active() != null) return false
        dao.insert(ShiftEntity(startMillis = System.currentTimeMillis(), note = note))
        return true
    }

    /** Encerra o turno ativo, registrando quilometragem opcional. */
    suspend fun end(distanceKm: Double? = null): ShiftEntity? {
        val current = dao.active() ?: return null
        val ended = current.copy(endMillis = System.currentTimeMillis(), distanceKm = distanceKm)
        dao.update(ended)
        return ended
    }

    /**
     * Turno automático: só inicia se ligado nas configurações e sem turno ativo.
     * Também ignora se o último turno encerrou há menos de 10 minutos — o
     * motorista encerrou de propósito e uma oferta isolada não reabre o turno.
     */
    suspend fun autoStart(): Boolean {
        val current = dao.active()
        if (current != null) return false
        val last = dao.all().firstOrNull()
        if (last?.endMillis != null && System.currentTimeMillis() - last.endMillis < TEN_MIN_MS) {
            return false
        }
        dao.insert(ShiftEntity(startMillis = System.currentTimeMillis(), note = "Iniciado automaticamente pelo Copiloto"))
        return true
    }

    suspend fun delete(shift: ShiftEntity) = dao.delete(shift)

    private companion object {
        const val TEN_MIN_MS = 10 * 60 * 1000L
    }
}
