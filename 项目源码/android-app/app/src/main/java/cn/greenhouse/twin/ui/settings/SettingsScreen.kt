package cn.greenhouse.twin.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cn.greenhouse.twin.BuildConfig
import cn.greenhouse.twin.domain.*
import cn.greenhouse.twin.domain.monitoring.MonitoringSettings
import cn.greenhouse.twin.ui.components.GreenhouseCard
import cn.greenhouse.twin.ui.components.SectionTitle
import cn.greenhouse.twin.ui.components.StatusTag
import cn.greenhouse.twin.ui.theme.GreenhouseColors as C
import cn.greenhouse.twin.ui.theme.GreenhouseDimensions as D
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// 测试结果由本次真实查询回调发布，不能把页面原有 SUCCESS 当成测试成功。
data class ConnectionTestUiState(
    val pending: Boolean = false,
    val succeeded: Boolean? = null,
    val completedAtUtcMs: Long? = null,
    val message: String? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: GreenhouseTwinState,
    monitoring: MonitoringSettings,
    monitoringLoading: Boolean,
    monitoringSaving: Boolean,
    monitoringError: String?,
    monitoringSavedAtUtcMs: Long?,
    animationsEnabled: Boolean,
    connectionTest: ConnectionTestUiState,
    onBack: () -> Unit,
    onTestConnection: () -> Unit,
    onRefresh: () -> Unit,
    onSaveMonitoringSettings: (MonitoringSettings) -> Unit,
    onUpdateRefreshSeconds: (Int) -> Unit,
    onAnimationChanged: (Boolean) -> Unit,
    onOpenData: () -> Unit,
    onImportCsv: () -> Unit,
) {
    var editMonitoring by rememberSaveable { mutableStateOf(false) }
    val configured = state.configuredProductId != null && state.configuredDeviceName != null
    val canQuery = configured && state.stateSource == StateSource.LIVE &&
        !state.synchronizationSuspended && !connectionTest.pending
    Scaffold(
        containerColor = C.Background,
        contentColor = C.Text,
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { insets ->
        LazyColumn(
            Modifier.fillMaxSize().padding(insets),
            contentPadding = PaddingValues(horizontal = D.PageMargin, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("设置", Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
                    TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text("返回") }
                }
            }
            item {
                SectionTitle("设备与连接")
                Spacer(Modifier.height(8.dp))
                GreenhouseCard {
                    SettingDetail("设备名称", state.configuredDeviceName ?: "未配置")
                    SettingDetail("OneNET 产品", state.configuredProductId ?: "未配置")
                    SettingDetail("设备启动标识", state.snapshotIdentity?.bootId ?: "未知")
                    SettingDetail("固件版本", "未知")
                    SettingDetail("Wi-Fi RSSI", "未知")
                    SettingDetail("运行时间 · 最近记录", uptimeLabel(state.snapshotUptimeMs))
                    SettingDetail("最后同步 · 北京时间", settingsTime(state.lastSyncedAtUtcMs))
                    SettingDetail("最近查询 · 北京时间", settingsTime(state.lastQueryAtUtcMs))
                    SettingDetail("数据来源", when (state.stateSource) {
                        StateSource.LIVE -> "实时 LIVE"
                        StateSource.REPLAY -> "历史回放 REPLAY"
                        StateSource.DESIGN_DEMO -> "设计预览 DESIGN_DEMO"
                    })
                    SettingDetail("设备连接", when (state.deviceConnection) {
                        DeviceConnection.UNKNOWN -> "未知"
                        DeviceConnection.ONLINE -> "在线"
                        DeviceConnection.OFFLINE -> "离线"
                    })
                    SettingDetail("云端查询", cloudQueryLabel(state.cloudQueryStatus))
                    SettingDetail("数据新鲜度", when (state.freshness) {
                        DataFreshness.UNKNOWN -> "时间未知"
                        DataFreshness.FRESH -> "实时"
                        DataFreshness.DELAYED -> "延迟 · 保留最近数据"
                        DataFreshness.STALE -> "陈旧 · 最近记录不代表当前状态"
                    })
                    if (state.queryErrorMessage != null) {
                        Text(state.queryErrorMessage, color = C.Critical,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onTestConnection, enabled = canQuery,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                            Text(if (connectionTest.pending) "正在测试" else "连接测试")
                        }
                        OutlinedButton(onClick = onRefresh, enabled = canQuery,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("重新同步") }
                    }
                    ConnectionTestResult(connectionTest)
                    if (!canQuery && !connectionTest.pending) {
                        Text(if (!configured) "先完成连接配置。" else "回到前台实时模式后可测试与同步。",
                            color = C.Muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item {
                SectionTitle("监测与报警", "手机本地")
                Spacer(Modifier.height(8.dp))
                GreenhouseCard {
                    when {
                        monitoringLoading && monitoringError != null -> Text("本地配置未知，无法编辑报警规则", color = C.Secondary)
                        monitoringLoading -> Text("正在读取本地设置", color = C.Secondary)
                        else -> {
                            MonitoringSummary(monitoring)
                            Text("阈值只用于手机前台实时监测，保存不会修改 ESP32 配置或自动控制规则。",
                                color = C.Muted, style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = { editMonitoring = true }, enabled = !monitoringSaving,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                Text("编辑本地报警阈值")
                            }
                            if (monitoringSavedAtUtcMs != null) {
                                Text("本地保存：${settingsTime(monitoringSavedAtUtcMs)}",
                                    color = C.Secondary, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    if (monitoringError != null) Text(monitoringError, color = C.Critical,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            item {
                SectionTitle("刷新与显示")
                Spacer(Modifier.height(8.dp))
                GreenhouseCard {
                    SettingDetail("刷新间隔", "${state.refreshSeconds} 秒")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(5, 10, 30).forEach { seconds ->
                            val selected = state.refreshSeconds == seconds
                            OutlinedButton(onClick = { onUpdateRefreshSeconds(seconds) },
                                enabled = configured && state.stateSource == StateSource.LIVE &&
                                    !monitoringLoading && !monitoringSaving,
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                                border = BorderStroke(1.dp, if (selected) C.Primary else C.Outline)) {
                                Text("$seconds 秒", color = if (selected) C.Primary else C.Secondary)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("默认 10 秒，最短 5 秒；仅应用前台实时页面刷新，后台、息屏与回放暂停。",
                        color = C.Muted, style = MaterialTheme.typography.bodySmall)
                    SettingDetail("主题", "深色")
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("界面动画", style = MaterialTheme.typography.bodyLarge)
                            Text("图标与模型动效，设备反馈保持原样", color = C.Muted,
                                style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = animationsEnabled, onCheckedChange = onAnimationChanged,
                            enabled = !monitoringLoading && !monitoringSaving,
                            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp))
                    }
                    if (monitoringSaving) Text("正在保存本地设置", color = C.Secondary,
                        style = MaterialTheme.typography.bodySmall)
                    else if (monitoringLoading) Text("本地设置尚未读取，刷新与动画设置暂不可修改", color = C.Muted,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            item {
                SectionTitle("数据管理")
                Spacer(Modifier.height(8.dp))
                GreenhouseCard {
                    SettingDetail("本机真实记录", "${state.storedRecordCount} 条")
                    SettingDetail("云端数据保留期", "未知")
                    Text("本地记录来自真实实时同步与档案导入；设计样例不计入历史。",
                        color = C.Muted, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onOpenData,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("查看数据中心") }
                    OutlinedButton(onClick = onImportCsv,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("导入 CSV 档案") }
                }
            }
            item {
                SectionTitle("关于项目")
                Spacer(Modifier.height(8.dp))
                GreenhouseCard {
                    SettingDetail("应用版本", "${BuildConfig.VERSION_NAME} · ${BuildConfig.VERSION_CODE}")
                    Text("数字孪生智慧大棚 · 半实物原型", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text("温度与空气湿度：SENSOR 真实采集。\n光照：EXP 实验输入，0–7 档。\n土壤湿度：SIM 软件模型。\n泵、补光与风扇以三路 LED 演示真实控制反馈，3D 表现不等于实际流量、照度或风速测量。",
                        color = C.Secondary, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    Text("AUTO 与设备端自动规则尚未开放；手机环境建议不会直接改变设备状态。",
                        color = C.Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }
    if (editMonitoring) {
        ModalBottomSheet(
            onDismissRequest = { if (!monitoringSaving) editMonitoring = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = C.Overview,
            contentColor = C.Text,
        ) {
            cn.greenhouse.twin.ui.monitoring.MonitoringSheetSystemBars()
            MonitoringSettingsEditor(monitoring, monitoringSaving, monitoringError,
                monitoringSavedAtUtcMs, onSaveMonitoringSettings) { editMonitoring = false }
        }
    }
}

@Composable
private fun SettingDetail(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, color = C.Muted, style = MaterialTheme.typography.labelMedium)
        Text(value, color = C.Text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ConnectionTestResult(test: ConnectionTestUiState) {
    val color = when {
        test.pending -> C.Waiting
        test.succeeded == true -> C.Primary
        test.succeeded == false -> C.Critical
        else -> C.Muted
    }
    val label = when {
        test.pending -> "等待本次真实查询返回"
        test.succeeded == true -> "本次连接测试成功"
        test.succeeded == false -> "本次连接测试失败"
        else -> "尚未进行连接测试"
    }
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = color, style = MaterialTheme.typography.bodyMedium)
        if (!test.pending && test.completedAtUtcMs != null) {
            Text(settingsTime(test.completedAtUtcMs), color = C.Muted,
                style = MaterialTheme.typography.bodySmall)
        }
        if (test.message != null) Text(test.message, color = C.Secondary,
            style = MaterialTheme.typography.bodySmall)
    }
}

private fun settingsTime(utcMs: Long?): String = if (utcMs == null) "未知" else
    SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).apply {
        timeZone = TimeZone.getTimeZone("Asia/Shanghai")
    }.format(Date(utcMs))

private fun uptimeLabel(ms: Long?): String {
    if (ms == null) return "未知"
    val seconds = ms / 1_000
    return if (seconds >= 3_600) "${seconds / 3_600} 小时 ${(seconds % 3_600) / 60} 分"
    else if (seconds >= 60) "${seconds / 60} 分 ${seconds % 60} 秒" else "$seconds 秒"
}

private fun cloudQueryLabel(status: CloudQueryStatus) = when (status) {
    CloudQueryStatus.UNCONFIGURED -> "未配置"
    CloudQueryStatus.IDLE -> "等待查询"
    CloudQueryStatus.QUERYING -> "正在查询"
    CloudQueryStatus.SUCCESS -> "最近查询成功"
    CloudQueryStatus.FAILED -> "最近查询失败"
}

@Composable
private fun MonitoringSummary(settings: MonitoringSettings) {
    StatusTag(if (settings.monitorEnabled) "本地监测已启用" else "本地监测未启用",
        if (settings.monitorEnabled) C.Primary else C.Muted)
    SettingDetail("温度 · SENSOR", thresholdRange(settings.temperatureLowC, settings.temperatureHighC, "°C"))
    SettingDetail("空气湿度 · SENSOR", thresholdRange(settings.humidityLowPercent, settings.humidityHighPercent, "%"))
    SettingDetail("土壤湿度 · SIM", settings.soilLowPercent?.let { "低于 ${thresholdNumber(it)}%" }
        ?: "未配置 · 不启用低湿规则")
    SettingDetail("光照 · EXP", settings.lightLowLevel?.let { "低于 $it 档（0–7）" }
        ?: "未配置 · 不启用低光规则")
    SettingDetail("数据陈旧时间", "超过 ${settings.staleAfterMs / 1_000} 秒")
}

private fun thresholdRange(low: Double?, high: Double?, unit: String): String =
    listOfNotNull(low?.let { "低于 ${thresholdNumber(it)}$unit" },
        high?.let { "高于 ${thresholdNumber(it)}$unit" }).joinToString(" / ")
        .ifEmpty { "未配置 · 不启用数值规则" }

private fun thresholdNumber(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

@Composable
private fun MonitoringSettingsEditor(
    initial: MonitoringSettings,
    saving: Boolean,
    saveError: String?,
    savedAtUtcMs: Long?,
    onSave: (MonitoringSettings) -> Unit,
    onClose: () -> Unit,
) {
    var enabled by rememberSaveable { mutableStateOf(initial.monitorEnabled) }
    var temperatureLow by rememberSaveable { mutableStateOf(initial.temperatureLowC?.let(::thresholdNumber).orEmpty()) }
    var temperatureHigh by rememberSaveable { mutableStateOf(initial.temperatureHighC?.let(::thresholdNumber).orEmpty()) }
    var humidityLow by rememberSaveable { mutableStateOf(initial.humidityLowPercent?.let(::thresholdNumber).orEmpty()) }
    var humidityHigh by rememberSaveable { mutableStateOf(initial.humidityHighPercent?.let(::thresholdNumber).orEmpty()) }
    var soilLow by rememberSaveable { mutableStateOf(initial.soilLowPercent?.let(::thresholdNumber).orEmpty()) }
    var lightLow by rememberSaveable { mutableStateOf(initial.lightLowLevel?.toString().orEmpty()) }
    var staleSeconds by rememberSaveable { mutableStateOf((initial.staleAfterMs / 1_000).toString()) }
    val values = listOf(temperatureLow, temperatureHigh, humidityLow, humidityHigh, soilLow)
    val numberError = values.any { it.isNotBlank() && (it.trim().toDoubleOrNull()?.isFinite() != true) }
    val light = lightLow.trim().toIntOrNull()
    val seconds = staleSeconds.trim().toLongOrNull()
    var validationError: String? = when {
        numberError -> "阈值须为有限数值；留空表示该条规则不启用。"
        lightLow.isNotBlank() && light == null -> "光照须为 0 至 7 的整数档位，或留空。"
        seconds == null || seconds <= 25 || seconds > Long.MAX_VALUE / 1_000 -> "数据陈旧时间须为大于 25 的整数秒。"
        else -> null
    }
    var candidate: MonitoringSettings? = null
    if (validationError == null && seconds != null) {
        val parsed = initial.copy(
            monitorEnabled = enabled,
            temperatureLowC = temperatureLow.trim().toDoubleOrNull(),
            temperatureHighC = temperatureHigh.trim().toDoubleOrNull(),
            humidityLowPercent = humidityLow.trim().toDoubleOrNull(),
            humidityHighPercent = humidityHigh.trim().toDoubleOrNull(),
            soilLowPercent = soilLow.trim().toDoubleOrNull(),
            lightLowLevel = light,
            staleAfterMs = seconds * 1_000,
        )
        try {
            parsed.validate()
            candidate = parsed
        } catch (error: IllegalArgumentException) {
            validationError = error.message
        }
    }
    val inputError = validationError
    Column(
        Modifier.fillMaxWidth().imePadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = D.PageMargin)
            .padding(bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("本地报警阈值", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onClose, enabled = !saving,
                modifier = Modifier.heightIn(min = 48.dp)) { Text("完成") }
        }
        Text("仅保存在手机。留空的阈值不启用对应规则；不会发送到 ESP32。",
            color = C.Secondary, style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("启用本地监测", style = MaterialTheme.typography.bodyLarge)
                Text("仅前台实时模式，历史回放暂停", color = C.Muted,
                    style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = enabled, onCheckedChange = { enabled = it }, enabled = !saving,
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp))
        }
        Text("温度 · SENSOR", style = MaterialTheme.typography.titleMedium)
        ThresholdField("温度下限（°C）", temperatureLow, !saving) { temperatureLow = it }
        ThresholdField("温度上限（°C）", temperatureHigh, !saving) { temperatureHigh = it }
        Text("空气湿度 · SENSOR", style = MaterialTheme.typography.titleMedium)
        Text("可留空；只有已配置的阈值参与监测。", color = C.Muted,
            style = MaterialTheme.typography.bodySmall)
        ThresholdField("湿度下限（0–100%）", humidityLow, !saving) { humidityLow = it }
        ThresholdField("湿度上限（0–100%）", humidityHigh, !saving) { humidityHigh = it }
        Text("土壤与光照", style = MaterialTheme.typography.titleMedium)
        ThresholdField("土壤湿度下限（0–100% SIM）", soilLow, !saving) { soilLow = it }
        ThresholdField("光照下限（0–7 档 EXP）", lightLow, !saving, integer = true) { lightLow = it }
        Text("数据时效", style = MaterialTheme.typography.titleMedium)
        ThresholdField("数据陈旧时间（秒，大于 25）", staleSeconds, !saving, integer = true) { staleSeconds = it }
        Text("25 秒以内为实时；超过 25 秒为延迟，超过这里的阈值为陈旧。时间不可信仍显示未知。",
            color = C.Muted, style = MaterialTheme.typography.bodySmall)
        if (inputError != null) Text(inputError, color = C.Critical,
            style = MaterialTheme.typography.bodyMedium)
        if (saveError != null) Text(saveError, color = C.Critical,
            style = MaterialTheme.typography.bodyMedium)
        if (savedAtUtcMs != null) Text("最近本地保存：${settingsTime(savedAtUtcMs)}",
            color = C.Secondary, style = MaterialTheme.typography.bodySmall)
        val validated = candidate
        Button(onClick = { if (validated != null) onSave(validated) },
            enabled = validated != null && !saving,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(if (saving) "正在保存到手机" else "保存本地阈值")
        }
        Text("保存设置不代表设备已应用配置，也不会自动执行泵、补光或风扇。",
            color = C.Muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ThresholdField(
    label: String,
    value: String,
    enabled: Boolean,
    integer: Boolean = false,
    onValue: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
        enabled = enabled,
        label = { Text(label) },
        placeholder = { Text("留空则不启用") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (integer) KeyboardType.Number else KeyboardType.Decimal),
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = C.Primary,
            unfocusedBorderColor = C.Outline, focusedLabelColor = C.Primary),
    )
}
