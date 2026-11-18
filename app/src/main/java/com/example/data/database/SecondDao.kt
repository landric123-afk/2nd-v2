package com.example.data.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SecondDao {
    // Sound Drops queries
    @Query("SELECT * FROM sound_drops ORDER BY timestamp DESC")
    fun getAllSoundDrops(): Flow<List<SoundDropEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSoundDrop(soundDrop: SoundDropEntity)

    @Delete
    suspend fun deleteSoundDrop(soundDrop: SoundDropEntity)

    @Query("DELETE FROM sound_drops")
    suspend fun clearAllSoundDrops()

    // Virtual Graffiti queries
    @Query("SELECT * FROM virtual_graffiti ORDER BY timestamp DESC")
    fun getAllVirtualGraffiti(): Flow<List<VirtualGraffitiEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVirtualGraffiti(graffiti: VirtualGraffitiEntity)

    @Delete
    suspend fun deleteVirtualGraffiti(graffiti: VirtualGraffitiEntity)

    @Query("DELETE FROM virtual_graffiti")
    suspend fun clearAllVirtualGraffiti()
}
