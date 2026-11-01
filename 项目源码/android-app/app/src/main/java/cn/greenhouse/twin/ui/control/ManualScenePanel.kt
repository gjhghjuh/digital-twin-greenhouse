package cn.greenhouse.twin.ui.control

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.greenhouse.twin.domain.*
import cn.greenhouse.twin.ui.components.GreenhouseCard
import cn.greenhouse.twin.ui.components.SectionTitle
import cn.greenhouse.twin.ui.components.SourceTag
import cn.greenhouse.twin.ui.components.StatusTag
import cn.greenhouse.twin.ui.history.historyDateTime
import cn.greenhouse.twin.ui.theme.GreenhouseColors as C

// 场景只展示统一仓库的编排结果，页面重组和导航不会触发新的设备命令。
@Composable
fun ManualScenePanel(
    state: GreenhouseTwinState,
    onRequestScene: (ManualScenePreset, SnapshotIdentity) -> Unit,
    onStopSceneRemaining: () -> Unit,
) {
    var selectedPreset by remember { mutableStateOf<ManualScenePreset?>(null) }
    var selectedIdentity by remember { mutableStateOf<SnapshotIdentity?>(null) }
    val currentIdentity = state.snapshotIdentity
    val canConfirm = state.canStartScene && selectedIdentity != null &&
        currentIdentity?.deviceId == selectedIdentity?.deviceId &&
        currentIdentity?.bootId == selectedIdentity?.bootId &&
        currentIdentity?.configVersion == selectedIdentity?.configVersion

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle("手动场景")
        Text("点击后按顺序应用 MANUAL 目标，每项取得关联确认后才继续。",
            color = C.Secondary, style = MaterialTheme.typography.bodyMedium)
        ManualScenePreset.entries.forEach { preset ->
            GreenhouseCard {
                Text(preset.label, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                SourceTag("MANUAL · 手动场景")
                Spacer(Modifier.height(8.dp))
                Text(preset.description, color = C.Secondary,
                    style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(10.dp))
                SceneTargetSummary(preset)
                Spacer(Modifier.height(10.dp))
                Button(onClick = {
                    selectedIdentity = currentIdentity
                    selectedPreset = preset
                }, enabled = state.canStartScene,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("确认${preset.label}场景")
                }
            }
        }
        state.sceneBlockedReason?.let { reason ->
            Text(reason, color = C.Waiting, style = MaterialTheme.typography.bodySmall)
        }
        state.sceneExecution?.let { execution ->
            SceneExecutionResults(execution, onStopSceneRemaining)
        }
    }

    selectedPreset?.let { preset ->
        AlertDialog(
            onDismissRequest = { selectedPreset = null },
            containerColor = C.Raised,
            title = { Text("确认${preset.label}场景") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("本次按以下顺序发送 MANUAL 目标：")
                    SceneTargetSummary(preset)
                    Text("当前反馈采样 · ${historyDateTime(state.snapshotUtcMs)}")
                    if (preset == ManualScenePreset.VENTILATION) {
                        Text("高温通风由本次点击执行，不按温度自动触发。")
                    }
                    Text("每项需取得设备关联确认；超时、拒绝或条件变化会停止后续步骤，并保留逐项结果。")
                    Text("关闭确认窗口不会发送命令。开始后停止后续步骤不会撤回已发送请求，也不会回滚已执行的设备状态。")
                    if (!canConfirm) {
                        Text(state.sceneBlockedReason ?: "设备启动或配置已变化，请重新打开场景确认。",
                            color = C.Waiting)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (canConfirm) {
                        selectedPreset = null
                        onRequestScene(preset, checkNotNull(selectedIdentity))
                    }
                }, enabled = canConfirm, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("开始逐项执行")
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedPreset = null }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("返回")
                }
            },
        )
    }
}

