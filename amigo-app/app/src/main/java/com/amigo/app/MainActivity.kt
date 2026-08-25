package com.amigo.app

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomnavigation.BottomNavigationView

class MainActivity : AppCompatActivity() {

    // 部署后改成你的博客线上地址（必须 https，不能以 / 结尾）
    private val blogUrl = "https://你的博客.com"
    private val adminUrl = blogUrl.trimEnd('/') + "/ai-bot-admin/"

    private lateinit var webView: WebView
    private lateinit var bottomNav: BottomNavigationView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webview)
        bottomNav = findViewById(R.id.bottom_nav)

        val ws = webView.settings
        ws.javaScriptEnabled = true
        ws.domStorageEnabled = true
        ws.cacheMode = WebSettings.LOAD_DEFAULT
        ws.userAgentString = ws.userAgentString + " AmigoApp/1.0"

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // 站内跳转全部留在 WebView 里
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

    private fun loadUrl(url: String) {
        if (url.isEmpty() || url.contains("你的博客")) {
            Toast.makeText(this, "请先在 MainActivity.kt 里配置博客地址", Toast.LENGTH_LONG).show()
            return
        }
        webView.loadUrl(url)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}