package com.example.environmenticonmotion.ui.motion

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.environmenticonmotion.R

enum class DataCenterState { Offline, Online, Fault }

/**
 * isSelected 表达页面进入，在线时底部玻璃环在水平平面旋转；负载仅调节速度。
 * 零负载在线仍缓转，返回或离线渐停，故障立即停转；云、服务器及植物保持固定。
 * motionEnabled 仅暂停呈现，不改变在线状态；暂停时显示静态目标强度。
 */
@Composable
fun AnimatedDataCenterIcon(
    state: DataCenterState,
    loadFraction: Float,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    pressed: Boolean = false,
    motionEnabled: Boolean = true,
) {
    NavigationIconMotion(
        resource = R.drawable.nav_data_center,
        kind = NavigationArtworkKind.DataCenter,
        state = when (state) {
            DataCenterState.Offline -> NavigationMotionState.Idle
            DataCenterState.Online -> NavigationMotionState.Active
            DataCenterState.Fault -> NavigationMotionState.Fault
        },
        activityFraction = loadFraction,
        isEntered = isSelected,
        label = "数据中心",
        description = when (state) {
            DataCenterState.Offline -> "离线"
            DataCenterState.Online -> "在线，${if (isSelected) "数据页已选中" else "数据页未选中"}"
            DataCenterState.Fault -> "连接故障，数据流已停止"
        },
        modifier = modifier,
        pressed = pressed,
        motionEnabled = motionEnabled,
    )
}
