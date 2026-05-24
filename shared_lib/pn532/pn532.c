/**
 * @file pn532.c
 * @brief PN532 NFC controller I2C driver implementation.
 *
 * Handles frame encoding/decoding, ACK verification, IRQ-based ready
 * detection, and all NFC-DEP Initiator/Target commands.
 */

#include "pn532.h"

#include <string.h>
#include "esp_log.h"
#include "driver/i2c.h"
#include "driver/gpio.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/semphr.h"

static const char *TAG = "pn532";

/* ── IRQ Notification ─────────────────────────────────────────────────── */

static TaskHandle_t s_irq_task_handle = NULL;

static void IRAM_ATTR pn532_irq_isr(void *arg)
{
    (void)arg;
    BaseType_t xHigherPriorityTaskWoken = pdFALSE;
    if (s_irq_task_handle) {
        vTaskNotifyGiveFromISR(s_irq_task_handle, &xHigherPriorityTaskWoken);
    }
    portYIELD_FROM_ISR(xHigherPriorityTaskWoken);
}

/* ── I2C Helpers ──────────────────────────────────────────────────────── */

static esp_err_t i2c_write(pn532_handle_t *h, const uint8_t *data, size_t len)
{
    return i2c_master_write_to_device(h->config.i2c_port, PN532_I2C_ADDR,
                                       data, len, pdMS_TO_TICKS(100));
}

static esp_err_t i2c_read(pn532_handle_t *h, uint8_t *data, size_t len)
{
    return i2c_master_read_from_device(h->config.i2c_port, PN532_I2C_ADDR,
                                        data, len, pdMS_TO_TICKS(100));
}

/* ── Ready Detection ──────────────────────────────────────────────────── */

bool pn532_wait_ready(pn532_handle_t *h, uint32_t timeout_ms)
{
    if (h->config.irq_pin >= 0) {
        /* IRQ-based: wait for falling edge notification */
        s_irq_task_handle = xTaskGetCurrentTaskHandle();
        ulTaskNotifyTake(pdTRUE, 0); /* Clear any pending */

        if (gpio_get_level(h->config.irq_pin) == 0) {
            return true; /* Already ready */
        }

        uint32_t got = ulTaskNotifyTake(pdTRUE, pdMS_TO_TICKS(timeout_ms));
        return (got > 0);
    }

    /* Polling fallback: read I2C status byte */
    TickType_t start = xTaskGetTickCount();
    while ((xTaskGetTickCount() - start) < pdMS_TO_TICKS(timeout_ms)) {
        uint8_t status = 0;
        if (i2c_read(h, &status, 1) == ESP_OK) {
            if (status & PN532_I2C_READY_BIT) return true;
        }
        vTaskDelay(pdMS_TO_TICKS(5));
    }
    return false;
}

/* ── Frame Encoding ───────────────────────────────────────────────────── */

esp_err_t pn532_write_command(pn532_handle_t *h, const uint8_t *cmd, uint8_t cmd_len)
{
    /*
     * I2C frame layout:
     * [PREAMBLE][START1][START2][LEN][LCS][TFI=0xD4][cmd...][DCS][POSTAMBLE]
     */
    uint8_t frame[cmd_len + 8];
    uint8_t len_field = cmd_len + 1; /* +1 for TFI */
    uint8_t lcs = (~len_field) + 1;  /* LEN + LCS = 0x00 mod 256 */

    frame[0] = PN532_PREAMBLE;
    frame[1] = PN532_STARTCODE1;
    frame[2] = PN532_STARTCODE2;
    frame[3] = len_field;
    frame[4] = lcs;
    frame[5] = PN532_HOSTTOPN532;

    uint8_t dcs_sum = PN532_HOSTTOPN532;
    for (int i = 0; i < cmd_len; i++) {
        frame[6 + i] = cmd[i];
        dcs_sum += cmd[i];
    }
    frame[6 + cmd_len] = (~dcs_sum) + 1; /* DCS */
    frame[7 + cmd_len] = PN532_POSTAMBLE;

    esp_err_t err = i2c_write(h, frame, cmd_len + 8);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "I2C write failed: %s", esp_err_to_name(err));
        return err;
    }

    /* Wait for ACK frame */
    vTaskDelay(pdMS_TO_TICKS(1));
    if (!pn532_wait_ready(h, 100)) {
        ESP_LOGE(TAG, "ACK timeout");
        return ESP_ERR_TIMEOUT;
    }

    uint8_t ack[7]; /* 1 status byte + 6 ACK bytes */
    err = i2c_read(h, ack, 7);
    if (err != ESP_OK) return err;

    /* Validate ACK: status[0] + [0x00 0x00 0xFF 0x00 0xFF 0x00] */
    if (ack[1] != 0x00 || ack[2] != 0x00 || ack[3] != 0xFF ||
        ack[4] != 0x00 || ack[5] != 0xFF) {
        ESP_LOGE(TAG, "Invalid ACK frame");
        return ESP_ERR_INVALID_RESPONSE;
    }

    return ESP_OK;
}

