package cn.greenhouse.twin.ui.history

import cn.greenhouse.twin.domain.GreenhouseTwinState
import cn.greenhouse.twin.data.history.CsvImportPreview
import cn.greenhouse.twin.domain.history.CsvImportResult
import cn.greenhouse.twin.domain.history.HistoryEvent
import cn.greenhouse.twin.domain.history.HistoryMetric
import cn.greenhouse.twin.domain.history.HistoryWindow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

enum class HistoryRange(val label: String, val durationMs: Long) {
    HOUR_1("1 小时", 3_600_000),
    HOUR_6("6 小时", 21_600_000),
    HOUR_24("24 小时", 86_400_000),
    DAY_7("7 日", 604_800_000),
}

// 页面只接收已核验的查询结果，计算和写入由历史数据层负责。
data class HistoryUiState(
    val devices: List<String> = emptyList(),
    val selectedDeviceId: String? = null,
    val metric: HistoryMetric = HistoryMetric.TEMPERATURE,
    val range: HistoryRange = HistoryRange.HOUR_24,
    val endUtcMs: Long? = null,
    val window: HistoryWindow? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

data class CsvImportUiState(
    val preview: CsvImportPreview? = null,
    val result: CsvImportResult? = null,
    val loading: Boolean = false,
    val importing: Boolean = false,
    val error: String? = null,
    val fileName: String? = null,
)

// 这是业务 ReplayState 的展示映射，不能成为新的实时状态或控制来源。
data class ReplayUiState(
    val window: HistoryWindow? = null,
    val positionUtcMs: Long? = null,
    val playing: Boolean = false,
    val speed: Float = 1f,
    val currentSnapshot: GreenhouseTwinState? = null,
    val gap: Boolean = true,
    val isUnknown: Boolean = true,
    val selectedEvent: HistoryEvent? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

internal fun historyDateTime(utcMs: Long?, pattern: String = "MM-dd HH:mm:ss"): String {
    if (utcMs == null) return "时间未知"
    return SimpleDateFormat(pattern, Locale.CHINA).apply {
        timeZone = TimeZone.getTimeZone("Asia/Shanghai")
    }.format(Date(utcMs))
}

internal fun historyNumber(value: Double?, decimals: Int = 1): String =
    value?.let { String.format(Locale.CHINA, "%.$decimals" + "f", it) } ?: "—"

internal fun historyDuration(ms: Long): String {
    val seconds = ms / 1_000
    return when {
        seconds >= 3_600 -> "${seconds / 3_600} 小时 ${(seconds % 3_600) / 60} 分"
        seconds >= 60 -> "${seconds / 60} 分 ${seconds % 60} 秒"
        else -> "$seconds 秒"
    }
}
