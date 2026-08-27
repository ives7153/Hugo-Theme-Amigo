package com.amigo.app

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.amigo.app.ai.AIEngine
import com.amigo.app.data.AppDatabase
import com.amigo.app.data.Post
import com.amigo.app.data.PostImage
import com.amigo.app.databinding.ActivityPostEditorBinding
import com.amigo.app.util.ImageStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PostEditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPostEditorBinding
    private val db by lazy { AppDatabase.get(this) }
    private val pickedUris = mutableListOf<Uri>()
    private val relativeNames = mutableListOf<String>()

    private val picker =
        registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(9)) { uris ->
            if (uris.isNotEmpty()) {
                pickedUris.clear()
                relativeNames.clear()
                pickedUris.addAll(uris)
                renderPicked()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPostEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "发朋友圈"

        binding.btnAddImages.setOnClickListener {
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        binding.btnPublish.setOnClickListener { publish() }
    }

    private fun renderPicked() {
        binding.pickedGrid.removeAllViews()
        if (pickedUris.isEmpty()) {
            binding.btnAddImages.text = getString(R.string.add_images)
            return
        }
        binding.btnAddImages.text = "重新选择（${pickedUris.size}/9）"
        val density = resources.displayMetrics.density
        val cell = (resources.displayMetrics.widthPixels / 3 - (20 * density).toInt())
        pickedUris.chunked(3).forEach { chunk ->
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            chunk.forEach { uri ->
                val iv = ImageView(this)
                iv.layoutParams = LinearLayout.LayoutParams(cell, cell).apply { marginEnd = (6 * density).toInt() }
                iv.setBackgroundColor(0xFF2d2d2d.toInt())
                iv.setImageURI(uri)
                iv.setOnLongClickListener {
                    pickedUris.remove(uri)
                    renderPicked()
                    true
                }
                row.addView(iv)
            }
            binding.pickedGrid.addView(row)
        }
    }

    private fun publish() {
        val content = binding.contentInput.text.toString().trim()
        if (content.isEmpty() && pickedUris.isEmpty()) {
            Toast.makeText(this, "写点内容或选张图片", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val postId = db.postDao().insert(
                Post(content = content, createdAt = System.currentTimeMillis())
            )
            withContext(Dispatchers.IO) {
                pickedUris.mapIndexed { i, uri ->
                    val rel = ImageStore.importFromUri(this@PostEditorActivity, uri)
                    db.imageDao().insert(PostImage(postId = postId, path = rel, sort = i))
                    rel
                }
            }
            // 安排 AI 角色的"刷到时刻"
            try {
                AIEngine(this@PostEditorActivity).scheduleInitialEvents(postId)
            } catch (e: Exception) {
                // 角色为空或调度失败不阻塞发布
            }
            Toast.makeText(this@PostEditorActivity, "已发布", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}