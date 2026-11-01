#include <Arduino.h>
#include <ArduinoJson.h>
#include <DallasTemperature.h>
#include <DHT.h>
#include <OneWire.h>
#include <U8g2lib.h>
#include <Wire.h>
#include <WiFi.h>
#include <esp_system.h>
#include <esp_timer.h>
#include <esp_task_wdt.h>
#include <time.h>
#include "generated_config.h"
#include "cloud.h"
#include "command_store.h"

// 本地采样和归档始终由主循环调度，不等待网络连接。
OneWire oneWire(TEMP_PIN);
DallasTemperature temperatures(&oneWire);
DHT humiditySensor(HUMIDITY_PIN, DHT11);
DisplayDriver display(U8G2_R0, U8X8_PIN_NONE, OLED_SCL, OLED_SDA);

struct Observation {
  float value = NAN;
  const char *source;
  const char *validity = "NOT_READY";
  uint64_t uptime = 0;
  int64_t utc = 0;
};
Observation temperature{NAN, "SENSOR"};
Observation humidity{NAN, "SENSOR"};
Observation light{NAN, "EXPERIMENT"};
Observation soil{NAN, "SIMULATION"};
bool actuators[3] = {false, false, false};
const unsigned pins[3] = {PUMP_PIN, LAMP_PIN, FAN_PIN};
const char *names[3] = {"pump", "lamp", "fan"};
String deviceId, bootId;
uint64_t snapshotSeq = 0, eventSeq = 0, stateVersion = 0;
uint64_t lastSample = 0, lastSnapshot = 0, modelAt = 0, conversionAt = 0, buzzerUntil = 0;
bool converting = false;
int adcRaw = 0, adcFiltered = 0, lightLevel = -1;
String serialLine;
bool discardLine = false;
bool reportedWifi = false, reportedTime = false;
uint64_t lastWifiAttempt = 0;
uint64_t wifiPauseUntil = 0;
QueueHandle_t wifiEvents;
CommandStore commandStore;
bool commandStorageReady = false;
String lastCommandId, lastCommandResult;

