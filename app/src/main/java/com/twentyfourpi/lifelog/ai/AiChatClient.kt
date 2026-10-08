package com.twentyfourpi.lifelog.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.io.Reader

sealed class AiServiceException(message: String) : Exception(message) {
    class Configuration : AiServiceException("请先填写服务地址、模型和 API Key")
    class Authentication : AiServiceException("认证失败，请检查 API Key")
    class RateLimited : AiServiceException("服务已限流，请稍后手动重试")
    class Timeout : AiServiceException("连接超时，请检查网络和服务地址")
    class Service : AiServiceException("服务暂时不可用或返回了无效内容")
    class Network : AiServiceException("无法连接服务，请检查网络和地址")
}

object ChatPayload {
    fun build(model: String, preview: String, tokenParameter: String = "max_tokens"): String = JSONObject().apply {
        require(tokenParameter in setOf("max_tokens", "max_completion_tokens"))
        put("model", model)
        put("stream", false)
        put(tokenParameter, 550)
        put("messages", JSONArray().put(JSONObject().put("role", "system").put("content",
            "你是私人生活记录回顾助手。仅解释用户提供的已记录汇总，不能推测缺失记录。数字以本地依据为准；如问题超出依据，请说明无法回答。用简洁中文回答。"))
            .put(JSONObject().put("role", "user").put("content", preview)))
    }.toString()

    fun answer(response: String): String {
        val text = JSONObject(response).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim()
        if (text.isBlank()) throw AiServiceException.Service()
        return text.take(8000)
    }
}

class AiChatClient(private val connectionFactory: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }) {
    @Volatile private var activeConnection: HttpURLConnection? = null
    fun cancel() { activeConnection?.disconnect() }
    suspend fun test(baseUrl: String, model: String, key: String, tokenParameter: String): String =
        request(baseUrl, key, ChatPayload.build(model, "连接测试；无生活记录。请只回答：连接成功", tokenParameter))

    suspend fun review(baseUrl: String, model: String, key: String, preview: String, tokenParameter: String): String =
        request(baseUrl, key, ChatPayload.build(model, preview, tokenParameter))

    private suspend fun request(baseUrl: String, key: String, body: String): String = withContext(Dispatchers.IO) {
        if (key.isBlank() || body.isBlank()) throw AiServiceException.Configuration()
        val uri = validatedBaseUrl(baseUrl)
        val endpoint = if (uri.path.trimEnd('/').endsWith("/chat/completions")) uri.toString()
            else uri.toString().trimEnd('/') + "/chat/completions"
        var connection: HttpURLConnection? = null
        try {
            connection = connectionFactory(URL(endpoint)).apply {
                requestMethod = "POST"
                instanceFollowRedirects = false
                connectTimeout = 10_000
                readTimeout = 25_000
                doOutput = true
                setRequestProperty("Authorization", "Bearer $key")
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
            }
            activeConnection = connection
            connection!!.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            currentCoroutineContext().ensureActive()
            val status = connection!!.responseCode
            if (status == 401 || status == 403) throw AiServiceException.Authentication()
            if (status == 429) throw AiServiceException.RateLimited()
            if (status !in 200..299) throw AiServiceException.Service()
            val text = connection!!.inputStream.bufferedReader().use(::readBounded)
            currentCoroutineContext().ensureActive()
            try { ChatPayload.answer(text) } catch (_: Exception) { throw AiServiceException.Service() }
        } catch (e: AiServiceException) { throw e }
        catch (e: SocketTimeoutException) { currentCoroutineContext().ensureActive(); throw AiServiceException.Timeout() }
        catch (e: IOException) { currentCoroutineContext().ensureActive(); throw AiServiceException.Network() }
        finally { connection?.disconnect(); if (activeConnection === connection) activeConnection = null }
    }
}

internal fun readBounded(reader: Reader, limit: Int = 12_000): String {
    val output = StringBuilder()
    val chunk = CharArray(1024)
    while (true) {
        val n = reader.read(chunk)
        if (n < 0) break
        output.append(chunk, 0, n)
        if (output.length > limit) throw AiServiceException.Service()
    }
    return output.toString()
}