/* ── Frame Decoding ───────────────────────────────────────────────────── */

esp_err_t pn532_read_response(pn532_handle_t *h, uint8_t *buf, uint8_t *len,
                               uint32_t timeout_ms)
{
    if (!pn532_wait_ready(h, timeout_ms)) {
        return ESP_ERR_TIMEOUT;
    }

    /*
     * I2C read: [RDY][PREAMBLE][START1][START2][LEN][LCS][TFI=0xD5][Data...][DCS][POSTAMBLE]
     * Read header first to get length, then full frame.
     */
    uint8_t header[7];
    esp_err_t err = i2c_read(h, header, 7);
    if (err != ESP_OK) return err;

    /* header[0] = ready status byte */
    if (!(header[0] & PN532_I2C_READY_BIT)) {
        return ESP_ERR_NOT_FINISHED;
    }

    /* Validate start code */
    if (header[1] != PN532_PREAMBLE || header[2] != PN532_STARTCODE1 ||
        header[3] != PN532_STARTCODE2) {
        ESP_LOGE(TAG, "Invalid start code: %02X %02X %02X", header[1], header[2], header[3]);
        return ESP_ERR_INVALID_RESPONSE;
    }

    uint8_t data_len = header[4]; /* LEN = TFI + response code + params */
    uint8_t lcs = header[5];

    /* Validate LEN checksum */
    if ((uint8_t)(data_len + lcs) != 0) {
        ESP_LOGE(TAG, "LEN checksum failed");
        return ESP_ERR_INVALID_CRC;
    }

    if (data_len < 2 || data_len > PN532_MAX_DATA_LEN) {
        ESP_LOGE(TAG, "Invalid data length: %d", data_len);
        return ESP_ERR_INVALID_SIZE;
    }

    /* TFI is already in header[6] */
    if (header[6] != PN532_PN532TOHOST) {
        ESP_LOGE(TAG, "Invalid TFI: 0x%02X", header[6]);
        return ESP_ERR_INVALID_RESPONSE;
    }

    /* Read remaining data: (data_len - 1) bytes + DCS + postamble */
    uint8_t remaining = data_len - 1 + 2;
    uint8_t rest[remaining];
    err = i2c_read(h, rest, remaining);
    if (err != ESP_OK) return err;

    /* Verify DCS: TFI + all data bytes + DCS = 0x00 */
    uint8_t dcs_sum = PN532_PN532TOHOST;
    for (int i = 0; i < data_len - 1; i++) {
        dcs_sum += rest[i];
    }
    dcs_sum += rest[data_len - 1]; /* DCS byte */
    if (dcs_sum != 0) {
        ESP_LOGE(TAG, "DCS checksum failed");
        return ESP_ERR_INVALID_CRC;
    }

    /* Copy response data (skip response code byte at rest[0]) */
    uint8_t out_len = data_len - 2; /* minus TFI and response code */
    if (out_len > *len) out_len = *len;

    /* rest[0] = response command code (e.g. 0x03 for GetFirmwareVersion+1) */
    /* We include the response code in the output for caller to check */
    out_len = data_len - 1; /* everything after TFI */
    if (out_len > *len) out_len = *len;
    memcpy(buf, rest, out_len);
    *len = out_len;

    return ESP_OK;
}

/* ── Initialization ───────────────────────────────────────────────────── */

