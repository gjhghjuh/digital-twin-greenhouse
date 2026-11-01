#include "cloud.h"
#include <WiFi.h>
#include <WiFiClientSecure.h>
#include <PubSubClient.h>
#include <U8g2lib.h>
#include <atomic>
#include <mbedtls/base64.h>
#include <mbedtls/md.h>
#include <mbedtls/sha256.h>
#include <time.h>
#include <esp_timer.h>
#include "generated_config.h"

namespace {
struct Post { char payload[2048]; };
struct Result { char payload[513]; uint32_t version; };
QueueHandle_t snapshots, commands, notices, results;
std::atomic<bool> connected{false};
// 复用 Arduino-ESP32 的 TLS 建连实现，在释放上下文前保留证书诊断。
// 建连顺序参考 WiFiClientSecure.cpp（LGPL-2.1-or-later，Evandro Luis Copercini 等）。
class VerifiedTlsClient : public WiFiClientSecure {
public:
  String verification;
  String peerSubject;
  static int inspectCertificate(void *context, mbedtls_x509_crt *certificate, int depth, uint32_t *flags) {
    // 保留原有校验结果并增加固定证书校验，不清除任何失败标志。
    if (depth == 0) {
      char subject[160]; mbedtls_x509_dn_gets(subject, sizeof(subject), &certificate->subject);
      static_cast<VerifiedTlsClient *>(context)->peerSubject = subject;
      // 官方下载证书为自签服务证书；固定其 DER 指纹，不信任同名替代证书。
      const unsigned char expected[32] = {0x7d,0xe9,0xbc,0x4c,0x1a,0xed,0x01,0x34,0x46,0x67,0x64,0x56,0x78,0xd4,0x49,0x74,0x12,0x10,0xb1,0x70,0x49,0x48,0x98,0x38,0xbf,0x63,0x79,0x53,0xb3,0xe9,0x01,0x17};
      unsigned char actual[32];
      if (mbedtls_sha256_ret(certificate->raw.p, certificate->raw.len, actual, 0) || memcmp(actual, expected, 32))
        *flags |= MBEDTLS_X509_BADCERT_NOT_TRUSTED;
    }
    return 0;
  }
  int connect(const char *host, uint16_t port) override {
    verification = "";
    peerSubject = "";
    IPAddress address;
    if (!WiFi.hostByName(host, address)) { verification = "DNS_FAILED"; return 0; }
    mbedtls_ssl_conf_verify(&sslclient->ssl_conf, inspectCertificate, this);
    // 官方证书无 DNS SAN，服务身份为固定 CN；同时校验 CA、有效期与叶证书指纹。
    int ret = start_ssl_client(sslclient, address, port, "OneNET MQTTS", _timeout, _CA_cert,
        false, nullptr, nullptr, nullptr, nullptr, false, nullptr);
    _lastError = ret;
    if (ret < 0) {
      uint32_t flags = mbedtls_ssl_get_verify_result(&sslclient->ssl_ctx);
      verification = String("IP_") + address.toString() + "_VERIFY_" + String(flags, HEX);
      stop(); return 0;
    }
    _connected = true; return 1;
  }
};
VerifiedTlsClient tls;
PubSubClient mqtt(tls);
String prefix;
uint32_t messageSeq = 0;
String desiredRequest, resultRequest, deleteRequest;
uint64_t resultSent = 0;
Result pending{};
bool hasResult = false, resultAccepted = false;
uint64_t lastConnect = 0, lastDesired = 0;
String messageId() { return String(++messageSeq); }
void notice(const char *type, const String &reason) {
  CloudNotice n{};
  strlcpy(n.type, type, sizeof(n.type)); strlcpy(n.reason, reason.c_str(), sizeof(n.reason));
  xQueueSend(notices, &n, 0);
}
String encode(const String &value) {
  String out;
  const char *hex = "0123456789ABCDEF";
  for (unsigned char c : value) {
    if (isalnum(c) || c == '-' || c == '_' || c == '.' || c == '~') out += char(c);
    else { out += '%'; out += hex[c >> 4]; out += hex[c & 15]; }
  }
  return out;
}
String token() {
  String expiry = String((unsigned long)(time(nullptr) + 3600));
  String resource = String("products/") + CLOUD_PRODUCT + "/devices/" + CLOUD_DEVICE;
  String data = expiry + "\nsha256\n" + resource + "\n2018-10-31";
  unsigned char key[128], digest[32], encoded[64]; size_t keySize = 0, encodedSize = 0;
  if (mbedtls_base64_decode(key, sizeof(key), &keySize, (const unsigned char *)CLOUD_KEY, strlen(CLOUD_KEY))) return "";
  if (mbedtls_md_hmac(mbedtls_md_info_from_type(MBEDTLS_MD_SHA256), key, keySize,
      (const unsigned char *)data.c_str(), data.length(), digest)) return "";
  if (mbedtls_base64_encode(encoded, sizeof(encoded)-1, &encodedSize, digest, sizeof(digest))) return "";
  encoded[encodedSize] = 0;
  return "version=2018-10-31&res=" + encode(resource) + "&et=" + expiry + "&method=sha256&sign=" + encode((const char *)encoded);
}
bool publish(const char *suffix, JsonDocument &doc) {
  String wire; serializeJson(doc, wire);
  return mqtt.publish((prefix + suffix).c_str(), wire.c_str());
}
void getDesired() {
  JsonDocument doc; desiredRequest = messageId(); doc["id"] = desiredRequest;
  doc["version"] = "1.0"; doc["params"].to<JsonArray>().add("controlPacket");
  publish("/desired/get", doc); lastDesired = esp_timer_get_time() / 1000;
}
void callback(char *topic, byte *payload, unsigned length) {
  JsonDocument doc;
  if (length > 4096 || deserializeJson(doc, payload, length)) { notice("CLOUD_PROTOCOL_ERROR", "INVALID_JSON"); return; }
  String suffix = String(topic).substring(prefix.length());
  String id = doc["id"] | "";
  if (suffix == "/desired/get/reply" && id == desiredRequest && doc["code"] == 200) {
    JsonVariant packet = doc["data"]["controlPacket"]["value"];
    if (packet.isNull() || hasResult) return;
    if (!packet.is<const char *>() || strlen(packet.as<const char *>()) > 512 || !doc["data"]["controlPacket"]["version"].is<uint32_t>()) {
      notice("CLOUD_PROTOCOL_ERROR", "INVALID_DESIRED_ENVELOPE"); return;
    }
    CloudCommand command{}; strlcpy(command.packet, packet.as<const char *>(), sizeof(command.packet));
    command.desiredVersion = doc["data"]["controlPacket"]["version"];
    if (!command.desiredVersion || xQueueSend(commands, &command, 0) != pdTRUE) notice("CLOUD_BACKPRESSURE", "COMMAND_QUEUE_FULL");
  } else if (suffix == "/post/reply") {
    notice(doc["code"] == 200 ? "CLOUD_POST_ACCEPTED" : "CLOUD_POST_REJECTED", String("MESSAGE_") + id + "_CODE_" + doc["code"].as<int>());
    if (doc["code"] != 200) notice("CLOUD_POST_ERROR", doc["msg"] | "NO_MESSAGE");
    if (hasResult && id == resultRequest && doc["code"] == 200) resultAccepted = true;
  } else if (suffix == "/desired/delete/reply" && hasResult && id == deleteRequest) {
    notice(doc["code"] == 200 ? "DESIRED_CLEARED" : "DESIRED_CLEAR_REJECTED", String("VERSION_") + pending.version + "_CODE_" + doc["code"].as<int>());
    // 版本不匹配时保留平台的新期望值，下次重新获取，绝不无版本删除。
    hasResult = false; resultAccepted = false; deleteRequest = ""; getDesired();
  } else if (suffix == "/set") {
    // 普通属性设置不执行动作；只触发拉取带版本的期望控制包。
    JsonDocument reply; reply["id"] = id; reply["code"] = 405; reply["msg"] = "USE_DESIRED_CONTROL_PACKET";
    publish("/set_reply", reply); getDesired();
  }
}
void task(void *) {
  prefix = String("$sys/") + CLOUD_PRODUCT + "/" + CLOUD_DEVICE + "/thing/property";
  tls.setCACert(CLOUD_CA); tls.setHandshakeTimeout(8); tls.setTimeout(5);
  mqtt.setServer(CLOUD_HOST, 8883); mqtt.setBufferSize(4096); mqtt.setSocketTimeout(5); mqtt.setKeepAlive(60); mqtt.setCallback(callback);
  for (;;) {
    uint64_t now = esp_timer_get_time() / 1000;
    if (WiFi.status() != WL_CONNECTED || time(nullptr) < 1704067200) {
      if (mqtt.connected()) mqtt.disconnect(); connected = false;
      vTaskDelay(pdMS_TO_TICKS(100)); continue;
    }
    if (!mqtt.connected()) {
      if (connected.exchange(false)) notice("CLOUD_DISCONNECTED", "MQTT_CONNECTION_LOST");
      if (!lastConnect || now - lastConnect >= 10000) {
        lastConnect = now;
        String password = token();
        if (password.length() && mqtt.connect(CLOUD_DEVICE, CLOUD_PRODUCT, password.c_str())) {
          bool subscribed = true;
          for (const char *suffix : {"/post/reply", "/desired/get/reply", "/desired/delete/reply", "/set"})
            subscribed = mqtt.subscribe((prefix + suffix).c_str(), 1) && subscribed;
          if (!subscribed) { mqtt.disconnect(); notice("CLOUD_CONNECT_FAILED", "SUBSCRIBE_WRITE_FAILED"); }
          else { connected = true; notice("CLOUD_CONNECTED", "TLS_CA_SERVICE_IDENTITY_AND_PIN_VERIFIED_MQTT_ACCEPTED"); getDesired(); resultSent = 0; }
        } else {
          char error[128]; tls.lastError(error, sizeof(error));
          notice("CLOUD_CONNECT_FAILED", String("MQTT_") + mqtt.state() + "_TLS_" + error);
          if (tls.verification.length()) notice("TLS_VERIFY_DETAIL", tls.verification);
          if (tls.peerSubject.length()) notice("TLS_PEER_SUBJECT", tls.peerSubject);
        }
      }
      vTaskDelay(pdMS_TO_TICKS(20)); continue;
    }
    mqtt.loop();
    if (!hasResult && xQueueReceive(results, &pending, 0) == pdTRUE) { hasResult = true; resultAccepted = false; resultSent = 0; }
    if (hasResult && (!resultSent || now - resultSent >= 5000)) {
      JsonDocument doc;
      if (!resultAccepted) {
        resultRequest = messageId(); doc["id"] = resultRequest; doc["version"] = "1.0";
        doc["params"]["commandResult"]["value"] = pending.payload;
        publish("/post", doc);
      } else {
        deleteRequest = messageId(); doc["id"] = deleteRequest; doc["version"] = "1.0";
        doc["params"]["controlPacket"]["version"] = pending.version;
        publish("/desired/delete", doc);
      }
      resultSent = now;
    }
    Post post;
    if (xQueueReceive(snapshots, &post, 0) == pdTRUE) {
      JsonDocument doc;
      if (!deserializeJson(doc, post.payload)) { doc["id"] = messageId(); publish("/post", doc); }
    }
    if (!hasResult && now - lastDesired >= 5000) getDesired();
    vTaskDelay(pdMS_TO_TICKS(10));
  }
}
}
void cloudStart() {
  snapshots = xQueueCreate(1, sizeof(Post)); commands = xQueueCreate(2, sizeof(CloudCommand));
  notices = xQueueCreate(16, sizeof(CloudNotice)); results = xQueueCreate(2, sizeof(Result));
  if (!snapshots || !commands || !notices || !results) abort();
  if (xTaskCreatePinnedToCore(task, "onenet", 12288, nullptr, 1, nullptr, 0) != pdPASS) abort();
}
bool cloudConnected() { return connected; }
bool cloudReceive(CloudCommand &c) { return xQueueReceive(commands, &c, 0) == pdTRUE; }
bool cloudNotice(CloudNotice &n) { return xQueueReceive(notices, &n, 0) == pdTRUE; }
bool cloudResult(const char *text, uint32_t version) {
  if (strlen(text) > 512) return false;
  Result r{}; strlcpy(r.payload, text, sizeof(r.payload)); r.version = version;
  return xQueueSend(results, &r, 0) == pdTRUE;
}
void cloudSnapshot(const JsonDocument &s) {
  JsonDocument compact;
  // 紧凑字段及数组顺序在onenet-mapping.json冻结，不改变观测来源或有效性。
  compact["v"] = 1; compact["b"] = s["bootId"]; compact["q"] = s["seq"]; compact["s"] = s["stateVersion"];
  compact["t"] = s["utcMs"]; compact["u"] = s["uptimeMs"]; compact["c"] = s["configVersion"]; compact["m"] = s["modelVersion"];
  JsonArray values = compact["o"].to<JsonArray>();
  for (const char *name : {"temperature", "humidity", "lightLevel", "soilHumidity"}) {
    JsonArray observation = values.add<JsonArray>();
    observation.add(s[name]["value"]);
    observation.add(s[name]["validity"] == "VALID" ? 1 : (s[name]["validity"] == "NOT_READY" ? 0 : 2));
    observation.add(s[name]["sampledAtUtcMs"]); observation.add(s[name]["uptimeMs"]);
  }
  JsonArray states = compact["a"].to<JsonArray>();
  for (const char *name : {"pump", "lamp", "fan"}) states.add(s[name]);
  compact["mode"] = 0;
  compact["raw"] = s["lightAdcRaw"]; compact["adc"] = s["lightAdcFiltered"];
  compact["w"] = s["wifiConnected"]; compact["net"] = s["cloudConnected"];
  compact["cmd"] = s["lastCommandId"];
  String packed; serializeJson(compact, packed);
  if (packed.length() > 512) { notice("CLOUD_PROTOCOL_ERROR", "COMPACT_SNAPSHOT_EXCEEDS_512"); return; }
  JsonDocument doc; doc["version"] = "1.0";
  for (const char *name : {"temperature", "humidity", "lightLevel", "soilHumidity"})
    if (s[name]["validity"] == "VALID") doc["params"][name]["value"] = s[name]["value"];
  // OneNET 对小数步长执行二进制浮点余数校验，会拒绝部分两位小数。
  // 标量按整数百分比上报以稳定落在 0.01 步长网格；完整快照保留模型原始精度。
  if (s["soilHumidity"]["validity"] == "VALID")
    doc["params"]["soilHumidity"]["value"] = round(s["soilHumidity"]["value"].as<double>());
  doc["params"]["snapshot"]["value"] = packed;
  Post post{};
  if (measureJson(doc) >= sizeof(post.payload)) { notice("CLOUD_PROTOCOL_ERROR", "POST_TOO_LARGE"); return; }
  serializeJson(doc, post.payload, sizeof(post.payload)); xQueueOverwrite(snapshots, &post);
}
