package com.twentyfourpi.lifelog.ui

/**
 * The three stable, top-level jobs in the archive experience.
 *
 * Each root owns an independent back stack. Selecting another root therefore
 * changes only [ArchiveNavigationState.selectedRoot]; it never discards the
 * user's place in the other roots.
 */
enum class ArchiveRoot {
    TIME,
    ARCHIVE,
    MANAGE,
}

enum class EvidenceKind {
    APP_SESSION,
    NOTIFICATION,
    PLACE_VISIT,
    LOCATION_POINT,
    COLLECTION_GAP,
}

/** Routes are facts or stable entry points; no route contains Android state. */
sealed interface ArchiveRoute {
    data class Day(val dateEpochDay: Long) : ArchiveRoute

    data class Chapter(
        val dateEpochDay: Long,
        val startMs: Long,
        val endMs: Long,
    ) : ArchiveRoute {
        init {
            require(endMs >= startMs) { "Chapter end must not precede its start" }
        }
    }

    data class Evidence(
        val kind: EvidenceKind,
        val id: Long,
        val startMs: Long,
        val endMs: Long,
    ) : ArchiveRoute {
        init {
            require(endMs >= startMs) { "Evidence end must not precede its start" }
        }
    }

    data class Trips(val dateEpochDay: Long) : ArchiveRoute
    data class Trip(val dateEpochDay: Long, val key: String) : ArchiveRoute
    data object CurrentLocation : ArchiveRoute
    data object ArchiveHome : ArchiveRoute
    data object PlaceArchive : ArchiveRoute
    data object NotificationSettings : ArchiveRoute
    data object ManageHome : ArchiveRoute
    data object Sources : ArchiveRoute
    data object AiReview : ArchiveRoute
    data object AiSettings : ArchiveRoute
}

/**
 * Immutable navigation state for the archive redesign.
 *
 * Operations apply to the selected root only. A chapter opened while ARCHIVE
 * is selected is consequently pushed onto the ARCHIVE stack; popping it
 * returns to the exact archive/index context rather than switching to TIME.
 */
