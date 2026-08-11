package com.deep.rcode.feature.agent.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * RC68 SCHEMA 38：`createdAt` 统一 `createdAtMs` 后缀，避免「单位是秒还是毫秒？」的跨代码块歧义。
 */
@Entity(
    tableName = "session_checkpoints",
    indices = [Index(value = ["sessionId"])]
)
data class CheckpointEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val userMessageId: String,
    val promptSnippet: String,
    val createdAtMs: Long = System.currentTimeMillis()
)
