package cn.greenhouse.twin.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import cn.greenhouse.twin.domain.*
import cn.greenhouse.twin.ui.theme.GreenhouseColors as C
import cn.greenhouse.twin.ui.theme.GreenhouseDimensions as D
import com.example.environmenticonmotion.R as IconR
import com.example.environmenticonmotion.ui.motion.*

// 从首页已认可的导航复制提取，一级页面共用原图标、动效及尺寸。
@Composable
fun GreenhouseNavigation(state: GreenhouseTwinState, selected: String, onSelect: (String) -> Unit) {
    Surface(Modifier.drawWithCache {
        onDrawWithContent {
            drawContent()
            drawLine(C.Outline.copy(alpha = 0.5f), Offset.Zero, Offset(size.width, 0f), 1.dp.toPx())
        }
    }, color = C.Navigation) {
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("首页", "数字孪生", "数据", "报警").forEachIndexed { index, title ->
                Column(Modifier.weight(1f).background(if (title == selected) C.Primary.copy(alpha = 0.12f) else Color.Transparent,
                    RoundedCornerShape(14.dp)).clickable { if (title != selected) onSelect(title) }
                    .padding(vertical = 4.dp).clearAndSetSemantics {
                        contentDescription = if (title == selected) "$title，当前页面" else "$title，点击打开"
                    }, horizontalAlignment = Alignment.CenterHorizontally) {
                    val icon = Modifier.size(D.NavigationIcon).alpha(if (title == selected) 1f else 0.7f)
                    when (index) {
                        0 -> AnimatedHomeIcon(title == selected, 0.3f, icon,
                            motionEnabled = state.canPresentCurrentActivity)
                        1 -> AnimatedDigitalTwinIcon(when (state.cloudQueryStatus) {
                            CloudQueryStatus.QUERYING -> TwinSyncState.Syncing
                            CloudQueryStatus.FAILED -> TwinSyncState.Fault
                            else -> TwinSyncState.Idle
                        }, if (state.cloudQueryStatus == CloudQueryStatus.QUERYING) 0.5f else 0f, icon,
                            motionEnabled = !state.synchronizationSuspended)
                        2 -> AnimatedDataCenterIcon(if (state.synchronizationStatus == SynchronizationStatus.READY)
                            DataCenterState.Online else DataCenterState.Offline, 0f, false, icon,
                            motionEnabled = state.canPresentCurrentActivity)
                        3 -> if (state.alarmSummary.status == AlarmSummaryStatus.NOT_CONNECTED) {
                            // 尚未取得报警依据时，不把组件的正常勾选当作业务结论。
                            Box(icon, contentAlignment = Alignment.Center) {
                                OriginalIcon(IconR.drawable.nav_alarm_center, Modifier.fillMaxSize())
                                Box(Modifier.fillMaxSize().background(C.Surface.copy(alpha = 0.75f)))
                                Text("未知", color = C.Secondary, style = MaterialTheme.typography.labelMedium)
                            }
                        } else AnimatedAlarmCenterIcon(if (state.alarmSummary.status == AlarmSummaryStatus.WARNING)
                                AlarmSeverity.Warning else AlarmSeverity.None,
                                if (state.isDesignPreview && state.alarmSummary.status == AlarmSummaryStatus.WARNING)
                                    setOf(AlarmSource.Water) else state.alarmSummary.affectedCategories.map { category ->
                                        when (category) {
                                            AlarmCategory.TEMPERATURE -> AlarmSource.Temperature
                                            AlarmCategory.WATER -> AlarmSource.Water
                                            AlarmCategory.EQUIPMENT -> AlarmSource.Equipment
                                        }
                                    }.toSet(),
                                if (state.alarmSummary.status == AlarmSummaryStatus.WARNING) 0.5f else 0f,
                                state.alarmSummary.allAcknowledged, icon, motionEnabled = state.canPresentCurrentActivity)
                    }
                    Text(title, style = MaterialTheme.typography.labelMedium,
                        color = if (title == selected) C.Primary else C.Secondary)
                }
            }
        }
    }
}

