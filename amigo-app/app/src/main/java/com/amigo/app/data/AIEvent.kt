package com.amigo.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ai_events")
data class AIEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val postId: Long,
    val characterName: String,
    val status: String,
    val at: Long,
    val replyTo: Long? = null
)

object EventStatus {
    const val PENDING = "pending"
    const val REPLIED = "replied"
    const val LIKED = "liked"
    const val SKIPPED = "skipped"
}