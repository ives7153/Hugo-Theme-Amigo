package com.amigo.app.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class LLMClient(
    private val endpoint: String,
    private val apiKey: String,
    private val model: String,
    private val temperature: Float
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    suspend fun complete(system: String, user: String): String = withContext(Dispatchers.IO) {
        val messages = JSONArray().apply {
            put(JSONObject().put("role", "system").put("content", system))
            put(JSONObject().put("role", "user").put("content", user))
        }
        val body = JSONObject()
            .put("model", model)
            .put("temperature", temperature)
            .put("max_tokens", 200)
            .put("messages", messages)
        val req = Request.Builder()
            .url(endpoint)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey") }
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw RuntimeException("LLM HTTP ${resp.code}: ${resp.body?.string()?.take(200)}")
            }
            val raw = resp.body?.string() ?: throw RuntimeException("LLM 空响应")
            val parsed = JSONObject(raw)
            val choices = parsed.optJSONArray("choices")
            if (choices == null || choices.length() == 0) {
                throw RuntimeException("LLM 响应无 choices")
            }
            choices.getJSONObject(0).optJSONObject("message")?.optString("content", "")
                ?: throw RuntimeException("LLM 响应无 content")
        }
    }
}