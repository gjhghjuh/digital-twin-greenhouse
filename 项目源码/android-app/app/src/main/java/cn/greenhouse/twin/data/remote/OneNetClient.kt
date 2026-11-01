package cn.greenhouse.twin.data.remote

import android.util.Base64
import android.os.SystemClock
import cn.greenhouse.twin.data.local.OneNetSettings
import cn.greenhouse.twin.data.model.CompactSnapshot
import cn.greenhouse.twin.data.model.CompactSnapshotParser
import cn.greenhouse.twin.data.model.CommandReceipt
import cn.greenhouse.twin.BuildConfig
import cn.greenhouse.twin.domain.DeviceConnection
import java.net.URL
import java.net.URLEncoder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import org.json.JSONArray

data class OneNetReading(
    val snapshot: CompactSnapshot?,
    val connection: DeviceConnection,
    val cloudReceivedAtUtcMs: Long?,
    val serverUtcMs: Long,
    val serverReceivedElapsedMs: Long,
    val commandReceipt: CommandReceipt? = null,
    val desiredPacket: DesiredPacket? = null,
)

data class DesiredPacket(val packet: String, val version: String)

class OneNetQueryException(message: String) : Exception(message)

// 仅调用已核验的中国区 HTTPS API，不关闭证书检查、不切换旧平台接口。
class OneNetClient {
    suspend fun query(settings: OneNetSettings, accessKey: String): OneNetReading = withContext(Dispatchers.IO) {
        val authorization = token(settings, accessKey)
        val query = "product_id=${encode(settings.productId)}&device_name=${encode(settings.deviceName)}"
        val (details, _) = get("device/detail", query, authorization)
        val data = details.getJSONObject("data")
        require(data.getString("pid") == settings.productId && data.getString("name") == settings.deviceName) {
            "云端返回了其他产品或设备"
        }
        val connection = when (data.getInt("status")) {
            0 -> DeviceConnection.OFFLINE
            1 -> DeviceConnection.ONLINE
            2 -> DeviceConnection.UNKNOWN
            else -> throw OneNetQueryException("云端设备状态编码不支持")
        }
        coroutineContext.ensureActive()
        val (properties, serverUtc) = get("thingmodel/query-device-property", query, authorization)
        val serverReceivedElapsed = SystemClock.elapsedRealtime()
        val list = properties.get("data")
        if (list !is JSONArray) throw OneNetQueryException("最新属性 data 类型未核验：${list.javaClass.simpleName}")
        val snapshots = (0 until list.length()).map { list.getJSONObject(it) }
            .filter { it.getString("identifier") == "snapshot" }
        require(snapshots.size <= 1) { "云端返回了多个同名快照属性" }
        val property = snapshots.singleOrNull()
        val results = (0 until list.length()).map { list.getJSONObject(it) }
            .filter { it.getString("identifier") == "commandResult" }
        require(results.size <= 1) { "云端返回多个命令结果属性" }
        val resultValue = results.singleOrNull()?.takeUnless { it.isNull("value") }?.getString("value")
        val receipt = resultValue?.takeIf { it.isNotBlank() }?.let { CommandReceipt.parse(it) }
        val desired = queryDesired(settings, accessKey)
        if (property == null || property.isNull("value") || property.getString("value").isBlank()) {
            return@withContext OneNetReading(null, connection, null, serverUtc, serverReceivedElapsed, receipt, desired)
        }
        val cloudTime = property.getString("time").toLong()
        require(cloudTime > 0) { "云端快照上报时间无效" }
        OneNetReading(CompactSnapshotParser.parse(property.getString("value"), settings.deviceName),
            connection, cloudTime, serverUtc, serverReceivedElapsed, receipt, desired)
    }

