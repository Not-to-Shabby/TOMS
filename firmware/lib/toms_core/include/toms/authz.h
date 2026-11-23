/* Who may send which diag (0x0E) command. Pure code: the firmware gathers the facts about the link and
 * the message, this decides. Runs on the host under test. PROTOCOL.md section 8 has the table.
 *
 *   cmd  what                          link level  signature                    session
 *   00   open a diagnostic session     required    -                            -
 *   01-04 bench tests                  -           -                            session, local mode or dev
 *   05   silence the alarm             required    signed once a key exists     see below
 *   06   store the signing key         required    -                            -
 *   07   choose the message layout     required    -                            -
 *   08   replace the signing key       required    signed under the current key (checked by the keyring)
 *
 * "required" = the link must be authenticated (CONFIG_TOMS_AUTH_LEVEL, 4 where Secure Connections
 * only is on, else 3). CONFIG_TOMS_DEV_MODE builds skip the link check and accept an unsigned 05.
 *
 * Interim rule for 05 (until the phone signs it): a machine that has a key accepts an unsigned 05
 * only from a bonded central whose connection has an open diagnostic session (command 00), and the
 * caller logs it. A signed 05 is always checked; a wrong signature is always refused. */
#ifndef TOMS_AUTHZ_H
#define TOMS_AUTHZ_H

#include <stdbool.h>
#include <stdint.h>

#include <toms/sig.h>

#define TOMS_DIAG_UNLOCK 0x00u
#define TOMS_DIAG_BUZZER 0x01u
#define TOMS_DIAG_SCREEN 0x02u
#define TOMS_DIAG_BACKLIGHT 0x03u
#define TOMS_DIAG_NFC 0x04u
#define TOMS_DIAG_ALARM_CLEAR 0x05u
#define TOMS_DIAG_PROVISION 0x06u
#define TOMS_DIAG_WIRE 0x07u
#define TOMS_DIAG_KEY_REPLACE 0x08u

/* The characteristic suffix the signature covers for every diag value. */
#define TOMS_DIAG_SUFFIX 0x0Eu

struct toms_authz_in {
	uint8_t cmd;
	uint8_t link_level;     /* what the stack reports for this connection (0-4) */
	uint8_t required_level; /* CONFIG_TOMS_AUTH_LEVEL */
	bool dev_mode;
	bool key_present;
	int sig;                /* TOMS_SIG_OK, TOMS_SIG_ABSENT, or negative: wrong or malformed */
	bool bonded;            /* this peer has a bond */
	bool session_open;      /* command 00 was accepted on this connection */
	bool local_diag_mode;   /* diagnostic mode was switched on at the machine (button) */
};

enum toms_authz_result {
	TOMS_AUTHZ_ALLOW = 0,
	TOMS_AUTHZ_ALLOW_UNSIGNED_INTERIM, /* unsigned 05 on the interim rule: log it */
	TOMS_AUTHZ_DENY_LINK,              /* link not authenticated enough */
	TOMS_AUTHZ_DENY_UNSIGNED,          /* a key exists and the 05 carries no signature */
	TOMS_AUTHZ_DENY_BAD_SIG,           /* signature wrong or the frame malformed */
	TOMS_AUTHZ_DENY_NO_SESSION,        /* bench command without a session */
};

/* True for the commands that need the authenticated link. */
bool toms_authz_needs_link(uint8_t cmd);

enum toms_authz_result toms_authz_diag(const struct toms_authz_in *in);

#endif /* TOMS_AUTHZ_H */
