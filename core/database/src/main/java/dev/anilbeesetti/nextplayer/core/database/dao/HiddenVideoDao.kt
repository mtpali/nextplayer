package dev.anilbeesetti.nextplayer.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import dev.anilbeesetti.nextplayer.core.database.entities.HiddenVideoEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface HiddenVideoDao {

    @Query("SELECT * FROM hidden_video ORDER BY hidden_at DESC")
    fun getAll(): Flow<List<HiddenVideoEntity>>

    @Query("DELETE FROM hidden_video WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

}
