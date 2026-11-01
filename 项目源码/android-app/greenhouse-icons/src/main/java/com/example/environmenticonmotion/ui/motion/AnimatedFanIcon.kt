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
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.cos

enum class FanState {
    Stopped,
    Running,
    Fault,
}

/**
 * 风扇由业务状态和零至一的视觉活动比例驱动，不代表实测转速或调速控制。
 * 零活动时不旋转；关闭缓慢制动，故障立即停转。
 * pressed 仅提供视觉反馈，真实点击由外部业务按钮负责。
 */
@Composable
fun AnimatedFanIcon(
    state: FanState,
    visualActivityFraction: Float,
    modifier: Modifier = Modifier,
    pressed: Boolean = false,
    motionEnabled: Boolean = true,
) {
    require(visualActivityFraction.isFinite() && visualActivityFraction in 0f..1f) {
        "视觉活动比例必须是零至一之间的有限数值"
    }
    val motionAllowed = rememberEnvironmentMotionAllowed()
    val active = motionAllowed && motionEnabled
    val artwork = rememberFanArtwork()
    val controller = remember { FanMotionController() }
    val press = remember { Animatable(1f) }

    LaunchedEffect(state, visualActivityFraction, active) {
        controller.request(state, visualActivityFraction * 360f)
        if (!active) controller.settleWhilePaused()
    }
    LaunchedEffect(pressed, active) {
        if (!active) press.snapTo(1f)
        else if (pressed) press.animateTo(EnvironmentMotionTokens.PressScale, tween(100, easing = EnvironmentMotionTokens.EaseOut))
        else press.animateTo(1f, EnvironmentMotionTokens.PressReturnSpring)
    }
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        var previous = withFrameNanos { it }
        val durationScale = checkNotNull(currentCoroutineContext()[MotionDurationScale])
        while (isActive) {
            val frame = withFrameNanos { it }
            if (durationScale.scaleFactor > 0f) {
                controller.step((frame - previous) / 1_000_000_000.0 / durationScale.scaleFactor)
            }
            previous = frame
        }
    }
    val description = when (state) {
        FanState.Stopped -> "已关闭"
        FanState.Running -> "运行中，图标为运行示意"
        FanState.Fault -> "故障，叶片已停止"
    }
    Canvas(modifier = modifier.semantics {
        contentDescription = "风扇"
        stateDescription = description
    }) {
        drawFanArtwork(
            artwork = artwork,
            angle = controller.angle,
            faultAlpha = if (state == FanState.Fault) {
                if (active) controller.faultAlpha else 0.18f
            } else 0f,
            pressScale = if (active) press.value else 1f,
        )
    }
}

private class FanMotionController {
    var angle by mutableFloatStateOf(0f)
        private set
    var faultAlpha by mutableFloatStateOf(0.12f)
        private set

    private var state = FanState.Stopped
    private var speed = 0.0
    private var speedFrom = 0.0
    private var speedTarget = 0.0
    private var speedTime = 0.0
    private var speedDuration = 0.45
    private var faultTime = 0.0

    fun request(next: FanState, requestedSpeed: Float) {
        val target = if (next == FanState.Running) requestedSpeed.toDouble() else 0.0
        if (next == FanState.Fault) {
            if (state != next) faultTime = 0.0
            speed = 0.0
            speedFrom = 0.0
            speedTarget = 0.0
            speedTime = speedDuration
        } else if (target != speedTarget || state == FanState.Fault) {
            // 沿用水泵的当前速度重定向逻辑；调整速度时不重置叶片角度。
            speedFrom = speed
            speedTarget = target
            speedTime = 0.0
            speedDuration = if (target == 0.0) 0.85 else 0.45
        }
        state = next
    }

    fun settleWhilePaused() {
        // 离屏或系统关闭动效时只更新目标速度，不累计后台时间或改变停止角度。
        speed = speedTarget
        speedFrom = speedTarget
        speedTime = speedDuration
    }

    fun step(seconds: Double) {
        val oldSpeed = speed
        val oldTime = speedTime
        speedTime = (speedTime + seconds).coerceAtMost(speedDuration)
        val u = speedTime / speedDuration
        speed = speedFrom + (speedTarget - speedFrom) * (u * u * (3 - 2 * u))
        // 对平滑速度曲线积分，帧间隔变化时仍保持连续，不积累逐帧误差。
        fun distanceAt(time: Double): Double {
            val progress = time / speedDuration
            return speedFrom * time + (speedTarget - speedFrom) * speedDuration *
                (progress * progress * progress - progress * progress * progress * progress / 2)
        }
        val transitionSeconds = speedTime - oldTime
        val distance = if (transitionSeconds > 0.0) distanceAt(speedTime) - distanceAt(oldTime) else 0.0
        angle = ((angle + distance + speed * (seconds - transitionSeconds)) % 360).toFloat()
        if (state == FanState.Fault) {
            faultTime = (faultTime + seconds) % 2.0
            faultAlpha = (0.12 + 0.12 * (1 - cos(PI * faultTime)) / 2).toFloat()
        }
    }
}
