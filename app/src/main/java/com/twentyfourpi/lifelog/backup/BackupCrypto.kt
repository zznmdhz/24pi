package com.twentyfourpi.lifelog.backup

import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object BackupCrypto {
    private val MAGIC = "24PIBK01".toByteArray(Charsets.US_ASCII)
    private const val ITERATIONS = 210_000
    private const val KEY_BITS = 256

    fun encrypt(input: InputStream, output: OutputStream, password: CharArray) {
        require(password.size >= 8) { "备份密码至少需要 8 个字符" }
        val random = SecureRandom()
        val salt = ByteArray(16).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        output.write(MAGIC); output.write(salt); output.write(iv)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, derive(password, salt), GCMParameterSpec(128, iv))
        CipherOutputStream(output, cipher).use { encrypted -> input.copyTo(encrypted) }
        password.fill('\u0000')
    }

    fun decrypt(input: InputStream, output: OutputStream, password: CharArray) {
        val magic = input.readExactly(MAGIC.size)
        require(magic.contentEquals(MAGIC)) { "不是有效的 24π 备份文件" }
        val salt = input.readExactly(16)
        val iv = input.readExactly(12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, derive(password, salt), GCMParameterSpec(128, iv))
        try {
            CipherInputStream(input, cipher).use { encrypted -> encrypted.copyTo(output) }
        } catch (error: Exception) {
            throw IllegalArgumentException("密码错误或备份文件已损坏", error)
        } finally { password.fill('\u0000') }
    }

    private fun derive(password: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, ITERATIONS, KEY_BITS)
        return try {
            SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
        } finally { spec.clearPassword() }
    }

    private fun InputStream.readExactly(size: Int): ByteArray {
        val result = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val read = read(result, offset, size - offset)
            if (read < 0) throw IllegalArgumentException("备份文件不完整")
            offset += read
        }
        return result
    }
}
