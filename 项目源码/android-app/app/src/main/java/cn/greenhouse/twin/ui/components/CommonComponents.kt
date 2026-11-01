package cn.greenhouse.twin.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import cn.greenhouse.twin.ui.theme.GreenhouseColors as C
import cn.greenhouse.twin.ui.theme.GreenhouseDimensions as D

@Composable
fun GreenhouseCard(
    modifier: Modifier = Modifier,
    outline: Color = C.Outline,
    containerColor: Color = C.Surface,
    highlight: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(modifier, shape = RoundedCornerShape(D.CardRadius), color = containerColor,
        border = BorderStroke(1.dp, outline)) {
        // 主舞台顶部的轻微内高光只改变容器层次，不给所有数据卡叠加发光。
        Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(
            if (highlight) Color.White.copy(alpha = 0.025f) else Color.Transparent,
            Color.Transparent,
        ))).padding(12.dp), content = content)
    }
}

@Composable
fun SourceTag(text: String) {
    Surface(shape = RoundedCornerShape(5.dp), color = Color.Transparent,
        border = BorderStroke(1.dp, C.Outline)) {
        Text(text, Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
            color = C.Muted, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun StatusTag(text: String, color: Color) {
    Surface(shape = RoundedCornerShape(50), color = color.copy(alpha = 0.08f)) {
        Row(Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(text, color = color, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
fun SectionTitle(title: String, subtitle: String? = null) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (subtitle != null) Text(subtitle, color = C.Muted,
            style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
fun OriginalIcon(resource: Int, modifier: Modifier = Modifier, description: String? = null) {
    Image(painterResource(resource), description, modifier, contentScale = ContentScale.Fit)
}

// 图标本体只呈现图形，中文业务状态由外部容器统一提供。
fun Modifier.businessIconSemantics(description: String) = clearAndSetSemantics {
    contentDescription = description
}

// 根据实际屏幕位置停掉离屏动效，不通过更改设备反馈实现暂停。
@Composable
fun rememberVisibleMotion(): Pair<Boolean, Modifier> {
    val view = LocalView.current
    var visible by remember { mutableStateOf(false) }
    val modifier = Modifier.onGloballyPositioned {
        val bounds = it.boundsInWindow()
        visible = bounds.bottom > 0f && bounds.top < view.height &&
            bounds.right > 0f && bounds.left < view.width
    }
    return visible to modifier
}
