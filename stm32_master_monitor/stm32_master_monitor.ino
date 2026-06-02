/**
 * @file stm32_master_monitor.ino
 * @brief TOMS — STM32 Blue Pill Master Monitor Firmware (Arduino IDE compatible)
 *
 * Receives JSON debug packets from the ESP32 Master via UART2 (PA3 RX)
 * and drives status LEDs + forwards to PC via USB CDC.
 *
 * Wiring (3.3 V logic — Blue Pill and ESP32-S3 are both 3.3 V):
 *
 *   ESP32-S3 Pin       Blue Pill Pin    Function
 *   ─────────────────────────────────────────────────────
 *   GPIO5 (TX)     →   PA3  (UART2 RX)  Debug UART input from ESP32 Master
 *   GPIO6 (RX)     ←   PA2  (UART2 TX)  Optional: future command injection
 *   GND            ─   GND              Common ground (REQUIRED)
 *
 *   Blue Pill Pin  →   LED/Indicator    Function
 *   ─────────────────────────────────────────────────────
 *   PC13           →   onboard LED      Alive heartbeat (inverted: LOW = ON)
 *   PB0            →   GREEN LED        ESP-NOW link active (Master-to-Slave Link)
 *   PB1            →   YELLOW LED       Conductor link active (USB CDC connected to phone)
 *   PB12           →   RED LED          General Error / timeout state
 *
 * Arduino IDE Settings:
 *   - Board: STM32F1xx / Generic STM32F103C series
 *   - Uploader: Maple DFU Bootloader (or ST-Link / Serial depending on bootloader)
 *   - USB Support: CDC (Generic Serial)
 */

#if defined(ARDUINO_ARCH_STM32F1) && !defined(STM32_CORE_VERSION)
// Maple Core (Roger Clark): Serial2 is pre-defined on PA2 (TX) and PA3 (RX)
#define MasterSerial Serial2
#else
// Official ST Core:
HardwareSerial MasterSerial(PA3, PA2);
#endif

#define MASTER_BAUD   115200

/* ── Status LEDs ──────────────────────────────────────────────────────────
 * PC13 is the onboard LED (active LOW — LOW turns it ON).
 * External LEDs on PB0, PB1, PB12 are active HIGH.          */
#define LED_ALIVE     PC13  /* Onboard LED — heartbeat blink */
#define LED_LINK      PB0   /* GREEN  — ESP-NOW Master-to-Slave Link active */
#define LED_CONDUCTOR PB1   /* YELLOW — Conductor USB CDC connected to phone */
#define LED_ERROR     PB12  /* RED    — timeout / error state */

/* ── State Machine ────────────────────────────────────────────────────── */
struct MasterStatus {
    bool        usbConnected  = false;  /* Conductor Link (PB1 / Yellow) */
    bool        slaveLinked   = false;  /* Master-to-Slave Link (PB0 / Green) */
    uint16_t    battMv        = 0;
    uint8_t     battPct       = 0;
    uint8_t     activeRoute   = 0;
    char        slaveMac[18]  = "00:00:00:00:00:00";
    uint32_t    lastPacketMs  = 0;      /* millis() when last telemetry packet arrived */
    uint32_t    lastEspnowMs  = 0;      /* millis() when last ESP-NOW rx/tx occurred */
    bool        errorState    = false;  /* General Error (PB12 / Red) */
};

static MasterStatus g_status;

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
    digitalWrite(LED_LINK,      g_status.slaveLinked   ? HIGH : LOW);
    digitalWrite(LED_CONDUCTOR, g_status.usbConnected  ? HIGH : LOW);
    digitalWrite(LED_ERROR,     g_status.errorState    ? HIGH : LOW);
}

