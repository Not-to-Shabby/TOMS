/**
 * @file usb_cdc.h
 * @brief TOMS USB CDC-ACM device interface for phone communication.
 *
 * Configures the ESP32-S3 as a USB CDC serial device using TinyUSB.
 * The phone (Flutter app) connects via USB OTG and communicates using
 * a JSON-line protocol over this virtual serial port.
 */

#ifndef TOMS_USB_CDC_H
#define TOMS_USB_CDC_H

#include <stdint.h>
#include <stddef.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

/** USB CDC receive buffer size */
#define TOMS_USB_RX_BUF_SIZE    1024

/** Maximum single JSON-line message length */
#define TOMS_USB_MAX_MSG_LEN    512

/**
 * @brief Callback for received USB messages.
 * @param data    Null-terminated JSON-line string.
 * @param len     Length excluding null terminator.
 */
typedef void (*toms_usb_recv_cb_t)(const char *data, size_t len);

/**
 * @brief Initialize USB CDC device.
 *
 * Sets up TinyUSB with CDC-ACM descriptors. The ESP32-S3 will appear
 * as a standard USB serial device to the connected Android phone.
 *
 * @return ESP_OK on success.
 */
int toms_usb_cdc_init(void);

/**
 * @brief Send a JSON-line message to the phone.
 *
 * Appends '\n' delimiter automatically.
 *
 * @param json      JSON string (null-terminated, without trailing newline).
 * @return Number of bytes sent, or -1 on error.
 */
int toms_usb_cdc_send(const char *json);

/**
 * @brief Send raw bytes to the phone.
 */
int toms_usb_cdc_send_raw(const uint8_t *data, size_t len);

/**
 * @brief Register a callback for received messages.
 *
 * Messages are delimited by '\n'. The callback is invoked from the
 * USB CDC processing task.
 */
void toms_usb_cdc_set_recv_cb(toms_usb_recv_cb_t cb);

/**
 * @brief Check if USB is connected and ready.
 */
bool toms_usb_cdc_is_connected(void);

/**
 * @brief USB CDC processing task (call from FreeRTOS).
 *
 * Continuously reads from USB, parses JSON lines, and invokes the
 * registered callback. This function never returns.
 *
 * @param arg   Unused (pass NULL).
 */
void toms_usb_cdc_task(void *arg);

#ifdef __cplusplus
}
#endif

#endif /* TOMS_USB_CDC_H */
