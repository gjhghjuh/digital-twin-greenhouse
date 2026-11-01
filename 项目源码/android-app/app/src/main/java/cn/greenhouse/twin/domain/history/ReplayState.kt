package cn.greenhouse.twin.domain.history

import cn.greenhouse.twin.domain.*

// 回放时钟及历史窗口独立于实时仓库，事件只能更新已有可靠基线的离散状态。
data class ReplayState(
    val window: HistoryWindow? = null,
    val positionUtcMs: Long? = null,
    val playing: Boolean = false,
    val speed: Float = 1f,
    val foreground: Boolean = true,
) {
    fun stateAtPosition(): GreenhouseTwinState? {
        val history = window ?: return null
        val time = positionUtcMs ?: return null
        val ordered = history.snapshots.filter { it.recordUtcMs != null }.sortedBy { it.recordUtcMs }
        val index = ordered.indexOfLast { checkNotNull(it.recordUtcMs) <= time }
        if (index < 0) return null
        val previous = ordered[index]
        val atRecord = previous.recordUtcMs == time
        val next = ordered.getOrNull(index + 1)
        val continuous = next != null && HistoryTimeline.continuous(previous, next)
        val verifiedEvents = if (continuous) HistoryTimeline.actuatorEvents(previous, checkNotNull(next), history.events) else null
        if (!atRecord && (!continuous || verifiedEvents == null)) return null
        val relevant = history.events.filter { it.utcMs != null && it.utcMs > previous.recordUtcMs!! && it.utcMs <= time }
            .sortedWith(compareBy<HistoryEvent> { it.utcMs }.thenBy { it.seq })
        // 新启动事件尚无完整快照时，不沿用前一启动的状态。
        if (relevant.any { it.bootId != previous.identity.bootId }) return null
        var value = previous.state
        verifiedEvents.orEmpty().filter { checkNotNull(it.utcMs) <= time }.forEach { event ->
            val reported = when (event.newValueJson) { "true" -> ReportedState.ON; "false" -> ReportedState.OFF; else -> null }
            if (reported != null) value = when (event.target) {
                "pump" -> value.copy(pump = ActuatorState(reported))
                "lamp" -> value.copy(lamp = ActuatorState(reported))
                "fan" -> value.copy(fan = ActuatorState(reported))
                else -> value
            }
        }
        return value.copy(stateSource = StateSource.REPLAY, deviceConnection = DeviceConnection.UNKNOWN,
            cloudQueryStatus = CloudQueryStatus.IDLE, freshness = DataFreshness.FRESH,
            synchronizationSuspended = !playing || !foreground, controlRequest = null,
            controlBlockedReason = "历史回放禁止真实控制", recentDeviceResults = emptyList())
    }

    fun eventAtPosition(): HistoryEvent? = window?.events?.filter {
        it.utcMs != null && positionUtcMs != null && it.utcMs <= positionUtcMs &&
            it.utcMs >= checkNotNull(window).startUtcMs && it.utcMs < checkNotNull(window).endUtcMs
    }?.maxWithOrNull(compareBy<HistoryEvent> { it.utcMs }.thenBy { it.seq })
}
