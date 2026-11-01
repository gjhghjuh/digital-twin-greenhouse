package cn.greenhouse.twin.ui.scene

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import cn.greenhouse.twin.domain.*
import cn.greenhouse.twin.ui.theme.GreenhouseColors as C
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.filament.Camera
import com.google.android.filament.Engine
import io.github.sceneview.SceneView
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.node.CameraNode
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.Node
import io.github.sceneview.utils.pickNode
import io.github.sceneview.utils.worldToScreen
import com.google.android.filament.MaterialInstance
import kotlin.math.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ModelPreviewStatus {
    data object Loading : ModelPreviewStatus
    data class Ready(val assetVersion: String, val sha256: String, val nodeCount: Int) : ModelPreviewStatus
    data class Error(val message: String) : ModelPreviewStatus
}

private sealed interface AssetCheck {
    data object Checking : AssetCheck
    data class Ready(val mapping: GreenhouseAssetMapping) : AssetCheck
    data class Failed(val message: String) : AssetCheck
}

@Composable
fun GreenhouseModelPreview(
    state: GreenhouseTwinState,
    modifier: Modifier = Modifier,
    interactive: Boolean = false,
    preset: GreenhouseViewPreset = GreenhouseViewPreset.COMPLETE,
    selectedObject: SceneObject? = null,
    motionEnabled: Boolean = true,
    showLabels: Boolean = true,
    labelContentInsets: PaddingValues = PaddingValues(6.dp),
    onObjectSelected: (SceneObject) -> Unit = {},
    onStatusChanged: (ModelPreviewStatus) -> Unit = {},
) {
    val applicationAnimationsEnabled = com.example.environmenticonmotion.motion.LocalEnvironmentAnimationsEnabled.current
    val context = LocalContext.current.applicationContext
    val owner = LocalLifecycleOwner.current
    val hostView = LocalView.current
    var assetCheck by remember { mutableStateOf<AssetCheck>(AssetCheck.Checking) }
    var status by remember { mutableStateOf<ModelPreviewStatus>(ModelPreviewStatus.Loading) }
    val statusListener by rememberUpdatedState(onStatusChanged)
    var resumed by remember(owner) { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var visible by remember { mutableStateOf(true) }

    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, _ ->
            resumed = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(status) { statusListener(status) }
    LaunchedEffect(context) {
        try {
            assetCheck = AssetCheck.Ready(withContext(Dispatchers.IO) { readVerifiedGreenhouseMapping(context) })
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val message = error.message ?: error.javaClass.simpleName
            Log.e("Greenhouse3D", "模型交付校验失败", error)
            assetCheck = AssetCheck.Failed(message)
            status = ModelPreviewStatus.Error(message)
        }
    }
    Box(
        modifier = modifier.onGloballyPositioned { coordinates ->
            val bounds = coordinates.boundsInWindow()
            visible = bounds.width > 0f && bounds.height > 0f && bounds.bottom > 0f && bounds.right > 0f &&
                bounds.left < hostView.width && bounds.top < hostView.height
        },
        contentAlignment = Alignment.Center,
    ) {
        when (val check = assetCheck) {
            AssetCheck.Checking -> ModelMessage("正在校验 3D 模型", loading = true)
            is AssetCheck.Failed -> ModelMessage("3D 模型加载失败\n${check.message}")
            is AssetCheck.Ready -> {
                if (resumed && visible) {
                    VerifiedModelScene(check.mapping, state, interactive, preset, selectedObject, motionEnabled && applicationAnimationsEnabled, showLabels,
                        onObjectSelected, labelContentInsets, Modifier.fillMaxSize()) { status = it }
                } else {
                    // 退出视口或进入后台后移除场景，释放 GPU 资源并停止帧循环。
                    ModelMessage("3D 预览已暂停")
                }
            }
        }
    }
}

@Composable
private fun VerifiedModelScene(
    mapping: GreenhouseAssetMapping,
    state: GreenhouseTwinState,
    interactive: Boolean,
    preset: GreenhouseViewPreset,
    selectedObject: SceneObject?,
    motionEnabled: Boolean,
    showLabels: Boolean,
    onObjectSelected: (SceneObject) -> Unit,
    labelContentInsets: PaddingValues,
    modifier: Modifier,
    onStatusChanged: (ModelPreviewStatus) -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalGreenhouseRenderResources.current
    val rendererResult = remember(context, mapping, interactive, resources) {
        runCatching { GreenhouseSceneView(context, mapping, interactive, resources, resources.stage(context, mapping)) }
    }
    val preview = rendererResult.getOrNull()
    if (preview == null) {
        val error = rendererResult.exceptionOrNull()!!
        val message = error.message ?: error.javaClass.simpleName
        LaunchedEffect(error) {
            Log.e("Greenhouse3D", "3D 渲染器创建失败", error)
            onStatusChanged(ModelPreviewStatus.Error(message))
        }
        ModelMessage("3D 渲染器创建失败\n$message")
        return
    }
    val listener by rememberUpdatedState(onStatusChanged)
    var loading by remember { mutableStateOf(true) }
    var loadingError by remember { mutableStateOf<String?>(null) }
    var labels by remember { mutableStateOf<List<ProjectedSceneLabel>>(emptyList()) }
    val objectListener by rememberUpdatedState(onObjectSelected)
    SideEffect {
        preview.configure(state, interactive, preset, motionEnabled)
        preview.projectLabelsEnabled = showLabels
        preview.onObjectSelected = { objectListener(it) }
        preview.onProjectedLabels = { if (labels != it) labels = it }
    }
    DisposableEffect(preview) {
        preview.load { nextStatus ->
            loading = nextStatus is ModelPreviewStatus.Loading
            loadingError = (nextStatus as? ModelPreviewStatus.Error)?.message
            listener(nextStatus)
        }
        // AndroidView 释放和窗口分离均可能调用 destroy，资源所有者只执行一次。
        onDispose { preview.destroy() }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { preview },
            onRelease = { it.destroy() },
        )
        if (loading) ModelMessage("正在加载 3D 大棚", loading = true)
        loadingError?.let { ModelMessage("3D 模型加载失败\n$it") }
        if (interactive && showLabels && !loading && loadingError == null)
            ProjectedLabels(labels, state, selectedObject, labelContentInsets, objectListener)
    }
}

