package com.example.capstone.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [EventEntity::class],  // 사용할 Entity 등록
    version = 3,                       // DB 버전 (스키마 변경 시 증가)
    exportSchema = false               // 스키마 내보내기 비활성화
)
abstract class BikiDatabase : RoomDatabase() {
    abstract fun eventDao(): EventDao

    companion object {
        @Volatile
        private var INSTANCE: BikiDatabase? = null

        fun getDatabase(context: Context): BikiDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    BikiDatabase::class.java,
                    "biki_database"  // DB 파일 이름
                ).fallbackToDestructiveMigration()
                    .build()

                INSTANCE = instance
                instance
            }
        }
    }
}