    suspend fun queryDesired(settings: OneNetSettings, accessKey: String): DesiredPacket? = withContext(Dispatchers.IO) {
        val body = deviceBody(settings).put("params", JSONArray().put("controlPacket"))
        val (response, _) = request("thingmodel/query-device-desired-property", "", token(settings, accessKey), body)
        // 仅调试版记录非秘密的响应结构，用实际中国区接口核验，不猜香港或旧平台结构。
        if (BuildConfig.DEBUG) android.util.Log.i("GreenhouseControl", "desired-response=${response.get("data")}")
        val data = response.getJSONObject("data")
        // 中国区实机接口无期望属性返回空对象，有值时直接以属性标识作为键。
        if (data.length() == 0) return@withContext null
        require(data.keys().asSequence().toSet() == setOf("controlPacket")) { "期望属性响应字段不一致" }
        val desired = data.getJSONObject("controlPacket")
        val value = desired.get("value")
        require(value is String) { "期望控制包必须是JSON字符串" }
        if (value.isBlank()) return@withContext null
        val version = desired.get("version").toString()
        require(version.matches(Regex("[0-9]+"))) { "期望属性版本格式不一致" }
        DesiredPacket(value, version)
    }

    suspend fun submit(settings: OneNetSettings, accessKey: String, packet: String,
        beforeWrite: () -> Boolean): Unit = withContext(Dispatchers.IO) {
        require(packet.toByteArray(Charsets.UTF_8).size <= 512) { "控制包超过512字节" }
        request("thingmodel/set-device-desired-property", "", token(settings, accessKey),
            deviceBody(settings).put("params", JSONObject().put("controlPacket", packet)), beforeWrite)
        Unit
    }

    private fun deviceBody(settings: OneNetSettings) = JSONObject()
        .put("product_id", settings.productId).put("device_name", settings.deviceName)

    private suspend fun get(path: String, query: String, authorization: String): Pair<JSONObject, Long> {
        return request(path, query, authorization, null)
    }

    private suspend fun request(path: String, query: String, authorization: String, body: JSONObject?,
        beforeWrite: (() -> Boolean)? = null): Pair<JSONObject, Long> {
        val suffix = if (query.isEmpty()) "" else "?$query"
        val connection = URL("https://iot-api.heclouds.com/$path$suffix").openConnection() as HttpsURLConnection
        return suspendCancellableCoroutine { continuation ->
            // 取消同步立即关闭在途连接，禁止后台请求等待超时后继续改变状态。
            continuation.invokeOnCancellation { connection.disconnect() }
            if (!continuation.isActive) return@suspendCancellableCoroutine
            try {
                connection.connectTimeout = 8_000
                connection.readTimeout = 8_000
                connection.instanceFollowRedirects = false
                connection.requestMethod = if (body == null) "GET" else "POST"
                connection.setRequestProperty("Authorization", authorization)
                connection.setRequestProperty("Accept", "application/json")
                if (body != null) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    val bytes = body.toString().toByteArray(Charsets.UTF_8)
                    connection.setFixedLengthStreamingMode(bytes.size)
                    if (!continuation.isActive) return@suspendCancellableCoroutine
                    if (beforeWrite != null && !beforeWrite()) throw OneNetQueryException("实时控制上下文已改变，未发送控制包")
                    connection.outputStream.use {
                        if (!continuation.isActive) return@use
                        if (beforeWrite != null && !beforeWrite()) throw OneNetQueryException("实时控制上下文已改变，未发送控制包")
                        it.write(bytes)
                    }
                }
                if (!continuation.isActive) return@suspendCancellableCoroutine
                val status = connection.responseCode
                if (status != 200) throw OneNetQueryException("OneNET HTTP $status")
                val response = JSONObject(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
                val code = response.getInt("code")
                if (code != 0) throw OneNetQueryException("OneNET 返回错误码 $code")
                val serverUtc = connection.getHeaderFieldDate("Date", -1)
                if (serverUtc <= 0) throw OneNetQueryException("云端未提供可信响应时间")
                continuation.resume(response to serverUtc)
            } catch (error: Exception) {
                continuation.resumeWithException(error)
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun token(settings: OneNetSettings, accessKey: String): String {
        val expiry = System.currentTimeMillis() / 1000 + 600
        val resource = "products/${settings.productId}"
        val bytes = Base64.decode(accessKey, Base64.DEFAULT)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(bytes, "HmacSHA256"))
        bytes.fill(0)
        val signature = Base64.encodeToString(mac.doFinal("$expiry\nsha256\n$resource\n2018-10-31".toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        return "version=2018-10-31&res=${encode(resource)}&et=$expiry&method=sha256&sign=${encode(signature)}"
    }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