private class GreenhouseSceneView(
    context: Context,
    private val mapping: GreenhouseAssetMapping,
    private val interactiveAtCreation: Boolean,
    private val resources: GreenhouseRenderResources,
    private val stage: GreenhouseRenderStage,
) : SceneView(
    context = context,
    sharedEngine = stage.engine,
    sharedModelLoader = stage.modelLoader,
    sharedMaterialLoader = stage.materialLoader,
    sharedEnvironmentLoader = stage.environmentLoader,
    sharedEnvironment = stage.environment,
    sharedScene = stage.scene,
    sharedView = stage.view,
    sharedRenderer = stage.renderer,
    sharedCameraNode = stage.camera,
    sharedMainLightNode = stage.mainLight,
    sharedCollisionSystem = stage.collisionSystem,
    isOpaque = false,
    cameraManipulator = null,
    onGestureListener = null,
) {
    // Activity 唯一拥有固定渲染资源与模型；页面只拥有当前 Surface 与帧回调。
    // 模型中的实体只归 AssetLoader 所有；ModelNode 仅作场景包装，不执行 destroy。
    protected override val activity: ComponentActivity? = null
    private val loadScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val overviewCamera = stage.camera
    private val sessionId = Integer.toHexString(System.identityHashCode(this))
    private val pickHandler = Handler(Looper.getMainLooper())
    private var model: ModelNode? = null
    private var nodes = emptyMap<String, Node>()
    private var currentState = GreenhouseTwinState()
    private var interactive = false
    private var motionEnabled = true
    private var preset = GreenhouseViewPreset.COMPLETE
    private var previousFrame = 0L
    private var projectionFrame = 0L
    private var rotorAngle = 0f
    private var flowMaterial: MaterialInstance? = null
    private var lampMaterial: MaterialInstance? = null
    private var soilMaterials = emptyList<MaterialInstance>()
    private var lastVisualKey = ""
    var onObjectSelected: (SceneObject) -> Unit = {}
    var onProjectedLabels: (List<ProjectedSceneLabel>) -> Unit = {}
    var projectLabelsEnabled = true
    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                overviewCamera.zoom(detector.scaleFactor)
                return true
            }
        })
    private val sceneGestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent) = true
        override fun onScroll(first: MotionEvent?, second: MotionEvent, dx: Float, dy: Float): Boolean {
            if (!scaleDetector.isInProgress) overviewCamera.orbit(dx, dy)
            return true
        }
        override fun onSingleTapUp(event: MotionEvent): Boolean {
            val loaded = model ?: return false
            // Filament 接收 Handler 或 Executor，不能传递 SceneView 默认的 Looper 对象。
            view.pickNode(event.x, event.y, loaded.renderableNodes, handler = pickHandler) { node, _, _ ->
                if (!isDestroyed) {
                    val type = mapping.pickObjectsByMesh[node?.name]?.firstOrNull()
                    Log.i("Greenhouse3D", "真实拾取：${node?.name}，对象=$type，场景=$sessionId")
                    type?.let(onObjectSelected)
                }
            }
            return true
        }
    })

    init {
        resources.attachScene()
        if (interactiveAtCreation) {
            // 孪生页将窗口标签置于模型之上，原生背景与已认可的舞台颜色一致。
            setZOrderMediaOverlay(true)
            skybox = null
            // 清屏区域直接进入显示表面，使用主题的显示色值，避免背景二次变暗。
            val background = C.Overview
            renderer.clearOptions = renderer.clearOptions.apply {
                clear = true
                clearColor = floatArrayOf(background.red, background.green, background.blue, 1f)
            }
        } else {
            skybox = stage.environment.skybox
            renderer.clearOptions = renderer.clearOptions.apply {
                clear = true
                clearColor = floatArrayOf(0f, 0f, 0f, 0f)
            }
        }
        // 首页沿用原透明合成，让已认可的椭圆光场和背景保持原样。
        setCameraNode(overviewCamera)
        onFrame = { frame -> animateFrame(frame) }
        Log.i("Greenhouse3D", "场景创建：$sessionId，共用 Activity 唯一渲染舞台")
    }

    fun load(onStatusChanged: (ModelPreviewStatus) -> Unit) {
        loadScope.launch {
            onStatusChanged(ModelPreviewStatus.Loading)
            try {
                ensureActive()
                check(!isDestroyed) { "场景已释放" }
                // 当前仅一个 RESUMED 页面显示模型；切换时先解绑，再复用同一米制资产。
                val loaded = resources.model(mapping)
                model = loaded
                nodes = loaded.nodes.associateBy { checkNotNull(it.name) }
                childNodes = listOf(loaded)
                fun material(binding: GreenhouseMaterialBinding): MaterialInstance {
                    val node = loaded.renderableNodes.single { it.name == binding.nodeName }
                    return node.materialInstances.single { it.name == binding.materialName }
                }
                // 直接调整交付的专用材质，实体释放仍只归 Activity 的共享 ModelLoader。
                flowMaterial = material(mapping.pumpFlow)
                lampMaterial = material(mapping.lampGlow)
                soilMaterials = loaded.renderableNodes.filter { it.name in mapping.soilNodes }
                    .flatMap { it.materialInstances }.filter { it.name == mapping.soilMaterialName }.distinct()
                require(soilMaterials.isNotEmpty()) { "土壤材质绑定缺失" }
                require(lampMaterial!!.material.hasParameter("emissiveFactor")) { "发光材质缺少运行时发光参数" }
                applyPreset()
                applyReported()
                onStatusChanged(ModelPreviewStatus.Ready(mapping.version, mapping.sha256, mapping.nodeNames.size))
                Log.i("Greenhouse3D", "V2 模型已加载：${mapping.nodeNames.size} 个节点，SHA-256=${mapping.sha256}，场景=$sessionId")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val message = error.message ?: error.javaClass.simpleName
                Log.e("Greenhouse3D", "GLB 加载失败，场景=$sessionId", error)
                onStatusChanged(ModelPreviewStatus.Error(message))
            }
        }
    }

    fun configure(state: GreenhouseTwinState, enabled: Boolean, requestedPreset: GreenhouseViewPreset, motion: Boolean) {
        currentState = state
        motionEnabled = motion
        if (interactive != enabled || preset != requestedPreset) {
            interactive = enabled
            preset = requestedPreset
            if (model != null) applyPreset()
        }
        if (model != null) applyReported()
    }

    private fun applyPreset() {
        val display = mapping.presets.getValue(preset)
        // 先恢复全部子树，再应用本次隐藏，保证来回切换可逆且保留灯支撑。
        nodes.values.forEach { it.isVisible = true }
        display.hiddenNodes.forEach { nodes.getValue(it).isVisible = false }
        overviewCamera.applyPreset(display, !interactive)
        lastVisualKey = ""
        applyReported()
        Log.i("Greenhouse3D", "预设=${preset.mappingKey}，隐藏=${display.hiddenNodes}，场景=$sessionId")
    }

    private fun applyReported() {
        val s = currentState
        val flow = s.pump.reported == ReportedState.ON
        val lamp = s.lamp.reported == ReportedState.ON
        val key = "${s.pump.reported}/${s.lamp.reported}/${s.fan.reported}/${s.canPresentModelActivity}/$motionEnabled/${s.soilHumidity.value}"
        if (key == lastVisualKey) return
        lastVisualKey = key
        val rgba = mapping.pumpFlowBaseColor
        flowMaterial!!.setParameter("baseColorFactor", rgba[0], rgba[1], rgba[2], if (flow) 1f else 0f)
        nodes.getValue(mapping.pumpFlow.nodeName).isVisible = flow
        // 强度只作暖白运行示意，不表示实测照度或可调功率，未改变其他材质。
        lampMaterial!!.setParameter("emissiveFactor", if (lamp) 30_000f else 0f,
            if (lamp) 26_000f else 0f, if (lamp) 18_000f else 0f)
        val soil = s.soilHumidity
        if (soil.value != null && soil.validity == ObservationValidity.VALID) {
            val dry = (1.0 - soil.value / 100.0).toFloat()
            val color = mapping.soilBaseColor
            soilMaterials.forEach { it.setParameter("baseColorFactor", color[0] * (0.55f + dry * 0.45f),
                color[1] * (0.5f + dry * 0.4f), color[2] * (0.45f + dry * 0.35f), color[3]) }
        } else {
            // 缺口中恢复资产中性外观，未知文字由标签表达，不能留下上一时刻的湿度着色。
            val color = mapping.soilBaseColor
            soilMaterials.forEach { it.setParameter("baseColorFactor", color[0], color[1], color[2], color[3]) }
        }
        Log.i("Greenhouse3D", "反馈绑定：pumpAlpha=${if (flow) 1 else 0}，lampEmission=${if (lamp) 30000 else 0}，" +
            "fan=${s.fan.reported}，活动=${s.canPresentModelActivity && motionEnabled}，仅叶轮局部X，场景=$sessionId")
    }

    private fun animateFrame(frame: Long) {
        val loaded = model ?: return
        if (isDestroyed) return
        val seconds = if (previousFrame == 0L) 0f else ((frame - previousFrame) / 1_000_000_000.0).toFloat().coerceIn(0f, 0.05f)
        previousFrame = frame
        if (currentState.canPresentModelActivity && motionEnabled) {
            if (currentState.fan.reported == ReportedState.ON) {
                rotorAngle = (rotorAngle + seconds * 240f) % 360f
                nodes.getValue(mapping.fanRotorNode).rotation = Rotation(rotorAngle, 0f, 0f)
            }
            if (currentState.pump.reported == ReportedState.ON) {
                val pulse = 0.85f + 0.15f * sin((frame / 1_000_000_000.0 * PI * 2 / 1.8).toFloat())
                flowMaterial!!.setParameter("emissiveFactor", 200f * pulse, 2000f * pulse, 2400f * pulse)
            }
        }
        if (interactive && projectLabelsEnabled && width > 0 && height > 0 && frame - projectionFrame >= 100_000_000) {
            projectionFrame = frame
            val positions = mapping.anchors.mapNotNull { (type, point) ->
                val xy = view.worldToScreen(point.toPosition())
                if (!xy.x.isFinite() || !xy.y.isFinite() || xy.x !in 0f..width.toFloat() || xy.y !in 0f..height.toFloat()) null
                else ProjectedSceneLabel(type, xy.x, xy.y, width, height)
            }
            onProjectedLabels(positions)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!interactive) return false
        parent?.requestDisallowInterceptTouchEvent(true)
        scaleDetector.onTouchEvent(event)
        sceneGestures.onTouchEvent(event)
        return true
    }

    override fun destroy() {
        if (isDestroyed) return
        Log.i("Greenhouse3D", "场景释放开始：$sessionId，取消加载并移除模型包装")
        // 在接触 native 资源前取消读取，禁止已移除页面的加载任务回写引擎。
        loadScope.cancel()
        onFrame = null
        onProjectedLabels = {}
        onObjectSelected = {}
        model = null
        nodes = emptyMap()
        flowMaterial = null
        lampMaterial = null
        soilMaterials = emptyList()
        clearChildNodes()
        // 共享相机与灯先解绑，库同时移除该页面的节点回调；固定资源由 Activity 统一释放。
        view.camera = null
        overviewCamera.collisionSystem = null
        mainLightNode = null
        // 所有固定资源均显式传入，库仅停止帧循环并分离当前 Surface/SwapChain。
        super.destroy()
        resources.detachScene()
        Log.i("Greenhouse3D", "场景释放完成：$sessionId，显示表面已释放，唯一模型已解绑")
    }
}

