package com.twentyfourpi.lifelog.ui

import com.twentyfourpi.lifelog.data.AppSessionEntity
import com.twentyfourpi.lifelog.data.CollectionGapEntity
import com.twentyfourpi.lifelog.data.NotificationEventEntity
import com.twentyfourpi.lifelog.data.PlaceVisitView
import com.twentyfourpi.lifelog.data.SourceId
import com.twentyfourpi.lifelog.data.TimelineDay

/** A range in which a merged place chapter temporarily had no place observation. */
data class TimeChapterRange(
    val startMs: Long,
    val endMs: Long,
)

/** The usage summary rendered on a chapter before the user opens raw sessions. */
data class TimeChapterAppSummary(
    val packageName: String,
    val appLabel: String,
    val durationMs: Long,
    val sessionCount: Int,
)

/**
 * A rebuildable UI projection over immutable timeline facts.
 *
 * [place] and every item in [placeVisits], [apps], [notifications] and [gaps] are
 * references to the original entities. Chapter boundaries may be clipped or merged, but
 * the source entities are never copied with altered timestamps.
 */
data class TimeChapter(
    val key: String,
    val startMs: Long,
    val endMs: Long,
    val place: PlaceVisitView?,
    val placeVisits: List<PlaceVisitView>,
    val placeContinuityGaps: List<TimeChapterRange>,
    val apps: List<AppSessionEntity>,
    val notifications: List<NotificationEventEntity>,
    val gaps: List<CollectionGapEntity>,
    val topApps: List<TimeChapterAppSummary>,
    val notificationCount: Int,
) {
    val gapSources: Set<String>
        get() = gaps.mapTo(linkedSetOf()) { it.source }

    val hasUsageGap: Boolean
        get() = SourceId.USAGE.name in gapSources

    val hasNotificationGap: Boolean
        get() = SourceId.NOTIFICATIONS.name in gapSources

    val hasLocationGap: Boolean
        get() = placeContinuityGaps.isNotEmpty() || SourceId.LOCATION.name in gapSources

    val hasStepGap: Boolean
        get() = SourceId.STEPS.name in gapSources

    val hasSleepGap: Boolean
        get() = SourceId.SLEEP.name in gapSources

    val hasAnyGap: Boolean
        get() = placeContinuityGaps.isNotEmpty() || gaps.isNotEmpty()

    fun hasGap(source: SourceId): Boolean = when (source) {
        SourceId.LOCATION -> hasLocationGap
        else -> source.name in gapSources
    }
}

/** Builds the semantic time chapters used by the day timeline. */
object TimeChapterComposer {
    const val PLACE_MERGE_GAP_MS = 10 * 60_000L
    const val ACTIVITY_CLUSTER_GAP_MS = 15 * 60_000L
    const val NOTIFICATION_DISPLAY_WINDOW_MS = 60_000L
    const val DEFAULT_TOP_APP_LIMIT = 3

    fun compose(
        day: TimelineDay,
        dayStartMs: Long,
        dayEndMs: Long,
        topAppLimit: Int = DEFAULT_TOP_APP_LIMIT,
    ): List<TimeChapter> {
        require(dayEndMs > dayStartMs) { "dayEndMs must be after dayStartMs" }
        require(topAppLimit >= 0) { "topAppLimit must not be negative" }

        val placeDrafts = placeDrafts(day.visits, dayStartMs, dayEndMs)
        val placeRanges = placeDrafts.map { TimeChapterRange(it.startMs, it.endMs) }
        val unknownDrafts = unknownDrafts(day, dayStartMs, dayEndMs, placeRanges)

        val placeChapters = placeDrafts.map { draft ->
            chapter(
                key = placeKey(draft, dayStartMs),
                startMs = draft.startMs,
                endMs = draft.endMs,
                place = draft.visits.first(),
                placeVisits = draft.visits,
                placeContinuityGaps = continuityGaps(draft.visits, draft.startMs, draft.endMs),
                day = day,
                topAppLimit = topAppLimit,
            )
        }
        val unknownChapters = unknownDrafts.map { draft ->
            chapter(
                key = unknownKey(draft, dayStartMs),
                startMs = draft.startMs,
                endMs = draft.endMs,
                place = null,
                placeVisits = emptyList(),
                placeContinuityGaps = emptyList(),
                day = day,
                topAppLimit = topAppLimit,
            )
        }

        return (placeChapters + unknownChapters).sortedWith(
            compareBy<TimeChapter> { it.startMs }
                .thenBy { it.endMs }
                .thenBy { it.key },
        )
    }