esp_err_t pn532_init(pn532_handle_t *handle, const pn532_config_t *config)
{
    if (!handle || !config) return ESP_ERR_INVALID_ARG;

    memcpy(&handle->config, config, sizeof(pn532_config_t));
    handle->initialized = false;

    /* Configure I2C master */
    i2c_config_t i2c_cfg = {
        .mode             = I2C_MODE_MASTER,
        .sda_io_num       = config->sda_pin,
        .scl_io_num       = config->scl_pin,
        .sda_pullup_en    = GPIO_PULLUP_ENABLE,
        .scl_pullup_en    = GPIO_PULLUP_ENABLE,
        .master.clk_speed = config->i2c_freq_hz,
    };

    esp_err_t err = i2c_param_config(config->i2c_port, &i2c_cfg);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "I2C config failed: %s", esp_err_to_name(err));
        return err;
    }

    err = i2c_driver_install(config->i2c_port, I2C_MODE_MASTER, 0, 0, 0);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "I2C driver install failed: %s", esp_err_to_name(err));
        return err;
    }

    /* Configure IRQ pin if provided */
    if (config->irq_pin >= 0) {
        gpio_config_t irq_cfg = {
            .pin_bit_mask = (1ULL << config->irq_pin),
            .mode         = GPIO_MODE_INPUT,
            .pull_up_en   = GPIO_PULLUP_ENABLE,
            .pull_down_en = GPIO_PULLDOWN_DISABLE,
            .intr_type    = GPIO_INTR_NEGEDGE,
        };
        gpio_config(&irq_cfg);
        gpio_install_isr_service(0);
        gpio_isr_handler_add(config->irq_pin, pn532_irq_isr, NULL);
        ESP_LOGI(TAG, "IRQ configured on GPIO %d", config->irq_pin);
    }

    /* Small delay for PN532 power-up */
    vTaskDelay(pdMS_TO_TICKS(50));

    /* Verify firmware version */
    uint8_t ic = 0, ver = 0, rev = 0;
    err = pn532_get_firmware_version(handle, &ic, &ver, &rev);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "Failed to read PN532 firmware version");
        return err;
    }
    ESP_LOGI(TAG, "PN532 firmware: IC=0x%02X ver=%d.%d", ic, ver, rev);

    if (ic != 0x07) {
        ESP_LOGW(TAG, "Unexpected IC type (expected 0x07 for PN532)");
    }

    /* SAM configuration — normal mode */
    err = pn532_sam_configuration(handle);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "SAM configuration failed");
        return err;
    }

    handle->initialized = true;
    ESP_LOGI(TAG, "PN532 initialized (SDA=%d, SCL=%d, IRQ=%d)",
             config->sda_pin, config->scl_pin, config->irq_pin);
    return ESP_OK;
}

/* ── GetFirmwareVersion ───────────────────────────────────────────────── */

esp_err_t pn532_get_firmware_version(pn532_handle_t *h,
                                      uint8_t *ic, uint8_t *ver, uint8_t *rev)
{
    uint8_t cmd[] = { PN532_CMD_GETFIRMWAREVERSION };
    esp_err_t err = pn532_write_command(h, cmd, 1);
    if (err != ESP_OK) return err;

    uint8_t buf[16];
    uint8_t buf_len = sizeof(buf);
    err = pn532_read_response(h, buf, &buf_len, 500);
    if (err != ESP_OK) return err;

    /* buf[0] = response code (0x03), buf[1..4] = IC, Ver, Rev, Support */
    if (buf_len >= 5 && buf[0] == (PN532_CMD_GETFIRMWAREVERSION + 1)) {
        if (ic)  *ic  = buf[1];
        if (ver) *ver = buf[2];
        if (rev) *rev = buf[3];
        return ESP_OK;
    }

    return ESP_ERR_INVALID_RESPONSE;
}

/* ── SAMConfiguration ─────────────────────────────────────────────────── */

esp_err_t pn532_sam_configuration(pn532_handle_t *h)
{
    /* Mode=0x01 (normal), Timeout=0x14 (1s), IRQ=0x01 (use IRQ pin) */
    uint8_t cmd[] = { PN532_CMD_SAMCONFIGURATION, 0x01, 0x14, 0x01 };
    esp_err_t err = pn532_write_command(h, cmd, sizeof(cmd));
    if (err != ESP_OK) return err;

    uint8_t buf[4];
    uint8_t buf_len = sizeof(buf);
    err = pn532_read_response(h, buf, &buf_len, 500);
    if (err != ESP_OK) return err;

    if (buf[0] == (PN532_CMD_SAMCONFIGURATION + 1)) {
        return ESP_OK;
    }
    return ESP_ERR_INVALID_RESPONSE;
}

/* ── InJumpForDEP (Initiator) ─────────────────────────────────────────── */

