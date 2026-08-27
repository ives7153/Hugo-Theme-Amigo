package com.amigo.app.data

import androidx.room.*

@Dao
interface AIEventDao {
    @Insert suspend fun insert(e: AIEvent): Long
    @Insert suspend fun insertRaw(e: AIEvent)
    @Query("SELECT * FROM ai_events WHERE status = :status AND at <= :now ORDER BY at ASC") suspend fun getDue(status: String, now: Long): List<AIEvent>
    @Query("SELECT * FROM ai_events") suspend fun getAll(): List<AIEvent>
    @Query("SELECT * FROM ai_events WHERE postId = :postId") suspend fun getByPost(postId: Long): List<AIEvent>
    @Query("UPDATE ai_events SET status = :status WHERE id = :id") suspend fun setStatus(id: Long, status: String)
    @Query("DELETE FROM ai_events") suspend fun clearAll()
}