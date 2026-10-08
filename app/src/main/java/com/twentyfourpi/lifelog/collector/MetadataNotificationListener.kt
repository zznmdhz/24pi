package com.twentyfourpi.lifelog.collector

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.room.withTransaction
import com.twentyfourpi.lifelog.LifeLogApp
import com.twentyfourpi.lifelog.data.NotificationCaptureOrigin
import com.twentyfourpi.lifelog.data.NotificationEventEntity
import com.twentyfourpi.lifelog.data.SettingsStore
import com.twentyfourpi.lifelog.data.SourceId
import com.twentyfourpi.lifelog.data.SourceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.twentyfourpi.lifelog.debug.DiagnosticLog
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class MetadataNotificationListener : NotificationListenerService() {
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error ->
            DiagnosticLog.error("notifications", "listener_coroutine_failed", error)
        },
    )
    private val app get() = application as LifeLogApp
    private val activeKeys = ConcurrentHashMap.newKeySet<String>()
    private val countedKeys = ConcurrentHashMap.newKeySet<String>()
    private val notificationWriteGate = NotificationWriteGate()
    private val appLabels by lazy { AppLabelResolver(applicationContext) }
    private val duplicateLogTimes = ConcurrentHashMap<String, Long>()
    private val activeStateLock = Any()
    private val persistenceMutex = Mutex()
    private val snapshotFailureCount = AtomicInteger(0)
    private val healthRecoveryUnbind = AtomicBoolean(false)
    private val recoveryWindowStartAt = AtomicLong(0L)
    @Volatile private var activeGeneration = 0L
    private var heartbeatJob: Job? = null

    override fun onListenerConnected() {
        runCatching { handleListenerConnected() }
            .onFailure { DiagnosticLog.error("notifications", "connected_callback_failed", it) }
    }

    private fun handleListenerConnected() {
        val connectedAt = System.currentTimeMillis()
        val generation = connectionGeneration.incrementAndGet()
        activeGeneration = generation
        connected = true
        recoveryWindowStartAt.set(
            app.settings.getLong(NOTIFICATION_GAP_START_KEY).takeIf { it > 0L } ?: connectedAt,
        )
        snapshotFailureCount.set(0)
        lastCallbackAt.set(connectedAt)
        touchHeartbeat(app.settings, connectedAt, force = true)
        heartbeatJob?.cancel()
        val collectionExpected = shouldCollectNotifications()
        if (!collectionExpected) {
            val staleGapStartedAt = app.settings.getLong(NOTIFICATION_GAP_START_KEY)
            if (staleGapStartedAt > 0L) {
                app.settings.remove(NOTIFICATION_GAP_START_KEY)
                DiagnosticLog.event(
                    "notifications",
                    "disabled_source_gap_marker_cleared",
                    mapOf("gapStartedAt" to staleGapStartedAt),
                )
            }
        }
        synchronized(activeStateLock) {
            activeKeys.clear()
            countedKeys.clear()
            duplicateLogTimes.clear()
        }
        DiagnosticLog.event("notifications", "listener_connected")
        scope.launch {
            if (!app.settings.getBoolean("notification_dedup_v2_migrated")) {
                app.settings.putBoolean("notification_dedup_v2_migrated", true)
                DiagnosticLog.event("notifications", "dedup_v2_enabled_without_history_reset")
            }
            if (collectionExpected) {
                reconcileActiveNotifications(ReconcileTrigger.CONNECTED, connectedAt, generation)
            } else {
                closeOpenNotificationsForPause(connectedAt, generation)
            }
        }
        if (collectionExpected) startSnapshotHealthLoop(generation)
    }

    /**
     * A successful active-notification snapshot is both a binder health check and a recovery source.
     * The same path is used immediately after connect and every few minutes, so a service object that
     * remains alive while callbacks silently stop cannot keep refreshing a fake heartbeat forever.
     */
    private suspend fun reconcileActiveNotifications(
        trigger: ReconcileTrigger,
        observedAt: Long = System.currentTimeMillis(),
        generation: Long,
    ): Boolean {
        if (!isCurrentConnection(generation) || !shouldCollectNotifications()) return false
        val snapshotResult = runCatching {
            activeNotifications?.toList() ?: error("activeNotifications returned null")
        }
        val snapshot = snapshotResult.getOrElse { error ->
            handleSnapshotFailure(trigger, error, generation)
            return false
        }
        if (!isCurrentConnection(generation)) return false
        snapshotFailureCount.set(0)

        val entries = mutableListOf<SnapshotEntry>()
        var indexComplete = true
        snapshot.forEach { sbn ->
            runCatching { snapshotEntry(sbn) }
                .onSuccess { entry -> if (entry != null) entries += entry }
                .onFailure {
                    indexComplete = false
                    DiagnosticLog.error("notifications", "snapshot_notification_index_failed", it)
                }
        }
        val snapshotKeys = entries.mapTo(hashSetOf(), SnapshotEntry::key)
        synchronized(activeStateLock) {
            // Do not remove keys here: a realtime callback can legitimately arrive after the snapshot
            // was read. Removal callbacks and the next real reconnect own destructive state cleanup.
            activeKeys.addAll(snapshotKeys)
        }
        val recoveryFrom = recoveryWindowStartAt.get().takeIf { it > 0L } ?: observedAt

        val persistenceResult = runCatching {
            persistenceMutex.withLock {
                if (!isCurrentConnection(generation) || !shouldCollectNotifications()) return@withLock ReconcileCounts()
                val dao = app.databaseProvider.get().dao()
                // Only records that predate the snapshot may be considered stale. A realtime callback
                // racing this pass waits on the same mutex and can never have its new row closed here.
                val openBeforeSnapshot = dao.openNotificationKeyHashesBefore(observedAt).toHashSet()
                val activeHashes = entries.mapTo(hashSetOf(), SnapshotEntry::keyHash)
                val eligibleHashes = entries.asSequence()
                    .filter { it.eligible && it.postTime in recoveryFrom..observedAt }
                    .mapTo(hashSetOf(), SnapshotEntry::keyHash)
                val plan = notificationReconciliationPlan(
                    openBeforeSnapshot = openBeforeSnapshot,
                    activeHashes = activeHashes,
                    eligibleHashes = eligibleHashes,
                    indexComplete = indexComplete,
                )
                var closed = 0
                plan.staleOpenHashes.forEach { staleHash ->
                    closed += dao.closeOpenNotification(staleHash, observedAt, REASON_RECONCILED)
                }

                var recovered = 0
                entries.forEach { entry ->
                    val wasAlreadyOpen = entry.keyHash in openBeforeSnapshot
                    if (wasAlreadyOpen) synchronized(activeStateLock) { countedKeys += entry.key }
                    if (!entry.eligible) return@forEach
                    if (!wasAlreadyOpen && entry.keyHash !in plan.missingEligibleHashes) return@forEach

                    // A realtime callback may have arrived after the snapshot and already reserved this
                    // generation in memory while its DB write is waiting for the mutex. In that case the
                    // realtime path owns the record and the snapshot must not create a duplicate.
                    val captured = captureContent(entry)
                    val signature = buildSignature(entry.generation, captured.content)
                    val generationKnown = notificationWriteGate.isReservedOrPersisted(entry.key, signature)
                    if (generationKnown && !wasAlreadyOpen) {
                        synchronized(activeStateLock) { countedKeys += entry.key }
                        return@forEach
                    }

                    if (entry.keyHash in plan.missingEligibleHashes) {
                        // All notification writes share persistenceMutex, so this DAO check + insert is
                        // an atomic decision within the only process that owns notification lifecycle.
                        if (!dao.hasOpenNotification(entry.keyHash)) {
                            val resolvedLabel = resolveApplicationLabel(entry.packageName)
                            val label = resolvedLabel.value
                            val insertedId = dao.insertNotification(
                                NotificationEventEntity(
                                    packageName = entry.packageName,
                                    appLabel = label,
                                    occurredMs = entry.postTime.takeIf { it in 1..observedAt } ?: observedAt,
                                    action = "POSTED",
                                    notificationTitle = captured.content?.title,
                                    notificationBody = captured.content?.body,
                                    notificationKeyHash = entry.keyHash,
                                    channelId = entry.channelId,
                                    contentState = captured.state,
                                    captureOrigin = NotificationCaptureOrigin.RECONNECT,
                                ),
                            )
                            DiagnosticLog.event("notifications", "snapshot_notification_persisted", mapOf(
                                "notificationId" to insertedId,
                                "keyHash" to entry.keyHash,
                                "package" to entry.packageName,
                                "label" to label,
                                "labelSource" to resolvedLabel.source,
                                "postTime" to entry.postTime,
                                "observedAt" to observedAt,
                                "recoveryFrom" to recoveryFrom,
                                "contentState" to captured.state,
                                "titleLength" to captured.content?.title?.length,
                                "bodyLength" to captured.content?.body?.length,
                                "contentFingerprint" to contentFingerprint(captured.content),
                            ))
                            recovered++
                        }
                    }
                    synchronized(activeStateLock) { countedKeys += entry.key }
                    notificationWriteGate.recordPersisted(entry.key, signature, observedAt)
                }
                trimOldGenerations()
                ReconcileCounts(recovered = recovered, closed = closed)
            }
        }
        val counts = persistenceResult.getOrElse { error ->
            DiagnosticLog.error("notifications", "snapshot_reconciliation_failed", error, mapOf("trigger" to trigger.name))
            runCatching {
                app.repository.setStatus(SourceId.NOTIFICATIONS, SourceState.ERROR, "通知快照对账失败：${error.message.orEmpty()}")
            }
            return false
        }
        if (!isCurrentConnection(generation)) return false

        val finalized = runCatching {
            closePendingGap(observedAt)
            app.repository.setStatus(SourceId.NOTIFICATIONS, SourceState.ACTIVE, statusDetail(), touched = true)
        }.onFailure {
            DiagnosticLog.error("notifications", "snapshot_reconciliation_finalize_failed", it, mapOf("trigger" to trigger.name))
        }.isSuccess
        if (!finalized || !isCurrentConnection(generation)) return false

        recoveryWindowStartAt.set(observedAt)
        touchHeartbeat(app.settings, force = true)
        if (trigger == ReconcileTrigger.CONNECTED || counts.recovered > 0 || counts.closed > 0) {
            DiagnosticLog.event(
                "notifications",
                "snapshot_reconciled",
                mapOf(
                    "trigger" to trigger.name,
                    "active" to entries.size,
                    "recovered" to counts.recovered,
                    "closed" to counts.closed,
                    "indexComplete" to indexComplete,
                    "recoveryFrom" to recoveryFrom,
                ),
            )
        }
        return true
    }

    private fun startSnapshotHealthLoop(generation: Long) {
        heartbeatJob = scope.launch {
            while (isActive && isCurrentConnection(generation)) {
                delay(SNAPSHOT_HEALTH_INTERVAL_MS)
                if (!shouldCollectNotifications()) break
                reconcileActiveNotifications(ReconcileTrigger.HEALTH_CHECK, generation = generation)
            }
        }
    }

    private suspend fun closeOpenNotificationsForPause(pausedAt: Long, generation: Long) {
        runCatching {
            persistenceMutex.withLock {
                if (!isCurrentConnection(generation)) return@withLock
                val dao = app.databaseProvider.get().dao()
                dao.openNotificationKeyHashes().forEach { keyHash ->
                    dao.closeOpenNotification(keyHash, pausedAt, REASON_COLLECTION_PAUSED)
                }
            }
            if (isCurrentConnection(generation)) {
                app.repository.setStatus(SourceId.NOTIFICATIONS, SourceState.PAUSED, "已暂停")
            }
        }.onFailure { DiagnosticLog.error("notifications", "paused_reconciliation_failed", it) }
    }

    override fun onListenerDisconnected() {
        runCatching {
            val plannedHealthRecovery = healthRecoveryUnbind.get()
            val ownsConnection = connectionGeneration.compareAndSet(activeGeneration, activeGeneration + 1)
            if (ownsConnection) connected = false
            heartbeatJob?.cancel()
            lastCallbackAt.set(System.currentTimeMillis())
            DiagnosticLog.event(
                "notifications",
                "listener_disconnected",
                mapOf("trackedActive" to activeKeys.size, "ownsConnection" to ownsConnection),
            )
            if (ownsConnection) {
                markDisconnected(app.settings)
                if (!plannedHealthRecovery) requestReconnect(this, force = false)
            }
        }.onFailure { DiagnosticLog.error("notifications", "disconnected_callback_failed", it) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        runCatching { handleNotificationPosted(sbn) }
            .onFailure {
                DiagnosticLog.error(
                    "notifications",
                    "posted_callback_failed",
                    it,
                    mapOf("package" to runCatching { sbn.packageName }.getOrNull()),
                )
            }
    }

    private fun handleNotificationPosted(sbn: StatusBarNotification) {
        lastCallbackAt.set(System.currentTimeMillis())
        if (sbn.packageName == packageName) return
        if (!shouldCollectNotifications()) {
            synchronized(activeStateLock) { activeKeys += sbn.key }
            return
        }
        touchHeartbeat(app.settings)
        val keyHash = hashKey(sbn.key)
        val wasAlreadyActive = synchronized(activeStateLock) { !activeKeys.add(sbn.key) }
        val ranking = Ranking()
        val rankingFound = currentRanking?.getRanking(sbn.key, ranking) == true
        val importance = if (rankingFound) ranking.importance else NotificationManager.IMPORTANCE_DEFAULT
        val reason = NotificationCountingPolicy.exclusionReason(sbn.notification.flags, sbn.isClearable, importance)
        if (reason != null) {
            DiagnosticLog.event("notifications", "notification_filtered", metadata(sbn, keyHash) + mapOf("reason" to reason, "importance" to importance))
            return
        }
        // HyperOS 会在网络、锁屏等状态切换时把同一条通知移除后立刻原样重发。
        // key 在 remove 时会离开 activeKeys，因此还要用通知自身的 generation 时间去重；
        // 真正的新通知即使复用相同 key，也会有新的 when/postTime，仍会被记录。
        val generation = sbn.notification.`when`.takeIf { it > 0 } ?: sbn.postTime
        // 开启内容保存后，同一个通知 key 的正文变化也代表一次新的可见通知；这样聊天
        // 应用更新同一通知卡片时不会漏记。关闭内容保存时不读取 extras，只按 generation 去重。
        val contentEnabled = app.settings.notificationContentEnabled
        var contentReadFailed = false
        val content = if (contentEnabled) {
            runCatching { NotificationContentExtractor.extract(sbn.notification) }
                .onFailure {
                    contentReadFailed = true
                    DiagnosticLog.error("notifications", "content_extract_failed", it, metadata(sbn, keyHash))
                }
                .getOrNull()
        } else null
        val contentState = when {
            !contentEnabled -> "DISABLED"
            contentReadFailed -> "READ_FAILED"
            content == null || content.isEmpty -> "NOT_PROVIDED"
            else -> "CAPTURED"
        }
        val signature = buildSignature(generation, content)
        val reservation = notificationWriteGate.reserve(sbn.key, signature)
        if (reservation == null) {
            val now = System.currentTimeMillis()
            val lastLogged = duplicateLogTimes.put(sbn.key, now) ?: 0L
            if (now - lastLogged >= DUPLICATE_LOG_INTERVAL_MS) {
                DiagnosticLog.event("notifications", "update_deduplicated", metadata(sbn, keyHash))
            }
            return
        }
        try {
            trimOldGenerations()
            synchronized(activeStateLock) { countedKeys += sbn.key }
            DiagnosticLog.event(
                "notifications",
                if (wasAlreadyActive) "notification_content_updated" else "notification_counted",
                metadata(sbn, keyHash) + mapOf(
                    "contentState" to contentState,
                    "titleLength" to content?.title?.length,
                    "bodyLength" to content?.body?.length,
                    "contentFingerprint" to contentFingerprint(content),
                ),
            )
            val eventPackage = sbn.packageName
            val occurredAt = if (wasAlreadyActive) {
                System.currentTimeMillis()
            } else {
                sbn.postTime.takeIf { it > 0L } ?: System.currentTimeMillis()
            }
            val channelId = runCatching { sbn.notification.channelId }.getOrNull()
            recordPosted(
                eventPackage = eventPackage,
                keyHash = keyHash,
                channelId = channelId,
                content = content,
                contentState = contentState,
                occurredAt = occurredAt,
                reservation = reservation,
            )
        } catch (error: Throwable) {
            notificationWriteGate.rollback(reservation)
            throw error
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap, reason: Int) {
        runCatching { handleNotificationRemoved(sbn, reason) }
            .onFailure {
                DiagnosticLog.error(
                    "notifications",
                    "removed_callback_failed",
                    it,
                    mapOf("package" to runCatching { sbn.packageName }.getOrNull()),
                )
            }
    }

    private fun handleNotificationRemoved(sbn: StatusBarNotification, reason: Int) {
        lastCallbackAt.set(System.currentTimeMillis())
        if (sbn.packageName == packageName) return
        val wasCounted = synchronized(activeStateLock) {
            activeKeys.remove(sbn.key)
            duplicateLogTimes.remove(sbn.key)
            countedKeys.remove(sbn.key)
        }
        if (!shouldCollectNotifications()) return
        touchHeartbeat(app.settings)
        DiagnosticLog.event("notifications", "notification_removed", metadata(sbn, hashKey(sbn.key)) + mapOf("reason" to reason, "wasCounted" to wasCounted))
        // The in-memory key set can be incomplete after an OEM callback failure. Closing by hash is
        // idempotent and guarantees a recovered/open lifecycle cannot remain stuck forever.
        recordRemoval(hashKey(sbn.key), reason)
    }

    private fun snapshotEntry(sbn: StatusBarNotification): SnapshotEntry? {
        val eventPackage = sbn.packageName
        if (eventPackage == packageName) return null
        val key = sbn.key
        val keyHash = hashKey(key)
        val notification = sbn.notification
        val ranking = Ranking()
        val importance = runCatching {
            if (currentRanking?.getRanking(key, ranking) == true) ranking.importance
            else NotificationManager.IMPORTANCE_DEFAULT
        }.getOrElse {
            DiagnosticLog.error("notifications", "snapshot_ranking_read_failed", it, mapOf("keyHash" to keyHash))
            NotificationManager.IMPORTANCE_DEFAULT
        }
        val exclusionReason = NotificationCountingPolicy.exclusionReason(
            notification.flags,
            sbn.isClearable,
            importance,
        )
        return SnapshotEntry(
            sbn = sbn,
            key = key,
            keyHash = keyHash,
            packageName = eventPackage,
            postTime = sbn.postTime,
            generation = notification.`when`.takeIf { it > 0L } ?: sbn.postTime,
            channelId = runCatching { notification.channelId }.getOrNull(),
            eligible = exclusionReason == null,
        )
    }

    /** The disabled branch returns before NotificationContentExtractor can touch Notification.extras. */
    private fun captureContent(entry: SnapshotEntry): CapturedContent {
        if (!app.settings.notificationContentEnabled) return CapturedContent(null, "DISABLED")
        var readFailed = false
        val content = runCatching { NotificationContentExtractor.extract(entry.sbn.notification) }
            .onFailure {
                readFailed = true
                DiagnosticLog.error(
                    "notifications",
                    "snapshot_content_extract_failed",
                    it,
                    mapOf("package" to entry.packageName, "keyHash" to entry.keyHash),
                )
            }
            .getOrNull()
        val state = when {
            readFailed -> "READ_FAILED"
            content == null || content.isEmpty -> "NOT_PROVIDED"
            else -> "CAPTURED"
        }
        return CapturedContent(content, state)
    }

    private suspend fun resolveApplicationLabel(eventPackage: String): ResolvedAppLabel {
        val resolved = appLabels.resolve(eventPackage)
        if (resolved.authoritative) {
            app.repository.updateRecordedAppLabel(eventPackage, resolved.value)
        }
        return resolved
    }

    private fun buildSignature(generation: Long, content: StoredNotificationContent?): String = buildString {
        append(generation)
        if (content != null) {
            append('|').append(content.title.orEmpty())
            append('|').append(content.body.orEmpty())
        }
    }

    private suspend fun closePendingGap(recoveredAt: Long) {
        val gapStartedAt = app.settings.getLong(NOTIFICATION_GAP_START_KEY)
        if (gapStartedAt <= 0L) return
        CollectionGapDetector(app.databaseProvider, app.settings).recordGap(
            SourceId.NOTIFICATIONS,
            gapStartedAt,
            recoveredAt,
            "LISTENER_RECONNECTED",
        )
        app.settings.remove(NOTIFICATION_GAP_START_KEY)
    }

    private suspend fun handleSnapshotFailure(
        trigger: ReconcileTrigger,
        error: Throwable,
        generation: Long,
    ) {
        if (!isCurrentConnection(generation)) return
        val failures = snapshotFailureCount.incrementAndGet()
        DiagnosticLog.error(
            "notifications",
            "active_notifications_snapshot_failed",
            error,
            mapOf("trigger" to trigger.name, "consecutiveFailures" to failures),
        )
        if (error !is SecurityException && failures < SNAPSHOT_FAILURE_THRESHOLD) return

        if (!connectionGeneration.compareAndSet(generation, generation + 1)) return
        connected = false
        healthRecoveryUnbind.set(true)
        markDisconnected(app.settings)
        runCatching {
            app.repository.setStatus(
                SourceId.NOTIFICATIONS,
                SourceState.SYSTEM_BLOCKED,
                "通知监听快照连续失败，正在重新连接",
            )
        }
        scheduleRebindAfterUnbind()
        runCatching { requestUnbind() }
            .onFailure { DiagnosticLog.error("notifications", "listener_unbind_failed", it) }
    }

    private fun scheduleRebindAfterUnbind() {
        val applicationContext = applicationContext
        val throttleRemaining = (
            REBIND_THROTTLE_MS - (System.currentTimeMillis() - lastRebindRequestAt.get())
        ).coerceAtLeast(0L)
        val delayMs = maxOf(REBIND_AFTER_UNBIND_DELAY_MS, throttleRemaining)
        Handler(Looper.getMainLooper()).postDelayed({
            healthRecoveryUnbind.set(false)
            requestReconnect(applicationContext, force = false)
        }, delayMs)
    }

    private fun isCurrentConnection(generation: Long): Boolean =
        connected && connectionGeneration.get() == generation

    private fun metadata(sbn: StatusBarNotification, keyHash: String): Map<String, Any?> = runCatching {
        mapOf(
            "package" to sbn.packageName,
            "id" to sbn.id,
            "keyHash" to keyHash,
            "channel" to sbn.notification.channelId,
            "flags" to sbn.notification.flags,
            "clearable" to sbn.isClearable,
            "postTime" to sbn.postTime,
            "notificationWhen" to sbn.notification.`when`,
        )
    }.getOrElse { mapOf("package" to runCatching { sbn.packageName }.getOrNull(), "keyHash" to keyHash, "metadataError" to it.javaClass.simpleName) }

    private fun trimOldGenerations() {
        val cutoff = System.currentTimeMillis() - GENERATION_CACHE_MS
        notificationWriteGate.trim(MAX_GENERATION_CACHE, cutoff)
    }

    private fun hashKey(key: String): String = MessageDigest.getInstance("SHA-256")
        .digest(key.toByteArray()).take(16).joinToString("") { "%02x".format(it) }

    private fun recordPosted(
        eventPackage: String,
        keyHash: String,
        channelId: String?,
        content: StoredNotificationContent?,
        contentState: String,
        occurredAt: Long,
        reservation: NotificationWriteGate.Reservation,
    ) = scope.launch {
        persistenceMutex.withLock {
            val persistenceResult = withContext(NonCancellable) {
                runCatching {
                    val resolvedLabel = resolveApplicationLabel(eventPackage)
                    val label = resolvedLabel.value
                    val now = System.currentTimeMillis()
                    val database = app.databaseProvider.get()
                    val (closedCount, insertedId) = database.withTransaction {
                        val dao = database.dao()
                        // A content update closes the previous lifecycle and creates its replacement as one unit.
                        val closed = dao.closeOpenNotification(keyHash, now, REASON_SUPERSEDED)
                        // content is extracted only behind the user's separate opt-in setting.
                        val inserted = dao.insertNotification(NotificationEventEntity(
                            packageName = eventPackage, appLabel = label,
                            occurredMs = occurredAt, action = "POSTED",
                            notificationTitle = content?.title, notificationBody = content?.body,
                            notificationKeyHash = keyHash,
                            channelId = channelId,
                            contentState = contentState,
                            captureOrigin = NotificationCaptureOrigin.REALTIME,
                        ))
                        closed to inserted
                    }
                    PersistedNotification(
                        id = insertedId,
                        label = label,
                        labelSource = resolvedLabel.source,
                        persistedAt = now,
                        closedSupersededCount = closedCount,
                    )
                }.also { result ->
                    if (result.isSuccess) notificationWriteGate.commit(reservation, result.getOrThrow().persistedAt)
                    else notificationWriteGate.rollback(reservation)
                }
            }
            persistenceResult.onSuccess { persisted ->
                runCatching {
                    DiagnosticLog.event("notifications", "notification_persisted", mapOf(
                        "notificationId" to persisted.id,
                        "keyHash" to keyHash,
                        "package" to eventPackage,
                        "label" to persisted.label,
                        "labelSource" to persisted.labelSource,
                        "occurredAt" to occurredAt,
                        "persistedAt" to persisted.persistedAt,
                        "closedSupersededCount" to persisted.closedSupersededCount,
                        "channel" to channelId,
                        "contentState" to contentState,
                        "titleLength" to content?.title?.length,
                        "bodyLength" to content?.body?.length,
                        "contentFingerprint" to contentFingerprint(content),
                    ))
                }.onFailure {
                    DiagnosticLog.error("notifications", "notification_success_log_failed", it)
                }
                runCatching {
                    app.repository.setStatus(SourceId.NOTIFICATIONS, SourceState.ACTIVE, statusDetail(), touched = true)
                }.onFailure {
                    DiagnosticLog.error("notifications", "notification_status_update_failed", it)
                }
            }.onFailure { persistenceError ->
                runCatching {
                    DiagnosticLog.error("notifications", "notification_persist_failed", persistenceError, mapOf("package" to eventPackage))
                }
                runCatching {
                    app.repository.setStatus(SourceId.NOTIFICATIONS, SourceState.ERROR, "通知写入失败：${persistenceError.message.orEmpty()}")
                }.onFailure { statusError ->
                    DiagnosticLog.error("notifications", "notification_error_status_update_failed", statusError)
                }
            }
        }
    }.also { job ->
        // launch may be cancelled before it acquires persistenceMutex; never leave that reservation stuck.
        job.invokeOnCompletion { notificationWriteGate.rollback(reservation) }
    }

    private fun recordRemoval(keyHash: String, reason: Int) = scope.launch {
        persistenceMutex.withLock {
            runCatching {
                val closedCount = app.databaseProvider.get().dao().closeOpenNotification(
                    keyHash = keyHash,
                    removedMs = System.currentTimeMillis(),
                    reasonCode = reason,
                )
                DiagnosticLog.event("notifications", "notification_removal_persisted", mapOf(
                    "keyHash" to keyHash, "reason" to reason, "closedCount" to closedCount,
                ))
                app.repository.setStatus(SourceId.NOTIFICATIONS, SourceState.ACTIVE, statusDetail(), touched = true)
            }.onFailure { DiagnosticLog.error("notifications", "notification_removal_persist_failed", it) }
        }
    }

    private fun statusDetail() = if (app.settings.notificationContentEnabled) "统计普通通知，并保存标题和正文" else STATUS_DETAIL

    private fun contentFingerprint(content: StoredNotificationContent?): String? {
        if (content == null || content.isEmpty) return null
        return MessageDigest.getInstance("SHA-256")
            .digest("${content.title.orEmpty()}\u0000${content.body.orEmpty()}".toByteArray())
            .take(12)
            .joinToString("") { "%02x".format(it) }
    }

    private fun shouldCollectNotifications(): Boolean =
        app.settings.collectionEnabled && app.settings.sourceEnabled(SourceId.NOTIFICATIONS)

    override fun onDestroy() {
        val ownsConnection = connectionGeneration.compareAndSet(activeGeneration, activeGeneration + 1)
        if (ownsConnection) connected = false
        heartbeatJob?.cancel()
        if (ownsConnection) {
            runCatching { markDisconnected(app.settings) }
                .onFailure { DiagnosticLog.error("notifications", "destroy_disconnect_mark_failed", it) }
        }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private data class SnapshotEntry(
            val sbn: StatusBarNotification,
            val key: String,
            val keyHash: String,
            val packageName: String,
            val postTime: Long,
            val generation: Long,
            val channelId: String?,
            val eligible: Boolean,
        )
        private data class CapturedContent(val content: StoredNotificationContent?, val state: String)
        private data class PersistedNotification(
            val id: Long,
            val label: String,
            val labelSource: String,
            val persistedAt: Long,
            val closedSupersededCount: Int,
        )
        private data class ReconcileCounts(val recovered: Int = 0, val closed: Int = 0)
        private enum class ReconcileTrigger { CONNECTED, HEALTH_CHECK }
        private const val STATUS_DETAIL = "仅统计可清除的普通通知，不读取内容"
        private const val DUPLICATE_LOG_INTERVAL_MS = 5 * 60_000L
        private const val SNAPSHOT_HEALTH_INTERVAL_MS = 5 * 60_000L
        private const val SNAPSHOT_FAILURE_THRESHOLD = 2
        private const val REBIND_AFTER_UNBIND_DELAY_MS = 750L
        private const val REBIND_THROTTLE_MS = 60_000L
        private const val MAX_GENERATION_CACHE = 4_096
        private const val GENERATION_CACHE_MS = 7 * 24 * 60 * 60_000L
        private const val NOTIFICATION_GAP_START_KEY = "notification_listener_gap_started_at"
        private const val NOTIFICATION_HEARTBEAT_KEY = "notification_listener_heartbeat_at"
        private const val HEARTBEAT_PERSIST_INTERVAL_MS = 30_000L
        private const val REASON_SUPERSEDED = -100
        private const val REASON_RECONCILED = -101
        private const val REASON_COLLECTION_PAUSED = -102
        @Volatile private var connected = false
        private val connectionGeneration = AtomicLong(0L)
        private val lastCallbackAt = AtomicLong(0L)
        private val lastRebindRequestAt = AtomicLong(0L)
        private val lastPersistedHeartbeatAt = AtomicLong(0L)

        fun isConnected(): Boolean = connected

        fun persistedHeartbeatAt(settings: SettingsStore): Long = settings.getLong(NOTIFICATION_HEARTBEAT_KEY)

        fun pendingGapStartedAt(settings: SettingsStore): Long = settings.getLong(NOTIFICATION_GAP_START_KEY)

        fun lastCallbackTimestamp(): Long = lastCallbackAt.get()

        fun markDisconnected(settings: SettingsStore, nowMs: Long = System.currentTimeMillis()) {
            if (!settings.collectionEnabled || !settings.sourceEnabled(SourceId.NOTIFICATIONS)) return
            if (settings.getLong(NOTIFICATION_GAP_START_KEY) == 0L) {
                val lastHeartbeat = settings.getLong(NOTIFICATION_HEARTBEAT_KEY)
                settings.putLong(NOTIFICATION_GAP_START_KEY, lastHeartbeat.takeIf { it in 1 until nowMs } ?: nowMs)
            }
        }

        fun clearGapMarker(settings: SettingsStore) {
            settings.remove(NOTIFICATION_GAP_START_KEY)
        }

        private fun touchHeartbeat(settings: SettingsStore, nowMs: Long = System.currentTimeMillis(), force: Boolean = false) {
            val previous = lastPersistedHeartbeatAt.get()
            if (!force && nowMs - previous < HEARTBEAT_PERSIST_INTERVAL_MS) return
            if (force || lastPersistedHeartbeatAt.compareAndSet(previous, nowMs)) {
                settings.putLong(NOTIFICATION_HEARTBEAT_KEY, nowMs)
            }
        }

        fun requestReconnect(context: Context, force: Boolean = false): Boolean {
            if (connected && !force) return false
            val now = System.currentTimeMillis()
            val previous = lastRebindRequestAt.get()
            if (!force && now - previous < REBIND_THROTTLE_MS) return false
            if (!lastRebindRequestAt.compareAndSet(previous, now) && !force) return false
            return runCatching {
                requestRebind(ComponentName(context, MetadataNotificationListener::class.java))
                DiagnosticLog.event("notifications", "listener_rebind_requested", mapOf(
                    "force" to force,
                    "lastCallbackAgeMs" to lastCallbackAt.get().takeIf { it > 0 }?.let { now - it },
                ))
                true
            }.onFailure {
                DiagnosticLog.error("notifications", "listener_rebind_failed", it)
            }.getOrDefault(false)
        }
    }
}
