// ESP32 蓝牙小车固件（Arduino IDE）
// 经典蓝牙 SPP（BluetoothSerial），支持：
// 1) 单字母状态指令：F 前进 / B 后退 / L 左转 / R 右转 / S 停止
// 2) 自然语言命令：前进2米、跑3秒、左转1.5秒、前进200cm、forward 2m
// 默认换算：1 秒约等于 0.75 米，也就是 1 米约等于 1.33 秒
#include <BluetoothSerial.h>
#include "esp_task_wdt.h"

BluetoothSerial SerialBT;

// --- 引脚定义 ---
#define IN1 25
#define IN2 26
#define IN3 27
#define IN4 14
#define ENA 16
#define ENB 17

// --- 运动参数 ---
const float METERS_PER_SECOND = 0.75f;
const unsigned long HEARTBEAT_MS = 20;
const int PWM_LEFT_FORWARD = 250;
const int PWM_RIGHT_FORWARD = 200;
const int PWM_LEFT_BACKWARD = 250;
const int PWM_RIGHT_BACKWARD = 10;
const int PWM_LEFT_TURN = 250;
const int PWM_RIGHT_TURN = 200;

volatile char currentCmd = 'S';
int pwmChL, pwmChR; // 用于存储自动分配的 PWM 通道号

String rxBuffer;

struct MotionPlan {
  char cmd;
  bool timed;
  unsigned long durationMs;
};

void moveBackward() {
  digitalWrite(IN1, HIGH); digitalWrite(IN2, LOW);
  digitalWrite(IN3, HIGH); digitalWrite(IN4, LOW);
  ledcWrite(pwmChL, PWM_LEFT_FORWARD);
  ledcWrite(pwmChR, PWM_RIGHT_FORWARD);
}

void moveForward() {
  digitalWrite(IN1, LOW); digitalWrite(IN2, HIGH);
  digitalWrite(IN3, LOW); digitalWrite(IN4, HIGH);
  ledcWrite(pwmChL, PWM_LEFT_BACKWARD);
  ledcWrite(pwmChR, PWM_RIGHT_BACKWARD);
}

void turnLeft() {
  digitalWrite(IN1, HIGH); digitalWrite(IN2, LOW);
  digitalWrite(IN3, LOW);  digitalWrite(IN4, HIGH);
  ledcWrite(pwmChL, PWM_LEFT_TURN);
  ledcWrite(pwmChR, PWM_RIGHT_TURN);
}

void turnRight() {
  digitalWrite(IN1, LOW);  digitalWrite(IN2, HIGH);
  digitalWrite(IN3, HIGH); digitalWrite(IN4, LOW);
  ledcWrite(pwmChL, PWM_LEFT_TURN);
  ledcWrite(pwmChR, PWM_RIGHT_TURN);
}

void stopCar() {
  digitalWrite(IN1, LOW); digitalWrite(IN2, LOW);
  digitalWrite(IN3, LOW); digitalWrite(IN4, LOW);
  ledcWrite(pwmChL, 0);
  ledcWrite(pwmChR, 0);
}

void runMotion(char cmd) {
  switch (cmd) {
    case 'F': moveForward(); break;
    case 'B': moveBackward(); break;
    case 'L': turnLeft(); break;
    case 'R': turnRight(); break;
    default: stopCar(); break;
  }
}

unsigned long secondsToMs(float seconds) {
  if (seconds <= 0.0f) return 0;
  return (unsigned long)(seconds * 1000.0f + 0.5f);
}

unsigned long metersToMs(float meters) {
  if (meters <= 0.0f) return 0;
  return secondsToMs(meters / METERS_PER_SECOND);
}

float parseNumberToken(const String &token) {
  if (token.length() == 0) return -1.0f;
  char buf[24];
  size_t n = token.length();
  if (n >= sizeof(buf)) n = sizeof(buf) - 1;
  for (size_t i = 0; i < n; ++i) buf[i] = token[i];
  buf[n] = '\0';
  return atof(buf);
}

String normalizeText(String s) {
  s.trim();
  s.toLowerCase();
  s.replace(" ", "");
  s.replace(",", "");
  s.replace("，", "");
  s.replace("。", "");
  s.replace("；", "");
  s.replace(";", "");
  s.replace("\t", "");
  return s;
}

bool containsAny(const String &s, const char *const *words, size_t count) {
  for (size_t i = 0; i < count; ++i) {
    if (s.indexOf(words[i]) >= 0) return true;
  }
  return false;
}

