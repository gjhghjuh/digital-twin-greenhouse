package cn.greenhouse.twin.data.repository

import android.content.Context
import android.os.SystemClock
import cn.greenhouse.twin.BuildConfig
import cn.greenhouse.twin.domain.GreenhouseTwinState
import java.io.File
import org.json.JSONObject
import org.json.JSONArray

// 调试证据仅放在应用私有目录，不记录密钥、鉴权头或设备详情原文。
class SyncDiagnostics(context: Context) {
    private val directory = context.filesDir

    @Synchronized
    fun event(name: String, detail: JSONObject = JSONObject()) {
        if (!BuildConfig.DEBUG) return
        val document = JSONObject().put("event", name).put("appUtcMs", System.currentTimeMillis())
            .put("elapsedMs", SystemClock.elapsedRealtime()).put("detail", detail)
        File(directory, "g1-sync.jsonl").appendText(document.toString() + "\n", Charsets.UTF_8)
    }

    @Synchronized
    fun state(value: GreenhouseTwinState) {
        if (!BuildConfig.DEBUG) return
        val document = JSONObject().put("source", value.stateSource.name)
            .put("cloud", value.cloudQueryStatus.name).put("device", value.deviceConnection.name)
            .put("freshness", value.freshness.name).put("sync", value.synchronizationStatus.name)
            .put("suspended", value.synchronizationSuspended).put("refreshSeconds", value.refreshSeconds)
            .put("recordCount", value.storedRecordCount)
            .put("bootId", value.snapshotIdentity?.bootId ?: JSONObject.NULL)
            .put("seq", value.snapshotIdentity?.seq ?: JSONObject.NULL)
            .put("snapshotUtcMs", value.snapshotUtcMs ?: JSONObject.NULL)
            .put("lastSyncedAtUtcMs", value.lastSyncedAtUtcMs ?: JSONObject.NULL)
            .put("error", value.queryErrorMessage ?: JSONObject.NULL)
            .put("canSendControl", value.canSendControl)
            .put("controlBlockedReason", value.controlBlockedReason ?: JSONObject.NULL)
            .put("canReserveOfflinePump", value.canReserveOfflinePump)
            .put("offlinePumpBlockedReason", value.offlinePumpBlockedReason ?: JSONObject.NULL)
            .put("canResendControl", value.canResendControl)
            .put("resendBlockedReason", value.resendBlockedReason ?: JSONObject.NULL)
            .put("canStartScene", value.canStartScene)
            .put("sceneBlockedReason", value.sceneBlockedReason ?: JSONObject.NULL)
            .put("scene", value.sceneExecution?.let { scene ->
                val steps = JSONArray()
                scene.steps.forEach { step -> steps.put(JSONObject().put("target", step.target.wireName)
                    .put("state", step.requestedState).put("request", step.request?.let {
                        JSONObject().put("commandId", it.commandId).put("purpose", it.purpose.name)
                            .put("status", it.status.name).put("cloudAccepted", it.cloudAccepted)
                            .put("issuedAtUtcMs", it.issuedAtUtcMs).put("expiresAtUtcMs", it.expiresAtUtcMs)
                            .put("baseStateVersion", it.baseStateVersion).put("resultUtcMs", it.resultUtcMs ?: JSONObject.NULL)
                            .put("late", it.late).put("detail", it.detail)
                    } ?: JSONObject.NULL)) }
                JSONObject().put("runId", scene.runId).put("preset", scene.preset.name).put("status", scene.status.name)
                    .put("detail", scene.detail).put("productId", scene.productId).put("deviceId", scene.identity.deviceId)
                    .put("bootId", scene.identity.bootId).put("configVersion", scene.identity.configVersion)
                    .put("createdAtUtcMs", scene.createdAtUtcMs).put("confirmedSteps", scene.confirmedStepCount)
                    .put("notSentSteps", scene.notSentStepCount).put("steps", steps)
            } ?: JSONObject.NULL)
            .put("pump", value.pump.reported.name).put("lamp", value.lamp.reported.name).put("fan", value.fan.reported.name)
            .put("control", value.controlRequest?.let {
                JSONObject().put("commandId", it.commandId).put("target", it.target.wireName)
                    .put("purpose", it.purpose.name)
                    .put("state", it.requestedState).put("targetBootId", it.targetBootId)
                    .put("status", it.status.name).put("cloudAccepted", it.cloudAccepted)
                    .put("issuedAtUtcMs", it.issuedAtUtcMs).put("expiresAtUtcMs", it.expiresAtUtcMs)
                    .put("detail", it.detail).put("late", it.late).put("waitingDismissed", it.waitingDismissed)
            } ?: JSONObject.NULL)
        File(directory, "g1-state.json").writeText(document.toString(), Charsets.UTF_8)
    }
}
