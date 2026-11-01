package cn.greenhouse.twin.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import cn.greenhouse.twin.BuildConfig
import cn.greenhouse.twin.R
import cn.greenhouse.twin.data.model.DemoStateFactory
import cn.greenhouse.twin.domain.*
import cn.greenhouse.twin.domain.history.HistoryMetric
import cn.greenhouse.twin.ui.components.*
import cn.greenhouse.twin.ui.scene.GreenhouseModelPreview
import cn.greenhouse.twin.ui.control.DeviceControlSheet
import cn.greenhouse.twin.ui.theme.GreenhouseColors as C
import cn.greenhouse.twin.ui.theme.GreenhouseDimensions as D
import com.example.environmenticonmotion.R as IconR
import com.example.environmenticonmotion.ui.motion.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private enum class SheetKind { SCENARIOS, COMPONENT }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: GreenhouseTwinState,
    enterDesignPreview: () -> Unit,
    selectScenario: (DemoScenario) -> Unit,
    exitDesignPreview: () -> Unit,
    onNavigateTwin: () -> Unit,
    onNavigateControl: () -> Unit,
    onRequest: (ActuatorKey, Boolean) -> Unit,
    onReserveOfflinePump: (Boolean, SnapshotIdentity) -> Unit,
    onRefresh: () -> Unit,
    onResend: () -> Unit,
    onDismissWait: () -> Unit,
    onNavigateData: () -> Unit,
    onNavigateReplay: () -> Unit,
    onNavigateSettings: () -> Unit,
    onNavigateAlarm: () -> Unit,
    onOpenAdvice: () -> Unit,
) {
    var sheet by remember { mutableStateOf<SheetKind?>(null) }
    var controlTarget by remember { mutableStateOf<ActuatorKey?>(null) }

    Scaffold(
        modifier = Modifier.fillMaxSize().background(C.Background).drawWithCache {
            val ambient = Brush.radialGradient(
                listOf(C.Ambient.copy(alpha = 0.10f), Color.Transparent),
                center = Offset(size.width * 0.6f, 0f), radius = size.width * 1.1f,
            )
            onDrawBehind { drawRect(ambient) }
        },
        containerColor = Color.Transparent,
        contentColor = C.Text,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            HomeHeader(state, onNavigateSettings) {
                if (!state.isDesignPreview) enterDesignPreview()
                sheet = SheetKind.SCENARIOS
            }
        },
        bottomBar = {
            GreenhouseNavigation(state, "首页") { destination ->
                when (destination) {
                    "数字孪生" -> onNavigateTwin()
                    "数据" -> onNavigateData()
                    "报警" -> onNavigateAlarm()
                }
            }
        },
    ) { insets ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(insets),
            contentPadding = PaddingValues(start = D.PageMargin, end = D.PageMargin, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { OverviewCard(state, onNavigateTwin) }
            item { SectionTitle("环境数据") }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(D.Gap)) {
                    EnvironmentCard("温度", EnvironmentKind.TEMPERATURE, state, Modifier.weight(1f))
                    EnvironmentCard("空气湿度", EnvironmentKind.HUMIDITY, state, Modifier.weight(1f))
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(D.Gap)) {
                    EnvironmentCard("土壤湿度", EnvironmentKind.SOIL, state, Modifier.weight(1f))
                    EnvironmentCard("光照档位", EnvironmentKind.LIGHT, state, Modifier.weight(1f))
                }
            }
            item { SectionTitle("设备摘要", "设备反馈") }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(D.Gap)) {
                    DeviceCard("水泵", DeviceKind.PUMP, state.pump, state, Modifier.weight(1f)) { controlTarget = ActuatorKey.PUMP }
                    DeviceCard("补光灯", DeviceKind.LAMP, state.lamp, state, Modifier.weight(1f)) { controlTarget = ActuatorKey.LAMP }
                    DeviceCard("风扇", DeviceKind.FAN, state.fan, state, Modifier.weight(1f)) { controlTarget = ActuatorKey.FAN }
                }
            }
            item {
                GreenhouseCard(modifier = Modifier.clickable(onClick = onNavigateAlarm), outline = if (state.alarmSummary.status == AlarmSummaryStatus.WARNING)
                    C.Warning.copy(alpha = 0.5f) else C.Outline) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(3.dp).height(32.dp).background(
                            if (state.alarmSummary.status == AlarmSummaryStatus.WARNING) C.Warning else C.Offline,
                            RoundedCornerShape(2.dp)))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(state.alarmSummary.title, style = MaterialTheme.typography.titleMedium)
                            Text(state.alarmSummary.detail, color = C.Muted,
                                style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            item { SectionTitle("快捷入口") }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(D.Gap)) {
                    QuickEntry("数字孪生", "完整模型与设备映射", Modifier.weight(1f), onNavigateTwin)
                    QuickEntry("历史回放", "查看真实记录与事件", Modifier.weight(1f), onNavigateReplay)
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(D.Gap)) {
                    QuickEntry("设备控制", "三路手动控制", Modifier.weight(1f), onNavigateControl)
                    QuickEntry("环境建议", "查看本地规则分析", Modifier.weight(1f), onOpenAdvice)
                }
            }
            item {
                Text("半实物原型 · 温湿度为传感器采集，光照为实验输入，土壤为软件模型。",
                    color = C.Muted, style = MaterialTheme.typography.labelSmall)
                if (BuildConfig.DEBUG) {
                    TextButton(onClick = { sheet = SheetKind.COMPONENT }) {
                        Text("公共浮层预览", style = MaterialTheme.typography.labelMedium,
                            color = C.Primary.copy(alpha = 0.8f))
                    }
                }
            }
        }
    }

    controlTarget?.let { target ->
        DeviceControlSheet(state, target, onRequest, onReserveOfflinePump, onRefresh, onResend, onDismissWait) {
            controlTarget = null
        }
    }

    if (sheet != null) {
        ModalBottomSheet(onDismissRequest = { sheet = null }, containerColor = C.Raised,
            scrimColor = C.Scrim.copy(alpha = 0.65f), shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) {
            // 浮层拥有独立窗口，跟随本应用深色视觉，不借用手机系统主题的黑色栏文字。
            val sheetView = LocalView.current
            val sheetWindow = (sheetView.parent as DialogWindowProvider).window
            SideEffect {
                WindowCompat.getInsetsController(sheetWindow, sheetWindow.decorView).apply {
                    isAppearanceLightStatusBars = false
                    isAppearanceLightNavigationBars = false
                }
            }
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp).padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                when (sheet) {
                    SheetKind.SCENARIOS -> {
                        Text("首页状态预览", style = MaterialTheme.typography.titleLarge)
                        Text("固定样例，仅检查布局与动效，不写入正式历史。", color = C.Secondary,
                            style = MaterialTheme.typography.bodyMedium)
                        DemoScenario.entries.chunked(2).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { scenario ->
                                    OutlinedButton(onClick = { selectScenario(scenario); sheet = null },
                                        modifier = Modifier.weight(1f), border = BorderStroke(1.dp,
                                            if (state.selectedDemoScenario == scenario) C.Primary else C.Outline)) {
                                        Text(scenarioLabel(scenario))
                                    }
                                }
                            }
                        }
                        TextButton(onClick = { exitDesignPreview(); sheet = null }) {
                            Text("退出设计预览，清除样例数据")
                        }
                    }
                    SheetKind.COMPONENT -> {
                        Text("公共浮层样式", style = MaterialTheme.typography.titleLarge)
                        SourceTag("设计预览")
                        Text("统一使用深绿浮层、清晰层次和完整的操作区域。此处用于确认公共样式。",
                            style = MaterialTheme.typography.bodyLarge)
                        Surface(color = C.Surface, shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, C.Outline)) {
                            Column(Modifier.padding(16.dp)) {
                                Text("设备反馈与请求进度分开呈现", style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(8.dp))
                                Text("控制以关联的设备确认作为执行依据。", color = C.Secondary)
                            }
                        }
                        Button(onClick = { sheet = null }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text("关闭样式预览")
                        }
                    }
                    null -> Unit
                }
            }
        }
    }
}