    private fun chapter(
        key: String,
        startMs: Long,
        endMs: Long,
        place: PlaceVisitView?,
        placeVisits: List<PlaceVisitView>,
        placeContinuityGaps: List<TimeChapterRange>,
        day: TimelineDay,
        topAppLimit: Int,
    ): TimeChapter {
        val apps = day.apps.filter { overlaps(it.startMs, it.endMs, startMs, endMs) }
            .sortedWith(compareBy<AppSessionEntity> { it.startMs }.thenBy { it.endMs }.thenBy { it.id })
        val notifications = day.notifications.filter { it.occurredMs in startMs until endMs }
            .sortedWith(compareBy<NotificationEventEntity> { it.occurredMs }.thenBy { it.id })
        val gaps = day.gaps.filter { overlaps(it.startMs, it.endMs, startMs, endMs) }
            .sortedWith(compareBy<CollectionGapEntity> { it.startMs }.thenBy { it.endMs }.thenBy { it.id })

        return TimeChapter(
            key = key,
            startMs = startMs,
            endMs = endMs,
            place = place,
            placeVisits = placeVisits,
            placeContinuityGaps = placeContinuityGaps,
            apps = apps,
            notifications = notifications,
            gaps = gaps,
            topApps = summarizeApps(apps, startMs, endMs, topAppLimit),
            notificationCount = notifications.count { it.action == "POSTED" },
        )
    }

    private fun summarizeApps(
        apps: List<AppSessionEntity>,
        chapterStartMs: Long,
        chapterEndMs: Long,
        limit: Int,
    ): List<TimeChapterAppSummary> {
        if (limit == 0) return emptyList()
        return apps.groupBy { it.packageName }.mapNotNull { (packageName, sessions) ->
            val durationMs = sessions.sumOf {
                (minOf(it.endMs, chapterEndMs) - maxOf(it.startMs, chapterStartMs)).coerceAtLeast(0L)
            }
            if (durationMs <= 0L) null else TimeChapterAppSummary(
                packageName = packageName,
                appLabel = sessions.first().appLabel,
                durationMs = durationMs,
                sessionCount = sessions.size,
            )
        }.sortedWith(
            compareByDescending<TimeChapterAppSummary> { it.durationMs }
                .thenBy { it.appLabel }
                .thenBy { it.packageName },
        ).take(limit)
    }

    private data class PlaceDraft(
        val visits: List<PlaceVisitView>,
        val startMs: Long,
        val endMs: Long,
    )

    private fun placeDrafts(
        visits: List<PlaceVisitView>,
        dayStartMs: Long,
        dayEndMs: Long,
    ): List<PlaceDraft> {
        val ordered = visits.filter { overlaps(it.startMs, it.endMs, dayStartMs, dayEndMs) }
            .sortedWith(compareBy<PlaceVisitView> { it.startMs }.thenBy { it.endMs }.thenBy { it.placeId }.thenBy { it.id })
        if (ordered.isEmpty()) return emptyList()

        val groups = mutableListOf<MutableList<PlaceVisitView>>()
        ordered.forEach { visit ->
            val current = groups.lastOrNull()
            val currentEnd = current?.maxOfOrNull { it.endMs }
            if (
                current != null &&
                current.last().placeId == visit.placeId &&
                currentEnd != null &&
                visit.startMs - currentEnd <= PLACE_MERGE_GAP_MS
            ) {
                current += visit
            } else {
                groups += mutableListOf(visit)
            }
        }

        val mergedDrafts = groups.mapNotNull { group ->
            val startMs = maxOf(dayStartMs, group.minOf { it.startMs })
            val endMs = minOf(dayEndMs, group.maxOf { it.endMs })
            if (endMs <= startMs) null else PlaceDraft(group.toList(), startMs, endMs)
        }
        return mergeAdjacentCanonicalPlaces(makePlaceDraftsDisjoint(mergedDrafts))
    }

    /**
     * Conflict slicing can produce adjacent fragments owned by different raw drafts of the
     * same canonical place. They are one visual chapter; raw visits stay available once each
     * so [continuityGaps] can still expose any unobserved interval inside the merged chapter.
     */
    private fun mergeAdjacentCanonicalPlaces(drafts: List<PlaceDraft>): List<PlaceDraft> {
        if (drafts.size < 2) return drafts
        val result = mutableListOf<PlaceDraft>()
        drafts.sortedWith(compareBy<PlaceDraft> { it.startMs }.thenBy { it.endMs }).forEach { draft ->
            val previous = result.lastOrNull()
            if (
                previous != null &&
                previous.endMs == draft.startMs &&
                previous.visits.first().placeId == draft.visits.first().placeId
            ) {
                val visits = (previous.visits + draft.visits)
                    .distinctBy { PlaceVisitIdentity(it.id, it.placeId, it.startMs, it.endMs) }
                    .sortedWith(compareBy<PlaceVisitView> { it.startMs }.thenBy { it.endMs }.thenBy { it.id })
                result[result.lastIndex] = PlaceDraft(
                    visits = visits,
                    startMs = previous.startMs,
                    endMs = draft.endMs,
                )
            } else {
                result += draft
            }
        }
        return result
    }

