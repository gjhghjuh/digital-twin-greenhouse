package com.example.environmenticonmotion.ui.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.example.environmenticonmotion.motion.EnvironmentMotionTokens
import com.example.environmenticonmotion.motion.rememberEnvironmentMotionAllowed
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.cos

enum class GrowLightState {
    Off,
    On,
    Fault,
}

private class GrowLightPhase(var radians: Double = 0.0)

/**
 * 补光灯接收业务状态和零至一的视觉活动比例，不代表实测亮度或调光控制。
 * pressed 由外部业务按钮提供，图标本身不附带点击操作。
 */
@Composable
fun AnimatedGrowLightIcon(
    state: GrowLightState,
    visualActivityFraction: Float,
    modifier: Modifier = Modifier,
    idleEnabled: Boolean = true,
    pressed: Boolean = false,
    motionEnabled: Boolean = true,
) {
    require(visualActivityFraction.isFinite() && visualActivityFraction in 0f..1f) {
        "视觉活动比例必须是零至一之间的有限数值"
    }
    val motionAllowed = rememberEnvironmentMotionAllowed()
    val active = motionAllowed && motionEnabled
    val target = if (state == GrowLightState.On) visualActivityFraction else 0f
    val intensity = remember { Animatable(target) }
    val beam = remember { Animatable(target) }
    val press = remember { Animatable(1f) }
    val phase = remember { GrowLightPhase() }
    var wave by remember { mutableFloatStateOf(0.5f) }
    val artwork = rememberGrowLightArtwork()

    LaunchedEffect(target, active, state) {
        if (!active || state == GrowLightState.Fault) intensity.snapTo(target)
        else intensity.animateTo(
            target,
            tween(if (target > intensity.value) 500 else 300, easing = EnvironmentMotionTokens.EaseOut),
        )
    }
    LaunchedEffect(target, active, state) {
        if (!active || state == GrowLightState.Fault) beam.snapTo(target)
        else {
            // 灯板先点亮，光束随后渐入；连续改变视觉活动时从当前值平滑重定向。
            if (target > 0f && beam.value == 0f) delay(80)
            beam.animateTo(
                target,
                tween(if (target > beam.value) 500 else 300, easing = EnvironmentMotionTokens.EaseOut),
            )
        }
    }
    LaunchedEffect(pressed, active) {
        if (!active) press.snapTo(1f)
        else if (pressed) press.animateTo(EnvironmentMotionTokens.PressScale, tween(100, easing = EnvironmentMotionTokens.EaseOut))
        else press.animateTo(1f, EnvironmentMotionTokens.PressReturnSpring)
    }
    val ambientMotion = state == GrowLightState.On && idleEnabled && visualActivityFraction > 0f
    LaunchedEffect(active, ambientMotion, state) {
        if (!active || (!ambientMotion && state != GrowLightState.Fault)) return@LaunchedEffect
        var previous = withFrameNanos { it }
        val durationScale = checkNotNull(currentCoroutineContext()[MotionDurationScale])
        val period = if (state == GrowLightState.Fault) 2.0 else 4.8
        while (isActive) {
            val frame = withFrameNanos { it }
            if (durationScale.scaleFactor > 0f) {
                val seconds = (frame - previous) / 1_000_000_000.0 / durationScale.scaleFactor
                // 相位独立保存，改变视觉活动不重启周期，恢复时不累计后台停留时间。
                phase.radians = (phase.radians + seconds * 2 * PI / period) % (2 * PI)
                wave = ((1 - cos(phase.radians)) / 2).toFloat()
            }
            previous = frame
        }
    }
    val description = when (state) {
        GrowLightState.Off -> "已关闭"
        GrowLightState.On -> "已开启，图标为运行示意"
        GrowLightState.Fault -> "故障，补光已停止"
    }
    Canvas(modifier = modifier.semantics {
        contentDescription = "补光灯"
        stateDescription = description
    }) {
        val steadyIntensity = if (active) intensity.value else target
        val beamLevel = if (active) beam.value else target
        val ambient = if (active && ambientMotion) 0.97f + 0.03f * wave else 1f
        drawGrowLightArtwork(
            artwork = artwork,
            intensity = steadyIntensity,
            beamIntensity = beamLevel * ambient,
            faultAlpha = if (state == GrowLightState.Fault) {
                if (active) 0.10f + 0.14f * wave else 0.17f
            } else 0f,
            pressScale = if (active) press.value else 1f,
        )
    }
}
