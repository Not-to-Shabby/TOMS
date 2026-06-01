/**
 * @file main.cpp
 * @brief TOMS — STM32 Blue Pill Slave Monitor Firmware
 *
 * Receives JSON debug packets from the ESP32 Slave via UART2
 * and drives status LEDs + forwards to USB CDC on the PC.
 *
 * Wiring (3.3 V logic — Blue Pill and ESP32-S3 are both 3.3 V):
 *
 *   ESP32-S3 Pin       Blue Pill Pin    Function
 *   ─────────────────────────────────────────────────────
 *   GPIO17 (TX)    →   PA3  (UART2 RX)  Debug UART input
 *   GPIO18 (RX)    ←   PA2  (UART2 TX)  Optional: future commands
 *   GND            ─   GND              Common ground (REQUIRED)
 *
 *   Blue Pill Pin  →   LED/Indicator    Function
 *   ─────────────────────────────────────────────────────
 *   PC13           →   onboard LED      Alive heartbeat (inverted: LOW = ON)
 *   PB0            →   GREEN LED        ESP-NOW link active
 *   PB1            →   YELLOW LED       Boarding in progress
 *   PB12           →   RED LED          Error / timeout state
 *
 * USB CDC:
 *   Connect Blue Pill USB (PA11/PA12) to PC — a COM port appears.
 *   Open at any baud rate to see raw JSON lines from the slave
 *   plus human-readable state summaries.
 *
 * Packet format from ESP32 slave (JSON, newline-delimited, 115200 baud):
 *   {"dbg":"state","screen":"fare","batt_mv":3820,"batt_pct":78,"master":"AA:BB:CC:DD:EE:FF","seq":12}
 *   {"dbg":"btn","event":"PRESS"}
 *   {"dbg":"btn","event":"HOLD_1S"}
 *   {"dbg":"btn","event":"HOLD_2S"}
 *   {"dbg":"btn","event":"HOLD_3S"}
 *   {"dbg":"btn","event":"RELEASE"}
 *   {"dbg":"espnow_rx","type":"BOARD_COMMAND","fare":1300,"seat":1}
 *   {"dbg":"espnow_rx","type":"RELEASE"}
 *   {"dbg":"espnow_rx","type":"ACK_MASTER"}
 *   {"dbg":"espnow_tx","type":"BUTTON_PRESS","seq":5}
 *   {"dbg":"espnow_tx","type":"RELEASE","seq":6}
 *   {"dbg":"timeout","reason":"board_wait"}
 *   {"dbg":"error","msg":"..."}
 *   {"dbg":"batt","mv":3820,"pct":78}
 */

#include <Arduino.h>

/* ── UART to ESP32 Slave ────────────────────────────────────────────────── */
/* UART2: PA2=TX, PA3=RX */
HardwareSerial SlaveSerial(PA3, PA2);
#define SLAVE_BAUD   115200

/* ── Status LEDs ──────────────────────────────────────────────────────────
 * PC13 is the onboard LED (active LOW — LOW turns it ON).
 * External LEDs on PB0, PB1, PB12 are active HIGH.          */
#define LED_ALIVE     PC13  /* Onboard LED — heartbeat blink */
#define LED_LINK      PB0   /* GREEN  — ESP-NOW master link active */
#define LED_BOARDING  PB1   /* YELLOW — boarding in progress */
#define LED_ERROR     PB12  /* RED    — timeout / error state */

/* ── State Machine ────────────────────────────────────────────────────── */
enum SlaveScreen {
    SCREEN_UNKNOWN,
    SCREEN_WELCOME,
    SCREEN_PROCESSING,
    SCREEN_FARE,
    SCREEN_QR,
    SCREEN_ALARM,
    SCREEN_SLEEP,
    SCREEN_ERROR,
    SCREEN_DEBUG,
};

