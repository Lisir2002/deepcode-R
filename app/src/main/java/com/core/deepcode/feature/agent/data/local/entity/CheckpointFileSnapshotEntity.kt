package com.core.deepcode.feature.agent.data.local.entity

data class CheckpointFileSnapshotEntity(
     val id: String,
    val checkpointId: String,
    val filePath: String,
    val snapshotRelativePath: String,
    val changeType: String,
    val createdAt: Long = System.currentTimeMillis()
)
