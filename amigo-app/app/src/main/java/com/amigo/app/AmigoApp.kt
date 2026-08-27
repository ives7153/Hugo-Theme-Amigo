package com.amigo.app

import android.app.Application
import java.io.File

class AmigoApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val default = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val f = File(filesDir, "crash.log")
                val sb = StringBuilder()
                sb.append("===== ").append(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA).format(java.util.Date())).append(" =====\n")
                sb.append("thread: ").append(thread.name).append("\n")
                sb.append(android.util.Log.getStackTraceString(throwable)).append("\n")
                f.appendText(sb.toString())
            } catch (_: Exception) {
            }
            default?.uncaughtException(thread, throwable)
        }
    }
}