package cn.greenhouse.twin.ui.control

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import cn.greenhouse.twin.domain.*
import cn.greenhouse.twin.ui.components.GreenhouseCard
import cn.greenhouse.twin.ui.components.SourceTag
import cn.greenhouse.twin.ui.components.StatusTag
import cn.greenhouse.twin.ui.history.historyDateTime
import cn.greenhouse.twin.ui.theme.GreenhouseColors as C
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceControlSheet(
    state: GreenhouseTwinState,
    target: ActuatorKey,
    onRequest: (ActuatorKey, Boolean) -> Unit,
    onReserveOfflinePump: (Boolean, SnapshotIdentity) -> Unit,
    onRefresh: () -> Unit,
    onResend: () -> Unit,
    onDismissWait: () -> Unit,
    onClose: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onClose,
        containerColor = C.Raised,
        scrimColor = C.Scrim.copy(alpha = 0.65f),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        val view = LocalView.current
        val window = (view.parent as DialogWindowProvider).window
        SideEffect {
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(target.label, style = MaterialTheme.typography.titleLarge)
            Text("虚拟执行器 · 三路外接 LED 对应手动开关", color = C.Secondary,
                style = MaterialTheme.typography.bodyMedium)
            DeviceStateFacts(state, target)
            ManualControls(state, target, onRequest)
            if (target == ActuatorKey.PUMP) OfflinePumpReservationControls(state, onReserveOfflinePump)
            state.controlRequest?.let { request ->
                if (request.target == target) {
                    ControlRequestProgress(state, onRefresh, onResend, onDismissWait)
                } else if (request.status in unresolvedStatuses) {
                    Text("${request.target.label}请求尚未结束，同一 ESP32 暂不能发送冲突请求。",
                        color = C.Waiting, style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = onRefresh) { Text("重新查询设备") }
                }
            }
            Text("MANUAL 手动模式。仅水泵支持 60 秒离线预约；手动场景在设备控制页逐项执行，自动规则尚未开放。",
                color = C.Muted, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("关闭面板")
            }
        }
    }
}

internal val unresolvedStatuses = setOf(
    RequestStatus.SENDING, RequestStatus.WAITING, RequestStatus.TIMED_OUT, RequestStatus.UNCERTAIN,
)

internal fun actuator(state: GreenhouseTwinState, target: ActuatorKey): ActuatorState = when (target) {
    ActuatorKey.PUMP -> state.pump
    ActuatorKey.LAMP -> state.lamp
    ActuatorKey.FAN -> state.fan
}

internal fun feedbackLabel(reported: ReportedState): String = when (reported) {
    ReportedState.ON -> "开启"
    ReportedState.OFF -> "关闭"
    ReportedState.UNKNOWN -> "状态未知"
}

internal fun requestLabel(status: RequestStatus): String = when (status) {
    RequestStatus.IDLE -> "尚未发送"
    RequestStatus.SENDING -> "正在提交云端"
    RequestStatus.WAITING -> "等待设备确认"
    RequestStatus.EXECUTED -> "已确认执行"
    RequestStatus.REJECTED -> "请求被拒绝 / 未提交"
    RequestStatus.TIMED_OUT -> "确认超时 · 结果未确认"
    RequestStatus.UNCERTAIN -> "提交结果未确认"
    RequestStatus.INTERRUPTED -> "设备已重新启动 · 原请求不适用"
}

