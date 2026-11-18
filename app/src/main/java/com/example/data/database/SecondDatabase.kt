package com.example.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [SoundDropEntity::class, VirtualGraffitiEntity::class],
    version = 3,
    exportSchema = false
)
abstract class SecondDatabase : RoomDatabase() {
    abstract fun secondDao(): SecondDao

    companion object {
        @Volatile
        private var INSTANCE: SecondDatabase? = null

        fun getDatabase(context: Context): SecondDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    SecondDatabase::class.java,
                    "second_db"
                ).fallbackToDestructiveMigration(true)
                 .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
