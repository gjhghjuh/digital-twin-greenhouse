package cn.greenhouse.twin.data.history

import cn.greenhouse.twin.data.local.TwinRecordEntity
import cn.greenhouse.twin.data.model.CompactSnapshotParser
import cn.greenhouse.twin.domain.*
import cn.greenhouse.twin.domain.history.HistoryEvent
import cn.greenhouse.twin.domain.history.HistorySnapshot
import org.json.JSONArray
import org.json.JSONObject

// 展开档案与云端紧凑编码比较共同可证明的语义，接收途径和 JSON 包装不参与身份冲突判定。
object HistoryRecordCodec {
    const val COMPACT = "COMPACT"
    const val EXPANDED = "EXPANDED"
    private val observationNames = listOf("temperature", "humidity", "lightLevel", "soilHumidity")
    private val observationSources = listOf("SENSOR", "SENSOR", "EXPERIMENT", "SIMULATION")

    fun normalize(record: TwinRecordEntity): TwinRecordEntity {
        val expanded = expanded(record)
        validate(expanded)
        require(expanded.getString("deviceId") == record.deviceId && expanded.getString("bootId") == record.bootId &&
            expanded.getString("kind") == record.kind && integer(expanded.get("seq")) == record.seq &&
            integer(expanded.get("stateVersion")) == record.stateVersion &&
            integer(expanded.get("configVersion")) == record.configVersion.toLong() &&
            integer(expanded.get("modelVersion")) == record.modelVersion.toLong() &&
            integer(expanded.get("uptimeMs")) == record.uptimeMs && nullableInteger(expanded.get("utcMs")) == record.recordUtcMs) {
            "记录索引与原内容身份或时间不一致"
        }
        val core = JSONObject(expanded.toString())
        // 此字段只有串口完整档案提供；原内容仍完整保留，不把它编造进紧凑记录。
        if (record.kind == "snapshot") core.remove("lastCommandResult")
        return record.copy(normalizedCoreJson = canonical(core), timeQuality = expanded.getString("timeQuality"))
    }

    fun equivalent(first: TwinRecordEntity, second: TwinRecordEntity): Boolean {
        val a = normalize(first)
        val b = normalize(second)
        if (a.normalizedCoreJson != b.normalizedCoreJson) return false
        // 两份完整档案均提供的字段必须一致，不能以紧凑编码缺字段为由忽略真实冲突。
        return first.payloadEncoding != EXPANDED || second.payloadEncoding != EXPANDED ||
            canonical(expanded(first)) == canonical(expanded(second))
    }

    fun expanded(record: TwinRecordEntity): JSONObject {
        if (record.payloadEncoding == EXPANDED) return JSONObject(record.payloadJson)
        require(record.payloadEncoding == COMPACT && record.kind == "snapshot") { "记录编码与类型不一致" }
        val parsed = CompactSnapshotParser.parse(record.payloadJson, record.deviceId)
        val raw = JSONObject(parsed.payloadJson)
        return JSONObject().apply {
            put("schemaVersion", 1); put("kind", "snapshot"); put("deviceId", record.deviceId)
            put("bootId", parsed.identity.bootId); put("seq", parsed.identity.seq)
            put("stateVersion", parsed.identity.stateVersion); put("configVersion", parsed.identity.configVersion)
            put("modelVersion", parsed.identity.modelVersion); put("utcMs", parsed.utcMs ?: JSONObject.NULL)
            put("uptimeMs", parsed.uptimeMs); put("timeQuality", if (parsed.utcMs == null) "UNSYNCED" else "SYNCED")
            val observations = raw.getJSONArray("o")
            observationNames.forEachIndexed { index, name ->
                val tuple = observations.getJSONArray(index)
                put(name, JSONObject().put("value", tuple.get(0)).put("source", observationSources[index])
                    .put("validity", listOf("NOT_READY", "VALID", "READ_ERROR")[tuple.getInt(1)])
                    .put("sampledAtUtcMs", tuple.get(2)).put("uptimeMs", tuple.get(3)))
            }
            val actuators = raw.getJSONArray("a")
            listOf("pump", "lamp", "fan").forEachIndexed { index, name ->
                put(name, actuators.getBoolean(index)); put(name + "Mode", "MANUAL")
            }
            put("wifiConnected", raw.getBoolean("w")); put("cloudConnected", raw.getBoolean("net"))
            put("lightAdcRaw", raw.get("raw")); put("lightAdcFiltered", raw.get("adc"))
            put("lastCommandId", raw.getString("cmd")); put("lastCommandResult", "")
        }
    }

