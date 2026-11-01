package cn.greenhouse.twin.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import cn.greenhouse.twin.domain.ActuatorKey
import cn.greenhouse.twin.domain.ControlRequestState
import cn.greenhouse.twin.domain.ControlPurpose
import cn.greenhouse.twin.domain.RequestStatus
import cn.greenhouse.twin.domain.SceneExecutionState
import cn.greenhouse.twin.domain.SceneExecutionStatus
import cn.greenhouse.twin.domain.SceneStepState
import cn.greenhouse.twin.domain.ManualScenePreset
import cn.greenhouse.twin.domain.SnapshotIdentity
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.json.JSONArray

private val Context.controlPreferences by preferencesDataStore(name = "control_journal")

data class ControlJournalState(val request: ControlRequestState?, val scene: SceneExecutionState?) {
    init {
        require(scene == null || scene.steps.count { it.request?.unresolved == true } <= 1) { "场景不得同时保留两包未决请求" }
        scene?.activeStep?.request?.let { require(it == request) { "场景在途步骤必须与当前控制原包一致" } }
    }
}

// 在发送前持久化完整原包；恢复及重发复用同一标识和有效期，不清空不确定请求。
class ControlJournal(context: Context) {
    private val preferences = context.applicationContext.controlPreferences

    suspend fun loadState(): ControlJournalState {
        migratePurpose()
        val values = preferences.data.first()
        return ControlJournalState(values[REQUEST]?.let { decode(JSONObject(it)) },
            values[SCENE]?.let { decodeScene(JSONObject(it)) })
    }

    private fun decode(json: JSONObject): ControlRequestState {
        require(json.keys().asSequence().toSet() == REQUEST_FIELDS) { "控制日志字段不符合当前格式" }
        return ControlRequestState(json.getString("commandId"), ActuatorKey.valueOf(json.getString("target")),
            json.getBoolean("requestedState"), json.getString("targetBootId"), json.getInt("configVersion"),
            json.getLong("issuedAtUtcMs"), json.getLong("expiresAtUtcMs"), json.getLong("baseStateVersion"),
            RequestStatus.valueOf(json.getString("status")), json.getBoolean("cloudAccepted"),
            json.getString("detail"), json.getBoolean("waitingDismissed"), json.getBoolean("late"),
            if (json.isNull("resultUtcMs")) null else json.getLong("resultUtcMs"),
            ControlPurpose.valueOf(json.getString("purpose")))
    }

    // 对既有未版本化日志执行一次原子迁移；先验证原件，再新增目的，不改原身份或有效期。
    private suspend fun migratePurpose() {
        preferences.edit { values ->
            val version = values[FORMAT_VERSION]
            if (version == null) {
                values[REQUEST]?.let { raw ->
                    val json = JSONObject(raw)
                    require(json.keys().asSequence().toSet() == REQUEST_FIELDS - "purpose") {
                        "旧控制日志字段不完整，已停止迁移"
                    }
                    json.put("purpose", ControlPurpose.REALTIME.name)
                    decode(json)
                    values[MIGRATION_SOURCE] = raw
                    values[REQUEST] = json.toString()
                }
                values[FORMAT_VERSION] = 2L
            } else {
                require(version == 2L) { "控制日志格式版本不支持" }
            }
        }
    }

    suspend fun highWater(): Long = preferences.data.first()[HIGH_WATER] ?: 0L

    private fun encode(value: ControlRequestState): JSONObject =
        JSONObject().put("commandId", value.commandId).put("target", value.target.name)
            .put("requestedState", value.requestedState).put("targetBootId", value.targetBootId)
            .put("configVersion", value.configVersion).put("issuedAtUtcMs", value.issuedAtUtcMs)
            .put("expiresAtUtcMs", value.expiresAtUtcMs).put("baseStateVersion", value.baseStateVersion)
            .put("status", value.status.name).put("cloudAccepted", value.cloudAccepted).put("detail", value.detail)
            .put("waitingDismissed", value.waitingDismissed).put("late", value.late)
            .put("resultUtcMs", value.resultUtcMs ?: JSONObject.NULL)
            .put("purpose", value.purpose.name)

