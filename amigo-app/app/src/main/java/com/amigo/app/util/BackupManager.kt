package com.amigo.app.util

import android.content.Context
import android.net.Uri
import com.amigo.app.data.AIEvent
import com.amigo.app.data.AppDatabase
import com.amigo.app.data.Character
import com.amigo.app.data.Comment
import com.amigo.app.data.Post
import com.amigo.app.data.PostImage
import com.amigo.app.data.SettingsRepositoryRef
import com.amigo.app.data.Setting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object BackupManager {

    private const val VERSION = 1
    private const val MAGIC = "amigo-backup"

    suspend fun export(context: Context, target: Uri) = withContext(Dispatchers.IO) {
        val db = AppDatabase.get(context)
        val root = JSONObject()
            .put("magic", MAGIC)
            .put("version", VERSION)
            .put("exportedAt", System.currentTimeMillis())
            .put("ownerName", SettingsRepositoryRef.ownerName(context))
            .put("ownerAvatar", SettingsRepositoryRef.ownerAvatar(context))

        root.put("posts", toArray(db.postDao().getAll().map { it.toJson() }))
        root.put("images", toArray(db.imageDao().getAll().map { it.toJson() }))
        root.put("comments", toArray(db.commentDao().getAll().map { it.toJson() }))
        root.put("characters", toArray(db.characterDao().getAll().map { it.toJson() }))
        root.put("settings", toArray(db.settingDao().getAll().map { it.toJson() }))
        root.put("aiEvents", toArray(db.aiEventDao().getAll().map { it.toJson() }))

        val imgDir = ImageStore.dir(context)
        val out = ZipOutputStream(BufferedOutputStream(context.contentResolver.openOutputStream(target)!!))
        try {
            out.putNextEntry(ZipEntry("backup.json"))
            out.write(root.toString().toByteArray(Charsets.UTF_8))
            out.closeEntry()
            db.imageDao().getAll().forEach { img ->
                val f = java.io.File(imgDir, img.path)
                if (f.exists()) {
                    out.putNextEntry(ZipEntry("images/" + img.path))
                    f.inputStream().use { it.copyTo(out) }
                    out.closeEntry()
                }
            }
        } finally {
            out.close()
        }
    }

    suspend fun import(context: Context, source: Uri): String = withContext(Dispatchers.IO) {
        val zip = ZipInputStream(BufferedInputStream(context.contentResolver.openInputStream(source)!!))
        var jsonText: String? = null
        val images = HashMap<String, ByteArray>()
        val imgDir = ImageStore.dir(context)
        try {
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name
                if (name == "backup.json") {
                    jsonText = zip.readBytes().toString(Charsets.UTF_8)
                } else if (name.startsWith("images/")) {
                    images[name.removePrefix("images/")] = zip.readBytes()
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        } finally {
            zip.close()
        }
        if (jsonText == null) throw IllegalArgumentException("备份文件缺少 backup.json")
        val root = JSONObject(jsonText)
        if (root.optString("magic") != MAGIC) throw IllegalArgumentException("不是有效的朋友圈备份文件")

        images.forEach { (rel, bytes) ->
            val f = java.io.File(imgDir, rel)
            f.parentFile?.mkdirs()
            f.writeBytes(bytes)
        }

        val db = AppDatabase.get(context)
        db.postDao().clearAll()
        db.imageDao().clearAll()
        db.commentDao().clearAll()
        db.characterDao().clearAll()
        db.settingDao().clearAll()
        db.aiEventDao().clearAll()

        // 帖子：保留原 id，让图片/评论外键有效
        val posts = root.optJSONArray("posts")
        if (posts != null) {
            for (i in 0 until posts.length()) {
                val o = posts.getJSONObject(i)
                db.postDao().insertRaw(
                    Post(
                        id = o.optLong("id"),
                        content = o.optString("content"),
                        createdAt = o.optLong("createdAt"),
                        aiCount = o.optInt("aiCount"),
                        cascadeRound = o.optInt("cascadeRound"),
                        liked = o.optBoolean("liked")
                    )
                )
            }
        }
        val imgs = root.optJSONArray("images")
        if (imgs != null) {
            for (i in 0 until imgs.length()) {
                val o = imgs.getJSONObject(i)
                db.imageDao().insertRaw(
                    PostImage(
                        id = o.optLong("id"),
                        postId = o.optLong("postId"),
                        path = o.optString("path"),
                        sort = o.optInt("sort")
                    )
                )
            }
        }
        val comments = root.optJSONArray("comments")
        if (comments != null) {
            for (i in 0 until comments.length()) {
                val o = comments.getJSONObject(i)
                db.commentDao().insertRaw(
                    Comment(
                        id = o.optLong("id"),
                        postId = o.optLong("postId"),
                        authorName = o.optString("authorName"),
                        authorAvatar = o.optString("authorAvatar").ifEmpty { null },
                        content = o.optString("content"),
                        createdAt = o.optLong("createdAt"),
                        isAI = o.optBoolean("isAI"),
                        type = o.optString("type"),
                        replyTo = if (o.isNull("replyTo")) null else o.optLong("replyTo")
                    )
                )
            }
        }
        val chars = root.optJSONArray("characters")
        if (chars != null) {
            for (i in 0 until chars.length()) {
                val o = chars.getJSONObject(i)
                db.characterDao().insertRaw(
                    Character(
                        id = o.optLong("id"),
                        name = o.optString("name"),
                        email = o.optString("email"),
                        avatar = o.optString("avatar").ifEmpty { null },
                        persona = o.optString("persona"),
                        activity = o.optInt("activity"),
                        likeRate = o.optDouble("likeRate").toFloat(),
                        isBestFriend = o.optBoolean("isBestFriend")
                    )
                )
            }
        }
        val settings = root.optJSONArray("settings")
        if (settings != null) {
            for (i in 0 until settings.length()) {
                val o = settings.getJSONObject(i)
                db.settingDao().put(Setting(o.optString("key"), o.optString("value")))
            }
        }
        val events = root.optJSONArray("aiEvents")
        if (events != null) {
            for (i in 0 until events.length()) {
                val o = events.getJSONObject(i)
                db.aiEventDao().insertRaw(
                    AIEvent(
                        id = o.optLong("id"),
                        postId = o.optLong("postId"),
                        characterName = o.optString("characterName"),
                        status = o.optString("status"),
                        at = o.optLong("at"),
                        replyTo = if (o.isNull("replyTo")) null else o.optLong("replyTo")
                    )
                )
            }
        }
        "导入完成：${posts?.length() ?: 0} 条朋友圈，${chars?.length() ?: 0} 个角色"
    }

    private fun toArray(items: List<JSONObject>): JSONArray = JSONArray().apply { items.forEach { put(it) } }

    private fun Post.toJson() = JSONObject()
        .put("id", id)
        .put("content", content)
        .put("createdAt", createdAt)
        .put("aiCount", aiCount)
        .put("cascadeRound", cascadeRound)
        .put("liked", liked)

    private fun PostImage.toJson() = JSONObject()
        .put("id", id)
        .put("postId", postId)
        .put("path", path)
        .put("sort", sort)

    private fun Comment.toJson() = JSONObject()
        .put("id", id)
        .put("postId", postId)
        .put("authorName", authorName)
        .put("authorAvatar", authorAvatar ?: "")
        .put("content", content)
        .put("createdAt", createdAt)
        .put("isAI", isAI)
        .put("type", type)
        .put("replyTo", replyTo ?: JSONObject.NULL)

    private fun Character.toJson() = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("email", email)
        .put("avatar", avatar ?: "")
        .put("persona", persona)
        .put("activity", activity)
        .put("likeRate", likeRate.toDouble())
        .put("isBestFriend", isBestFriend)

    private fun Setting.toJson() = JSONObject().put("key", key).put("value", value)

    private fun AIEvent.toJson() = JSONObject()
        .put("id", id)
        .put("postId", postId)
        .put("characterName", characterName)
        .put("status", status)
        .put("at", at)
        .put("replyTo", replyTo ?: JSONObject.NULL)
}