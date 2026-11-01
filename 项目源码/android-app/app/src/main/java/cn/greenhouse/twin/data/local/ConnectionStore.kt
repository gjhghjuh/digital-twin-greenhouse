package cn.greenhouse.twin.data.local

import android.content.Context
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import cn.greenhouse.twin.data.security.OneNetCredentialVault
import cn.greenhouse.twin.domain.monitoring.MonitoringSettings
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

private val Context.connectionPreferences by preferencesDataStore(name = "onenet_connection")

data class OneNetSettings(
    val productId: String,
    val deviceName: String,
    val refreshSeconds: Int = 10,
    val delayedAfterMs: Long = 25_000,
    val staleAfterMs: Long = 60_000,
) {
    init {
        require(productId == PROJECT_PRODUCT_ID && deviceName == PROJECT_DEVICE_NAME) {
            "连接配置不是本项目已核验的 OneNET 产品与设备"
        }
        require(refreshSeconds >= 5) { "刷新间隔不得小于五秒" }
        require(delayedAfterMs > 0 && staleAfterMs > delayedAfterMs) { "数据时效阈值不正确" }
    }

    companion object {
        const val PROJECT_PRODUCT_ID = "28Q9sTZMG6"
        const val PROJECT_DEVICE_NAME = "ESP32-ccaa29c4f5fc"
    }
}

// DataStore 只保留非秘密字段；API 密钥由独立的 KeyStore 加密文件管理。
class ConnectionStore(context: Context) {
    private val applicationContext = context.applicationContext
    private val preferences = applicationContext.connectionPreferences
    private val vault = OneNetCredentialVault(applicationContext)

    val settings: Flow<OneNetSettings?> = preferences.data.map { values ->
        val productId = values[PRODUCT_ID]
        val deviceName = values[DEVICE_NAME]
        val refreshSeconds = values[REFRESH_SECONDS]
        if (productId == null && deviceName == null && refreshSeconds == null) {
            null
        } else {
            check(productId != null && deviceName != null && refreshSeconds != null) {
                "OneNET 非秘密连接配置不完整"
            }
            OneNetSettings(productId, deviceName, refreshSeconds,
                staleAfterMs = values[STALE_AFTER_MS] ?: 60_000)
        }
    }

    // 首次安装的初始化值与已保存规则分开，空阈值保持未配置，不补造湿度与光照参数。
    val monitoringSettings: Flow<MonitoringSettings> = preferences.data.map { values ->
        if (values[MONITORING_CONFIGURED] != true) {
            MonitoringSettings(staleAfterMs = values[STALE_AFTER_MS] ?: 60_000,
                animationsEnabled = values[ANIMATIONS_ENABLED] ?: true)
        } else {
            MonitoringSettings(values[TEMPERATURE_LOW], values[TEMPERATURE_HIGH],
                values[HUMIDITY_LOW], values[HUMIDITY_HIGH], values[SOIL_LOW], values[LIGHT_LOW],
                checkNotNull(values[STALE_AFTER_MS]) { "监测陈旧时间配置不完整" },
                checkNotNull(values[ANIMATIONS_ENABLED]) { "动画配置不完整" },
                checkNotNull(values[MONITOR_ENABLED]) { "监测启用配置不完整" })
        }.also { it.validate() }
    }

    suspend fun current(): OneNetSettings? = settings.first()

    suspend fun importPendingConfiguration(): Boolean = withContext(Dispatchers.IO) {
        importMutex.withLock {
            val importFile = File(applicationContext.filesDir, "connection-import.json")
            if (!importFile.exists()) return@withLock false
            require(importFile.length() in 1..65_536) { "连接导入文件大小不正确" }
            val document = try {
                JSONObject(importFile.readText(Charsets.UTF_8))
            } catch (_: Exception) {
                throw IllegalArgumentException("连接导入文件必须是有效 UTF-8 JSON 对象")
            }
            require(document.keys().asSequence().all { it in IMPORT_FIELDS }) {
                "连接导入文件包含未定义字段"
            }
            val productId = requiredString(document, "productId")
            val deviceName = requiredString(document, "deviceName")
            val accessKey = requiredString(document, "accessKey")
            val refreshSeconds = if (document.has("refreshSeconds")) {
                val value = document.get("refreshSeconds")
                require(value is Number && value.toDouble() == value.toInt().toDouble()) {
                    "刷新间隔必须是整数秒"
                }
                value.toInt()
            } else {
                10
            }
            val imported = OneNetSettings(productId, deviceName, refreshSeconds)
            require(accessKey.matches(Regex("[A-Za-z0-9+/]+={0,2}"))) {
                "OneNET API 密钥须使用标准 Base64 格式"
            }
            val decoded = try {
                Base64.decode(accessKey, Base64.DEFAULT)
            } catch (_: IllegalArgumentException) {
                throw IllegalArgumentException("OneNET API 密钥须使用标准 Base64 格式")
            }
            require(decoded.isNotEmpty()) { "OneNET API 密钥不能为空" }
            decoded.fill(0)
            vault.save(accessKey)
            preferences.edit { values ->
                values[PRODUCT_ID] = imported.productId
                values[DEVICE_NAME] = imported.deviceName
                values[REFRESH_SECONDS] = imported.refreshSeconds
            }
            check(importFile.delete()) { "连接配置已导入，但明文导入文件未能删除" }
            true
        }
    }

