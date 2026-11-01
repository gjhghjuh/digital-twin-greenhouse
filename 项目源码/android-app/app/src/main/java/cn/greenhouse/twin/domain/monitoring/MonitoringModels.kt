package cn.greenhouse.twin.domain.monitoring

import cn.greenhouse.twin.domain.ActuatorKey
import cn.greenhouse.twin.domain.AlarmSummary
import cn.greenhouse.twin.domain.AlarmSummaryStatus
import cn.greenhouse.twin.domain.ObservationSource
import cn.greenhouse.twin.domain.history.HistoryMetric

// 数值为空表示该规则未配置；启用状态独立保存，不以默认数值代表用户确认。
data class MonitoringSettings(
    val temperatureLowC: Double? = 10.0,
    val temperatureHighC: Double? = 35.0,
    val humidityLowPercent: Double? = 40.0,
    val humidityHighPercent: Double? = 85.0,
    val soilLowPercent: Double? = 30.0,
    val lightLowLevel: Int? = 2,
    val staleAfterMs: Long = 60_000,
    val animationsEnabled: Boolean = true,
    val monitorEnabled: Boolean = false,
) {
    fun validate() {
        listOfNotNull(temperatureLowC, temperatureHighC, humidityLowPercent,
            humidityHighPercent, soilLowPercent).forEach { require(it.isFinite()) { "阈值必须是有限数值" } }
        require(temperatureLowC == null || temperatureHighC == null || temperatureLowC < temperatureHighC) { "温度下限必须小于上限" }
        require(humidityLowPercent == null || humidityHighPercent == null || humidityLowPercent < humidityHighPercent) { "湿度下限必须小于上限" }
        listOfNotNull(humidityLowPercent, humidityHighPercent, soilLowPercent).forEach {
            require(it in 0.0..100.0) { "湿度阈值应在 0 至 100 之间" }
        }
        require(lightLowLevel == null || lightLowLevel in 0..7) { "光照阈值应在 0 至 7 档之间" }
        require(staleAfterMs > 25_000) { "陈旧时间必须大于 25 秒" }
    }
}

enum class AlarmSeverity { INFO, WARNING, CRITICAL }
enum class AlarmKind(val label: String, val metric: HistoryMetric? = null, val actuator: ActuatorKey? = null) {
    LOW_TEMPERATURE("温度过低", HistoryMetric.TEMPERATURE),
    HIGH_TEMPERATURE("温度过高", HistoryMetric.TEMPERATURE, ActuatorKey.FAN),
    LOW_HUMIDITY("空气湿度过低", HistoryMetric.HUMIDITY),
    HIGH_HUMIDITY("空气湿度过高", HistoryMetric.HUMIDITY),
    LOW_SOIL_MOISTURE("土壤湿度过低", HistoryMetric.SOIL, ActuatorKey.PUMP),
    LOW_LIGHT("光照不足", HistoryMetric.LIGHT, ActuatorKey.LAMP),
    DEVICE_OFFLINE("设备离线"), QUERY_FAILURE("云端查询失败"),
    DATA_STALE("数据陈旧"), COMMAND_TIMEOUT("控制请求超时"),
}
enum class AlarmEndReason(val label: String) {
    VALUE_RECOVERED("数值恢复"), CONNECTION_RECOVERED("连接恢复"),
    QUERY_RECOVERED("查询恢复"), FRESH_DATA("数据恢复"),
    COMMAND_RESOLVED("已收到命令最终结果"), RULE_CHANGED("规则修改，非数值恢复"),
}
enum class MonitoringStatus { ACTIVE, DISABLED, PAUSED, UNVERIFIED }

// 开始、最后观察及确认时间是手机本地观察时间；采样时间单独保留设备可信 UTC。
data class AlarmEpisode(
    val id: String,
    val deviceId: String,
    val kind: AlarmKind,
    val severity: AlarmSeverity,
    val source: ObservationSource?,
    val triggerValue: Double?,
    val latestValue: Double?,
    val thresholdValue: Double?,
    val unit: String,
    val startedAtUtcMs: Long,
    val lastObservedAtUtcMs: Long,
    val triggerSampleUtcMs: Long?,
    val lastSampleUtcMs: Long?,
    val triggerBootId: String?,
    val triggerSeq: Long?,
    val lastBootId: String?,
    val lastSeq: Long?,
    val observedDurationMs: Long = 0,
    val acknowledgedAtUtcMs: Long? = null,
    val recoveredAtUtcMs: Long? = null,
    val endedAtUtcMs: Long? = null,
    val endedReason: AlarmEndReason? = null,
    val verificationPending: Boolean = true,
    val commandId: String? = null,
    val detail: String = "",
    val commandTarget: ActuatorKey? = null,
) {
    val isOpen: Boolean get() = endedAtUtcMs == null
    val isAcknowledged: Boolean get() = acknowledgedAtUtcMs != null
    val title: String get() = kind.label
    val metric: HistoryMetric? get() = kind.metric
    val actuator: ActuatorKey? get() = kind.actuator ?: commandTarget
}

data class AdviceItem(
    val id: String,
    val title: String,
    val reason: String,
    val expectedImpact: String,
    val metric: HistoryMetric,
    val source: ObservationSource,
    val value: Double,
    val thresholdValue: Double,
    val sampledAtUtcMs: Long,
    val bootId: String,
    val seq: Long,
    val target: ActuatorKey,
    val requestedState: Boolean = true,
    val canExecute: Boolean,
    val blockedReason: String?,
)

data class MonitoringUiState(
    val settings: MonitoringSettings = MonitoringSettings(),
    val status: MonitoringStatus = MonitoringStatus.UNVERIFIED,
    val statusReason: String = "监测尚未加载",
    val currentAlarms: List<AlarmEpisode> = emptyList(),
    val historicalAlarms: List<AlarmEpisode> = emptyList(),
    val adviceItems: List<AdviceItem> = emptyList(),
    val adviceUnavailableReason: String? = "尚未取得可信实时数据",
    val error: String? = null,
    val summary: AlarmSummary = AlarmSummary(AlarmSummaryStatus.NOT_CONNECTED, "监测尚未核实", "尚未取得可信实时数据"),
)
