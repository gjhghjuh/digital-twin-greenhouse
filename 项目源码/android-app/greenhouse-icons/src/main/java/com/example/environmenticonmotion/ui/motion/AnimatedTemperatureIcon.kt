package com.example.environmenticonmotion.ui.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import com.example.environmenticonmotion.motion.rememberEnvironmentMotionAllowed
import com.example.environmenticonmotion.motion.temperatureVisualProfile
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private object TemperatureMotion {
    const val IdlePeriodSeconds = 10f
}

private class TemperaturePhase(
    var radians: Double = 0.0,
    var flow: Double = 0.0,
)

/** 外围辉光比例只调整画布光场的范围与强度，主体素材和数据动效保持原样。 */
@Composable
fun AnimatedTemperatureIcon(
    temperatureProgress: Float,
    modifier: Modifier = Modifier,
    idleEnabled: Boolean = true,
    interactionEnabled: Boolean = true,
    pressed: Boolean = false,
    motionEnabled: Boolean = true,
    glowScale: Float = 1f,
) {
    require(glowScale.isFinite() && glowScale in 0f..1f) { "外围辉光比例必须为零至一的有限数值" }
    require(temperatureProgress.isFinite()) { "温度进度必须是有限数值" }
    val motionAllowed = rememberEnvironmentMotionAllowed() && motionEnabled
    val visualProgress by animateFloatAsState(
        targetValue = temperatureProgress.coerceIn(0f, 1f),
        animationSpec = if (motionAllowed) EnvironmentMotionTokens.DataResponseSpring else snap(),
        label = "Temperature progress",
    )
    val idleEnvelope = remember { Animatable(0f) }
    val phase = remember { TemperaturePhase() }
    var ambientPulse by remember { mutableFloatStateOf(0f) }
    var flowPhase by remember { mutableFloatStateOf(0f) }
    val currentVisualProgress by rememberUpdatedState(visualProgress)
    val press = remember { Animatable(1f) }
    var held by remember { mutableStateOf(false) }
    var accessibilityRequest by remember { mutableIntStateOf(0) }
    var handledAccessibility by remember { mutableIntStateOf(0) }

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
        // Idle 不移动温度计，只让既有暖色光场产生极弱的十秒调制。
        while (isActive) {
            val frame = withFrameNanos { it }
            val durationScale = currentCoroutineContext()[MotionDurationScale]?.scaleFactor ?: 1f
            if (durationScale > 0f) {
                val seconds = ((frame - previousFrame) / 1_000_000_000.0).coerceAtMost(0.1)
                phase.radians = (
                    phase.radians + seconds * 2.0 * PI / (TemperatureMotion.IdlePeriodSeconds * durationScale)
                    ) % (2.0 * PI)
                val flowPeriod = temperatureVisualProfile(currentVisualProgress).flowPeriodSeconds
                phase.flow = (phase.flow + seconds / (flowPeriod * durationScale)) % 1.0
                ambientPulse = ((1.0 - cos(phase.radians)) / 2.0).toFloat()
                flowPhase = phase.flow.toFloat()
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
                onClick(label = "Test temperature press") {
                    accessibilityRequest++
                    true
                }
            }
    } else Modifier

    val profile = temperatureVisualProfile(visualProgress)
    val idleFactor = 0.97f + ambientPulse * 0.03f * idleEnvelope.value
    Box(
        modifier = modifier
            .then(interaction)
            .drawWithCache {
                // 零值明确关闭外围光场，避免创建半径为零的渐变。
                if (glowScale == 0f) return@drawWithCache onDrawBehind {}
                val bulbCenter = Offset(size.width * 0.49f, size.height * 0.73f)
                val bodyCenter = Offset(size.width * 0.49f, size.height * 0.38f)
                val outerWarmth = Brush.radialGradient(
                    colorStops = arrayOf(
                        0f to Color(0xFFFF5A24),
                        0.35f to Color(0xFFFF7A1A),
                        0.72f to Color(0xFFFFB13B),
                        1f to Color.Transparent,
                    ),
                    center = bulbCenter,
                    radius = size.minDimension * 0.40f * glowScale,
                )
                val bodyWarmth = Brush.radialGradient(
                    colorStops = arrayOf(
                        0f to Color(0xFFFF8A22),
                        0.48f to Color(0xFFFFA52F),
                        1f to Color.Transparent,
                    ),
                    center = bodyCenter,
                    radius = size.minDimension * 0.34f * glowScale,
                )
                val warmRing = Brush.radialGradient(
                    colorStops = arrayOf(
                        0f to Color.Transparent,
                        0.43f to Color.Transparent,
                        0.62f to Color(0xFFFF8A22),
                        1f to Color.Transparent,
                    ),
                    center = bulbCenter,
                    radius = size.minDimension * 0.31f * glowScale,
                )
                onDrawBehind {
                    drawCircle(
                        brush = bodyWarmth,
                        radius = size.minDimension * 0.34f * glowScale,
                        center = bodyCenter,
                        alpha = profile.glowAlpha * 0.52f * idleFactor * glowScale,
                    )
                    drawCircle(
                        brush = outerWarmth,
                        radius = size.minDimension * 0.40f * glowScale,
                        center = bulbCenter,
                        alpha = profile.glowAlpha * 0.78f * idleFactor * glowScale,
                    )
                    drawCircle(
                        brush = warmRing,
                        radius = size.minDimension * 0.31f * glowScale,
                        center = bulbCenter,
                        alpha = profile.glowAlpha * idleFactor * glowScale,
                    )
                }
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val corridorTop = size.height * 0.14f
            val corridorHeight = size.height * 0.63f
            val corridorWidth = size.width * 0.095f
            val bandHeight = size.height * 0.29f

            fun drawFlowBand(channelCenterX: Float, phaseOffset: Float) {
                val progress = (flowPhase + phaseOffset) % 1f
                // 光带在首尾处淡出，循环复位时不会出现跳变。
                val edgeFade = sin(PI * progress).toFloat().coerceAtLeast(0f)
                val centerY = size.height * (0.79f - progress * 0.63f)
                val band = Brush.verticalGradient(
                    colorStops = arrayOf(
                        0f to Color.Transparent,
                        0.32f to Color(0xFFFF6A1F),
                        0.52f to Color(0xFFFFC34A),
                        0.72f to Color(0xFFFF8126),
                        1f to Color.Transparent,
                    ),
                    startY = centerY - bandHeight * 0.5f,
                    endY = centerY + bandHeight * 0.5f,
                )
                drawRoundRect(
                    brush = band,
                    topLeft = Offset(channelCenterX - corridorWidth * 0.5f, corridorTop),
                    size = Size(corridorWidth, corridorHeight),
                    cornerRadius = CornerRadius(corridorWidth * 0.5f),
                    alpha = profile.flowAlpha * edgeFade * idleEnvelope.value,
                )
            }

            // 两侧通道与中央液柱保持距离；两组错相光带保证热流连续。
            drawFlowBand(size.width * 0.37f, 0f)
            drawFlowBand(size.width * 0.37f, 0.5f)
            drawFlowBand(size.width * 0.62f, 0.16f)
            drawFlowBand(size.width * 0.62f, 0.66f)
        }
        Image(
            painter = painterResource(R.drawable.env_temperature),
            contentDescription = "Temperature",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val pressWeight = (
                        (1f - press.value) / (1f - EnvironmentMotionTokens.PressScale)
                        ).coerceIn(0f, 1f)
                    val scale = press.value + (profile.iconScale - 1f) * (1f - pressWeight)
                    scaleX = scale
                    scaleY = scale
                    alpha = profile.iconAlpha
                },
        )
    }
}
