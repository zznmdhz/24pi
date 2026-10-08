package com.twentyfourpi.lifelog.debug

import android.content.Context
import android.os.SystemClock
import com.twentyfourpi.lifelog.BuildConfig
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.UUID

/**
 * 本机、分段、缓冲写入的观察日志。
 *
 * 日志线程绝不能反向阻塞采集回调；队列极端拥堵时宁可记下丢弃数量，也不能拖死
 * UsageStats、NotificationListener 或 LocationManager。严禁写入通知标题、正文、联系人、
 * 完整坐标、用户输入的备份密码或地图 Key。
 */
object DiagnosticLog {
    private const val SEGMENT_MAX_BYTES = 10L * 1024 * 1024
    // v0.16.1：事故版 7 天即写出 164.6MB（事故窗口证据毁于超大导出中途死亡），临时收紧到 50MB。
    // 16.2 高频事件分钟聚合落地后可再评估放宽。
    private const val TOTAL_MAX_BYTES = 50L * 1024 * 1024
    private const val RETENTION_MS = 7L * 24 * 60 * 60_000
    private const val MAX_PENDING_WRITES = 20_000
    private const val FLUSH_INTERVAL_SECONDS = 2L

    private val stateLock = Any()
    private val droppedEvents = AtomicLong(0)
    private val eventSequence = AtomicLong(0)
    private val segmentSequence = AtomicLong(0)
    private val processSessionId = UUID.randomUUID().toString()
    private val executor = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "24pi-observation-log").apply { isDaemon = true }
    }.apply { setRemoveOnCancelPolicy(true) }

    @Volatile private var initialized = false
    private lateinit var directory: File
    private var writer: BufferedWriter? = null
    private var activeFile: File? = null
    private var lastFlushAt = 0L

    data class Stats(
        val segmentCount: Int,
        val totalBytes: Long,
        val pendingWrites: Int,
        val droppedEvents: Long,
        val retentionHours: Int,
        val maximumBytes: Long,
    )

    fun initialize(context: Context) {
        synchronized(stateLock) {
            if (initialized) return
            directory = File(context.filesDir, "diagnostics").apply { mkdirs() }
            initialized = true
        }
        executor.execute {
            pruneSegments()
            writeLine(jsonLine("app", "logger_initialized", mapOf(
                "formatVersion" to 4,
                "retentionHours" to RETENTION_MS / 60 / 60_000,
                "maximumBytes" to TOTAL_MAX_BYTES,
                "versionName" to BuildConfig.VERSION_NAME,
                "versionCode" to BuildConfig.VERSION_CODE,
            )))
        }
        executor.scheduleWithFixedDelay(
            { runCatching { flushWriter() } },
            FLUSH_INTERVAL_SECONDS,
            FLUSH_INTERVAL_SECONDS,
            TimeUnit.SECONDS,
        )
    }

    fun event(category: String, name: String, fields: Map<String, Any?> = emptyMap()) {
        enqueue(category, name, fields, "INFO")
    }

    fun warning(category: String, name: String, fields: Map<String, Any?> = emptyMap()) {
        enqueue(category, name, fields, "WARNING")
    }

    fun error(category: String, name: String, error: Throwable, fields: Map<String, Any?> = emptyMap()) {
        enqueue(category, name, fields + mapOf(
            "errorType" to error.javaClass.simpleName,
            "error" to (error.message ?: "unknown").take(500),
            "causeChain" to error.causeChain(),
            "stack" to error.stackTraceToString().take(6_000),
        ), "ERROR")
    }

    private fun enqueue(category: String, name: String, fields: Map<String, Any?>, severity: String) {
        if (!initialized) return
        if (executor.queue.size >= MAX_PENDING_WRITES) {
            droppedEvents.incrementAndGet()
            return
        }
        val line = runCatching { jsonLine(category, name, fields, severity) }.getOrElse { serializationError ->
            jsonLine("debug", "${name}_serialization_failed", mapOf(
                "errorType" to serializationError.javaClass.simpleName,
                "error" to serializationError.message.orEmpty().take(300),
            ), "ERROR")
        }
        runCatching { executor.execute { writeLine(line) } }
            .onFailure { droppedEvents.incrementAndGet() }
    }

    /**
     * Closes the active segment on the writer thread, then returns immutable files for export.
     * Events arriving during export start a new segment and cannot mutate the returned files.
     */
    fun files(): List<File> {
        if (!initialized) return emptyList()
        val latch = CountDownLatch(1)
        var snapshot: List<File> = emptyList()
        executor.execute {
            runCatching {
                emitDroppedMarkerIfNeeded()
                closeWriter()
                pruneSegments()
                snapshot = segmentFiles()
            }
            latch.countDown()
        }
        check(latch.await(30, TimeUnit.SECONDS)) { "诊断日志刷新超时" }
        return snapshot
    }

    /** Best-effort flush used immediately before Android terminates the process after an uncaught error. */
    fun flushForCrash(timeoutMs: Long = 1_000L) {
        if (!initialized || Thread.currentThread().name == "24pi-observation-log") return
        val latch = CountDownLatch(1)
        runCatching {
            executor.execute {
                runCatching { flushWriter() }
                latch.countDown()
            }
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        }
    }

    fun stats(): Stats {
        if (!initialized) return Stats(0, 0, 0, 0, (RETENTION_MS / 60 / 60_000).toInt(), TOTAL_MAX_BYTES)
        val files = synchronized(stateLock) { segmentFiles() }
        return Stats(
            segmentCount = files.size,
            totalBytes = files.sumOf(File::length),
            pendingWrites = executor.queue.size,
            droppedEvents = droppedEvents.get(),
            retentionHours = (RETENTION_MS / 60 / 60_000).toInt(),
            maximumBytes = TOTAL_MAX_BYTES,
        )
    }

    private fun writeLine(line: String) {
        runCatching {
            emitDroppedMarkerIfNeeded()
            val file = ensureWriter()
            writer?.apply {
                write(line)
                newLine()
            }
            if (file.length() >= SEGMENT_MAX_BYTES) {
                closeWriter()
                pruneSegments()
            } else if (System.currentTimeMillis() - lastFlushAt >= FLUSH_INTERVAL_SECONDS * 1_000) {
                flushWriter()
            }
        }.onFailure {
            // Logging failures must never escape into the collector callback that produced the event.
            droppedEvents.incrementAndGet()
            closeWriter()
        }
    }

    private fun emitDroppedMarkerIfNeeded() {
        val dropped = droppedEvents.getAndSet(0)
        if (dropped <= 0) return
        ensureWriter()
        writer?.apply {
            write(jsonLine("debug", "log_events_dropped", mapOf("count" to dropped)))
            newLine()
        }
    }

    private fun ensureWriter(): File {
        activeFile?.let { if (writer != null) return it }
        val file = File(
            directory,
            "diagnostics-${System.currentTimeMillis()}-${segmentSequence.incrementAndGet()}.jsonl",
        )
        activeFile = file
        writer = BufferedWriter(OutputStreamWriter(FileOutputStream(file, true), Charsets.UTF_8), 64 * 1024)
        lastFlushAt = System.currentTimeMillis()
        return file
    }

    private fun flushWriter() {
        writer?.flush()
        lastFlushAt = System.currentTimeMillis()
    }

    private fun closeWriter() {
        runCatching { writer?.flush() }
        runCatching { writer?.close() }
        writer = null
        activeFile = null
    }

    private fun pruneSegments(nowMs: Long = System.currentTimeMillis()) {
        val active = activeFile
        segmentFiles().filter { it != active && nowMs - it.lastModified() > RETENTION_MS }
            .forEach { runCatching { it.delete() } }
        val remaining = segmentFiles().filter { it != active }.sortedBy { it.lastModified() }.toMutableList()
        var total = segmentFiles().sumOf(File::length)
        while (total > TOTAL_MAX_BYTES && remaining.isNotEmpty()) {
            val oldest = remaining.removeAt(0)
            val bytes = oldest.length()
            if (oldest.delete()) total -= bytes else break
        }
    }

    private fun segmentFiles(): List<File> = if (!::directory.isInitialized) emptyList() else {
        directory.listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension == "jsonl" }
            .sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name })
    }

    private fun jsonLine(
        category: String,
        name: String,
        fields: Map<String, Any?>,
        severity: String = "INFO",
    ): String {
        val json = JSONObject()
            .put("at", Instant.now().toString())
            .put("wallTimeMs", System.currentTimeMillis())
            .put("elapsedRealtimeMs", SystemClock.elapsedRealtime())
            .put("sequence", eventSequence.incrementAndGet())
            .put("processSession", processSessionId)
            .put("thread", Thread.currentThread().name)
            .put("severity", severity)
            .put("category", category)
            .put("event", name)
        fields.forEach { (key, value) -> json.put(key, value ?: JSONObject.NULL) }
        return json.toString()
    }

    private fun Throwable.causeChain(): String = generateSequence(this) { it.cause }
        .take(6)
        .joinToString(" <- ") { cause ->
            "${cause.javaClass.simpleName}:${cause.message.orEmpty().take(160)}"
        }
}
