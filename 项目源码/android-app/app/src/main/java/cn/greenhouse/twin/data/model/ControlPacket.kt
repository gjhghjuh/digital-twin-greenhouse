package cn.greenhouse.twin.data.model

import cn.greenhouse.twin.domain.ActuatorKey
import cn.greenhouse.twin.domain.ControlRequestState
import org.json.JSONObject

// 命令包字段严格沿用已烧录固件；没有额外 schemaVersion，也不把字符串改成对象。
fun ControlRequestState.packetJson(): String = JSONObject()
    .put("commandId", commandId).put("targetBootId", targetBootId)
    .put("issuedAtUtcMs", issuedAtUtcMs).put("expiresAtUtcMs", expiresAtUtcMs)
    .put("configVersion", configVersion).put("target", target.wireName)
    .put("mode", "MANUAL").put("state", requestedState).toString().also {
        require(it.toByteArray(Charsets.UTF_8).size <= 512) { "控制包超过固件上限" }
    }

data class CommandReceipt(
    val commandId: String, val bootId: String, val result: String,
    val duplicate: Boolean, val stateVersion: Long, val utcMs: Long?,
    val pump: Boolean, val lamp: Boolean, val fan: Boolean,
) {
    fun targetState(target: ActuatorKey): Boolean = when (target) {
        ActuatorKey.PUMP -> pump
        ActuatorKey.LAMP -> lamp
        ActuatorKey.FAN -> fan
    }

    companion object {
        fun parse(value: String): CommandReceipt {
            val data = JSONObject(value)
            require(data.keys().asSequence().toSet() == setOf("commandId", "bootId", "result", "duplicate",
                "stateVersion", "utcMs", "pump", "lamp", "fan")) { "设备命令结果字段与当前固件不一致" }
            val id = data.getString("commandId")
            require(id.matches(Regex("[A-Za-z0-9._:-]{1,64}"))) { "设备命令标识无效" }
            val boot = data.getString("bootId")
            require(boot.matches(Regex("[0-9a-f]{32}"))) { "命令结果启动标识无效" }
            val result = data.getString("result")
            require(result in setOf("INVALID_PACKET", "STORAGE_UNAVAILABLE", "TIME_UNSYNCED", "BOOT_MISMATCH",
                "CONFIG_MISMATCH", "UNSUPPORTED_MODE", "INVALID_TIME_WINDOW", "EXPIRED", "STALE_ISSUED_TIME",
                "STORAGE_WRITE_FAILED", "RESULT_STORAGE_FAILED", "EXECUTED", "INTERRUPTED")) { "未知设备命令结果" }
            fun flag(name: String): Boolean {
                val flag = data.get(name)
                require(flag is Boolean) { "命令结果 $name 必须为布尔值" }
                return flag
            }
            fun integer(name: String): Long {
                val value = data.get(name)
                require(value is Number && value.toDouble().isFinite() && value.toDouble() >= 0 &&
                    value.toDouble() <= 9_007_199_254_740_991.0 && value.toDouble() == value.toLong().toDouble()) {
                    "命令结果 $name 必须为安全整数"
                }
                return value.toLong()
            }
            val utc = if (data.isNull("utcMs")) null else integer("utcMs").also { require(it > 0) }
            return CommandReceipt(id, boot, result, flag("duplicate"), integer("stateVersion"), utc,
                flag("pump"), flag("lamp"), flag("fan"))
        }
    }
}
