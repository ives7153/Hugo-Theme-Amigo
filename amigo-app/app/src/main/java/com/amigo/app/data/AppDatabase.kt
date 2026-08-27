package com.amigo.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [Post::class, PostImage::class, Comment::class, Character::class, Setting::class, AIEvent::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun postDao(): PostDao
    abstract fun imageDao(): ImageDao
    abstract fun commentDao(): CommentDao
    abstract fun characterDao(): CharacterDao
    abstract fun settingDao(): SettingDao
    abstract fun aiEventDao(): AIEventDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "amigo.db"
                ).build().also { INSTANCE = it }
            }
    }
}