    fun snapshot(record: TwinRecordEntity): HistorySnapshot {
        val j = expanded(record)
        validate(j)
        require(j.getString("kind") == "snapshot") { "事件不能作为完整快照" }
        fun observation(index: Int): Observation<Double> {
            val o = j.getJSONObject(observationNames[index])
            return Observation(if (o.isNull("value")) null else o.getDouble("value"),
                ObservationSource.valueOf(o.getString("source")), ObservationValidity.valueOf(o.getString("validity")),
                nullableInteger(o.get("sampledAtUtcMs")), integer(o.get("uptimeMs")))
        }
        val light = observation(2)
        val state = GreenhouseTwinState(stateSource = StateSource.REPLAY,
            temperature = observation(0), humidity = observation(1), soilHumidity = observation(3),
            lightLevel = Observation(light.value?.toInt(), light.source, light.validity, light.sampledAtUtcMs, light.sampledUptimeMs),
            pump = ActuatorState(if (j.getBoolean("pump")) ReportedState.ON else ReportedState.OFF),
            lamp = ActuatorState(if (j.getBoolean("lamp")) ReportedState.ON else ReportedState.OFF),
            fan = ActuatorState(if (j.getBoolean("fan")) ReportedState.ON else ReportedState.OFF),
            snapshotIdentity = SnapshotIdentity(record.deviceId, record.bootId, record.seq, record.stateVersion,
                record.configVersion, record.modelVersion), snapshotUtcMs = record.recordUtcMs,
            snapshotUptimeMs = record.uptimeMs, synchronizationStatus = SynchronizationStatus.READY,
            controlBlockedReason = "历史回放禁止真实控制", synchronizationSuspended = true,
        )
        return HistorySnapshot(state, record.recordUtcMs, record.archiveName, record.stateSource)
    }

    fun event(record: TwinRecordEntity): HistoryEvent {
        val j = expanded(record)
        validate(j)
        require(record.kind == "event")
        return HistoryEvent(record.deviceId, record.bootId, record.seq, record.stateVersion,
            record.recordUtcMs, record.uptimeMs, j.getString("eventId"), j.getString("eventType"),
            j.getString("target"), canonical(j.get("oldValue")), canonical(j.get("newValue")),
            j.getString("commandId"), j.getString("reason"), j.getString("operator"))
    }

