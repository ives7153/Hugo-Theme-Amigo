package com.amigo.app.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.amigo.app.R
import com.amigo.app.data.AppDatabase
import com.amigo.app.data.Comment
import com.amigo.app.data.PrefKeys
import com.amigo.app.data.SettingsRepository
import com.amigo.app.util.AvatarLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class CommentSheetDialog(context: Context, private val postId: Long) : Dialog(context) {

    private val db = AppDatabase.get(context)
    private val settings = SettingsRepository(db.settingDao())
    private val scope = CoroutineScope(Dispatchers.Main)

    private lateinit var listContainer: LinearLayout
    private lateinit var input: EditText
    private lateinit var likeBtn: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = context.resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }

        val root = LinearLayout(context)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(context.getColor(R.color.card))
        root.setPadding(dp(16), dp(16), dp(16), dp(16))

        val title = TextView(context)
        title.text = "评论"
        title.setTextColor(context.getColor(R.color.text))
        title.textSize = 16f
        title.setPadding(0, 0, 0, dp(8))
        root.addView(title)

        // 点赞按钮（微信风格：点赞入口）
        likeBtn = TextView(context)
        likeBtn.text = "👍 点赞"
        likeBtn.gravity = Gravity.CENTER
        likeBtn.setTextColor(context.getColor(R.color.green))
        likeBtn.textSize = 14f
        likeBtn.setBackgroundResource(R.drawable.bg_input)
        val likeLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40))
        likeLp.bottomMargin = dp(8)
        root.addView(likeBtn, likeLp)

        val scroll = ScrollView(context)
        scroll.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(280))
        listContainer = LinearLayout(context)
        listContainer.orientation = LinearLayout.VERTICAL
        scroll.addView(listContainer)
        root.addView(scroll)

        input = EditText(context)
        input.hint = context.getString(R.string.comment_hint)
        input.setTextColor(context.getColor(R.color.text))
        input.setHintTextColor(context.getColor(R.color.muted))
        input.setBackgroundResource(R.drawable.bg_input)
        input.setPadding(dp(10), dp(10), dp(10), dp(10))
        val inputLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        inputLp.topMargin = dp(10)
        root.addView(input, inputLp)

        val send = TextView(context)
        send.text = context.getString(R.string.send)
        send.gravity = Gravity.CENTER
        send.setTextColor(Color.WHITE)
        send.textSize = 14f
        send.setBackgroundResource(R.drawable.bg_green_btn)
        val sendLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44))
        sendLp.topMargin = dp(10)
        root.addView(send, sendLp)
        send.setOnClickListener { submitComment() }
        likeBtn.setOnClickListener { toggleLike() }

        setContentView(root)
        window?.setLayout((context.resources.displayMetrics.widthPixels * 0.94f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        window?.setGravity(Gravity.BOTTOM)
        window?.setBackgroundDrawable(GradientDrawable().apply { setColor(Color.TRANSPARENT) })
        window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        refresh()
    }

    private fun refresh() {
        val density = context.resources.displayMetrics.density
        scope.launch {
            try {
                val ownerAvatar = settings.getString(PrefKeys.OWNER_AVATAR)
                val comments = db.commentDao().getByPost(postId)
                val myLike = comments.firstOrNull { !it.isAI && it.type == "like" }
                likeBtn.text = if (myLike != null) "❤️ 已点赞" else "👍 点赞"

                listContainer.removeAllViews()
                if (comments.isEmpty()) {
                    val empty = TextView(context)
                    empty.text = "还没有评论，说点什么吧"
                    empty.setTextColor(context.getColor(R.color.muted))
                    empty.textSize = 13f
                    empty.setPadding(0, 8, 0, 8)
                    listContainer.addView(empty)
                    return@launch
                }
                val replies = comments.filter { it.type == "comment" }
                comments.forEach { c ->
                    val row = LinearLayout(context)
                    row.orientation = LinearLayout.HORIZONTAL
                    row.gravity = Gravity.TOP
                    row.setPadding(0, 6, 0, 6)

                    val avatar = ImageView(context)
                    val size = (36 * density).toInt()
                    avatar.layoutParams = LinearLayout.LayoutParams(size, size)
                    avatar.setBackgroundResource(R.drawable.bg_avatar)
                    row.addView(avatar)
                    AvatarLoader.load(context, avatar, if (c.isAI) c.authorAvatar else ownerAvatar, c.authorName)

                    val textCol = LinearLayout(context)
                    textCol.orientation = LinearLayout.VERTICAL
                    val colLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    colLp.marginStart = (8 * density).toInt()
                    textCol.layoutParams = colLp

                    val name = TextView(context)
                    name.text = c.authorName
                    name.setTextColor(context.getColor(R.color.green))
                    name.textSize = 13f
                    textCol.addView(name)

                    val content = TextView(context)
                    content.setTextColor(context.getColor(R.color.text))
                    content.textSize = 14f
                    if (c.type == "like") {
                        content.text = "❤️"
                    } else if (c.replyTo != null && c.replyTo != 0L) {
                        val target = replies.firstOrNull { it.id == c.replyTo }?.authorName
                        content.text = if (target != null) "回复 $target：${c.content}" else c.content
                    } else {
                        content.text = c.content
                    }
                    textCol.addView(content)

                    row.addView(textCol)
                    listContainer.addView(row)
                }
            } catch (e: Exception) {
                // 防御：不闪退
            }
        }
    }

    private fun submitComment() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        scope.launch {
            try {
                val ownerName = settings.getString(PrefKeys.OWNER_NAME, "我")
                val ownerAvatar = settings.getString(PrefKeys.OWNER_AVATAR)
                db.commentDao().insert(
                    Comment(
                        postId = postId,
                        authorName = ownerName,
                        authorAvatar = ownerAvatar,
                        content = text,
                        createdAt = System.currentTimeMillis(),
                        isAI = false,
                        type = "comment"
                    )
                )
                input.text.clear()
                refresh()
            } catch (e: Exception) {
            }
        }
    }

    private fun toggleLike() {
        scope.launch {
            try {
                val ownerName = settings.getString(PrefKeys.OWNER_NAME, "我")
                val ownerAvatar = settings.getString(PrefKeys.OWNER_AVATAR)
                val comments = db.commentDao().getByPost(postId)
                val myLike = comments.firstOrNull { !it.isAI && it.type == "like" }
                if (myLike != null) {
                    db.commentDao().delete(myLike)
                    db.postDao().setLiked(postId, false)
                } else {
                    db.commentDao().insert(
                        Comment(
                            postId = postId,
                            authorName = ownerName,
                            authorAvatar = ownerAvatar,
                            content = "❤️",
                            createdAt = System.currentTimeMillis(),
                            isAI = false,
                            type = "like"
                        )
                    )
                    db.postDao().setLiked(postId, true)
                }
                refresh()
            } catch (e: Exception) {
            }
        }
    }
}