    suspend fun updateRefreshSeconds(seconds: Int) {
        require(seconds >= 5) { "刷新间隔不得小于五秒" }
        check(current() != null) { "须先导入 OneNET 连接配置" }
        preferences.edit { it[REFRESH_SECONDS] = seconds }
    }

    suspend fun saveMonitoringSettings(settings: MonitoringSettings) {
        settings.validate()
        preferences.edit { values ->
            fun saveNumber(key: androidx.datastore.preferences.core.Preferences.Key<Double>, value: Double?) {
                if (value == null) values.remove(key) else values[key] = value
            }
            saveNumber(TEMPERATURE_LOW, settings.temperatureLowC)
            saveNumber(TEMPERATURE_HIGH, settings.temperatureHighC)
            saveNumber(HUMIDITY_LOW, settings.humidityLowPercent)
            saveNumber(HUMIDITY_HIGH, settings.humidityHighPercent)
            saveNumber(SOIL_LOW, settings.soilLowPercent)
            if (settings.lightLowLevel == null) values.remove(LIGHT_LOW) else values[LIGHT_LOW] = settings.lightLowLevel
            values[STALE_AFTER_MS] = settings.staleAfterMs
            values[ANIMATIONS_ENABLED] = settings.animationsEnabled
            values[MONITOR_ENABLED] = settings.monitorEnabled
            values[MONITORING_CONFIGURED] = true
        }
    }

    suspend fun updateStaleAfterMs(milliseconds: Long) {
        require(milliseconds > 25_000) { "陈旧时间必须大于 25 秒" }
        preferences.edit { it[STALE_AFTER_MS] = milliseconds }
    }

    suspend fun updateAnimationsEnabled(enabled: Boolean) {
        preferences.edit { it[ANIMATIONS_ENABLED] = enabled }
    }

    fun accessKey(): String? = vault.read()

    private fun requiredString(document: JSONObject, field: String): String {
        val value = document.opt(field)
        require(value is String && value.isNotBlank()) { "连接导入字段 $field 缺失或类型不正确" }
        return value
    }

    private companion object {
        val importMutex = Mutex()
        val PRODUCT_ID = stringPreferencesKey("product_id")
        val DEVICE_NAME = stringPreferencesKey("device_name")
        val REFRESH_SECONDS = intPreferencesKey("refresh_seconds")
        val MONITORING_CONFIGURED = booleanPreferencesKey("monitoring_configured")
        val MONITOR_ENABLED = booleanPreferencesKey("monitor_enabled")
        val ANIMATIONS_ENABLED = booleanPreferencesKey("environment_animations_enabled")
        val STALE_AFTER_MS = longPreferencesKey("stale_after_ms")
        val TEMPERATURE_LOW = doublePreferencesKey("alarm_temperature_low_c")
        val TEMPERATURE_HIGH = doublePreferencesKey("alarm_temperature_high_c")
        val HUMIDITY_LOW = doublePreferencesKey("alarm_humidity_low_percent")
        val HUMIDITY_HIGH = doublePreferencesKey("alarm_humidity_high_percent")
        val SOIL_LOW = doublePreferencesKey("alarm_soil_low_percent")
        val LIGHT_LOW = intPreferencesKey("alarm_light_low_level")
        val IMPORT_FIELDS = setOf("productId", "deviceName", "accessKey", "refreshSeconds")
    }
}
