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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.example.environmenticonmotion.R
import com.example.environmenticonmotion.motion.EnvironmentMotionTokens
import com.example.environmenticonmotion.motion.rememberEnvironmentMotionAllowed
import com.example.environmenticonmotion.motion.soilMoistureVisualProfile
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt

private object SoilMoistureMotion {
    const val IdlePeriodSeconds = 8f
}

private class SoilMoisturePhase(var radians: Double = 0.0)

/** 外围辉光比例只调整画布光场的范围与强度，主体素材和水滴映射保持原样。 */
@Composable
fun AnimatedSoilMoistureIcon(
    moistureLevel: Float,
    modifier: Modifier = Modifier,
    idleEnabled: Boolean = true,
    interactionEnabled: Boolean = true,
    pressed: Boolean = false,
    motionEnabled: Boolean = true,
    glowScale: Float = 1f,
) {
    require(glowScale.isFinite() && glowScale in 0f..1f) { "外围辉光比例必须为零至一的有限数值" }
    require(moistureLevel.isFinite()) { "土壤湿度必须是有限数值" }
    val motionAllowed = rememberEnvironmentMotionAllowed() && motionEnabled
    val context = LocalContext.current
    val sourceImage = remember(context.resources) {
        ImageBitmap.imageResource(context.resources, R.drawable.env_soil_moisture)
    }
    val targetLevel = moistureLevel.coerceIn(0f, 1f)
    val visualLevel by animateFloatAsState(
        targetValue = targetLevel,
        animationSpec = if (motionAllowed) EnvironmentMotionTokens.DataResponseSpring else snap(),
        label = "Soil moisture level",
    )
    val idleEnvelope = remember { Animatable(0f) }
    val phase = remember { SoilMoisturePhase() }
    var ambientPulse by remember { mutableFloatStateOf(0f) }
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
        // Idle 只让水滴附近的环境光缓慢呼吸，植物与土壤主体保持静止。
        while (isActive) {
            val frame = withFrameNanos { it }
            val durationScale = currentCoroutineContext()[MotionDurationScale]?.scaleFactor ?: 1f
            if (durationScale > 0f) {
                val seconds = ((frame - previousFrame) / 1_000_000_000.0).coerceAtMost(0.1)
                phase.radians = (
                    phase.radians + seconds * 2.0 * PI / (SoilMoistureMotion.IdlePeriodSeconds * durationScale)
                    ) % (2.0 * PI)
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
                onClick(label = "Test soil moisture press") {
                    accessibilityRequest++
                    true
                }
            }
    } else Modifier

    val profile = soilMoistureVisualProfile(visualLevel)
    Box(
        modifier = modifier
            .then(interaction)
            .drawWithCache {
                // 零值明确关闭外围光场，避免创建半径为零的渐变。
                if (glowScale == 0f) return@drawWithCache onDrawBehind {}
                val center = Offset(size.width * 0.4975f, size.height * 0.75f)
                val field = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF63E9FF),
                        Color(0xFF228DFF),
                        Color.Transparent,
                    ),
                    center = center,
                    radius = size.minDimension * 0.23f * glowScale,
                )
                onDrawBehind {
                    drawCircle(
                        brush = field,
                        radius = size.minDimension * 0.23f * glowScale,
                        center = center,
                        alpha = profile.glowAlpha * (0.84f + ambientPulse * 0.16f * idleEnvelope.value) * glowScale,
                    )
                }
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = press.value
                    scaleY = press.value
                },
        ) {
            Image(
                painter = painterResource(R.drawable.env_soil_moisture),
                contentDescription = "Soil Moisture",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cropLeft = size.width * 0.395f
                val cropTop = size.height * 0.625f
                val cropWidth = size.width * 0.205f
                val cropHeight = size.height * 0.235f
                val cropCenterX = cropLeft + cropWidth * 0.5f
                val cropBottom = cropTop + cropHeight

                fun dropletPath(left: Float, top: Float, width: Float, height: Float) = Path().apply {
                    moveTo(left + width * 0.5f, top + height * 0.05f)
                    cubicTo(
                        left + width * 0.43f,
                        top + height * 0.18f,
                        left + width * 0.08f,
                        top + height * 0.55f,
                        left + width * 0.07f,
                        top + height * 0.73f,
                    )
                    cubicTo(
                        left + width * 0.04f,
                        top + height * 0.89f,
                        left + width * 0.21f,
                        top + height * 0.96f,
                        left + width * 0.5f,
                        top + height * 0.97f,
                    )
                    cubicTo(
                        left + width * 0.79f,
                        top + height * 0.96f,
                        left + width * 0.96f,
                        top + height * 0.89f,
                        left + width * 0.93f,
                        top + height * 0.73f,
                    )
                    cubicTo(
                        left + width * 0.91f,
                        top + height * 0.55f,
                        left + width * 0.57f,
                        top + height * 0.18f,
                        left + width * 0.5f,
                        top + height * 0.05f,
                    )
                    close()
                }

                val originalDroplet = dropletPath(cropLeft, cropTop, cropWidth, cropHeight)
                drawPath(
                    path = originalDroplet,
                    brush = Brush.verticalGradient(
                        colors = listOf(Color(0xFF6A321E), Color(0xFF291817)),
                        startY = cropTop,
                        endY = cropBottom,
                    ),
                    alpha = 0.97f,
                )

                val scaledWidth = cropWidth * profile.dropletScale
                val scaledHeight = cropHeight * profile.dropletScale
                val destinationLeft = cropCenterX - scaledWidth * 0.5f
                val destinationTop = cropBottom - scaledHeight
                val scaledDroplet = dropletPath(destinationLeft, destinationTop, scaledWidth, scaledHeight)
                clipPath(scaledDroplet) {
                    drawImage(
                        image = sourceImage,
                        srcOffset = IntOffset(
                            (sourceImage.width * 0.395f).roundToInt(),
                            (sourceImage.height * 0.625f).roundToInt(),
                        ),
                        srcSize = IntSize(
                            (sourceImage.width * 0.205f).roundToInt(),
                            (sourceImage.height * 0.235f).roundToInt(),
                        ),
                        dstOffset = IntOffset(destinationLeft.roundToInt(), destinationTop.roundToInt()),
                        dstSize = IntSize(scaledWidth.roundToInt(), scaledHeight.roundToInt()),
                        alpha = profile.dropletAlpha,
                    )
                }
            }
        }
    }
}
