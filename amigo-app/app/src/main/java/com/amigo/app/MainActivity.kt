package com.amigo.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.amigo.app.databinding.ActivityMainBinding
import com.amigo.app.ui.FeedFragment
import com.amigo.app.ui.SettingsFragment
import com.amigo.app.work.AIWorker
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val feedFragment by lazy { FeedFragment() }
    private val settingsFragment by lazy { SettingsFragment() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = "朋友圈"

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .add(R.id.container, feedFragment, "feed")
                .commit()
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_feed -> switchTo(feedFragment, "feed")
                R.id.nav_settings -> switchTo(settingsFragment, "settings")
                else -> false
            }
        }

        scheduleAIWorker()
        AIWorker.processDueNow(this)
    }

    private fun switchTo(fragment: Fragment, tag: String): Boolean {
        val fm = supportFragmentManager
        val current = fm.findFragmentById(R.id.container)
        if (current === fragment) return true
        val ft = fm.beginTransaction()
        ft.setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out)
        if (current != null) ft.hide(current)
        if (fragment.isAdded) {
            ft.show(fragment)
        } else {
            ft.add(R.id.container, fragment, tag)
        }
        ft.commit()
        return true
    }

    private fun scheduleAIWorker() {
        val request = PeriodicWorkRequestBuilder<AIWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "ai-worker",
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }
}