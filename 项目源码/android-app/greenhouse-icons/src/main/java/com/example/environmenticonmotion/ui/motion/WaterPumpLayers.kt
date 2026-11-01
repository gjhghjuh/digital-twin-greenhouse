package com.example.environmenticonmotion.ui.motion

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import com.example.environmenticonmotion.R
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

internal class WaterPumpLayers(
    val texture: Painter,
    val impeller: Painter,
    val hub: Painter,
    val water: Painter,
    val highlight: Painter,
) {
    // 泵体与出水口使用同一纹理的互补区域，静态叠合没有接缝或重复像素。
    val bodyClip = Path().apply {
        fillType = PathFillType.EvenOdd
        addRect(androidx.compose.ui.geometry.Rect(0f, 0f, 1254f, 1254f))
        addRect(androidx.compose.ui.geometry.Rect(1056f, 471f, 1230f, 690f))
    }
    val glassClip = Path().apply {
        addOval(androidx.compose.ui.geometry.Rect(723f, 580f, 905f, 852f))
    }
    val sweepBrush = Brush.linearGradient(
        0f to Color.Transparent,
        0.5f to Color.White.copy(alpha = 0.24f),
        1f to Color.Transparent,
        start = Offset.Zero,
        end = Offset(65f, 0f),
    )
}

@Composable
internal fun rememberWaterPumpLayers(): WaterPumpLayers {
    val texture = painterResource(R.drawable.water_pump_body)
    val impeller = painterResource(R.drawable.water_pump_impeller)
    val hub = painterResource(R.drawable.water_pump_hub)
    val water = painterResource(R.drawable.water_pump_water)
    val highlight = painterResource(R.drawable.water_pump_highlight)
    return remember(texture, impeller, hub, water, highlight) {
        WaterPumpLayers(texture, impeller, hub, water, highlight)
    }
}

internal fun DrawScope.drawWaterPumpLayers(
    layers: WaterPumpLayers,
    angle: Float,
    rotorScale: Float,
    waterPhase: Float,
    waterAlpha: Float,
    faultAlpha: Float,
    glossProgress: Float,
    startupOffsetPx: Float,
    pressScale: Float,
) {
    // 所有图层都对齐实际的居中 Fit 图片矩形，支持非正方形容器。
    val fit = min(size.width / 1254f, size.height / 1254f)
    val left = (size.width - 1254f * fit) / 2f
    val top = (size.height - 1254f * fit) / 2f
    translate(left + startupOffsetPx, top) {
        scale(fit, fit, pivot = Offset.Zero) {
            scale(pressScale, pressScale, pivot = Offset(627f, 627f)) {
                clipPath(layers.bodyClip) {
                    with(layers.texture) { draw(Size(1254f, 1254f)) }
                }
                clipPath(layers.glassClip) {
                    translate(814f, 716f) {
                        // 先在叶轮平面旋转，再压缩水平轴，保留窗口的透视关系。
                        scale(0.69f * rotorScale, rotorScale, pivot = Offset.Zero) {
                            rotate(angle, pivot = Offset.Zero) {
                                translate(-150f, -150f) {
                                    with(layers.impeller) { draw(Size(300f, 300f)) }
                                }
                            }
                            // 轴心高光固定在光源方向，叶片九十度同形归位时不会闪变。
                            translate(-150f, -150f) {
                                with(layers.hub) { draw(Size(300f, 300f)) }
                            }
                        }
                    }
                }
                if (waterAlpha > 0f) {
                    repeat(3) { index ->
                        val progress = (waterPhase + index / 3f) % 1f
                        // 循环两端透明，水滴从出口下缘出现后向下移动。
                        val alpha = sin(PI * progress).toFloat() * waterAlpha
                        val x = 1132f + sin(PI * progress).toFloat() * 6f
                        translate(x, 635f + progress * 230f) {
                            with(layers.water) { draw(Size(48f, 72f), alpha = alpha) }
                        }
                    }
                }
                // 出水口前景在水滴之后绘制，遮住水流的起点。
                clipRect(1056f, 471f, 1230f, 690f) {
                    with(layers.texture) { draw(Size(1254f, 1254f)) }
                }
                with(layers.highlight) { draw(Size(1254f, 1254f)) }
                if (glossProgress in 0f..1f) {
                    clipPath(layers.glassClip) {
                        translate(650f + glossProgress * 310f, 0f) {
                            drawRect(layers.sweepBrush, size = Size(65f, 1254f))
                        }
                    }
                }
                if (faultAlpha > 0f) {
                    drawOval(
                        color = Color(0xFFFFC45B),
                        topLeft = Offset(721f, 578f),
                        size = Size(186f, 276f),
                        alpha = faultAlpha,
                        style = Stroke(width = 8f),
                    )
                }
            }
        }
    }
}
