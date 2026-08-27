package com.amigo.app.data

object PrefKeys {
    const val OWNER_NAME = "owner_name"
    const val OWNER_AVATAR = "owner_avatar"
    const val AI_ENABLED = "ai_enabled"
    const val LLM_ENDPOINT = "llm_endpoint"
    const val LLM_API_KEY = "llm_api_key"
    const val LLM_MODEL = "llm_model"
    const val LLM_TEMPERATURE = "llm_temperature"
    const val SNOOZE_MIN = "snooze_min"
    const val SNOOZE_MAX = "snooze_max"
    const val AI_REPLY_RATE = "ai_reply_rate"
    const val CASCADE_MAX_ROUNDS = "cascade_max_rounds"
    const val CASCADE_DELAY_MIN = "cascade_delay_min"
    const val CASCADE_DELAY_MAX = "cascade_delay_max"
    const val MAX_COMMENTS_PER_POST = "max_comments_per_post"
}

class SettingsRepository(private val dao: SettingDao) {
    suspend fun getString(key: String, def: String = ""): String = dao.get(key) ?: def
    suspend fun getInt(key: String, def: Int): Int = dao.get(key)?.toIntOrNull() ?: def
    suspend fun getFloat(key: String, def: Float): Float = dao.get(key)?.toFloatOrNull() ?: def
    suspend fun set(key: String, value: String) = dao.put(Setting(key, value))
}