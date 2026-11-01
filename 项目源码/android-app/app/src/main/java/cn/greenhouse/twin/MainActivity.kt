package cn.greenhouse.twin

import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.os.Bundle
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import cn.greenhouse.twin.domain.DemoScenario
import cn.greenhouse.twin.ui.home.HomeScreen
import cn.greenhouse.twin.ui.home.HomeViewModel
import cn.greenhouse.twin.ui.control.DeviceControlScreen
import cn.greenhouse.twin.ui.twin.GreenhouseTwinScreen
import cn.greenhouse.twin.ui.theme.GreenhouseTheme
import cn.greenhouse.twin.ui.scene.GreenhouseRenderResources
import cn.greenhouse.twin.ui.scene.LocalGreenhouseRenderResources
import cn.greenhouse.twin.ui.scene.GreenhouseModelPreview
import cn.greenhouse.twin.ui.scene.SceneObject
import cn.greenhouse.twin.ui.history.*
import cn.greenhouse.twin.domain.GreenhouseTwinState
import cn.greenhouse.twin.domain.StateSource
import cn.greenhouse.twin.domain.history.HistoryMetric
import cn.greenhouse.twin.ui.theme.GreenhouseColors
import cn.greenhouse.twin.ui.monitoring.AlarmCenterScreen
import cn.greenhouse.twin.ui.monitoring.EnvironmentAdviceSheet
import cn.greenhouse.twin.ui.settings.SettingsScreen
import cn.greenhouse.twin.ui.control.DeviceControlSheet
import cn.greenhouse.twin.domain.ActuatorKey
import cn.greenhouse.twin.domain.monitoring.MonitoringSettings
import com.example.environmenticonmotion.motion.LocalEnvironmentAnimationsEnabled

