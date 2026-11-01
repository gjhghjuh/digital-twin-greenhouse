package com.example.environmenticonmotion.ui.motion

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.res.painterResource
import com.example.environmenticonmotion.R
import kotlin.math.min

internal class FanArtwork(val texture: Painter) {
    // 沿原图叶片内缘取纹理，避免把后方支架像素带入旋转图层。
    val blades = PathParser().parsePathString(
        "M 551,534 C 537,515 507,498 476,482 C 439,463 413,444 409,410 " +
            "C 398,379 417,349 444,328 C 481,296 530,281 576,275 " +
            "C 624,269 668,278 697,303 C 726,331 739,367 737,407 " +
            "C 735,450 719,491 697,523 L 664,583 L 579,583 Z " +
            "M 739,599 C 783,591 826,567 864,551 C 902,534 934,531 953,558 " +
            "C 972,588 973,625 961,664 C 947,710 924,752 892,786 " +
            "C 869,812 842,834 814,838 C 782,841 748,822 723,797 " +
            "C 697,771 683,746 670,718 L 649,650 L 687,601 Z " +
            "M 512,599 C 477,593 432,598 392,610 C 350,624 323,647 312,677 " +
            "C 296,713 324,757 356,794 C 390,835 438,873 476,895 " +
            "C 504,912 530,920 550,902 C 571,885 572,857 570,826 " +
            "C 566,790 574,752 592,723 L 617,666 L 566,613 Z",
    ).toPath()
    val aperture = Path().apply { addOval(Rect(264f, 251f, 986f, 965f)) }
    val hub = Path().apply { addOval(Rect(510f, 499f, 743f, 730f)) }
    val supports = listOf(
        PathParser().parsePathString("M 340,378 L 379,339 L 909,853 L 870,892 Z").toPath(),
        PathParser().parsePathString("M 338,853 L 376,892 L 913,377 L 875,339 Z").toPath(),
    )
    val supportBrushes = listOf(
        Brush.linearGradient(
            0f to Color(0xFF255B56), 0.12f to Color(0xFF99DED0),
            0.26f to Color(0xFF3B9188), 0.80f to Color(0xFF72BEB1),
            1f to Color(0xFF184A47),
            start = Offset(595f, 645f), end = Offset(635f, 605f),
        ),
        Brush.linearGradient(
            0f to Color(0xFF245E57), 0.12f to Color(0xFFC2F2D9),
            0.28f to Color(0xFF65B9A9), 0.80f to Color(0xFF2F827B),
            1f to Color(0xFF133F3D),
            start = Offset(590f, 591f), end = Offset(629f, 631f),
        ),
    )
    val motorBrush = Brush.radialGradient(
        0f to Color(0xFF1E716A), 0.82f to Color(0xFF205D56),
        0.96f to Color(0xFF84BA98), 1f to Color(0xFF17433E),
        center = Offset(628f, 612f), radius = 172f,
    )
}

@Composable
internal fun rememberFanArtwork(): FanArtwork {
    val texture = painterResource(R.drawable.device_fan)
    return remember(texture) { FanArtwork(texture) }
}

internal fun DrawScope.drawFanArtwork(
    artwork: FanArtwork,
    angle: Float,
    faultAlpha: Float,
    pressScale: Float,
) {
    val fit = min(size.width / 1254f, size.height / 1254f)
    val left = (size.width - 1254f * fit) / 2f
    val top = (size.height - 1254f * fit) / 2f
    translate(left, top) {
        scale(fit, fit, pivot = Offset.Zero) {
            scale(pressScale, pressScale, pivot = Offset(627f, 627f)) {
                clipPath(artwork.aperture) {
                    // 原图被叶片遮住的支架和电机用原生渐变补全，仅恢复旋转后的背景。
                    artwork.supports.forEachIndexed { index, path ->
                        drawPath(path, artwork.supportBrushes[index])
                        drawPath(path, Color(0xFF90CFC1), style = Stroke(2f))
                    }
                    drawOval(artwork.motorBrush, topLeft = Offset(455f, 442f), size = Size(346f, 340f))
                }
                // 整个内腔由静态支架和旋转叶片组成，固定图层不保留原叶片边缘。
                clipPath(artwork.aperture, ClipOp.Difference) {
                    with(artwork.texture) { draw(Size(1254f, 1254f)) }
                }
                clipPath(artwork.aperture) {
                    rotate(angle, pivot = Offset(626f, 614f)) {
                        clipPath(artwork.blades) {
                            clipPath(artwork.hub, ClipOp.Difference) {
                                with(artwork.texture) { draw(Size(1254f, 1254f)) }
                            }
                        }
                    }
                }
                // 轴心和高光方向固定，避免中心光斑随叶片绕圈。
                clipPath(artwork.hub) {
                    with(artwork.texture) { draw(Size(1254f, 1254f)) }
                }
                if (faultAlpha > 0f) {
                    drawOval(
                        color = Color(0xFFFFC45B), topLeft = Offset(512f, 501f),
                        size = Size(229f, 227f), alpha = faultAlpha, style = Stroke(8f),
                    )
                }
            }
        }
    }
}