static void printSummary(const char *event)
{
    char buf[256];
    snprintf(buf, sizeof(buf),
             "[TOMS-MON] %s | usb_conn=%s slave_link=%s batt=%dmV(%d%%) route=%d slave=%s err=%s",
             event,
             g_status.usbConnected ? "YES" : "NO ",
             g_status.slaveLinked  ? "YES" : "NO ",
             g_status.battMv,
             g_status.battPct,
             g_status.activeRoute,
             g_status.slaveMac,
             g_status.errorState   ? "YES" : "NO ");
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
        int usb_conn = 0;
        if (jsonGetInt(json, "usb_connected", &usb_conn)) {
            g_status.usbConnected = (usb_conn != 0);
        }

        int mv = 0, pct = 0;
        if (jsonGetInt(json, "batt_mv", &mv))  g_status.battMv  = (uint16_t)mv;
        if (jsonGetInt(json, "batt_pct", &pct)) g_status.battPct = (uint8_t)pct;

        int route = 0;
        if (jsonGetInt(json, "active_route", &route)) g_status.activeRoute = (uint8_t)route;

        char slaveMac[18] = {0};
        if (jsonGetStr(json, "slave", slaveMac, sizeof(slaveMac))) {
            strcpy(g_status.slaveMac, slaveMac);
            if (strcmp(slaveMac, "00:00:00:00:00:00") != 0) {
                g_status.slaveLinked = true;
                g_status.lastEspnowMs = millis();
            }
        }

        printSummary("STATE");

    /* ── ESP-NOW received ─────────────────────────────────────────────── */
    } else if (strcmp(dbgType, "espnow_rx") == 0) {
        g_status.slaveLinked = true;
        g_status.lastEspnowMs = millis();
        g_status.errorState = false;

        char type[32] = {0};
        jsonGetStr(json, "type", type, sizeof(type));

        char slaveMac[18] = {0};
        if (jsonGetStr(json, "mac", slaveMac, sizeof(slaveMac))) {
            strcpy(g_status.slaveMac, slaveMac);
        }

        char msg[64];
        snprintf(msg, sizeof(msg), "ESPNOW_RX[%s]", type);
        printSummary(msg);

    /* ── ESP-NOW transmitted ──────────────────────────────────────────── */
    } else if (strcmp(dbgType, "espnow_tx") == 0) {
        g_status.slaveLinked = true;
        g_status.lastEspnowMs = millis();
        g_status.errorState = false;

        char type[32] = {0};
        jsonGetStr(json, "type", type, sizeof(type));

        char slaveMac[18] = {0};
        if (jsonGetStr(json, "mac", slaveMac, sizeof(slaveMac))) {
            strcpy(g_status.slaveMac, slaveMac);
        }

        char msg[64];
        snprintf(msg, sizeof(msg), "ESPNOW_TX[%s]", type);
        printSummary(msg);

    /* ── USB Command Received ─────────────────────────────────────────── */
    } else if (strcmp(dbgType, "usb_rx") == 0) {
        g_status.usbConnected = true;
        char cmd[32] = {0};
        jsonGetStr(json, "cmd", cmd, sizeof(cmd));
        char msg[64];
        snprintf(msg, sizeof(msg), "USB_RX[%s]", cmd);
        printSummary(msg);

    /* ── USB Event Transmitted ────────────────────────────────────────── */
    } else if (strcmp(dbgType, "usb_tx") == 0) {
        g_status.usbConnected = true;
        char evt[32] = {0};
        jsonGetStr(json, "evt", evt, sizeof(evt));
        char msg[64];
        snprintf(msg, sizeof(msg), "USB_TX[%s]", evt);
        printSummary(msg);

    /* ── Error ────────────────────────────────────────────────────────── */
    } else if (strcmp(dbgType, "error") == 0) {
        char errMsg[64] = {0};
        jsonGetStr(json, "msg", errMsg, sizeof(errMsg));
        g_status.errorState = true;
        char msg[80];
        snprintf(msg, sizeof(msg), "ERROR[%s]", errMsg);
        printSummary(msg);
    }

    updateLEDs();
}