class MainActivity : ComponentActivity() {
    private val homeViewModel: HomeViewModel by viewModels()
    private val renderResources: GreenhouseRenderResources by viewModels()
    private val historyViewModel: HistoryViewModel by viewModels()
    private var started = false
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = updateForeground()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ContextCompat.registerReceiver(this, screenReceiver,
            IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON) },
            ContextCompat.RECEIVER_NOT_EXPORTED)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        if (savedInstanceState == null) applyDesignIntent(intent)
        setContent {
            val preferences by homeViewModel.preferences.collectAsStateWithLifecycle()
            CompositionLocalProvider(LocalGreenhouseRenderResources provides renderResources,
                LocalEnvironmentAnimationsEnabled provides (preferences?.animationsEnabled ?: false)) {
            GreenhouseTheme {
                val navigation = rememberNavController()
                var deferred by remember { mutableStateOf<String?>(null) }
                var adviceOpen by remember { mutableStateOf(false) }
                var controlTarget by remember { mutableStateOf<ActuatorKey?>(null) }
                var twinFocus by remember { mutableStateOf<SceneObject?>(null) }
                val monitoring by homeViewModel.monitoring.collectAsStateWithLifecycle()
                val currentState by homeViewModel.state.collectAsStateWithLifecycle()
                val localError by homeViewModel.localOperationError.collectAsStateWithLifecycle()
                fun navigatePrimary(route: String) {
                    navigation.navigate(route) { popUpTo("home"); launchSingleTop = true }
                }
                fun navigateNamed(name: String) {
                    when (name) {
                        "首页" -> navigation.popBackStack("home", false)
                        "数字孪生" -> navigatePrimary("twin")
                        "数据" -> navigatePrimary("data")
                        "报警" -> navigatePrimary("alarms")
                    }
                }
                fun openData(objectType: SceneObject? = null) {
                    when (objectType) {
                        SceneObject.TEMPERATURE -> historyViewModel.selectMetric(HistoryMetric.TEMPERATURE)
                        SceneObject.HUMIDITY -> historyViewModel.selectMetric(HistoryMetric.HUMIDITY)
                        SceneObject.SOIL -> historyViewModel.selectMetric(HistoryMetric.SOIL)
                        SceneObject.LIGHT -> historyViewModel.selectMetric(HistoryMetric.LIGHT)
                        else -> Unit
                    }
                    navigatePrimary("data")
                }
                NavHost(navigation, startDestination = "home") {
                    // 一级页共用应用范围状态与唯一轮询，设备控制使用二级返回导航。
                    composable("home") {
                        // 在页面作用域收集，避免导航图记住初次构建时的状态快照。
                        val state by homeViewModel.state.collectAsStateWithLifecycle()
                        HomeScreen(state, homeViewModel::enterDesignPreview,
                            homeViewModel::selectDemoScenario, homeViewModel::exitDesignPreview,
                            { navigation.navigate("twin") { launchSingleTop = true } },
                            { navigation.navigate("control") { launchSingleTop = true } },
                            homeViewModel::requestControl, homeViewModel::requestOfflinePump, homeViewModel::refreshNow,
                            homeViewModel::resendControl, homeViewModel::dismissControlWaiting,
                            { openData() }, { navigation.navigate("replay") { launchSingleTop = true } },
                            { navigation.navigate("settings") { launchSingleTop = true } },
                            { navigatePrimary("alarms") }, { adviceOpen = true })
                    }
                    composable("twin") {
                        val state by homeViewModel.state.collectAsStateWithLifecycle()
                        GreenhouseTwinScreen(state,
                            { navigation.popBackStack("home", false) },
                            { navigation.navigate("control") { launchSingleTop = true } },
                            homeViewModel::requestControl, homeViewModel::requestOfflinePump, homeViewModel::refreshNow,
                            homeViewModel::resendControl, homeViewModel::dismissControlWaiting, ::openData,
                            { navigatePrimary("alarms") }, { adviceOpen = true }, twinFocus, { twinFocus = null })
                    }
                    composable("control") {
                        val state by homeViewModel.state.collectAsStateWithLifecycle()
                        DeviceControlScreen(state, { navigation.popBackStack() }, homeViewModel::requestControl,
                            homeViewModel::requestOfflinePump, homeViewModel::refreshNow, homeViewModel::resendControl,
                            homeViewModel::dismissControlWaiting, homeViewModel::requestScene, homeViewModel::stopSceneRemaining)
                    }
                    composable("data") {
                        val liveState by homeViewModel.state.collectAsStateWithLifecycle()
                        val historyState by historyViewModel.history.collectAsStateWithLifecycle()
                        val importState by historyViewModel.csvImport.collectAsStateWithLifecycle()
                        DataCenterScreen(liveState, historyState, historyViewModel::selectDevice,
                            historyViewModel::selectMetric, historyViewModel::selectRange,
                            historyViewModel::shiftWindow, historyViewModel::latest, historyViewModel::openImport,
                            { navigation.navigate("replay") { launchSingleTop = true } }, historyViewModel::refresh,
                            ::navigateNamed, { window, style, modifier -> HistoryTrendChart(window, style, modifier) })
                        importState?.let { CsvImportSheet(it, historyViewModel::chooseFile,
                            historyViewModel::confirmImport, historyViewModel::openImport, historyViewModel::closeImport) }
                    }
                    composable("replay") {
                        val replay by historyViewModel.replay.collectAsStateWithLifecycle()
                        DisposableEffect(Unit) {
                            historyViewModel.startReplay()
                            onDispose { historyViewModel.exitReplay() }
                        }
                        ReplayScreen(replay, historyViewModel::setPlaying, historyViewModel::setSpeed,
                            historyViewModel::seek, historyViewModel::previousEvent, historyViewModel::nextEvent,
                            { historyViewModel.exitReplay(); navigation.popBackStack() },
                            historyViewModel::refresh,
                            { state, modifier -> GreenhouseModelPreview(state, modifier, interactive = true,
                                motionEnabled = replay.playing, showLabels = false) })
                    }
                    composable("alarms") {
                        AlarmCenterScreen(currentState, monitoring, homeViewModel::acknowledgeAlarm,
                            { navigation.navigate("settings") { launchSingleTop = true } },
                            { selected -> twinFocus = selected; navigatePrimary("twin") },
                            { metric -> historyViewModel.selectMetric(metric); historyViewModel.latest(); navigatePrimary("data") },
                            { target -> controlTarget = target }, ::navigateNamed)
                    }
                    composable("settings") {
                        val saving by homeViewModel.monitoringSaving.collectAsStateWithLifecycle()
                        val error by homeViewModel.monitoringError.collectAsStateWithLifecycle()
                        val savedAt by homeViewModel.monitoringSavedAt.collectAsStateWithLifecycle()
                        val connectionTest by homeViewModel.connectionTest.collectAsStateWithLifecycle()
                        SettingsScreen(currentState, preferences ?: MonitoringSettings(), preferences == null,
                            saving, error, savedAt, preferences?.animationsEnabled ?: false, connectionTest,
                            { navigation.popBackStack() }, homeViewModel::testConnection, homeViewModel::refreshNow,
                            homeViewModel::saveMonitoringSettings, homeViewModel::updateRefreshSeconds,
                            homeViewModel::updateAnimationsEnabled, { openData() },
                            { openData(); historyViewModel.openImport() })
                    }
                }
                if (adviceOpen) {
                    EnvironmentAdviceSheet(monitoring, { item ->
                        val current = homeViewModel.currentAdvice(item.id)
                        adviceOpen = false
                        if (current?.canExecute == true) controlTarget = current.target
                        else deferred = "建议已失效，请重新取得有效实时数据后分析。"
                    }, { adviceOpen = false })
                }
                controlTarget?.let { target ->
                    DeviceControlSheet(currentState, target, homeViewModel::requestControl,
                        homeViewModel::requestOfflinePump, homeViewModel::refreshNow, homeViewModel::resendControl,
                        homeViewModel::dismissControlWaiting) { controlTarget = null }
                }
                localError?.let { message ->
                    AlertDialog(onDismissRequest = homeViewModel::clearLocalError,
                        containerColor = GreenhouseColors.Raised, title = { Text("本地操作未完成") },
                        text = { Text(message) }, confirmButton = {
                            TextButton(onClick = homeViewModel::clearLocalError) { Text("知道了") }
                        })
                }
                deferred?.let { name ->
                    AlertDialog(onDismissRequest = { deferred = null }, containerColor = GreenhouseColors.Raised,
                        title = { Text("环境建议") }, text = { Text(name) },
                        confirmButton = { TextButton(onClick = { deferred = null }) { Text("返回") } })
                }
            }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        started = true
        updateForeground()
    }

    override fun onStop() {
        started = false
        updateForeground()
        super.onStop()
    }

    override fun onDestroy() {
        unregisterReceiver(screenReceiver)
        super.onDestroy()
    }

    private fun updateForeground() {
        val foreground = started && (getSystemService(POWER_SERVICE) as PowerManager).isInteractive
        homeViewModel.setForeground(foreground)
        historyViewModel.setForeground(foreground)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyDesignIntent(intent)
    }

    // 设计样例只能由调试版本的显式入口注入，正式启动保持未知状态。
    private fun applyDesignIntent(intent: Intent) {
        if (!BuildConfig.DEBUG) return
        if (intent.getBooleanExtra("verify_replay_control_block", false)) historyViewModel.verifyReplayControlBlock()
        val name = intent.getStringExtra("design_demo") ?: return
        if (name == "EXIT") {
            homeViewModel.exitDesignPreview()
            android.util.Log.i("GreenhouseUI", "已退出设计预览，状态恢复未知")
            return
        }
        val scenario = DemoScenario.entries.singleOrNull { it.name == name } ?: return
        homeViewModel.enterDesignPreview()
        homeViewModel.selectDemoScenario(scenario)
        android.util.Log.i("GreenhouseUI", "设计状态已切换：$scenario")
    }
}
