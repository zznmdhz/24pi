package com.twentyfourpi.lifelog.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsWalk
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.twentyfourpi.lifelog.collector.hasNotificationListenerAccess
import com.twentyfourpi.lifelog.collector.hasPermission
import com.twentyfourpi.lifelog.collector.hasUsageAccess
import com.twentyfourpi.lifelog.data.SourceId

internal enum class OnboardingStep {
    PRIVACY,
    USAGE,
    NOTIFICATIONS,
    LOCATION,
    STEPS,
}

internal enum class LocationSetupAction {
    REQUEST_FINE_LOCATION,
    REQUEST_BACKGROUND_LOCATION,
    REQUEST_NOTIFICATION_DISPLAY,
    CONTINUE,
}

internal enum class StepSetupAction {
    REQUEST_NOTIFICATION_DISPLAY,
    REQUEST_ACTIVITY_RECOGNITION,
    COMPLETE,
}

internal fun locationSetupAction(
    fineLocation: Boolean,
    backgroundLocation: Boolean,
    notificationDisplay: Boolean,
): LocationSetupAction = when {
    !fineLocation -> LocationSetupAction.REQUEST_FINE_LOCATION
    !backgroundLocation -> LocationSetupAction.REQUEST_BACKGROUND_LOCATION
    !notificationDisplay -> LocationSetupAction.REQUEST_NOTIFICATION_DISPLAY
    else -> LocationSetupAction.CONTINUE
}

internal fun stepSetupAction(
    notificationDisplay: Boolean,
    activityRecognition: Boolean,
): StepSetupAction = when {
    !notificationDisplay -> StepSetupAction.REQUEST_NOTIFICATION_DISPLAY
    !activityRecognition -> StepSetupAction.REQUEST_ACTIVITY_RECOGNITION
    else -> StepSetupAction.COMPLETE
}

/**
 * First run asks for one understandable capability at a time. Optional sources
 * are disabled when skipped, so finishing onboarding never creates a wall of
 * permission errors. They can be enabled later from My > Recording items.
 */