    private data class PlaceVisitIdentity(
        val id: Long,
        val placeId: Long,
        val startMs: Long,
        val endMs: Long,
    )

    /**
     * Conflicting place observations are kept as raw evidence, but the UI projection must
     * have one place at any instant. Higher-confidence evidence wins; equal-confidence
     * conflicts prefer the later-starting observation, which models a place transition.
     */
    private fun makePlaceDraftsDisjoint(drafts: List<PlaceDraft>): List<PlaceDraft> {
        if (drafts.size < 2) return drafts
        val indexed = drafts.withIndex().toList()
        val boundaries = drafts.flatMap { listOf(it.startMs, it.endMs) }.distinct().sorted()
        data class Slice(val owner: Int, val startMs: Long, val endMs: Long)

        val slices = mutableListOf<Slice>()
        boundaries.zipWithNext().forEach { (startMs, endMs) ->
            if (endMs <= startMs) return@forEach
            val candidates = indexed.filter { (_, draft) ->
                overlaps(draft.startMs, draft.endMs, startMs, endMs)
            }
            if (candidates.isEmpty()) return@forEach
            val winner = candidates.maxWith(
                compareBy<IndexedValue<PlaceDraft>> {
                    confidenceDuring(it.value, startMs, endMs)
                }.thenBy { it.value.startMs }
                    .thenBy { it.value.visits.first().id }
                    .thenBy { it.value.visits.first().placeId },
            )
            val previous = slices.lastOrNull()
            if (previous != null && previous.owner == winner.index && previous.endMs == startMs) {
                slices[slices.lastIndex] = previous.copy(endMs = endMs)
            } else {
                slices += Slice(winner.index, startMs, endMs)
            }
        }
        return slices.map { slice ->
            val owner = drafts[slice.owner]
            PlaceDraft(owner.visits, slice.startMs, slice.endMs)
        }
    }

    private fun confidenceDuring(draft: PlaceDraft, startMs: Long, endMs: Long): Float =
        draft.visits.filter { overlaps(it.startMs, it.endMs, startMs, endMs) }
            .maxOfOrNull { it.confidence }
            ?: draft.visits.maxOf { it.confidence }

    private fun continuityGaps(
        visits: List<PlaceVisitView>,
        dayStartMs: Long,
        dayEndMs: Long,
    ): List<TimeChapterRange> {
        val ordered = visits.sortedWith(compareBy<PlaceVisitView> { it.startMs }.thenBy { it.endMs })
        if (ordered.size < 2) return emptyList()
        val result = mutableListOf<TimeChapterRange>()
        var coveredUntil = maxOf(dayStartMs, minOf(dayEndMs, ordered.first().endMs))
        ordered.drop(1).forEach { visit ->
            val nextStart = maxOf(dayStartMs, minOf(dayEndMs, visit.startMs))
            if (nextStart > coveredUntil) result += TimeChapterRange(coveredUntil, nextStart)
            coveredUntil = maxOf(coveredUntil, maxOf(dayStartMs, minOf(dayEndMs, visit.endMs)))
        }
        return result.filter { it.endMs > it.startMs }
    }

    private enum class SeedKind { APP, NOTIFICATION, GAP }

    private data class Seed(
        val startMs: Long,
        val endMs: Long,
        val kind: SeedKind,
        val token: String,
    )

    private data class UnknownDraft(
        val seeds: List<Seed>,
        val startMs: Long,
        val endMs: Long,
    )

