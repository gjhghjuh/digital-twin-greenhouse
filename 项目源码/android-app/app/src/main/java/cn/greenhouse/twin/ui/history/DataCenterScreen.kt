package cn.greenhouse.twin.ui.history

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cn.greenhouse.twin.domain.*
import cn.greenhouse.twin.domain.history.*
import cn.greenhouse.twin.ui.components.*
import cn.greenhouse.twin.ui.theme.GreenhouseColors as C
import cn.greenhouse.twin.ui.theme.GreenhouseDimensions as D
import com.example.environmenticonmotion.R as IconR
import com.example.environmenticonmotion.ui.motion.AnimatedAirHumidityIcon
import com.example.environmenticonmotion.ui.motion.AnimatedLightIcon
import com.example.environmenticonmotion.ui.motion.AnimatedSoilMoistureIcon
import com.example.environmenticonmotion.ui.motion.AnimatedTemperatureIcon

@Composable
fun DataCenterScreen(
    liveState: GreenhouseTwinState,
    state: HistoryUiState,
    onDevice: (String) -> Unit,
    onMetric: (HistoryMetric) -> Unit,
    onRange: (HistoryRange) -> Unit,
    onShiftWindow: (Int) -> Unit,
    onLatest: () -> Unit,
    onImport: () -> Unit,
    onReplay: () -> Unit,
    onRefresh: () -> Unit,
    onNavigate: (String) -> Unit,
    trendContent: @Composable (HistoryWindow, HistoryChartStyle, Modifier) -> Unit,
) {
    var chartStyle by rememberSaveable { mutableStateOf(HistoryChartStyle.LINE) }
    Scaffold(
        containerColor = C.Background,
        contentColor = C.Text,
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = { GreenhouseNavigation(liveState, "数据", onNavigate) },
    ) { insets ->
        LazyColumn(
            Modifier.fillMaxSize().padding(insets),
            contentPadding = PaddingValues(horizontal = D.PageMargin, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("数据中心", Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
                    TextButton(onClick = onImport, modifier = Modifier.heightIn(min = 48.dp),
                        colors = ButtonDefaults.textButtonColors(contentColor = C.Primary)) {
                        Text("导入 CSV")
                    }
                }
            }
            item { HistoryFilters(state, onDevice, onMetric, onRange, onShiftWindow, onLatest) }
            if (liveState.deviceConnection == DeviceConnection.OFFLINE) {
                item {
                    Text("设备离线 · 正在查看已保存历史", color = C.Offline,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            when {
                state.loading -> item { HistoryLoadingCard("正在读取历史记录") }
                state.error != null -> item { HistoryErrorCard(state.error, onRefresh) }
                state.window == null || (state.window.snapshots.isEmpty() && state.window.events.isEmpty()) -> {
                    item {
                        GreenhouseCard {
                            Text("这个时段还没有记录", style = MaterialTheme.typography.titleLarge)
                            Spacer(Modifier.height(8.dp))
                            Text("切换时段，或导入真实历史档案。未知时段不会补成 0。",
                                color = C.Secondary, style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = onImport, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = C.Primary, contentColor = C.OnPrimary)) {
                                Text("选择 CSV 文件")
                            }
                        }
                    }
                }
                else -> {
                    val window = state.window
                    item { HistoryTrendCard(window, chartStyle, { chartStyle = it }, trendContent) }
                    item { HistoryStatisticsCard(window) }
                    item { HistoryCoverageCard(window) }
                    item { ActuatorStatisticsCard(window) }
                    if (window.unknownUtcCount > 0) {
                        item {
                            GreenhouseCard(outline = C.Waiting.copy(alpha = 0.35f)) {
                                Text("${window.unknownUtcCount} 条记录的设备时间未知", color = C.Waiting,
                                    style = MaterialTheme.typography.bodyMedium)
                                Spacer(Modifier.height(4.dp))
                                Text("原记录保留，不计入已定位的趋势与覆盖。",
                                    color = C.Secondary, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    item { HistoryInformationCard(window) }
                    item {
                        Button(onClick = onReplay, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = C.Primary, contentColor = C.OnPrimary)) {
                            Text("打开历史回放")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryFilters(
    state: HistoryUiState,
    onDevice: (String) -> Unit,
    onMetric: (HistoryMetric) -> Unit,
    onRange: (HistoryRange) -> Unit,
    onShiftWindow: (Int) -> Unit,
    onLatest: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) {
                TextButton(onClick = { expanded = true },
                    enabled = state.devices.isNotEmpty() && !state.loading,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 8.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = C.Secondary)) {
                    Column(Modifier.fillMaxWidth()) {
                        Text("设备", color = C.Muted, style = MaterialTheme.typography.labelSmall)
                        Text(state.selectedDeviceId ?: "未选择设备", color = C.Secondary,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    state.devices.forEach { device ->
                        DropdownMenuItem(text = { Text(device) },
                            onClick = { expanded = false; onDevice(device) })
                    }
                }
            }
            TextButton(onClick = onLatest, enabled = !state.loading,
                modifier = Modifier.heightIn(min = 48.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = C.Primary)) {
                Text("最新记录", style = MaterialTheme.typography.labelMedium)
            }
        }
        // 四个范围始终等宽，不靠横向滚动隐藏选项。
        Row(Modifier.fillMaxWidth().background(C.Surface, RoundedCornerShape(14.dp)).padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            HistoryRange.entries.forEach { range ->
                DashboardSelection(
                    text = when (range) {
                        HistoryRange.HOUR_1 -> "1 小时"
                        HistoryRange.HOUR_6 -> "6 小时"
                        HistoryRange.HOUR_24 -> "24 小时"
                        HistoryRange.DAY_7 -> "7 天"
                    },
                    selected = state.range == range,
                    color = C.Primary,
                    enabled = !state.loading,
                    onClick = { onRange(range) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HistoryMetric.entries.forEach { metric ->
                DashboardSelection(metric.label, state.metric == metric, historyMetricColor(metric),
                    !state.loading, { onMetric(metric) }, Modifier.weight(1f))
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onShiftWindow(-1) },
                enabled = !state.loading && state.endUtcMs != null,
                modifier = Modifier.widthIn(min = 48.dp).heightIn(min = 48.dp),
                contentPadding = PaddingValues(horizontal = 4.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = C.Secondary)) {
                Text("前一段", style = MaterialTheme.typography.labelSmall)
            }
            Text(state.endUtcMs?.let {
                historyCompactRange(it - state.range.durationMs, it)
            } ?: "时间范围待同步", Modifier.weight(1f).padding(horizontal = 4.dp),
                color = C.Secondary, textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelMedium)
            TextButton(onClick = { onShiftWindow(1) },
                enabled = !state.loading && state.endUtcMs != null,
                modifier = Modifier.widthIn(min = 48.dp).heightIn(min = 48.dp),
                contentPadding = PaddingValues(horizontal = 4.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = C.Secondary)) {
                Text("后一段", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun DashboardSelection(
    text: String,
    selected: Boolean,
    color: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.heightIn(min = 48.dp)
            .selectable(selected = selected, enabled = enabled, role = Role.Tab, onClick = onClick),
        shape = RoundedCornerShape(11.dp),
        color = if (selected) color.copy(alpha = 0.15f) else Color.Transparent,
        border = if (selected) BorderStroke(1.dp, color.copy(alpha = 0.45f)) else null,
    ) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 3.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center) {
            Text(text, color = (if (selected) color else C.Secondary).copy(alpha = if (enabled) 1f else 0.55f),
                textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium)
        }
    }
}

@Composable
private fun HistoryTrendCard(
    window: HistoryWindow,
    chartStyle: HistoryChartStyle,
    onChartStyle: (HistoryChartStyle) -> Unit,
    trendContent: @Composable (HistoryWindow, HistoryChartStyle, Modifier) -> Unit,
) {
    val color = historyMetricColor(window.metric)
    // 时段末次取真实有效采样；既不是均值，也不是设备当前值。
    val last = window.points.lastOrNull {
        it.validity == ObservationValidity.VALID && it.value != null
    }
    GreenhouseCard(containerColor = C.Overview, outline = color.copy(alpha = 0.22f), highlight = true) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            HistoryMetricIcon(window.metric, last?.value)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(window.metric.label, style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(historyNumber(last?.value, if (window.metric == HistoryMetric.LIGHT) 0 else 1),
                        color = color, style = MaterialTheme.typography.displaySmall)
                    Text(window.metric.unit, color = C.Secondary,
                        modifier = Modifier.padding(bottom = 5.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
            SourceTag(dashboardSourceCode(window.metric.source))
        }
        Spacer(Modifier.height(6.dp))
        Text(if (last != null) "时段末次 · ${historyDateTime(last.utcMs)}"
            else "时段末次 · 没有有效采样", color = C.Muted,
            style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth().background(C.Surface, RoundedCornerShape(14.dp)).padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            HistoryChartStyle.entries.forEach { style ->
                DashboardSelection(style.label, chartStyle == style, color, true,
                    { onChartStyle(style) }, Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(12.dp))
        if (window.points.isEmpty()) {
            Box(Modifier.fillMaxWidth().height(260.dp), contentAlignment = Alignment.Center) {
                Text("没有可定位到时间轴的记录", color = C.Muted,
                    style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            }
        } else {
            // 样式只改变呈现，不改时段统计、覆盖率和已保存的原始记录。
            trendContent(window, chartStyle, Modifier.fillMaxWidth().heightIn(min = 260.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text("触摸图表查看数值 · 灰色保留真实缺口", color = C.Muted,
            style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun HistoryStatisticsCard(window: HistoryWindow) {
    val color = historyMetricColor(window.metric)
    GreenhouseCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("时段统计", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Text("${window.statistics.validRecords} 条有效值", color = C.Muted,
                style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("平均" to window.statistics.average, "最高" to window.statistics.maximum,
                "最低" to window.statistics.minimum).forEach { (label, value) ->
                Surface(Modifier.weight(1f), color = C.Raised.copy(alpha = 0.45f),
                    shape = RoundedCornerShape(12.dp)) {
                    Column(Modifier.padding(horizontal = 8.dp, vertical = 10.dp)) {
                        Text(label, color = C.Secondary, style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.height(5.dp))
                        Text(historyNumber(value,
                            if (window.metric == HistoryMetric.LIGHT && label != "平均") 0 else 1),
                            color = color, style = MaterialTheme.typography.titleLarge)
                        Text(window.metric.unit, color = C.Muted, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
internal fun HistoryCoverageCard(window: HistoryWindow) {
    val color = historyMetricColor(window.metric)
    val known = window.coverage.expectedBuckets > 0
    GreenhouseCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("${window.metric.label}覆盖率", Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium)
            Text(if (known) "${historyNumber(window.coverage.percent)}%" else "—",
                color = color, style = MaterialTheme.typography.titleLarge)
        }
        Spacer(Modifier.height(12.dp))
        LinearProgressIndicator(
            progress = { if (known) (window.coverage.percent / 100.0).toFloat().coerceIn(0f, 1f) else 0f },
            modifier = Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(50)),
            color = color, trackColor = C.Raised,
        )
        Spacer(Modifier.height(9.dp))
        Text("有效 ${window.coverage.validBuckets} / 应有 ${window.coverage.expectedBuckets} 桶",
            color = C.Secondary, style = MaterialTheme.typography.bodySmall)
        Text("10 秒一个桶 · 同桶只计一次", color = C.Muted,
            style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun ActuatorStatisticsCard(window: HistoryWindow) {
    GreenhouseCard {
        Text("设备已记录运行", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text("仅展示可证明区间 · — 表示未知", color = C.Muted,
            style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("设备", Modifier.weight(0.8f), color = C.Muted, style = MaterialTheme.typography.labelSmall)
            Text("开启次数", Modifier.weight(0.75f), color = C.Muted, style = MaterialTheme.typography.labelSmall)
            Text("累计运行", Modifier.weight(1.45f), color = C.Muted, style = MaterialTheme.typography.labelSmall)
        }
        window.actuatorDurations.forEach { record ->
            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = C.Outline.copy(alpha = 0.7f))
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top) {
                Text(record.target.label, Modifier.weight(0.8f), style = MaterialTheme.typography.bodyMedium)
                Text(if (record.coveredMs > 0) "${record.runCount} 次" else "—",
                    Modifier.weight(0.75f), color = C.Text, style = MaterialTheme.typography.bodyMedium)
                Text(if (record.coveredMs > 0) historyDuration(record.runningMs) else "—",
                    Modifier.weight(1.45f), color = C.Text, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(5.dp))
            Text("可核验覆盖 ${historyDuration(record.coveredMs)} / ${historyDuration(record.selectedMs)}",
                color = C.Muted, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun HistoryInformationCard(window: HistoryWindow) {
    var expanded by remember { mutableStateOf(false) }
    GreenhouseCard {
        TextButton(onClick = { expanded = !expanded },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            contentPadding = PaddingValues(horizontal = 0.dp),
            colors = ButtonDefaults.textButtonColors(contentColor = C.Secondary)) {
            Text("统计与时段细节", Modifier.weight(1f), textAlign = TextAlign.Start,
                style = MaterialTheme.typography.bodyMedium)
            Text(if (expanded) "收起" else "展开", color = C.Primary,
                style = MaterialTheme.typography.labelMedium)
        }
        if (expanded) {
            HorizontalDivider(color = C.Outline)
            Spacer(Modifier.height(10.dp))
            Text("开始 · ${historyDateTime(window.startUtcMs, "yyyy-MM-dd HH:mm:ss")}（含）",
                color = C.Secondary, style = MaterialTheme.typography.bodySmall)
            Text("结束 · ${historyDateTime(window.endUtcMs, "yyyy-MM-dd HH:mm:ss")}（不含）",
                color = C.Secondary, style = MaterialTheme.typography.bodySmall)
            Text("北京时间 UTC+08:00 · [start,end)", color = C.Muted,
                style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(10.dp))
            Text(historySourceLabel(window.metric.source), color = historyMetricColor(window.metric),
                style = MaterialTheme.typography.bodySmall)
            Text(if (window.metric == HistoryMetric.LIGHT)
                "光照 0–7 是实验离散档位，不是照度。土壤湿度来自模拟；温度和空气湿度为实测。"
            else "土壤湿度来自模拟；光照为实验离散档位；温度和空气湿度为实测。",
                color = C.Muted, style = MaterialTheme.typography.bodySmall)
            Text("仅统计该指标的有效值；趋势只连接可证明连续的有效记录段，不跨缺口插值。",
                color = C.Muted, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(10.dp))
            Text("覆盖率 = 有效桶 / 应有桶", color = C.Secondary,
                style = MaterialTheme.typography.bodySmall)
            Text("每个 UTC 10 秒桶只计一次；与区间相交的边界桶计入应有桶，只有真实有效采样计入有效桶。",
                color = C.Muted, style = MaterialTheme.typography.bodySmall)
            Text("覆盖区间 · ${historyDateTime(window.coverage.startUtcMs)} — ${historyDateTime(window.coverage.endUtcMs)}",
                color = C.Muted, style = MaterialTheme.typography.labelSmall)
            Text("首桶 UTC · ${historyDateTime(window.coverage.firstBucketStartUtcMs)}（换算为北京时间）",
                color = C.Muted, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(10.dp))
            Text("开启次数只计可证明的 false→true 事件。同启动、连续序号、配置/模型一致，UTC和运行时钟间隔1至15秒，且完整状态版本事件链与端点匹配，才证明设备运行区间。缺口或无覆盖不推算。",
                color = C.Muted, style = MaterialTheme.typography.bodySmall)
            Text("设备UTC未知的记录仍保留，不借用电脑接收时间伪造采样时刻，不计入已定位的趋势与覆盖。",
                color = C.Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun historyCompactRange(startUtcMs: Long, endUtcMs: Long): String =
    if (historyDateTime(startUtcMs, "yyyy-MM-dd") == historyDateTime(endUtcMs, "yyyy-MM-dd")) {
        "${historyDateTime(startUtcMs, "MM-dd")} · ${historyDateTime(startUtcMs, "HH:mm")}–${historyDateTime(endUtcMs, "HH:mm") }"
    } else {
        "${historyDateTime(startUtcMs, "MM-dd HH:mm")} — ${historyDateTime(endUtcMs, "MM-dd HH:mm")}"
    }

private fun dashboardSourceCode(source: ObservationSource): String = when (source) {
    ObservationSource.SENSOR -> "SENSOR"
    ObservationSource.SIMULATION -> "SIM"
    ObservationSource.EXPERIMENT -> "EXP"
}

@Composable
internal fun HistoryLoadingCard(message: String) {
    GreenhouseCard {
        Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            Text(message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
internal fun HistoryErrorCard(message: String, onRetry: () -> Unit) {
    GreenhouseCard(outline = C.Critical.copy(alpha = 0.4f)) {
        Text("历史读取失败", color = C.Critical, style = MaterialTheme.typography.titleMedium)
        Text(message, color = C.Secondary, style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text("重新读取") }
    }
}

@Composable
internal fun HistoryMetricIcon(metric: HistoryMetric, value: Double?, motionEnabled: Boolean = false) {
    if (value != null) {
        require(value.isFinite())
        if (metric == HistoryMetric.LIGHT) require(value in 0.0..7.0 && value == value.toInt().toDouble())
    }
    val (visible, visibilityModifier) = rememberVisibleMotion()
    val active = visible && motionEnabled && value != null
    val description = if (value != null)
        "${metric.label}，历史有效采样 ${historyNumber(value, if (metric == HistoryMetric.LIGHT) 0 else 1)} ${metric.unit}"
    else "${metric.label}，历史采样未知"
    val staticLightGlow = if (metric == HistoryMetric.LIGHT && value != null && !active) {
        Modifier.drawWithCache {
            // 太阳组件暂停时关闭光场；这里沿用其同款渐变保留静态历史光晕。
            val radius = size.minDimension * 0.62f
            val glow = Brush.radialGradient(
                0f to Color.Transparent, 0.38f to Color.Transparent,
                0.72f to Color(0xFFFFBD45), 1f to Color.Transparent,
                center = Offset(size.width / 2f, size.height / 2f), radius = radius,
            )
            onDrawBehind { drawCircle(glow, radius, alpha = 0.08f) }
        }
    } else Modifier
    Box(Modifier.size(D.EnvironmentIcon).then(visibilityModifier).then(staticLightGlow)
        .businessIconSemantics(description)) {
        if (value != null) {
            // 复用首页认可的强度映射与光晕；静态历史保留光晕而不表示实时活动。
            when (metric) {
                HistoryMetric.TEMPERATURE -> AnimatedTemperatureIcon(
                    (value.toFloat() / 50f).coerceIn(0f, 1f), Modifier.fillMaxSize(),
                    interactionEnabled = false, motionEnabled = active, glowScale = 1f)
                HistoryMetric.HUMIDITY -> AnimatedAirHumidityIcon(value.toFloat(), Modifier.fillMaxSize(),
                    interactionEnabled = false, motionEnabled = active, glowScale = 0.7f)
                HistoryMetric.LIGHT -> AnimatedLightIcon(value.toInt(), Modifier.fillMaxSize(),
                    interactionEnabled = false, motionEnabled = active, glowScale = 1f)
                HistoryMetric.SOIL -> AnimatedSoilMoistureIcon(value.toFloat() / 100f, Modifier.fillMaxSize(),
                    interactionEnabled = false, motionEnabled = active, glowScale = 0.7f)
            }
        } else {
            OriginalIcon(when (metric) {
                HistoryMetric.TEMPERATURE -> IconR.drawable.env_temperature
                HistoryMetric.HUMIDITY -> IconR.drawable.env_air_humidity
                HistoryMetric.LIGHT -> IconR.drawable.env_light
                HistoryMetric.SOIL -> IconR.drawable.env_soil_moisture
            }, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(C.Surface.copy(alpha = 0.65f)))
            Text("未知", Modifier.align(Alignment.Center), color = C.Secondary,
                style = MaterialTheme.typography.labelMedium)
        }
    }
}

internal fun historySourceLabel(source: ObservationSource): String = when (source) {
    ObservationSource.SENSOR -> "SENSOR · 实测"
    ObservationSource.SIMULATION -> "SIM · 模拟"
    ObservationSource.EXPERIMENT -> "EXP · 实验"
}
