package com.example.environmenticonmotion.ui.motion

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.environmenticonmotion.R

/**
 * isSelected 为页面进入状态：进入时双门轻移并增亮，返回待机时合门并恢复原图亮度。
 * 开门与活动量独立，活动比例仅调节屋檐巡光；快速反向切换从当前门位置接续。
 * pressed 接收外部真实按压状态，组件本身不消费点击。
 */
@Composable
fun AnimatedHomeIcon(
    isSelected: Boolean,
    activityFraction: Float,
    modifier: Modifier = Modifier,
    pressed: Boolean = false,
    motionEnabled: Boolean = true,
) {
    NavigationIconMotion(
        resource = R.drawable.nav_home,
        kind = NavigationArtworkKind.Home,
        state = if (isSelected) NavigationMotionState.Active else NavigationMotionState.Idle,
        activityFraction = activityFraction,
        isEntered = isSelected,
        label = "首页",
        description = if (isSelected) "已进入首页" else "首页未选中",
        modifier = modifier,
        pressed = pressed,
        motionEnabled = motionEnabled,
    )
}
