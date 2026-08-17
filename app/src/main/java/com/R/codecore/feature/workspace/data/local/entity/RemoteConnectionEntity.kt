package com.R.codecore.feature.workspace.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.R.codecore.feature.workspace.domain.model.RemoteProtocol
import java.util.UUID

@Entity(tableName = "remote_connections")
data class RemoteConnectionEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val protocol: RemoteProtocol,
    val host: String,
    val port: Int,
    val username: String,
    val authType: String = "PASSWORD", // 'PASSWORD' (密码) or 'PRIVATE_KEY' (私钥)；历史版本可能存小写 password/key，读取时两边都认
    val authData: String, // PASSWORD: 加密后的密码；PRIVATE_KEY: 私钥文件路径
    val passphrase: String? = null // PRIVATE_KEY 场景: 加密后的私钥 passphrase
)