char inferCommand(const String &s) {
  const char *forwardWords[] = {"forward", "f", "前进", "向前", "往前", "跑", "走"};
  const char *backwardWords[] = {"backward", "back", "b", "后退", "倒退", "往后"};
  const char *leftWords[] = {"left", "l", "左转", "向左", "左"};
  const char *rightWords[] = {"right", "r", "右转", "向右", "右"};
  const char *stopWords[] = {"stop", "s", "停止", "停下", "刹车", "别动"};

  if (containsAny(s, stopWords, sizeof(stopWords) / sizeof(stopWords[0]))) return 'S';
  if (containsAny(s, leftWords, sizeof(leftWords) / sizeof(leftWords[0]))) return 'L';
  if (containsAny(s, rightWords, sizeof(rightWords) / sizeof(rightWords[0]))) return 'R';
  if (containsAny(s, backwardWords, sizeof(backwardWords) / sizeof(backwardWords[0]))) return 'B';
  if (containsAny(s, forwardWords, sizeof(forwardWords) / sizeof(forwardWords[0]))) return 'F';
  return 0;
}

float parseTimedValue(const String &s, const String &unit) {
  int idx = s.indexOf(unit);
  if (idx < 0) return -1.0f;

  int start = idx - 1;
  while (start >= 0) {
    char ch = s[start];
    if ((ch >= '0' && ch <= '9') || ch == '.' || ch == '-') {
      --start;
      continue;
    }
    break;
  }
  ++start;
  if (start >= idx) return -1.0f;

  String num = s.substring(start, idx);
  return parseNumberToken(num);
}

MotionPlan parseMotionCommand(String raw) {
  MotionPlan plan;
  plan.cmd = 0;
  plan.timed = false;
  plan.durationMs = 0;

  String s = normalizeText(raw);
  if (s.length() == 0) return plan;

  if (s.length() == 1) {
    char c = toupper(s[0]);
    if (strchr("FBRLS", c)) {
      plan.cmd = c;
      return plan;
    }
  }

  char inferred = inferCommand(s);
  if (inferred == 0) return plan;
  plan.cmd = inferred;

  float seconds = parseTimedValue(s, "秒");
  if (seconds < 0.0f) seconds = parseTimedValue(s, "sec");
  if (seconds < 0.0f) seconds = parseTimedValue(s, "s");
  if (seconds >= 0.0f) {
    plan.timed = true;
    plan.durationMs = secondsToMs(seconds);
    return plan;
  }

  float meters = parseTimedValue(s, "米");
  if (meters < 0.0f) meters = parseTimedValue(s, "m");
  if (meters < 0.0f) meters = parseTimedValue(s, "meter");
  if (meters >= 0.0f) {
    if (s.indexOf("cm") >= 0 || s.indexOf("厘米") >= 0) {
      meters = meters / 100.0f;
    }
    plan.timed = true;
    plan.durationMs = metersToMs(meters);
    return plan;
  }

  return plan;
}

void executeTimedMove(char cmd, unsigned long durationMs) {
  if (durationMs == 0) {
    runMotion(cmd);
    return;
  }

  unsigned long start = millis();
  while ((unsigned long)(millis() - start) < durationMs) {
    runMotion(cmd);
    delay(HEARTBEAT_MS);
  }
  stopCar();
}

void handleCommand(String raw) {
  MotionPlan plan = parseMotionCommand(raw);
  if (plan.cmd == 0) return;

  if (plan.cmd == 'S') {
    currentCmd = 'S';
    stopCar();
    return;
  }

  currentCmd = plan.cmd;
  if (plan.timed) {
    executeTimedMove(plan.cmd, plan.durationMs);
  } else {
    runMotion(plan.cmd);
  }
}

void setup() {
  pwmChL = ledcAttach(ENA, 5000, 8);
  pwmChR = ledcAttach(ENB, 5000, 8);

  Serial.begin(115200);
  esp_task_wdt_deinit();
  setCpuFrequencyMhz(80);

  SerialBT.begin("ESP32_Car");
  Serial.println("Bluetooth ready");

  pinMode(IN1, OUTPUT); pinMode(IN2, OUTPUT);
  pinMode(IN3, OUTPUT); pinMode(IN4, OUTPUT);

  stopCar();

  delay(1000);
  moveForward();
  delay(2000);
  stopCar();
}

void loop() {
  while (SerialBT.available()) {
    char c = SerialBT.read();
    if (c == '\r' || c == '\n' || c == '\t') {
      if (rxBuffer.length() > 0) {
        handleCommand(rxBuffer);
        rxBuffer = "";
      }
      continue;
    }
    rxBuffer += c;
    if (rxBuffer.length() > 64) {
      handleCommand(rxBuffer);
      rxBuffer = "";
    }
  }

  if (rxBuffer.length() == 1) {
    char c = toupper(rxBuffer[0]);
    if (strchr("FBRLS", c)) {
      currentCmd = c;
      runMotion(c);
      rxBuffer = "";
      return;
    }
  }

  switch (currentCmd) {
    case 'F': moveForward(); break;
    case 'B': moveBackward(); break;
    case 'L': turnLeft(); break;
    case 'R': turnRight(); break;
    default: stopCar(); break;
  }
}