class ArchiveNavigationState private constructor(
    val selectedRoot: ArchiveRoot,
    private val rootStacks: Map<ArchiveRoot, List<ArchiveRoute>>,
) {
    val currentRoute: ArchiveRoute
        get() = stack(selectedRoot).last()

    fun stack(root: ArchiveRoot = selectedRoot): List<ArchiveRoute> =
        rootStacks.getValue(root)

    fun selectRoot(root: ArchiveRoot): ArchiveNavigationState =
        if (root == selectedRoot) this else create(root, rootStacks)

    fun push(route: ArchiveRoute): ArchiveNavigationState =
        updateSelectedStack(stack() + route)

    fun pop(): ArchiveNavigationState {
        val current = stack()
        return if (current.size == 1) this else updateSelectedStack(current.dropLast(1))
    }

    /**
     * Replaces the visible route without growing history. Date paging should
     * use this operation with [ArchiveRoute.Day], not [push].
     */
    fun replace(route: ArchiveRoute): ArchiveNavigationState =
        updateSelectedStack(stack().dropLast(1) + route)

    fun clearToRoot(): ArchiveNavigationState {
        val current = stack()
        return if (current.size == 1) this else updateSelectedStack(listOf(current.first()))
    }

    /** A compact, deterministic representation suitable for SavedState or disk. */
    fun encode(): String = buildString {
        append(CODEC_VERSION)
        append('|')
        append(selectedRoot.name)
        ArchiveRoot.entries.forEach { root ->
            append('|')
            append(root.name)
            append('=')
            append(stack(root).joinToString("/") { encodeRoute(it) })
        }
    }

    private fun updateSelectedStack(stack: List<ArchiveRoute>): ArchiveNavigationState {
        val updated = rootStacks.toMutableMap().apply { put(selectedRoot, stack.toList()) }
        return create(selectedRoot, updated)
    }

    override fun equals(other: Any?): Boolean =
        other is ArchiveNavigationState &&
            selectedRoot == other.selectedRoot &&
            rootStacks == other.rootStacks

    override fun hashCode(): Int = 31 * selectedRoot.hashCode() + rootStacks.hashCode()

    override fun toString(): String = "ArchiveNavigationState(${encode()})"

    companion object {
        private const val CODEC_VERSION = "1"

        fun initial(
            todayEpochDay: Long,
            selectedRoot: ArchiveRoot = ArchiveRoot.TIME,
        ): ArchiveNavigationState = create(
            selectedRoot = selectedRoot,
            stacks = mapOf(
                ArchiveRoot.TIME to listOf(ArchiveRoute.Day(todayEpochDay)),
                ArchiveRoot.ARCHIVE to listOf(ArchiveRoute.ArchiveHome),
                ArchiveRoot.MANAGE to listOf(ArchiveRoute.ManageHome),
            ),
        )

        /** Returns null for unknown versions, malformed values, or invalid roots. */
        fun decode(encoded: String): ArchiveNavigationState? = runCatching {
            val parts = encoded.split('|')
            require(parts.size == 2 + ArchiveRoot.entries.size)
            require(parts[0] == CODEC_VERSION)
            val selectedRoot = ArchiveRoot.valueOf(parts[1])
            val stacks = parts.drop(2).associate { encodedStack ->
                val separator = encodedStack.indexOf('=')
                require(separator > 0 && separator < encodedStack.lastIndex)
                val root = ArchiveRoot.valueOf(encodedStack.substring(0, separator))
                val routes = encodedStack.substring(separator + 1)
                    .split('/')
                    .map(::decodeRoute)
                root to routes
            }
            require(stacks.size == ArchiveRoot.entries.size)
            create(selectedRoot, stacks)
        }.getOrNull()

        private fun create(
            selectedRoot: ArchiveRoot,
            stacks: Map<ArchiveRoot, List<ArchiveRoute>>,
        ): ArchiveNavigationState {
            require(stacks.keys == ArchiveRoot.entries.toSet())
            val immutableStacks = ArchiveRoot.entries.associateWith { root ->
                stacks.getValue(root).also { stack ->
                    require(stack.isNotEmpty())
                    require(validRootRoute(root, stack.first()))
                }.toList()
            }
            return ArchiveNavigationState(selectedRoot, immutableStacks)
        }

        private fun validRootRoute(root: ArchiveRoot, route: ArchiveRoute): Boolean = when (root) {
            ArchiveRoot.TIME -> route is ArchiveRoute.Day
            ArchiveRoot.ARCHIVE -> route == ArchiveRoute.ArchiveHome
            ArchiveRoot.MANAGE -> route == ArchiveRoute.ManageHome
        }

        private fun encodeRoute(route: ArchiveRoute): String = when (route) {
            is ArchiveRoute.Day -> "d,${route.dateEpochDay}"
            is ArchiveRoute.Chapter ->
                "c,${route.dateEpochDay},${route.startMs},${route.endMs}"
            is ArchiveRoute.Evidence ->
                "e,${route.kind.name},${route.id},${route.startMs},${route.endMs}"
            is ArchiveRoute.Trips -> "r,${route.dateEpochDay}"
            is ArchiveRoute.Trip -> "t,${route.dateEpochDay},${route.key}"
            ArchiveRoute.CurrentLocation -> "l"
            ArchiveRoute.ArchiveHome -> "a"
            ArchiveRoute.PlaceArchive -> "p"
            ArchiveRoute.NotificationSettings -> "n"
            ArchiveRoute.ManageHome -> "m"
            ArchiveRoute.Sources -> "s"
            ArchiveRoute.AiReview -> "i"
            ArchiveRoute.AiSettings -> "is"
        }

        private fun decodeRoute(encoded: String): ArchiveRoute {
            val fields = encoded.split(',')
            return when (fields.firstOrNull()) {
                "d" -> {
                    require(fields.size == 2)
                    ArchiveRoute.Day(fields[1].toLong())
                }
                "c" -> {
                    require(fields.size == 4)
                    ArchiveRoute.Chapter(fields[1].toLong(), fields[2].toLong(), fields[3].toLong())
                }
                "e" -> {
                    require(fields.size == 5)
                    ArchiveRoute.Evidence(
                        kind = EvidenceKind.valueOf(fields[1]),
                        id = fields[2].toLong(),
                        startMs = fields[3].toLong(),
                        endMs = fields[4].toLong(),
                    )
                }
                "r" -> { require(fields.size == 2); ArchiveRoute.Trips(fields[1].toLong()) }
                "t" -> { require(fields.size == 3); ArchiveRoute.Trip(fields[1].toLong(), fields[2]) }
                "l" -> ArchiveRoute.CurrentLocation.also { require(fields.size == 1) }
                "a" -> ArchiveRoute.ArchiveHome.also { require(fields.size == 1) }
                "p" -> ArchiveRoute.PlaceArchive.also { require(fields.size == 1) }
                "n" -> ArchiveRoute.NotificationSettings.also { require(fields.size == 1) }
                "m" -> ArchiveRoute.ManageHome.also { require(fields.size == 1) }
                "s" -> ArchiveRoute.Sources.also { require(fields.size == 1) }
                "i" -> ArchiveRoute.AiReview.also { require(fields.size == 1) }
                "is" -> ArchiveRoute.AiSettings.also { require(fields.size == 1) }
                else -> error("Unknown archive route")
            }
        }
    }
}
