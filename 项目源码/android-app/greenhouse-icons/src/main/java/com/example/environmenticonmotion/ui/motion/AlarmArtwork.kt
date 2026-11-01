package com.example.environmenticonmotion.ui.motion

import android.graphics.Path as NativePath
import android.graphics.PathMeasure
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.math.PI
import kotlin.math.sin

/** 从导航光路复制适配：警灯、卡片、通路和警示牌各自承担不同节拍，实体保持固定。 */
internal class AlarmArtwork {
    private val warning = Color(0xFFFFCB70)
    private val critical = Color(0xFFFF8070)
    private val cyan = Color(0xFF8AF5E8)
    private val dome = Path().apply {
        moveTo(559f, 370f); lineTo(577f, 241f)
        cubicTo(585f, 184f, 634f, 164f, 700f, 164f)
        cubicTo(777f, 164f, 812f, 190f, 821f, 249f)
        lineTo(841f, 376f); cubicTo(800f, 403f, 597f, 403f, 559f, 370f); close()
    }
    private val sweep = Brush.horizontalGradient(
        listOf(Color.Transparent, Color(0x88FFF6D2), Color.Transparent), 0f, 95f,
    )
    private val beaconGlow = Brush.radialGradient(listOf(
        Color(0x55FFE1A5), Color.Transparent), Offset(701f, 295f), 160f)
    private val emblemGlow = Brush.radialGradient(listOf(
        Color(0x66FFD68D), Color.Transparent), Offset(700f, 845f), 118f)
    private val temperature = Path().apply {
        moveTo(287f, 509f); lineTo(287f, 451f)
        cubicTo(287f, 421f, 323f, 421f, 323f, 451f)
        lineTo(323f, 508f); cubicTo(359f, 543f, 333f, 572f, 305f, 572f)
        cubicTo(273f, 572f, 255f, 540f, 287f, 509f); close()
    }
    private val water = Path().apply {
        moveTo(1038f, 371f)
        cubicTo(1012f, 400f, 992f, 427f, 1000f, 450f)
        cubicTo(1010f, 485f, 1066f, 486f, 1077f, 450f)
        cubicTo(1086f, 427f, 1066f, 399f, 1038f, 371f); close()
    }
    private val equipment = Path().apply { addOval(Rect(1054f, 578f, 1159f, 693f)) }
    private val emblem = AlarmLightRoute(NativePath().apply {
        moveTo(570f, 918f); lineTo(675f, 746f)
        quadTo(700f, 719f, 725f, 746f); lineTo(830f, 918f)
        quadTo(843f, 941f, 817f, 941f); lineTo(583f, 941f)
        quadTo(557f, 941f, 570f, 918f); close()
    }, closed = true)
    private val routes = listOf(
        // 原图前景警示牌、卡片和固定球遮住的通路不画光包，避免穿透实体。
        AlarmLightRoute(NativePath().apply {
            moveTo(463f, 444f); cubicTo(479f, 431f, 505f, 424f, 520f, 420f)
        }),
        AlarmLightRoute(NativePath().apply {
            moveTo(878f, 388f); lineTo(955f, 403f)
        }),
        AlarmLightRoute(NativePath().apply {
            moveTo(214f, 704f); cubicTo(272f, 792f, 438f, 837f, 568f, 848f)
        }),
        AlarmLightRoute(NativePath().apply {
            moveTo(857f, 849f); cubicTo(915f, 841f, 967f, 827f, 1004f, 813f)
        }),
        AlarmLightRoute(NativePath().apply {
            moveTo(1085f, 781f); cubicTo(1140f, 761f, 1164f, 742f, 1176f, 725f)
        }),
    )
    private val rayStarts = arrayOf(Offset(471f, 213f), Offset(454f, 303f),
        Offset(871f, 252f), Offset(888f, 309f))
    private val rayEnds = arrayOf(Offset(532f, 254f), Offset(516f, 310f),
        Offset(931f, 214f), Offset(948f, 302f))