struct SlaveStatus {
    SlaveScreen screen      = SCREEN_UNKNOWN;
    bool        masterLinked  = false;  /* received BOARD_COMMAND or CONFIG_SYNC recently */
    uint16_t    battMv      = 0;
    uint8_t     battPct     = 0;
    uint16_t    lastFare    = 0;
    uint8_t     lastSeat    = 0;
    uint8_t     rxSeq       = 0;
    char        masterMac[18] = "??:??:??:??:??:??";
    uint32_t    lastPacketMs  = 0;  /* millis() when last packet arrived */
    bool        errorState    = false;
};

static SlaveStatus g_status;

/* ── Receive Buffer ───────────────────────────────────────────────────── */
static char     g_rxBuf[512];
static uint16_t g_rxIdx = 0;

/* ── Heartbeat ────────────────────────────────────────────────────────── */
static uint32_t g_lastBlink      = 0;
static bool     g_aliveState     = false;
#define BLINK_INTERVAL_MS  500

/* ── Link timeout (no packet in 15s = unlinked) ──────────────────────── */
#define LINK_TIMEOUT_MS  15000

/* ──────────────────────────────────────────────────────────────────────── */
/* Helpers                                                                  */
/* ──────────────────────────────────────────────────────────────────────── */

static const char* screenName(SlaveScreen s)
{
    switch (s) {
        case SCREEN_WELCOME:    return "WELCOME";
        case SCREEN_PROCESSING: return "PROCESSING";
        case SCREEN_FARE:       return "FARE";
        case SCREEN_QR:         return "QR";
        case SCREEN_ALARM:      return "ALARM";
        case SCREEN_SLEEP:      return "SLEEP";
        case SCREEN_ERROR:      return "ERROR";
        case SCREEN_DEBUG:      return "DEBUG";
        default:                return "UNKNOWN";
    }
}

static SlaveScreen parseScreen(const char *val)
{
    if (strstr(val, "welcome"))    return SCREEN_WELCOME;
    if (strstr(val, "processing")) return SCREEN_PROCESSING;
    if (strstr(val, "fare"))       return SCREEN_FARE;
    if (strstr(val, "qr"))         return SCREEN_QR;
    if (strstr(val, "alarm"))      return SCREEN_ALARM;
    if (strstr(val, "sleep"))      return SCREEN_SLEEP;
    if (strstr(val, "error"))      return SCREEN_ERROR;
    if (strstr(val, "debug"))      return SCREEN_DEBUG;
    return SCREEN_UNKNOWN;
}

/** Minimal strstr-based JSON field extractor.
 *  Extracts the string value of a key like "key":"value" or "key":number. */
static bool jsonGetStr(const char *json, const char *key, char *out, size_t outSize)
{
    char search[64];
    snprintf(search, sizeof(search), "\"%s\":\"", key);
    const char *p = strstr(json, search);
    if (!p) return false;
    p += strlen(search);
    const char *end = strchr(p, '"');
    if (!end) return false;
    size_t len = (size_t)(end - p);
    if (len >= outSize) len = outSize - 1;
    memcpy(out, p, len);
    out[len] = '\0';
    return true;
}

static bool jsonGetInt(const char *json, const char *key, int *out)
{
    char search[64];
    snprintf(search, sizeof(search), "\"%s\":", key);
    const char *p = strstr(json, search);
    if (!p) return false;
    p += strlen(search);
    *out = atoi(p);
    return true;
}

/* ──────────────────────────────────────────────────────────────────────── */
/* Packet parser                                                            */
/* ──────────────────────────────────────────────────────────────────────── */

static void updateLEDs()
{
    bool boarding = (g_status.screen == SCREEN_FARE   ||
                     g_status.screen == SCREEN_QR      ||
                     g_status.screen == SCREEN_PROCESSING);

    digitalWrite(LED_LINK,     g_status.masterLinked ? HIGH : LOW);
    digitalWrite(LED_BOARDING, boarding              ? HIGH : LOW);
    digitalWrite(LED_ERROR,    g_status.errorState   ? HIGH : LOW);
}

