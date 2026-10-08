@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.twentyfourpi.lifelog.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.twentyfourpi.lifelog.collector.hasPermission
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun LifeLogRoot(viewModel: MainViewModel) {
    val initialNavigation = remember { ArchiveNavigationState.initial(LocalDate.now().toEpochDay()) }
    var encodedNavigation by rememberSaveable { mutableStateOf(initialNavigation.encode()) }
    val navigation = remember(encodedNavigation) {
        ArchiveNavigationState.decode(encodedNavigation) ?: initialNavigation
    }
    val route = navigation.currentRoute
    var pendingPlaceRange by remember { mutableStateOf<Pair<LocalDate, LocalDate>?>(null) }
    var pendingPlaceRangeToken by rememberSaveable { mutableLongStateOf(0L) }
    var navigationDirection by remember { mutableIntStateOf(0) }
    val pageStateHolder = rememberSaveableStateHolder()
    val context = LocalContext.current
    val motion = rememberMotionPreference()
    val transitionProgress = remember { Animatable(1f) }
    val density = LocalDensity.current
    var permissionRevision by remember { mutableIntStateOf(0) }
    val fineLocationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionRevision++
        viewModel.refreshHealth()
    }
    val backgroundPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permissionRevision++
        viewModel.refreshHealth()
    }
    val activityPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permissionRevision++
        viewModel.refreshHealth()
    }
    val notificationDisplayPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permissionRevision++
        viewModel.refreshHealth()
    }

    fun requestFineLocation() {
        // Android 12+ requires coarse and fine location to be requested together.
        // This is one location choice, not a batch of unrelated permissions.
        fineLocationPermission.launch(
            arrayOf(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ),
        )
    }
    fun requestBackgroundLocation() {
        if (context.hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            backgroundPermission.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        } else {
            requestFineLocation()
        }
    }
    fun requestActivityRecognition() {
        activityPermission.launch(Manifest.permission.ACTIVITY_RECOGNITION)
    }
    fun requestNotificationDisplay() {
        notificationDisplayPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    fun openAppDetails() {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
    }
    fun navigate(next: ArchiveNavigationState) {
        navigationDirection = if (next.selectedRoot != navigation.selectedRoot) 0
            else next.stack().size.compareTo(navigation.stack().size)
        encodedNavigation = next.encode()
    }

    if (!viewModel.settings.onboardingCompleted) {
        OnboardingFlow(
            permissionRevision = permissionRevision,
            onUsageAccess = { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) },
            onNotificationAccess = { context.startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")) },
            onFineLocation = ::requestFineLocation,
            onNotificationDisplay = ::requestNotificationDisplay,
            onBackgroundLocation = ::requestBackgroundLocation,
            onActivityRecognition = ::requestActivityRecognition,
            onAppDetails = ::openAppDetails,
            onSourceEnabled = viewModel::setSourceEnabled,
            onComplete = { viewModel.completeOnboarding(); viewModel.setCollection(true) },
        )
        return
    }

    LaunchedEffect(route) {
        when (route) {
            is ArchiveRoute.Day -> viewModel.selectedDate.value = LocalDate.ofEpochDay(route.dateEpochDay)
            is ArchiveRoute.Chapter -> viewModel.selectedDate.value = LocalDate.ofEpochDay(route.dateEpochDay)
            is ArchiveRoute.Evidence -> viewModel.selectedDate.value = Instant.ofEpochMilli(route.startMs)
                .atZone(ZoneId.systemDefault()).toLocalDate()
            else -> Unit
        }
    }

    BackHandler(enabled = navigation.stack().size > 1) {
        navigate(navigation.pop())
    }

    LaunchedEffect(navigation.selectedRoot, route, motion.enabled) {
        if (motion.enabled) {
            transitionProgress.snapTo(0f)
            transitionProgress.animateTo(1f, tween(if (navigationDirection == 0) 180 else 240))
        } else transitionProgress.snapTo(1f)
    }

    CompositionLocalProvider(LocalMotionPreference provides motion) {
    Scaffold(
        bottomBar = {
            if (navigation.stack().size == 1) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp,
                ) {
                    ArchiveRoot.entries.forEach { item ->
                        val selected = navigation.selectedRoot == item
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navigate(
                                    if (selected) navigation.clearToRoot()
                                    else navigation.selectRoot(item)
                                )
                            },
                            icon = {
                                Icon(
                                    when (item) {
                                        ArchiveRoot.TIME -> Icons.Outlined.Schedule
                                        ArchiveRoot.ARCHIVE -> Icons.Outlined.Search
                                        ArchiveRoot.MANAGE -> Icons.Outlined.Person
                                    },
                                    contentDescription = item.navigationLabel(),
                                )
                            },
                            label = { Text(item.navigationLabel()) },
                            alwaysShowLabel = true,
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize().graphicsLayer {
            alpha = transitionProgress.value
            translationX = with(density) { 24.dp.toPx() } * navigationDirection * (1f - transitionProgress.value)
        }) {
            pageStateHolder.SaveableStateProvider("${navigation.selectedRoot}:$route") {
                when (route) {
                    is ArchiveRoute.Day -> TimeArchiveDayScreen(
                        viewModel = viewModel,
                        onSelectDate = { date ->
                            viewModel.selectedDate.value = date
                            navigate(navigation.replace(ArchiveRoute.Day(date.toEpochDay())))
                        },
                        onOpenChapter = { startMs, endMs ->
                            navigate(
                                navigation.push(
                                    ArchiveRoute.Chapter(route.dateEpochDay, startMs, endMs),
                                ),
                            )
                        },
                        onOpenTrips = { navigate(navigation.push(ArchiveRoute.Trips(route.dateEpochDay))) },
                        onOpenLocation = { navigate(navigation.push(ArchiveRoute.CurrentLocation)) },
                        onOpenTrip = { key -> navigate(navigation.push(ArchiveRoute.Trip(route.dateEpochDay, key))) },
                        onOpenCollectionHealth = { navigate(navigation.push(ArchiveRoute.Sources)) },
                        onOpenAi = { navigate(navigation.push(ArchiveRoute.AiReview)) },
                        onBack = if (navigation.stack().size > 1) ({ navigate(navigation.pop()) }) else null,
                    )
                    is ArchiveRoute.Chapter -> TimeChapterDetailScreen(
                        viewModel = viewModel,
                        chapterStartMs = route.startMs,
                        chapterEndMs = route.endMs,
                        onBack = { navigate(navigation.pop()) },
                    )
                    is ArchiveRoute.Evidence -> TimeChapterDetailScreen(
                        viewModel = viewModel,
                        chapterStartMs = route.startMs,
                        chapterEndMs = route.endMs,
                        onBack = { navigate(navigation.pop()) },
                        initialEvidenceKind = route.kind,
                        initialEvidenceId = route.id,
                        onInitialEvidenceDismiss = { navigate(navigation.pop()) },
                    )
                    is ArchiveRoute.Trips -> TripArchiveScreen(viewModel, LocalDate.ofEpochDay(route.dateEpochDay),
                        onBack = { navigate(navigation.pop()) },
                        onTrip = { navigate(navigation.push(ArchiveRoute.Trip(it.date.toEpochDay(), it.key))) })
                    is ArchiveRoute.Trip -> TripDetailScreen(viewModel, LocalDate.ofEpochDay(route.dateEpochDay), route.key,
                        onBack = { navigate(navigation.pop()) },
                        onRecords = { start, end -> navigate(navigation.push(ArchiveRoute.Chapter(route.dateEpochDay, start, end))) })
                    ArchiveRoute.CurrentLocation -> CurrentLocationScreen(viewModel, onBack = { navigate(navigation.pop()) })
                    ArchiveRoute.ArchiveHome -> ArchiveIndexScreen(
                        viewModel = viewModel,
                        initialPlaceRange = pendingPlaceRange,
                        initialPlaceRangeToken = pendingPlaceRangeToken,
                        onPlaceRangeApplied = { pendingPlaceRange = null },
                        onOpenEvidence = { result ->
                            val date = Instant.ofEpochMilli(result.startMs).atZone(ZoneId.systemDefault()).toLocalDate()
                            val kind = when (result.kind) {
                                "NOTIFICATION" -> EvidenceKind.NOTIFICATION
                                "PLACE" -> EvidenceKind.PLACE_VISIT
                                else -> EvidenceKind.APP_SESSION
                            }
                            viewModel.selectedDate.value = date
                            // U07：一次返回回到搜索结果；不再 push 两层（Chapter+Evidence）。
                            // Evidence 详情自身会显示所属时段上下文，返回直接回落搜索结果位置。
                            navigate(
                                navigation.push(
                                    ArchiveRoute.Evidence(kind, result.id, result.startMs, result.endMs),
                                ),
                            )
                        },
                        onOpenTrips = { navigate(navigation.push(ArchiveRoute.Trips(LocalDate.now().toEpochDay()))) },
                        onOpenPlaces = { navigate(navigation.push(ArchiveRoute.PlaceArchive)) },
                        onOpenAi = { navigate(navigation.push(ArchiveRoute.AiReview)) },
                        onOpenNotificationSettings = {
                            navigate(navigation.push(ArchiveRoute.NotificationSettings))
                        },
                    )
                    ArchiveRoute.PlaceArchive -> PlacesScreen(
                        viewModel = viewModel,
                        initialManagement = true,
                        onOpenVisit = { visit ->
                            val date = Instant.ofEpochMilli(visit.startMs).atZone(ZoneId.systemDefault()).toLocalDate()
                            viewModel.selectedDate.value = date
                            // U07：与搜索一致——只压一层 Evidence，返回直接回落地点列表。
                            navigate(
                                navigation.push(
                                    ArchiveRoute.Evidence(
                                        EvidenceKind.PLACE_VISIT,
                                        visit.id,
                                        visit.startMs,
                                        visit.endMs,
                                    ),
                                ),
                            )
                        },
                        onBack = { navigate(navigation.pop()) },
                    )
                    ArchiveRoute.Sources -> SourcesScreen(
                        viewModel,
                        onBack = { navigate(navigation.pop()) },
                        onUsage = { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) },
                        onNotifications = { context.startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")) },
                        onRuntimePermissions = ::requestActivityRecognition,
                        onBackgroundLocation = ::requestBackgroundLocation,
                        onAppDetails = ::openAppDetails,
                    )
                    ArchiveRoute.NotificationSettings -> NotificationContentSettingsScreen(
                        viewModel = viewModel,
                        onBack = { navigate(navigation.pop()) },
                    )
                    ArchiveRoute.ManageHome -> SettingsScreen(
                        viewModel,
                        onSources = { navigate(navigation.push(ArchiveRoute.Sources)) },
                        onPlaces = { navigate(navigation.push(ArchiveRoute.PlaceArchive)) },
                        onAiSettings = { navigate(navigation.push(ArchiveRoute.AiSettings)) },
                    )
                    ArchiveRoute.AiReview -> AiReviewScreen(
                        onBack = { navigate(navigation.pop()) },
                        onOpenSettings = { navigate(navigation.push(ArchiveRoute.AiSettings)) },
                        onOpenPlace = { id, label, from, to ->
                            val inclusiveEnd = to.minusDays(1).coerceAtLeast(from)
                            pendingPlaceRange = Pair(from, inclusiveEnd)
                            pendingPlaceRangeToken++
                            viewModel.searchByPlaceObject(id, label, from, inclusiveEnd)
                            navigate(navigation.selectRoot(ArchiveRoot.ARCHIVE).clearToRoot())
                        },
                    )
                    ArchiveRoute.AiSettings -> AiSettingsScreen(onBack = { navigate(navigation.pop()) })
                }
            }
        }
    }
    }
}

private fun ArchiveRoot.navigationLabel(): String = when (this) {
    ArchiveRoot.TIME -> "时间"
    ArchiveRoot.ARCHIVE -> "档案"
    ArchiveRoot.MANAGE -> "我的"
}
