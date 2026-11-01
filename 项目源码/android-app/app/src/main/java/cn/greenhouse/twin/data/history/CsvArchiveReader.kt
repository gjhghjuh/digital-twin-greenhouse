package cn.greenhouse.twin.data.history

import cn.greenhouse.twin.data.local.TwinRecordEntity
import cn.greenhouse.twin.domain.history.CsvRowResult
import cn.greenhouse.twin.domain.history.CsvRowStatus
import org.json.JSONObject
import java.io.Reader

data class CsvImportPreview(
    val fileName: String, val sha256: String, val kind: String, val deviceIds: List<String>,
    val startUtcMs: Long?, val endUtcMs: Long?, val snapshotCount: Int, val eventCount: Int,
    val duplicateCount: Int, val invalidCount: Int, val unknownUtcCount: Int,
    val rows: List<CsvRowResult>, internal val candidates: List<CsvCandidate>,
)

data class CsvCandidate(val rowNumber: Int, val record: TwinRecordEntity)

// 与 serial-archiver 的 csv-columns.json 一致；空值及布尔值保留协议语义。
object CsvArchiveReader {
    private val base = listOf("schemaVersion", "kind", "deviceId", "bootId", "seq", "stateVersion", "utcMs",
        "uptimeMs", "timeQuality", "configVersion", "modelVersion")
    private val observations = listOf("temperature", "humidity", "lightLevel", "soilHumidity")
    private val snapshot = base + observations.flatMap { name ->
        listOf("value", "source", "validity", "sampledAtUtcMs", "uptimeMs").map { "$name.$it" }
    } + listOf("lightAdcRaw", "lightAdcFiltered", "pump", "lamp", "fan", "pumpMode", "lampMode", "fanMode",
        "wifiConnected", "cloudConnected", "lastCommandId", "lastCommandResult", "receivedAtUtcMs")
    private val event = base + listOf("eventId", "eventType", "target", "oldValue", "newValue", "commandId", "reason", "operator", "receivedAtUtcMs")
    private val integerFields = setOf("schemaVersion", "seq", "stateVersion", "uptimeMs", "configVersion", "modelVersion",
        "lightAdcRaw", "lightAdcFiltered", "receivedAtUtcMs")
    private val booleanFields = setOf("pump", "lamp", "fan", "wifiConnected", "cloudConnected")

    fun read(reader: Reader, fileName: String, expectedDeviceId: String?, importedAt: Long): Pair<String, List<Result<CsvCandidate>>> {
        val rows = rfc4180(reader)
        require(rows.isNotEmpty()) { "CSV为空，没有表头" }
        val header = rows.first().mapIndexed { index, value -> if (index == 0) value.removePrefix("\uFEFF") else value }
        val kind = when (header) {
            snapshot -> "snapshot"
            event -> "event"
            else -> throw IllegalArgumentException("CSV列名或列顺序与 snapshots/events 契约不一致")
        }
        require(rows.size <= 100_001) { "单文件超过100000条记录，请按归档日期选择文件" }
        val results = rows.drop(1).mapIndexed { index, cells -> runCatching {
            require(cells.size == header.size) { "列数不正确，应为${header.size}列，实际${cells.size}列" }
            val raw = JSONObject()
            var archiveReceivedAt: Long? = null
            header.zip(cells).forEach { (column, cell) ->
                if (column == "receivedAtUtcMs") {
                    archiveReceivedAt = number(cell, column).also { require(it > 0) { "电脑接收时间无效" } }
                } else if ('.' in column) {
                    val (parent, field) = column.split('.', limit = 2)
                    val observation = if (raw.has(parent)) raw.getJSONObject(parent) else JSONObject().also { raw.put(parent, it) }
                    val value: Any = when (field) {
                        "value" -> if (cell.isEmpty()) JSONObject.NULL else finite(cell, column)
                        "sampledAtUtcMs" -> nullableNumber(cell, column)
                        "uptimeMs" -> number(cell, column)
                        else -> cell
                    }
                    observation.put(field, value)
                } else {
                    val value: Any = when {
                        column == "utcMs" -> nullableNumber(cell, column)
                        column in integerFields -> number(cell, column)
                        column in booleanFields -> when (cell) {
                            "true" -> true; "false" -> false
                            else -> throw IllegalArgumentException("$column 必须为true或false")
                        }
                        column in setOf("oldValue", "newValue") -> eventValue(cell)
                        else -> cell
                    }
                    raw.put(column, value)
                }
            }
            require(raw.getString("kind") == kind) { "行类型与CSV表头不一致" }
            if (expectedDeviceId != null) require(raw.getString("deviceId") == expectedDeviceId) { "设备与当前所选设备不一致" }
            require(!raw.getString("deviceId").contains("DESIGN_DEMO", true) &&
                raw.getString("deviceId") != "CONTRACT-EXAMPLE-NOT-HARDWARE" &&
                !fileName.contains("protocol-example-only", true)) { "设计示例不能导入正式历史" }
            HistoryRecordCodec.validate(raw)
            val value = TwinRecordEntity(raw.getString("deviceId"), raw.getString("bootId"), kind, raw.getLong("seq"),
                raw.getLong("stateVersion"), raw.getInt("configVersion"), raw.getInt("modelVersion"),
                if (raw.isNull("utcMs")) null else raw.getLong("utcMs"), raw.getLong("uptimeMs"), null, null,
                raw.toString(), "ARCHIVE", HistoryRecordCodec.EXPANDED, archiveReceivedAtUtcMs = archiveReceivedAt,
                archiveName = fileName, importedAtUtcMs = importedAt)
            CsvCandidate(index + 2, HistoryRecordCodec.normalize(value))
        } }
        return kind to results
    }

