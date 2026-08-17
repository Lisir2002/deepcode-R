package com.R.codecore.feature.backup.domain

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCryptoTest {

    @Test
    fun encryptStream_and_decryptStream_roundTrip() {
        val plain = "R-CodeCore encrypted backup payload".toByteArray(Charsets.UTF_8)
        val password = "correct horse battery staple".toCharArray()

        val encryptedStream = ByteArrayOutputStream()
        BackupCrypto.encryptStream(ByteArrayInputStream(plain), encryptedStream, password)
        val encryptedBytes = encryptedStream.toByteArray()

        assertTrue(encryptedBytes.size > BackupCrypto.SALT_LEN + BackupCrypto.IV_LEN)

        val decryptedStream = ByteArrayOutputStream()
        BackupCrypto.decryptStream(ByteArrayInputStream(encryptedBytes), decryptedStream, password)
        assertArrayEquals(plain, decryptedStream.toByteArray())
    }

    @Test
    fun decryptStream_wrongPassword_throwsBackupDecryptionException() {
        val plain = "encrypted payload".toByteArray(Charsets.UTF_8)
        val password = "right-password".toCharArray()

        val encryptedStream = ByteArrayOutputStream()
        BackupCrypto.encryptStream(ByteArrayInputStream(plain), encryptedStream, password)

        val decryptedStream = ByteArrayOutputStream()
        assertThrows(BackupDecryptionException::class.java) {
            BackupCrypto.decryptStream(
                ByteArrayInputStream(encryptedStream.toByteArray()),
                decryptedStream,
                "wrong-password".toCharArray()
            )
        }
    }

    @Test
    fun decryptStream_dataTooShortForHeader_throwsIllegalArgumentException() {
        val shortData = ByteArray(BackupCrypto.SALT_LEN + BackupCrypto.IV_LEN - 1)
        val decryptedStream = ByteArrayOutputStream()

        assertThrows(IllegalArgumentException::class.java) {
            BackupCrypto.decryptStream(
                ByteArrayInputStream(shortData),
                decryptedStream,
                "pw".toCharArray()
            )
        }
    }
}
