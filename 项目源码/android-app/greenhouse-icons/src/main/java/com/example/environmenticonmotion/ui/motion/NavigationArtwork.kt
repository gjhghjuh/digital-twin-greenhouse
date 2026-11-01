package com.example.environmenticonmotion.ui.motion

import android.graphics.Path as NativePath
import android.graphics.PathMeasure
import androidx.compose.ui.geometry.Offset
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

/** 光效沿原图坐标绘制，门和圆环的局部运动由独立绘制层承担。 */
internal class NavigationArtwork(private val kind: NavigationArtworkKind) {
    private val cyan = Color(0xFF69F4FF)
    private val green = Color(0xFFB2FF6C)
    private val gold = Color(0xFFFFE38C)
    private val twinScanClip = Path().apply {
        moveTo(710f, 399f); lineTo(857f, 338f); lineTo(1075f, 502f)
        lineTo(1075f, 687f); lineTo(960f, 735f); lineTo(851f, 687f)
        lineTo(838f, 549f); close()
    }
    private val scanLight = Brush.verticalGradient(
        listOf(Color.Transparent, Color(0x8869F4FF), Color.Transparent), 0f, 70f,
    )
    private val paths = when (kind) {
        NavigationArtworkKind.Home -> listOf(
            LightRoute(NativePath().apply {
                moveTo(268f, 501f)
                lineTo(660f, 205f)
                quadTo(700f, 174f, 742f, 205f)
                lineTo(1136f, 501f)
            }),
            LightRoute(NativePath().apply {
                addRoundRect(554f, 562f, 849f, 845f, 48f, 48f, NativePath.Direction.CW)
            }, closed = true),
        )
        NavigationArtworkKind.DigitalTwin -> listOf(
            LightRoute(NativePath().apply {
                moveTo(459f, 239f)
                cubicTo(595f, 132f, 769f, 127f, 905f, 186f)
            }),
            LightRoute(NativePath().apply {
                moveTo(1158f, 657f)
                cubicTo(1233f, 861f, 854f, 1042f, 427f, 868f)
            }),
            LightRoute(NativePath().apply {
                moveTo(852f, 841f)
                lineTo(852f, 495f)
            }),
            LightRoute(NativePath().apply {
                moveTo(749f, 258f)
                lineTo(749f, 408f)
            }),
        )
        NavigationArtworkKind.DataCenter -> emptyList()
    }

