package cn.greenhouse.twin.data.model

import cn.greenhouse.twin.domain.*
import org.json.JSONArray
import org.json.JSONObject

// 云端紧凑快照是唯一观测依据，不从旧标量拼接当前状态。
data class CompactSnapshot(
    val identity: SnapshotIdentity,
    val utcMs: Long?,
    val uptimeMs: Long,
    val temperature: Observation<Double>,
    val humidity: Observation<Double>,
    val lightLevel: Observation<Int>,
    val soilHumidity: Observation<Double>,
    val pump: Boolean,
    val lamp: Boolean,
    val fan: Boolean,
    val payloadJson: String,
) {
    fun asState(connection: DeviceConnection): GreenhouseTwinState = GreenhouseTwinState(
        temperature = temperature, humidity = humidity, lightLevel = lightLevel, soilHumidity = soilHumidity,
        pump = ActuatorState(if (pump) ReportedState.ON else ReportedState.OFF),
        lamp = ActuatorState(if (lamp) ReportedState.ON else ReportedState.OFF),
        fan = ActuatorState(if (fan) ReportedState.ON else ReportedState.OFF),
        deviceConnection = connection, cloudQueryStatus = CloudQueryStatus.SUCCESS,
        synchronizationStatus = SynchronizationStatus.READY, snapshotIdentity = identity,
        snapshotUtcMs = utcMs, snapshotUptimeMs = uptimeMs,
    )
}

object CompactSnapshotParser {
    fun parse(raw: String, deviceId: String): CompactSnapshot {
        require(raw.length <= 512) { "快照超过已核验物模型长度" }
        val json = JSONObject(raw)
        require(integer(json.get("v")) == 1L) { "快照编码版本不支持" }
        val boot = json.getString("b")
        require(boot.matches(Regex("[0-9a-f]{32}"))) { "快照启动标识无效" }
        require(integer(json.get("c")) == 1L && integer(json.get("m")) == 1L && integer(json.get("mode")) == 0L) {
            "设备配置、模型版本或运行模式不匹配已核验契约"
        }
        val identity = SnapshotIdentity(deviceId, boot, integer(json.get("q")), integer(json.get("s")), 1, 1)
        val uptime = integer(json.get("u"))
        val utc = nullableInteger(json.get("t"))
        val observations = json.getJSONArray("o")
        require(observations.length() == 4) { "快照观测数量不正确" }
        val temperature = observation(observations.getJSONArray(0), ObservationSource.SENSOR, -55.0, 125.0, uptime)
        val humidity = observation(observations.getJSONArray(1), ObservationSource.SENSOR, 0.0, 100.0, uptime)
        val light = observation(observations.getJSONArray(2), ObservationSource.EXPERIMENT, 0.0, 7.0, uptime)
        require(light.value == null || light.value % 1.0 == 0.0) { "光照档位必须是整数" }
        val soil = observation(observations.getJSONArray(3), ObservationSource.SIMULATION, 0.0, 100.0, uptime)
        require(listOf(temperature, humidity, light, soil).all {
            utc == null || it.sampledAtUtcMs == null || it.sampledAtUtcMs <= utc
        }) { "采样 UTC 晚于快照" }
        val actuators = json.getJSONArray("a")
        require(actuators.length() == 3 && (0..2).all { actuators.get(it) is Boolean }) { "设备反馈必须是三个布尔值" }
        require(json.get("w") is Boolean && json.get("net") is Boolean) { "设备网络字段无效" }
        require(integer(json.get("raw")) <= 4095 && integer(json.get("adc")) <= 4095) { "ADC值超出编码范围" }
        require(json.get("cmd") is String && json.getString("cmd").length <= 64) { "命令标识无效" }
        return CompactSnapshot(identity, utc, uptime, temperature, humidity,
            Observation(light.value?.toInt(), light.source, light.validity, light.sampledAtUtcMs, light.sampledUptimeMs),
            soil, actuators.getBoolean(0), actuators.getBoolean(1), actuators.getBoolean(2), canonical(json))
    }

    private fun observation(tuple: JSONArray, source: ObservationSource, min: Double, max: Double,
        snapshotUptime: Long): Observation<Double> {
        require(tuple.length() == 4) { "观测元组长度不正确" }
        val validity = when (integer(tuple.get(1))) {
            0L -> ObservationValidity.NOT_READY
            1L -> ObservationValidity.VALID
            2L -> ObservationValidity.READ_ERROR
            else -> throw IllegalArgumentException("观测有效性编码不支持")
        }
        val value = if (tuple.isNull(0)) null else {
            val number = tuple.get(0)
            require(number is Number) { "观测值必须是数值或空值" }
            number.toDouble().also { require(it.isFinite() && it in min..max) { "观测值超出协议范围" } }
        }
        require((validity == ObservationValidity.VALID) == (value != null)) { "观测值和有效性不一致" }
        val sampledUptime = integer(tuple.get(3))
        require(sampledUptime <= snapshotUptime) { "采样开机时间晚于快照" }
        return Observation(value, source, validity, nullableInteger(tuple.get(2)), sampledUptime)
    }

    private fun nullableInteger(value: Any): Long? = if (value == JSONObject.NULL) null else integer(value).also {
        require(it > 0) { "UTC 必须有效或为空" }
    }

    // 同一记录不因字段顺序或等值数字的编码形式不同而产生身份冲突。
    private fun canonical(value: Any): String = when (value) {
        JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().sorted().joinToString(prefix = "{", postfix = "}") {
            JSONObject.quote(it) + ":" + canonical(value.get(it))
        }
        is JSONArray -> (0 until value.length()).joinToString(prefix = "[", postfix = "]") { canonical(value.get(it)) }
        is String -> JSONObject.quote(value)
        is Number -> JSONObject.numberToString(value)
        is Boolean -> value.toString()
        else -> throw IllegalArgumentException("快照字段类型无效")
    }

    private fun integer(value: Any): Long {
        require(value is Number && value.toDouble().isFinite() && value.toDouble() >= 0 &&
            value.toDouble() <= 9_007_199_254_740_991.0 && value.toDouble() == value.toLong().toDouble()) {
            "协议整数无效或超出安全范围"
        }
        return value.toLong()
    }
}
