package com.amigo.app.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface PostDao {
    @Insert suspend fun insert(post: Post): Long
    @Insert suspend fun insertRaw(post: Post)
    @Query("SELECT * FROM posts ORDER BY createdAt DESC") suspend fun getAll(): List<Post>
    @Query("SELECT * FROM posts ORDER BY createdAt DESC") fun observeAll(): Flow<List<Post>>
    @Query("SELECT * FROM posts WHERE id = :id") suspend fun getById(id: Long): Post?
    @Query("UPDATE posts SET aiCount = aiCount + 1 WHERE id = :id") suspend fun bumpAiCount(id: Long)
    @Query("UPDATE posts SET cascadeRound = :round WHERE id = :id") suspend fun setCascadeRound(id: Long, round: Int)
    @Query("UPDATE posts SET liked = :liked WHERE id = :id") suspend fun setLiked(id: Long, liked: Boolean)
    @Query("DELETE FROM posts") suspend fun clearAll()
}

@Dao
interface ImageDao {
    @Insert suspend fun insert(img: PostImage): Long
    @Insert suspend fun insertRaw(img: PostImage)
    @Query("SELECT * FROM post_images WHERE postId = :postId ORDER BY sort") suspend fun getByPost(postId: Long): List<PostImage>
    @Query("SELECT * FROM post_images") suspend fun getAll(): List<PostImage>
    @Query("SELECT * FROM post_images") fun observeAll(): Flow<List<PostImage>>
    @Query("DELETE FROM post_images") suspend fun clearAll()
}

@Dao
interface CommentDao {
    @Insert suspend fun insert(c: Comment): Long
    @Insert suspend fun insertRaw(c: Comment)
    @Delete suspend fun delete(c: Comment)
    @Query("SELECT * FROM comments WHERE postId = :postId ORDER BY createdAt ASC") suspend fun getByPost(postId: Long): List<Comment>
    @Query("SELECT * FROM comments") suspend fun getAll(): List<Comment>
    @Query("SELECT * FROM comments WHERE postId = :postId") fun observeByPost(postId: Long): Flow<List<Comment>>
    @Query("DELETE FROM comments") suspend fun clearAll()
}

@Dao
interface CharacterDao {
    @Insert suspend fun insert(c: Character): Long
    @Insert suspend fun insertRaw(c: Character)
    @Query("SELECT * FROM characters ORDER BY isBestFriend DESC, activity DESC") suspend fun getAll(): List<Character>
    @Query("SELECT * FROM characters WHERE id = :id") suspend fun getById(id: Long): Character?
    @Update suspend fun update(c: Character)
    @Delete suspend fun delete(c: Character)
    @Query("DELETE FROM characters") suspend fun clearAll()
}

@Dao
interface SettingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(s: Setting)
    @Query("SELECT value FROM settings WHERE key = :key") suspend fun get(key: String): String?
    @Query("SELECT * FROM settings") suspend fun getAll(): List<Setting>
    @Query("DELETE FROM settings") suspend fun clearAll()
}