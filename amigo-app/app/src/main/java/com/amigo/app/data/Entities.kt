package com.amigo.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "posts")
data class Post(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val content: String,
    val createdAt: Long,
    val aiCount: Int = 0,
    val cascadeRound: Int = 0,
    val liked: Boolean = false
)

@Entity(tableName = "post_images")
data class PostImage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val postId: Long,
    val path: String,
    val sort: Int
)

@Entity(tableName = "comments")
data class Comment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val postId: Long,
    val authorName: String,
    val authorAvatar: String? = null,
    val content: String,
    val createdAt: Long,
    val isAI: Boolean = false,
    val type: String = "comment",
    val replyTo: Long? = null
)

@Entity(tableName = "characters")
data class Character(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val email: String = "",
    val avatar: String? = null,
    val persona: String = "",
    val activity: Int = 60,
    val likeRate: Float = 0.2f,
    val isBestFriend: Boolean = false
)

@Entity(tableName = "settings")
data class Setting(
    @PrimaryKey val key: String,
    val value: String
)