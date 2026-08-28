package com.R.codecore.feature.workspace.data.local.entity

import java.util.UUID

data class RemoteMountEntity(
     val id: String = UUID.randomUUID().toString(),
    val connectionId: String,
    val remotePath: String,
    val localMountPath: String,
    val isActive: Boolean = false,
    
    val autoConnect: Boolean = true
)
