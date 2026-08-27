package com.amigo.app.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.amigo.app.ai.AIEngine
import com.amigo.app.data.AppDatabase
import com.amigo.app.data.PrefKeys
import com.amigo.app.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class AIWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return withContext(Dispatchers.IO) {
            try {
                if (enabled()) {
                    AIEngine(applicationContext).processDueEvents()
                }
                Result.success()
            } catch (e: Exception) {
                Result.retry()
            }
        }
    }

    private suspend fun enabled(): Boolean {
        val repo = SettingsRepository(AppDatabase.get(applicationContext).settingDao())
        return repo.getString(PrefKeys.AI_ENABLED, "1") == "1"
    }

    companion object {
        /** 应用启动时同步处理一次到期事件（受 AI 开关控制） */
        fun processDueNow(context: Context) {
            Thread {
                try {
                    runBlocking {
                        val repo = SettingsRepository(AppDatabase.get(context.applicationContext).settingDao())
                        if (repo.getString(PrefKeys.AI_ENABLED, "1") == "1") {
                            AIEngine(context.applicationContext).processDueEvents()
                        }
                    }
                } catch (ignored: Exception) {
                }
            }.start()
        }
    }
}