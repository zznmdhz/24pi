package com.twentyfourpi.lifelog.collector

import android.location.Location
import androidx.room.withTransaction
import com.twentyfourpi.lifelog.data.*
import com.twentyfourpi.lifelog.util.distanceMeters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.twentyfourpi.lifelog.debug.DiagnosticLog
import kotlin.math.round

class LocationRecorder(
    private val provider: DatabaseProvider,
    private val repository: LifeLogRepository,
    private val settings: SettingsStore,
    private val geocoder: PlaceNameResolver,
    private val backgroundScope: CoroutineScope,
) {
    private var candidate: DwellCandidate? = restoreOrMigrateCandidate()
    private val processMutex = Mutex()
    private var lastRealFixElapsedRealtimeNanos = 0L

    suspend fun record(
        location: Location,
        traceId: String,
        receivedAtMs: Long = System.currentTimeMillis(),
    ) = processMutex.withLock {
        process(location, persistRawPoint = true, traceId = traceId, receivedAtMs = receivedAtMs)
    }

    suspend fun heartbeat(
        location: Location,
        traceId: String,
        receivedAtMs: Long = System.currentTimeMillis(),
    ) = processMutex.withLock {
        // v0.15 A3：lastknown/缓存心跳只允许延续已有候选，不参与新停留归属。
        val current = candidate
        val action = DwellTracker.heartbeat(
            current, receivedAtMs, location.latitude, location.longitude,
        )
        when (action) {
            is DwellAction.Continue -> {
                candidate = action.candidate
                persist(action.candidate)
                repository.setStatus(
                    SourceId.LOCATION, SourceState.ACTIVE,
                    "静默期心跳：停留保持中，已持续 ${(action.candidate.lastMs - action.candidate.startMs) / 60_000} 分钟",
                    touched = true,
                )
            }
            is DwellAction.Reset -> {
                // 心跳无法归属到任何候选（无候选/超时/出半径）——直接丢弃，
                // 绝不用旧缓存位置开新候选（旧逻辑的串位根源）。
                DiagnosticLog.event("location", "heartbeat_discarded", mapOf(
                    "hadCandidate" to (current != null),
                    "candidateAgeMs" to current?.let { receivedAtMs - it.lastMs },
                ))
            }
        }
    }

    private suspend fun process(location: Location, persistRawPoint: Boolean, traceId: String, receivedAtMs: Long) {
        val now = receivedAtMs
        val elapsedNanos = location.elapsedRealtimeNanos
        if (persistRawPoint && elapsedNanos > 0L && elapsedNanos <= lastRealFixElapsedRealtimeNanos) {
            DiagnosticLog.event("location", "duplicate_or_out_of_order_fix_ignored", mapOf(
                "traceId" to traceId,
                "elapsedRealtimeNanos" to elapsedNanos,
                "previousElapsedRealtimeNanos" to lastRealFixElapsedRealtimeNanos,
                "provider" to location.provider,
                "accuracyM" to location.accuracy,
            ))
            return
        }
        if (persistRawPoint && elapsedNanos > 0L) lastRealFixElapsedRealtimeNanos = elapsedNanos
        val candidateBefore = candidate
        var statusDetail = "已定位，正在判断停留"
        if (persistRawPoint) {
            val pointId = provider.get().dao().insertLocationPoint(LocationPointEntity(
                recordedMs = now, latitude = location.latitude, longitude = location.longitude,
                accuracyM = location.accuracy, provider = location.provider ?: "unknown",
                measuredMs = location.time.takeIf { it > 0 } ?: now,
                elapsedRealtimeNanos = location.elapsedRealtimeNanos,
                speedMps = location.speed.takeIf { location.hasSpeed() && it.isFinite() && it >= 0f },
                isMock = location.isMock,
            ))
            DiagnosticLog.event("location", "raw_point_persisted", mapOf(
                "traceId" to traceId,
                "pointId" to pointId,
                "recordedMs" to now,
                "measuredMs" to location.time,
                "elapsedRealtimeNanos" to location.elapsedRealtimeNanos,
                "provider" to location.provider,
                "accuracyM" to location.accuracy,
                "speedMps" to location.speed.takeIf { location.hasSpeed() },
                "isMock" to location.isMock,
            ))
        }
        val distanceFromCandidate = candidate?.let {
            distanceMeters(it.latitude, it.longitude, location.latitude, location.longitude)
        }
        if (location.hasSpeed() && location.speed > MOVING_SPEED_MPS &&
            (distanceFromCandidate == null || distanceFromCandidate > DwellTracker.RADIUS_METERS)
        ) {
            clearCandidate()
            DiagnosticLog.event("location", "dwell_decision", observationFields(
                traceId, location, persistRawPoint, now, candidateBefore, distanceFromCandidate,
            ) + mapOf(
                "decision" to "SUPPRESSED_MOVING",
                "speedMps" to location.speed,
                "candidateAfter" to "NONE",
            ))
            repository.setStatus(SourceId.LOCATION, SourceState.ACTIVE, "检测到持续移动，暂不形成停留", touched = true)
            return
        }
        val dao = provider.get().dao()
        val knownPlace = dao.activePlaces().any {
            distanceMeters(it.latitude, it.longitude, location.latitude, location.longitude) <= DwellTracker.RADIUS_METERS
        }
        val requiredDwellMs = if (knownPlace) DwellTracker.KNOWN_PLACE_DWELL_MS else DwellTracker.NEW_PLACE_DWELL_MS
        // 已经确认的停留不应被单个低精度点切断。定位精度越差，给予有限的连续性容差；
        // 未确认候选仍使用严格半径，避免快速路过被误判为停留。
        val continuityRadius = if (candidate?.visitId != 0L) {
            (DwellTracker.RADIUS_METERS + location.accuracy.coerceAtLeast(0f) * 1.5).coerceAtMost(MAX_CONFIRMED_RADIUS_METERS)
        } else DwellTracker.RADIUS_METERS
        settings.putLong("dwell_required_ms", requiredDwellMs)
        when (val action = DwellTracker.update(
            candidate, now, location.latitude, location.longitude, requiredDwellMs, continuityRadius,
        )) {
            is DwellAction.Reset -> {
                candidate = action.next
                persist(action.next)
                statusDetail = "实时位置已记录 · ${if (knownPlace) "已知地点约1分钟确认" else "新地点约5分钟确认"}"
                DiagnosticLog.event("location", "dwell_candidate_reset", mapOf(
                    "previousDurationMs" to action.previous?.let { it.lastMs - it.startMs },
                    "provider" to location.provider, "accuracyM" to location.accuracy,
                    "lat3" to rounded(location.latitude), "lon3" to rounded(location.longitude),
                ))
                DiagnosticLog.event("location", "dwell_decision", observationFields(
                    traceId, location, persistRawPoint, now, candidateBefore, distanceFromCandidate,
                ) + mapOf(
                    "decision" to "RESET_CANDIDATE",
                    "knownPlace" to knownPlace,
                    "requiredDwellMs" to requiredDwellMs,
                    "continuityRadiusM" to continuityRadius,
                    "candidateAfterStartMs" to action.next.startMs,
                    "candidateAfterSamples" to action.next.samples,
                    "candidateAfterVisitId" to action.next.visitId,
                ))
            }
            is DwellAction.Continue -> {
                var next = action.candidate
                if (action.becameVisit) next = createVisit(next)
                else if (next.visitId != 0L) {
                    val visit = dao.visitById(next.visitId) ?: dao.visitByPlaceAndStart(next.placeId, next.startMs)
                    if (visit != null) {
                        if (visit.endMs < now) dao.updateVisit(visit.copy(endMs = now))
                        if (visit.id != next.visitId) next = next.copy(visitId = visit.id)
                    } else {
                        // 数据库升级去重或异常恢复后，候选状态可能仍引用已经移除的旧 ID。
                        next = createVisit(next.copy(visitId = 0L))
                    }
                }
                candidate = next
                persist(next)
                val durationMs = next.lastMs - next.startMs
                statusDetail = if (next.visitId != 0L) {
                    "停留已识别，已持续 ${durationMs / 60_000} 分钟"
                } else {
                    val minutes = durationMs / 60_000
                    val seconds = durationMs / 1_000 % 60
                    val phase = if (!knownPlace && durationMs < DwellTracker.PROVISIONAL_MS) "临时位置" else "候选停留"
                    "$phase：${minutes}分${seconds}秒 / ${requiredDwellMs / 60_000}分钟"
                }
                DiagnosticLog.event("location", "dwell_candidate_updated", mapOf(
                    "durationMs" to (next.lastMs - next.startMs), "samples" to next.samples,
                    "visitCreated" to (next.visitId != 0L), "provider" to location.provider,
                    "accuracyM" to location.accuracy, "heartbeat" to !persistRawPoint,
                ))
                DiagnosticLog.event("location", "dwell_decision", observationFields(
                    traceId, location, persistRawPoint, now, candidateBefore, distanceFromCandidate,
                ) + mapOf(
                    "decision" to when {
                        action.becameVisit -> "CONFIRMED_VISIT"
                        next.visitId != 0L -> "EXTENDED_VISIT"
                        else -> "CONTINUED_CANDIDATE"
                    },
                    "knownPlace" to knownPlace,
                    "requiredDwellMs" to requiredDwellMs,
                    "continuityRadiusM" to continuityRadius,
                    "candidateAfterStartMs" to next.startMs,
                    "candidateAfterLastMs" to next.lastMs,
                    "candidateAfterSamples" to next.samples,
                    "candidateAfterVisitId" to next.visitId,
                    "candidateAfterPlaceId" to next.placeId,
                ))
            }
        }
        repository.setStatus(SourceId.LOCATION, SourceState.ACTIVE, statusDetail, touched = true)
    }

    private suspend fun createVisit(value: DwellCandidate): DwellCandidate {
        val write = provider.get().withTransaction {
            val dao = provider.get().dao()
            val activePlaces = dao.activePlaces()
            val place = selectWeightedPlaceForCandidate(activePlaces, value, value.lastMs)
            val createdPlaceId = if (place == null) dao.insertPlace(PlaceEntity(
                name = "未命名地点", latitude = value.latitude, longitude = value.longitude,
            )) else null
            val placeId = place?.id ?: requireNotNull(createdPlaceId)
            val visit = PlaceVisitEntity(
                placeId = placeId, startMs = value.startMs, endMs = value.lastMs, confidence = .9f,
            )
            val insertedId = dao.insertVisit(visit)
            val persistedVisit = if (insertedId == -1L) {
                dao.visitByPlaceAndStart(placeId, value.startMs)?.also { existing ->
                    if (value.lastMs > existing.endMs) dao.updateVisit(existing.copy(endMs = value.lastMs))
                } ?: error("停留记录冲突后无法读取已有记录")
            } else {
                // 只有真正插入了一次独立停留，才更新访问统计。新地点第一次停留从 1 开始，
                // 冲突恢复不会虚增权重。
                dao.placeById(placeId)?.let { current ->
                    dao.updatePlace(current.copy(
                        visitCount = current.visitCount + 1,
                        lastVisitMs = maxOf(current.lastVisitMs, value.lastMs),
                    ))
                }
                visit.copy(id = insertedId)
            }
            VisitWriteResult(
                place = place,
                createdPlaceId = createdPlaceId,
                placeId = placeId,
                visitId = persistedVisit.id,
                reusedExistingVisit = insertedId == -1L,
                resultingVisitCount = dao.placeById(placeId)?.visitCount ?: 0,
            )
        }
        DiagnosticLog.event("location", "place_visit_created", mapOf(
            "placeId" to write.placeId, "visitId" to write.visitId,
            "durationMs" to (value.lastMs - value.startMs),
            "matchedExistingPlace" to (write.place != null),
            "createdNewPlace" to (write.createdPlaceId != null),
            "reusedExistingVisit" to write.reusedExistingVisit,
            "placeTransientBefore" to write.place?.isTransientAt(value.lastMs),
            "placeVisitCountBefore" to write.place?.visitCount,
            "placeVisitCountAfter" to write.resultingVisitCount,
            "realSamples" to value.samples,
        ))
        // 地址服务可能需要十几秒，不能让网络请求阻塞定位状态机和后续位置回调。
        if (write.createdPlaceId != null) enrichPlaceInBackground(write.createdPlaceId, value.latitude, value.longitude)
        else if (write.place?.address.isNullOrBlank() && geocoder.enabled()) geocoder.scheduleBackfill()
        return value.copy(visitId = write.visitId, placeId = write.placeId)
    }

    /**
     * v0.15 加权地点匹配：修复"回家被记成制衣厂"类错绑。
     *
     * 旧逻辑在半径内的所有已知点里取最近的一个——临时固化点（洗车店/充电站）
     * 与常驻地坐标接近时就会抢走归属。新逻辑对每个候选点计算权重分：
     *
     *   score = visitCount / (distanceMeters + 50)
     *
     * 访问次数是主因子（去得多的地方权重线性增长），距离做平滑惩罚。
     * 临时点（从未形成第二次停留、或30天没再去）分数天然趋近于零，
     * 只有当常驻地完全不在附近时才会胜出。同分时取更近者，保持旧行为兜底。
     */
    private data class VisitWriteResult(
        val place: PlaceEntity?,
        val createdPlaceId: Long?,
        val placeId: Long,
        val visitId: Long,
        val reusedExistingVisit: Boolean,
        val resultingVisitCount: Int,
    )

    private fun enrichPlaceInBackground(placeId: Long, latitude: Double, longitude: Double) {
        if (!geocoder.enabled()) {
            DiagnosticLog.event("location", "reverse_geocode_skipped", mapOf("reason" to "no_place_name_consent", "placeId" to placeId))
            return
        }
        backgroundScope.launch(Dispatchers.IO) {
            val result = runCatching { geocoder.reverse(latitude, longitude) }
                .onFailure { DiagnosticLog.error("location", "reverse_geocode_failed", it, mapOf("placeId" to placeId)) }
                .getOrNull()
            if (result != null) {
                provider.get().dao().placeById(placeId)?.let { current ->
                    provider.get().dao().updatePlace(current.copy(
                        name = if (current.name == "未命名地点") result.name else current.name,
                        address = result.address,
                    ))
                }
            }
            DiagnosticLog.event(
                "location",
                "reverse_geocode_finished",
                mapOf("success" to (result != null), "placeId" to placeId, "provider" to result?.provider),
            )
            if (result == null) geocoder.scheduleBackfill()
        }
    }

    private fun persist(value: DwellCandidate) {
        settings.putLong("dwell_start", value.startMs); settings.putLong("dwell_last", value.lastMs)
        settings.putString("dwell_lat", value.latitude.toString()); settings.putString("dwell_lon", value.longitude.toString())
        settings.putLong("dwell_samples", value.samples.toLong()); settings.putLong("dwell_visit", value.visitId)
        settings.putLong("dwell_place", value.placeId)
    }

    private fun clearCandidate() {
        candidate = null
        settings.remove("dwell_start", "dwell_last", "dwell_lat", "dwell_lon", "dwell_samples", "dwell_visit", "dwell_place", "dwell_required_ms")
    }

    private fun restoreCandidate(): DwellCandidate? {
        val start = settings.getLong("dwell_start")
        if (start == 0L) return null
        val last = settings.getLong("dwell_last")
        if (last == 0L || System.currentTimeMillis() - last > MAX_CANDIDATE_RESUME_GAP_MS) {
            settings.remove("dwell_start", "dwell_last", "dwell_lat", "dwell_lon", "dwell_samples", "dwell_visit", "dwell_place", "dwell_required_ms")
            DiagnosticLog.event("location", "stale_dwell_candidate_closed", mapOf(
                "lastSampleAgeMs" to last.takeIf { it > 0L }?.let { System.currentTimeMillis() - it },
            ))
            return null
        }
        return DwellCandidate(start, last, settings.getString("dwell_lat").toDoubleOrNull() ?: return null,
            settings.getString("dwell_lon").toDoubleOrNull() ?: return null, settings.getLong("dwell_samples").toInt(),
            settings.getLong("dwell_visit"), settings.getLong("dwell_place"))
    }

    private fun restoreOrMigrateCandidate(): DwellCandidate? {
        if (settings.getBoolean("dwell_v2_migrated")) return restoreCandidate()
        // 旧版本可能把设备上报的陈旧时间写入候选停留。升级后丢弃候选状态，保留已形成的地点与访问。
        settings.remove("dwell_start", "dwell_last", "dwell_lat", "dwell_lon", "dwell_samples", "dwell_visit", "dwell_place")
        settings.putBoolean("dwell_v2_migrated", true)
        DiagnosticLog.event("location", "legacy_dwell_candidate_cleared")
        return null
    }

    private fun rounded(value: Double): Double = round(value * 1_000.0) / 1_000.0

    private fun observationFields(
        traceId: String,
        location: Location,
        persistRawPoint: Boolean,
        receivedAtMs: Long,
        before: DwellCandidate?,
        distanceFromCandidate: Double?,
    ): Map<String, Any?> = mapOf(
        "traceId" to traceId,
        "receivedAtMs" to receivedAtMs,
        "locationTimeMs" to location.time,
        "provider" to location.provider,
        "accuracyM" to location.accuracy,
        "hasSpeed" to location.hasSpeed(),
        "speedMps" to location.speed.takeIf { location.hasSpeed() },
        "latitudeApprox" to rounded(location.latitude),
        "longitudeApprox" to rounded(location.longitude),
        "rawPointPersisted" to persistRawPoint,
        "distanceFromCandidateM" to distanceFromCandidate,
        "candidateBeforeStartMs" to before?.startMs,
        "candidateBeforeLastMs" to before?.lastMs,
        "candidateBeforeSamples" to before?.samples,
        "candidateBeforeVisitId" to before?.visitId,
        "candidateBeforePlaceId" to before?.placeId,
    )

    companion object {
        private const val MOVING_SPEED_MPS = 2.5f
        private const val MAX_CONFIRMED_RADIUS_METERS = 450.0
        private const val MAX_CANDIDATE_RESUME_GAP_MS = 10 * 60_000L
    }
}