    // 当前包和场景步骤在同一提交中保存，进程终止不会留下孤立的已发送步骤。
    suspend fun save(value: ControlRequestState?, scene: SceneExecutionState?) {
        ControlJournalState(value, scene)
        preferences.edit {
            require(it[FORMAT_VERSION] == 2L) { "控制日志必须先完成迁移" }
            if (value == null) it.remove(REQUEST) else {
                it[REQUEST] = encode(value).toString()
                it[HIGH_WATER] = maxOf(it[HIGH_WATER] ?: 0L, value.issuedAtUtcMs)
            }
            if (scene == null) it.remove(SCENE) else it[SCENE] = encodeScene(scene).toString()
        }
    }

    private fun encodeScene(scene: SceneExecutionState): JSONObject {
        val identity = scene.identity
        val steps = JSONArray()
        scene.steps.forEach { steps.put(JSONObject().put("target", it.target.name).put("requestedState", it.requestedState)
            .put("request", it.request?.let(::encode) ?: JSONObject.NULL)) }
        return JSONObject().put("formatVersion", 1).put("runId", scene.runId).put("preset", scene.preset.name)
            .put("productId", scene.productId).put("createdAtUtcMs", scene.createdAtUtcMs)
            .put("status", scene.status.name).put("detail", scene.detail).put("steps", steps)
            .put("identity", JSONObject().put("deviceId", identity.deviceId).put("bootId", identity.bootId)
                .put("seq", identity.seq).put("stateVersion", identity.stateVersion)
                .put("configVersion", identity.configVersion).put("modelVersion", identity.modelVersion))
    }

    private fun decodeScene(json: JSONObject): SceneExecutionState {
        require(json.keys().asSequence().toSet() == setOf("formatVersion", "runId", "preset", "productId",
            "createdAtUtcMs", "status", "detail", "steps", "identity") && json.getInt("formatVersion") == 1) {
            "场景日志格式不支持"
        }
        val identity = json.getJSONObject("identity")
        require(identity.keys().asSequence().toSet() == setOf("deviceId", "bootId", "seq", "stateVersion", "configVersion", "modelVersion")) {
            "场景目标身份字段不完整"
        }
        val steps = json.getJSONArray("steps")
        return SceneExecutionState(json.getString("runId"), ManualScenePreset.valueOf(json.getString("preset")),
            SnapshotIdentity(identity.getString("deviceId"), identity.getString("bootId"), identity.getLong("seq"),
                identity.getLong("stateVersion"), identity.getInt("configVersion"), identity.getInt("modelVersion")),
            json.getString("productId"), json.getLong("createdAtUtcMs"), (0 until steps.length()).map { index ->
                val step = steps.getJSONObject(index)
                require(step.keys().asSequence().toSet() == setOf("target", "requestedState", "request")) { "场景步骤日志字段不完整" }
                SceneStepState(ActuatorKey.valueOf(step.getString("target")), step.getBoolean("requestedState"),
                    if (step.isNull("request")) null else decode(step.getJSONObject("request")))
            }, SceneExecutionStatus.valueOf(json.getString("status")), json.getString("detail"))
    }

    private companion object {
        val REQUEST = stringPreferencesKey("last_control_request")
        val SCENE = stringPreferencesKey("last_scene_execution")
        val HIGH_WATER = longPreferencesKey("issued_high_water")
        val FORMAT_VERSION = longPreferencesKey("format_version")
        val MIGRATION_SOURCE = stringPreferencesKey("request_before_purpose_migration")
        val REQUEST_FIELDS = setOf("commandId", "target", "requestedState", "targetBootId", "configVersion",
            "issuedAtUtcMs", "expiresAtUtcMs", "baseStateVersion", "status", "cloudAccepted", "detail",
            "waitingDismissed", "late", "resultUtcMs", "purpose")
    }
}
