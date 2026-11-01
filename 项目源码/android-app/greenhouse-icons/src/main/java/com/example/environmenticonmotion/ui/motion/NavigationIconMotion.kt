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

internal enum class NavigationArtworkKind { Home, DigitalTwin, DataCenter }
internal enum class NavigationMotionState { Idle, Active, Fault }

@Composable
internal fun NavigationIconMotion(
    @DrawableRes resource: Int,
    kind: NavigationArtworkKind,
    state: NavigationMotionState,
    activityFraction: Float,
    isEntered: Boolean,
    label: String,
    description: String,
    modifier: Modifier,
    pressed: Boolean,
    motionEnabled: Boolean,
) {
    require(activityFraction.isFinite() && activityFraction in 0f..1f) {
        "业务活动比例必须是零至一之间的有限数值"
    }
    val motionAllowed = rememberEnvironmentMotionAllowed()
    val active = motionAllowed && motionEnabled
    val image = ImageBitmap.imageResource(resource)
    val artwork = remember(kind) { NavigationArtwork(kind) }
    val controller = remember { NavigationMotionController() }
    val press = remember { Animatable(1f) }
    val entry = remember { Animatable(0f) }
    val homeLayers = remember(kind) { if (kind == NavigationArtworkKind.Home) HomeDoorLayers() else null }
    val ringLayers = remember(kind) { if (kind == NavigationArtworkKind.DataCenter) DataCenterRingLayers() else null }

    LaunchedEffect(state, activityFraction, isEntered, active) {
        controller.request(state, activityFraction, kind, isEntered)
        if (!active) controller.settleWhilePaused()
    }
    LaunchedEffect(isEntered, active) {
        // 进入及返回从当前值重定向，连续切换不排队；暂停直接显示目标状态。
        val target = if (isEntered) 1f else 0f
        if (!active) entry.snapTo(target)
        else entry.animateTo(target, tween(if (isEntered) 720 else 560,
            easing = EnvironmentMotionTokens.EaseOut))
    }
    LaunchedEffect(pressed, active) {
        if (!active) press.snapTo(1f)
        else if (pressed) press.animateTo(
            EnvironmentMotionTokens.PressScale,
            tween(100, easing = EnvironmentMotionTokens.EaseOut),
        ) else press.animateTo(1f, EnvironmentMotionTokens.PressReturnSpring)
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

    Canvas(modifier = modifier.semantics {
        contentDescription = label
        stateDescription = description
    }) {
        // 光路与原图共用真实尺寸和居中适配变换，非等比容器与按压均不会错位。
        val fit = min(size.width / image.width, size.height / image.height)
        val offset = Offset((size.width - image.width * fit) / 2, (size.height - image.height * fit) / 2)
        withTransform({
            translate(offset.x, offset.y)
            scale(fit, fit, Offset.Zero)
            val pressScale = if (active) press.value else 1f
            scale(pressScale, pressScale, Offset(image.width / 2f, image.height / 2f))
        }) {
            when (kind) {
                NavigationArtworkKind.Home -> checkNotNull(homeLayers).draw(this, image, entry.value)
                NavigationArtworkKind.DataCenter -> checkNotNull(ringLayers).draw(this, image,
                    controller.phase, entry.value)
                NavigationArtworkKind.DigitalTwin -> drawImage(image)
            }
            artwork.draw(this, controller.phase, controller.strength, controller.level,
                state == NavigationMotionState.Fault)
        }
    }
}

/** 从风扇控制器复制并适配：沿用当前速度重定向及解析积分，不随数据更新重启相位。 */
private class NavigationMotionController {
    var phase by mutableFloatStateOf(0f)
        private set
    var strength by mutableFloatStateOf(0f)
        private set
    var level by mutableFloatStateOf(0f)
        private set
    private var state = NavigationMotionState.Idle
    private var speed = 0.0
    private var speedFrom = 0.0
    private var speedTarget = 0.0
    private var speedTime = 0.0
    private var speedDuration = 0.45
    private var strengthFrom = 0f
    private var strengthTarget = 0f
    private var levelFrom = 0f
    private var levelTarget = 0f
    private var appearanceTime = 0.0
    private val appearanceDuration = 0.3

    fun request(next: NavigationMotionState, activity: Float, kind: NavigationArtworkKind, isEntered: Boolean) {
        // 圆环表达页面进入，零负载在线时仍缓慢旋转；同步流光仍由真实活动量决定。
        val running = next == NavigationMotionState.Active && isEntered &&
            (activity > 0f || kind == NavigationArtworkKind.DataCenter)
        // 周期为完整一圈：屋檐九至四秒、同步六至二秒、数据环八至三秒。
        val period = when (kind) {
            NavigationArtworkKind.Home -> 9.0 - 5.0 * activity
            NavigationArtworkKind.DigitalTwin -> 6.0 - 4.0 * activity
            NavigationArtworkKind.DataCenter -> 8.0 - 5.0 * activity
        }
        val target = if (running) 1.0 / period else 0.0
        val nextStrength = if (running) 0.35f + 0.65f * activity else 0f
        if (next == NavigationMotionState.Fault) {
            speed = 0.0
            speedFrom = 0.0
            speedTarget = 0.0
            speedTime = speedDuration
            strength = 0f
            strengthFrom = 0f
            strengthTarget = 0f
            level = activity
            levelFrom = activity
            levelTarget = activity
        } else {
            if (target != speedTarget || state == NavigationMotionState.Fault) {
                speedFrom = speed
                speedTarget = target
                speedTime = 0.0
                speedDuration = if (target == 0.0) 0.6 else 0.45
            }
            if (nextStrength != strengthTarget || activity != levelTarget) {
                strengthFrom = strength
                strengthTarget = nextStrength
                levelFrom = level
                levelTarget = activity
                appearanceTime = 0.0
            }
        }
        state = next
    }

    fun settleWhilePaused() {
        // 暂停保留当前相位，直接呈现目标强度；恢复不补算后台时间。
        speed = speedTarget
        speedFrom = speedTarget
        speedTime = speedDuration
        strength = strengthTarget
        strengthFrom = strengthTarget
        level = levelTarget
        levelFrom = levelTarget
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
        if (state != NavigationMotionState.Fault) {
            appearanceTime = (appearanceTime + seconds).coerceAtMost(appearanceDuration)
            val appearance = (appearanceTime / appearanceDuration).toFloat()
            val eased = appearance * appearance * (3 - 2 * appearance)
            strength = strengthFrom + (strengthTarget - strengthFrom) * eased
            level = levelFrom + (levelTarget - levelFrom) * eased
        }
    }
}