    fun draw(scope: DrawScope, phase: Float, attention: Float, signal: Float, activity: Float,
        temperatureLevel: Float, waterLevel: Float, equipmentLevel: Float,
        severity: AlarmSeverity, acknowledged: Boolean, motionActive: Boolean) = with(scope) {
        val color = if (severity == AlarmSeverity.Critical) critical else warning
        // 每轮先来源采集、再通路传递、最后警示回应，最高级别增加第二次局部呼应。
        val beacon = beat(phase, 0.63f, 0.16f) +
            if (severity == AlarmSeverity.Critical) beat(phase, 0.86f, 0.10f) * 0.75f else 0f
        val movement = if (motionActive) attention else 0f
        val response = beacon.coerceAtMost(1f) * movement
        if (signal > 0.001f) {
            clipPath(dome) {
                drawCircle(beaconGlow, 160f, Offset(701f, 295f), alpha = signal * 0.25f + response * 0.7f)
                if (movement > 0.001f) withTransform({ translate(508f + phase * 380f, 0f) }) {
                    drawRect(sweep, Offset(0f, 169f), Size(95f, 228f),
                        alpha = sin(PI * phase).toFloat().let { it * it } * movement * 0.7f)
                }
            }
            for (index in rayStarts.indices) {
                drawLine(color, rayStarts[index], rayEnds[index], strokeWidth = 13f,
                    cap = StrokeCap.Round, alpha = signal * 0.08f + response * 0.6f)
            }
            drawPath(temperature, color, alpha = temperatureLevel * signal *
                (0.22f + movement * beat(phase, 0.14f, 0.14f) * 0.75f), style = Stroke(6f))
            drawPath(water, color, alpha = waterLevel * signal *
                (0.22f + movement * beat(phase, 0.23f, 0.14f) * 0.75f), style = Stroke(6f))
            drawPath(equipment, color, alpha = equipmentLevel * signal *
                (0.22f + movement * beat(phase, 0.32f, 0.14f) * 0.75f), style = Stroke(6f))
            // 分类卡片状态条稳态常亮；确认后仍能识别异常来源，不伪装恢复正常。
            drawLine(color, Offset(366f, 438f), Offset(427f, 421f), strokeWidth = 8f,
                cap = StrokeCap.Round, alpha = temperatureLevel * signal * 0.7f)
            drawLine(color, Offset(1108f, 392f), Offset(1159f, 398f), strokeWidth = 8f,
                cap = StrokeCap.Round, alpha = waterLevel * signal * 0.7f)
            drawLine(color, Offset(1180f, 620f), Offset(1219f, 633f), strokeWidth = 8f,
                cap = StrokeCap.Round, alpha = equipmentLevel * signal * 0.7f)
            drawCircle(emblemGlow, 118f, Offset(700f, 845f), alpha = response * 0.45f)
            emblem.outline(this, color, 0.18f * signal + 0.55f * response, 6f)
            drawLine(Color(0xFFFFF4C0), Offset(699f, 790f), Offset(699f, 851f),
                strokeWidth = 20f, cap = StrokeCap.Round, alpha = response * 0.55f)
            if (movement * activity > 0.001f) {
                val transport = (phase + 0.22f) % 1f
                for (index in routes.indices) {
                    val origin = when (index) {
                        0, 2 -> temperatureLevel
                        1, 3 -> waterLevel
                        else -> equipmentLevel
                    }
                    routes[index].draw(this, transport, 0.14f, color,
                        movement * activity * origin, 7f)
                    if (activity > 0.5f) routes[index].draw(this, (transport + 0.12f) % 1f,
                        0.08f, color, movement * ((activity - 0.5f) * 2f) * origin, 5f)
                }
            }
        }
        if (severity == AlarmSeverity.None || acknowledged) {
            // 静态勾表示正常或已知晓，具体含义同时由组件无障碍状态与页面文案说明。
            val center = Offset(1180f, 170f)
            val badgeColor = if (severity == AlarmSeverity.None) cyan else warning
            drawCircle(Color(0xFF153631), 43f, center)
            drawCircle(badgeColor, 41f, center, style = Stroke(4f))
            drawLine(badgeColor, center + Offset(-18f, 1f), center + Offset(-5f, 15f),
                strokeWidth = 6f, cap = StrokeCap.Round)
            drawLine(badgeColor, center + Offset(-5f, 15f), center + Offset(20f, -15f),
                strokeWidth = 6f, cap = StrokeCap.Round)
        }
    }

    private fun beat(phase: Float, center: Float, halfWidth: Float): Float {
        val distance = kotlin.math.abs(phase - center)
        if (distance >= halfWidth) return 0f
        val value = sin(PI * (1f - distance / halfWidth) / 2).toFloat()
        return value * value
    }
}

/** 复用已验证的缓存路径片段，开放通路在两端渐隐。 */
private class AlarmLightRoute(path: NativePath, private val closed: Boolean = false) {
    private val fullPath = path.asComposePath()
    private val measure = PathMeasure(path, closed)
    private val segment = NativePath()
    private val composeSegment = segment.asComposePath()
    private val position = FloatArray(2)

    fun outline(scope: DrawScope, color: Color, alpha: Float, width: Float) {
        scope.drawPath(fullPath, color, alpha = alpha, style = Stroke(width, cap = StrokeCap.Round))
    }

    fun draw(scope: DrawScope, progress: Float, tail: Float, color: Color, strength: Float, width: Float) {
        val head = progress * measure.length
        val start = head - tail * measure.length
        segment.rewind()
        if (closed && start < 0f) measure.getSegment(measure.length + start, measure.length, segment, true)
        measure.getSegment(start.coerceAtLeast(0f), head, segment, true)
        val edge = if (closed) 1f else sin(PI * progress).toFloat().let { it * it }
        val alpha = strength * edge
        with(scope) {
            drawPath(composeSegment, color, alpha = alpha * 0.1f, style = Stroke(width * 4f, cap = StrokeCap.Round))
            drawPath(composeSegment, color, alpha = alpha * 0.4f, style = Stroke(width * 2f, cap = StrokeCap.Round))
            drawPath(composeSegment, color, alpha = alpha, style = Stroke(width, cap = StrokeCap.Round))
            measure.getPosTan(head, position, null)
            drawCircle(Color(0xFFFFF6CD), width * 0.8f, Offset(position[0], position[1]), alpha = alpha)
        }
    }
}
