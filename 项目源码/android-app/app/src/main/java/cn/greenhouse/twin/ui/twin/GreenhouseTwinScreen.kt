package cn.greenhouse.twin.ui.twin

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import cn.greenhouse.twin.domain.*
import cn.greenhouse.twin.ui.components.*
import cn.greenhouse.twin.ui.control.ControlDeviceIcon
import cn.greenhouse.twin.ui.control.DeviceControlSheet
import cn.greenhouse.twin.ui.control.actuator
import cn.greenhouse.twin.ui.control.controlTime
import cn.greenhouse.twin.ui.control.feedbackLabel
import cn.greenhouse.twin.ui.control.requestLabel
import cn.greenhouse.twin.ui.scene.GreenhouseModelPreview
import cn.greenhouse.twin.ui.scene.GreenhouseViewPreset
import cn.greenhouse.twin.ui.scene.SceneObject
import cn.greenhouse.twin.ui.theme.GreenhouseColors as C
import cn.greenhouse.twin.ui.theme.GreenhouseDimensions as D
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GreenhouseTwinScreen(
    state: GreenhouseTwinState,
    onNavigateHome: () -> Unit,
    onNavigateControl: () -> Unit,
    onRequest: (ActuatorKey, Boolean) -> Unit,
    onReserveOfflinePump: (Boolean, SnapshotIdentity) -> Unit,
    onRefresh: () -> Unit,
    onResend: () -> Unit,
    onDismissWait: () -> Unit,
    onNavigateData: (SceneObject?) -> Unit,
    onNavigateAlarm: () -> Unit,
    onOpenAdvice: () -> Unit,
    initialObject: SceneObject? = null,
    onFocusConsumed: () -> Unit = {},
) {
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var preset by rememberSaveable { mutableStateOf(GreenhouseViewPreset.COMPLETE) }
    var showLabels by rememberSaveable { mutableStateOf(true) }
    var selectedObject by rememberSaveable { mutableStateOf<SceneObject?>(null) }
    var objectPanel by remember { mutableStateOf<SceneObject?>(null) }
    var deferred by remember { mutableStateOf<String?>(null) }
    val activity = LocalContext.current as Activity
    val scroll = rememberScrollState()

    // 报警定位复用既有模型选中和详情，不创建新的三维场景。
    LaunchedEffect(initialObject) {
        initialObject?.let {
            selectedObject = it
            objectPanel = it
            onFocusConsumed()
        }
    }

    // 全屏保持同一场景调用位置，只调整容器和相机预设；退出后恢复方向和系统栏。
    DisposableEffect(fullscreen, activity) {
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        if (fullscreen) {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            if (fullscreen) {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
    BackHandler(enabled = fullscreen) { fullscreen = false }

    Scaffold(
        containerColor = C.Background,
        contentColor = C.Text,
        contentWindowInsets = if (fullscreen) WindowInsets(0, 0, 0, 0) else WindowInsets.safeDrawing,
        topBar = {
            if (!fullscreen) {
                Row(Modifier.fillMaxWidth().statusBarsPadding()
                    .padding(horizontal = D.PageMargin, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("数字孪生", style = MaterialTheme.typography.headlineMedium)
                        Text("完整三维大棚 · 半实物原型", color = C.Muted,
                            style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton(onClick = { showLabels = !showLabels }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(if (showLabels) "隐藏标签" else "显示标签")
                    }
                    TextButton(onClick = { fullscreen = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("全屏")
                    }
                }
            }
        },
        bottomBar = {
            if (!fullscreen) {
                GreenhouseNavigation(state, "数字孪生") { destination ->
                    when (destination) {
                        "首页" -> onNavigateHome()
                        "数据" -> onNavigateData(null)
                        "报警" -> onNavigateAlarm()
                        else -> deferred = destination
                    }
                }
            }
        },
    ) { insets ->
        Column(
            Modifier.fillMaxSize().padding(insets)
                .then(if (!fullscreen) Modifier.verticalScroll(scroll) else Modifier)
                .padding(horizontal = if (fullscreen) 0.dp else D.PageMargin),
            verticalArrangement = Arrangement.spacedBy(if (fullscreen) 0.dp else 10.dp),
        ) {
            if (!fullscreen) {
                TwinConnectionStatus(state)
                TwinMetricSummary(state)
                PresetSelector(preset) { preset = it }
            }
            key("greenhouse-twin-scene") {
                Surface(
                    Modifier.fillMaxWidth().then(if (fullscreen) Modifier.weight(1f) else Modifier.height(350.dp)),
                    color = C.Overview,
                    shape = RoundedCornerShape(if (fullscreen) 0.dp else D.CardRadius),
                    border = if (fullscreen) null else BorderStroke(1.dp, C.Outline),
                ) {
                    Box(Modifier.fillMaxSize()) {
                        GreenhouseModelPreview(
                            state = state,
                            modifier = Modifier.fillMaxSize(),
                            interactive = true,
                            preset = preset,
                            selectedObject = selectedObject,
                            showLabels = showLabels,
                            labelContentInsets = if (fullscreen) PaddingValues(start = 12.dp, end = 12.dp,
                                top = 68.dp, bottom = 76.dp) else PaddingValues(6.dp),
                            onObjectSelected = { selected -> selectedObject = selected; objectPanel = selected },
                        )
                        if (fullscreen) {
                            Surface(Modifier.align(Alignment.TopStart).padding(12.dp),
                                color = C.Raised.copy(alpha = 0.95f), shape = RoundedCornerShape(14.dp)) {
                                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                    Text(when {
                                        state.isDesignPreview -> "设计预览 · 控制禁用"
                                        state.canPresentCurrentActivity -> "实时反馈 · MANUAL"
                                        state.deviceConnection == DeviceConnection.OFFLINE -> "设备离线 · 动态暂停"
                                        state.freshness == DataFreshness.STALE -> "数据陈旧 · 动态暂停"
                                        else -> "当前状态待核实 · 动态暂停"
                                    }, color = if (state.canPresentCurrentActivity) C.Primary else C.Waiting,
                                        style = MaterialTheme.typography.labelMedium)
                                    Text("采样 ${controlTime(state.snapshotUtcMs)}",
                                        color = C.Muted, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            Row(Modifier.align(Alignment.TopEnd).padding(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Surface(color = C.Raised.copy(alpha = 0.95f), shape = RoundedCornerShape(14.dp)) {
                                    TextButton(onClick = { showLabels = !showLabels }, modifier = Modifier.heightIn(min = 48.dp)) {
                                        Text(if (showLabels) "隐藏标签" else "显示标签")
                                    }
                                }
                                Surface(color = C.Raised.copy(alpha = 0.95f), shape = RoundedCornerShape(14.dp)) {
                                    TextButton(onClick = { fullscreen = false }, modifier = Modifier.heightIn(min = 48.dp)) {
                                        Text("退出全屏")
                                    }
                                }
                            }
                            Surface(Modifier.align(Alignment.BottomCenter).padding(12.dp),
                                color = C.Raised.copy(alpha = 0.95f), shape = RoundedCornerShape(14.dp)) {
                                PresetSelector(preset) { preset = it }
                            }
                        }
                    }
                }
            }
            if (!fullscreen) {
                Text("拖动旋转，双指缩放；点击设备或数据标签查看详情。", color = C.Muted,
                    style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("设备反馈", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = onNavigateControl, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("设备控制")
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActuatorKey.entries.forEach { target ->
                        GreenhouseCard(Modifier.weight(1f).clickable {
                            val selected = target.sceneObject()
                            selectedObject = selected
                            objectPanel = selected
                        }) {
                            ControlDeviceIcon(state, target)
                            Spacer(Modifier.height(6.dp))
                            Text(target.label, style = MaterialTheme.typography.bodyMedium)
                            Text(feedbackLabel(actuator(state, target).reported), color = when (actuator(state, target).reported) {
                                ReportedState.UNKNOWN -> C.Waiting
                                ReportedState.ON -> if (state.canPresentCurrentActivity) C.Running else C.Offline
                                ReportedState.OFF -> C.Offline
                            }, style = MaterialTheme.typography.labelMedium)
                            Text(if (state.canPresentCurrentActivity) "手动" else "最后已知",
                                color = C.Muted, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                GreenhouseCard {
                    Text("最近控制请求", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    val request = state.controlRequest
                    if (request == null) {
                        Text("本次运行尚未发出控制请求。", color = C.Muted,
                            style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text("${request.target.label}目标${if (request.requestedState) "开启" else "关闭"} · ${requestLabel(request.status)}",
                            color = C.Secondary, style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { objectPanel = request.target.sceneObject() }) { Text("查看目标、反馈与请求") }
                    }
                    TextButton(onClick = onOpenAdvice, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("环境建议 · 本地规则分析", color = C.Advice)
                    }
                }
                GreenhouseCard {
                    Text("最近设备反馈", style = MaterialTheme.typography.titleMedium)
                    Text("本次打开收到的设备命令结果", color = C.Muted, style = MaterialTheme.typography.labelSmall)
                    if (state.recentDeviceResults.isEmpty()) {
                        Text("尚未取得设备命令结果。", color = C.Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    state.recentDeviceResults.forEach { result ->
                        Spacer(Modifier.height(10.dp))
                        Text("${result.target?.label ?: "设备命令"} · ${when (result.result) {
                            "EXECUTED" -> "已执行"
                            "EXPIRED" -> "已过期，未执行"
                            "INTERRUPTED" -> "执行中断"
                            "BOOT_MISMATCH" -> "启动标识不匹配"
                            else -> "设备拒绝执行"
                        }}${if (result.duplicate) " · 重复请求回执" else ""}",
                            color = if (result.result == "EXECUTED") C.Primary else C.Waiting,
                            style = MaterialTheme.typography.bodyMedium)
                        Text("${controlTime(result.utcMs)} · ${if (state.snapshotIdentity?.bootId == result.bootId)
                            "当前启动" else "其他启动"} · ${result.commandId}", color = C.Muted,
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
                Text("温湿度 SENSOR · 光照 EXP · 土壤 SIM。模型中的压力表和探针为演示外观。",
                    color = C.Muted, style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(14.dp))
            }
        }
    }

    objectPanel?.let { selected ->
        val target = selected.actuatorKey()
        if (target != null) {
            DeviceControlSheet(state, target, onRequest, onReserveOfflinePump, onRefresh, onResend, onDismissWait) { objectPanel = null }
        } else {
            TwinObjectSheet(state, selected, {
                objectPanel = null
                onNavigateData(selected)
            }) { objectPanel = null }
        }
    }
    deferred?.let { name ->
        ModalBottomSheet(onDismissRequest = { deferred = null }, containerColor = C.Raised,
            scrimColor = C.Scrim.copy(alpha = 0.65f)) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(name, style = MaterialTheme.typography.titleLarge)
                Text("该功能尚未开放。", color = C.Secondary, style = MaterialTheme.typography.bodyLarge)
                Button(onClick = { deferred = null }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("返回数字孪生")
                }
            }
        }
    }
}

@Composable
private fun TwinConnectionStatus(state: GreenhouseTwinState) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        StatusTag(when (state.deviceConnection) {
            DeviceConnection.UNKNOWN -> "设备未知"
            DeviceConnection.ONLINE -> "设备在线"
            DeviceConnection.OFFLINE -> "设备离线"
        }, if (state.deviceConnection == DeviceConnection.ONLINE) C.Primary else C.Waiting)
        StatusTag(when (state.cloudQueryStatus) {
            CloudQueryStatus.UNCONFIGURED -> "云端未配置"
            CloudQueryStatus.IDLE -> "云端待查询"
            CloudQueryStatus.QUERYING -> "云端查询中"
            CloudQueryStatus.SUCCESS -> "云端查询成功"
            CloudQueryStatus.FAILED -> "云端查询失败"
        }, if (state.cloudQueryStatus == CloudQueryStatus.FAILED) C.Critical else C.Secondary)
        StatusTag(when (state.freshness) {
            DataFreshness.UNKNOWN -> "时效未知"
            DataFreshness.FRESH -> "数据实时"
            DataFreshness.DELAYED -> "数据延迟"
            DataFreshness.STALE -> "数据陈旧"
        }, if (state.freshness == DataFreshness.FRESH) C.Primary else C.Waiting)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(if (state.isDesignPreview) "设计预览 · 真实控制禁用" else "MANUAL · 半实物原型",
            color = if (state.isDesignPreview) C.Waiting else C.Muted, style = MaterialTheme.typography.labelSmall)
        Text("同步 ${controlTime(state.lastSyncedAtUtcMs)}", color = C.Muted,
            style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun TwinMetricSummary(state: GreenhouseTwinState) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(SceneObject.TEMPERATURE, SceneObject.HUMIDITY, SceneObject.LIGHT, SceneObject.SOIL).forEach { obj ->
            val info = observationInfo(state, obj)
            GreenhouseCard(Modifier.weight(1f)) {
                Text(info.title, color = C.Muted, style = MaterialTheme.typography.labelSmall)
                Text(info.value, style = MaterialTheme.typography.bodyMedium)
                Text(info.source, color = C.Muted, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun PresetSelector(selected: GreenhouseViewPreset, onSelected: (GreenhouseViewPreset) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        GreenhouseViewPreset.entries.forEach { preset ->
            FilterChip(selected = preset == selected, onClick = { onSelected(preset) },
                label = { Text(when (preset) {
                    GreenhouseViewPreset.COMPLETE -> "完整"
                    GreenhouseViewPreset.OVERVIEW -> "概览"
                    GreenhouseViewPreset.INTERIOR -> "内部"
                    GreenhouseViewPreset.TOP -> "俯视"
                    GreenhouseViewPreset.DETAIL -> "细节"
                }) }, colors = FilterChipDefaults.filterChipColors(containerColor = C.Surface,
                    labelColor = C.Secondary, selectedContainerColor = C.Raised,
                    selectedLabelColor = C.Primary), modifier = Modifier.heightIn(min = 48.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TwinObjectSheet(state: GreenhouseTwinState, obj: SceneObject, onViewTrend: () -> Unit, onClose: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onClose, containerColor = C.Raised,
        scrimColor = C.Scrim.copy(alpha = 0.65f),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) {
        val view = LocalView.current
        val window = (view.parent as DialogWindowProvider).window
        SideEffect {
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (obj == SceneObject.CONTROLLER) {
                Text("ESP32 控制节点", style = MaterialTheme.typography.titleLarge)
                Text(state.configuredDeviceName ?: "设备名称未知", color = C.Secondary)
                Text("三路 MANUAL · 软件土壤模型 · 光照实验输入", color = C.Muted,
                    style = MaterialTheme.typography.bodyMedium)
                Text("当前启动 · ${state.snapshotIdentity?.bootId ?: "尚未同步"}",
                    color = C.Muted, style = MaterialTheme.typography.bodySmall)
                Text("采样 · ${controlTime(state.snapshotUtcMs)}", color = C.Muted,
                    style = MaterialTheme.typography.bodySmall)
            } else {
                val info = observationInfo(state, obj)
                Text(info.title, style = MaterialTheme.typography.titleLarge)
                Text(info.value, style = MaterialTheme.typography.displaySmall)
                SourceTag(info.source)
                Text(info.validity, color = C.Secondary, style = MaterialTheme.typography.bodyMedium)
                Text("采样 · ${controlTime(info.sampledAtUtcMs)}", color = C.Muted,
                    style = MaterialTheme.typography.bodySmall)
                if (!state.canPresentCurrentActivity && info.validity == "有效值") {
                    Text("这是最后已知数值，当前不能视为实时状态。", color = C.Waiting,
                        style = MaterialTheme.typography.bodyMedium)
                }
                OutlinedButton(onClick = onViewTrend, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("查看趋势")
                }
            }
            Button(onClick = onClose, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("关闭详情") }
        }
    }
}

private data class ObservationInfo(val title: String, val value: String, val source: String,
    val validity: String, val sampledAtUtcMs: Long?)

private fun observationInfo(state: GreenhouseTwinState, obj: SceneObject): ObservationInfo {
    val observation: Observation<out Number>
    val title: String
    val unit: String
    when (obj) {
        SceneObject.TEMPERATURE -> { observation = state.temperature; title = "温度"; unit = "℃" }
        SceneObject.HUMIDITY -> { observation = state.humidity; title = "空气湿度"; unit = "%" }
        SceneObject.LIGHT -> { observation = state.lightLevel; title = "光照"; unit = "/7档" }
        SceneObject.SOIL -> { observation = state.soilHumidity; title = "土壤"; unit = "%" }
        else -> error("该对象不是环境指标")
    }
    val valid = observation.validity == ObservationValidity.VALID && observation.value != null
    val value = if (!valid) "—" else if (obj == SceneObject.LIGHT) "${observation.value!!.toInt()} $unit"
        else String.format(Locale.ROOT, if (obj == SceneObject.TEMPERATURE) "%.1f %s" else "%.0f %s",
            observation.value!!.toDouble(), unit)
    return ObservationInfo(title, value, when (observation.source) {
        ObservationSource.SENSOR -> "SENSOR"
        ObservationSource.EXPERIMENT -> "EXP"
        ObservationSource.SIMULATION -> "SIM"
    }, when (observation.validity) {
        ObservationValidity.VALID -> "有效值"
        ObservationValidity.NOT_READY -> "等待首次有效数据"
        ObservationValidity.READ_ERROR -> "读取失败 · 缺测"
    }, observation.sampledAtUtcMs)
}

private fun ActuatorKey.sceneObject() = when (this) {
    ActuatorKey.PUMP -> SceneObject.PUMP
    ActuatorKey.LAMP -> SceneObject.LAMP
    ActuatorKey.FAN -> SceneObject.FAN
}

private fun SceneObject.actuatorKey(): ActuatorKey? = when (this) {
    SceneObject.PUMP -> ActuatorKey.PUMP
    SceneObject.LAMP -> ActuatorKey.LAMP
    SceneObject.FAN -> ActuatorKey.FAN
    else -> null
}
