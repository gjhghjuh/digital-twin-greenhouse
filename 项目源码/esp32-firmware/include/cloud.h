#pragma once
#include <ArduinoJson.h>
#include <Arduino.h>

struct CloudCommand { char packet[513]; uint32_t desiredVersion; };
struct CloudNotice { char type[40]; char reason[100]; };
void cloudStart();
bool cloudConnected();
void cloudSnapshot(const JsonDocument &snapshot);
bool cloudReceive(CloudCommand &command);
bool cloudNotice(CloudNotice &notice);
bool cloudResult(const char *result, uint32_t desiredVersion);
