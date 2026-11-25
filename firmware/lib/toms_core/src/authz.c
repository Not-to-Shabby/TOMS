#include <toms/authz.h>

bool toms_authz_needs_link(uint8_t cmd)
{
	return cmd == TOMS_DIAG_UNLOCK || cmd == TOMS_DIAG_ALARM_CLEAR ||
	       cmd == TOMS_DIAG_PROVISION || cmd == TOMS_DIAG_WIRE || cmd == TOMS_DIAG_KEY_REPLACE;
}

enum toms_authz_result toms_authz_diag(const struct toms_authz_in *in)
{
	if (toms_authz_needs_link(in->cmd) && !in->dev_mode && in->link_level < in->required_level) {
		return TOMS_AUTHZ_DENY_LINK;
	}

	switch (in->cmd) {
	case TOMS_DIAG_ALARM_CLEAR:
		if (!in->key_present) {
			return TOMS_AUTHZ_ALLOW; /* nothing to sign with yet */
		}
		if (in->sig == TOMS_SIG_OK) {
			return TOMS_AUTHZ_ALLOW;
		}
		if (in->sig != TOMS_SIG_ABSENT) {
			return TOMS_AUTHZ_DENY_BAD_SIG; /* a wrong signature is refused in every build */
		}
		if (in->dev_mode) {
			return TOMS_AUTHZ_ALLOW;
		}
		return (in->bonded && in->session_open) ? TOMS_AUTHZ_ALLOW_UNSIGNED_INTERIM
							: TOMS_AUTHZ_DENY_UNSIGNED;

	case TOMS_DIAG_UNLOCK:
	case TOMS_DIAG_PROVISION:
	case TOMS_DIAG_WIRE:
	case TOMS_DIAG_KEY_REPLACE:
		return TOMS_AUTHZ_ALLOW;

	default: /* 01-04 and anything unknown: the bench commands */
		return (in->session_open || in->local_diag_mode || in->dev_mode)
			       ? TOMS_AUTHZ_ALLOW
			       : TOMS_AUTHZ_DENY_NO_SESSION;
	}
}