    fun draw(scope: DrawScope, phase: Float, strength: Float, level: Float, fault: Boolean) {
        if (strength > 0.001f) {
            when (kind) {
                NavigationArtworkKind.Home -> with(scope) {
                    paths[0].draw(this, phase, 0.085f, cyan, strength, 8f)
                    paths[1].draw(this, (phase + 0.48f) % 1f, 0.11f, gold, strength * 0.8f, 6f)
                    // 门灯仅叠加柔和呼吸光，不改变门体或植物尺寸。
                    val pulse = 0.65f + 0.35f * sin(phase * 2f * PI).toFloat()
                    drawLine(gold, Offset(582f, 454f), Offset(821f, 454f),
                        strokeWidth = 12f, cap = StrokeCap.Round, alpha = strength * pulse * 0.35f)
                }
                NavigationArtworkKind.DigitalTwin -> with(scope) {
                    // 上行采集与下行回传错开半圈，活动高时增加同批数据包。
                    for (packet in 0..2) {
                        val packetStrength = strength * when (packet) {
                            0 -> 1f
                            1 -> level * 0.75f
                            else -> level * level * 0.6f
                        }
                        paths[0].draw(this, (phase + packet * 0.075f) % 1f,
                            0.055f, green, packetStrength, 10f)
                        paths[1].draw(this, (phase + 0.5f + packet * 0.07f) % 1f,
                            0.055f, cyan, packetStrength, 10f)
                    }
                    paths[2].draw(this, (phase + 0.2f) % 1f, 0.16f, gold, strength, 6f)
                    paths[3].draw(this, (phase + 0.7f) % 1f, 0.22f, cyan, strength, 6f)
                    clipPath(twinScanClip) {
                        // 扫描只经过右侧数字温室，边界渐隐保证每轮回到起点时无跳闪。
                        val scanAlpha = sin(PI * phase).toFloat().let { it * it } * strength
                        withTransform({ translate(0f, 310f + phase * 440f) }) {
                            drawRect(scanLight, Offset(710f, 0f), Size(385f, 70f), alpha = scanAlpha)
                            drawLine(cyan, Offset(710f, 35f), Offset(1095f, 35f),
                                strokeWidth = 3f, alpha = scanAlpha * 0.9f)
                        }
                    }
                    // 左侧采集图表依次响应，随后右侧仪表亮起，形成同步握手反馈。
                    for (bar in 0..5) {
                        val response = (0.5f + 0.5f * sin((phase - bar * 0.065f) * 2 * PI).toFloat())
                        drawLine(green, Offset(299f + bar * 35f, 344f),
                            Offset(299f + bar * 35f, 321f - response * (20f + level * 37f)),
                            strokeWidth = 10f, cap = StrokeCap.Round, alpha = strength * response * 0.65f)
                    }
                    val reply = sin((phase + 0.5f) * 2 * PI).toFloat().coerceAtLeast(0f)
                    drawArc(cyan, -90f, 300f * reply, false, Offset(993f, 335f), Size(105f, 115f),
                        style = Stroke(5f, cap = StrokeCap.Round), alpha = reply * strength)
                }
                NavigationArtworkKind.DataCenter -> with(scope) {
                    // 强度调制附加状态灯，服务器及云图表仍保持原图。
                    val pulse = 0.6f + 0.4f * sin(phase * 2f * PI).toFloat()
                    for (row in 0..2) {
                        drawCircle(green, 10f + 5f * level, Offset(801f, 521f + row * 129f),
                            alpha = strength * pulse * 0.5f)
                    }
                }
            }
        }
        if (fault) with(scope) {
            // 静态故障标记放在图标留白处，关闭系统动效时也能识别状态。
            val center = Offset(1180f, 170f)
            drawCircle(Color(0xFF2C1620), 47f, center)
            drawCircle(Color(0xFFFF8C9C), 45f, center, style = Stroke(5f))
            drawLine(Color(0xFFFFB5C0), center + Offset(0f, -22f), center + Offset(0f, 7f),
                strokeWidth = 7f, cap = StrokeCap.Round)
            drawCircle(Color(0xFFFFB5C0), 4.5f, center + Offset(0f, 23f))
        }
    }
}

/** 缓存原生路径、片段及坐标缓冲，绘制阶段复用；开放路径两端渐隐避免循环跳闪。 */
private class LightRoute(path: NativePath, private val closed: Boolean = false) {
    private val measure = PathMeasure(path, closed)
    private val segment = NativePath()
    private val composeSegment = segment.asComposePath()
    private val position = FloatArray(2)

    fun draw(scope: DrawScope, progress: Float, tail: Float, color: Color, strength: Float, width: Float) {
        val head = progress * measure.length
        val start = head - tail * measure.length
        segment.rewind()
        if (closed && start < 0f) measure.getSegment(measure.length + start, measure.length, segment, true)
        measure.getSegment(start.coerceAtLeast(0f), head, segment, true)
        val edge = if (closed) 1f else sin(PI * progress).toFloat().let { it * it }
        val alpha = strength * edge
        with(scope) {
            drawPath(composeSegment, color, alpha = alpha * 0.07f,
                style = Stroke(width * 5f, cap = StrokeCap.Round))
            drawPath(composeSegment, color, alpha = alpha * 0.24f,
                style = Stroke(width * 2.2f, cap = StrokeCap.Round))
            drawPath(composeSegment, color, alpha = alpha * 0.92f,
                style = Stroke(width, cap = StrokeCap.Round))
            measure.getPosTan(head, position, null)
            val point = Offset(position[0], position[1])
            drawCircle(color, width * 2.6f, point, alpha = alpha * 0.12f)
            drawCircle(color, width * 1.25f, point, alpha = alpha * 0.72f)
            drawCircle(Color.White, width * 0.48f, point, alpha = alpha)
        }
    }
}
