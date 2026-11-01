package cn.greenhouse.twin.ui.monitoring

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.greenhouse.twin.domain.history.HistoryMetric
import cn.greenhouse.twin.domain.monitoring.AdviceItem
import cn.greenhouse.twin.domain.monitoring.MonitoringStatus
import cn.greenhouse.twin.domain.monitoring.MonitoringUiState
import cn.greenhouse.twin.ui.components.*
import cn.greenhouse.twin.ui.theme.GreenhouseColors as C
import com.example.environmenticonmotion.R as IconR

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnvironmentAdviceSheet(
    monitoringState: MonitoringUiState,
    onExecute: (AdviceItem) -> Unit,
    onClose: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onClose, sheetState = sheetState,
        containerColor = C.Raised, scrimColor = C.Scrim.copy(alpha = 0.65f),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) {
        MonitoringSheetSystemBars()
        LazyColumn(Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("环境建议", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(6.dp))
                StatusTag("本地规则分析", C.Advice)
                Spacer(Modifier.height(8.dp))
                Text("依据有效实时采样与设备反馈生成。建议不会直接改变执行器，操作仍需控制确认。",
                    color = C.Secondary, style = MaterialTheme.typography.bodyMedium)
            }
            monitoringState.error?.let { error ->
                item {
                    GreenhouseCard(outline = C.Critical.copy(alpha = 0.35f)) {
                        Text("建议数据暂不可用", color = C.Critical, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
                        Text(error, color = C.Secondary, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            monitoringState.adviceUnavailableReason?.let { reason ->
                item {
                    GreenhouseCard(outline = C.Waiting.copy(alpha = 0.35f)) {
                        Text("当前不能生成操作建议", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
                        Text(reason, color = C.Secondary, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (monitoringState.adviceItems.isEmpty() && monitoringState.adviceUnavailableReason == null && monitoringState.error == null) {
                item {
                    GreenhouseCard {
                        Text("当前没有操作建议", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
                        Text("已启用的本地规则未生成需要操作的建议；未配置的指标不据此判定正常。",
                            color = C.Secondary, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            items(monitoringState.adviceItems, key = { it.id }) { advice ->
                EnvironmentAdviceCard(advice,
                    monitoringState.settings.monitorEnabled && monitoringState.status == MonitoringStatus.ACTIVE &&
                        monitoringState.adviceUnavailableReason == null && monitoringState.error == null,
                    onExecute)
            }
            item {
                Text("仅前台实时模式分析。土壤为软件模型、光照为实验档位；本页不配置设备 AUTO，不提供后台推送。",
                    color = C.Muted, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("暂不处理")
                }
            }
        }
    }
}

@Composable
private fun EnvironmentAdviceCard(advice: AdviceItem, currentAdviceAvailable: Boolean, onExecute: (AdviceItem) -> Unit) {
    GreenhouseCard(outline = C.Advice.copy(alpha = 0.25f)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // 采样依据用既有原色图标静态呈现，不用图标活动伪造建议执行结果。
            OriginalIcon(when (advice.metric) {
                HistoryMetric.TEMPERATURE -> IconR.drawable.env_temperature
                HistoryMetric.HUMIDITY -> IconR.drawable.env_air_humidity
                HistoryMetric.LIGHT -> IconR.drawable.env_light
                HistoryMetric.SOIL -> IconR.drawable.env_soil_moisture
            }, Modifier.size(52.dp))
            Column(Modifier.weight(1f)) {
                Text(advice.title, style = MaterialTheme.typography.titleMedium)
                SourceTag(monitoringSourceLabel(advice.source))
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("依据", color = C.Muted, style = MaterialTheme.typography.labelMedium)
        Text(advice.reason, color = C.Text, style = MaterialTheme.typography.bodyMedium)
        Text("采样 ${monitoringTime(advice.sampledAtUtcMs)} · 北京时间", color = C.Secondary,
            style = MaterialTheme.typography.bodySmall)
        Text("${advice.metric.label} ${monitoringValue(advice.value, advice.metric.unit)} · 本地阈值 ${monitoringValue(advice.thresholdValue, advice.metric.unit)}",
            color = C.Secondary, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(10.dp))
        Text("预期影响", color = C.Muted, style = MaterialTheme.typography.labelMedium)
        Text(advice.expectedImpact, color = C.Secondary, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(12.dp))
        Button(onClick = { onExecute(advice) }, enabled = advice.canExecute && currentAdviceAvailable,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = C.Primary, contentColor = C.OnPrimary)) {
            Text("执行建议 · 先确认${advice.target.label}控制")
        }
        if (!advice.canExecute) {
            advice.blockedReason?.let { reason ->
                Text(reason, color = C.Waiting, style = MaterialTheme.typography.bodySmall)
            }
        }
        Text("${if (advice.requestedState) "建议开启" else "建议关闭"}${advice.target.label}；进入标准控制面板后核对目标、反馈与请求。",
            color = C.Muted, style = MaterialTheme.typography.bodySmall)
    }
}
