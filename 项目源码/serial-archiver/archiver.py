"""独占串口，校验设备记录并持久化到按 UTC 接收日期划分的 CSV。"""

import argparse
import csv
import json
import os
import queue
import sqlite3
import sys
import threading
import time
from datetime import datetime, timezone
from pathlib import Path

import serial
from serial.tools import list_ports
from jsonschema import Draft202012Validator

CONTRACTS = Path(__file__).resolve().parents[1] / "contracts"
RUNTIME = json.loads((CONTRACTS / "runtime.json").read_text(encoding="utf-8"))
COLUMNS = json.loads((CONTRACTS / "csv-columns.json").read_text(encoding="utf-8"))


def utc_ms():
    return time.time_ns() // 1_000_000


def cell(record, name):
    value = record
    for part in name.split("."):
        value = value.get(part) if isinstance(value, dict) else None
    if value is None:
        return ""
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, (dict, list)):
        return json.dumps(value, ensure_ascii=False, separators=(",", ":"))
    return value


class Archive:
    def __init__(self, root):
        self.root = root
        root.mkdir(parents=True, exist_ok=True)
        # 排他锁防止多个进程同时改写同一归档，SQLite保存已接收记录用于崩溃恢复。
        self.lock = open(root / "archive.lock", "a+b")
        import msvcrt
        self.lock.seek(0)
        self.lock.write(b"0")
        self.lock.flush()
        self.lock.seek(0)
        msvcrt.locking(self.lock.fileno(), msvcrt.LK_NBLCK, 1)
        self.db = sqlite3.connect(root / "archive.sqlite3")
        self.db.execute("PRAGMA journal_mode=WAL")
        self.db.execute("PRAGMA synchronous=FULL")
        self.db.execute("CREATE TABLE IF NOT EXISTS records (device TEXT, boot TEXT, kind TEXT, seq INTEGER, day TEXT, payload TEXT, PRIMARY KEY(device,boot,kind,seq))")
        self.db.commit()
        self.validator = Draft202012Validator(json.loads((CONTRACTS / "record.schema.json").read_text(encoding="utf-8")))
        self.last_written = None
        self.last_snapshot = {}
        for (payload,) in self.db.execute("SELECT payload FROM records WHERE rowid IN (SELECT MAX(rowid) FROM records WHERE kind='snapshot' GROUP BY device)"):
            record = json.loads(payload)
            self.last_snapshot[record["deviceId"]] = record
        self.last_written = self.db.execute("SELECT MAX(json_extract(payload,'$.receivedAtUtcMs')) FROM records").fetchone()[0]
        # 从已提交的唯一记录重建CSV，避免CSV与索引之间中断造成重复或漏行。
        for day, kind in self.db.execute("SELECT DISTINCT day,kind FROM records"):
            self.export(day, kind)

    def log(self, event, **fields):
        now = utc_ms()
        folder = self.root / datetime.fromtimestamp(now / 1000, timezone.utc).strftime("%Y-%m-%d")
        folder.mkdir(exist_ok=True)
        line = json.dumps({"receivedAtUtcMs": now, "event": event, **fields}, ensure_ascii=False)
        with open(folder / "debug.jsonl", "a", encoding="utf-8", newline="\n") as f:
            f.write(line + "\n")
            f.flush()
            os.fsync(f.fileno())
        print(line, flush=True)

    def export(self, day, kind):
        folder = self.root / day
        folder.mkdir(exist_ok=True)
        target = folder / ("snapshots.csv" if kind == "snapshot" else "events.csv")
        temp = target.with_suffix(".csv.tmp")
        with open(temp, "w", encoding="utf-8", newline="") as f:
            writer = csv.writer(f)
            writer.writerow(COLUMNS[kind])
            for (payload,) in self.db.execute("SELECT payload FROM records WHERE day=? AND kind=? ORDER BY rowid", (day, kind)):
                record = json.loads(payload)
                writer.writerow([cell(record, c) for c in COLUMNS[kind]])
            f.flush()
            os.fsync(f.fileno())
        os.replace(temp, target)

    def receive(self, raw):
        text = raw.decode("utf-8", errors="strict").rstrip("\r\n")
        if text.startswith("LOG "):
            self.log("DEVICE_LOG", message=text[4:])
            return
        if not text.startswith("DATA "):
            self.log("INVALID_PREFIX", message=text[:300])
            return
        record = json.loads(text[5:], parse_constant=lambda x: (_ for _ in ()).throw(ValueError("非有限数字")))
        self.validator.validate(record)
        # UTC未知必须为空，不能以电脑收到记录的时间冒充设备采样时间。
        if (record["timeQuality"] == "UNSYNCED") != (record["utcMs"] is None):
            raise ValueError("设备时间质量与UTC空值不一致")
        if record["kind"] == "snapshot":
            for name in ("temperature", "humidity", "lightLevel", "soilHumidity"):
                obs = record[name]
                if (obs["validity"] == "VALID") != (obs["value"] is not None):
                    raise ValueError(name + "有效性与值不一致")
        now = utc_ms()
        record["receivedAtUtcMs"] = now
        day = datetime.fromtimestamp(now / 1000, timezone.utc).strftime("%Y-%m-%d")
        identity = tuple(record[k] for k in ("deviceId", "bootId", "kind", "seq"))
        with self.db:
            result = self.db.execute("INSERT OR IGNORE INTO records VALUES(?,?,?,?,?,?)", (*identity, day, json.dumps(record, ensure_ascii=False)))
        if result.rowcount == 0:
            previous = json.loads(self.db.execute("SELECT payload FROM records WHERE device=? AND boot=? AND kind=? AND seq=?", identity).fetchone()[0])
            previous.pop("receivedAtUtcMs")
            comparison = dict(record)
            comparison.pop("receivedAtUtcMs")
            if previous != comparison:
                self.log("IDENTITY_CONFLICT", identity=identity)
                return
            self.log("DUPLICATE", identity=identity)
            return
        # 追加仅发生在唯一记录事务完成后；重启时由SQLite确定性恢复CSV。
        folder = self.root / day
        folder.mkdir(exist_ok=True)
        target = folder / ("snapshots.csv" if record["kind"] == "snapshot" else "events.csv")
        fresh = not target.exists()
        with open(target, "a", encoding="utf-8", newline="") as f:
            writer = csv.writer(f)
            if fresh:
                writer.writerow(COLUMNS[record["kind"]])
            writer.writerow([cell(record, c) for c in COLUMNS[record["kind"]]])
            f.flush()
            os.fsync(f.fileno())
        self.last_written = now
        if record["kind"] == "snapshot":
            previous = self.last_snapshot.get(record["deviceId"])
            if previous:
                if previous["bootId"] != record["bootId"]:
                    self.log("DEVICE_RESTART", previousBoot=previous["bootId"], bootId=record["bootId"])
                elif record["seq"] != previous["seq"] + 1 or record["uptimeMs"] - previous["uptimeMs"] > RUNTIME["timing"]["gapAfterMs"]:
                    self.log("SNAPSHOT_GAP", previousSeq=previous["seq"], seq=record["seq"], deltaMs=record["uptimeMs"] - previous["uptimeMs"])
            self.last_snapshot[record["deviceId"]] = record
        print(f"已写盘 {datetime.fromtimestamp(now / 1000, timezone.utc).isoformat()} {identity}", flush=True)

    def close(self):
        self.db.close()
        self.lock.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--list", action="store_true", help="枚举串口")
    parser.add_argument("--port", help="明确指定本项目USB串口")
    parser.add_argument("--output", type=Path, default=Path(__file__).resolve().parents[2] / "项目数据" / "development")
    parser.add_argument("--seconds", type=float, help="运行时长；省略则持续运行")
    parser.add_argument("--import-lines", type=Path, help="重放已保存的真实串口记录，复用校验与去重流程")
    parser.add_argument("--send", type=Path, help="连通后发送文件中的显式本地诊断JSON行")
    parser.add_argument("--interactive", action="store_true", help="归档时从标准输入接收显式本地诊断JSON，不另占串口")
    parser.add_argument("--restart-on-usb-reconnect", action="store_true", help="同一USB串口物理移除并重连后，执行一次ESP32应用启动时序；初次打开不复位")
    args = parser.parse_args()
    if args.list:
        print(json.dumps([{"port": p.device, "description": p.description, "hwid": p.hwid} for p in list_ports.comports()], ensure_ascii=False, indent=2))
        return
    if not args.port and not args.import_lines:
        parser.error("必须提供 --port 或 --import-lines；不会自动选择蓝牙或其他串口")
    archive = Archive(args.output)
    commands = queue.Queue()
    if args.interactive:
        def read_commands():
            for command_line in sys.stdin:
                commands.put(command_line.strip())
        threading.Thread(target=read_commands, daemon=True).start()
    started = time.monotonic()
    port = None
    sent = False
    pending = bytearray()
    discard_line = False
    last_bytes = time.monotonic()
    gap_reported = False
    usb_identity = None
    usb_removed = False
    try:
        archive.log("ARCHIVER_START", port=args.port, lastRecord=archive.db.execute("SELECT MAX(json_extract(payload,'$.receivedAtUtcMs')) FROM records").fetchone()[0])
        if args.import_lines:
            with open(args.import_lines, "rb") as f:
                for raw in f:
                    archive.receive(raw)
            return
        while args.seconds is None or time.monotonic() - started < args.seconds:
            if port is None:
                if args.restart_on_usb_reconnect:
                    device = next((p for p in list_ports.comports() if p.device.upper() == args.port.upper()), None)
                    if device is None:
                        if usb_identity is not None and not usb_removed:
                            usb_removed = True
                            archive.log("USB_REMOVED", port=args.port)
                        time.sleep(0.25)
                        continue
                    identity = (device.vid, device.pid, device.serial_number)
                    if None in identity:
                        raise RuntimeError("USB重连启动要求可核实的VID、PID与设备序列号")
                    if usb_identity is not None and identity != usb_identity:
                        raise RuntimeError("串口对应的USB设备已更换，停止归档以避免复位其他设备")
                try:
                    port = serial.Serial(port=None, baudrate=115200, timeout=0.25, write_timeout=2)
                    port.dtr = False
                    port.rts = False
                    port.port = args.port
                    port.open()
                    pending.clear()
                    discard_line = False
                    archive.log("PORT_OPEN", port=args.port)
                    if args.restart_on_usb_reconnect:
                        usb_identity = identity
                        if usb_removed:
                            # 物理重连是新启动边界，先保证GPIO0释放，再拉低并释放EN进入Flash应用。
                            # 只在观察到USB移除后执行一次，不因无数据、网络或解析错误反复复位。
                            usb_removed = False
                            archive.log("USB_APPLICATION_START", port=args.port, usbSerial=identity[2], resetPulseMs=150)
                            port.dtr = False
                            port.rts = True
                            time.sleep(0.15)
                            port.rts = False
                            archive.log("USB_APPLICATION_START_RELEASED", port=args.port)
                    last_bytes = time.monotonic()
                    gap_reported = False
                    if args.send and not sent:
                        for line in args.send.read_text(encoding="utf-8").splitlines():
                            json.loads(line)
                            port.write((line + "\n").encode("utf-8"))
                        port.flush()
                        sent = True
                except (serial.SerialException, OSError) as e:
                    if port:
                        port.close()
                    port = None
                    archive.log("PORT_UNAVAILABLE", error=str(e))
                    time.sleep(2)
                    continue
            try:
                if not commands.empty():
                    line = commands.get_nowait()
                    try:
                        command = json.loads(line)
                        if command.get("scope") != "LOCAL_DIAGNOSTIC":
                            raise ValueError("只允许显式LOCAL_DIAGNOSTIC入口")
                        port.write((line + "\n").encode("utf-8"))
                        port.flush()
                        archive.log("DIAGNOSTIC_SENT", command=command)
                    except (ValueError, TypeError, AttributeError) as e:
                        archive.log("DIAGNOSTIC_INPUT_REJECTED", error=str(e))
                data = port.read(max(1, min(port.in_waiting, 4096)))
            except (serial.SerialException, OSError) as e:
                archive.log("PORT_DISCONNECTED", error=str(e), lastSuccessfulWriteUtcMs=archive.last_written)
                port.close()
                port = None
                continue
            if data:
                last_bytes = time.monotonic()
                gap_reported = False
            elif time.monotonic() - last_bytes > 15 and not gap_reported:
                archive.log("NO_SERIAL_DATA", lastSuccessfulWriteUtcMs=archive.last_written)
                gap_reported = True
            for b in data:
                if b == 10:
                    if not discard_line and pending:
                        try:
                            archive.receive(bytes(pending))
                        except (UnicodeError, ValueError, TypeError, KeyError) as e:
                            archive.log("INVALID_RECORD", error=str(e), bytes=len(pending))
                        except Exception as e:
                            from jsonschema import ValidationError
                            if isinstance(e, ValidationError):
                                archive.log("SCHEMA_REJECT", error=e.message)
                            else:
                                raise
                    pending.clear()
                    discard_line = False
                elif not discard_line:
                    pending.append(b)
                    if len(pending) > RUNTIME["serial"]["maxLineBytes"]:
                        archive.log("LINE_TOO_LONG")
                        pending.clear()
                        discard_line = True
    except KeyboardInterrupt:
        archive.log("USER_STOP")
    finally:
        if port:
            port.close()
        archive.close()


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        # 写盘错误必须停止，不能继续显示成功或丢弃失败记录。
        print(f"归档停止，未保证后续数据写入：{exc}", file=sys.stderr, flush=True)
        raise SystemExit(1)
