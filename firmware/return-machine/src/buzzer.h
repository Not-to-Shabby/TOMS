/* Buzzer patterns. The buzzer is a fixed-pitch active unit behind a low-side
 * NPN (GPIO6 high = on), so everything is expressed as beep patterns. */
#ifndef TOMS_BUZZER_H
#define TOMS_BUZZER_H

enum toms_tone {
	TOMS_TONE_BOOT = 0,   /* rising 3 quick beeps: boot / reconnect */
	TOMS_TONE_OK = 1,     /* 1 x 80 ms: card read, paid */
	TOMS_TONE_UNPAID = 2, /* 3 x 200 ms: unpaid card returned */
	TOMS_TONE_UNKNOWN = 3,/* 2 x 300 ms: card not in the registry */
	TOMS_TONE_UNVERIFIED = 4, /* 2 x 60 ms: BLE down or reply late */
	TOMS_TONE_DUPLICATE = 5,  /* 1 x 400 ms: card read twice at exit */
};

void buzzer_init(void);
/* Non-blocking; playing a new tone replaces the one in progress. */
void buzzer_play(enum toms_tone tone);

#endif /* TOMS_BUZZER_H */