esp_err_t pn532_in_jump_for_dep(pn532_handle_t *h, uint8_t baud,
                                 uint8_t *tg, uint8_t *tg_nfcid3,
                                 uint32_t timeout_ms)
{
    /*
     * InJumpForDEP command:
     *   [0x56][ActPass][Baud][Next]
     *   ActPass = 0x01 (active), Baud = 0x02 (424kbps)
     *   Next = 0x00 (no optional fields → auto-generate NFCID3)
     */
    uint8_t cmd[] = {
        PN532_CMD_INJUMPFORDEP,
        PN532_DEP_ACTIVE,   /* Active communication */
        baud,               /* 424 kbps */
        0x00                /* No passive initiator data, no NFCID3, no Gi */
    };

    esp_err_t err = pn532_write_command(h, cmd, sizeof(cmd));
    if (err != ESP_OK) return err;

    uint8_t buf[64];
    uint8_t buf_len = sizeof(buf);
    err = pn532_read_response(h, buf, &buf_len, timeout_ms);
    if (err != ESP_OK) return err;

    /* Response: [0x57][Status][Tg][NFCID3t(10)][DIDt][BSt][BRt][TO][PPt][Gt...] */
    if (buf[0] != (PN532_CMD_INJUMPFORDEP + 1)) {
        ESP_LOGE(TAG, "Unexpected InJumpForDEP response: 0x%02X", buf[0]);
        return ESP_ERR_INVALID_RESPONSE;
    }

    uint8_t status = buf[1];
    if (status != 0x00) {
        ESP_LOGD(TAG, "InJumpForDEP failed, status=0x%02X", status);
        return ESP_ERR_TIMEOUT;
    }

    if (tg) *tg = buf[2];

    /* Extract NFCID3t (10 bytes starting at buf[3]) */
    if (tg_nfcid3 && buf_len >= 13) {
        memcpy(tg_nfcid3, &buf[3], 10);
    }

    ESP_LOGI(TAG, "NFC-DEP link established (target=%d)", buf[2]);
    return ESP_OK;
}

/* ── InDataExchange (Initiator) ───────────────────────────────────────── */

esp_err_t pn532_in_data_exchange(pn532_handle_t *h,
                                  const uint8_t *send, uint8_t send_len,
                                  uint8_t *resp, uint8_t *resp_len,
                                  uint32_t timeout_ms)
{
    /* Command: [0x40][Tg=0x01][DataOut...] */
    uint8_t cmd[send_len + 2];
    cmd[0] = PN532_CMD_INDATAEXCHANGE;
    cmd[1] = 0x01; /* Target number */
    memcpy(&cmd[2], send, send_len);

    esp_err_t err = pn532_write_command(h, cmd, send_len + 2);
    if (err != ESP_OK) return err;

    uint8_t buf[64];
    uint8_t buf_len = sizeof(buf);
    err = pn532_read_response(h, buf, &buf_len, timeout_ms);
    if (err != ESP_OK) return err;

    /* Response: [0x41][Status][DataIn...] */
    if (buf[0] != (PN532_CMD_INDATAEXCHANGE + 1)) {
        return ESP_ERR_INVALID_RESPONSE;
    }

    if (buf[1] != 0x00) {
        ESP_LOGW(TAG, "InDataExchange error status=0x%02X", buf[1]);
        return ESP_FAIL;
    }

    uint8_t data_out_len = buf_len - 2;
    if (data_out_len > *resp_len) data_out_len = *resp_len;
    memcpy(resp, &buf[2], data_out_len);
    *resp_len = data_out_len;

    return ESP_OK;
}

/* ── InRelease ────────────────────────────────────────────────────────── */

esp_err_t pn532_in_release(pn532_handle_t *h, uint8_t tg)
{
    uint8_t cmd[] = { PN532_CMD_INRELEASE, tg };
    esp_err_t err = pn532_write_command(h, cmd, sizeof(cmd));
    if (err != ESP_OK) return err;

    uint8_t buf[4];
    uint8_t buf_len = sizeof(buf);
    return pn532_read_response(h, buf, &buf_len, 500);
}

/* ── TgInitAsTarget (Target/Slave) ────────────────────────────────────── */

