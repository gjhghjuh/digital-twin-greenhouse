package cn.greenhouse.twin.ui.control

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.greenhouse.twin.domain.*
import cn.greenhouse.twin.ui.components.*
import cn.greenhouse.twin.ui.theme.GreenhouseColors as C
import cn.greenhouse.twin.ui.theme.GreenhouseDimensions as D
import com.example.environmenticonmotion.R as IconR
import com.example.environmenticonmotion.ui.motion.*

@Composable
fun DeviceControlScreen(
    state: GreenhouseTwinState,
    onBack: () -> Unit,
    onRequest: (ActuatorKey, Boolean) -> Unit,
    onReserveOfflinePump: (Boolean, SnapshotIdentity) -> Unit,
    onRefresh: () -> Unit,
    onResend: () -> Unit,
    onDismissWait: () -> Unit,
    onRequestScene: (ManualScenePreset, SnapshotIdentity) -> Unit,
    onStopSceneRemaining: () -> Unit,
) {
    var selected by remember { mutableStateOf<ActuatorKey?>(null) }
    Scaffold(
        containerColor = C.Background,
        contentColor = C.Text,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = D.PageMargin, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text("返回") }
                Text("设备控制", modifier = Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
                TextButton(onClick = onRefresh, enabled = state.stateSource == StateSource.LIVE &&
                    !state.synchronizationSuspended, modifier = Modifier.heightIn(min = 48.dp)) { Text("查询") }
            }
        },
    ) { insets ->
        LazyColumn(
            Modifier.fillMaxSize().padding(insets),
            contentPadding = PaddingValues(start = D.PageMargin, end = D.PageMargin, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("实时手动控制", style = MaterialTheme.typography.titleLarge)
                Text("目标与设备反馈分开显示，关联确认后才认定执行。",
                    color = C.Secondary, style = MaterialTheme.typography.bodyMedium)
                if (state.isDesignPreview) StatusTag("设计预览 · 禁止真实控制", C.Waiting)
            }
            state.controlRequest?.let {
                item { ControlRequestProgress(state, onRefresh, onResend, onDismissWait) }
            }
            item { ManualScenePanel(state, onRequestScene, onStopSceneRemaining) }
            item { SectionTitle("设备") }
            ActuatorKey.entries.forEach { target ->
                item { ControlDeviceCard(state, target, onRequest) { selected = target } }
            }
            item {
                GreenhouseCard {
                    Text("当前能力", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text("三路 MANUAL 开关。水泵、补光灯、风扇是虚拟执行器，对应三路外接 LED。",
                        color = C.Secondary, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    Text("水泵支持 60 秒离线预约，手动场景逐项发送并确认。自动规则尚未开放。没有流量、转速或调光百分比的实测反馈。",
                        color = C.Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    selected?.let { target ->
        DeviceControlSheet(state, target, onRequest, onReserveOfflinePump, onRefresh, onResend, onDismissWait) { selected = null }
    }
}

@Composable
private fun ControlDeviceCard(
    state: GreenhouseTwinState,
    target: ActuatorKey,
    onRequest: (ActuatorKey, Boolean) -> Unit,
    onDetails: () -> Unit,
) {
    GreenhouseCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ControlDeviceIcon(state, target)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(target.label, style = MaterialTheme.typography.titleLarge)
                Text("设备反馈 · ${feedbackLabel(actuator(state, target).reported)}", color = C.Secondary,
                    style = MaterialTheme.typography.bodyMedium)
                Text("${if (state.canPresentCurrentActivity) "当前反馈" else "最后已知"} · ${controlTime(state.snapshotUtcMs)}",
                    color = C.Muted, style = MaterialTheme.typography.labelSmall)
            }
            TextButton(onClick = onDetails, modifier = Modifier.heightIn(min = 48.dp)) { Text("详情") }
        }
        Spacer(Modifier.height(12.dp))
        ManualControls(state, target, onRequest)
        if (target == ActuatorKey.PUMP) {
            TextButton(onClick = onDetails, modifier = Modifier.heightIn(min = 48.dp)) { Text("预约期望状态") }
        }
        state.controlRequest?.takeIf { it.target == target }?.let {
            Text("目标${if (it.requestedState) "开启" else "关闭"} · ${requestLabel(it.status)}",
                color = C.Waiting, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun ControlDeviceIcon(state: GreenhouseTwinState, target: ActuatorKey, modifier: Modifier = Modifier) {
    val (visible, visibility) = rememberVisibleMotion()
    val reported = actuator(state, target).reported
    val running = reported == ReportedState.ON
    val active = state.canPresentCurrentActivity && visible
    Box(modifier.size(D.DeviceIcon).then(visibility)
        .businessIconSemantics("${target.label}，${feedbackLabel(reported)}，运行示意"), contentAlignment = Alignment.Center) {
        if (reported == ReportedState.UNKNOWN) {
            OriginalIcon(when (target) {
                ActuatorKey.PUMP -> IconR.drawable.device_water_pump
                ActuatorKey.LAMP -> IconR.drawable.device_grow_light
                ActuatorKey.FAN -> IconR.drawable.device_fan
            }, Modifier.fillMaxSize())
            Surface(Modifier.fillMaxSize(), color = C.Surface.copy(alpha = 0.75f)) {}
            Text("未知", color = C.Secondary, style = MaterialTheme.typography.labelSmall)
        } else when (target) {
            ActuatorKey.PUMP -> AnimatedWaterPumpIcon(if (running) WaterPumpState.Running else WaterPumpState.Stopped,
                if (running) 0.6f else 0f, Modifier.fillMaxSize(), motionEnabled = active)
            ActuatorKey.LAMP -> AnimatedGrowLightIcon(if (running) GrowLightState.On else GrowLightState.Off,
                if (running) 0.65f else 0f, Modifier.fillMaxSize(), motionEnabled = active)
            ActuatorKey.FAN -> AnimatedFanIcon(if (running) FanState.Running else FanState.Stopped,
                if (running) 0.6f else 0f, Modifier.fillMaxSize(), motionEnabled = active)
        }
    }
}