@Composable
private fun HomeHeader(state: GreenhouseTwinState, onSettings: () -> Unit, onPreview: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(C.Background.copy(alpha = 0.90f)).statusBarsPadding()
        .padding(horizontal = D.PageMargin).padding(top = 6.dp, bottom = 6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("智慧大棚", style = MaterialTheme.typography.headlineMedium)
                Text(if (state.isDesignPreview) "10 月 3 日 · 设计样例" else "环境、设备与反馈一览",
                    color = C.Muted, style = MaterialTheme.typography.labelSmall)
            }
            IconButton(onClick = onSettings, modifier = Modifier.size(48.dp)) {
                OriginalIcon(R.drawable.settings_open, Modifier.size(D.SettingsIcon).alpha(0.82f),
                    "设置")
            }
        }
        if (BuildConfig.DEBUG) {
            // 预览标识放在固定页头，滚动后也不会失去样例来源提示。
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                StatusTag(when {
                    state.isDesignPreview -> "设计预览"
                    state.configuredProductId == null || state.configuredDeviceName == null -> "连接未配置"
                    else -> "实时模式"
                }, if (state.isDesignPreview) C.Waiting else C.Secondary)
                Spacer(Modifier.weight(1f))
                val scenarioColor = when (state.selectedDemoScenario) {
                    DemoScenario.NORMAL -> C.Primary
                    DemoScenario.SOIL_WARNING, DemoScenario.DELAYED, DemoScenario.STALE -> C.Warning
                    DemoScenario.CLOUD_ERROR -> C.Critical
                    else -> C.Secondary
                }
                Surface(Modifier.heightIn(min = 36.dp).clickable(onClick = onPreview),
                    shape = RoundedCornerShape(50), color = C.Surface,
                    border = BorderStroke(1.dp, scenarioColor.copy(alpha = 0.2f))) {
                    Row(Modifier.padding(horizontal = 11.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(5.dp).background(scenarioColor, CircleShape))
                        Text(if (!state.isDesignPreview) "查看样例"
                            else if (state.selectedDemoScenario == DemoScenario.NORMAL) "系统正常"
                            else scenarioLabel(state.selectedDemoScenario), color = scenarioColor,
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun OverviewCard(state: GreenhouseTwinState, onClick: () -> Unit) {
    GreenhouseCard(Modifier.fillMaxWidth(), containerColor = C.Overview, highlight = true) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("数字大棚", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            SourceTag("半实物原型")
        }
        Box(Modifier.fillMaxWidth().height(D.ModelStage).clickable(onClick = onClick),
            contentAlignment = Alignment.Center) {
            // 光场只衬托真实模型，不修改模型材质，也不表示设备运行。
            Canvas(Modifier.fillMaxSize()) {
                val center = Offset(size.width * 0.5f, size.height * 0.83f)
                val radius = size.width * 0.38f
                withTransform({ scale(1f, size.height * 0.11f / radius, center) }) {
                    drawCircle(Brush.radialGradient(listOf(C.ModelGlow.copy(alpha = 0.12f),
                        Color.Transparent), center, radius), radius, center)
                }
            }
            GreenhouseModelPreview(state, Modifier.fillMaxSize())
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            StatusTag(when (state.deviceConnection) {
                DeviceConnection.UNKNOWN -> "设备未知"
                DeviceConnection.ONLINE -> "设备在线"
                DeviceConnection.OFFLINE -> "设备离线"
            }, when (state.deviceConnection) {
                DeviceConnection.ONLINE -> C.Primary
                DeviceConnection.OFFLINE -> C.Offline
                DeviceConnection.UNKNOWN -> C.Waiting
            })
            StatusTag(when (state.cloudQueryStatus) {
                CloudQueryStatus.SUCCESS -> "云端查询成功"
                CloudQueryStatus.FAILED -> "云端查询失败"
                CloudQueryStatus.QUERYING -> if (state.synchronizationStatus == SynchronizationStatus.INITIAL_SYNC)
                    "首次同步中" else "云端同步中"
                CloudQueryStatus.UNCONFIGURED -> "连接未配置"
                CloudQueryStatus.IDLE -> "云端未查询"
            }, when (state.cloudQueryStatus) {
                CloudQueryStatus.SUCCESS -> C.Primary
                CloudQueryStatus.FAILED -> C.Critical
                else -> C.Waiting
            })
            StatusTag(when (state.freshness) {
                DataFreshness.FRESH -> "实时数据"
                DataFreshness.DELAYED -> "数据延迟"
                DataFreshness.STALE -> "数据陈旧"
                DataFreshness.UNKNOWN -> "时效未知"
            }, when (state.freshness) {
                DataFreshness.FRESH -> C.Primary
                DataFreshness.UNKNOWN -> C.Offline
                else -> C.Warning
            })
        }
        Spacer(Modifier.height(6.dp))
        Text(if (state.lastSyncedAtUtcMs == null) "最后同步未知 · 等待有效数据"
            else "最后同步 ${clockTime(state.lastSyncedAtUtcMs)} · ${if (state.isDesignPreview) "样例时间" else "已更新"}",
            color = C.Muted, style = MaterialTheme.typography.labelSmall)
    }
}

private enum class EnvironmentKind { TEMPERATURE, HUMIDITY, SOIL, LIGHT }
private enum class DeviceKind { PUMP, LAMP, FAN }

@Composable
private fun EnvironmentCard(name: String, kind: EnvironmentKind, state: GreenhouseTwinState, modifier: Modifier) {
    val observation: Observation<out Number> = when (kind) {
        EnvironmentKind.TEMPERATURE -> state.temperature
        EnvironmentKind.HUMIDITY -> state.humidity
        EnvironmentKind.SOIL -> state.soilHumidity
        EnvironmentKind.LIGHT -> state.lightLevel
    }
    val value = observation.value?.toDouble()
    val valid = observation.validity == ObservationValidity.VALID && value != null && value.isFinite() &&
        (kind != EnvironmentKind.LIGHT || value.toInt() in 0..7)
    val metric = when (kind) {
        EnvironmentKind.TEMPERATURE -> HistoryMetric.TEMPERATURE
        EnvironmentKind.HUMIDITY -> HistoryMetric.HUMIDITY
        EnvironmentKind.SOIL -> HistoryMetric.SOIL
        EnvironmentKind.LIGHT -> HistoryMetric.LIGHT
    }
    val warningLabel = if (state.isDesignPreview && kind == EnvironmentKind.SOIL &&
        state.selectedDemoScenario == DemoScenario.SOIL_WARNING) "偏低 · 设计警告"
        else if (state.stateSource == StateSource.LIVE && state.canPresentCurrentActivity)
            state.alarmSummary.metricWarnings[metric] else null
    val warning = warningLabel != null
    val (visible, visibilityModifier) = rememberVisibleMotion()
    val active = visible && valid && state.canPresentCurrentActivity
    GreenhouseCard(modifier, if (warning) C.Warning.copy(alpha = 0.6f) else C.Outline) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.bodyMedium, color = C.Text)
            }
            Box(Modifier.size(D.EnvironmentIcon).then(visibilityModifier)
                .businessIconSemantics("$name，${if (valid) "已取得有效值" else "状态未知"}")) {
                if (valid) {
                    when (kind) {
                        EnvironmentKind.TEMPERATURE -> AnimatedTemperatureIcon(
                            ((value!!.toFloat() - 0f) / 50f).coerceIn(0f, 1f), Modifier.fillMaxSize(),
                            interactionEnabled = false, motionEnabled = active, glowScale = 1f)
                        EnvironmentKind.HUMIDITY -> AnimatedAirHumidityIcon(value!!.toFloat(), Modifier.fillMaxSize(),
                            interactionEnabled = false, motionEnabled = active, glowScale = 0.7f)
                        EnvironmentKind.SOIL -> AnimatedSoilMoistureIcon(value!!.toFloat() / 100f, Modifier.fillMaxSize(),
                            interactionEnabled = false, motionEnabled = active, glowScale = 0.7f)
                        EnvironmentKind.LIGHT -> AnimatedLightIcon(value!!.toInt(), Modifier.fillMaxSize(),
                            interactionEnabled = false, motionEnabled = active, glowScale = 1f)
                    }
                } else {
                    OriginalIcon(when (kind) {
                        EnvironmentKind.TEMPERATURE -> IconR.drawable.env_temperature
                        EnvironmentKind.HUMIDITY -> IconR.drawable.env_air_humidity
                        EnvironmentKind.SOIL -> IconR.drawable.env_soil_moisture
                        EnvironmentKind.LIGHT -> IconR.drawable.env_light
                    }, Modifier.fillMaxSize())
                    Box(Modifier.fillMaxSize().background(C.Surface.copy(alpha = 0.65f)))
                    Text("未知", Modifier.align(Alignment.Center), color = C.Secondary,
                        style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(if (!valid) "—" else when (kind) {
                EnvironmentKind.TEMPERATURE -> String.format(Locale.ROOT, "%.1f", value)
                EnvironmentKind.LIGHT -> value!!.toInt().toString()
                else -> String.format(Locale.ROOT, "%.0f", value)
            }, style = MaterialTheme.typography.displaySmall, color = if (warning) C.Warning else C.Text)
            Text(when (kind) {
                EnvironmentKind.TEMPERATURE -> "℃"
                EnvironmentKind.LIGHT -> "/ 7 档"
                else -> "%"
            }, color = C.Secondary, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 4.dp))
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            SourceTag(when (observation.source) {
                ObservationSource.SENSOR -> "SENSOR"
                ObservationSource.SIMULATION -> "SIM"
                ObservationSource.EXPERIMENT -> "EXP"
            })
            Text(when {
            !valid && observation.validity == ObservationValidity.READ_ERROR -> "读取失败 · 缺测"
            !valid -> "等待首次有效数据"
            warning -> checkNotNull(warningLabel)
            observation.sampledAtUtcMs == null -> "采样时间未知"
            state.cloudQueryStatus == CloudQueryStatus.FAILED ||
                (!state.isDesignPreview && state.synchronizationSuspended) ||
                state.freshness == DataFreshness.STALE ||
                state.deviceConnection == DeviceConnection.OFFLINE -> "最后已知 · ${clockTime(observation.sampledAtUtcMs)}"
            state.freshness == DataFreshness.DELAYED -> "更新延迟 · ${clockTime(observation.sampledAtUtcMs)}"
            state.freshness == DataFreshness.UNKNOWN -> "时效未知 · ${clockTime(observation.sampledAtUtcMs)}"
            else -> "${clockTime(observation.sampledAtUtcMs)} 采样"
            }, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                color = if (warning) C.Warning else C.Muted)
        }
    }
}

@Composable
private fun DeviceCard(name: String, kind: DeviceKind, actuator: ActuatorState, state: GreenhouseTwinState,
    modifier: Modifier, onClick: () -> Unit) {
    val (visible, visibilityModifier) = rememberVisibleMotion()
    val active = state.canPresentCurrentActivity && visible
    val known = actuator.reported != ReportedState.UNKNOWN
    val on = actuator.reported == ReportedState.ON
    GreenhouseCard(modifier.clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().height(D.DeviceIcon).then(visibilityModifier)
            .businessIconSemantics("$name，${reportedLabel(actuator.reported)}，运行示意"), contentAlignment = Alignment.TopEnd) {
            if (known) when (kind) {
                DeviceKind.PUMP -> AnimatedWaterPumpIcon(if (on) WaterPumpState.Running else WaterPumpState.Stopped,
                    if (on) 0.6f else 0f, Modifier.size(D.DeviceIcon), motionEnabled = active)
                DeviceKind.LAMP -> AnimatedGrowLightIcon(if (on) GrowLightState.On else GrowLightState.Off,
                    if (on) 0.65f else 0f, Modifier.size(D.DeviceIcon), motionEnabled = active)
                DeviceKind.FAN -> AnimatedFanIcon(if (on) FanState.Running else FanState.Stopped,
                    if (on) 0.6f else 0f, Modifier.size(D.DeviceIcon), motionEnabled = active)
            } else {
                OriginalIcon(when (kind) {
                    DeviceKind.PUMP -> IconR.drawable.device_water_pump
                    DeviceKind.LAMP -> IconR.drawable.device_grow_light
                    DeviceKind.FAN -> IconR.drawable.device_fan
                }, Modifier.size(D.DeviceIcon))
                Box(Modifier.size(D.DeviceIcon).background(C.Surface.copy(alpha = 0.7f)))
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        val statusColor = when (actuator.reported) {
            ReportedState.ON -> if (state.canPresentCurrentActivity) C.Running else C.Offline
            ReportedState.OFF -> C.Offline
            ReportedState.UNKNOWN -> C.Waiting
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Box(Modifier.size(5.dp).background(statusColor, CircleShape))
            Text(reportedLabel(actuator.reported), color = statusColor,
                style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(5.dp))
        Text(if (!known) "模式待同步" else "手动${if (!state.canPresentCurrentActivity) " · 最后已知" else ""}",
            color = C.Muted, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun QuickEntry(title: String, detail: String, modifier: Modifier, onClick: () -> Unit) {
    GreenhouseCard(modifier.clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text("›", color = C.Muted, style = MaterialTheme.typography.bodyMedium)
        }
        Text(detail, color = C.Muted, style = MaterialTheme.typography.labelSmall)
    }
}


private fun reportedLabel(state: ReportedState) = when (state) {
    ReportedState.UNKNOWN -> "状态未知"
    ReportedState.ON -> "开启"
    ReportedState.OFF -> "关闭"
}

private fun scenarioLabel(scenario: DemoScenario?) = when (scenario) {
    DemoScenario.INITIAL_SYNC -> "首次未知"
    DemoScenario.NORMAL -> "正常样例"
    DemoScenario.SOIL_WARNING -> "土壤警告"
    DemoScenario.MISSING -> "部分缺测"
    DemoScenario.DELAYED -> "数据延迟"
    DemoScenario.STALE -> "数据陈旧"
    DemoScenario.CLOUD_ERROR -> "云端失败"
    DemoScenario.OFFLINE -> "设备离线"
    null -> "状态样例"
}

private fun clockTime(utcMs: Long?): String {
    if (utcMs == null) return "时间未知"
    return SimpleDateFormat("HH:mm:ss", Locale.CHINA).apply {
        timeZone = TimeZone.getTimeZone("Asia/Shanghai")
    }.format(Date(utcMs))
}
