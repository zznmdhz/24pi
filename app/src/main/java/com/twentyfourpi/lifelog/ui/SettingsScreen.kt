package com.twentyfourpi.lifelog.ui

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.twentyfourpi.lifelog.BuildConfig
import com.twentyfourpi.lifelog.collector.CollectorWatchdogScheduler
import com.twentyfourpi.lifelog.data.SourceId
import com.twentyfourpi.lifelog.data.SourceState
import java.time.LocalDate

@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onSources: () -> Unit,
    onPlaces: () -> Unit = {},
    onAiSettings: () -> Unit = {},
    onBack: (() -> Unit)? = null,
    focusNotificationContent: Boolean = false,
) {
    val context = LocalContext.current
    val motion = LocalMotionPreference.current
    var amapKey by remember { mutableStateOf(viewModel.settings.amapKey) }
    var placeNameConsent by remember { mutableStateOf(viewModel.settings.placeNameConsent) }
    var passwordPurpose by remember { mutableStateOf<String?>(null) }
    var password by remember { mutableStateOf("") }
    var selectedRestoreUri by remember { mutableStateOf<Uri?>(null) }
    var notificationContentEnabled by remember { mutableStateOf(viewModel.settings.notificationContentEnabled) }
    var confirmNotificationContent by remember { mutableStateOf<Boolean?>(null) }
    var confirmReadableExport by remember { mutableStateOf(false) }
    var backgroundExpanded by rememberSaveable { mutableStateOf(false) }
    var placeNamingAdvanced by rememberSaveable { mutableStateOf(false) }
    val message by viewModel.operationMessage.collectAsStateWithLifecycle()
    val darkMode by viewModel.darkMode.collectAsStateWithLifecycle()
    val projectionRaw by viewModel.gapProjectionRaw.collectAsStateWithLifecycle()
    val statuses by viewModel.statuses.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val listState = rememberLazyListState()
    val powerManager = remember { context.getSystemService(PowerManager::class.java) }
    var batteryExempt by remember {
        mutableStateOf(powerManager.isIgnoringBatteryOptimizations(context.packageName))
    }
    var exactAlarmAllowed by remember {
        mutableStateOf(CollectorWatchdogScheduler.hasExactAlarmAccess(context))
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                batteryExempt = powerManager.isIgnoringBatteryOptimizations(context.packageName)
                exactAlarmAllowed = CollectorWatchdogScheduler.hasExactAlarmAccess(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(focusNotificationContent) {
        if (focusNotificationContent) listState.scrollToItem(8)
    }

    val createDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) { passwordPurpose = "backup:$uri"; password = "" }
    }
    val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { selectedRestoreUri = uri; passwordPurpose = "restore"; password = "" }
    }
    val exportDiagnostics = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) viewModel.exportDiagnostics(uri)
    }
    val exportReadableData = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) viewModel.exportReadableData(uri)
    }
    // U15：长操作（备份/导出）过程状态与结果，防重复点击。
    val operationInProgress by viewModel.operationInProgress.collectAsState()
    // R15：操作类型与失败态（备份中 CSV 不显示“正在导出”；失败样式结构化）。
    val operationType by viewModel.operationType.collectAsState()
    val operationFailed by viewModel.operationFailed.collectAsState()
    val operationMessage by viewModel.operationMessage.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                onBack?.let { back ->
                    IconButton(onClick = back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回档案") }
                }
                Text(if (focusNotificationContent) "通知记录设置" else "我的", style = MaterialTheme.typography.headlineSmall)
            }
        }
        item {
            InstrumentPanel {
                Row(
                    Modifier.fillMaxWidth().clickable(onClick = onSources).padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(if (viewModel.settings.collectionEnabled) "正在记录" else "记录已暂停", style = MaterialTheme.typography.titleMedium)
                        // U13/R08：彩点改为文字状态摘要；只把“启用中且真正需要处理”的
                        // 来源算作待检查（PAUSED/UNSUPPORTED/初始未齐不算——用户关闭或设备
                        // 不支持不是故障）。
                        if (!viewModel.settings.collectionEnabled) {
                            Text(
                                "记录已暂停，已有档案仍可查看",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            val watchSources = SourceId.entries.filter { it != SourceId.SLEEP }
                            val issueStates = setOf(SourceState.PERMISSION_REQUIRED.name, SourceState.SYSTEM_BLOCKED.name, SourceState.ERROR.name)
                            val issueCount = watchSources.count { status ->
                                val st = statuses.firstOrNull { it.source == status.name }?.state
                                st != null && st in issueStates && viewModel.settings.sourceEnabled(status)
                            }
                            val activeCount = watchSources.count { status ->
                                statuses.firstOrNull { it.source == status.name }?.state == SourceState.ACTIVE.name
                            }
                            Text(
                                when {
                                    issueCount > 0 -> "$issueCount 项需要处理（其余均在记录）"
                                    activeCount == watchSources.size -> "应用 · 通知 · 地点 · 步数均在记录"
                                    else -> "已暂停或不支持的数据源不计入待处理"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (issueCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Icon(Icons.Outlined.ChevronRight, "查看数据源", tint = MaterialTheme.colorScheme.outline)
                }
            }
        }
        item { SectionTitle("整理与回顾") }
        item { SettingsCard(Icons.Outlined.Place, "地点管理", "命名、标记和整理去过的地点", onPlaces) }
        item { SettingsCard(Icons.Outlined.Apps, "AI 配置", "连接自己的服务，回顾本机记录", onAiSettings) }
        item { SectionTitle("后台保障") }
        item {
            InstrumentPanel {
                Column(Modifier.animateContentSize(animationSpec = motion.spec(200))) {
                    Row(
                        Modifier.fillMaxWidth().clickable { backgroundExpanded = !backgroundExpanded }.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                if (batteryExempt && exactAlarmAllowed) "后台运行已基本就绪" else "还有后台保障需要设置",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                if (batteryExempt && exactAlarmAllowed) "电量限制与系统级恢复已确认" else "展开后按提示检查 HyperOS 设置",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(if (backgroundExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, if (backgroundExpanded) "收起" else "展开")
                    }
                    if (backgroundExpanded) {
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        SettingsRow(Icons.Outlined.AdminPanelSettings, "允许受限设置", "仅在通知或使用情况权限开关变灰时使用") {
                            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                        }
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        SettingsRow(Icons.Outlined.Autorenew, "允许自启动", "避免重启后采集中断；应用无法直接读取此开关状态") {
                            val intent = Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"))
                            runCatching { context.startActivity(intent) }.onFailure { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }
                        }
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        SettingsRow(
                            Icons.Outlined.BatterySaver,
                            if (batteryExempt) "电量限制已关闭" else "关闭电量限制",
                            if (batteryExempt) "系统已确认允许后台持续运行" else "当前尚未确认；精细定位需要设为无限制",
                        ) {
                            runCatching { context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))) }
                                .onFailure { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                        }
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        SettingsRow(
                            Icons.Outlined.Alarm,
                            if (exactAlarmAllowed) "系统级采集自愈已开启" else "开启系统级采集自愈",
                            if (exactAlarmAllowed) "系统可定期检查地点与通知监听" else "推荐开启；应用被 HyperOS 终止后仍可唤醒检查",
                        ) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                runCatching {
                                    context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
                                }.onFailure {
                                    context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
                                }
                            }
                        }
                    }
                }
            }
        }
        item { SectionTitle("通知记录") }
        item {
            InstrumentPanel {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text("保存通知标题和正文", style = MaterialTheme.typography.titleMedium)
                            Text(if (notificationContentEnabled) "已开启，只保存此后收到的普通通知内容" else "默认关闭，目前只统计应用、时间和数量", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = notificationContentEnabled, onCheckedChange = { confirmNotificationContent = it })
                    }
                    Text("内容可能包含聊天消息、验证码或金融信息。数据只保存在本机和你的加密备份中，不保存图片、联系人或点击动作。", style = MaterialTheme.typography.bodySmall)
                    NotificationContentExample()
                    Text("已经保存的标题和正文永久保留；关闭开关只停止保存此后收到的内容。", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item { SectionTitle("地点名称") }
        item {
            InstrumentPanel { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text("自动获取地点名称", style = MaterialTheme.typography.titleMedium)
                        Text("无需 Key。优先使用 Android 系统服务，失败时使用 OpenStreetMap；只发送已确认停留的中心坐标。", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(
                        checked = placeNameConsent,
                        // U14/T21：开关立即真正生效（写 Settings），不再需要再点保存；
                        // 名称反查顺手安排一次补全（未开 Key 时仅本地系统服务）。
                        onCheckedChange = {
                            placeNameConsent = it
                            viewModel.savePlaceNaming(it, amapKey)
                        },
                    )
                }
                TextButton(onClick = { placeNamingAdvanced = !placeNamingAdvanced }) {
                    Text(if (placeNamingAdvanced) "收起高级服务设置" else "高级服务设置")
                    Icon(if (placeNamingAdvanced) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
                }
                if (placeNamingAdvanced) {
                    Text("备用数据 © OpenStreetMap contributors · 公共服务限速并在本机缓存结果", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(
                        value = amapKey,
                        onValueChange = { amapKey = it },
                        label = { Text("高德 Web 服务 Key（可选）") },
                        supportingText = { Text("中国大陆地点需要更精确结果时再填写，不再是必需项。") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Button(onClick = { viewModel.savePlaceNaming(placeNameConsent, amapKey) }, modifier = Modifier.align(Alignment.End)) { Text(if (amapKey.isBlank()) "保存设置" else "保存并补全已有地点") }
            } }
        }
        item { SectionTitle("数据导出") }
        item {
            InstrumentPanel { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("导出可读记录", style = MaterialTheme.typography.titleMedium)
                Text("生成包含应用精确起止、通知、原始位置点、地点停留和采集缺口的 CSV 压缩包。它与诊断日志不同，也不会加密。", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(
                    onClick = { confirmReadableExport = true },
                    enabled = !operationInProgress,
                ) {
                    Icon(Icons.Outlined.Download, null); Spacer(Modifier.width(6.dp)); Text(if (operationInProgress && operationType == "export") "正在导出…" else "导出 CSV")
                }
            } }
        }
        item { SectionTitle("备份与恢复") }
        item {
            InstrumentPanel { Column(Modifier.padding(16.dp)) {
                Text("备份包含数据库、地点命名和设置；若已开启通知内容，也会一并写入。文件使用 AES-256-GCM 加密，密码遗失后无法恢复。", style = MaterialTheme.typography.bodySmall)
                Row {
                    TextButton(
                        onClick = { createDocument.launch("24pi-${LocalDate.now()}.24pi") },
                        enabled = !operationInProgress,
                    ) { Icon(Icons.Outlined.Backup, null); Spacer(Modifier.width(4.dp)); Text("创建备份") }
                    TextButton(
                        onClick = { openDocument.launch(arrayOf("application/octet-stream", "*/*")) },
                        enabled = !operationInProgress,
                    ) { Icon(Icons.Outlined.Restore, null); Spacer(Modifier.width(4.dp)); Text("恢复备份") }
                }
                // U15/R15：长操作过程状态（不伪造百分比）；只在“本块”任务进行中显示。
                if (operationInProgress && (operationType == "backup" || operationType == "restore")) {
                    UiOperationStatus(
                        stage = if (operationType == "restore") "正在恢复…" else "正在创建加密备份…",
                        message = null,
                        inProgress = true,
                    )
                }
                operationMessage?.let {
                    if (!operationInProgress && (operationType == null)) {
                        UiInlineNotice(
                            text = it,
                            tone = if (operationFailed) UiNoticeTone.ERROR else UiNoticeTone.NEUTRAL,
                        )
                    }
                }
            } }
        }
        if (BuildConfig.ENABLE_OBSERVATION_TOOLS) {
            item { SectionTitle("诊断（观察版）") }
            item {
                InstrumentPanel {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("完整观察日志正在记录", style = MaterialTheme.typography.titleMedium)
                        Text("日志分段缓冲保存，保留最近 7 天、最多约 50 MB。导出包包含系统应用事件、内部会话决策、通知生命周期、全部定位回调、地点匹配评分、断档分级和错误堆栈，以及约 100 米精度的位置；不包含通知正文、联系人、密码或地图 Key。", style = MaterialTheme.typography.bodySmall)
                        Text("看到异常时点一下标记，之后无需停留在本页面。", style = MaterialTheme.typography.labelMedium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("开始一天观察", "时间线顺序异常", "短断档提示过多", "应用记录异常", "通知疑似漏记", "地点归属错误", "到达后地点未更新", "地点出现中断", "结束一天观察").forEach { label ->
                                AssistChip(onClick = { viewModel.addDiagnosticMarker(label) }, label = { Text(label) })
                            }
                        }
                        Button(onClick = { exportDiagnostics.launch("24pi-observe-${LocalDate.now()}.zip") }) {
                            Icon(Icons.Outlined.BugReport, null); Spacer(Modifier.width(6.dp)); Text("下载诊断日志")
                        }
                    }
                }
            }
        }
        item { SectionTitle("记录解释") }
        item {
            InstrumentPanel { Row(Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text("用有效投影解释缺口", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (projectionRaw) {
                            "当前：原始缺口解释（投影已撤销）。原始记录一直在，随时可以切回投影。"
                        } else {
                            "当前：有效投影。按实测停留与到访重建缺口区间，可随时撤销回原始解释。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = !projectionRaw,
                    onCheckedChange = { enabled -> viewModel.setGapProjectionRaw(!enabled) },
                )
            } }
        }
        item { SectionTitle("隐私与数据") }
        item {
            InstrumentPanel { Column(Modifier.padding(16.dp)) {
                Text("本应用没有账号、广告、分析或崩溃上报。通知内容默认不读取；只有你单独开启后才保存。所有已经记录的数据永久保留，应用内不提供删除入口。")
            } }
        }
        item { SectionTitle("外观") }
        item {
            InstrumentPanel { Row(Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text("深色模式", style = MaterialTheme.typography.titleMedium)
                    Text(if (darkMode) "当前使用深色主题" else "当前使用浅色主题", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = darkMode, onCheckedChange = viewModel::setDarkMode)
            } }
        }
        item {
            UiSettingToggleRow(
                title = "减少动态效果",
                checked = motion.reduced.value,
                onCheckedChange = motion::setReduced,
                supportingText = if (motion.systemEnabled) "关闭页面位移与展开动画" else "系统已关闭动画，页面变化将立即显示",
            )
        }
        item { Text("24π·人生记录 ${BuildConfig.VERSION_NAME} · 本机个人版", style = MaterialTheme.typography.bodySmall) }
    }

    passwordPurpose?.let { purpose ->
        InstrumentDialog(
            title = if (purpose.startsWith("backup:")) "设置备份密码" else "输入备份密码",
            onDismiss = { passwordPurpose = null; password = "" },
            content = {
                OutlinedTextField(value = password, onValueChange = { password = it }, singleLine = true, visualTransformation = PasswordVisualTransformation(), label = { Text("至少 8 个字符") })
                if (purpose == "restore") Text("恢复会替换当前本机记录，请先另做备份。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            },
            actions = {
                TextButton(onClick = { passwordPurpose = null; password = "" }) { Text("取消") }
                Button(onClick = {
                if (purpose.startsWith("backup:")) viewModel.createBackup(Uri.parse(purpose.removePrefix("backup:")), password)
                else selectedRestoreUri?.let { viewModel.restoreBackup(it, password) }
                passwordPurpose = null; password = ""
                }, enabled = password.length >= 8) { Text(if (purpose == "restore") "确认恢复" else "创建") }
            },
        )
    }
    confirmNotificationContent?.let { target ->
        InstrumentDialog(
            title = if (target) "保存通知内容？" else "停止保存通知内容？",
            onDismiss = { confirmNotificationContent = null },
            content = { Text(if (target)
                "开启后，24π 会保存此后收到的通知标题和正文，其中可能包含敏感信息。旧通知无法补录；关闭不会影响通知数量统计。"
                else "将停止读取此后收到的通知标题和正文；已经保存的历史内容会永久保留。应用、时间和数量仍会记录。")
            },
            actions = {
                TextButton(onClick = { confirmNotificationContent = null }) { Text("取消") }
                Button(onClick = {
                    notificationContentEnabled = target
                    viewModel.setNotificationContentEnabled(target)
                    confirmNotificationContent = null
                }) { Text(if (target) "确认开启" else "停止保存") }
            },
        )
    }
    if (confirmReadableExport) InstrumentDialog(
        title = "导出未加密记录？",
        onDismiss = { confirmReadableExport = false },
        content = { Text("导出包可能包含通知正文、应用使用记录和精确停留坐标。请只保存到可信位置；需要安全存档时应使用密码加密备份。") },
        actions = {
            TextButton(onClick = { confirmReadableExport = false }) { Text("取消") }
            Button(onClick = {
                confirmReadableExport = false
                exportReadableData.launch("24pi-records-${LocalDate.now()}.zip")
            }) { Text("继续导出") }
        },
    )
    message?.let {
        if (!operationInProgress) {
            InstrumentDialog(
                title = if (operationFailed) "操作失败" else "操作完成",
                onDismiss = viewModel::clearOperationMessage,
                content = { Text(it) },
                actions = { TextButton(onClick = viewModel::clearOperationMessage) { Text("知道了") } },
            )
        }
    }
}

@Composable private fun SectionTitle(text: String) { Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }

@Composable private fun SettingsCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, detail: String, onClick: () -> Unit) {
    InstrumentPanel(Modifier.clickable(onClick = onClick)) { Row(Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null); Spacer(Modifier.width(14.dp)); Column { Text(title, style = MaterialTheme.typography.titleMedium); Text(detail, style = MaterialTheme.typography.bodySmall) }
    } }
}

@Composable private fun SettingsRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, detail: String, onClick: () -> Unit) {
    // U22-U24：统一使用 UiKit 导航行模板（触控 ≥48dp、圆角/间距一致、右箭头语义）。
    UiSettingNavigationRow(
        title = title,
        supportingText = detail,
        icon = icon,
        onClick = onClick,
    )
}
