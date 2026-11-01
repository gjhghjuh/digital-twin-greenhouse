package cn.greenhouse.twin.ui.scene

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.content.Context
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModel
import com.google.android.filament.Engine
import io.github.sceneview.SceneView
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.loaders.EnvironmentLoader
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.node.ModelNode
import java.nio.ByteBuffer

internal val LocalGreenhouseRenderResources = staticCompositionLocalOf<GreenhouseRenderResources> {
    error("3D 引擎必须由当前 Activity 提供")
}

/** Activity 持有完整渲染舞台，页面只绑定当前显示表面。 */
class GreenhouseRenderResources : ViewModel() {
    private var graphics: GraphicsContext? = null
    private var assetLoader: ModelLoader? = null
    private var renderStage: GreenhouseRenderStage? = null
    private var loadedModel: ModelNode? = null
    private var loadedSha256: String? = null
    private var surfaces = 0
    private var cleared = false

    internal val engine: Engine
        get() {
            checkMainThread()
            check(!cleared) { "当前 Activity 已释放" }
            return (graphics ?: GraphicsContext().also { graphics = it }).engine
        }

    internal fun attachScene() {
        checkMainThread()
        check(!cleared)
        check(surfaces == 0) { "同一渲染舞台只能绑定一个活跃页面" }
        surfaces++
        Log.i("Greenhouse3D", "引擎场景引用：$surfaces")
    }

    internal fun modelLoader(context: Context): ModelLoader {
        checkMainThread()
        check(!cleared)
        return assetLoader ?: SceneView.createModelLoader(engine, context.applicationContext)
            .also { assetLoader = it }
    }

    internal fun stage(context: Context, mapping: GreenhouseAssetMapping): GreenhouseRenderStage {
        checkMainThread()
        check(!cleared)
        renderStage?.let {
            check(it.assetSha256 == mapping.sha256) { "当前 Activity 的渲染舞台资产不一致" }
            return it
        }
        return GreenhouseRenderStage(context.applicationContext, mapping, engine, modelLoader(context))
            .also { renderStage = it }
    }

    internal fun model(mapping: GreenhouseAssetMapping): ModelNode {
        checkMainThread()
        check(!cleared)
        loadedModel?.let {
            check(loadedSha256 == mapping.sha256) { "当前 Activity 的模型资产不一致" }
            return it
        }
        val instance = checkNotNull(assetLoader).createModelInstance(ByteBuffer.wrap(mapping.modelBytes))
        val names = instance.asset.entities.map { instance.asset.getName(it) }.filterNotNull().toSet()
        require(names.containsAll(mapping.nodeNames)) { "渲染器加载后的模型节点缺失" }
        return ModelNode(instance, autoAnimate = false, scaleToUnits = null, centerOrigin = null).also {
            loadedModel = it
            loadedSha256 = mapping.sha256
            // 同一资产只创建一次实例，释放原始解析数据，页面切换不重新编译材质和上传纹理。
            instance.asset.releaseSourceData()
            Log.i("Greenhouse3D", "Activity 唯一 GLB 资产已加载：${mapping.sha256}")
        }
    }

    internal fun detachScene() {
        checkMainThread()
        check(surfaces > 0)
        surfaces--
        Log.i("Greenhouse3D", "引擎场景引用：$surfaces")
        releaseIfCleared()
    }

    override fun onCleared() {
        checkMainThread()
        cleared = true
        // Compose 释放视图可能晚于 ViewModel 清理，最后一个表面分离后再销毁引擎。
        releaseIfCleared()
    }

    private fun releaseIfCleared() {
        if (cleared && surfaces == 0) {
            renderStage?.destroy()
            renderStage = null
            // 包装节点不再单独销毁实体，模型资产仍只由共享 Loader 唯一释放。
            loadedModel = null
            loadedSha256 = null
            assetLoader?.destroy()
            assetLoader = null
            graphics?.destroy()
            graphics = null
        }
    }

