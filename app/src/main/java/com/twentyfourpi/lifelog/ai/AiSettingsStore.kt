package com.twentyfourpi.lifelog.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.net.URI
import java.security.KeyStore
import java.util.Base64
import java.util.Properties
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class AiConfig(val baseUrl: String = "", val model: String = "", val hasKey: Boolean = false, val tokenParameter: String = "max_tokens")

/** Keystore-backed key and configuration live outside Android backup and app exports. */
class AiSettingsStore(context: Context) {
    private val file = File(context.noBackupFilesDir, "ai_service.properties")
    private val alias = "lifelog_ai_service_key_v1"

    @Synchronized fun read(): AiConfig = load().let { AiConfig(it.getProperty("base", ""), it.getProperty("model", ""), it.containsKey("key"), it.getProperty("token_parameter", "max_tokens")) }

    @Synchronized fun save(baseUrl: String, model: String, newKey: String?, tokenParameter: String = "max_tokens") {
        val uri = validatedBaseUrl(baseUrl)
        require(model.isNotBlank() && model.length <= 120 && !model.contains(Regex("[\\r\\n]"))) { "请输入有效模型名称" }
        require(tokenParameter in setOf("max_tokens", "max_completion_tokens")) { "Token 参数无效" }
        val p = load()
        val oldBase = p.getProperty("base", "")
        p["base"] = uri.toString().trimEnd('/')
        p["model"] = model.trim()
        p["token_parameter"] = tokenParameter
        if (newKey != null) {
            if (newKey.isBlank()) p.remove("key") else {
                require(newKey.length <= 4096 && !newKey.contains(Regex("[\\r\\n]"))) { "请输入有效 API Key" }
                p["key"] = encrypt(newKey.trim())
            }
        }
        // Destination change is surfaced by the review preview before any send.
        if (oldBase != p["base"]) p["destination_changed"] = "true"
        persist(p)
    }

    @Synchronized fun clearKey() { val p = load(); p.remove("key"); persist(p) }

    @Synchronized fun key(): String? = load().getProperty("key")?.let { decrypt(it) }

    private fun load() = Properties().also { props -> if (file.exists()) file.inputStream().use { props.load(it) } }
    private fun persist(p: Properties) {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.outputStream().use { p.store(it, null) }
        if (!tmp.renameTo(file)) { tmp.copyTo(file, overwrite = true); tmp.delete() }
    }

    private fun secret(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }
    private fun encrypt(text: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, secret()) }
        return Base64.getEncoder().encodeToString(c.iv + c.doFinal(text.toByteArray(Charsets.UTF_8)))
    }
    private fun decrypt(encoded: String): String {
        val all = Base64.getDecoder().decode(encoded)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, all.copyOfRange(0, 12))) }
        return String(c.doFinal(all.copyOfRange(12, all.size)), Charsets.UTF_8)
    }
}

fun validatedBaseUrl(text: String): URI {
    val uri = try { URI(text.trim()) } catch (_: Exception) { throw IllegalArgumentException("请输入 HTTPS 服务地址") }
    require(uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null) {
        "服务地址须为 HTTPS，且不能包含账号、查询参数或片段"
    }
    require(uri.port == -1 || uri.port in 1..65535) { "服务端口无效" }
    return uri
}