esp_err_t pn532_tg_init_as_target(pn532_handle_t *h,
                                   const uint8_t *nfcid3,
                                   uint32_t timeout_ms)
{
    /*
     * TgInitAsTarget: [0x8C][Mode][MifareParams(6)][FelicaParams(18)][NFCID3t(10)][LenGt][LenTk]
     * Mode = 0x02 (DEP only)
     */
    uint8_t cmd[39];
    memset(cmd, 0, sizeof(cmd));

    cmd[0] = PN532_CMD_TGINITASTARGET;
    cmd[1] = PN532_TG_MODE_DEP_ONLY;

    /* Mifare params (6 bytes): SENS_RES(2) + NFCID1t(3) + SEL_RES(1) */
    cmd[2] = 0x08; cmd[3] = 0x00;                  /* SENS_RES */
    cmd[4] = nfcid3[0]; cmd[5] = nfcid3[1]; cmd[6] = nfcid3[2]; /* NFCID1t */
    cmd[7] = 0x40;                                  /* SEL_RES */

    /* FeliCa params (18 bytes): NFCID2t(8) + PAD(8) + SystemCode(2) */
    cmd[8]  = 0x01; cmd[9]  = 0xFE;
    cmd[10] = nfcid3[0]; cmd[11] = nfcid3[1]; cmd[12] = nfcid3[2];
    cmd[13] = nfcid3[3]; cmd[14] = nfcid3[4]; cmd[15] = nfcid3[5];
    /* PAD = zeros (cmd[16..23] already 0) */
    cmd[24] = 0xFF; cmd[25] = 0xFF; /* SystemCode */

    /* NFCID3t (10 bytes) — embed eFuse MAC for identification */
    memcpy(&cmd[26], nfcid3, 10);

    /* LenGt = 0, LenTk = 0 */
    cmd[36] = 0x00;
    cmd[37] = 0x00;

    esp_err_t err = pn532_write_command(h, cmd, 38);
    if (err != ESP_OK) return err;

    uint8_t buf[32];
    uint8_t buf_len = sizeof(buf);
    err = pn532_read_response(h, buf, &buf_len, timeout_ms);
    if (err != ESP_OK) return err;

    /* Response: [0x8D][Mode][Initiator command...] */
    if (buf[0] != (PN532_CMD_TGINITASTARGET + 1)) {
        return ESP_ERR_INVALID_RESPONSE;
    }

    ESP_LOGI(TAG, "Target activated by initiator (mode=0x%02X)", buf[1]);
    return ESP_OK;
}

/* ── TgGetData (Target/Slave) ─────────────────────────────────────────── */

esp_err_t pn532_tg_get_data(pn532_handle_t *h,
                             uint8_t *data, uint8_t *data_len,
                             uint32_t timeout_ms)
{
    uint8_t cmd[] = { PN532_CMD_TGGETDATA };
    esp_err_t err = pn532_write_command(h, cmd, 1);
    if (err != ESP_OK) return err;

    uint8_t buf[64];
    uint8_t buf_len = sizeof(buf);
    err = pn532_read_response(h, buf, &buf_len, timeout_ms);
    if (err != ESP_OK) return err;

    /* Response: [0x87][Status][DataIn...] */
    if (buf[0] != (PN532_CMD_TGGETDATA + 1)) {
        return ESP_ERR_INVALID_RESPONSE;
    }

    if (buf[1] != 0x00) {
        ESP_LOGW(TAG, "TgGetData error status=0x%02X", buf[1]);
        /* Status 0x29 = released by initiator */
        return (buf[1] == 0x29) ? ESP_ERR_TIMEOUT : ESP_FAIL;
    }

    uint8_t out_len = buf_len - 2;
    if (out_len > *data_len) out_len = *data_len;
    memcpy(data, &buf[2], out_len);
    *data_len = out_len;

    return ESP_OK;
}

/* ── TgSetData (Target/Slave) ─────────────────────────────────────────── */

esp_err_t pn532_tg_set_data(pn532_handle_t *h,
                             const uint8_t *data, uint8_t data_len,
                             uint32_t timeout_ms)
{
    uint8_t cmd[data_len + 1];
    cmd[0] = PN532_CMD_TGSETDATA;
    memcpy(&cmd[1], data, data_len);

    esp_err_t err = pn532_write_command(h, cmd, data_len + 1);
    if (err != ESP_OK) return err;

    uint8_t buf[4];
    uint8_t buf_len = sizeof(buf);
    err = pn532_read_response(h, buf, &buf_len, timeout_ms);
    if (err != ESP_OK) return err;

    /* Response: [0x8F][Status] */
    if (buf[0] != (PN532_CMD_TGSETDATA + 1)) {
        return ESP_ERR_INVALID_RESPONSE;
    }

    if (buf[1] != 0x00) {
        ESP_LOGW(TAG, "TgSetData error status=0x%02X", buf[1]);
        return ESP_FAIL;
    }

    return ESP_OK;
}
