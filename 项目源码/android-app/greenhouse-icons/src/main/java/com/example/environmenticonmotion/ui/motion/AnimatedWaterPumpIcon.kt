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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.example.environmenticonmotion.motion.EnvironmentMotionTokens
import com.example.environmenticonmotion.motion.rememberEnvironmentMotionAllowed
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.round
import kotlin.math.sin

enum class WaterPumpState {
    Stopped,
    Running,
    Fault,
}

/**
 * 水泵状态由业务提供；零至一的活动比例仅控制运行示意，不代表实测流量。
 * 活动比例为零时保留叶轮运行示意，不绘制水滴。
 * pressed 只提供视觉反馈，业务点击由外部按钮负责。
 */
@Composable
fun AnimatedWaterPumpIcon(
    state: WaterPumpState,
    visualActivityFraction: Float,
    modifier: Modifier = Modifier,
    pressed: Boolean = false,
    motionEnabled: Boolean = true,
) {
    require(visualActivityFraction.isFinite() && visualActivityFraction in 0f..1f) {
        "视觉活动比例必须是零至一之间的有限数值"
    }
    val speed = if (visualActivityFraction <= 0.5f) 45f + 90f * visualActivityFraction
    else 90f + 120f * (visualActivityFraction - 0.5f)
    val waterVisible = state == WaterPumpState.Running && visualActivityFraction > 0f
    val motionAllowed = rememberEnvironmentMotionAllowed()
    val active = motionAllowed && motionEnabled
    val layers = rememberWaterPumpLayers()
    val controller = remember { WaterPumpMotionController() }
    val press = remember { Animatable(1f) }
    val vibrationPx = with(LocalDensity.current) { 0.5.dp.toPx() }

    LaunchedEffect(state, speed, waterVisible, visualActivityFraction) {
        controller.request(state, speed, waterVisible, visualActivityFraction)
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
        WaterPumpState.Stopped -> "已停止"
        WaterPumpState.Running -> "运行中，图标为运行示意"
        WaterPumpState.Fault -> "故障"
    }
    Canvas(
        modifier = modifier.semantics {
            contentDescription = "水泵"
            stateDescription = description
        },
    ) {
        drawWaterPumpLayers(
            layers = layers,
            angle = controller.angle,
            rotorScale = if (active) controller.rotorScale else 1f,
            waterPhase = controller.waterPhase,
            waterAlpha = if (active) controller.waterAlpha else if (waterVisible) 0.9f else 0f,
            faultAlpha = if (active) controller.faultAlpha else if (state == WaterPumpState.Fault) 0.2f else 0f,
            glossProgress = if (active) controller.glossProgress else -1f,
            startupOffsetPx = if (active) controller.startupOffset * vibrationPx else 0f,
            pressScale = press.value,
        )
    }
}

private class WaterPumpMotionController {
    var angle by mutableFloatStateOf(0f)
        private set
    var rotorScale by mutableFloatStateOf(1f)
        private set
    var waterPhase by mutableFloatStateOf(0f)
        private set
    var waterAlpha by mutableFloatStateOf(0f)
        private set
    var faultAlpha by mutableFloatStateOf(0f)
        private set
    var glossProgress by mutableFloatStateOf(-1f)
        private set
    var startupOffset by mutableFloatStateOf(0f)
        private set

    private var state: WaterPumpState? = null
    private var speed = 0.0
    private var speedFrom = 0.0
    private var speedTarget = 0.0
    private var speedTime = 0.5
    private var parking: Parking? = null
    private var startupTime = 1.0
    private var waterTarget = 0f
    private var waterCyclesPerSecond = 1.0
    private var faultTime = 0.0
    private var glossTime = -1.0

    private data class Parking(
        val from: Double,
        val to: Double,
        val initialSpeed: Double,
        val duration: Double,
        var time: Double = 0.0,
    )

