#pragma once
#include <Arduino.h>
#include <Preferences.h>
#include <nvs_flash.h>

// 单次NVS提交包含接收记录和时间高水位，断电不会分裂这两项状态。
struct CommandReceipt {
  char id[65]; char result[40];
  uint64_t stateVersion;
};
struct CommandJournal {
  uint32_t format = 1;
  uint32_t cursor = 0;
  int64_t issuedHighWater = 0;
  CommandReceipt receipts[64]{};
};
class CommandStore {
  Preferences prefs;
  CommandJournal journal;
public:
  bool begin() {
    if (nvs_flash_init_partition("commands") != ESP_OK) return false;
    if (!prefs.begin("m0commands", false, "commands")) return false;
    size_t length = prefs.getBytesLength("journal");
    // 分区迁移只执行一次：先复制原日志，再改写新分区，保留旧分区原始记录。
    if (!length) {
      Preferences original;
      if (original.begin("m0commands", true)) {
        size_t oldLength = original.getBytesLength("journal");
        if (oldLength && (oldLength != sizeof(journal) || original.getBytes("journal", &journal, sizeof(journal)) != sizeof(journal))) return false;
        original.end();
      }
      if (!save()) return false;
      length = sizeof(journal);
    }
    if (length && length != sizeof(journal)) return false;
    if (length && prefs.getBytes("journal", &journal, sizeof(journal)) != sizeof(journal)) return false;
    if (journal.format != 1 || journal.cursor >= 64) return false;
    bool changed = false;
    for (auto &receipt : journal.receipts) if (!strcmp(receipt.result, "RECEIVED")) {
      strlcpy(receipt.result, "INTERRUPTED", sizeof(receipt.result)); changed = true;
    }
    return !changed || save();
  }
  bool save() { return prefs.putBytes("journal", &journal, sizeof(journal)) == sizeof(journal); }
  const CommandReceipt *find(const char *id) const {
    for (const auto &receipt : journal.receipts) if (*receipt.id && !strcmp(receipt.id, id)) return &receipt;
    return nullptr;
  }
  int64_t highWater() const { return journal.issuedHighWater; }
  bool receive(const char *id, int64_t issued) {
    auto &receipt = journal.receipts[journal.cursor]; receipt = {};
    strlcpy(receipt.id, id, sizeof(receipt.id)); strlcpy(receipt.result, "RECEIVED", sizeof(receipt.result));
    journal.cursor = (journal.cursor + 1) % 64;
    journal.issuedHighWater = issued;
    return save();
  }
  bool finish(const char *id, const char *result, uint64_t version) {
    for (auto &receipt : journal.receipts) if (!strcmp(receipt.id, id)) {
      strlcpy(receipt.result, result, sizeof(receipt.result)); receipt.stateVersion = version;
      return save();
    }
    return false;
  }
};
