package com.example.environmenticonmotion.ui.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.environmenticonmotion.R
import com.example.environmenticonmotion.motion.EnvironmentMotionTokens
import com.example.environmenticonmotion.motion.airHumidityMotionProfile
import com.example.environmenticonmotion.motion.rememberEnvironmentMotionAllowed
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private class AirHumidityPhase(var radians: Double = 0.0)

/** 外围辉光比例只调整画布光场的范围与强度，主体素材和浮动相位保持原样。 */
@Composable
fun AnimatedAirHumidityIcon(
    relativeHumidityPercent: Float,
    modifier: Modifier = Modifier,
    idleEnabled: Boolean = true,
    interactionEnabled: Boolean = true,
    pressed: Boolean = false,
    motionEnabled: Boolean = true,
    glowScale: Float = 1f,
) {
    require(glowScale.isFinite() && glowScale in 0f..1f) { "外围辉光比例必须为零至一的有限数值" }
    val motionAllowed = rememberEnvironmentMotionAllowed() && motionEnabled
    val density = LocalDensity.current
    val targetProfile = airHumidityMotionProfile(relativeHumidityPercent)
    val period = remember { Animatable(targetProfile.periodSeconds) }
    val verticalDrift = remember { Animatable(targetProfile.verticalDriftDp) }
    val horizontalDrift = remember { Animatable(targetProfile.horizontalDriftDp) }
    val glowAlpha = remember { Animatable(targetProfile.glowAlpha) }
    val idleEnvelope = remember { Animatable(0f) }
    val phase = remember { AirHumidityPhase() }
    var wave by remember { mutableFloatStateOf(0f) }
    var ambientPulse by remember { mutableFloatStateOf(0f) }
    val press = remember { Animatable(1f) }
    var held by remember { mutableStateOf(false) }
    var accessibilityRequest by remember { mutableIntStateOf(0) }
    var handledAccessibility by remember { mutableIntStateOf(0) }

    LaunchedEffect(targetProfile, motionAllowed) {
        if (motionAllowed) {
            coroutineScope {
                launch { period.animateTo(targetProfile.periodSeconds, tween(800, easing = EnvironmentMotionTokens.EaseInOut)) }
                launch { verticalDrift.animateTo(targetProfile.verticalDriftDp, tween(800, easing = EnvironmentMotionTokens.EaseInOut)) }
                launch { horizontalDrift.animateTo(targetProfile.horizontalDriftDp, tween(800, easing = EnvironmentMotionTokens.EaseInOut)) }
                launch { glowAlpha.animateTo(targetProfile.glowAlpha, tween(800, easing = EnvironmentMotionTokens.EaseInOut)) }
            }
        } else {
            period.snapTo(targetProfile.periodSeconds)
            verticalDrift.snapTo(targetProfile.verticalDriftDp)
            horizontalDrift.snapTo(targetProfile.horizontalDriftDp)
            glowAlpha.snapTo(targetProfile.glowAlpha)
        }
    }

    LaunchedEffect(idleEnabled, motionAllowed) {
        if (!motionAllowed) idleEnvelope.snapTo(0f)
        else idleEnvelope.animateTo(
            if (idleEnabled) 1f else 0f,
            tween(240, easing = EnvironmentMotionTokens.EaseOut),
        )
    }

    LaunchedEffect(idleEnabled, motionAllowed) {
        if (!idleEnabled || !motionAllowed) return@LaunchedEffect
        var previousFrame = withFrameNanos { it }
        // 连续相位形成缓慢的 Float；湿度变化只改变速度和幅度，不重启周期。
        while (isActive) {
            val frame = withFrameNanos { it }
            val durationScale = currentCoroutineContext()[MotionDurationScale]?.scaleFactor ?: 1f
            if (durationScale > 0f) {
                val seconds = ((frame - previousFrame) / 1_000_000_000.0).coerceAtMost(0.1)
                phase.radians = (phase.radians + seconds * 2.0 * PI / (period.value * durationScale)) % (2.0 * PI)
                wave = sin(phase.radians).toFloat()
                ambientPulse = ((1.0 - cos(phase.radians)) / 2.0).toFloat()
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
                onClick(label = "Test air humidity press") {
                    accessibilityRequest++
                    true
                }
            }
    } else Modifier

    Box(
        modifier = modifier
            .then(interaction)
            .drawWithCache {
                // 零值明确关闭外围光场，避免创建半径为零的渐变。
                if (glowScale == 0f) return@drawWithCache onDrawBehind {}
                val field = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF53E4DC),
                        Color(0xFF36AFFF),
                        Color.Transparent,
                    ),
                    center = Offset(size.width * 0.52f, size.height * 0.5f),
                    radius = size.minDimension * 0.52f * glowScale,
                )
                onDrawBehind {
                    if (motionAllowed) {
                        drawCircle(
                            brush = field,
                            radius = size.minDimension * 0.52f * glowScale,
                            center = Offset(size.width * 0.52f, size.height * 0.5f),
                            alpha = glowAlpha.value * (0.7f + 0.3f * ambientPulse) * idleEnvelope.value * glowScale,
                        )
                    }
                }
            },
    ) {
        Image(
            painter = painterResource(R.drawable.env_air_humidity),
            contentDescription = "Air Humidity",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val motionWeight = idleEnvelope.value * (1f - ((1f - press.value) / (1f - EnvironmentMotionTokens.PressScale)).coerceIn(0f, 1f))
                    translationY = with(density) { (-wave * verticalDrift.value * motionWeight).dp.toPx() }
                    // X 使用不同相位与更小幅度，避免形成机械的斜线往返。
                    translationX = with(density) {
                        (sin(phase.radians * 0.5 + PI / 4.0).toFloat() * horizontalDrift.value * motionWeight).dp.toPx()
                    }
                    scaleX = press.value
                    scaleY = press.value
                },
        )
    }
}
