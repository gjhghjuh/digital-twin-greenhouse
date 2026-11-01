package cn.greenhouse.twin.ui.scene

import android.content.Context
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

internal data class WorldPoint(val x: Double, val y: Double, val z: Double) {
    operator fun minus(other: WorldPoint) = WorldPoint(x - other.x, y - other.y, z - other.z)
    fun dot(other: WorldPoint) = x * other.x + y * other.y + z * other.z
    fun cross(other: WorldPoint) = WorldPoint(
        y * other.z - z * other.y,
        z * other.x - x * other.z,
        x * other.y - y * other.x,
    )

    fun normalized(): WorldPoint {
        val length = sqrt(dot(this))
        require(length > 0.0) { "相机方向无效" }
        return WorldPoint(x / length, y / length, z / length)
    }
}

internal data class OrthographicFrustum(val halfWidth: Double, val halfHeight: Double)

internal data class GreenhouseDisplayPreset(
    val type: GreenhouseViewPreset,
    val hiddenNodes: Set<String>,
    val cameraPosition: WorldPoint,
    val cameraTarget: WorldPoint,
    val orthographicScale: Double,
)

internal data class GreenhouseMaterialBinding(
    val nodeName: String,
    val materialName: String,
)

internal data class GreenhouseAssetMapping(
    val version: String,
    val sha256: String,
    val modelAssetPath: String,
    val nodeNames: Set<String>,
    val cameraPosition: WorldPoint,
    val cameraTarget: WorldPoint,
    val orthographicScale: Double,
    val boundsMin: WorldPoint,
    val boundsMax: WorldPoint,
    val presets: Map<GreenhouseViewPreset, GreenhouseDisplayPreset>,
    val anchors: Map<SceneObject, WorldPoint>,
    val pickObjectsByMesh: Map<String, List<SceneObject>>,
    val pumpFlow: GreenhouseMaterialBinding,
    val pumpFlowBaseColor: List<Float>,
    val lampGlow: GreenhouseMaterialBinding,
    val fanRotorNode: String,
    val fanPivot: WorldPoint,
    val soilNodes: Set<String>,
    val soilMaterialName: String,
    val soilBaseColor: List<Float>,
    val modelBytes: ByteArray,
) {
    fun frustum(
        aspect: Double,
        preset: GreenhouseDisplayPreset = presets.getValue(GreenhouseViewPreset.COMPLETE),
    ): OrthographicFrustum {
        require(aspect > 0.0 && aspect.isFinite()) { "模型容器比例无效" }
        val forward = (preset.cameraTarget - preset.cameraPosition).normalized()
        // 俯视相机的方向平行于 Y 轴，明确以 -Z 为相机上方。
        val cameraUp = if (preset.type == GreenhouseViewPreset.TOP) WorldPoint(0.0, 0.0, -1.0)
            else WorldPoint(0.0, 1.0, 0.0)
        val right = forward.cross(cameraUp).normalized()
        val up = right.cross(forward).normalized()
        var requiredWidth = 0.0
        var requiredHeight = 0.0
        for (x in listOf(boundsMin.x, boundsMax.x)) {
            for (y in listOf(boundsMin.y, boundsMax.y)) {
                for (z in listOf(boundsMin.z, boundsMax.z)) {
                    val relative = WorldPoint(x, y, z) - preset.cameraTarget
                    requiredWidth = max(requiredWidth, abs(relative.dot(right)))
                    requiredHeight = max(requiredHeight, abs(relative.dot(up)))
                }
            }
        }
        // 保持映射中的相机与米制；只扩大正交视野以适配卡片，不缩放模型。
        val baseWidth = preset.orthographicScale * 0.5 * if (aspect >= 1.0) 1.0 else aspect
        val baseHeight = preset.orthographicScale * 0.5 / if (aspect >= 1.0) aspect else 1.0
        // 细节预设保留交付的局部构图，其余预设适配完整米制边界。
        val fit = if (preset.type == GreenhouseViewPreset.DETAIL) 1.0
            else max(1.0, max(requiredWidth * 1.08 / baseWidth, requiredHeight * 1.08 / baseHeight))
        return OrthographicFrustum(baseWidth * fit, baseHeight * fit)
    }
}

