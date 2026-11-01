package cn.greenhouse.twin.data.model

import cn.greenhouse.twin.domain.ActuatorState
import cn.greenhouse.twin.domain.AlarmSummary
import cn.greenhouse.twin.domain.AlarmSummaryStatus
import cn.greenhouse.twin.domain.CloudQueryStatus
import cn.greenhouse.twin.domain.DataFreshness
import cn.greenhouse.twin.domain.DemoScenario
import cn.greenhouse.twin.domain.DeviceConnection
import cn.greenhouse.twin.domain.GreenhouseTwinState
import cn.greenhouse.twin.domain.Observation
import cn.greenhouse.twin.domain.ObservationSource
import cn.greenhouse.twin.domain.ObservationValidity
import cn.greenhouse.twin.domain.ReportedState
import cn.greenhouse.twin.domain.StateSource
import cn.greenhouse.twin.domain.SynchronizationStatus

// 固定时刻和样例仅供首页视觉验收，不代表当时或当前的硬件实测。
object DemoStateFactory {
    const val REFERENCE_UTC_MS: Long = 1790992800000L
    private const val REFERENCE_UPTIME_MS: Long = 120000L

    fun create(scenario: DemoScenario): GreenhouseTwinState {
        if (scenario == DemoScenario.INITIAL_SYNC) {
            return GreenhouseTwinState(
                stateSource = StateSource.DESIGN_DEMO,
                cloudQueryStatus = CloudQueryStatus.QUERYING,
                selectedDemoScenario = scenario,
                alarmSummary = AlarmSummary(
                    title = "等待首次同步",
                    detail = "设计样例：尚未收到有效数据，无法判断报警状态",
                ),
            )
        }

        // 此处是明确的展示场景时间，不用于定义真实同步的新鲜度阈值。
        val sampleAgeMs = when (scenario) {
            DemoScenario.DELAYED -> 40000L
            DemoScenario.STALE, DemoScenario.CLOUD_ERROR, DemoScenario.OFFLINE -> 90000L
            else -> 2000L
        }
        val sampledUtcMs = REFERENCE_UTC_MS - sampleAgeMs
        val sampledUptimeMs = REFERENCE_UPTIME_MS - sampleAgeMs
        val normal = GreenhouseTwinState(
            stateSource = StateSource.DESIGN_DEMO,
            temperature = observation(28.6, ObservationSource.SENSOR, sampledUtcMs, sampledUptimeMs),
            humidity = observation(67.0, ObservationSource.SENSOR, sampledUtcMs, sampledUptimeMs),
            lightLevel = observation(5, ObservationSource.EXPERIMENT, sampledUtcMs, sampledUptimeMs),
            soilHumidity = observation(
                if (scenario == DemoScenario.SOIL_WARNING) 24.0 else 42.0,
                ObservationSource.SIMULATION,
                sampledUtcMs,
                sampledUptimeMs,
            ),
            pump = ActuatorState(reported = ReportedState.OFF),
            lamp = ActuatorState(reported = ReportedState.ON),
            fan = ActuatorState(reported = ReportedState.ON),
            deviceConnection = DeviceConnection.ONLINE,
            cloudQueryStatus = CloudQueryStatus.SUCCESS,
            freshness = DataFreshness.FRESH,
            synchronizationStatus = SynchronizationStatus.READY,
            snapshotUtcMs = sampledUtcMs,
            snapshotUptimeMs = sampledUptimeMs,
            lastSyncedAtUtcMs = REFERENCE_UTC_MS,
            lastQueryAtUtcMs = REFERENCE_UTC_MS,
            selectedDemoScenario = scenario,
            alarmSummary = AlarmSummary(
                status = AlarmSummaryStatus.NONE,
                title = "当前无告警",
                detail = "设计样例，不代表实时报警结果",
            ),
        )
        return when (scenario) {
            DemoScenario.NORMAL -> normal
            DemoScenario.SOIL_WARNING -> normal.copy(
                // 独立设计样例展示三路动效，不能解释为自动灌溉或真实控制结果。
                pump = ActuatorState(reported = ReportedState.ON),
                lamp = ActuatorState(reported = ReportedState.OFF),
                fan = ActuatorState(reported = ReportedState.OFF),
                alarmSummary = AlarmSummary(
                    status = AlarmSummaryStatus.WARNING,
                    title = "土壤湿度偏低",
                    detail = "设计样例：软件土壤湿度 24%，仅演示警告样式",
                ),
            )
            DemoScenario.MISSING -> normal.copy(
                humidity = Observation(
                    source = ObservationSource.SENSOR,
                    validity = ObservationValidity.READ_ERROR,
                    sampledAtUtcMs = sampledUtcMs,
                    sampledUptimeMs = sampledUptimeMs,
                ),
                lightLevel = Observation(
                    source = ObservationSource.EXPERIMENT,
                    validity = ObservationValidity.READ_ERROR,
                    sampledAtUtcMs = sampledUtcMs,
                    sampledUptimeMs = sampledUptimeMs,
                ),
                alarmSummary = unavailableAlarm("部分数据缺测", "设计样例：湿度与光照读取失败，不能判断全部环境状态"),
            )
            DemoScenario.DELAYED -> normal.copy(
                freshness = DataFreshness.DELAYED,
                alarmSummary = unavailableAlarm("数据延迟", "设计样例：采样时间较旧，等待下一次有效更新"),
            )
            DemoScenario.STALE -> normal.copy(
                freshness = DataFreshness.STALE,
                alarmSummary = unavailableAlarm("数据已陈旧", "设计样例：保留最后已知值，停止实时活动提示"),
            )
            DemoScenario.CLOUD_ERROR -> normal.copy(
                deviceConnection = DeviceConnection.UNKNOWN,
                cloudQueryStatus = CloudQueryStatus.FAILED,
                freshness = DataFreshness.STALE,
                lastSyncedAtUtcMs = sampledUtcMs,
                queryErrorMessage = "设计样例：云端查询失败",
                alarmSummary = unavailableAlarm("云端查询失败", "无法确认设备当前连接状态；显示最后已知数据"),
            )
            DemoScenario.OFFLINE -> normal.copy(
                deviceConnection = DeviceConnection.OFFLINE,
                freshness = DataFreshness.STALE,
                alarmSummary = unavailableAlarm("设备已离线", "设计样例：云端查询成功，设备离线；显示最后已知数据"),
            )
            DemoScenario.INITIAL_SYNC -> error("首次同步场景已在入口处理")
        }
    }

    private fun <T : Number> observation(
        value: T,
        source: ObservationSource,
        sampledUtcMs: Long,
        sampledUptimeMs: Long,
    ) = Observation(
        value = value,
        source = source,
        validity = ObservationValidity.VALID,
        sampledAtUtcMs = sampledUtcMs,
        sampledUptimeMs = sampledUptimeMs,
    )

    private fun unavailableAlarm(title: String, detail: String) = AlarmSummary(
        status = AlarmSummaryStatus.NOT_CONNECTED,
        title = title,
        detail = detail,
    )
}
