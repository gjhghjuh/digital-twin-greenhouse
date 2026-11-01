package com.example.environmenticonmotion.ui.motion

import androidx.annotation.DrawableRes
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.example.environmenticonmotion.motion.EnvironmentMotionTokens
import com.example.environmenticonmotion.motion.rememberEnvironmentMotionAllowed
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlin.math.min
import kotlin.math.roundToInt

/** 从导航绘制复制适配，沿用 Fit、按压、生命周期及系统动效缩放。 */
@Composable
internal fun AlarmIconMotion(
    @DrawableRes resource: Int,
    severity: AlarmSeverity,
    affectedSources: Set<AlarmSource>,
    activityFraction: Float,
    acknowledged: Boolean,
    modifier: Modifier,
    pressed: Boolean,
    motionEnabled: Boolean,
) {
    require(activityFraction.isFinite() && activityFraction in 0f..1f) {
        "告警活动比例必须是零至一之间的有限数值"
    }
    val active = rememberEnvironmentMotionAllowed() && motionEnabled
    val image = ImageBitmap.imageResource(resource)
    val artwork = remember { AlarmArtwork() }
    val controller = remember { AlarmMotionController() }
    val press = remember { Animatable(1f) }
    val sources = AlarmSource.entries.map { source ->
        val value = remember(source) { Animatable(0f) }
        LaunchedEffect(source in affectedSources, active) {
            val target = if (source in affectedSources) 1f else 0f
            if (active) value.animateTo(target, tween(300, easing = EnvironmentMotionTokens.EaseOut))
            else value.snapTo(target)
        }
        value
    }
    LaunchedEffect(severity, activityFraction, acknowledged, active) {
        controller.request(severity, activityFraction, acknowledged)
        if (!active) controller.settleWhilePaused()
    }
    LaunchedEffect(pressed, active) {
        if (!active) press.snapTo(1f)
        else if (pressed) press.animateTo(EnvironmentMotionTokens.PressScale,
            tween(100, easing = EnvironmentMotionTokens.EaseOut))
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
    val levelText = when (severity) {
        AlarmSeverity.None -> "无告警"
        AlarmSeverity.Warning -> "预警"
        AlarmSeverity.Critical -> "严重告警"
    }
    val sourceText = affectedSources.sortedBy { it.ordinal }.joinToString("、") {
        when (it) {
            AlarmSource.Temperature -> "温度"
            AlarmSource.Water -> "水分"
            AlarmSource.Equipment -> "设备"
        }
    }
    Canvas(modifier.semantics {
        contentDescription = "报警中心"
        stateDescription = if (severity == AlarmSeverity.None) levelText else
            "$levelText，$sourceText，${if (acknowledged) "已确认，仍待处理" else "未确认"}，活动 ${(activityFraction * 100).roundToInt()}%"
    }) {
        val fit = min(size.width / image.width, size.height / image.height)
        val offset = Offset((size.width - image.width * fit) / 2f, (size.height - image.height * fit) / 2f)
        withTransform({
            translate(offset.x, offset.y)
            scale(fit, fit, Offset.Zero)
            val pressScale = if (active) press.value else 1f
            scale(pressScale, pressScale, Offset(image.width / 2f, image.height / 2f))
        }) {
            drawImage(image)
            artwork.draw(this, controller.phase, controller.attention, controller.signal,
                controller.activity, sources[0].value, sources[1].value, sources[2].value,
                severity, acknowledged, active)
        }
    }
}

/** 从导航速度积分适配：级别控制提醒周期，持续活动更新不会重启相位。 */
private class AlarmMotionController {
    var phase by mutableFloatStateOf(0f)
        private set
    var attention by mutableFloatStateOf(0f)
        private set
    var signal by mutableFloatStateOf(0f)
        private set
    var activity by mutableFloatStateOf(0f)
        private set
    private var speed = 0.0
    private var speedFrom = 0.0
    private var speedTarget = 0.0
    private var speedTime = 0.0
    private var speedDuration = 0.45
    private var attentionFrom = 0f
    private var attentionTarget = 0f
    private var signalFrom = 0f
    private var signalTarget = 0f
    private var activityFrom = 0f
    private var activityTarget = 0f
    private var appearanceTime = 0.0
    private val appearanceDuration = 0.3

    fun request(severity: AlarmSeverity, activityFraction: Float, acknowledged: Boolean) {
        val nextSignal = when (severity) {
            AlarmSeverity.None -> 0f
            AlarmSeverity.Warning -> 0.65f
            AlarmSeverity.Critical -> 1f
        }
        val nextAttention = if (acknowledged) 0f else nextSignal
        val targetSpeed = if (nextAttention == 0f) 0.0
            else 1.0 / if (severity == AlarmSeverity.Critical) 2.8 else 4.8
        if (targetSpeed != speedTarget) {
            speedFrom = speed; speedTarget = targetSpeed; speedTime = 0.0
            speedDuration = if (targetSpeed == 0.0) 0.5 else 0.45
        }
        val nextActivity = if (severity == AlarmSeverity.None) 0f else activityFraction
        if (nextAttention != attentionTarget || nextSignal != signalTarget || nextActivity != activityTarget) {
            attentionFrom = attention; attentionTarget = nextAttention
            signalFrom = signal; signalTarget = nextSignal
            activityFrom = activity; activityTarget = nextActivity
            appearanceTime = 0.0
        }
    }

    fun settleWhilePaused() {
        // 保留停止相位，只结算目标外观，恢复不补算后台时间。
        speed = speedTarget; speedFrom = speedTarget; speedTime = speedDuration
        attention = attentionTarget; attentionFrom = attentionTarget
        signal = signalTarget; signalFrom = signalTarget
        activity = activityTarget; activityFrom = activityTarget
        appearanceTime = appearanceDuration
    }

    fun step(seconds: Double) {
        val oldTime = speedTime
        speedTime = (speedTime + seconds).coerceAtMost(speedDuration)
        val u = speedTime / speedDuration
        speed = speedFrom + (speedTarget - speedFrom) * (u * u * (3 - 2 * u))
        fun distanceAt(time: Double): Double {
            val progress = time / speedDuration
            return speedFrom * time + (speedTarget - speedFrom) * speedDuration *
                (progress * progress * progress - progress * progress * progress * progress / 2)
        }
        val transitionSeconds = speedTime - oldTime
        val distance = if (transitionSeconds > 0.0) distanceAt(speedTime) - distanceAt(oldTime) else 0.0
        phase = ((phase + distance + speed * (seconds - transitionSeconds)) % 1.0).toFloat()
        appearanceTime = (appearanceTime + seconds).coerceAtMost(appearanceDuration)
        val appearance = (appearanceTime / appearanceDuration).toFloat()
        val eased = appearance * appearance * (3f - 2f * appearance)
        attention = attentionFrom + (attentionTarget - attentionFrom) * eased
        signal = signalFrom + (signalTarget - signalFrom) * eased
        activity = activityFrom + (activityTarget - activityFrom) * eased
    }
}
