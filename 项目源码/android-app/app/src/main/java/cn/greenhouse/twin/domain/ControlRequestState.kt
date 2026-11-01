package cn.greenhouse.twin.domain

// 只呈现实际查询到的设备回执，不把手机请求进度伪装成设备事件。
data class DeviceCommandFeedback(
    val commandId: String,
    val bootId: String,
    val result: String,
    val duplicate: Boolean,
    val stateVersion: Long,
    val utcMs: Long?,
    val target: ActuatorKey?,
)

enum class ActuatorKey(val wireName: String, val label: String) {
    PUMP("pump", "水泵"), LAMP("lamp", "补光灯"), FAN("fan", "风扇");

    fun reported(state: GreenhouseTwinState): ActuatorState = when (this) {
        PUMP -> state.pump
        LAMP -> state.lamp
        FAN -> state.fan
    }
}

// 目的只保存在手机日志，设备控制包仍沿用原八字段 MANUAL 契约。
enum class ControlPurpose { REALTIME, OFFLINE_RESERVATION }

// 一个设备共用一份控制记录；网络超时和取消界面等待都不能撤回设备意图。
data class ControlRequestState(
    val commandId: String,
    val target: ActuatorKey,
    val requestedState: Boolean,
    val targetBootId: String,
    val configVersion: Int,
    val issuedAtUtcMs: Long,
    val expiresAtUtcMs: Long,
    val baseStateVersion: Long,
    val status: RequestStatus = RequestStatus.SENDING,
    val cloudAccepted: Boolean = false,
    val detail: String = "正在检查云端待处理包",
    val waitingDismissed: Boolean = false,
    val late: Boolean = false,
    val resultUtcMs: Long? = null,
    val purpose: ControlPurpose = ControlPurpose.REALTIME,
) {
    init {
        require(commandId.matches(Regex("[A-Za-z0-9._:-]{1,64}"))) { "控制标识无效" }
        require(targetBootId.matches(Regex("[0-9a-f]{32}"))) { "目标启动标识无效" }
        require(configVersion == 1 && baseStateVersion >= 0) { "控制配置版本无效" }
        require(issuedAtUtcMs > 0 && expiresAtUtcMs > issuedAtUtcMs &&
            expiresAtUtcMs - issuedAtUtcMs <= 300_000) { "控制有效期无效" }
        require(purpose != ControlPurpose.OFFLINE_RESERVATION || target == ActuatorKey.PUMP) {
            "离线预约仅支持水泵"
        }
    }

    val unresolved: Boolean
        get() = status in setOf(RequestStatus.SENDING, RequestStatus.WAITING,
            RequestStatus.TIMED_OUT, RequestStatus.UNCERTAIN)
}