    private fun unknownDrafts(
        day: TimelineDay,
        dayStartMs: Long,
        dayEndMs: Long,
        placeRanges: List<TimeChapterRange>,
    ): List<UnknownDraft> {
        val seeds = buildList {
            day.apps.forEach { app ->
                subtractPlaces(app.startMs, app.endMs, dayStartMs, dayEndMs, placeRanges).forEach { range ->
                    add(Seed(range.startMs, range.endMs, SeedKind.APP, "a:${app.id}:${app.packageName}:${app.startMs}"))
                }
            }
            day.notifications.forEach { notification ->
                val atMs = notification.occurredMs
                if (atMs in dayStartMs until dayEndMs && placeRanges.none { atMs in it.startMs until it.endMs }) {
                    add(
                        Seed(
                            startMs = atMs,
                            endMs = minOf(dayEndMs, safeAdd(atMs, 1L)),
                            kind = SeedKind.NOTIFICATION,
                            token = "n:${notification.id}:${notification.notificationKeyHash.orEmpty()}:${notification.packageName}:$atMs",
                        ),
                    )
                }
            }
            day.gaps.forEach { gap ->
                subtractPlaces(gap.startMs, gap.endMs, dayStartMs, dayEndMs, placeRanges).forEach { range ->
                    add(Seed(range.startMs, range.endMs, SeedKind.GAP, "g:${gap.id}:${gap.source}:${gap.startMs}"))
                }
            }
        }.filter { it.endMs > it.startMs }.sortedWith(
            compareBy<Seed> { it.startMs }.thenBy { it.endMs }.thenBy { it.kind.ordinal }.thenBy { it.token },
        )
        if (seeds.isEmpty()) return emptyList()

        val groups = mutableListOf<MutableList<Seed>>()
        seeds.forEach { seed ->
            val current = groups.lastOrNull()
            val currentEnd = current?.maxOfOrNull { it.endMs }
            val placeBetween = if (currentEnd == null) false else placeRanges.any {
                it.startMs < seed.startMs && it.endMs > currentEnd
            }
            if (
                current != null &&
                currentEnd != null &&
                seed.startMs - currentEnd <= ACTIVITY_CLUSTER_GAP_MS &&
                !placeBetween
            ) {
                current += seed
            } else {
                groups += mutableListOf(seed)
            }
        }

        return groups.map { group ->
            val startMs = group.minOf { it.startMs }
            val observedEndMs = group.maxOf { it.endMs }
            val endMs = if (group.all { it.kind == SeedKind.NOTIFICATION }) {
                val desiredEndMs = safeAdd(group.maxOf { it.startMs }, NOTIFICATION_DISPLAY_WINDOW_MS)
                val nextPlaceStartMs = placeRanges.asSequence()
                    .map { it.startMs }
                    .filter { it > startMs }
                    .minOrNull()
                    ?: dayEndMs
                maxOf(observedEndMs, minOf(dayEndMs, nextPlaceStartMs, desiredEndMs))
            } else {
                observedEndMs
            }
            UnknownDraft(group.toList(), startMs, endMs)
        }.filter { it.endMs > it.startMs }
    }

    private fun subtractPlaces(
        rawStartMs: Long,
        rawEndMs: Long,
        dayStartMs: Long,
        dayEndMs: Long,
        placeRanges: List<TimeChapterRange>,
    ): List<TimeChapterRange> {
        val startMs = maxOf(rawStartMs, dayStartMs)
        val endMs = minOf(rawEndMs, dayEndMs)
        if (endMs <= startMs) return emptyList()

        val result = mutableListOf<TimeChapterRange>()
        var cursor = startMs
        placeRanges.sortedBy { it.startMs }.forEach { place ->
            if (place.endMs <= cursor || place.startMs >= endMs) return@forEach
            if (place.startMs > cursor) result += TimeChapterRange(cursor, minOf(place.startMs, endMs))
            cursor = maxOf(cursor, place.endMs)
            if (cursor >= endMs) return@forEach
        }
        if (cursor < endMs) result += TimeChapterRange(cursor, endMs)
        return result.filter { it.endMs > it.startMs }
    }

    private fun placeKey(draft: PlaceDraft, dayStartMs: Long): String {
        val first = draft.visits.minWith(compareBy<PlaceVisitView> { it.startMs }.thenBy { it.id })
        return "place:$dayStartMs:${first.placeId}:${first.id}:${first.startMs}:${draft.startMs}"
    }

    private fun unknownKey(draft: UnknownDraft, dayStartMs: Long): String {
        val anchor = draft.seeds.minWith(compareBy<Seed> { it.startMs }.thenBy { it.kind.ordinal }.thenBy { it.token })
        return "unknown:$dayStartMs:${draft.startMs}:${anchor.token}"
    }

    private fun overlaps(startMs: Long, endMs: Long, rangeStartMs: Long, rangeEndMs: Long): Boolean =
        startMs < rangeEndMs && endMs > rangeStartMs

    private fun safeAdd(value: Long, delta: Long): Long =
        if (value > Long.MAX_VALUE - delta) Long.MAX_VALUE else value + delta
}

/** Day screen presentation order: the current/latest chapter comes first while chapter internals stay chronological. */
internal fun newestFirstTimeChapters(chapters: List<TimeChapter>): List<TimeChapter> =
    chapters.sortedWith(compareByDescending<TimeChapter> { it.startMs }.thenByDescending { it.endMs }.thenBy { it.key })