static void printSummary(const char *event)
{
    char buf[256];
    snprintf(buf, sizeof(buf),
             "[TOMS-MON] %s | screen=%-12s link=%s batt=%dmV(%d%%) fare=%d seat=%d master=%s",
             event,
             screenName(g_status.screen),
             g_status.masterLinked ? "YES" : "NO ",
             g_status.battMv,
             g_status.battPct,
             g_status.lastFare,
             g_status.lastSeat,
             g_status.masterMac);
    Serial.println(buf);   /* USB CDC to PC */
}

static void parsePacket(const char *json)
{
    /* Forward raw packet to PC */
    Serial.print("[RAW] ");
    Serial.println(json);

    g_status.lastPacketMs = millis();

    char dbgType[32] = {0};
    if (!jsonGetStr(json, "dbg", dbgType, sizeof(dbgType))) return;

    /* ── State update ─────────────────────────────────────────────────── */
    if (strcmp(dbgType, "state") == 0) {
        char screenStr[32] = {0};
        if (jsonGetStr(json, "screen", screenStr, sizeof(screenStr))) {
            g_status.screen = parseScreen(screenStr);
        }

        int mv = 0, pct = 0;
        if (jsonGetInt(json, "batt_mv", &mv))  g_status.battMv  = (uint16_t)mv;
        if (jsonGetInt(json, "batt_pct", &pct)) g_status.battPct = (uint8_t)pct;

        int seq = 0;
        if (jsonGetInt(json, "seq", &seq)) g_status.rxSeq = (uint8_t)seq;

        jsonGetStr(json, "master", g_status.masterMac, sizeof(g_status.masterMac));

        printSummary("STATE");

    /* ── Button event ─────────────────────────────────────────────────── */
    } else if (strcmp(dbgType, "btn") == 0) {
        char ev[32] = {0};
        jsonGetStr(json, "event", ev, sizeof(ev));
        char msg[64];
        snprintf(msg, sizeof(msg), "BTN[%s]", ev);
        printSummary(msg);

    /* ── ESP-NOW received ─────────────────────────────────────────────── */
    } else if (strcmp(dbgType, "espnow_rx") == 0) {
        char type[32] = {0};
        jsonGetStr(json, "type", type, sizeof(type));

        if (strcmp(type, "BOARD_COMMAND") == 0) {
            int fare = 0, seat = 0;
            jsonGetInt(json, "fare", &fare);
            jsonGetInt(json, "seat", &seat);
            g_status.lastFare   = (uint16_t)fare;
            g_status.lastSeat   = (uint8_t)seat;
            g_status.masterLinked = true;
            g_status.errorState   = false;
        } else if (strcmp(type, "CONFIG_SYNC") == 0 ||
                   strcmp(type, "FARE_TABLE_UPDATE") == 0) {
            g_status.masterLinked = true;
        } else if (strcmp(type, "ACK_MASTER") == 0) {
            /* Release was acknowledged */
            g_status.errorState = false;
        }

        char msg[64];
        snprintf(msg, sizeof(msg), "ESPNOW_RX[%s]", type);
        printSummary(msg);

    /* ── ESP-NOW transmitted ──────────────────────────────────────────── */
    } else if (strcmp(dbgType, "espnow_tx") == 0) {
        char type[32] = {0};
        jsonGetStr(json, "type", type, sizeof(type));
        char msg[64];
        snprintf(msg, sizeof(msg), "ESPNOW_TX[%s]", type);
        printSummary(msg);

    /* ── Timeout ──────────────────────────────────────────────────────── */
    } else if (strcmp(dbgType, "timeout") == 0) {
        char reason[32] = {0};
        jsonGetStr(json, "reason", reason, sizeof(reason));
        g_status.errorState = true;
        char msg[64];
        snprintf(msg, sizeof(msg), "TIMEOUT[%s]", reason);
        printSummary(msg);

    /* ── Error ────────────────────────────────────────────────────────── */
    } else if (strcmp(dbgType, "error") == 0) {
        char errMsg[64] = {0};
        jsonGetStr(json, "msg", errMsg, sizeof(errMsg));
        g_status.errorState = true;
        char msg[80];
        snprintf(msg, sizeof(msg), "ERROR[%s]", errMsg);
        printSummary(msg);

    /* ── Battery ──────────────────────────────────────────────────────── */
    } else if (strcmp(dbgType, "batt") == 0) {
        int mv = 0, pct = 0;
        if (jsonGetInt(json, "mv", &mv))  g_status.battMv  = (uint16_t)mv;
        if (jsonGetInt(json, "pct", &pct)) g_status.battPct = (uint8_t)pct;
        printSummary("BATT");
    }

    updateLEDs();
}

