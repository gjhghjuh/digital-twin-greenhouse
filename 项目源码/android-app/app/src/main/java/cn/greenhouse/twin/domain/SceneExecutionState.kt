package cn.greenhouse.twin.domain

data class SceneStepTarget(val target: ActuatorKey, val requestedState: Boolean)

// 预设只编排现有手动开关，未列出的执行器保持原状，不承诺实物灌溉或降温效果。
enum class ManualScenePreset(val label: String, val description: String, val steps: List<SceneStepTarget>) {
    IRRIGATION("灌溉", "开启水泵，补光灯和风扇保持原状。", listOf(SceneStepTarget(ActuatorKey.PUMP, true))),
    VENTILATION("高温通风", "先确认风扇开启，再关闭补光灯；水泵保持原状。",
        listOf(SceneStepTarget(ActuatorKey.FAN, true), SceneStepTarget(ActuatorKey.LAMP, false))),
}

enum class SceneExecutionStatus { RUNNING, SUCCESS, PARTIAL, STOPPED }

data class SceneStepState(val target: ActuatorKey, val requestedState: Boolean, val request: ControlRequestState? = null)

data class SceneExecutionState(
    val runId: String,
    val preset: ManualScenePreset,
    val identity: SnapshotIdentity,
    val productId: String,
    val createdAtUtcMs: Long,
    val steps: List<SceneStepState>,
    val status: SceneExecutionStatus = SceneExecutionStatus.RUNNING,
    val detail: String = "正在逐项执行手动场景",
) {
    init {
        require(runId.matches(Regex("[A-Za-z0-9._:-]{1,64}")) && productId.isNotBlank() && createdAtUtcMs > 0) {
            "场景运行身份无效"
        }
        require(identity.deviceId.isNotBlank() && identity.bootId.matches(Regex("[0-9a-f]{32}")) &&
            identity.seq >= 1 && identity.stateVersion >= 0 && identity.configVersion == 1 && identity.modelVersion == 1) {
            "场景目标快照身份无效"
        }
        require(steps.size == preset.steps.size && steps.zip(preset.steps).all { (actual, planned) ->
            actual.target == planned.target && actual.requestedState == planned.requestedState
        }) { "场景步骤与已确认预设不一致" }
        require(steps.all { step -> step.request?.let {
            it.target == step.target && it.requestedState == step.requestedState &&
                it.purpose == ControlPurpose.REALTIME && it.targetBootId == identity.bootId &&
                it.configVersion == identity.configVersion
        } != false }) { "场景控制包与步骤或目标启动不一致" }
        require(steps.mapNotNull { it.request?.commandId }.let { it.size == it.toSet().size } &&
            steps.withIndex().all { (index, step) -> step.request == null ||
                steps.take(index).all { it.request?.status == RequestStatus.EXECUTED } }) {
            "场景步骤必须按顺序关联原控制包"
        }
        require(status != SceneExecutionStatus.SUCCESS || steps.all { it.request?.status == RequestStatus.EXECUTED }) {
            "场景成功必须有全部步骤的执行确认"
        }
    }

    val isRunning: Boolean get() = status == SceneExecutionStatus.RUNNING
    val confirmedStepCount: Int get() = steps.count { it.request?.status == RequestStatus.EXECUTED }
    val notSentStepCount: Int get() = steps.count { it.request == null }
    val activeStep: SceneStepState? get() = steps.firstOrNull { it.request?.unresolved == true }
}
