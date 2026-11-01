package com.example.environmenticonmotion.ui.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import com.example.environmenticonmotion.R
import com.example.environmenticonmotion.motion.EnvironmentMotionTokens
import com.example.environmenticonmotion.motion.lightBreathingPeriodSeconds
import com.example.environmenticonmotion.motion.rememberEnvironmentMotionAllowed
import kotlinx.coroutines.isActive
import kotlinx.coroutines.currentCoroutineContext
import kotlin.math.cos
import kotlin.math.PI

private object LightMotion {
    const val IdlePeak = 1.05f
}

private class LightPhase(var radians: Double = 0.0)

/** 外围辉光比例只调整画布光场的范围与强度，主体素材和档位呼吸保持原样。 */
@Composable
fun AnimatedLightIcon(
    lightLevel: Int,
    modifier: Modifier = Modifier,
    idleEnabled: Boolean = true,
    interactionEnabled: Boolean = true,
    pressed: Boolean = false,
    motionEnabled: Boolean = true,
    glowScale: Float = 1f,
) {
    require(glowScale.isFinite() && glowScale in 0f..1f) { "外围辉光比例必须为零至一的有限数值" }
    val motionAllowed = rememberEnvironmentMotionAllowed() && motionEnabled
    val period = remember { Animatable(lightBreathingPeriodSeconds(lightLevel)) }
    val idleEnvelope = remember { Animatable(0f) }
    val phase = remember { LightPhase() }
    var breath by remember { mutableFloatStateOf(0f) }
    val press = remember { Animatable(1f) }
    var held by remember { mutableStateOf(false) }
    var accessibilityRequest by remember { mutableIntStateOf(0) }
    var handledAccessibility by remember { mutableIntStateOf(0) }

    val targetPeriod = lightBreathingPeriodSeconds(lightLevel)
    LaunchedEffect(targetPeriod, motionAllowed) {
        if (motionAllowed) period.animateTo(targetPeriod, tween(800, easing = EnvironmentMotionTokens.EaseInOut))
        else period.snapTo(targetPeriod)
    }

    LaunchedEffect(idleEnabled, motionAllowed) {
        if (!motionAllowed) {
            idleEnvelope.snapTo(0f)
        } else {
            idleEnvelope.animateTo(if (idleEnabled) 1f else 0f, tween(240, easing = EnvironmentMotionTokens.EaseOut))
        }
    }

    LaunchedEffect(idleEnabled, motionAllowed) {
        if (!idleEnabled || !motionAllowed) return@LaunchedEffect
        var previousFrame = withFrameNanos { it }
        // 累加连续相位，改档位只改速度，不重新开始呼吸周期。
        while (isActive) {
            val frame = withFrameNanos { it }
            val durationScale = currentCoroutineContext()[MotionDurationScale]?.scaleFactor ?: 1f
            if (durationScale > 0f) {
                val seconds = ((frame - previousFrame) / 1_000_000_000.0).coerceAtMost(0.1)
                phase.radians = (phase.radians + seconds * 2.0 * PI / (period.value * durationScale)) % (2.0 * PI)
                breath = ((1.0 - cos(phase.radians)) / 2.0).toFloat()
            }
            previousFrame = frame
        }
    }

    LaunchedEffect(held, pressed, accessibilityRequest, interactionEnabled, motionAllowed) {
        val testRequested = accessibilityRequest != handledAccessibility
        handledAccessibility = accessibilityRequest
        if (!motionAllowed || !interactionEnabled) {
            press.snapTo(1f)
        } else if (held || pressed) {
            press.animateTo(EnvironmentMotionTokens.PressScale, tween(100, easing = EnvironmentMotionTokens.EaseOut))
        } else {
            if (testRequested) {
                press.animateTo(EnvironmentMotionTokens.PressScale, tween(100, easing = EnvironmentMotionTokens.EaseOut))
            }
            // 临界阻尼保留自然回弹感，但不越过目标值。
            press.animateTo(1f, EnvironmentMotionTokens.PressReturnSpring)
        }
    }

    val interaction = if (interactionEnabled && motionAllowed) {
        Modifier
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    held = true
                    try {
                        tryAwaitRelease()
                    } finally {
                        held = false
                    }
                })
            }
            .semantics {
                role = Role.Button
                onClick(label = "Test light press") {
                    accessibilityRequest++
                    true
                }
            }
    } else Modifier

    Box(modifier = modifier.then(interaction).drawWithCache {
        // 零值明确关闭外围光场，避免创建半径为零的渐变。
        if (glowScale == 0f) return@drawWithCache onDrawBehind {}
        // 外围光晕由画布绘制，中心透明，不复制或修改图片。
        // 光场略超出太阳主体，缩小图标后仍能看清外围光晕。
        val radius = size.minDimension * 0.62f * glowScale
        val glow = Brush.radialGradient(
            0f to Color.Transparent,
            0.38f to Color.Transparent,
            0.72f to Color(0xFFFFBD45),
            1f to Color.Transparent,
            center = Offset(size.width / 2f, size.height / 2f),
            radius = radius,
        )
        onDrawBehind {
            if (motionAllowed) {
                drawCircle(glow, radius, alpha = (0.08f + 0.12f * breath) * idleEnvelope.value * glowScale)
            }
        }
    }) {
      Image(
        painter = painterResource(R.drawable.env_light),
        contentDescription = "Light",
        contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize()
            .graphicsLayer {
                // 按压权重将呼吸平滑压回基线，避免两种缩放累乘。
                val pressWeight = ((1f - press.value) / (1f - EnvironmentMotionTokens.PressScale)).coerceIn(0f, 1f)
                val scale = if (motionAllowed) press.value + (LightMotion.IdlePeak - 1f) * breath * idleEnvelope.value * (1f - pressWeight) else 1f
                scaleX = scale
                scaleY = scale
            },
    )
    }
}