    fun request(next: WaterPumpState, nextSpeed: Float, waterVisible: Boolean, visualActivityFraction: Float) {
        val stateChanged = state != next
        if (next == WaterPumpState.Running && (stateChanged || speedTarget != nextSpeed.toDouble())) {
            // 改视觉活动比例只从当前速度重定向，不重置叶轮角度、水滴相位或启动震动。
            speedFrom = speed
            speedTarget = nextSpeed.toDouble()
            speedTime = 0.0
            parking = null
            if (stateChanged) {
                startupTime = 0.0
                glossTime = -1.0
            }
        } else if (stateChanged && next == WaterPumpState.Stopped) {
            if (speed > 0.0) {
                val current = angle.toDouble()
                val nearest = round(current / 90.0) * 90.0
                // 启动尚未展开就关闭时，回收极小偏转，避免低速归位耗时过长。
                parking = if (speed < 30.0 && abs(nearest - current) < 5.0) {
                    Parking(current, nearest, speed, 0.35)
                } else {
                    // 四片叶片每九十度同形；按当前速度和相位计算同向制动终点。
                    val target = ceil((current + speed * 0.45) / 90.0) * 90.0
                    Parking(current, target, speed, 2.0 * (target - current) / speed)
                }
            } else {
                val current = angle.toDouble()
                val target = round(current / 90.0) * 90.0
                if (current != target) {
                    parking = Parking(current, target, 0.0, 0.6)
                } else glossTime = 0.0
            }
            speedTarget = 0.0
        } else if (stateChanged && next == WaterPumpState.Fault) {
            // 故障立即停止叶轮，不继续模拟正常制动或出水。
            speed = 0.0
            speedTarget = 0.0
            parking = null
            faultTime = 0.0
            glossTime = -1.0
        }
        state = next
        waterTarget = if (waterVisible) 0.9f else 0f
        waterCyclesPerSecond = 1.0 / (1.4 - 0.75 * visualActivityFraction)
    }

    fun step(seconds: Double) {
        val stop = parking
        if (stop != null) {
            stop.time = (stop.time + seconds).coerceAtMost(stop.duration)
            val u = stop.time / stop.duration
            // 三次 Hermite 保持起始角速度连续，并以零速度到达固定角度。
            val h00 = 2 * u * u * u - 3 * u * u + 1
            val h10 = u * u * u - 2 * u * u + u
            val h01 = -2 * u * u * u + 3 * u * u
            angle = ((h00 * stop.from + h10 * stop.duration * stop.initialSpeed + h01 * stop.to) % 360).toFloat()
            val derivative = (6 * u * u - 6 * u) * stop.from +
                (3 * u * u - 4 * u + 1) * stop.duration * stop.initialSpeed +
                (-6 * u * u + 6 * u) * stop.to
            speed = derivative / stop.duration
            if (stop.time == stop.duration) {
                angle = (stop.to % 90.0).toFloat()
                speed = 0.0
                parking = null
                glossTime = 0.0
            }
        } else if (state == WaterPumpState.Running) {
            val oldSpeed = speed
            speedTime = (speedTime + seconds).coerceAtMost(0.5)
            val u = speedTime / 0.5
            speed = speedFrom + (speedTarget - speedFrom) * (u * u * (3 - 2 * u))
            angle = ((angle + (oldSpeed + speed) * seconds / 2.0) % 360).toFloat()
        }
        startupTime += seconds
        startupOffset = if (startupTime < 0.18 && state == WaterPumpState.Running) {
            (sin(startupTime / 0.18 * 4 * PI) * (1 - startupTime / 0.18)).toFloat()
        } else 0f
        rotorScale = if (startupTime < 0.28 && state == WaterPumpState.Running) {
            (1 - 0.025 * sin(startupTime / 0.28 * PI)).toFloat()
        } else 1f
        val waterChange = (seconds / if (waterTarget > waterAlpha) 0.32 else 0.24).toFloat()
        waterAlpha = if (waterTarget > waterAlpha) (waterAlpha + waterChange).coerceAtMost(waterTarget)
        else (waterAlpha - waterChange).coerceAtLeast(waterTarget)
        if (waterAlpha > 0f) {
            waterPhase = ((waterPhase + seconds * waterCyclesPerSecond) % 1.0).toFloat()
        }
        if (state == WaterPumpState.Fault) {
            faultTime = (faultTime + seconds) % 2.0
            faultAlpha = (0.12 + 0.12 * (1 - cos(PI * faultTime)) / 2).toFloat()
        } else faultAlpha = 0f
        if (glossTime >= 0.0) {
            glossTime += seconds
            glossProgress = if (glossTime < 3.0) (glossTime / 3.0).toFloat() else -1f
            if (glossTime >= 3.0) glossTime = -1.0
        } else glossProgress = -1f
    }
}
