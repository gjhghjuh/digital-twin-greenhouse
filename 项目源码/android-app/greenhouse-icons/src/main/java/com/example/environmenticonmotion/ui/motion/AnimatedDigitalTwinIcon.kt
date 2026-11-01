package com.example.environmenticonmotion.ui.motion

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.environmenticonmotion.R

enum class TwinSyncState { Idle, Syncing, Fault }

/**
 * 采集图表、成组上行包、孪生扫描及回传响应表达同步，比例是活动强度而非进度。
 * 待机或零值渐隐新增流光，故障立即停流并显示静态错误标记。
 * 原图中的双温室、箭头及仪表盘均不旋转，也不作为可独立更新的图层。
 */
@Composable
fun AnimatedDigitalTwinIcon(
    state: TwinSyncState,
    syncActivityFraction: Float,
    modifier: Modifier = Modifier,
    pressed: Boolean = false,
    motionEnabled: Boolean = true,
) {
    NavigationIconMotion(
        resource = R.drawable.nav_digital_twin,
        kind = NavigationArtworkKind.DigitalTwin,
        state = when (state) {
            TwinSyncState.Idle -> NavigationMotionState.Idle
            TwinSyncState.Syncing -> NavigationMotionState.Active
            TwinSyncState.Fault -> NavigationMotionState.Fault
        },
        activityFraction = syncActivityFraction,
        isEntered = state == TwinSyncState.Syncing,
        label = "数据孪生",
        description = when (state) {
            TwinSyncState.Idle -> "同步待机"
            TwinSyncState.Syncing -> "同步进行中"
            TwinSyncState.Fault -> "同步故障，数据流已停止"
        },
        modifier = modifier,
        pressed = pressed,
        motionEnabled = motionEnabled,
    )
}
