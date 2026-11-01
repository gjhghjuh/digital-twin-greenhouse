package com.example.environmenticonmotion.ui.motion

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** 从水泵分层绘制适配：复用源坐标裁切、运动平面投影和固定前景覆盖顺序。 */
internal class HomeDoorLayers {
    private val opening = Path().apply {
        moveTo(565f, 839f); lineTo(565f, 615f)
        cubicTo(565f, 584f, 582f, 571f, 610f, 571f)
        lineTo(793f, 571f); cubicTo(823f, 571f, 838f, 589f, 838f, 615f)
        lineTo(838f, 839f); close()
    }
    private val leftDoor = Path().apply {
        moveTo(565f, 839f); lineTo(565f, 615f)
        cubicTo(565f, 584f, 582f, 571f, 610f, 571f)
        lineTo(701.5f, 571f); lineTo(701.5f, 839f); close()
    }
    private val rightDoor = Path().apply {
        moveTo(701.5f, 571f); lineTo(793f, 571f)
        cubicTo(823f, 571f, 838f, 589f, 838f, 615f)
        lineTo(838f, 839f); lineTo(701.5f, 839f); close()
    }
    private val fixedBody = Path().apply {
        fillType = PathFillType.EvenOdd
        addRect(Rect(0f, 0f, 1402f, 1122f)); addPath(opening)
    }
    private val warmLight = Brush.radialGradient(
        listOf(Color(0xFFFFEAAF), Color(0x66FFD060), Color.Transparent),
        Offset(702f, 665f), 230f,
    )

    fun draw(scope: DrawScope, image: ImageBitmap, progress: Float) = with(scope) {
        clipPath(opening) {
            // 用门内植物纹理恢复中央被门柱遮挡的区域；外框和外围植物不参与移动。
            drawImage(image)
            drawImage(image, srcOffset = IntOffset(623, 571), srcSize = IntSize(64, 268),
                dstOffset = IntOffset(670, 571), dstSize = IntSize(64, 268))
            drawRect(warmLight, topLeft = Offset(565f, 571f), size = Size(273f, 268f),
                alpha = 0.13f + progress * 0.3f)
            withTransform({ translate(-31f * progress, 0f) }) {
                clipPath(leftDoor) { drawImage(image) }
            }
            withTransform({ translate(31f * progress, 0f) }) {
                clipPath(rightDoor) { drawImage(image) }
            }
            // 新增暖光只随开门增亮，关门回到原图的待机亮度。
            drawRect(warmLight, topLeft = Offset(565f, 571f), size = Size(273f, 268f),
                alpha = progress * 0.24f)
        }
        clipPath(fixedBody) { drawImage(image) }
    }
}

/** 圆环在水平平面旋转后压缩为椭圆；云、服务器、植物和底座保持固定。 */
internal class DataCenterRingLayers {
    private val cyan = Color(0xFF8CF8FF)
    private val gold = Color(0xFFFFE38C)
    private val ringBand = Path().apply {
        // 移除原环的玻璃带和旧高光，避免新环转动时露出固定残影。
        moveTo(436f, 535f)
        cubicTo(262f, 549f, 159f, 637f, 179f, 735f)
        cubicTo(188f, 853f, 447f, 922f, 701f, 922f)
        cubicTo(1001f, 922f, 1233f, 848f, 1229f, 714f)
        cubicTo(1228f, 650f, 1150f, 608f, 1040f, 589f)
        lineTo(1036f, 655f)
        cubicTo(1120f, 664f, 1176f, 696f, 1178f, 734f)
        cubicTo(1154f, 816f, 915f, 858f, 700f, 858f)
        cubicTo(459f, 858f, 234f, 796f, 226f, 711f)
        cubicTo(225f, 654f, 322f, 619f, 438f, 608f); close()
    }
    private val oldRightNode = Path().apply { addOval(Rect(1045f, 786f, 1127f, 868f)) }
    private val ringErase = Path.combine(PathOperation.Union,
        Path.combine(PathOperation.Union, ringBand,
            Path().apply { addOval(Rect(282f, 580f, 356f, 652f)) }), oldRightNode)
    private val fixedBody = Path.combine(PathOperation.Difference,
        Path().apply { addRect(Rect(0f, 0f, 1402f, 1122f)) }, ringErase)
    private val server = Path().apply { addRect(Rect(430f, 408f, 966f, 841f)) }
    private val restorationPaint = Paint()
    private val restorationMask = Brush.radialGradient(
        0f to Color.White, 0.6f to Color.White, 1f to Color.Transparent,
        center = Offset(484f, 787f), radius = 72f,
    )
    private val leafShape = Path().apply {
        moveTo(813f, 686f); cubicTo(874f, 655f, 956f, 674f, 989f, 748f)
        cubicTo(987f, 675f, 1051f, 618f, 1134f, 614f)
        cubicTo(1145f, 700f, 1094f, 764f, 1026f, 801f)
        lineTo(1008f, 843f); lineTo(979f, 847f)
        cubicTo(963f, 815f, 858f, 797f, 813f, 686f); close()
    }
    private val leaves = Path.combine(PathOperation.Difference, leafShape, oldRightNode)
    private val wall = Path().apply {
        moveTo(180f, 695f)
        cubicTo(180f, 789f, 413f, 872f, 700f, 872f)
        cubicTo(987f, 872f, 1220f, 789f, 1220f, 695f)
        lineTo(1220f, 736f)
        cubicTo(1220f, 830f, 987f, 913f, 700f, 913f)
        cubicTo(413f, 913f, 180f, 830f, 180f, 736f); close()
    }
    private val plane = Path().apply {
        fillType = PathFillType.EvenOdd
        addOval(Rect(-520f, -520f, 520f, 520f))
        addOval(Rect(-478f, -478f, 478f, 478f))
    }
    private val glass = Brush.verticalGradient(listOf(
        Color(0xBB24BFAE), Color(0xD808555F), Color(0xBB43C6D1), Color(0xE5BAFFF0)),
        810f, 913f)
    private val reflection = Brush.radialGradient(listOf(
        Color(0xC8F8FFD5), Color(0x668FFFE4), Color.Transparent), Offset.Zero, 140f)
    private val surface = Brush.sweepGradient(listOf(
        Color(0xEE0B7A72), Color(0xE572EEE5), Color(0xE50B645D),
        Color(0xE5CCF695), Color(0xEE0B7A72)), Offset.Zero)