uint64_t uptimeMs() { return esp_timer_get_time() / 1000; }
int64_t utcMs() {
  timeval tv;
  gettimeofday(&tv, nullptr);
  return tv.tv_sec >= 1704067200 ? int64_t(tv.tv_sec) * 1000 + tv.tv_usec / 1000 : 0;
}
template <typename T> void nullableTime(T field, int64_t value) {
  if (value) field.set(value); else field.set(nullptr);
}
void base(JsonDocument &doc, const char *kind, uint64_t sequence) {
  doc["schemaVersion"] = 1;
  doc["kind"] = kind;
  doc["deviceId"] = deviceId;
  doc["bootId"] = bootId;
  doc["seq"] = sequence;
  doc["stateVersion"] = stateVersion;
  const int64_t now = utcMs();
  nullableTime(doc["utcMs"], now);
  doc["uptimeMs"] = uptimeMs();
  doc["timeQuality"] = now ? "SYNCED" : "UNSYNCED";
  doc["configVersion"] = 1;
  doc["modelVersion"] = 1;
}
void emit(JsonDocument &doc) {
  Serial.print("DATA ");
  serializeJson(doc, Serial);
  Serial.println();
}
void event(const char *type, const char *target, JsonVariantConst oldValue,
           JsonVariantConst newValue, const char *reason, const char *actor = "device", const char *commandId = "") {
  JsonDocument doc;
  base(doc, "event", ++eventSeq);
  doc["eventId"] = bootId + ":" + String((unsigned long)eventSeq);
  doc["eventType"] = type;
  doc["target"] = target;
  doc["oldValue"] = oldValue;
  doc["newValue"] = newValue;
  doc["commandId"] = commandId;
  doc["reason"] = reason;
  doc["operator"] = actor;
  emit(doc);
}
void simpleEvent(const char *type, const char *reason) {
  event(type, "device", JsonVariantConst(), JsonVariantConst(), reason);
}
void updateObservation(Observation &obs, float value) {
  obs.value = value;
  obs.validity = isfinite(value) ? "VALID" : "READ_ERROR";
  obs.uptime = uptimeMs();
  obs.utc = utcMs();
}
void putObservation(JsonObject obj, const Observation &obs, bool integral = false) {
  if (isfinite(obs.value)) {
    if (integral) obj["value"] = int(obs.value); else obj["value"] = obs.value;
  } else obj["value"] = nullptr;
  obj["source"] = obs.source;
  obj["validity"] = obs.validity;
  nullableTime(obj["sampledAtUtcMs"], obs.utc);
  obj["uptimeMs"] = obs.uptime;
}
void snapshot() {
  JsonDocument doc;
  base(doc, "snapshot", ++snapshotSeq);
  putObservation(doc["temperature"].to<JsonObject>(), temperature);
  putObservation(doc["humidity"].to<JsonObject>(), humidity);
  putObservation(doc["lightLevel"].to<JsonObject>(), light, true);
  putObservation(doc["soilHumidity"].to<JsonObject>(), soil);
  doc["lightAdcRaw"] = adcRaw;
  doc["lightAdcFiltered"] = adcFiltered;
  for (int i = 0; i < 3; ++i) {
    doc[names[i]] = actuators[i];
    doc[String(names[i]) + "Mode"] = "MANUAL";
  }
  // 网络连接与云端鉴权是独立状态，尚未鉴权时云端始终为未连接。
  doc["wifiConnected"] = WiFi.status() == WL_CONNECTED;
  doc["cloudConnected"] = cloudConnected();
  doc["lastCommandId"] = lastCommandId;
  doc["lastCommandResult"] = lastCommandResult;
  emit(doc);
  cloudSnapshot(doc);
}
void advanceModel() {
  uint64_t now = uptimeMs();
  soil.value = constrain(soil.value + (-SOIL_EVAPORATION + (actuators[0] ? SOIL_PUMP_INCREASE : 0)) * ((now - modelAt) / 1000.0f), 0.0f, 100.0f);
  modelAt = now;
  updateObservation(soil, soil.value);
}
void renderDisplay() {
  display.clearBuffer();
  display.setFont(u8g2_font_5x7_tf);
  char line[64];
  snprintf(line, sizeof(line), "M0 WIFI %s CLOUD %s", WiFi.status() == WL_CONNECTED ? "ON" : "OFF", cloudConnected() ? "ON" : "OFF");
  display.drawStr(0, 8, line);
  if (isfinite(temperature.value)) snprintf(line, sizeof(line), "Temp %.2f C SENSOR", temperature.value);
  else snprintf(line, sizeof(line), "Temp -- %s", temperature.validity);
  display.drawStr(0, 18, line);
  if (isfinite(humidity.value)) snprintf(line, sizeof(line), "RH %.1f %% SENSOR", humidity.value);
  else snprintf(line, sizeof(line), "RH -- %s", humidity.validity);
  display.drawStr(0, 28, line);
  snprintf(line, sizeof(line), "Light %d/7 EXP ADC %d", lightLevel, adcFiltered);
  display.drawStr(0, 38, line);
  snprintf(line, sizeof(line), "Soil %.2f %% SIM", soil.value);
  display.drawStr(0, 48, line);
  snprintf(line, sizeof(line), "P%d L%d F%d MANUAL %lus", actuators[0], actuators[1], actuators[2], (unsigned long)(uptimeMs() / 1000));
  display.drawStr(0, 60, line);
  display.sendBuffer();
}
void sample() {
  advanceModel();
  float h = humiditySensor.readHumidity();
  updateObservation(humidity, isfinite(h) && h >= 0 && h <= 100 ? h : NAN);
  adcRaw = analogRead(ADC_PIN);
  unsigned total = 0;
  for (unsigned i = 0; i < ADC_SAMPLES; ++i) total += analogRead(ADC_PIN);
  adcFiltered = total / ADC_SAMPLES;
  int next = constrain((adcFiltered - int(ADC_MIN)) * 8 / int(ADC_MAX - ADC_MIN + 1), 0, 7);
  if (lightLevel < 0) lightLevel = next;
  const int width = (ADC_MAX - ADC_MIN + 1) / 8;
  if ((next > lightLevel && adcFiltered >= int(ADC_MIN) + (lightLevel + 1) * width + int(ADC_HYSTERESIS)) ||
      (next < lightLevel && adcFiltered < int(ADC_MIN) + lightLevel * width - int(ADC_HYSTERESIS))) {
    JsonDocument change;
    change["old"] = lightLevel;
    change["new"] = next;
    lightLevel = next;
    ++stateVersion;
    event("LIGHT_LEVEL_CHANGED", "lightLevel", change["old"], change["new"], "POTENTIOMETER_EXPERIMENT", "physical-input");
  }
  updateObservation(light, lightLevel);
  temperatures.requestTemperatures();
  conversionAt = uptimeMs();
  converting = true;
  renderDisplay();
}
void diagnostic(const String &line) {
  JsonDocument command;
  if (deserializeJson(command, line) || command["scope"] != "LOCAL_DIAGNOSTIC" || !command["operator"].is<const char *>()) {
    simpleEvent("DIAGNOSTIC_REJECTED", "INVALID_SCOPE_OR_OPERATOR");
    return;
  }
  const char *actor = command["operator"];
  const char *operation = command["operation"] | "";
  if (!strlen(actor) || strlen(actor) > 64) {
    simpleEvent("DIAGNOSTIC_REJECTED", "INVALID_OPERATOR"); return;
  }
  if (!strcmp(operation, "setActuator")) {
    const char *target = command["target"] | "";
    for (int i = 0; i < 3; ++i) {
      if (!strcmp(target, names[i]) && command["state"].is<bool>()) {
        advanceModel();
        JsonDocument change;
        change["old"] = actuators[i];
        change["new"] = command["state"].as<bool>();
        actuators[i] = command["state"];
        digitalWrite(pins[i], actuators[i] == LED_ACTIVE_HIGH ? HIGH : LOW);
        ++stateVersion;
        event("LOCAL_ACTUATOR_SET", target, change["old"], change["new"], "LOCAL_DIAGNOSTIC_ONLY", actor);
        renderDisplay();
        return;
      }
    }
  } else if (!strcmp(operation, "injectSoil") && command["value"].is<float>()) {
    float value = command["value"];
    if (isfinite(value) && value >= 0 && value <= 100) {
      advanceModel();
      JsonDocument change;
      change["old"] = soil.value; change["new"] = value;
      updateObservation(soil, value);
      ++stateVersion;
      event("SOIL_INJECTED", "soilHumidity", change["old"], change["new"], "SIMULATION_INJECTION", actor);
      renderDisplay();
      return;
    }
  } else if (!strcmp(operation, "beep")) {
    ledcWriteTone(0, 2000);
    buzzerUntil = uptimeMs() + 250;
    simpleEvent("LOCAL_BUZZER", "LOCAL_DIAGNOSTIC_250MS");
    return;
  } else if (!strcmp(operation, "pauseWifi") && command["durationMs"].is<unsigned>()) {
    unsigned duration = command["durationMs"];
    if (duration >= 10000 && duration <= 120000 && !wifiPauseUntil) {
      // 仅串口诊断暂停本机网络，不阻塞采样，不修改凭据，到期恢复正常连接流程。
      wifiPauseUntil = uptimeMs() + duration;
      WiFi.disconnect(false, false);
      event("LOCAL_WIFI_PAUSED", "wifi", JsonVariantConst(), JsonVariantConst(), "BOUNDED_LOCAL_DIAGNOSTIC", actor);
      return;
    }
  } else if (!strcmp(operation, "snapshot")) {
    snapshot(); return;
  }
  simpleEvent("DIAGNOSTIC_REJECTED", "INVALID_OPERATION_OR_VALUE");
}
void remoteCommand(const CloudCommand &incoming) {
  JsonDocument command;
  DeserializationError error = deserializeJson(command, incoming.packet);
  const char *id = command["commandId"] | "";
  const char *target = command["target"] | "";
  const char *result = "INVALID_PACKET";
  bool duplicate = false;
  int index = -1;
  for (int i = 0; i < 3; ++i) if (!strcmp(target, names[i])) index = i;
  int64_t issued = command["issuedAtUtcMs"] | int64_t(0);
  int64_t expires = command["expiresAtUtcMs"] | int64_t(0);
  int64_t now = utcMs();
  bool shape = !error && command.is<JsonObject>() && command.size() == 8 && *id && strlen(id) <= 64 &&
    command["targetBootId"].is<const char *>() && command["issuedAtUtcMs"].is<int64_t>() &&
    command["expiresAtUtcMs"].is<int64_t>() && command["configVersion"].is<int>() &&
    command["mode"].is<const char *>() && command["state"].is<bool>() && index >= 0;
  for (const char *p = id; *p; ++p)
    if (!((*p >= 'a' && *p <= 'z') || (*p >= 'A' && *p <= 'Z') || (*p >= '0' && *p <= '9') || strchr("._:-", *p))) shape = false;
  if (shape) {
    const CommandReceipt *previous = commandStore.find(id);
    if (!commandStorageReady) result = "STORAGE_UNAVAILABLE";
    else if (previous) { result = previous->result; duplicate = true; }
    else if (!now) result = "TIME_UNSYNCED";
    else if (command["targetBootId"] != bootId) result = "BOOT_MISMATCH";
    else if (command["configVersion"] != 1) result = "CONFIG_MISMATCH";
    else if (command["mode"] != "MANUAL") result = "UNSUPPORTED_MODE";
    else if (issued <= 0 || expires <= issued || expires-issued > 300000 || issued > now) result = "INVALID_TIME_WINDOW";
    else if (expires <= now) result = "EXPIRED";
    else if (issued <= commandStore.highWater()) result = "STALE_ISSUED_TIME";
    else if (!commandStore.receive(id, issued)) { result = "STORAGE_WRITE_FAILED"; commandStorageReady = false; }
    else {
      advanceModel();
      JsonDocument change; change["old"] = actuators[index]; change["new"] = command["state"];
      actuators[index] = command["state"];
      digitalWrite(pins[index], actuators[index] == LED_ACTIVE_HIGH ? HIGH : LOW);
      ++stateVersion;
      result = "EXECUTED";
      if (!commandStore.finish(id, result, stateVersion)) { result = "RESULT_STORAGE_FAILED"; commandStorageReady = false; }
      event("REMOTE_ACTUATOR_SET", target, change["old"], change["new"], result, "onenet-desired", id);
      renderDisplay();
    }
  }
  // 可解析的拒绝结果也落盘，同一commandId日后不能换包或等时间流逝后执行。
  if (shape && commandStorageReady && !commandStore.find(id)) {
    if (!commandStore.receive(id, commandStore.highWater()) || !commandStore.finish(id, result, stateVersion)) {
      commandStorageReady = false; result = "STORAGE_WRITE_FAILED";
    }
  }
  lastCommandId = String(id).substring(0, 64); lastCommandResult = result;
  JsonDocument receipt;
  receipt["commandId"] = lastCommandId; receipt["bootId"] = bootId;
  receipt["result"] = result; receipt["duplicate"] = duplicate;
  receipt["stateVersion"] = stateVersion; nullableTime(receipt["utcMs"], now);
  for (int i = 0; i < 3; ++i) receipt[names[i]] = actuators[i];
  String wire; serializeJson(receipt, wire);
  if (!cloudResult(wire.c_str(), incoming.desiredVersion)) simpleEvent("CLOUD_BACKPRESSURE", "RESULT_QUEUE_FULL_DESIRED_RETAINED");
  event(duplicate ? "COMMAND_DUPLICATE" : "COMMAND_RESULT", target, JsonVariantConst(), JsonVariantConst(), result, "onenet-desired", lastCommandId.c_str());
}
void setup() {
  Serial.begin(115200);
  // 云端和串口共用逻辑设备名，板卡更换后物理 MAC 单独记录。
  deviceId = CLOUD_DEVICE;
  char id[40];
  uint32_t random[4]; esp_fill_random(random, sizeof(random));
  snprintf(id, sizeof(id), "%08x%08x%08x%08x", random[0], random[1], random[2], random[3]);
  bootId = id;
  for (unsigned pin : pins) { digitalWrite(pin, LED_ACTIVE_HIGH ? LOW : HIGH); pinMode(pin, OUTPUT); }
  ledcSetup(0, 2000, 8); ledcAttachPin(BUZZER_PIN, 0); ledcWrite(0, 0);
  Wire.begin(OLED_SDA, OLED_SCL);
  Wire.setTimeOut(30);
  display.setI2CAddress(OLED_ADDRESS << 1);
  display.begin();
  temperatures.begin(); temperatures.setResolution(12); temperatures.setWaitForConversion(false);
  humiditySensor.begin();
  analogReadResolution(12); analogSetPinAttenuation(ADC_PIN, ADC_11db);
  updateObservation(soil, SOIL_INITIAL); modelAt = uptimeMs();
  WiFi.persistent(false);
  wifiEvents = xQueueCreate(8, sizeof(uint16_t));
  WiFi.onEvent([](WiFiEvent_t type, WiFiEventInfo_t info) {
    if (type == ARDUINO_EVENT_WIFI_STA_DISCONNECTED && wifiEvents) {
      uint16_t reason = info.wifi_sta_disconnected.reason;
      xQueueSend(wifiEvents, &reason, 0);
    }
  });
  WiFi.mode(WIFI_STA);
  WiFi.setAutoReconnect(false);
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
  lastWifiAttempt = uptimeMs();
  // SNTP 在系统网络任务运行，不阻塞采样与串口归档。
  configTime(0, 0, "ntp.aliyun.com", "cn.pool.ntp.org");
  commandStorageReady = commandStore.begin();
  cloudStart();
  simpleEvent("BOOT", "OUTPUTS_OFF_NEW_BOOT_ID");
  simpleEvent("MODEL_RESET", "SOIL_50_PERCENT_SIMULATION");
  simpleEvent("TIME_UNSYNCED", "WAITING_FOR_SNTP");
  snapshot();
  lastSnapshot = uptimeMs(); lastSample = uptimeMs();
  renderDisplay();
  esp_task_wdt_init(10, true);
  esp_task_wdt_add(nullptr);
}
void loop() {
  uint64_t now = uptimeMs();
  CloudNotice notice;
  if (cloudNotice(notice)) simpleEvent(notice.type, notice.reason);
  CloudCommand incoming;
  if (cloudReceive(incoming)) remoteCommand(incoming);
  uint16_t wifiReason;
  if (wifiEvents && xQueueReceive(wifiEvents, &wifiReason, 0) == pdTRUE) {
    String reason = String("ESP_WIFI_REASON_") + wifiReason;
    simpleEvent("WIFI_CONNECT_FAILED", reason.c_str());
  }
  bool wifiNow = WiFi.status() == WL_CONNECTED;
  if (wifiNow != reportedWifi) {
    reportedWifi = wifiNow;
    simpleEvent(wifiNow ? "WIFI_CONNECTED" : "WIFI_DISCONNECTED", "NETWORK_STATUS_CHANGED");
    renderDisplay();
  }
  if (wifiPauseUntil && now >= wifiPauseUntil) {
    wifiPauseUntil = 0;
    lastWifiAttempt = now;
    WiFi.reconnect();
    simpleEvent("LOCAL_WIFI_RESUMED", "DIAGNOSTIC_PAUSE_ENDED");
  }
  if (!wifiNow && !wifiPauseUntil && now - lastWifiAttempt >= 30000) {
    lastWifiAttempt = now;
    WiFi.reconnect();
  }
  if (!reportedTime && utcMs()) {
    reportedTime = true;
    simpleEvent("TIME_SYNCED", "SNTP_UTC_AVAILABLE");
  }
  // 每轮限制串口工作量，持续输入不会饿死采样和模型调度。
  for (unsigned n = 0; n < 256 && Serial.available(); ++n) {
    char c = Serial.read();
    if (c == '\n') {
      if (!discardLine && serialLine.length()) diagnostic(serialLine);
      serialLine = ""; discardLine = false;
    } else if (!discardLine && c != '\r') {
      if (serialLine.length() >= 1024) { serialLine = ""; discardLine = true; simpleEvent("DIAGNOSTIC_REJECTED", "LINE_TOO_LONG"); }
      else serialLine += c;
    }
  }
  if (converting && now - conversionAt >= 750) {
    float t = temperatures.getTempCByIndex(0);
    updateObservation(temperature, t != DEVICE_DISCONNECTED_C && isfinite(t) && t >= -55 && t <= 125 ? t : NAN);
    converting = false;
    renderDisplay();
  }
  if (now - lastSample >= SAMPLE_MS) { lastSample = now; sample(); }
  if (now - lastSnapshot >= SNAPSHOT_MS) { lastSnapshot = now; snapshot(); }
  if (buzzerUntil && now >= buzzerUntil) { ledcWriteTone(0, 0); buzzerUntil = 0; }
  esp_task_wdt_reset();
  delay(1);
}
