package com.amigo.app

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView

class MainActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences("amigo", Context.MODE_PRIVATE) }
    private val keyUrl = "blog_url"

    private var blogUrl = ""
    private val adminUrl get() = blogUrl.trimEnd('/') + "/ai-bot-admin/"

    private lateinit var webView: WebView
    private lateinit var bottomNav: BottomNavigationView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        blogUrl = prefs.getString(keyUrl, "") ?: ""
        if (blogUrl.isEmpty()) {
            showWelcome()
        } else {
            showMain()
        }
    }

    private fun showWelcome() {
        setContentView(R.layout.activity_welcome)
        val input = findViewById<EditText>(R.id.url_input)
        if (blogUrl.isNotEmpty()) {
            input.setText(blogUrl)
        }
        findViewById<android.widget.Button>(R.id.url_save).setOnClickListener {
            var url = input.text.toString().trim()
            if (url.isEmpty()) {
                toast("先填博客地址")
                return@setOnClickListener
            }
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "https://" + url
            }
            if (!url.startsWith("https://") && !url.startsWith("http://") || url.length < 12) {
                toast("地址格式不对，示例：https://你的博客.com")
                return@setOnClickListener
            }
            blogUrl = url.trimEnd('/')
            prefs.edit().putString(keyUrl, blogUrl).apply()
            showMain()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showMain() {
        setContentView(R.layout.activity_main)
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = blogUrl
        webView = findViewById(R.id.webview)
        bottomNav = findViewById(R.id.bottom_nav)

        val ws = webView.settings
        ws.javaScriptEnabled = true
        ws.domStorageEnabled = true
        ws.cacheMode = WebSettings.LOAD_DEFAULT
        ws.userAgentString = ws.userAgentString + " AmigoApp/1.0"

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return false
            }
        }

        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_feed -> { loadUrl(blogUrl); true }
                R.id.nav_admin -> { loadUrl(adminUrl); true }
                else -> false
            }
        }

        loadUrl(blogUrl)
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_change_site -> { showWelcome(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun loadUrl(url: String) {
        webView.loadUrl(url)
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}