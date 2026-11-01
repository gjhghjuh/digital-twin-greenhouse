package com.example.environmenticonmotion.ui.motion

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.res.painterResource
import com.example.environmenticonmotion.R
import kotlin.math.min

internal class GrowLightArtwork(val texture: Painter) {
    // 分区均以原图坐标定义，属于局部遮罩重构，不改变源 PNG。
    val fixture = PathParser().parsePathString(
        "M 0,0 H 1402 V 600 L 1255,683 L 1000,805 L 973,811 L 218,589 L 0,550 Z",
    ).toPath()
    val panel = PathParser().parsePathString(
        "M 242,538 L 452,411 Q 476,402 501,413 L 1198,627 Q 1221,641 1201,660 " +
            "L 992,780 Q 974,789 954,781 L 250,576 Q 224,564 242,538 Z",
    ).toPath()
    val plants = PathParser().parsePathString(
        "M 187,893 C 170,846 252,795 324,796 C 398,779 468,811 530,851 " +
            "C 577,819 610,787 682,779 C 772,755 844,787 864,847 " +
            "C 874,861 870,876 852,874 C 804,835 748,846 701,907 " +
            "C 748,903 789,919 810,950 C 854,915 913,873 1005,871 " +
            "C 1080,864 1144,892 1194,931 C 1215,949 1214,972 1197,976 " +
            "C 1168,971 1140,950 1108,952 C 1060,945 1025,964 1000,989 " +
            "L 974,1072 L 844,1072 C 829,1041 824,1005 797,978 " +
            "C 795,1017 773,1056 745,1072 L 484,1072 L 433,1028 " +
            "C 387,968 335,945 275,934 C 226,930 191,920 187,893 Z",
    ).toPath()
    val panelMatrix = ColorMatrix()
    val plantMatrix = ColorMatrix()
}

@Composable
internal fun rememberGrowLightArtwork(): GrowLightArtwork {
    val texture = painterResource(R.drawable.device_grow_light)
    return remember(texture) { GrowLightArtwork(texture) }
}

internal fun DrawScope.drawGrowLightArtwork(
    artwork: GrowLightArtwork,
    intensity: Float,
    beamIntensity: Float,
    faultAlpha: Float,
    pressScale: Float,
) {
    val fit = min(size.width / 1402f, size.height / 1122f)
    val left = (size.width - 1402f * fit) / 2f
    val top = (size.height - 1122f * fit) / 2f
    val panelScale = 0.22f + 0.78f * intensity
    artwork.panelMatrix.setToScale(panelScale, panelScale, panelScale, 1f)
    artwork.plantMatrix.setToScale(
        0.50f + 0.50f * intensity,
        0.72f + 0.28f * intensity,
        0.55f + 0.45f * intensity,
        1f,
    )
    val panelFilter = ColorFilter.colorMatrix(artwork.panelMatrix)
    val plantFilter = ColorFilter.colorMatrix(artwork.plantMatrix)
    translate(left, top) {
        scale(fit, fit, pivot = Offset.Zero) {
            scale(pressScale, pressScale, pivot = Offset(701f, 561f)) {
                // 灯体与灯板使用差集裁切，边界不会重复叠加。
                clipPath(artwork.fixture) {
                    clipPath(artwork.panel, ClipOp.Difference) {
                        with(artwork.texture) { draw(Size(1402f, 1122f)) }
                    }
                }
                clipPath(artwork.panel) {
                    with(artwork.texture) { draw(Size(1402f, 1122f), colorFilter = panelFilter) }
                }
                if (beamIntensity > 0f) {
                    // 光束不包含植物纹理，关闭时不绘制光束区域。
                    clipPath(artwork.fixture, ClipOp.Difference) {
                        clipPath(artwork.panel, ClipOp.Difference) {
                            clipPath(artwork.plants, ClipOp.Difference) {
                                with(artwork.texture) { draw(Size(1402f, 1122f), alpha = beamIntensity) }
                            }
                        }
                    }
                }
                clipPath(artwork.plants) {
                    with(artwork.texture) { draw(Size(1402f, 1122f), colorFilter = plantFilter) }
                }
                if (faultAlpha > 0f) {
                    drawPath(
                        path = artwork.panel,
                        color = Color(0xFFFFC04D),
                        alpha = faultAlpha,
                        style = Stroke(width = 5f),
                    )
                }
            }
        }
    }
}
