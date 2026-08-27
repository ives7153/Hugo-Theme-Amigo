package com.amigo.app.data

import android.content.Context
import kotlinx.coroutines.runBlocking

/** 同步读取单个设置的便捷入口（供备份等场景使用） */
object SettingsRepositoryRef {
    fun ownerName(context: Context): String {
        return runBlocking { SettingsRepository(AppDatabase.get(context).settingDao()).getString(PrefKeys.OWNER_NAME, "我") }
    }

    fun ownerAvatar(context: Context): String {
        return runBlocking { SettingsRepository(AppDatabase.get(context).settingDao()).getString(PrefKeys.OWNER_AVATAR) }
    }
}