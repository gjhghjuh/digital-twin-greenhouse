package cn.greenhouse.twin.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.greenhouse.twin.GreenhouseApplication
import cn.greenhouse.twin.domain.DemoScenario
import cn.greenhouse.twin.domain.GreenhouseTwinState
import cn.greenhouse.twin.domain.ActuatorKey
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.*
import cn.greenhouse.twin.domain.*
import cn.greenhouse.twin.domain.monitoring.*
import cn.greenhouse.twin.ui.settings.ConnectionTestUiState

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as GreenhouseApplication).homeRepository
    private val monitor = (application as GreenhouseApplication).monitorRepository
    val monitoring = monitor.state
    private val mutableSettingsReadError = MutableStateFlow<String?>(null)
    private val mutableError = MutableStateFlow<String?>(null)
    // 读取失败保持未知，不能用初始化规则冒充已保存配置。
    val preferences: StateFlow<MonitoringSettings?> = repository.connectionStore.monitoringSettings
        .map<MonitoringSettings, MonitoringSettings?> { it }
        .catch { error ->
            if (error is CancellationException) throw error
            mutableSettingsReadError.value = "本地设置读取失败：${error.message ?: error.javaClass.simpleName}"
            emit(null)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    // 报警摘要只是呈现映射，不回写同步仓库，避免监测与实时状态相互触发。
    val state: StateFlow<GreenhouseTwinState> = combine(repository.state, monitoring) { live, rules ->
        if (live.stateSource == StateSource.LIVE) live.copy(alarmSummary = rules.summary) else live
    }.stateIn(viewModelScope, SharingStarted.Eagerly, repository.state.value)
    private val mutableSaving = MutableStateFlow(false)
    val monitoringSaving = mutableSaving.asStateFlow()
    val monitoringError: StateFlow<String?> = combine(mutableSettingsReadError, mutableError) { readError, operationError ->
        readError ?: operationError
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    // 读取错误留在设置页；可关闭的全局提示只展示本次操作错误。
    val localOperationError = mutableError.asStateFlow()
    private val mutableSavedAt = MutableStateFlow<Long?>(null)
    val monitoringSavedAt = mutableSavedAt.asStateFlow()
    private val mutableTest = MutableStateFlow(ConnectionTestUiState())
    val connectionTest = mutableTest.asStateFlow()

    fun acknowledgeAlarm(id: String) = saveLocal { monitor.acknowledge(id) }
    fun saveMonitoringSettings(settings: MonitoringSettings) = saveLocal {
        monitor.saveSettings(settings)
        mutableSavedAt.value = System.currentTimeMillis()
    }
    fun updateAnimationsEnabled(enabled: Boolean) = saveLocal {
        repository.connectionStore.updateAnimationsEnabled(enabled)
    }
    fun currentAdvice(id: String): AdviceItem? {
        val loaded = preferences.value ?: return null
        if (mutableSaving.value || mutableSettingsReadError.value != null) return null
        return monitor.currentAdvice(id, loaded)
    }
    fun clearLocalError() { mutableError.value = null }

    private fun saveLocal(action: suspend () -> Unit) {
        if (mutableSaving.value) {
            mutableError.value = "正在保存本地设置，请稍后再试"
            return
        }
        mutableSaving.value = true
        mutableError.value = null
        viewModelScope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutableError.value = error.message ?: "本地设置操作失败" }
            finally { mutableSaving.value = false }
        }
    }

    fun testConnection() {
        if (mutableTest.value.pending) return
        val priorQueryAt = repository.state.value.lastQueryAtUtcMs
        val start = System.currentTimeMillis()
        mutableTest.value = ConnectionTestUiState(pending = true, message = "正在进行新的云端查询")
        viewModelScope.launch {
            try {
                repository.refreshNow()
                val result = withTimeout(30_000) {
                    repository.state.first { !it.synchronizationSuspended &&
                        it.lastQueryAtUtcMs != priorQueryAt &&
                        (it.lastQueryAtUtcMs ?: 0) >= start &&
                        it.cloudQueryStatus in setOf(CloudQueryStatus.SUCCESS, CloudQueryStatus.FAILED) }
                }
                val success = result.cloudQueryStatus == CloudQueryStatus.SUCCESS
                mutableTest.value = ConnectionTestUiState(false, success, result.lastQueryAtUtcMs,
                    if (success) "本次云端查询成功；设备${when (result.deviceConnection) {
                        DeviceConnection.ONLINE -> "在线"
                        DeviceConnection.OFFLINE -> "离线"
                        DeviceConnection.UNKNOWN -> "状态未知"
                    }}" else result.queryErrorMessage ?: "本次云端查询失败")
            } catch (_: TimeoutCancellationException) {
                mutableTest.value = ConnectionTestUiState(false, false,
                    message = "30 秒内未收到新的查询结果，连接测试未通过")
            } catch (cancelled: CancellationException) { throw cancelled }
        }
    }

    fun enterDesignPreview() = repository.enterDesignPreview()

    fun selectDemoScenario(scenario: DemoScenario) = repository.selectDemoScenario(scenario)

    fun exitDesignPreview() = repository.exitDesignPreview()

    fun setForeground(enabled: Boolean) = repository.setForeground(enabled)

    // 等待同一次本地写入完成；刷新流负责重启唯一的实时轮询。
    fun updateRefreshSeconds(seconds: Int) = saveLocal {
        repository.connectionStore.updateRefreshSeconds(seconds)
    }

    fun requestControl(target: ActuatorKey, enabled: Boolean) = repository.requestControl(target, enabled)
    fun requestOfflinePump(enabled: Boolean, expectedIdentity: SnapshotIdentity) =
        repository.requestOfflinePump(enabled, expectedIdentity)
    fun requestScene(preset: ManualScenePreset, expectedIdentity: SnapshotIdentity) =
        repository.requestScene(preset, expectedIdentity)
    fun stopSceneRemaining() = repository.stopSceneRemaining()
    fun refreshNow() = repository.refreshNow()
    fun resendControl() = repository.resendControl()
    fun dismissControlWaiting() = repository.dismissControlWaiting()
    fun enterReplay() = repository.enterReplay()
    fun exitReplay() = repository.exitReplay()
}
