package com.twentyfourpi.lifelog.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.twentyfourpi.lifelog.LifeLogApp
import com.twentyfourpi.lifelog.ai.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

data class AiReviewState(
    val question: String = "最近30天去了哪里，按停留时间排序",
    val range: ReviewRange? = null,
    val evidence: ReviewEvidence? = null,
    val preview: String = "",
    val answer: String? = null,
    val status: String = "",
    val working: Boolean = false,
    val sending: Boolean = false,
    val stale: Boolean = false,
)

class AiViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as LifeLogApp
    private val store = AiSettingsStore(application)
    private val client = AiChatClient()
    private val _state = MutableStateFlow(AiReviewState())
    val state: StateFlow<AiReviewState> = _state
    private var task: Job? = null

    init {
        viewModelScope.launch { app.repository.observePlaces().drop(1).collect { invalidate() } }
        viewModelScope.launch { app.repository.observeAttributionRevisions().drop(1).collect { invalidate() } }
    }

    fun config(): AiConfig = store.read()
    fun saveConfig(base: String, model: String, key: String?, tokenParameter: String): String = try {
        store.save(base, model, key, tokenParameter); invalidate(); "已保存。发送前仍会显示目的地和依据。"
    } catch (_: Exception) { "配置无效，请检查 HTTPS 地址、模型和 Key" }
    fun clearKey(): String = try { store.clearKey(); invalidate(); "API Key 已清除" } catch (_: Exception) { "清除失败" }

    fun testConnection(onResult: (String) -> Unit) {
        cancel()
        task = viewModelScope.launch {
            val c = try { store.read() } catch (_: Exception) { onResult("配置读取失败"); return@launch }
            val key = try { store.key() } catch (_: Exception) { null }
            if (c.baseUrl.isBlank() || c.model.isBlank() || key.isNullOrBlank()) { onResult("请先保存地址、模型和 API Key"); return@launch }
            onResult("正在测试连接…")
            try { client.test(c.baseUrl, c.model, key, c.tokenParameter); onResult("连接成功") }
            catch (_: CancellationException) { onResult("已取消") }
            catch (e: AiServiceException) { onResult(e.message ?: "连接失败") }
            catch (_: Exception) { onResult("连接失败") }
        }
    }

    fun setQuestion(text: String) { cancel(); _state.value = AiReviewState(question = text.take(240)) }
    fun setRange(range: ReviewRange) { cancel(); _state.value = _state.value.copy(range = range, evidence = null, preview = "", answer = null, stale = false, status = "") }

    fun prepare() {
        cancel()
        val before = _state.value
        val range = before.range ?: when (val parsed = ReviewRangeParser.parse(before.question)) {
            is RangeParse.Valid -> parsed.range
            is RangeParse.NeedsSelection -> { _state.value = before.copy(status = parsed.reason); return }
        }
        _state.value = before.copy(range = range, evidence = null, preview = "", answer = null, working = true, sending = false, stale = false, status = "正在整理本地依据…")
        task = viewModelScope.launch(Dispatchers.IO) {
            try {
                val asOf = System.currentTimeMillis()
                val zone = ZoneId.systemDefault()
                val places = app.repository.observePlaces().first()
                val acc = EvidenceAccumulator(range, zone, asOf, places)
                var day = range.start
                while (day < range.endExclusive) {
                    acc.add(day, app.repository.observeDay(day).first())
                    day = day.plusDays(1)
                }
                val evidence = acc.finish()
                val preview = evidence.preview(before.question)
                _state.value = _state.value.copy(evidence = evidence, preview = preview, working = false,
                    status = if (evidence.hasRecords) "本地依据已就绪，请核对发送内容" else "这段时间没有可用记录；不能把它解释为零活动")
            } catch (_: CancellationException) { }
            catch (_: Exception) { _state.value = _state.value.copy(working = false, status = "读取记录失败，请重试") }
        }
    }

    fun send() {
        val s = _state.value
        if (s.working || s.sending || s.stale || s.evidence == null || !s.evidence.hasRecords) return
        val c = try { store.read() } catch (_: Exception) { _state.value = s.copy(status = "配置读取失败"); return }
        val key = try { store.key() } catch (_: Exception) { null }
        if (c.baseUrl.isBlank() || c.model.isBlank() || key.isNullOrBlank()) { _state.value = s.copy(status = "请先设置服务地址、模型和 API Key"); return }
        task?.cancel()
        _state.value = s.copy(sending = true, answer = null, status = "等待 AI 服务…")
        task = viewModelScope.launch {
            try {
                val answer = client.review(c.baseUrl, c.model, key, s.preview, c.tokenParameter)
                _state.value = _state.value.copy(answer = answer, sending = false, status = "生成总结；请以本地数字为准")
            } catch (_: CancellationException) { }
            catch (e: AiServiceException) { _state.value = _state.value.copy(sending = false, status = e.message ?: "请求失败") }
            catch (_: Exception) { _state.value = _state.value.copy(sending = false, status = "请求失败；本地依据仍可查看") }
        }
    }

    fun cancel() { task?.cancel(); client.cancel(); task = null; _state.value = _state.value.copy(working = false, sending = false, status = "已取消") }
    private fun invalidate() {
        task?.cancel(); client.cancel(); task = null
        if (_state.value.evidence != null || _state.value.working || _state.value.sending)
            _state.value = _state.value.copy(stale = true, working = false, sending = false, answer = null, status = "地点资料已变化，请重新整理依据")
    }
    override fun onCleared() { cancel(); super.onCleared() }
}
