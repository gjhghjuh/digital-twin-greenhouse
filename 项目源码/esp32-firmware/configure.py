"""从同一份契约生成固件常量，缺少实测屏幕参数时明确阻止烧录。"""
Import("env")
import json
from pathlib import Path

root = Path(env["PROJECT_DIR"])
config = json.loads((root.parent / "contracts" / "runtime.json").read_text(encoding="utf-8"))
hardware = config["hardware"]
driver = hardware["oledDriver"]
address = hardware["oledAddress"]
if not driver or address is None:
    raise RuntimeError("缺少已验证 OLED 构造类和 I2C 地址：请填写 contracts/runtime.json，不猜测驱动")
if not isinstance(driver, str) or not driver.startswith("U8G2_") or not driver.replace("_", "").isalnum():
    raise ValueError("oledDriver 必须是经验证的 U8g2 构造类名")
if type(address) is not int or not 8 <= address <= 119:
    raise ValueError("OLED地址必须是7位I2C整数")
out = root / "include" / "generated_config.h"
out.parent.mkdir(exist_ok=True)
lines = ["#pragma once", "// 由统一契约自动生成，请修改 contracts/runtime.json。", f"using DisplayDriver = {driver};", f"constexpr unsigned OLED_ADDRESS = {address};"]
names = {"ds18b20":"TEMP_PIN", "dht11":"HUMIDITY_PIN", "adc":"ADC_PIN", "oledScl":"OLED_SCL", "oledSda":"OLED_SDA", "buzzer":"BUZZER_PIN"}
for source, target in names.items():
    lines.append(f"constexpr unsigned {target} = {hardware[source]};")
for name, pin in hardware["leds"].items():
    lines.append(f"constexpr unsigned {name.upper()}_PIN = {pin};")
lines += [f"constexpr bool LED_ACTIVE_HIGH = {str(hardware['ledActiveHigh']).lower()};", f"constexpr unsigned SAMPLE_MS = {config['timing']['sampleMs']};", f"constexpr unsigned SNAPSHOT_MS = {config['timing']['snapshotMs']};", f"constexpr unsigned ADC_SAMPLES = {config['light']['averageSamples']};", f"constexpr unsigned ADC_HYSTERESIS = {config['light']['hysteresisCounts']};", f"constexpr unsigned ADC_MIN = {config['light']['adcMin']};", f"constexpr unsigned ADC_MAX = {config['light']['adcMax']};", f"constexpr float SOIL_INITIAL = {config['soil']['initial']}f;", f"constexpr float SOIL_EVAPORATION = {config['soil']['evaporationPerSecond']}f;", f"constexpr float SOIL_PUMP_INCREASE = {config['soil']['pumpIncreasePerSecond']}f;"]
content = "\n".join(lines) + "\n"
network = json.loads((root / "network.local.json").read_text(encoding="utf-8"))
if not isinstance(network.get("ssid"), str) or not network["ssid"]:
    raise ValueError("network.local.json 缺少有效的 ssid")
if not isinstance(network.get("password"), str):
    raise ValueError("network.local.json 缺少有效的 password")
content += "// Wi-Fi 来自用户确认的本地配置，不提交版本库。\n"
content += "constexpr const char *WIFI_SSID = " + json.dumps(network["ssid"], ensure_ascii=True) + ";\n"
content += "constexpr const char *WIFI_PASSWORD = " + json.dumps(network["password"], ensure_ascii=True) + ";\n"
cloud = json.loads((root / "cloud.local.json").read_text(encoding="utf-8"))
for key, symbol in (("productId", "CLOUD_PRODUCT"), ("deviceName", "CLOUD_DEVICE"), ("deviceKey", "CLOUD_KEY"), ("host", "CLOUD_HOST")):
    if not isinstance(cloud.get(key), str) or not cloud[key]:
        raise ValueError(f"cloud.local.json 缺少 {key}")
    content += f"constexpr const char *{symbol} = " + json.dumps(cloud[key]) + ";\n"
if cloud["port"] != 8883:
    raise ValueError("只允许已核实的 TLS 端口8883")
content += 'constexpr const char *CLOUD_CA = R"CERT(' + (root / "certificate.pem").read_text(encoding="ascii") + ')CERT";\n'
if not out.exists() or out.read_text(encoding="utf-8") != content:
    out.write_text(content, encoding="utf-8")
