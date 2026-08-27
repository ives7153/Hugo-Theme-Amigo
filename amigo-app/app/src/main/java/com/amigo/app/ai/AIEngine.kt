package com.amigo.app.ai

import android.content.Context
import com.amigo.app.data.AIEvent
import com.amigo.app.data.AppDatabase
import com.amigo.app.data.Character
import com.amigo.app.data.Comment
import com.amigo.app.data.EventStatus
import com.amigo.app.data.PrefKeys
import com.amigo.app.data.SettingsRepository
import java.util.concurrent.ThreadLocalRandom

class AIEngine(private val context: Context) {

    private val db = AppDatabase.get(context)
    private val settings = SettingsRepository(db.settingDao())

    // ============ 发帖时：给每个角色安排"刷到时刻" ============
    suspend fun scheduleInitialEvents(postId: Long) {
        val characters = db.characterDao().getAll()
        val now = System.currentTimeMillis()
        for (ch in characters) {
            // 挚友较快刷到；普通角色随机 5 分钟 ~ 6 小时
            val delayMs = if (ch.isBestFriend) {
                randomLong(1 * 60_000, 15 * 60_000)
            } else {
                randomLong(5 * 60_000, 6 * 60 * 60_000)
            }
            db.aiEventDao().insert(
                AIEvent(
                    postId = postId,
                    characterName = ch.name,
                    status = EventStatus.PENDING,
                    at = now + delayMs,
                    replyTo = null
                )
            )
        }
    }

    // ============ 定期 tick：处理到期的刷到/接话事件 ============
    suspend fun processDueEvents() {
        val due = db.aiEventDao().getDue(EventStatus.PENDING, System.currentTimeMillis())
        if (due.isEmpty()) return
        val characters = db.characterDao().getAll()
        for (ev in due) {
            val ch = characters.firstOrNull { it.name == ev.characterName } ?: continue
            processEvent(ev.postId, ch, ev.id, ev.replyTo)
        }
    }

    private suspend fun processEvent(postId: Long, character: Character, eventId: Long, replyTo: Long?) {
        val post = db.postDao().getById(postId) ?: return
        val comments = db.commentDao().getByPost(postId)

        // 挚友必回；其他角色按活跃度决定出现：回复 / 点赞 / 潜水
        val roll = ThreadLocalRandom.current().nextInt(100)
        if (!character.isBestFriend && roll >= character.activity) {
            if (ThreadLocalRandom.current().nextFloat() < character.likeRate) {
                db.commentDao().insert(
                    Comment(
                        postId = postId,
                        authorName = character.name,
                        authorAvatar = character.avatar,
                        content = "❤️",
                        createdAt = System.currentTimeMillis(),
                        isAI = true,
                        type = "like"
                    )
                )
                db.aiEventDao().setStatus(eventId, EventStatus.LIKED)
            } else {
                db.aiEventDao().setStatus(eventId, EventStatus.SKIPPED)
            }
            return
        }

        val maxComments = settings.getInt(PrefKeys.MAX_COMMENTS_PER_POST, 8)
        val replyCount = comments.count { it.isAI && it.type == "comment" }
        if (replyCount >= maxComments) {
            db.aiEventDao().setStatus(eventId, EventStatus.SKIPPED)
            return
        }

        val replyToComment = if (replyTo != null) {
            comments.firstOrNull { it.id == replyTo }
        } else {
            null
        }
        val replyToNick = replyToComment?.authorName ?: ""
        val followup = replyTo != null

        val system = buildSystem(character.persona)
        val user = buildUser(post.content, comments, followup, replyToNick)
        val content = try {
            cleanReply(generate(character, system, user))
        } catch (e: Exception) {
            null
        }
        if (content.isNullOrBlank()) {
            db.aiEventDao().setStatus(eventId, EventStatus.SKIPPED)
            return
        }

        val commentId = db.commentDao().insert(
            Comment(
                postId = postId,
                authorName = character.name,
                authorAvatar = character.avatar,
                content = content,
                createdAt = System.currentTimeMillis(),
                isAI = true,
                type = "comment",
                replyTo = replyTo
            )
        )
        db.aiEventDao().setStatus(eventId, EventStatus.REPLIED)
        db.postDao().bumpAiCount(postId)

        // AI 互聊：本条回复可能引出下一位 AI 接话
        maybeScheduleCascade(postId, character.name, commentId)
    }

    // ============ 级联：按概率挑下一位角色安排接话 ============
    private suspend fun maybeScheduleCascade(postId: Long, lastAuthor: String, lastCommentId: Long) {
        val aiReplyRate = settings.getFloat(PrefKeys.AI_REPLY_RATE, 0.4f)
        if (ThreadLocalRandom.current().nextFloat() >= aiReplyRate) return

        val post = db.postDao().getById(postId) ?: return
        val characters = db.characterDao().getAll()
        val comments = db.commentDao().getByPost(postId)
        val maxComments = settings.getInt(PrefKeys.MAX_COMMENTS_PER_POST, 8)
        if (comments.count { it.isAI && it.type == "comment" } >= maxComments) return

        val maxRounds = settings.getInt(PrefKeys.CASCADE_MAX_ROUNDS, 2)
        if (post.cascadeRound >= maxRounds) return

        val pick = pickCharacterExcluding(characters, lastAuthor) ?: return
        val delayMs = randomLong(
            settings.getInt(PrefKeys.CASCADE_DELAY_MIN, 10) * 60_000L,
            settings.getInt(PrefKeys.CASCADE_DELAY_MAX, 60) * 60_000L
        )
        db.aiEventDao().insert(
            AIEvent(
                postId = postId,
                characterName = pick.name,
                status = EventStatus.PENDING,
                at = System.currentTimeMillis() + delayMs,
                replyTo = lastCommentId
            )
        )
        db.postDao().setCascadeRound(postId, post.cascadeRound + 1)
    }