    private fun checkMainThread() = check(Looper.myLooper() == Looper.getMainLooper()) {
        "Filament 的创建、调用与释放必须在主线程串行执行"
    }

    private class GraphicsContext {
        private val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        private val context: EGLContext
        private val surface: EGLSurface
        val engine: Engine

        init {
            check(display != EGL14.EGL_NO_DISPLAY && EGL14.eglInitialize(display, null, 0, null, 0))
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            check(EGL14.eglChooseConfig(display, intArrayOf(EGL14.EGL_RENDERABLE_TYPE, 0x40,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE), 0,
                configs, 0, 1, count, 0) && count[0] > 0)
            val config = checkNotNull(configs[0])
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
            check(context != EGL14.EGL_NO_CONTEXT)
            surface = EGL14.eglCreatePbufferSurface(display, config,
                intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
            check(surface != EGL14.EGL_NO_SURFACE)
            check(EGL14.eglMakeCurrent(display, surface, surface, context))
            engine = SceneView.createEngine(context)
            Log.i("Greenhouse3D", "Activity 唯一渲染引擎已创建")
        }

        fun destroy() {
            engine.flushAndWait()
            engine.destroy()
            check(EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT))
            check(EGL14.eglDestroySurface(display, surface))
            check(EGL14.eglDestroyContext(display, context))
            check(EGL14.eglTerminate(display))
            Log.i("Greenhouse3D", "Activity 引擎、EGL 上下文与缓冲表面已释放")
        }
    }
}

/** 所有固定 native 资源只创建一次，不随着首页和孪生页导航重建。 */
internal class GreenhouseRenderStage(
    context: Context,
    mapping: GreenhouseAssetMapping,
    val engine: Engine,
    val modelLoader: ModelLoader,
) {
    val assetSha256 = mapping.sha256
    val materialLoader: MaterialLoader = SceneView.createMaterialLoader(engine, context)
    val environmentLoader: EnvironmentLoader = SceneView.createEnvironmentLoader(engine, context)
    val environment = SceneView.createEnvironment(environmentLoader, false)
    val scene = SceneView.createScene(engine)
    val view = SceneView.createView(engine)
    val renderer = SceneView.createRenderer(engine)
    val camera = FixedOverviewCamera(engine, mapping)
    val mainLight = SceneView.createMainLightNode(engine)
    val collisionSystem = SceneView.createCollisionSystem(view)
    private var destroyed = false

    init {
        view.scene = scene
        view.camera = camera.camera
        scene.indirectLight = environment.indirectLight
        scene.skybox = environment.skybox
        Log.i("Greenhouse3D", "Activity 唯一完整渲染舞台已创建：$assetSha256")
    }

    fun destroy() {
        if (destroyed) return
        // 调用方保证已经分离最后一个 Surface，并停止所有页面帧回调。
        engine.flushAndWait()
        val environmentTextures = listOfNotNull(environment.indirectLight?.reflectionsTexture,
            environment.indirectLight?.irradianceTexture).distinct()
        val colorGrading = view.colorGrading
        view.camera = null
        scene.indirectLight = null
        scene.skybox = null
        camera.collisionSystem = null
        mainLight.collisionSystem = null
        scene.removeEntity(mainLight.entity)
        camera.destroy()
        mainLight.destroy()
        collisionSystem.destroy()
        engine.destroyRenderer(renderer)
        engine.destroyView(view)
        engine.destroyScene(scene)
        // 静态工厂创建的默认环境未登记在 EnvironmentLoader，明确由舞台释放。
        environment.indirectLight?.let(engine::destroyIndirectLight)
        environment.skybox?.let(engine::destroySkybox)
        environmentTextures.forEach(engine::destroyTexture)
        colorGrading?.let(engine::destroyColorGrading)
        materialLoader.destroy()
        environmentLoader.destroy()
        destroyed = true
        Log.i("Greenhouse3D", "Activity 完整渲染舞台已释放")
    }
}