internal class FixedOverviewCamera(
    engine: Engine,
    private val mapping: GreenhouseAssetMapping,
) : CameraNode(engine) {
    private var currentPreset = mapping.presets.getValue(GreenhouseViewPreset.COMPLETE)
    private var zoomScale = 1.0
    private var yaw = 0.0
    private var pitch = 0.0
    private var radius = 1.0
    private var aspectRatio = 1.0
    private var homepage = true
    init {
        position = mapping.cameraPosition.toPosition()
        lookAt(targetWorldPosition = mapping.cameraTarget.toPosition(), smooth = false)
        setExposure(16.0f, 1.0f / 125.0f, 100.0f)
    }

    fun applyPreset(preset: GreenhouseDisplayPreset, isHomepage: Boolean) {
        currentPreset = preset
        homepage = isHomepage
        zoomScale = 1.0
        val relative = preset.cameraPosition - preset.cameraTarget
        radius = sqrt(relative.dot(relative))
        yaw = atan2(relative.x, relative.z)
        pitch = asin(relative.y / radius)
        updatePose()
        updateProjection(28.0, 0.1f, 100f, aspectRatio)
    }

    fun zoom(factor: Float) {
        if (!factor.isFinite() || factor <= 0) return
        zoomScale = (zoomScale / factor).coerceIn(0.35, 3.0)
        updateProjection(28.0, 0.1f, 100f, aspectRatio)
    }

    fun orbit(dx: Float, dy: Float) {
        yaw += dx * 0.005
        pitch = (pitch + dy * 0.005).coerceIn(-1.45, 1.45)
        updatePose()
    }

    private fun updatePose() {
        val target = currentPreset.cameraTarget
        position = Position((target.x + radius * cos(pitch) * sin(yaw)).toFloat(),
            (target.y + radius * sin(pitch)).toFloat(), (target.z + radius * cos(pitch) * cos(yaw)).toFloat())
        val up = if (currentPreset.type == GreenhouseViewPreset.TOP && abs(cos(pitch)) < 0.001)
            Position(0f, 0f, -1f) else Position(0f, 1f, 0f)
        lookAt(targetWorldPosition = target.toPosition(), upDirection = up, smooth = false)
    }

    override fun updateProjection(focalLength: Double, near: Float, far: Float, aspect: Double) {
        if (view != null) {
            aspectRatio = aspect
            val frustum = mapping.frustum(aspect, currentPreset)
            // 仅调整首页构图，让模型略上移；保持映射相机、资产坐标与米制不变。
            val verticalCompositionOffset = if (homepage) 0.18 else 0.0
            setProjection(
                Camera.Projection.ORTHO,
                -frustum.halfWidth * zoomScale,
                frustum.halfWidth * zoomScale,
                -frustum.halfHeight * zoomScale - verticalCompositionOffset,
                frustum.halfHeight * zoomScale - verticalCompositionOffset,
                0.1,
                100.0,
            )
        }
    }
}