    // ============ LLM 调用 ============
    private suspend fun generate(character: Character, system: String, user: String): String {
        val endpoint = settings.getString(PrefKeys.LLM_ENDPOINT)
        val model = settings.getString(PrefKeys.LLM_MODEL)
        if (endpoint.isBlank() || model.isBlank()) {
            return mockReply(character)
        }
        val client = LLMClient(
            endpoint = endpoint,
            apiKey = settings.getString(PrefKeys.LLM_API_KEY),
            model = model,
            temperature = settings.getFloat(PrefKeys.LLM_TEMPERATURE, 0.9f)
        )
        return client.complete(system, user)
    }

    // ============ Prompt 构建（与 ai-bot/prompt.go 对齐） ============
    private fun buildSystem(persona: String): String {
        val sb = StringBuilder("你正在以微信朋友圈好友的身份评论朋友的一条朋友圈。")
        if (persona.isNotBlank()) {
            sb.append("\n你的设定：").append(persona)
        }
        sb.append(
            """
            
            要求：
            - 口语化，像真实朋友聊天，不要书面腔
            - 简短自然，一般不超过 120 字
            - 不要使用 Markdown、列表、标题、引号包裹
            - 只输出评论内容本身，不要任何前后缀说明
            - 如果上下文里有人先评论，你可以自然接话或回应其中某个人
            - 偶尔可以带一点点小动作描述但要克制
            - 不要提到自己是 AI、机器人或模型
            """.trimIndent()
        )
        return sb.toString()
    }

    private fun buildUser(postContent: String, comments: List<Comment>, isFollowup: Boolean, replyToNick: String): String {
        val sb = StringBuilder("【朋友圈内容】\n")
        sb.append(postContent.take(800)).append("\n\n")
        sb.append("【当前评论】\n")
        if (comments.isEmpty()) {
            sb.append("（还没有人评论）\n")
        } else {
            comments.forEachIndexed { i, c ->
                val nick = c.authorName
                val text = cleanContentText(c.content).take(200)
                if (c.type == "like") {
                    sb.append("${i + 1}. $nick：点了个赞\n")
                } else {
                    sb.append("${i + 1}. $nick：$text\n")
                }
            }
        }
        sb.append("\n【任务】\n")
        if (isFollowup) {
            if (replyToNick.isNotBlank()) {
                sb.append("上面刚刚新增了一条评论（来自 $replyToNick），请以朋友身份针对这条最新评论自然接话。")
            } else {
                sb.append("上面刚刚有新的评论，请以朋友身份自然接话，可以回应某个人。")
            }
        } else {
            sb.append("请以朋友身份评论这条朋友圈。")
        }
        return sb.toString()
    }

    private fun cleanContentText(s: String): String {
        return s.replace("[LIKE]", "")
            .replace("❤️", "")
            .replace(Regex("<[^>]*>"), "")
            .trim()
    }

    private fun cleanReply(s: String): String {
        var out = s.trim().trim('"', '\u201c', '\u201d', '\u2018', '\u2019', '\'')
        val lines = out.split("\n").map { it.trim().removePrefix("-").removePrefix(">").trim() }.filter { it.isNotEmpty() }
        out = lines.joinToString(" ")
        if (out.length > 200) out = out.take(200) + "…"
        return out.trim()
    }

    // LLM 未配置时的兜底回复：带角色风味，保证功能可演示
    private fun mockReply(character: Character): String {
        return when {
            character.persona.contains("毒舌") || character.persona.contains("损") ->
                "就这？不过确实有点意思，我笑死"
            character.persona.contains("温柔") || character.persona.contains("细心") ->
                "辛苦啦，看到你发这条，感觉状态不错，改天一起出来玩"
            character.persona.contains("程序员") || character.persona.contains("话少") ->
                "看到了。挺好的。"
            else -> "哈哈，说得对，给你点个赞"
        }
    }

    private fun pickCharacterExcluding(characters: List<Character>, exclude: String): Character? {
        val pool = characters.filter { it.name != exclude && it.activity > 0 }
        if (pool.isEmpty()) return null
        val total = pool.sumOf { it.activity }
        var n = ThreadLocalRandom.current().nextInt(total)
        for (ch in pool) {
            n -= ch.activity
            if (n < 0) return ch
        }
        return pool.last()
    }

    private fun randomLong(min: Long, max: Long): Long {
        if (max <= min) return min
        return min + ThreadLocalRandom.current().nextLong(max - min + 1)
    }
}