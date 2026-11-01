package cn.greenhouse.twin.ui.monitoring

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import cn.greenhouse.twin.domain.ActuatorKey
import cn.greenhouse.twin.domain.GreenhouseTwinState
import cn.greenhouse.twin.domain.ObservationSource
import cn.greenhouse.twin.domain.history.HistoryMetric
import cn.greenhouse.twin.domain.monitoring.*
import cn.greenhouse.twin.ui.components.*
import cn.greenhouse.twin.ui.scene.SceneObject
import cn.greenhouse.twin.ui.theme.GreenhouseColors as C
import cn.greenhouse.twin.ui.theme.GreenhouseDimensions as D
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@Composable
fun AlarmCenterScreen(
    liveState: GreenhouseTwinState,
    monitoringState: MonitoringUiState,
    onAcknowledge: (String) -> Unit,
    onThresholds: () -> Unit,
    onLocate: (SceneObject) -> Unit,
    onTrend: (HistoryMetric) -> Unit,
    onControl: (ActuatorKey) -> Unit,
    onNavigate: (String) -> Unit,
) {
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val source = if (showHistory) monitoringState.historicalAlarms else monitoringState.currentAlarms
    val alarms = remember(source) {
        source.sortedWith(compareByDescending<AlarmEpisode> { it.severity.ordinal }
            .thenByDescending { it.lastObservedAtUtcMs })
    }
    Scaffold(containerColor = C.Background, contentColor = C.Text,
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = { GreenhouseNavigation(liveState, "报警", onNavigate) }) { insets ->
        LazyColumn(Modifier.fillMaxSize().padding(insets),
            contentPadding = PaddingValues(horizontal = D.PageMargin, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("报警中心", Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
                    TextButton(onClick = onThresholds, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("阈值设置", color = C.Primary)
                    }
                }
            }
            item { MonitoringStatusCard(monitoringState) }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(D.Gap)) {
                    listOf(false to "当前报警", true to "历史报警").forEach { (history, label) ->
                        OutlinedButton(onClick = { showHistory = history; selectedId = null },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                            border = BorderStroke(1.dp, if (showHistory == history) C.Primary else C.Outline),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (showHistory == history) C.Primary.copy(alpha = 0.08f) else Color.Transparent,
                                contentColor = if (showHistory == history) C.Primary else C.Secondary)) {
                            Text(label)
                        }
                    }
                }
            }
            monitoringState.error?.let { error ->
                item {
                    GreenhouseCard(outline = C.Critical.copy(alpha = 0.35f)) {
                        Text("监测记录暂不可用", color = C.Critical, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
                        Text(error, color = C.Secondary, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (alarms.isEmpty()) {
                item { AlarmEmptyCard(monitoringState, showHistory, onThresholds) }
            } else {
                items(alarms, key = { it.id }) { alarm ->
                    AlarmEpisodeCard(alarm) { selectedId = alarm.id }
                }
            }
            item {
                Text("仅在手机前台实时模式监测；回放、后台或息屏时暂停。不提供后台远程推送，本地报警不会自动控制设备。",
                    color = C.Muted, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
            }
        }
    }
    // 详情按身份读取最新记录，确认后仍保留当前报警，不用旧对象快照假装恢复。
    val selected = selectedId?.let { id ->
        (monitoringState.currentAlarms + monitoringState.historicalAlarms).singleOrNull { it.id == id }
    }
    selected?.let { alarm ->
        AlarmDetailsSheet(alarm, onAcknowledge, {
            selectedId = null
            onLocate(it)
        }, {
            selectedId = null
            onTrend(it)
        }, {
            selectedId = null
            onControl(it)
        }) { selectedId = null }
    }
}

@Composable
private fun MonitoringStatusCard(state: MonitoringUiState) {
    GreenhouseCard(containerColor = C.Overview) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(when {
                state.status == MonitoringStatus.ACTIVE -> "${state.currentAlarms.size} 条当前报警"
                state.currentAlarms.isNotEmpty() -> "${state.currentAlarms.size} 条已保存当前报警"
                else -> "当前数量待核实"
            }, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            StatusTag(when (state.status) {
                MonitoringStatus.ACTIVE -> "正在监测"
                MonitoringStatus.DISABLED -> "尚未启用"
                MonitoringStatus.PAUSED -> "监测暂停"
                MonitoringStatus.UNVERIFIED -> "待核实"
            }, when (state.status) {
                MonitoringStatus.ACTIVE -> C.Primary
                MonitoringStatus.DISABLED -> C.Offline
                MonitoringStatus.PAUSED, MonitoringStatus.UNVERIFIED -> C.Waiting
            })
        }
        Spacer(Modifier.height(8.dp))
        Text(state.statusReason, color = C.Secondary, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(6.dp))
        Text("手机本地规则 · 确认仅表示已查看，恢复以新的有效观察为依据。",
            color = C.Muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun AlarmEmptyCard(state: MonitoringUiState, history: Boolean, onThresholds: () -> Unit) {
    GreenhouseCard {
        Text(when {
            history && state.status == MonitoringStatus.UNVERIFIED -> "历史报警状态待核实"
            history -> "尚无已保存的历史报警"
            state.status == MonitoringStatus.ACTIVE -> "当前没有触发的本地报警"
            state.status == MonitoringStatus.DISABLED -> "本地监测尚未启用"
            else -> "当前监测状态待核实"
        }, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(if (history) "历史记录保留触发、确认、恢复和规则修改终止的不同含义。"
            else if (state.status == MonitoringStatus.ACTIVE) "只检查已启用的规则；未配置阈值的指标不会被判定正常。"
            else state.statusReason, color = C.Secondary, style = MaterialTheme.typography.bodyMedium)
        if (!history && !state.settings.monitorEnabled) {
            Spacer(Modifier.height(12.dp))
            Button(onClick = onThresholds, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("查看并确认本地阈值")
            }
        }
    }
}

@Composable
private fun AlarmEpisodeCard(alarm: AlarmEpisode, onOpen: () -> Unit) {
    val color = monitoringSeverityColor(alarm.severity)
    GreenhouseCard(Modifier.fillMaxWidth().clickable(onClick = onOpen), outline = color.copy(alpha = 0.30f)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.width(3.dp).height(36.dp).background(color, RoundedCornerShape(2.dp)))
            Column(Modifier.weight(1f)) {
                Text(alarm.title, style = MaterialTheme.typography.titleMedium)
                Text(if (alarm.verificationPending && alarm.isOpen) "最后已知报警 · 当前待核实"
                    else if (alarm.isOpen) "仍在触发" else alarm.endedReason?.label ?: "已结束",
                    color = C.Secondary, style = MaterialTheme.typography.bodySmall)
            }
            StatusTag(monitoringSeverityLabel(alarm.severity), color)
        }
        Spacer(Modifier.height(10.dp))
        if (alarm.metric != null) {
            Text("触发值 ${monitoringValue(alarm.triggerValue, alarm.unit)} · 阈值 ${monitoringValue(alarm.thresholdValue, alarm.unit)}",
                style = MaterialTheme.typography.bodyMedium)
            Text("最近观察值 ${monitoringValue(alarm.latestValue, alarm.unit)}", color = C.Secondary,
                style = MaterialTheme.typography.bodySmall)
        } else if (alarm.detail.isNotBlank()) {
            Text(alarm.detail, color = C.Secondary, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(6.dp))
        SourceTag(monitoringSourceLabel(alarm.source))
        Text("触发采样 ${monitoringTime(alarm.triggerSampleUtcMs)}", color = C.Muted,
            style = MaterialTheme.typography.bodySmall)
        Text("开始观察 ${monitoringTime(alarm.startedAtUtcMs)}", color = C.Secondary,
            style = MaterialTheme.typography.bodySmall)
        Text("最后观察 ${monitoringTime(alarm.lastObservedAtUtcMs)}", color = C.Muted,
            style = MaterialTheme.typography.bodySmall)
        Text("已观测 ${monitoringDuration(alarm.observedDurationMs)} · ${if (alarm.isAcknowledged) "已确认查看" else "尚未确认"}",
            color = C.Muted, style = MaterialTheme.typography.bodySmall)
        if (!alarm.isOpen) {
            Text(if (alarm.recoveredAtUtcMs != null) "恢复 ${monitoringTime(alarm.recoveredAtUtcMs)}"
                else "终止 ${monitoringTime(alarm.endedAtUtcMs)} · ${alarm.endedReason?.label ?: "原因未记录"}",
                color = C.Secondary, style = MaterialTheme.typography.bodySmall)
        }
        Text("查看详情", color = C.Primary, style = MaterialTheme.typography.labelMedium)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlarmDetailsSheet(
    alarm: AlarmEpisode,
    onAcknowledge: (String) -> Unit,
    onLocate: (SceneObject) -> Unit,
    onTrend: (HistoryMetric) -> Unit,
    onControl: (ActuatorKey) -> Unit,
    onClose: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onClose, sheetState = sheetState,
        containerColor = C.Raised, scrimColor = C.Scrim.copy(alpha = 0.65f),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) {
        MonitoringSheetSystemBars()
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(alarm.title, style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusTag(monitoringSeverityLabel(alarm.severity), monitoringSeverityColor(alarm.severity))
                SourceTag(monitoringSourceLabel(alarm.source))
            }
            GreenhouseCard {
                if (alarm.metric != null) {
                    AlarmFact("触发值", monitoringValue(alarm.triggerValue, alarm.unit))
                    AlarmFact("本地报警阈值", monitoringValue(alarm.thresholdValue, alarm.unit))
                    AlarmFact("最近观察值", monitoringValue(alarm.latestValue, alarm.unit))
                }
                AlarmFact("触发采样 · 北京时间", monitoringTime(alarm.triggerSampleUtcMs))
                AlarmFact("最近采样 · 北京时间", monitoringTime(alarm.lastSampleUtcMs))
                AlarmFact("开始观察 · 手机时间", monitoringTime(alarm.startedAtUtcMs))
                AlarmFact("最后观察 · 手机时间", monitoringTime(alarm.lastObservedAtUtcMs))
                AlarmFact("已观测时长", monitoringDuration(alarm.observedDurationMs))
                AlarmFact("确认查看", if (alarm.isAcknowledged) monitoringTime(alarm.acknowledgedAtUtcMs) else "尚未确认")
                alarm.recoveredAtUtcMs?.let { AlarmFact("恢复时间", monitoringTime(it)) }
                alarm.endedAtUtcMs?.let { AlarmFact("终止时间", monitoringTime(it)) }
                alarm.endedReason?.let { AlarmFact("终止原因", it.label) }
                if (alarm.detail.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(alarm.detail, color = C.Secondary, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Text("已观测时长只累计有效观察，不把监测断档当作连续报警。确认或查看不会使报警恢复；规则修改终止也不表示数值恢复。",
                color = C.Muted, style = MaterialTheme.typography.bodySmall)
            if (alarm.verificationPending && alarm.isOpen) {
                Text("监测中断后的当前状态待核实。", color = C.Waiting, style = MaterialTheme.typography.bodyMedium)
            }
            if (!alarm.isAcknowledged) {
                Button(onClick = { onAcknowledge(alarm.id) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("确认已查看")
                }
            }
            OutlinedButton(onClick = { onLocate(alarmSceneObject(alarm)) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("定位到大棚") }
            alarm.metric?.let { metric ->
                OutlinedButton(onClick = { onTrend(metric) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("查看${metric.label}趋势")
                }
            }
            alarm.actuator?.let { target ->
                OutlinedButton(onClick = { onControl(target) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("查看${target.label}控制")
                }
            }
            TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("关闭详情") }
        }
    }
}

@Composable
private fun AlarmFact(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, color = C.Muted, style = MaterialTheme.typography.labelMedium)
        Text(value, color = C.Text, style = MaterialTheme.typography.bodyMedium)
    }
}

// 与已认可的控制和导入浮层使用相同的暗色系统栏，不另造浮层主题。
@Composable
internal fun MonitoringSheetSystemBars() {
    val view = LocalView.current
    val window = (view.parent as DialogWindowProvider).window
    SideEffect {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }
}

private fun alarmSceneObject(alarm: AlarmEpisode): SceneObject = when (alarm.metric) {
    HistoryMetric.TEMPERATURE -> SceneObject.TEMPERATURE
    HistoryMetric.HUMIDITY -> SceneObject.HUMIDITY
    HistoryMetric.LIGHT -> SceneObject.LIGHT
    HistoryMetric.SOIL -> SceneObject.SOIL
    null -> when (alarm.actuator) {
        ActuatorKey.PUMP -> SceneObject.PUMP
        ActuatorKey.LAMP -> SceneObject.LAMP
        ActuatorKey.FAN -> SceneObject.FAN
        null -> SceneObject.CONTROLLER
    }
}

private fun monitoringSeverityColor(severity: AlarmSeverity): Color = when (severity) {
    AlarmSeverity.INFO -> C.Offline
    AlarmSeverity.WARNING -> C.Warning
    AlarmSeverity.CRITICAL -> C.Critical
}

private fun monitoringSeverityLabel(severity: AlarmSeverity): String = when (severity) {
    AlarmSeverity.INFO -> "提示"
    AlarmSeverity.WARNING -> "警告"
    AlarmSeverity.CRITICAL -> "严重"
}

internal fun monitoringSourceLabel(source: ObservationSource?): String = when (source) {
    ObservationSource.SENSOR -> "SENSOR · 实测"
    ObservationSource.EXPERIMENT -> "EXP · 实验"
    ObservationSource.SIMULATION -> "SIM · 模拟"
    null -> "手机本地监测"
}

internal fun monitoringValue(value: Double?, unit: String): String =
    if (value == null) "未知" else "${BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()}${if (unit.isBlank()) "" else " $unit"}"

internal fun monitoringTime(utcMs: Long?): String = if (utcMs == null) "未知" else
    SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).apply {
        timeZone = TimeZone.getTimeZone("Asia/Shanghai")
    }.format(Date(utcMs))

private fun monitoringDuration(milliseconds: Long): String {
    val seconds = milliseconds / 1_000
    return when {
        seconds >= 3_600 -> "${seconds / 3_600} 小时 ${(seconds % 3_600) / 60} 分钟"
        seconds >= 60 -> "${seconds / 60} 分钟 ${seconds % 60} 秒"
        else -> "$seconds 秒"
    }
}
