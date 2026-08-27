package com.amigo.app.ui

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.amigo.app.data.Comment
import com.amigo.app.data.Post
import com.amigo.app.data.PostImage
import com.amigo.app.databinding.PostItemBinding
import com.amigo.app.util.AvatarLoader
import com.amigo.app.util.ImageStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class FeedItem(
    val post: Post,
    val images: List<PostImage>,
    val comments: List<Comment>
)

class FeedAdapter(
    private val context: Context,
    private val scope: CoroutineScope,
    private val ownerAvatar: String?,
    private val onLike: (Post) -> Unit,
    private val onComment: (Post) -> Unit
) : RecyclerView.Adapter<FeedAdapter.VH>() {

    private var items: List<FeedItem> = emptyList()

    fun submit(list: List<FeedItem>) {
        items = list
        notifyDataSetChanged()
    }

    class VH(val binding: PostItemBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = PostItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val b = holder.binding

        scope.launch { AvatarLoader.load(context, b.avatar, ownerAvatar, "我") }

        b.author.text = "我"
        b.content.text = item.post.content
        b.time.text = relativeTime(item.post.createdAt)
        b.likeBar.isVisible = false
        b.commentBar.isVisible = false

        renderImages(b.imageGrid, item.images)

        val likes = item.comments.filter { it.type == "like" }
        if (likes.isNotEmpty()) {
            b.likeBar.isVisible = true
            b.likeBar.text = "❤️ " + likes.joinToString("，") { it.authorName }
        }

        val replies = item.comments.filter { it.type == "comment" }
        if (replies.isNotEmpty()) {
            b.commentBar.isVisible = true
            b.commentBar.text = replies.joinToString("\n") { c -> formatComment(replies, c) }
        }

        b.btnLike.setOnClickListener { onLike(item.post) }
        b.btnComment.setOnClickListener { onComment(item.post) }
        b.cardRoot.setOnClickListener { onComment(item.post) }
    }

    private fun formatComment(replies: List<Comment>, c: Comment): String {
        val prefix = if (c.isAI) c.authorName else "我"
        if (c.replyTo != null && c.replyTo != 0L) {
            val target = replies.firstOrNull { it.id == c.replyTo }?.authorName
            if (target != null) return "$prefix 回复 $target：${c.content}"
        }
        return "$prefix：${c.content}"
    }

    private fun renderImages(container: LinearLayout, images: List<PostImage>) {
        container.removeAllViews()
        if (images.isEmpty()) return
        val density = context.resources.displayMetrics.density
        val screenW = context.resources.displayMetrics.widthPixels
        val margin = (6 * density).toInt()
        val pad = (12 * density).toInt()
        val cell = (screenW - pad * 2 - margin * 2) / 3

        when {
            images.size == 1 -> {
                val row = newRow(container)
                addImage(row, images[0], (screenW * 0.62f).toInt(), cell)
            }
            images.size == 2 -> {
                val row = newRow(container)
                addImage(row, images[0], (screenW * 0.62f).toInt(), cell)
                val row2 = newRow(container)
                addImage(row2, images[1], cell, cell)
            }
            else -> {
                images.chunked(3).forEach { chunk ->
                    val row = newRow(container)
                    chunk.forEach { img -> addImage(row, img, cell, cell) }
                    val missing = 3 - chunk.size
                    repeat(missing) { row.addView(emptyCell(row.context, cell)) }
                }
            }
        }
    }

    private fun newRow(container: LinearLayout): LinearLayout {
        val row = LinearLayout(container.context)
        row.orientation = LinearLayout.HORIZONTAL
        container.addView(row)
        return row
    }

    private fun addImage(row: LinearLayout, img: PostImage, width: Int, height: Int) {
        val iv = ImageView(row.context)
        val lp = LinearLayout.LayoutParams(width, height)
        lp.marginEnd = (6 * row.context.resources.displayMetrics.density).toInt()
        row.addView(iv, lp)
        loadImage(iv, img.path, width)
    }

    private fun emptyCell(context: Context, size: Int): View {
        val v = View(context)
        v.layoutParams = LinearLayout.LayoutParams(size, size)
        return v
    }

    private fun loadImage(iv: ImageView, path: String, size: Int) {
        iv.setImageDrawable(null)
        iv.setBackgroundColor(0xFF2d2d2d.toInt())
        scope.launch {
            val bmp = withContext(Dispatchers.IO) { ImageStore.loadBitmap(context, path, size) }
            if (bmp != null) {
                iv.setImageBitmap(bmp)
                iv.setBackgroundColor(Color.TRANSPARENT)
            }
        }
    }

    private fun relativeTime(ts: Long): String {
        val diff = System.currentTimeMillis() - ts
        return when {
            diff < 60_000 -> "刚刚"
            diff < 3600_000 -> "${diff / 60_000} 分钟前"
            diff < 24 * 3600_000 -> "${diff / 3600_000} 小时前"
            diff < 7 * 24 * 3600_000 -> "${diff / (24 * 3600_000)} 天前"
            else -> SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(ts))
        }
    }
}