internal fun selectWeightedPlaceForCandidate(
    places: List<PlaceEntity>,
    value: DwellCandidate,
    nowMs: Long,
): PlaceEntity? {
    val scoredCandidates = places.mapNotNull { place ->
        val distance = distanceMeters(place.latitude, place.longitude, value.latitude, value.longitude)
        if (distance <= PLACE_MATCH_RADIUS_METERS) {
            val transient = place.isTransientAt(nowMs)
            val transientPenalty = if (transient) 0.25 else 1.0
            val score = place.visitCount.coerceAtLeast(1) / (distance + PLACE_SCORE_DISTANCE_SMOOTHING_M) * transientPenalty
            WeightedPlaceCandidate(place, distance, score, transient)
        } else null
    }
    // 250 米只用于“附近已有一个候选，但稳定常驻点被漂移推到 200 米外”的救援场景。
    // 如果 200 米内完全没有地点，仍创建新地点，避免把距家 240 米的新咖啡店强行归到家。
    val candidates = if (scoredCandidates.any { it.distanceM <= DwellTracker.RADIUS_METERS }) {
        scoredCandidates
    } else {
        emptyList()
    }
    DiagnosticLog.event("location", "place_match_scored", mapOf(
        "scoredCandidateCount" to scoredCandidates.size,
        "candidateCount" to candidates.size,
        "candidateIds" to candidates.joinToString("|") { it.place.id.toString() },
        "candidateDistancesM" to candidates.joinToString("|") { round(it.distanceM).toLong().toString() },
        "candidateScores" to candidates.joinToString("|") { "%.6f".format(java.util.Locale.US, it.score) },
        "candidateVisitCounts" to candidates.joinToString("|") { it.place.visitCount.toString() },
        "candidateTransient" to candidates.joinToString("|") { it.transient.toString() },
        "selectedPlaceId" to candidates.maxWithOrNull(compareBy<WeightedPlaceCandidate> { it.score }.thenBy { -it.distanceM })?.place?.id,
        "matchRadiusM" to PLACE_MATCH_RADIUS_METERS,
        "extendedRadiusActivated" to candidates.any { it.distanceM > DwellTracker.RADIUS_METERS },
    ))
    return candidates.maxWithOrNull(
        compareBy<WeightedPlaceCandidate> { it.score }.thenBy { -it.distanceM },
    )?.place
}

private data class WeightedPlaceCandidate(
    val place: PlaceEntity,
    val distanceM: Double,
    val score: Double,
    val transient: Boolean,
)

private const val PLACE_MATCH_RADIUS_METERS = 250.0
private const val PLACE_SCORE_DISTANCE_SMOOTHING_M = 50.0