/* ──────────────────────────────────────────────────────────────────────── */
/* Arduino setup / loop                                                     */
/* ──────────────────────────────────────────────────────────────────────── */

void setup()
{
    /* USB CDC — PC side */
    Serial.begin(115200);
    while (!Serial && millis() < 3000) {}  /* wait up to 3s for USB enumeration */

    /* UART2 — ESP32 Slave side */
    SlaveSerial.begin(SLAVE_BAUD);

    /* Status LEDs */
    pinMode(LED_ALIVE,    OUTPUT);
    pinMode(LED_LINK,     OUTPUT);
    pinMode(LED_BOARDING, OUTPUT);
    pinMode(LED_ERROR,    OUTPUT);

    /* Initial state: all LEDs off (PC13 is inverted: HIGH = off for onboard LED) */
    digitalWrite(LED_ALIVE,    HIGH);
    digitalWrite(LED_LINK,     LOW);
    digitalWrite(LED_BOARDING, LOW);
    digitalWrite(LED_ERROR,    LOW);

    Serial.println("[TOMS-MON] STM32 Slave Monitor ready");
    Serial.println("[TOMS-MON] Waiting for ESP32 Slave debug packets on UART2 (PA3 RX)...");
    Serial.println("[TOMS-MON] -------------------------------------------------------");
    Serial.println("[TOMS-MON] LEDs: PB0=LINK(green), PB1=BOARDING(yellow), PB12=ERROR(red)");
    Serial.println("[TOMS-MON] -------------------------------------------------------");
}

void loop()
{
    /* ── Receive bytes from ESP32 Slave ─────────────────────────────── */
    while (SlaveSerial.available()) {
        char c = (char)SlaveSerial.read();

        if (c == '\n' || c == '\r') {
            if (g_rxIdx > 0) {
                g_rxBuf[g_rxIdx] = '\0';
                parsePacket(g_rxBuf);
                g_rxIdx = 0;
            }
        } else {
            if (g_rxIdx < sizeof(g_rxBuf) - 1) {
                g_rxBuf[g_rxIdx++] = c;
            } else {
                /* Buffer overflow — discard current line */
                Serial.println("[TOMS-MON] RX buffer overflow — discarding line");
                g_rxIdx = 0;
            }
        }
    }

    /* ── Link timeout detection ─────────────────────────────────────── */
    if (g_status.masterLinked &&
        g_status.lastPacketMs != 0 &&
        (millis() - g_status.lastPacketMs) > LINK_TIMEOUT_MS)
    {
        g_status.masterLinked = false;
        Serial.println("[TOMS-MON] WARNING: No packets for 15s — slave may be asleep or disconnected");
        updateLEDs();
    }

    /* ── Alive heartbeat blink (PC13, inverted) ─────────────────────── */
    if (millis() - g_lastBlink >= BLINK_INTERVAL_MS) {
        g_lastBlink  = millis();
        g_aliveState = !g_aliveState;
        /* PC13 is inverted: LOW = LED on */
        digitalWrite(LED_ALIVE, g_aliveState ? LOW : HIGH);
    }

    /* ── PC → Slave passthrough (optional command injection) ────────── */
    while (Serial.available()) {
        char c = (char)Serial.read();
        SlaveSerial.write(c);  /* forward to slave UART RX (GPIO18) */
    }
}