    // 引号内的逗号、换行与双引号都是同一字段，不能用 split(',') 拆开事件JSON。
    private fun rfc4180(reader: Reader): List<List<String>> {
        val result = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var inQuote = false
        var afterQuote = false
        var previousCr = false
        var seen = false
        var total = 0L
        fun finishCell() { row += cell.toString(); cell.setLength(0); afterQuote = false }
        fun finishRow() { finishCell(); result += row; row = mutableListOf() }
        while (true) {
            val code = reader.read()
            if (code == -1) break
            require(++total <= 64L * 1024 * 1024) { "CSV文本超过读取上限" }
            val c = code.toChar()
            if (total == 1L && c == '\uFEFF') continue
            seen = true
            if (previousCr) {
                previousCr = false
                if (c == '\n') continue
            }
            if (inQuote) {
                if (c == '"') { inQuote = false; afterQuote = true } else cell.append(c)
                continue
            }
            if (afterQuote) {
                when (c) {
                    '"' -> { cell.append('"'); inQuote = true; afterQuote = false }
                    ',' -> finishCell()
                    '\n' -> finishRow()
                    '\r' -> { finishRow(); previousCr = true }
                    else -> throw IllegalArgumentException("引号结束后有非法字符")
                }
                continue
            }
            when (c) {
                '"' -> { require(cell.isEmpty()) { "未转义的双引号" }; inQuote = true }
                ',' -> finishCell()
                '\n' -> finishRow()
                '\r' -> { finishRow(); previousCr = true }
                else -> cell.append(c)
            }
            require(result.size <= 100_001) { "单文件超过100000条记录" }
        }
        require(!inQuote) { "CSV引号字段未闭合" }
        if (cell.isNotEmpty() || row.isNotEmpty() || afterQuote) finishRow()
        require(seen) { "CSV为空" }
        return result
    }

    private fun number(value: String, field: String): Long {
        require(value.matches(Regex("0|[1-9][0-9]*"))) { "$field 必须为非负整数" }
        val parsed = value.toLongOrNull() ?: throw IllegalArgumentException("$field 整数超出范围")
        require(parsed <= 9_007_199_254_740_991L) { "$field 整数超出协议范围" }
        return parsed
    }
    private fun nullableNumber(value: String, field: String): Any = if (value.isEmpty()) JSONObject.NULL else number(value, field)
    private fun finite(value: String, field: String): Double =
        value.toDoubleOrNull()?.takeIf { it.isFinite() } ?: throw IllegalArgumentException("$field 不是有限数字")

    private fun eventValue(value: String): Any = when {
        value.isEmpty() -> JSONObject.NULL
        value == "true" -> true
        value == "false" -> false
        value == "null" -> JSONObject.NULL
        value.startsWith('{') -> JSONObject(value)
        value.matches(Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")) -> finite(value, "事件值")
        else -> value
    }
}