/* ──────────────────────────────────────────────────────────────────────── */
/* Arduino setup / loop                                                     */
/* ──────────────────────────────────────────────────────────────────────── */

void setup()
{
    /* Force USB re-enumeration by pulling PA12 (USB D+) low briefly.
     * This is a critical hardware workaround for STM32 Blue Pill boards
     * to ensure the host PC detects the virtual COM port. */
    pinMode(PA12, OUTPUT);
    digitalWrite(PA12, LOW);
    delay(100);
    pinMode(PA12, INPUT);
    delay(100);

    /* USB CDC — PC side */
    Serial.begin(115200);
    while (!Serial && millis() < 3000) {}  /* wait up to 3s for USB enumeration */

    /* UART2 — ESP32 Master side */
    MasterSerial.begin(MASTER_BAUD);

    /* Status LEDs */
    pinMode(LED_ALIVE,     OUTPUT);
    pinMode(LED_LINK,      OUTPUT);
    pinMode(LED_CONDUCTOR, OUTPUT);
    pinMode(LED_ERROR,     OUTPUT);

    /* Initial state: all LEDs off (PC13 is inverted: HIGH = off for onboard LED) */
    digitalWrite(LED_ALIVE,     HIGH);
    digitalWrite(LED_LINK,      LOW);
    digitalWrite(LED_CONDUCTOR, LOW);
    digitalWrite(LED_ERROR,     LOW);

    Serial.println("[TOMS-MON] STM32 Master Monitor ready");
    Serial.println("[TOMS-MON] Waiting for ESP32 Master debug packets on UART2 (PA3 RX)...");
    Serial.println("[TOMS-MON] -------------------------------------------------------");
    Serial.println("[TOMS-MON] LEDs: PB0=LINK(green), PB1=CONDUCTOR(yellow), PB12=ERROR(red)");
    Serial.println("[TOMS-MON] -------------------------------------------------------");
}

void loop()
{
    /* ── Receive bytes from ESP32 Master ────────────────────────────── */
    while (MasterSerial.available()) {
        char c = (char)MasterSerial.read();

        if (c == '\n' || c == '\r') {
            if (g_rxIdx > 0) {
                g_rxBuf[g_rxIdx] = '\0';
                if (g_rxBuf[0] == '{') {
                    parsePacket(g_rxBuf);
                } else {
                    /* Regular console log statement from ESP32 */
                    Serial.print("[LOG] ");
                    Serial.println(g_rxBuf);
                }
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
    if (g_status.slaveLinked &&
        g_status.lastEspnowMs != 0 &&
        (millis() - g_status.lastEspnowMs) > LINK_TIMEOUT_MS)
    {
        g_status.slaveLinked = false;
        Serial.println("[TOMS-MON] WARNING: No ESP-NOW activity for 15s — slave may be disconnected");
        updateLEDs();
    }

    /* ── Telemetry packet timeout detection (disconnected from Master) ─ */
    if (g_status.usbConnected &&
        g_status.lastPacketMs != 0 &&
        (millis() - g_status.lastPacketMs) > LINK_TIMEOUT_MS)
    {
        g_status.usbConnected = false;
        Serial.println("[TOMS-MON] WARNING: No telemetry packets for 15s — Master may be disconnected");
        updateLEDs();
    }

    /* ── Alive heartbeat blink (PC13, inverted) ─────────────────────── */
    if (millis() - g_lastBlink >= BLINK_INTERVAL_MS) {
        g_lastBlink  = millis();
        g_aliveState = !g_aliveState;
        /* PC13 is inverted: LOW = LED on */
        digitalWrite(LED_ALIVE, g_aliveState ? LOW : HIGH);
    }

    /* ── PC → Master passthrough (optional command injection) ────────── */
    while (Serial.available()) {
        char c = (char)Serial.read();
        MasterSerial.write(c);  /* forward to Master UART RX (GPIO6) */
    }
}