@Composable
internal fun DeviceStateFacts(state: GreenhouseTwinState, target: ActuatorKey) {
    val feedback = actuator(state, target)
    val request = state.controlRequest?.takeIf { it.target == target }
    val current = state.canPresentCurrentActivity
    GreenhouseCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("目标状态", color = C.Secondary, style = MaterialTheme.typography.bodyMedium)
            Text(request?.let { if (it.requestedState) "开启" else "关闭" } ?: "未发出目标",
                style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("设备反馈", color = C.Secondary, style = MaterialTheme.typography.bodyMedium)
            Text(feedbackLabel(feedback.reported), color = when (feedback.reported) {
                ReportedState.UNKNOWN -> C.Waiting
                ReportedState.ON -> if (current) C.Running else C.Offline
                ReportedState.OFF -> C.Offline
            }, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(10.dp))
        Text("画面状态", color = C.Secondary, style = MaterialTheme.typography.bodyMedium)
        Text(when {
            feedback.reported == ReportedState.UNKNOWN -> "尚未取得设备反馈"
            !current -> "最后已知${feedbackLabel(feedback.reported)} · 实时活动已暂停"
            request != null && request.status in unresolvedStatuses ->
                "设备反馈${feedbackLabel(feedback.reported)} · ${if (request.requestedState) "开启" else "关闭"}请求待确认"
            feedback.reported == ReportedState.ON -> "按设备反馈显示运行示意"
            else -> "按设备反馈显示关闭"
        }, color = C.Muted, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(10.dp))
        Text("反馈采样 · ${historyDateTime(state.snapshotUtcMs)}", color = C.Muted,
            style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
internal fun ManualControls(
    state: GreenhouseTwinState,
    target: ActuatorKey,
    onRequest: (ActuatorKey, Boolean) -> Unit,
) {
    SourceTag("MANUAL · 手动")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { onRequest(target, true) }, enabled = state.canSendControl,
            modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("开启") }
        OutlinedButton(onClick = { onRequest(target, false) }, enabled = state.canSendControl,
            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            border = BorderStroke(1.dp, if (state.canSendControl) C.Primary else C.Outline)) { Text("关闭") }
    }
    if (!state.canSendControl) {
        Text(state.controlBlockedReason ?: "当前状态尚不能发送实时控制。", color = C.Waiting,
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun OfflinePumpReservationControls(
    state: GreenhouseTwinState,
    onReserve: (Boolean, SnapshotIdentity) -> Unit,
) {
    var selection by remember { mutableStateOf<Boolean?>(null) }
    var selectedIdentity by remember { mutableStateOf<SnapshotIdentity?>(null) }
    val identity = state.snapshotIdentity
    // 确认窗口不沿用已经变化的启动或配置，保存时仓库还会重新核对资格。
    val canConfirm = state.canReserveOfflinePump && selectedIdentity != null &&
        identity?.deviceId == selectedIdentity?.deviceId && identity?.bootId == selectedIdentity?.bootId &&
        identity?.configVersion == selectedIdentity?.configVersion
    GreenhouseCard {
        Text("水泵离线预约", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text("设备离线时保存期望状态，60 秒内按同一次启动重连后处理。",
            color = C.Secondary, style = MaterialTheme.typography.bodyMedium)
        Text("需要手机可访问云端。保存不表示水泵已运行，设备重启后原预约不适用。",
            color = C.Muted, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                selectedIdentity = identity
                selection = true
            }, enabled = state.canReserveOfflinePump,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("预约开启") }
            OutlinedButton(onClick = {
                selectedIdentity = identity
                selection = false
            }, enabled = state.canReserveOfflinePump,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("预约关闭") }
        }
        state.offlinePumpBlockedReason?.let {
            Text(it, color = C.Waiting, style = MaterialTheme.typography.bodySmall)
        }
    }
    selection?.let { desired ->
        AlertDialog(
            onDismissRequest = { selection = null },
            containerColor = C.Raised,
            title = { Text("确认水泵离线预约") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("期望${if (desired) "开启" else "关闭"} · MANUAL · 有效期 60 秒")
                    Text("最后已知反馈 · ${feedbackLabel(state.pump.reported)}\n采样 · ${historyDateTime(state.snapshotUtcMs)}")
                    Text("这是离线时的最后记录，当前运行未确认。云端保存后，设备必须在有效期内按原启动重连；重启或到期不执行新动作。")
                    Text("关闭确认窗口不会创建预约；已保存后取消等待不会撤回云端意图。")
                    if (!canConfirm) Text(state.offlinePumpBlockedReason ?: "设备启动或配置已变化，请重新打开预约确认。",
                        color = C.Waiting)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (canConfirm) {
                        selection = null
                        onReserve(desired, checkNotNull(selectedIdentity))
                    }
                }, enabled = canConfirm, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("保存${if (desired) "开启" else "关闭"}预约")
                }
            },
            dismissButton = {
                TextButton(onClick = { selection = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("返回") }
            },
        )
    }
}

@Composable
fun ControlRequestProgress(
    state: GreenhouseTwinState,
    onRefresh: () -> Unit,
    onResend: () -> Unit,
    onDismissWait: () -> Unit,
) {
    val request = state.controlRequest ?: return
    var details by remember(request.commandId) { mutableStateOf(false) }
    var now by remember(request.commandId) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(request.commandId, request.status) {
        while (request.status in unresolvedStatuses) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val waiting = request.status in unresolvedStatuses
    val reservation = request.purpose == ControlPurpose.OFFLINE_RESERVATION
    val uncertain = request.status == RequestStatus.TIMED_OUT || request.status == RequestStatus.UNCERTAIN
    val color = when (request.status) {
        RequestStatus.EXECUTED -> C.Primary
        RequestStatus.REJECTED -> C.Critical
        RequestStatus.INTERRUPTED -> C.Warning
        else -> C.Waiting
    }
    val canQuery = state.stateSource == StateSource.LIVE && !state.synchronizationSuspended &&
        state.configuredProductId != null
    val canResend = state.canResendControl

    GreenhouseCard(outline = color.copy(alpha = 0.4f)) {
        Text("${request.target.label}${if (reservation) "离线预约" else "控制请求"}", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        StatusTag((if (reservation && request.status == RequestStatus.WAITING) "云端已保存 · 等待设备结果"
            else requestLabel(request.status)) + if (request.late) " · 迟到确认" else "", color)
        Spacer(Modifier.height(10.dp))
        ProgressEvidence("请求已创建", true)
        ProgressEvidence(if (reservation) "云端保存" else "云端接受", request.cloudAccepted)
        if (reservation) Text("60 秒有效期 · 绑定原启动 · 保存不等于运行",
            color = C.Muted, style = MaterialTheme.typography.bodySmall)
        Text("设备接收 · 当前协议没有独立接收回执", color = C.Muted,
            style = MaterialTheme.typography.bodySmall)
        ProgressEvidence("关联确认执行", request.status == RequestStatus.EXECUTED)
        Spacer(Modifier.height(10.dp))
        if (request.status == RequestStatus.WAITING && !request.waitingDismissed) {
            Text("等待 ESP32 确认 · ${((now - request.issuedAtUtcMs).coerceAtLeast(0) / 1000)} 秒",
                color = C.Waiting, style = MaterialTheme.typography.bodyMedium)
        }
        if (uncertain) {
            Text("暂未收到设备确认，实际执行结果仍待核实。", color = C.Waiting,
                style = MaterialTheme.typography.bodyMedium)
        }
        Text(request.detail, color = C.Secondary, style = MaterialTheme.typography.bodySmall)
        if (waiting && !canResend) state.resendBlockedReason?.let {
            Text(it, color = C.Muted, style = MaterialTheme.typography.bodySmall)
        }
        if (request.waitingDismissed && waiting) {
            Text("已取消界面等待；请求仍可能被设备处理，未确认目标继续保留。",
                color = C.Muted, style = MaterialTheme.typography.bodySmall)
        }
        if (request.status == RequestStatus.INTERRUPTED) {
            Text("先同步新启动状态，再发送新的目标；原启动请求不能重发到新启动。",
                color = C.Muted, style = MaterialTheme.typography.bodySmall)
        }
        if (waiting && now >= request.expiresAtUtcMs) {
            Text("有效期已过；重发用于核实同一请求，未执行的包会被拒绝。",
                color = C.Muted, style = MaterialTheme.typography.bodySmall)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onRefresh, enabled = canQuery) { Text("重新查询") }
            if (waiting && request.status != RequestStatus.SENDING) {
                TextButton(onClick = onResend, enabled = canResend) { Text("同一请求重发") }
            }
        }
        if (waiting && !request.waitingDismissed) {
            TextButton(onClick = onDismissWait) { Text("取消界面等待") }
            Text("取消等待不会撤销设备请求。", color = C.Muted,
                style = MaterialTheme.typography.labelSmall)
        }
        TextButton(onClick = { details = !details }) {
            Text(if (details) "收起请求详情" else "展开请求详情")
        }
        if (details) {
            Text("commandId · ${request.commandId}", color = C.Secondary,
                style = MaterialTheme.typography.bodySmall)
            Text("创建 · ${historyDateTime(request.issuedAtUtcMs)}\n有效期至 · ${historyDateTime(request.expiresAtUtcMs)}",
                color = C.Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ProgressEvidence(label: String, evidenced: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = C.Secondary, style = MaterialTheme.typography.bodySmall)
        Text(if (evidenced) "有对应证据" else "待确认", color = if (evidenced) C.Primary else C.Muted,
            style = MaterialTheme.typography.bodySmall)
    }
}

internal fun controlTime(utcMs: Long?): String {
    if (utcMs == null) return "采样时间未知"
    return SimpleDateFormat("HH:mm:ss", Locale.CHINA).apply {
        timeZone = TimeZone.getTimeZone("Asia/Shanghai")
    }.format(Date(utcMs))
}
