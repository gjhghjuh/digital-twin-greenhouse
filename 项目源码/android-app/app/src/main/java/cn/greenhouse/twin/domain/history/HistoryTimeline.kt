package cn.greenhouse.twin.domain.history

import cn.greenhouse.twin.domain.ReportedState

// 回放和运行统计共用同一连续区间证明，缺事件时不能由两端相同状态推定中间没有运行。
object HistoryTimeline {
    fun continuous(a: HistorySnapshot, b: HistorySnapshot): Boolean {
        val aTime = a.recordUtcMs ?: return false
        val bTime = b.recordUtcMs ?: return false
        val aUptime = a.state.snapshotUptimeMs ?: return false
        val bUptime = b.state.snapshotUptimeMs ?: return false
        return a.identity.bootId == b.identity.bootId && b.identity.seq == a.identity.seq + 1 &&
            a.identity.configVersion == b.identity.configVersion && a.identity.modelVersion == b.identity.modelVersion &&
            bTime - aTime in 1..15_000L && bUptime - aUptime in 1..15_000L
    }

    fun actuatorEvents(a: HistorySnapshot, b: HistorySnapshot, events: List<HistoryEvent>): List<HistoryEvent>? {
        if (!continuous(a, b)) return null
        val aTime = checkNotNull(a.recordUtcMs); val bTime = checkNotNull(b.recordUtcMs)
        val delta = b.identity.stateVersion - a.identity.stateVersion
        if (delta < 0) return null
        val inInterval = events.filter { it.utcMs != null && it.utcMs > aTime && it.utcMs <= bTime }
        if (inInterval.any { it.bootId != a.identity.bootId }) return null
        val changes = inInterval.filter { it.eventType.endsWith("ACTUATOR_SET") }
            .sortedWith(compareBy<HistoryEvent> { it.utcMs }.thenBy { it.seq })
        if (changes.size.toLong() != delta) return null
        val states = mutableMapOf("pump" to a.state.pump.reported, "lamp" to a.state.lamp.reported, "fan" to a.state.fan.reported)
        changes.forEachIndexed { index, event ->
            if (event.stateVersion != a.identity.stateVersion + index + 1 || event.target !in states ||
                event.oldValueJson !in setOf("true", "false") || event.newValueJson !in setOf("true", "false")) return null
            val old = if (event.oldValueJson == "true") ReportedState.ON else ReportedState.OFF
            if (states[event.target] != old) return null
            states[event.target] = if (event.newValueJson == "true") ReportedState.ON else ReportedState.OFF
        }
        if (states["pump"] != b.state.pump.reported || states["lamp"] != b.state.lamp.reported || states["fan"] != b.state.fan.reported) return null
        return changes
    }
}
