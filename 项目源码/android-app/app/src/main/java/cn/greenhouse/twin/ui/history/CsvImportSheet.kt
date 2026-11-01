package cn.greenhouse.twin.ui.history

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import cn.greenhouse.twin.domain.history.CsvRowResult
import cn.greenhouse.twin.domain.history.CsvRowStatus
import cn.greenhouse.twin.ui.components.GreenhouseCard
import cn.greenhouse.twin.ui.components.StatusTag
import cn.greenhouse.twin.ui.theme.GreenhouseColors as C

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CsvImportSheet(
    state: CsvImportUiState,
    onPick: (Uri) -> Unit,
    onConfirm: () -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit,
) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onPick(uri)
    }
    var showAllRows by remember(state.preview, state.result) { mutableStateOf(false) }
    // 预览与结果直接完整展开，避免确认入口停在半展开浮层下方。
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val preview = state.preview
    val result = state.result
    val rows = result?.rows ?: preview?.rows.orEmpty()
    val problemRows = remember(rows) {
        rows.filter { it.status in setOf(CsvRowStatus.INVALID, CsvRowStatus.CONFLICT) }
    }
    val displayedRows = if (showAllRows) rows else problemRows
    val canConfirm = preview != null && preview.rows.any { it.status == CsvRowStatus.READY || it.status == CsvRowStatus.DUPLICATE }
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheetState,
        containerColor = C.Raised,
        scrimColor = C.Scrim.copy(alpha = 0.65f),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        // 与已认可控制面板使用相同的系统栏明暗和浮层层次。
        val view = LocalView.current
        val window = (view.parent as DialogWindowProvider).window
        SideEffect {
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
        LazyColumn(
            Modifier.fillMaxWidth().heightIn(max = 680.dp),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("导入 CSV", style = MaterialTheme.typography.titleLarge)
                Text("选择 → 格式与设备校验 → 预览 → 确认 → 结果",
                    color = C.Secondary, style = MaterialTheme.typography.bodySmall)
            }
            item {
                OutlinedButton(onClick = { picker.launch(arrayOf("text/*", "application/vnd.ms-excel", "application/octet-stream")) },
                    enabled = !state.loading, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = C.Primary)) {
                    Text(if (preview != null || result != null) "选择另一份 CSV" else "选择 CSV 文件")
                }
                Text("支持归档 snapshots.csv 与 events.csv；真实来源、空值和采样时间保留。",
                    color = C.Muted, style = MaterialTheme.typography.bodySmall)
            }
            when {
                state.loading -> item { HistoryLoadingCard(if (state.importing) "正在写入有效记录" else "正在校验文件与设备") }
                state.error != null -> item {
                    GreenhouseCard(outline = C.Critical.copy(alpha = 0.4f)) {
                        Text("${if (state.importing) "导入" else "文件校验"}失败", color = C.Critical,
                            style = MaterialTheme.typography.titleMedium)
                        Text(state.error, color = C.Secondary, style = MaterialTheme.typography.bodyMedium)
                        Text("未将本次失败报告为全部成功。", color = C.Muted,
                            style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp),
                            colors = ButtonDefaults.textButtonColors(contentColor = C.Primary)) { Text("重新校验") }
                    }
                }
                result != null -> item {
                    GreenhouseCard(outline = if (result.notImported > 0) C.Warning.copy(alpha = 0.4f) else C.Outline) {
                        Text(if (result.notImported > 0) "导入结束 · 有未导入行" else "导入结束",
                            style = MaterialTheme.typography.titleMedium)
                        Text(result.fileName, color = C.Secondary, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(10.dp))
                        ImportCount("新增", result.added)
                        ImportCount("重复跳过", result.duplicate)
                        ImportCount("未导入", result.notImported)
                        Text("完成 · ${historyDateTime(result.importedAtUtcMs)}", color = C.Muted,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                preview != null -> item {
                    GreenhouseCard {
                        Text("待确认预览", style = MaterialTheme.typography.titleMedium)
                        Text(preview.fileName, color = C.Secondary, style = MaterialTheme.typography.bodyMedium)
                        Text("类型 · ${if (preview.kind == "snapshot") "快照" else "事件"}",
                            color = C.Muted, style = MaterialTheme.typography.bodySmall)
                        Text("设备 · ${preview.deviceIds.joinToString("、").ifEmpty { "没有有效设备记录" }}",
                            color = C.Secondary, style = MaterialTheme.typography.bodySmall)
                        Text("${historyDateTime(preview.startUtcMs, "yyyy-MM-dd HH:mm:ss")} — ${historyDateTime(preview.endUtcMs, "yyyy-MM-dd HH:mm:ss")}",
                            color = C.Secondary, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(10.dp))
                        ImportCount("快照", preview.snapshotCount)
                        ImportCount("事件", preview.eventCount)
                        ImportCount("已有或文件内重复", preview.duplicateCount)
                        ImportCount("问题行", preview.invalidCount)
                        if (preview.unknownUtcCount > 0) {
                            Text("${preview.unknownUtcCount} 条设备 UTC 未知，不会借用电脑接收时间补齐。",
                                color = C.Waiting, style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = onConfirm, enabled = canConfirm,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = C.Primary, contentColor = C.OnPrimary)) { Text("确认导入") }
                        Text(if (canConfirm) "有效行写入，重复跳过，问题行保留原因且不覆盖已有记录。"
                            else "没有可写入或可确认重复的记录，请检查问题行或另选文件。",
                            color = C.Muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (!state.loading && rows.isNotEmpty() && state.error == null) {
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (showAllRows) "全部行 · ${rows.size}" else "问题行 · ${problemRows.size}",
                            modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = { showAllRows = !showAllRows }, modifier = Modifier.heightIn(min = 48.dp),
                            colors = ButtonDefaults.textButtonColors(contentColor = C.Primary)) {
                            Text(if (showAllRows) "只看问题" else "查看全部")
                        }
                    }
                    if (!showAllRows && problemRows.isEmpty()) {
                        Text("本次校验没有问题行；可展开查看每行身份与结果。", color = C.Muted,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                // 大档案按实际行懒加载，不以只展示前几行代替完整逐行原因。
                items(displayedRows, key = { it.rowNumber }) { row -> CsvRowCard(row) }
            }
            item {
                OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = C.Primary)) {
                    Text(if (result == null) "关闭" else "完成")
                }
            }
        }
    }
}

@Composable
private fun ImportCount(label: String, count: Int) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = C.Secondary, style = MaterialTheme.typography.bodyMedium)
        Text(count.toString(), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun CsvRowCard(row: CsvRowResult) {
    val label = when (row.status) {
        CsvRowStatus.READY -> "有效 · 待导入"
        CsvRowStatus.DUPLICATE, CsvRowStatus.SKIPPED -> "重复跳过"
        CsvRowStatus.INVALID -> "校验未通过"
        CsvRowStatus.ADDED -> "已新增"
        CsvRowStatus.CONFLICT -> "身份内容冲突"
    }
    val color = when (row.status) {
        CsvRowStatus.INVALID, CsvRowStatus.CONFLICT -> C.Warning
        CsvRowStatus.READY, CsvRowStatus.ADDED -> C.Primary
        CsvRowStatus.DUPLICATE, CsvRowStatus.SKIPPED -> C.Muted
    }
    GreenhouseCard {
        Text("CSV 第 ${row.rowNumber} 行", style = MaterialTheme.typography.bodyMedium)
        StatusTag(label, color)
        row.identity?.let { Text(it, color = C.Muted, style = MaterialTheme.typography.bodySmall) }
        row.reason?.let { Text(it, color = C.Secondary, style = MaterialTheme.typography.bodySmall) }
    }
}