private object VerifiedGreenhouseAssetCache {
    var mapping: GreenhouseAssetMapping? = null
}

internal fun readVerifiedGreenhouseMapping(context: Context): GreenhouseAssetMapping =
    synchronized(VerifiedGreenhouseAssetCache) {
        VerifiedGreenhouseAssetCache.mapping ?: readAndVerifyGreenhouseMapping(context).also {
            // 只共享校验后的只读字节与映射，不跨页面共享相机、选中态或 native 实体。
            VerifiedGreenhouseAssetCache.mapping = it
        }
    }

private fun readAndVerifyGreenhouseMapping(context: Context): GreenhouseAssetMapping {
    val mapping = context.assets.open("models/twin-map.json").bufferedReader(Charsets.UTF_8).use {
        JSONObject(it.readText())
    }
    require(mapping.getInt("schemaVersion") == 1) { "物模型映射版本不支持" }
    require(mapping.getString("assetId") == "greenhouse-v2") { "模型资产标识不一致" }
    require(mapping.getString("assetVersion") == "2.0.0") { "本轮需要 V2 模型资产" }
    require(mapping.getJSONObject("units").getDouble("metresPerUnit") == 1.0) { "模型必须为米制" }
    require(mapping.getJSONObject("coordinates").getJSONObject("glb").getString("upAxis") == "+Y") {
        "模型坐标轴必须为 GLB Y-up"
    }
    require(mapping.getString("defaultDisplayPreset") == "complete") { "默认模型视图必须为 complete" }
    val preset = mapping.getJSONObject("displayPresets").getJSONObject("complete")
    require(preset.getJSONArray("hiddenNodes").length() == 0) { "完整预览不能隐藏模型部件" }
    require(!preset.getBoolean("autoPlayback")) { "首页外观预览不能自动播放设备动画" }
    val camera = preset.getJSONObject("camera")
    require(camera.getString("coordinateSystem") == "GLB_Y_UP") { "相机坐标系不一致" }
    require(camera.getString("projection") == "ORTHOGRAPHIC") { "首页必须使用映射中的正交相机" }
    val fileName = mapping.getString("glb")
    require(fileName == "greenhouse.glb") { "模型文件名不一致" }
    val modelAssetPath = "models/$fileName"
    val bytes = context.assets.open(modelAssetPath).use { it.readBytes() }
    val actualHash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
        "%02x".format(it.toInt() and 0xff)
    }
    val expectedHash = mapping.getString("glbSha256")
    require(actualHash == expectedHash) { "GLB 的 SHA-256 与交付映射不一致" }

    // 对当前 GLB 的真实节点索引逐项核对，避免误用旧资产的名称或索引。
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    require(bytes.size >= 20 && buffer.getInt(0) == 0x46546c67 && buffer.getInt(4) == 2) { "GLB 文件头无效" }
    require(buffer.getInt(8) == bytes.size) { "GLB 文件长度不完整" }
    val jsonLength = buffer.getInt(12)
    require(jsonLength > 0 && jsonLength <= bytes.size - 20 && buffer.getInt(16) == 0x4e4f534a) {
        "GLB 场景描述无效"
    }
    val gltf = JSONObject(String(bytes, 20, jsonLength, Charsets.UTF_8))
    val actualNodes = gltf.getJSONArray("nodes")
    val inventory = mapping.getJSONArray("nodeInventory")
    require(inventory.length() == actualNodes.length()) { "GLB 节点数量与映射不一致" }
    val names = buildSet {
        for (i in 0 until inventory.length()) {
            val item = inventory.getJSONObject(i)
            val index = item.getInt("index")
            require(index in 0 until actualNodes.length()) { "映射节点索引超出 GLB" }
            val name = item.getString("name")
            require(actualNodes.getJSONObject(index).getString("name") == name) { "节点 $name 的索引不一致" }
            require(add(name)) { "模型存在重复节点名：$name" }
        }
    }
    require(mapping.getString("rootNode") in names) { "GLB 缺少映射根节点" }
    val bounds = mapping.getJSONObject("boundsGLB")
    val min = bounds.point("min")
    val max = bounds.point("max")
    require(max.x > min.x && max.y > min.y && max.z > min.z) { "模型边界无效" }
    val actualMaterials = gltf.getJSONArray("materials")
    val materialInventory = mapping.getJSONArray("materialInventory")
    require(actualMaterials.length() == materialInventory.length()) { "材质数量与映射不一致" }
    val materialByName = buildMap<String, JSONObject> {
        for (i in 0 until materialInventory.length()) {
            val item = materialInventory.getJSONObject(i)
            val index = item.getInt("index")
            require(index in 0 until actualMaterials.length()) { "材质索引超出 GLB" }
            val material = actualMaterials.getJSONObject(index)
            val name = item.getString("name")
            require(material.getString("name") == name) { "材质 $name 的索引不一致" }
            require(put(name, material) == null) { "重复材质名称：$name" }
        }
    }
    val presets = GreenhouseViewPreset.entries.associateWith { type ->
        val item = mapping.getJSONObject("displayPresets").getJSONObject(type.mappingKey)
        val presetCamera = item.getJSONObject("camera")
        require(presetCamera.getString("coordinateSystem") == "GLB_Y_UP") { "预设坐标系不一致" }
        require(presetCamera.getString("projection") == "ORTHOGRAPHIC") { "预设必须为正交相机" }
        require(!item.getBoolean("autoPlayback")) { "预设不能自动播放设备动画" }
        val hidden = item.getJSONArray("hiddenNodes").stringSet()
        require(names.containsAll(hidden)) { "预设隐藏节点缺失" }
        GreenhouseDisplayPreset(
            type, hidden, presetCamera.point("position"), presetCamera.point("target"),
            presetCamera.getDouble("orthographicScaleMetres").also {
                require(it > 0.0 && it.isFinite()) { "预设正交相机尺度无效" }
            },
        )
    }
    val anchors = SceneObject.entries.associateWith { objectType ->
        require(objectType.anchorName in names) { "标签锚点缺失：${objectType.anchorName}" }
        mapping.getJSONObject("allAnchors").getJSONObject(objectType.anchorName).point("worldPosition")
    }
    val actuators = mapping.getJSONObject("actuators")
    fun actuatorBinding(type: SceneObject): GreenhouseMaterialBinding {
        val actuator = actuators.getJSONObject(type.businessId)
        val dynamicNodes = actuator.getJSONArray("dynamicNodes").stringSet()
        val dynamicMaterials = actuator.getJSONArray("dynamicMaterials").stringSet()
        require(dynamicNodes.size == 1 && dynamicMaterials.size == 1) { "动态节点与材质必须单独对应" }
        val binding = GreenhouseMaterialBinding(dynamicNodes.single(), dynamicMaterials.single())
        require(binding.nodeName in names && binding.materialName in materialByName) { "动态绑定缺失" }
        return binding
    }
    val pump = actuatorBinding(SceneObject.PUMP)
    val lamp = actuatorBinding(SceneObject.LAMP)
    val flowMaterial = materialByName.getValue(pump.materialName)
    // glTF 2.0规定省略alphaCutoff时取0.5，这是格式定义而不是加载错误的替代路径。
    require(flowMaterial.getString("alphaMode") == "MASK" && flowMaterial.optDouble("alphaCutoff", 0.5) == 0.5) {
        "水流材质必须使用交付的透明遮罩规则"
    }
    val fan = actuators.getJSONObject("fan").getJSONObject("rotation")
    require(fan.point("localAxis") == WorldPoint(1.0, 0.0, 0.0)) { "风扇局部轴必须为 X 轴" }
    require(fan.point("pivotLocal") == WorldPoint(0.0, 0.0, 0.0)) { "风扇轮毂局部枢轴必须为原点" }
    val rotor = fan.getString("node")
    require(rotor in names) { "风扇叶轮缺失" }
    val soil = mapping.getJSONObject("soil")
    val soilNodes = soil.getJSONArray("nodes").stringSet()
    val soilMaterial = soil.getString("material")
    require(names.containsAll(soilNodes) && soilMaterial in materialByName) { "土壤独立材质或节点缺失" }
    val pickByMesh = buildMap<String, MutableList<SceneObject>> {
        for (type in listOf(SceneObject.PUMP, SceneObject.LAMP, SceneObject.FAN)) {
            for (mesh in actuators.getJSONObject(type.businessId).getJSONArray("pickableMeshes").stringSet()) {
                require(mesh in names) { "设备拾取网格缺失：$mesh" }
                getOrPut(mesh) { mutableListOf() }.add(type)
            }
        }
        val controller = mapping.getJSONObject("controller").getString("mesh")
        require(controller in names) { "控制器拾取网格缺失" }
        getOrPut(controller) { mutableListOf() }.add(SceneObject.CONTROLLER)
        val props = mapping.getJSONArray("demonstrationProps")
        for (i in 0 until props.length()) {
            val prop = props.getJSONObject(i)
            val objects = when (prop.getString("kind")) {
                "soilProbe" -> listOf(SceneObject.SOIL)
                "environmentHousing" -> listOf(SceneObject.TEMPERATURE, SceneObject.HUMIDITY, SceneObject.LIGHT)
                else -> emptyList()
            }
            for (mesh in prop.getJSONArray("nodes").stringSet()) {
                require(mesh in names) { "环境拾取网格缺失：$mesh" }
                getOrPut(mesh) { mutableListOf() }.addAll(objects)
            }
        }
        for (mesh in soilNodes) getOrPut(mesh) { mutableListOf() }.add(SceneObject.SOIL)
    }.mapValues { (_, values) -> values.distinct() }
    return GreenhouseAssetMapping(
        version = mapping.getString("assetVersion"),
        sha256 = expectedHash,
        modelAssetPath = modelAssetPath,
        nodeNames = names,
        cameraPosition = camera.point("position"),
        cameraTarget = camera.point("target"),
        orthographicScale = camera.getDouble("orthographicScaleMetres").also {
            require(it > 0.0) { "正交相机尺度无效" }
        },
        boundsMin = min,
        boundsMax = max,
        presets = presets,
        anchors = anchors,
        pickObjectsByMesh = pickByMesh,
        pumpFlow = pump,
        pumpFlowBaseColor = flowMaterial.baseColor(),
        lampGlow = lamp,
        fanRotorNode = rotor,
        fanPivot = fan.point("pivotGLB"),
        soilNodes = soilNodes,
        soilMaterialName = soilMaterial,
        soilBaseColor = materialByName.getValue(soilMaterial).baseColor(),
        modelBytes = bytes,
    )
}

private fun org.json.JSONArray.stringSet(): Set<String> = buildSet {
    for (i in 0 until length()) require(add(getString(i))) { "映射存在重复名称" }
}

private fun JSONObject.baseColor(): List<Float> {
    val pbr = getJSONObject("pbrMetallicRoughness")
    // 纹理土壤未显式存颜色乘数，glTF标准乘数为全白，保留其原有纹理。
    if (!pbr.has("baseColorFactor")) return listOf(1f, 1f, 1f, 1f)
    val values = pbr.getJSONArray("baseColorFactor")
    require(values.length() == 4) { "材质颜色必须有四个分量" }
    return List(4) { values.getDouble(it).toFloat().also { value ->
        require(value.isFinite()) { "材质颜色无效" }
    } }
}

private fun JSONObject.point(key: String): WorldPoint {
    val values = getJSONArray(key)
    require(values.length() == 3) { "$key 必须包含三个坐标值" }
    return WorldPoint(values.getDouble(0), values.getDouble(1), values.getDouble(2)).also {
        require(it.x.isFinite() && it.y.isFinite() && it.z.isFinite()) { "$key 坐标无效" }
    }
}