@Composable
fun OnboardingFlow(
    permissionRevision: Int,
    onUsageAccess: () -> Unit,
    onNotificationAccess: () -> Unit,
    onFineLocation: () -> Unit,
    onNotificationDisplay: () -> Unit,
    onBackgroundLocation: () -> Unit,
    onActivityRecognition: () -> Unit,
    onAppDetails: () -> Unit,
    onSourceEnabled: (SourceId, Boolean) -> Unit,
    onComplete: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var stepName by rememberSaveable { mutableStateOf(OnboardingStep.PRIVACY.name) }
    var lifecycleRevision by remember { mutableIntStateOf(0) }
    var helpExpanded by rememberSaveable { mutableStateOf(false) }
    val step = OnboardingStep.entries.firstOrNull { it.name == stepName } ?: OnboardingStep.PRIVACY

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) lifecycleRevision++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Both revisions deliberately participate in these checks: runtime
    // permissions return through a launcher, while special access screens only
    // report their result when this activity resumes.
    val usageGranted = remember(step, permissionRevision, lifecycleRevision) { context.hasUsageAccess() }
    val notificationsGranted = remember(step, permissionRevision, lifecycleRevision) {
        context.hasNotificationListenerAccess()
    }
    val fineLocationGranted = remember(step, permissionRevision, lifecycleRevision) {
        context.hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    val backgroundLocationGranted = remember(step, permissionRevision, lifecycleRevision) {
        context.hasPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }
    val notificationDisplayGranted = remember(step, permissionRevision, lifecycleRevision) {
        context.hasPermission(Manifest.permission.POST_NOTIFICATIONS)
    }
    val activityGranted = remember(step, permissionRevision, lifecycleRevision) {
        context.hasPermission(Manifest.permission.ACTIVITY_RECOGNITION)
    }
    val hasStepSensor = remember {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_STEP_COUNTER)
    }

    fun moveTo(next: OnboardingStep) {
        helpExpanded = false
        stepName = next.name
    }

    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            OnboardingProgress(step)
            AnimatedContent(targetState = step, label = "onboarding-step", modifier = Modifier.weight(1f)) { current ->
                when (current) {
                    OnboardingStep.PRIVACY -> OnboardingPrivacyStep(
                        onContinue = { moveTo(OnboardingStep.USAGE) },
                    )

                    OnboardingStep.USAGE -> OnboardingPermissionStep(
                        icon = Icons.Outlined.Apps,
                        eyebrow = "核心记录",
                        title = "记录应用使用时间",
                        detail = "开启后，24π 才能保存应用的开始、结束和使用时长。这是还原一天手机使用情况的核心来源。",
                        skippedImpact = "稍后开启也可以；在此之前，时间线上不会有应用使用记录。",
                        granted = usageGranted,
                        grantedText = "使用情况访问已开启",
                        primaryLabel = if (usageGranted) "继续" else "打开使用情况访问",
                        onPrimary = {
                            if (usageGranted) {
                                onSourceEnabled(SourceId.USAGE, true)
                                moveTo(OnboardingStep.NOTIFICATIONS)
                            } else onUsageAccess()
                        },
                        skipLabel = "稍后开启",
                        onSkip = {
                            onSourceEnabled(SourceId.USAGE, false)
                            moveTo(OnboardingStep.NOTIFICATIONS)
                        },
                        helpExpanded = helpExpanded,
                        onHelp = { helpExpanded = !helpExpanded },
                        onAppDetails = onAppDetails,
                    )

                    OnboardingStep.NOTIFICATIONS -> OnboardingPermissionStep(
                        icon = Icons.Outlined.NotificationsNone,
                        eyebrow = "可选 · 通知",
                        title = "记录收到通知的时间",
                        detail = "开启后会保存通知来自哪个应用、何时出现以及是否移除。标题和正文仍然默认不读取。",
                        skippedImpact = "跳过后不会记录通知；应用使用、地点和步数不受影响。",
                        granted = notificationsGranted,
                        grantedText = "通知使用权已开启",
                        primaryLabel = if (notificationsGranted) "继续" else "打开通知使用权",
                        onPrimary = {
                            if (notificationsGranted) {
                                onSourceEnabled(SourceId.NOTIFICATIONS, true)
                                moveTo(OnboardingStep.LOCATION)
                            } else onNotificationAccess()
                        },
                        skipLabel = "不记录通知",
                        onSkip = {
                            onSourceEnabled(SourceId.NOTIFICATIONS, false)
                            moveTo(OnboardingStep.LOCATION)
                        },
                        helpExpanded = helpExpanded,
                        onHelp = { helpExpanded = !helpExpanded },
                        onAppDetails = onAppDetails,
                    )

                    OnboardingStep.LOCATION -> {
                        val action = locationSetupAction(
                            fineLocation = fineLocationGranted,
                            backgroundLocation = backgroundLocationGranted,
                            notificationDisplay = notificationDisplayGranted,
                        )
                        OnboardingPermissionStep(
                            icon = Icons.Outlined.LocationOn,
                            eyebrow = "可选 · 地点",
                            title = "记录停留过的地点",
                            detail = when (action) {
                                LocationSetupAction.REQUEST_FINE_LOCATION ->
                                    "先允许精确位置。可信坐标会立即保存在本机；已知地点约 1 分钟、新地点约 5 分钟形成停留。"
                                LocationSetupAction.REQUEST_BACKGROUND_LOCATION ->
                                    "精确位置已允许。下一步选择“始终允许”，锁屏或使用其他应用时才能连续记录。"
                                LocationSetupAction.REQUEST_NOTIFICATION_DISPLAY ->
                                    "位置权限已完成。最后允许显示常驻采集状态，让你始终知道后台定位何时运行。"
                                LocationSetupAction.CONTINUE ->
                                    "精确位置、后台位置和采集状态通知均已开启。"
                            },
                            skippedImpact = "跳过后时间线不会显示地点；应用使用和通知不受影响。",
                            granted = action == LocationSetupAction.CONTINUE,
                            grantedText = "地点记录已准备好",
                            primaryLabel = when (action) {
                                LocationSetupAction.REQUEST_FINE_LOCATION -> "允许精确位置"
                                LocationSetupAction.REQUEST_BACKGROUND_LOCATION -> "允许始终访问位置"
                                LocationSetupAction.REQUEST_NOTIFICATION_DISPLAY -> "允许显示采集状态"
                                LocationSetupAction.CONTINUE -> "继续"
                            },
                            onPrimary = {
                                when (action) {
                                    LocationSetupAction.REQUEST_FINE_LOCATION -> onFineLocation()
                                    LocationSetupAction.REQUEST_BACKGROUND_LOCATION -> onBackgroundLocation()
                                    LocationSetupAction.REQUEST_NOTIFICATION_DISPLAY -> onNotificationDisplay()
                                    LocationSetupAction.CONTINUE -> {
                                        onSourceEnabled(SourceId.LOCATION, true)
                                        moveTo(OnboardingStep.STEPS)
                                    }
                                }
                            },
                            skipLabel = "不记录地点",
                            onSkip = {
                                onSourceEnabled(SourceId.LOCATION, false)
                                moveTo(OnboardingStep.STEPS)
                            },
                            helpExpanded = helpExpanded,
                            onHelp = { helpExpanded = !helpExpanded },
                            onAppDetails = onAppDetails,
                        )
                    }

                    OnboardingStep.STEPS -> {
                        val action = if (!hasStepSensor) StepSetupAction.COMPLETE else stepSetupAction(
                            notificationDisplay = notificationDisplayGranted,
                            activityRecognition = activityGranted,
                        )
                        OnboardingPermissionStep(
                            icon = Icons.AutoMirrored.Outlined.DirectionsWalk,
                            eyebrow = "可选 · 最后一步",
                            title = if (hasStepSensor) "记录每天的步数" else "这台设备没有计步传感器",
                            detail = when {
                                !hasStepSensor -> "步数会显示为设备不支持，其他记录可以正常开始。"
                                action == StepSetupAction.REQUEST_NOTIFICATION_DISPLAY ->
                                    "先允许显示常驻采集状态，让你知道计步服务何时在后台运行。"
                                action == StepSetupAction.REQUEST_ACTIVITY_RECOGNITION ->
                                    "允许身体活动后，只读取设备计步传感器并按小时、按天汇总。"
                                else -> "步数记录已准备好。你之后仍可在“我的”中调整每个记录项目。"
                            },
                            skippedImpact = "跳过后不记录步数；应用、通知和地点不受影响。",
                            granted = !hasStepSensor || action == StepSetupAction.COMPLETE,
                            grantedText = if (hasStepSensor) "步数记录已准备好" else "可以开始记录",
                            primaryLabel = when (action) {
                                StepSetupAction.REQUEST_NOTIFICATION_DISPLAY -> "允许显示采集状态"
                                StepSetupAction.REQUEST_ACTIVITY_RECOGNITION -> "允许步数记录"
                                StepSetupAction.COMPLETE -> "完成并开始记录"
                            },
                            onPrimary = {
                                when (action) {
                                    StepSetupAction.REQUEST_NOTIFICATION_DISPLAY -> onNotificationDisplay()
                                    StepSetupAction.REQUEST_ACTIVITY_RECOGNITION -> onActivityRecognition()
                                    StepSetupAction.COMPLETE -> {
                                        onSourceEnabled(SourceId.STEPS, hasStepSensor && activityGranted)
                                        onComplete()
                                    }
                                }
                            },
                            skipLabel = if (hasStepSensor) "不记录步数，直接完成" else null,
                            onSkip = {
                                onSourceEnabled(SourceId.STEPS, false)
                                onComplete()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OnboardingProgress(step: OnboardingStep) {
    val position = step.ordinal + 1
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("24π · 开始记录", style = MaterialTheme.typography.titleMedium)
            Text("$position / ${OnboardingStep.entries.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LinearProgressIndicator(
            progress = { position / OnboardingStep.entries.size.toFloat() },
            modifier = Modifier.fillMaxWidth().height(3.dp).semantics {
                contentDescription = "设置进度 $position，共 ${OnboardingStep.entries.size} 步"
            },
        )
    }
}

@Composable
private fun OnboardingPrivacyStep(onContinue: () -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
        Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
            OnboardingGlyph(Icons.Outlined.Lock)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("你的记录只属于你", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "24π 没有账号、云同步、广告或行为分析。应用使用、通知和位置默认只保存在这台手机上。",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            PrivacyPromise("你可以跳过任何可选记录项目，其他功能仍然可用。")
            PrivacyPromise("通知标题和正文不会自动保存，需要你之后单独开启。")
            PrivacyPromise("已经记录的数据永久保留；你可以随时导出或创建加密备份。")
        }
        Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) { Text("了解并继续") }
    }
}

@Composable
private fun PrivacyPromise(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Icon(Icons.Outlined.Check, null, modifier = Modifier.size(19.dp), tint = MaterialTheme.colorScheme.primary)
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun OnboardingPermissionStep(
    icon: ImageVector,
    eyebrow: String,
    title: String,
    detail: String,
    skippedImpact: String,
    granted: Boolean,
    grantedText: String,
    primaryLabel: String,
    onPrimary: () -> Unit,
    skipLabel: String?,
    onSkip: () -> Unit,
    helpExpanded: Boolean = false,
    onHelp: (() -> Unit)? = null,
    onAppDetails: (() -> Unit)? = null,
) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.animateContentSize(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            OnboardingGlyph(icon)
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(eyebrow, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(title, style = MaterialTheme.typography.headlineMedium)
                Text(detail, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (granted) {
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Check, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(grantedText, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            Text(skippedImpact, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (onHelp != null && onAppDetails != null) {
                TextButton(onClick = onHelp) { Text(if (helpExpanded) "收起帮助" else "开关变灰或找不到？") }
                if (helpExpanded) {
                    InstrumentPanel {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            Text("侧载应用可能被限制", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "打开应用详情，点右上角“更多/⋮ → 允许受限设置”，再回来重试。只有遇到开关变灰时才需要这一步。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedButton(onClick = onAppDetails) { Text("打开应用详情") }
                        }
                    }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = onPrimary, modifier = Modifier.fillMaxWidth()) { Text(primaryLabel) }
            if (skipLabel != null) {
                TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) { Text(skipLabel) }
            } else {
                Spacer(Modifier.height(48.dp))
            }
        }
    }
}

@Composable
private fun OnboardingGlyph(icon: ImageVector) {
    Surface(
        modifier = Modifier.size(54.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, null, modifier = Modifier.size(26.dp), tint = MaterialTheme.colorScheme.primary)
        }
    }
}