    fun validate(j: JSONObject) {
        val base = setOf("schemaVersion", "kind", "deviceId", "bootId", "seq", "stateVersion", "utcMs", "uptimeMs",
            "timeQuality", "configVersion", "modelVersion")
        val snapshot = observationNames.toSet() + setOf("pump", "lamp", "fan", "pumpMode", "lampMode", "fanMode",
            "wifiConnected", "cloudConnected", "lightAdcRaw", "lightAdcFiltered", "lastCommandId", "lastCommandResult")
        val event = setOf("eventId", "eventType", "target", "oldValue", "newValue", "commandId", "reason", "operator")
        val kind = j.getString("kind")
        require(kind in setOf("snapshot", "event")) { "记录类型不支持" }
        require(j.keys().asSequence().toSet() == base + if (kind == "snapshot") snapshot else event) { "记录字段与契约不一致" }
        require(integer(j.get("schemaVersion")) == 1L && integer(j.get("configVersion")) == 1L &&
            integer(j.get("modelVersion")) == 1L) { "记录版本不支持" }
        require(j.getString("deviceId").length in 1..64 && j.getString("deviceId").isNotBlank()) { "设备标识无效" }
        require(j.getString("bootId").matches(Regex("[0-9a-f]{32}"))) { "启动标识无效" }
        integer(j.get("seq")); integer(j.get("stateVersion"))
        val uptime = integer(j.get("uptimeMs"))
        val utc = nullableInteger(j.get("utcMs"))
        require(j.getString("timeQuality") == if (utc == null) "UNSYNCED" else "SYNCED") { "时间质量与设备UTC不一致" }
        if (kind == "snapshot") {
            observationNames.forEachIndexed { index, name ->
                val o = j.getJSONObject(name)
                require(o.keys().asSequence().toSet() == setOf("value", "source", "validity", "sampledAtUtcMs", "uptimeMs")) { "$name 观测字段不完整" }
                require(o.getString("source") == observationSources[index]) { "$name 来源不符合契约" }
                val validity = o.getString("validity")
                require(validity in setOf("VALID", "NOT_READY", "READ_ERROR")) { "$name 有效性无效" }
                require((validity == "VALID") == !o.isNull("value")) { "$name 值与有效性不一致" }
                if (!o.isNull("value")) {
                    val number = o.get("value")
                    require(number is Number && number.toDouble().isFinite()) { "$name 值不是有限数字" }
                    val value = number.toDouble()
                    require(value in (if (index == 0) -55.0..125.0 else if (index == 2) 0.0..7.0 else 0.0..100.0)) { "$name 值超出范围" }
                    if (index == 2) require(value == value.toInt().toDouble()) { "光照档位必须是整数" }
                }
                val sampleUtc = nullableInteger(o.get("sampledAtUtcMs"))
                require(integer(o.get("uptimeMs")) <= uptime && (utc == null || sampleUtc == null || sampleUtc <= utc)) { "$name 采样时间晚于快照" }
            }
            listOf("pump", "lamp", "fan", "wifiConnected", "cloudConnected").forEach {
                require(j.get(it) is Boolean) { "$it 必须为true或false" }
            }
            listOf("pumpMode", "lampMode", "fanMode").forEach {
                require(j.getString(it) == "MANUAL") { "当前历史呈现仅支持已核验的MANUAL，不能把AUTO伪装为MANUAL" }
            }
            listOf("lightAdcRaw", "lightAdcFiltered").forEach { require(integer(j.get(it)) <= 4095) { "ADC超出范围" } }
            listOf("lastCommandId", "lastCommandResult").forEach { string(j.get(it)) }
        } else {
            listOf("eventId", "eventType", "target", "commandId", "reason", "operator").forEach { string(j.get(it)) }
            listOf("oldValue", "newValue").forEach { require(j.get(it) !is JSONArray) { "事件值不支持数组" }; canonical(j.get(it)) }
        }
    }

    fun canonical(value: Any): String = when (value) {
        JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().sorted().joinToString(prefix = "{", postfix = "}") {
            JSONObject.quote(it) + ":" + canonical(value.get(it))
        }
        is JSONArray -> (0 until value.length()).joinToString(prefix = "[", postfix = "]") { canonical(value.get(it)) }
        is String -> JSONObject.quote(value)
        is Number -> { require(value.toDouble().isFinite()) { "非有限数值" }; JSONObject.numberToString(value) }
        is Boolean -> value.toString()
        else -> throw IllegalArgumentException("记录字段类型无效")
    }

    fun integer(value: Any): Long {
        require(value is Number && value.toDouble().isFinite() && value.toDouble() >= 0 &&
            value.toDouble() <= 9_007_199_254_740_991.0 && value.toDouble() == value.toLong().toDouble()) { "协议整数无效" }
        return value.toLong()
    }

    fun nullableInteger(value: Any): Long? = if (value == JSONObject.NULL) null else integer(value)
    private fun string(value: Any) { require(value is String && value.length <= 512) { "文本字段类型或长度无效" } }
}