@Composable
private fun SceneTargetSummary(preset: ManualScenePreset) {
    preset.steps.forEachIndexed { index, step ->
        Text("${index + 1}. ${step.target.label} · MANUAL → ${if (step.requestedState) "开启" else "关闭"}",
            style = MaterialTheme.typography.bodyMedium)
    }
    val unchanged = ActuatorKey.entries.filter { target -> preset.steps.none { it.target == target } }
    if (unchanged.isNotEmpty()) {
        Text("${unchanged.joinToString("、") { it.label }}保持当前状态，本场景不发送对应命令。",
            color = C.Muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SceneExecutionResults(
    execution: SceneExecutionState,
    onStopSceneRemaining: () -> Unit,
) {
    val color = when (execution.status) {
        SceneExecutionStatus.SUCCESS -> C.Primary
        SceneExecutionStatus.PARTIAL -> C.Warning
        SceneExecutionStatus.RUNNING -> C.Waiting
        SceneExecutionStatus.STOPPED -> C.Offline
    }
    val statusLabel = when (execution.status) {
        SceneExecutionStatus.RUNNING -> "正在逐项执行"
        SceneExecutionStatus.SUCCESS -> "全部已确认"
        SceneExecutionStatus.PARTIAL -> "部分完成"
        SceneExecutionStatus.STOPPED -> "已停止后续步骤"
    }
    GreenhouseCard(outline = color.copy(alpha = 0.4f)) {
        Text("${execution.preset.label} · 场景结果", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        StatusTag("$statusLabel · 已确认 ${execution.confirmedStepCount}/${execution.steps.size} 项", color)
        Spacer(Modifier.height(10.dp))
        // 停止说明保存的是当时经历，当前确认结果由逐项记录展示。
        val resultDetail = if (!execution.isRunning && execution.detail.contains("停止"))
            "原停止记录：${execution.detail}" else execution.detail
        Text(resultDetail, color = C.Secondary, style = MaterialTheme.typography.bodyMedium)
        execution.steps.forEachIndexed { index, step ->
            Spacer(Modifier.height(14.dp))
            SceneStepResult(index, step, execution)
        }
        Spacer(Modifier.height(14.dp))
        Text("逐项结果记录本次命令的确认；当前设备状态仍以最新有效反馈为准。",
            color = C.Muted, style = MaterialTheme.typography.bodySmall)
        if (execution.isRunning) {
            TextButton(onClick = onStopSceneRemaining, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("停止后续步骤")
            }
        }
        Text("停止后续不会撤回已发送请求，也不会回滚已执行状态；未确认的请求仍需核实。",
            color = C.Muted, style = MaterialTheme.typography.bodySmall)
    }
}

// 历史步骤保留各自关联记录，只读详情不接入当前控制包的重发和取消等待按钮。
@Composable
private fun SceneStepResult(index: Int, step: SceneStepState, execution: SceneExecutionState) {
    val request = step.request
    var expanded by remember(execution.runId, index) { mutableStateOf(false) }
    Text("${index + 1}. ${step.target.label} · MANUAL · 目标${if (step.requestedState) "开启" else "关闭"}",
        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(6.dp))
    if (request == null) {
        StatusTag("尚未发送", C.Muted)
        Text(if (execution.isRunning) "本项控制包尚未创建，等待逐项确认流程。"
            else "后续未执行 · 原停止记录：${execution.detail}", color = C.Muted,
            style = MaterialTheme.typography.bodySmall)
        return
    }
    val color = when (request.status) {
        RequestStatus.EXECUTED -> C.Primary
        RequestStatus.REJECTED -> C.Critical
        RequestStatus.INTERRUPTED -> C.Warning
        else -> C.Waiting
    }
    StatusTag(requestLabel(request.status) + if (request.late) " · 迟到确认" else "", color)
    Text(request.detail, color = C.Secondary, style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = { expanded = !expanded }, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(if (expanded) "收起本项记录" else "查看本项记录")
    }
    if (expanded) {
        Text("commandId · ${request.commandId}", color = C.Secondary,
            style = MaterialTheme.typography.bodySmall)
        Text("创建 · ${historyDateTime(request.issuedAtUtcMs)}\n有效期至 · ${historyDateTime(request.expiresAtUtcMs)}",
            color = C.Muted, style = MaterialTheme.typography.bodySmall)
        request.resultUtcMs?.let { resultTime ->
            Text("关联结果 · ${historyDateTime(resultTime)}", color = C.Muted,
                style = MaterialTheme.typography.bodySmall)
        }
    }
}
