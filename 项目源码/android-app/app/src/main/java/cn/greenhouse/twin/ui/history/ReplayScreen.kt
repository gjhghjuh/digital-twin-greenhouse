package cn.greenhouse.twin.ui.history

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cn.greenhouse.twin.domain.*
import cn.greenhouse.twin.domain.history.*
import cn.greenhouse.twin.ui.components.GreenhouseCard
import cn.greenhouse.twin.ui.components.SourceTag
import cn.greenhouse.twin.ui.components.StatusTag
import cn.greenhouse.twin.ui.theme.GreenhouseColors as C
import cn.greenhouse.twin.ui.theme.GreenhouseDimensions as D

@Composable
fun ReplayScreen(
    state: ReplayUiState,
    onPlaying: (Boolean) -> Unit,
    onSpeed: (Float) -> Unit,
    onSeek: (Long) -> Unit,
    onPreviousEvent: () -> Unit,
    onNextEvent: () -> Unit,
    onExit: () -> Unit,
    onRefresh: () -> Unit,
    twinContent: @Composable (GreenhouseTwinState, Modifier) -> Unit,
) {
    Scaffold(
        containerColor = C.Background,
        contentColor = C.Text,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = D.PageMargin, vertical = 8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("历史回放", Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
                    TextButton(onClick = onExit, modifier = Modifier.heightIn(min = 48.dp)) { Text("退出回放") }
                }
                // 回放模式固定可见，不用短暂提示代替业务隔离。
                StatusTag("REPLAY · 禁止真实控制", C.Advice)
                Text("${historyDateTime(state.positionUtcMs, "yyyy-MM-dd HH:mm:ss")} · 北京时间",
                    color = C.Secondary, style = MaterialTheme.typography.bodySmall)
            }
        },
    ) { insets ->
        LazyColumn(
            Modifier.fillMaxSize().padding(insets),
            contentPadding = PaddingValues(start = D.PageMargin, end = D.PageMargin, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                state.loading -> item { HistoryLoadingCard("正在读取回放记录") }
                state.error != null -> item { HistoryErrorCard(state.error, onRefresh) }
                state.window == null -> item {
                    GreenhouseCard {
                        Text("暂无可回放历史", style = MaterialTheme.typography.titleLarge)
                        Text("回到数据中心选择真实记录区间或导入 CSV。", color = C.Secondary,
                            style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(onClick = onExit, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text("返回数据中心")
                        }
                    }
                }
                else -> {
                    val window = state.window
                    item {
                        GreenhouseCard(containerColor = C.Overview, highlight = true) {
                            Text("历史温室", style = MaterialTheme.typography.titleMedium)
                            Text(window.deviceId, color = C.Muted, style = MaterialTheme.typography.bodySmall)
                            val snapshot = state.currentSnapshot
                            if (!state.isUnknown && !state.gap && snapshot != null) {
                                twinContent(snapshot, Modifier.fillMaxWidth().height(248.dp))
                                Text("呈现已记录反馈，不对真实设备发送指令。", color = C.Muted,
                                    style = MaterialTheme.typography.bodySmall)
                            } else {
                                Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("该时段无记录，状态未知", color = C.Waiting,
                                            style = MaterialTheme.typography.titleMedium)
                                        Text("不延续上一条反馈，不补出运行状态。", color = C.Secondary,
                                            style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                    item { ReplayEnvironment(state) }
                    item { ReplayActuators(state) }
                    item { ReplayTimeline(state, onSeek) }
                    item {
                        GreenhouseCard {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = onPreviousEvent,
                                    enabled = window.events.any { it.utcMs != null },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp),
                                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                                    Text("上一事件", style = MaterialTheme.typography.labelMedium)
                                }
                                Button(onClick = { onPlaying(!state.playing) },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp),
                                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                                    Text(if (state.playing) "暂停" else "播放", style = MaterialTheme.typography.labelMedium)
                                }
                                OutlinedButton(onClick = onNextEvent,
                                    enabled = window.events.any { it.utcMs != null },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp),
                                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                                    Text("下一事件", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(0.5f, 1f, 2f, 4f).forEach { speed ->
                                    FilterChip(selected = state.speed == speed, onClick = { onSpeed(speed) },
                                        label = { Text("${historyNumber(speed.toDouble())}×") },
                                        colors = FilterChipDefaults.filterChipColors(
                                            containerColor = C.Surface, labelColor = C.Secondary,
                                            selectedContainerColor = C.Primary.copy(alpha = 0.15f),
                                            selectedLabelColor = C.Primary),
                                        modifier = Modifier.heightIn(min = 48.dp))
                                }
                            }
                        }
                    }
                    item { ReplayEventCard(state.selectedEvent) }
                    item { HistoryCoverageCard(window) }
                    item {
                        Text("退出回放后先重新同步当前设备，取得新鲜反馈及正确启动标识后才恢复实时控制。",
                            color = C.Muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun ReplayEnvironment(state: ReplayUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HistoryMetric.entries.chunked(2).forEach { metrics ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                metrics.forEach { metric ->
                    val snapshot = state.currentSnapshot
                    val observation: Observation<out Number>? = snapshot?.let {
                        when (metric) {
                            HistoryMetric.TEMPERATURE -> it.temperature
                            HistoryMetric.HUMIDITY -> it.humidity
                            HistoryMetric.LIGHT -> it.lightLevel
                            HistoryMetric.SOIL -> it.soilHumidity
                        }
                    }
                    val valid = !state.gap && !state.isUnknown && observation?.validity == ObservationValidity.VALID &&
                        observation?.value != null
                    GreenhouseCard(Modifier.weight(1f)) {
                        Text(metric.label, style = MaterialTheme.typography.titleMedium)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            HistoryMetricIcon(metric,
                                value = if (valid) observation?.value?.toDouble() else null,
                                motionEnabled = state.playing)
                            Spacer(Modifier.width(6.dp))
                            Column(Modifier.weight(1f)) {
                                Text(if (valid) historyNumber(observation!!.value!!.toDouble(),
                                    if (metric == HistoryMetric.LIGHT) 0 else 1) else "—",
                                    style = MaterialTheme.typography.titleLarge)
                                Text(metric.unit, color = C.Muted, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        SourceTag(historySourceLabel(metric.source))
                        Text(if (valid) "采样 ${historyDateTime(observation!!.sampledAtUtcMs, "HH:mm:ss")}"
                        else if (state.gap || state.isUnknown) "该时段状态未知" else when (observation?.validity) {
                            ObservationValidity.READ_ERROR -> "读取错误 · 值无效"
                            ObservationValidity.NOT_READY -> "尚未就绪 · 缺测"
                            ObservationValidity.VALID -> "记录值为空"
                            null -> "没有对应记录"
                        },
                            color = if (valid) C.Muted else C.Waiting, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun ReplayActuators(state: ReplayUiState) {
    GreenhouseCard {
        Text("历史设备反馈", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActuatorKey.entries.forEach { target ->
                val reported = if (state.isUnknown || state.gap) ReportedState.UNKNOWN else state.currentSnapshot?.let {
                    when (target) {
                        ActuatorKey.PUMP -> it.pump.reported
                        ActuatorKey.LAMP -> it.lamp.reported
                        ActuatorKey.FAN -> it.fan.reported
                    }
                } ?: ReportedState.UNKNOWN
                Column(Modifier.weight(1f)) {
                    Text(target.label, color = C.Secondary, style = MaterialTheme.typography.bodySmall)
                    Text(when (reported) {
                        ReportedState.ON -> "开启"
                        ReportedState.OFF -> "关闭"
                        ReportedState.UNKNOWN -> "未知"
                    }, color = when (reported) {
                        ReportedState.ON -> C.Primary
                        ReportedState.OFF -> C.Offline
                        ReportedState.UNKNOWN -> C.Waiting
                    }, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        Text("仅查看真实已记录反馈，不提供控制按钮。", color = C.Muted,
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ReplayTimeline(state: ReplayUiState, onSeek: (Long) -> Unit) {
    val window = state.window ?: return
    val span = window.endUtcMs - window.startUtcMs
    var dragging by remember(window.startUtcMs, window.endUtcMs) { mutableStateOf(false) }
    var dragFraction by remember(window.startUtcMs, window.endUtcMs) { mutableFloatStateOf(0f) }
    val current = if (span > 0) ((state.positionUtcMs ?: window.startUtcMs) - window.startUtcMs).toDouble() / span else 0.0
    val fraction = if (dragging) dragFraction else current.toFloat().coerceIn(0f, 1f)
    val previewUtc = if (dragging) window.startUtcMs + ((span - 1).coerceAtLeast(0) * fraction).toLong()
        else state.positionUtcMs
    GreenhouseCard {
        Text("真实时间轴", style = MaterialTheme.typography.titleMedium)
        Text(historyDateTime(previewUtc, "yyyy-MM-dd HH:mm:ss"), color = C.Primary,
            style = MaterialTheme.typography.bodyMedium)
        Canvas(Modifier.fillMaxWidth().height(28.dp).semantics {
            contentDescription = "历史事件与灰色无数据区，缺口保持真实时间宽度"
        }) {
            if (span > 0) {
                drawLine(C.Outline, Offset(0f, size.height / 2), Offset(size.width, size.height / 2),
                    strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
                window.replayGaps.forEach { gap ->
                    val from = ((gap.startUtcMs - window.startUtcMs).toDouble() / span).toFloat().coerceIn(0f, 1f)
                    val to = ((gap.endUtcMs - window.startUtcMs).toDouble() / span).toFloat().coerceIn(0f, 1f)
                    drawRect(C.Offline.copy(alpha = 0.32f), Offset(from * size.width, 0f),
                        Size((to - from).coerceAtLeast(0f) * size.width, size.height))
                }
                window.events.forEach { event ->
                    event.utcMs?.takeIf { it in window.startUtcMs until window.endUtcMs }?.let { time ->
                        val x = ((time - window.startUtcMs).toDouble() / span * size.width).toFloat()
                        drawLine(C.Advice, Offset(x, size.height * 0.3f), Offset(x, size.height * 0.7f), 1.dp.toPx())
                    }
                }
                drawCircle(C.Primary, 4.dp.toPx(), Offset(fraction * size.width, size.height / 2))
            }
        }
        Slider(value = fraction, onValueChange = { dragging = true; dragFraction = it },
            enabled = span > 0,
            colors = SliderDefaults.colors(thumbColor = C.Primary, activeTrackColor = C.Primary,
                inactiveTrackColor = C.Outline, activeTickColor = C.OnPrimary,
                inactiveTickColor = C.Secondary),
            onValueChangeFinished = {
                if (dragging && span > 0) {
                    onSeek(window.startUtcMs + ((span - 1) * dragFraction).toLong())
                    dragging = false
                }
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                contentDescription = "拖动历史时间轴，松手跳转到所选时刻"
            })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("开始\n${historyDateTime(window.startUtcMs, "MM-dd HH:mm:ss")}",
                modifier = Modifier.weight(1f), color = C.Muted, style = MaterialTheme.typography.bodySmall)
            Text("结束（不含）\n${historyDateTime(window.endUtcMs, "MM-dd HH:mm:ss")}",
                modifier = Modifier.weight(1f), color = C.Muted, style = MaterialTheme.typography.bodySmall)
        }
        Text("灰色 · 无记录    竖线 · 真实事件    北京时间 UTC+08:00",
            color = C.Muted, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun ReplayEventCard(event: HistoryEvent?) {
    GreenhouseCard {
        Text("当前事件", style = MaterialTheme.typography.titleMedium)
        if (event == null) {
            Text("当前时刻没有已定位事件。", color = C.Muted, style = MaterialTheme.typography.bodyMedium)
        } else {
            Text(when (event.eventType) {
                "REMOTE_ACTUATOR_SET" -> "远程控制反馈"
                "COMMAND_RESULT" -> "设备命令结果"
                "COMMAND_DUPLICATE" -> "同一命令去重"
                "BOOT", "BOOT_READY" -> "设备启动"
                "CLOUD_POST_ACCEPTED" -> "云端接收记录"
                else -> event.eventType
            }, style = MaterialTheme.typography.bodyMedium)
            Text(historyDateTime(event.utcMs, "yyyy-MM-dd HH:mm:ss"), color = C.Secondary,
                style = MaterialTheme.typography.bodySmall)
            if (event.target.isNotEmpty()) Text("对象 · ${event.target}", color = C.Secondary,
                style = MaterialTheme.typography.bodySmall)
            if (event.reason.isNotEmpty()) Text("记录结果 · ${event.reason}", color = C.Secondary,
                style = MaterialTheme.typography.bodySmall)
            Text("启动 ${event.bootId.take(8)} · 序号 ${event.seq}", color = C.Muted,
                style = MaterialTheme.typography.labelSmall)
        }
    }
}
