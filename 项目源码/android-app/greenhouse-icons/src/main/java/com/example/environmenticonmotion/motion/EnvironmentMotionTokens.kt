package com.example.environmenticonmotion.motion

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

internal object EnvironmentMotionTokens {
    const val PressScale = 0.99f
    val EaseInOut = CubicBezierEasing(0.77f, 0f, 0.175f, 1f)
    val EaseOut = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)
    val PressReturnSpring = spring<Float>(
        dampingRatio = 1f,
        stiffness = 300f,
        visibilityThreshold = 0.0001f,
    )
    val DataResponseSpring = spring<Float>(
        dampingRatio = 1f,
        stiffness = 420f,
        visibilityThreshold = 0.001f,
    )
}

internal fun lightBreathingPeriodSeconds(lightLevel: Int): Float {
    require(lightLevel in 0..7) { "光照档位必须为零至七的整数" }
    // 档位只控制图标活动：零档八秒、七档三秒，不代表照度或设备调光。
    return 8f - 5f * lightLevel / 7f
}

internal data class AirHumidityMotionProfile(
    val periodSeconds: Float,
    val verticalDriftDp: Float,
    val horizontalDriftDp: Float,
    val glowAlpha: Float,
)

internal fun airHumidityMotionProfile(relativeHumidityPercent: Float): AirHumidityMotionProfile {
    require(relativeHumidityPercent.isFinite()) { "空气湿度必须是有限数值" }
    val normalized = ((relativeHumidityPercent.coerceIn(20f, 90f) - 20f) / 70f)
    return AirHumidityMotionProfile(
        periodSeconds = 7.5f - normalized * 2.5f,
        verticalDriftDp = 1.25f + normalized * 1.75f,
        horizontalDriftDp = 0.35f + normalized * 0.65f,
        glowAlpha = 0.035f + normalized * 0.065f,
    )
}

internal data class SoilMoistureVisualProfile(
    val dropletScale: Float,
    val dropletAlpha: Float,
    val glowAlpha: Float,
)

internal fun soilMoistureVisualProfile(moistureLevel: Float): SoilMoistureVisualProfile {
    require(moistureLevel.isFinite()) { "土壤湿度必须是有限数值" }
    val level = moistureLevel.coerceIn(0f, 1f)
    return SoilMoistureVisualProfile(
        dropletScale = 0.58f + level * 0.56f,
        dropletAlpha = 0.78f + level * 0.22f,
        glowAlpha = 0.025f + level * 0.075f,
    )
}

internal data class TemperatureVisualProfile(
    val glowAlpha: Float,
    val flowAlpha: Float,
    val flowPeriodSeconds: Float,
    val iconAlpha: Float,
    val iconScale: Float,
)

internal fun temperatureVisualProfile(temperatureProgress: Float): TemperatureVisualProfile {
    require(temperatureProgress.isFinite()) { "温度进度必须是有限数值" }
    val progress = temperatureProgress.coerceIn(0f, 1f)
    return TemperatureVisualProfile(
        glowAlpha = 0.04f + progress * 0.28f,
        flowAlpha = 0.03f + progress * 0.13f,
        flowPeriodSeconds = 9f - progress * 5.2f,
        iconAlpha = 0.92f + progress * 0.08f,
        iconScale = 1f + progress * 0.018f,
    )
}

@Composable
internal fun rememberEnvironmentMotionAllowed(): Boolean {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val inspection = LocalInspectionMode.current
    var resumed by remember(lifecycle) {
        mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    var systemEnabled by remember {
        mutableStateOf(
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) > 0f,
        )
    }
    DisposableEffect(context, lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        val settingsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                systemEnabled = Settings.Global.getFloat(
                    context.contentResolver,
                    Settings.Global.ANIMATOR_DURATION_SCALE,
                    1f,
                ) > 0f
            }
        }
        lifecycle.addObserver(observer)
        context.contentResolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            settingsObserver,
        )
        onDispose {
            lifecycle.removeObserver(observer)
            context.contentResolver.unregisterContentObserver(settingsObserver)
        }
    }
    return resumed && systemEnabled && !inspection && LocalEnvironmentAnimationsEnabled.current
}

// 应用设置统一暂停图标活动，保留原始素材、光晕和业务状态。
val LocalEnvironmentAnimationsEnabled = androidx.compose.runtime.staticCompositionLocalOf { true }
