package com.twentyfourpi.lifelog.backup

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class BackupCryptoTest {
    @Test fun `round trip preserves content`() {
        val original = "只保存在本机的 24π 数据".repeat(100).toByteArray()
        val encrypted = ByteArrayOutputStream()
        BackupCrypto.encrypt(ByteArrayInputStream(original), encrypted, "correct-horse".toCharArray())
        assertFalse(original.contentEquals(encrypted.toByteArray()))

        val restored = ByteArrayOutputStream()
        BackupCrypto.decrypt(ByteArrayInputStream(encrypted.toByteArray()), restored, "correct-horse".toCharArray())
        assertArrayEquals(original, restored.toByteArray())
    }

    @Test fun `wrong password fails authentication`() {
        val encrypted = ByteArrayOutputStream()
        BackupCrypto.encrypt(ByteArrayInputStream("private".toByteArray()), encrypted, "correct-horse".toCharArray())
        assertThrows(IllegalArgumentException::class.java) {
            BackupCrypto.decrypt(ByteArrayInputStream(encrypted.toByteArray()), ByteArrayOutputStream(), "wrong-password".toCharArray())
        }
    }
}
