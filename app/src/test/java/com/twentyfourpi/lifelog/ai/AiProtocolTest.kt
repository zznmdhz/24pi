package com.twentyfourpi.lifelog.ai

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.Reader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class AiProtocolTest {
    @Test fun chatCompletionPayloadHasBoundedSingleCallAndNoSecret() {
        val body = ChatPayload.build("my-model", "问题：去哪\n已记录地点 公司 30分钟")
        val json = JSONObject(body)
        assertEquals("my-model", json.getString("model"))
        assertEquals(false, json.getBoolean("stream"))
        assertTrue(json.getInt("max_tokens") <= 550)
        assertEquals(2, json.getJSONArray("messages").length())
        assertFalse(body.contains("Authorization"))
    }

    @Test fun endpointRejectsUnsafeDestinations() {
        listOf("http://example.com/v1", "https://user:pass@example.com/v1", "https://example.com/v1?key=secret", "https://example.com/v1#part")
            .forEach { assertThrows(IllegalArgumentException::class.java) { validatedBaseUrl(it) } }
        assertEquals("example.com", validatedBaseUrl("https://example.com/v1").host)
    }

    @Test fun fragmentedResponseIsFullyReadAndBounded() {
        val input = "{\"choices\":[{\"message\":{\"content\":\"hello\"}}]}"
        val oneCharReader = object : Reader() {
            var index = 0
            override fun read(cbuf: CharArray, off: Int, len: Int): Int {
                if (index == input.length) return -1
                cbuf[off] = input[index++]
                return 1
            }
            override fun close() = Unit
        }
        assertEquals("hello", ChatPayload.answer(readBounded(oneCharReader)))
        assertThrows(AiServiceException.Service::class.java) { readBounded("too long".reader(), 2) }
    }

    @Test fun fakeTransportSeesExactPreviewAndNoRedirect() = runBlocking {
        val fake = FakeConnection(200, "{\"choices\":[{\"message\":{\"content\":\"已记录 60 分钟\"}}]}")
        val client = AiChatClient { fake }
        val preview = "问题：这个月在公司待了多长时间\n公司标记地点总停留：60分钟"
        assertEquals("已记录 60 分钟", client.review("https://example.com/v1", "demo", "synthetic", preview, "max_completion_tokens"))
        val sent = JSONObject(fake.written.toString(Charsets.UTF_8.name()))
        assertEquals(preview, sent.getJSONArray("messages").getJSONObject(1).getString("content"))
        assertTrue(sent.has("max_completion_tokens"))
        assertFalse(sent.has("max_tokens"))
        assertFalse(fake.instanceFollowRedirects)
        assertEquals("/v1/chat/completions", fake.url.path)
        assertEquals(1, fake.disconnects)
    }

    @Test fun fakeTransportMapsStatusAndTimeoutWithoutResponseLeak() {
        listOf(401 to AiServiceException.Authentication::class.java, 429 to AiServiceException.RateLimited::class.java, 302 to AiServiceException.Service::class.java).forEach { (status, klass) ->
            val fake = FakeConnection(status, "secret service response")
            assertThrows(klass) { runBlocking { AiChatClient { fake }.test("https://example.com/v1", "demo", "synthetic", "max_tokens") } }
        }
        val timeout = FakeConnection(200, "").apply { timeout = true }
        assertThrows(AiServiceException.Timeout::class.java) { runBlocking { AiChatClient { timeout }.test("https://example.com/v1", "demo", "synthetic", "max_tokens") } }
    }

    @Test fun explicitCancelDisconnectsBlockedFakeRequest() = runBlocking {
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        val fake = FakeConnection(200, "").apply { onResponse = { entered.countDown(); released.await(3, TimeUnit.SECONDS); throw IOException("cancelled") }; onDisconnect = { released.countDown() } }
        val client = AiChatClient { fake }
        val job = async(Dispatchers.Default) { client.test("https://example.com/v1", "demo", "synthetic", "max_tokens") }
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        job.cancel()
        client.cancel()
        withTimeout(3_000) { job.join() }
        assertTrue(job.isCancelled)
        assertTrue(fake.disconnects >= 1)
    }

    private class FakeConnection(val status: Int, val reply: String) : HttpURLConnection(URL("https://example.com/v1/chat/completions")) {
        val written = ByteArrayOutputStream()
        var disconnects = 0
        var timeout = false
        var onResponse: (() -> Int)? = null
        var onDisconnect: (() -> Unit)? = null
        override fun connect() = Unit
        override fun disconnect() { disconnects++; onDisconnect?.invoke() }
        override fun usingProxy() = false
        override fun getOutputStream() = written
        override fun getResponseCode(): Int {
            if (timeout) throw SocketTimeoutException("synthetic")
            return onResponse?.invoke() ?: status
        }
        override fun getInputStream() = ByteArrayInputStream(reply.toByteArray())
    }
}