    fun draw(scope: DrawScope, image: ImageBitmap, phase: Float, engagement: Float) = with(scope) {
        drawPath(wall, glass)
        // 投影轮廓固定，材质、分段刻线和节点一起绕环运动，不旋转整张图。
        withTransform({
            translate(700f, 695f)
            scale(1f, 0.34f, Offset.Zero)
            rotate(phase * 360f, Offset.Zero)
        }) {
            drawPath(plane, surface)
            drawCircle(cyan, 520f, Offset.Zero, style = Stroke(5f), alpha = 0.85f)
            drawCircle(Color(0xFFEAFFD0), 478f, Offset.Zero, style = Stroke(4f), alpha = 0.85f)
            for (index in 0 until 12) {
                val angle = index * PI / 6
                val direction = Offset(cos(angle).toFloat(), sin(angle).toFloat())
                drawLine(cyan, direction * 486f, direction * 510f,
                    strokeWidth = 4f, cap = StrokeCap.Round, alpha = 0.35f + engagement * 0.4f)
            }
        }
        // 玻璃反射沿轨道连续移动，整条前沿使用同一渐变，避免分段接缝。
        clipPath(wall) {
            for (index in 0..1) {
                val angle = (phase + index * 0.5f) * 2 * PI
                val depth = sin(angle).toFloat().coerceAtLeast(0f)
                withTransform({ translate(700f + 520f * cos(angle).toFloat(),
                    715f + 177f * sin(angle).toFloat()) }) {
                    drawCircle(reflection, 140f, Offset.Zero, alpha = depth * 0.8f)
                }
            }
        }
        drawArc(cyan, 0f, 180f, false, Offset(180f, 559f), Size(1040f, 354f),
            style = Stroke(6f), alpha = 0.8f)
        clipPath(fixedBody) { drawImage(image) }
        // 背面球先画，再覆盖服务器；正面球最后画，建立真实遮挡关系。
        for (index in 0..2) drawNode(this, phase + index / 3f, false, engagement, index)
        clipPath(server) {
            drawImage(image)
            // 相邻服务器层纹理等比例补全遮挡区，羽化仅作用于补片，避免圆形硬边。
            drawContext.canvas.saveLayer(Rect(412f, 715f, 556f, 859f), restorationPaint)
            drawImage(image, srcOffset = IntOffset(412, 585), srcSize = IntSize(144, 144),
                dstOffset = IntOffset(412, 715), dstSize = IntSize(144, 144))
            drawRect(restorationMask, Offset(412f, 715f), Size(144f, 144f), blendMode = BlendMode.DstIn)
            drawContext.canvas.restore()
        }
        for (index in 0..2) drawNode(this, phase + index / 3f, true, engagement, index)
        clipPath(leaves) { drawImage(image) }
    }


    private fun drawNode(scope: DrawScope, turn: Float, front: Boolean, engagement: Float, index: Int) {
        val angle = turn * 2 * PI
        val depth = sin(angle).toFloat()
        if ((depth >= 0f) != front) return
        val position = Offset(700f + 498f * cos(angle).toFloat(), 695f + 170f * depth)
        val radius = 19f + 10f * (depth + 1f) / 2f
        val color = if (index == 1) gold else cyan
        with(scope) {
            drawCircle(color, radius * 1.5f, position, alpha = 0.10f + engagement * 0.08f)
            drawCircle(Brush.radialGradient(listOf(Color.White, color, Color(0xFF15968E)),
                position + Offset(-radius * 0.3f, -radius * 0.3f), radius * 1.3f), radius, position)
            drawCircle(Color(0xFFE7FFEF), radius, position, style = Stroke(3f))
        }
    }
}