private fun WorldPoint.toPosition() = Position(x.toFloat(), y.toFloat(), z.toFloat())

private data class ProjectedSceneLabel(val type: SceneObject, val x: Float, val y: Float, val width: Int, val height: Int)

@Composable
private fun ProjectedLabels(labels: List<ProjectedSceneLabel>, state: GreenhouseTwinState,
    selected: SceneObject?, contentInsets: PaddingValues, onSelect: (SceneObject) -> Unit) {
    val density = LocalDensity.current
    val measured = remember { mutableStateMapOf<SceneObject, IntSize>() }
    if (labels.isEmpty()) return
    val widthDp = 112.dp * density.fontScale
    val estimatedWidth = with(density) { widthDp.toPx() }
    val minimumHeight = with(density) { 48.dp.toPx() }
    val gap = with(density) { 6.dp.toPx() }
    val insetTop = with(density) { contentInsets.calculateTopPadding().toPx() }
    val insetBottom = with(density) { contentInsets.calculateBottomPadding().toPx() }
    data class Placed(val label: ProjectedSceneLabel, val x: Float, val y: Float, val width: Float, val height: Float)
    val placed = mutableListOf<Placed>()
    // 八个业务节点各自保留至少48dp触摸区域，按实际测量尺寸避让并留出全屏工具栏。
    for (label in labels) {
        val size = measured[label.type]
        val boxWidth = size?.width?.toFloat() ?: estimatedWidth
        val boxHeight = max(minimumHeight, size?.height?.toFloat() ?: minimumHeight)
        val maxX = (label.width - boxWidth - gap).coerceAtLeast(gap)
        val maxY = (label.height - insetBottom - boxHeight).coerceAtLeast(insetTop)
        val wantedX = (label.x - boxWidth / 2).coerceIn(gap, maxX)
        val wantedY = (label.y - boxHeight).coerceIn(insetTop, maxY)
        val candidates = mutableListOf(wantedX to wantedY)
        var y = insetTop
        while (y <= maxY) {
            var x = gap
            while (x <= maxX) { candidates.add(x to y); x += boxWidth + gap }
            y += boxHeight + gap
        }
        val chosen = candidates.minBy { (x, y) ->
            val overlaps = placed.count { x < it.x + it.width + gap && x + boxWidth + gap > it.x &&
                y < it.y + it.height + gap && y + boxHeight + gap > it.y }
            (x - wantedX).pow(2) + (y - wantedY).pow(2) + overlaps * 1_000_000_000f
        }
        placed.add(Placed(label, chosen.first, chosen.second, boxWidth, boxHeight))
    }
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            placed.forEach { drawLine(C.Outline.copy(alpha = 0.8f), Offset(it.label.x, it.label.y),
                Offset(it.x + it.width / 2, it.y + it.height / 2), 1.2f) }
        }
        placed.forEach { p ->
            val key = when (p.label.type) {
                SceneObject.PUMP -> ActuatorKey.PUMP
                SceneObject.LAMP -> ActuatorKey.LAMP
                SceneObject.FAN -> ActuatorKey.FAN
                else -> null
            }
            val waiting = key?.reported(state)?.requestStatus in setOf(RequestStatus.SENDING, RequestStatus.WAITING,
                RequestStatus.TIMED_OUT, RequestStatus.UNCERTAIN)
            val border = if (waiting) C.Waiting else if (selected == p.label.type) C.Primary else C.Outline
            Surface(modifier = Modifier.align(Alignment.TopStart).offset { IntOffset(p.x.roundToInt(), p.y.roundToInt()) }
                .width(widthDp).heightIn(min = 48.dp).onSizeChanged { measured[p.label.type] = it }
                .clickable { onSelect(p.label.type) }, color = C.Raised.copy(alpha = 0.93f),
                shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, border)) {
                Column(Modifier.padding(horizontal = 7.dp, vertical = 5.dp), verticalArrangement = Arrangement.Center) {
                    Text(p.label.type.displayName, color = C.Secondary, fontSize = 10.sp, lineHeight = 12.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(sceneLabelText(p.label.type, state), color = if (waiting) C.Waiting else C.Text,
                        fontSize = 10.sp, lineHeight = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

private fun sceneLabelText(type: SceneObject, state: GreenhouseTwinState): String {
    fun number(observation: Observation<out Number>, unit: String, source: String): String =
        if (observation.validity != ObservationValidity.VALID || observation.value == null) "未知 · $source"
        else "${String.format(java.util.Locale.ROOT, "%.1f", observation.value.toDouble())}$unit · $source"
    val key = when (type) {
        SceneObject.PUMP -> ActuatorKey.PUMP
        SceneObject.LAMP -> ActuatorKey.LAMP
        SceneObject.FAN -> ActuatorKey.FAN
        else -> null
    }
    if (key != null) {
        val actual = key.reported(state)
        val text = when (actual.reported) { ReportedState.ON -> "开启"; ReportedState.OFF -> "关闭"; ReportedState.UNKNOWN -> "未知" }
        val waiting = actual.requestStatus in setOf(RequestStatus.SENDING, RequestStatus.WAITING, RequestStatus.TIMED_OUT, RequestStatus.UNCERTAIN)
        return text + when { state.stateSource == StateSource.REPLAY -> " · 历史反馈"; waiting -> " · 等待核实"; !state.canPresentModelActivity && actual.reported != ReportedState.UNKNOWN -> " · 最后已知"; else -> " · 手动" }
    }
    return when (type) {
        SceneObject.TEMPERATURE -> number(state.temperature, "°C", "SENSOR")
        SceneObject.HUMIDITY -> number(state.humidity, "%", "SENSOR")
        SceneObject.SOIL -> number(state.soilHumidity, "%", "SIM")
        SceneObject.LIGHT -> if (state.lightLevel.value == null) "未知 · EXP" else "${state.lightLevel.value}/7档 · EXP"
        SceneObject.CONTROLLER -> when (state.deviceConnection) { DeviceConnection.ONLINE -> "在线"; DeviceConnection.OFFLINE -> "离线"; DeviceConnection.UNKNOWN -> "未知" }
        else -> error("设备标签已由反馈处理")
    }
}

@Composable
private fun ModelMessage(message: String, loading: Boolean = false) {
    Column(
        modifier = Modifier.padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (loading) CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
