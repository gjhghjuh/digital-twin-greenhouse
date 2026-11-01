package com.example.environmenticonmotion.ui.motion

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.environmenticonmotion.R

enum class AlarmSeverity { None, Warning, Critical }
enum class AlarmSource { Temperature, Water, Equipment }

/**
 * 严重级别与来源来自业务，活动比例为零至一的事件活动强度，不代表告警数量。
 * 零活动仍保留级别提醒；已确认只收敛循环，不清除严重级别和异常来源。
 * 无告警时来源必须为空且未确认；原图固定红色保留，待机停止新增提醒。
 * pressed 接收外部真实按压，motionEnabled 关闭时呈现静态业务状态。
 */
@Composable
fun AnimatedAlarmCenterIcon(
    severity: AlarmSeverity,
    affectedSources: Set<AlarmSource>,
    activityFraction: Float,
    acknowledged: Boolean,
    modifier: Modifier = Modifier,
    pressed: Boolean = false,
    motionEnabled: Boolean = true,
) {
    require((severity == AlarmSeverity.None) == affectedSources.isEmpty()) {
        "无告警时来源必须为空，存在告警时必须提供异常来源"
    }
    require(!acknowledged || severity != AlarmSeverity.None) { "无告警状态不能标记为已确认" }
    AlarmIconMotion(R.drawable.nav_alarm_center, severity, affectedSources, activityFraction,
        acknowledged, modifier, pressed, motionEnabled)
}
