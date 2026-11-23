package ph.toms.conductor.ble

/**
 * What the phone can know about how it is paired with the Return Machine, and what to tell the conductor
 * (firmware/PROTOCOL.md section 8.1 and 8.6).
 *
 * The machine only pairs while its pairing window is open (button held at power-up, 60 s), only for one bond, and only
 * with an authenticated pairing (numeric comparison or a passkey; Just Works is refused). Android's public API does not let an app
 * choose the pairing method, and cannot ask for a pairing level: the phone announces its own input and display ability and the
 * system picks. What the app can do, and this class does, is watch what happened and say so in plain words:
 *
 *  - the pairing request's variant: numeric comparison (2) or a passkey (1, 4) is an authenticated pairing; anything else is not,
 *    and the machine will not accept it, so the conductor is told to pair again the right way;
 *  - a pairing that ends without a bond: the machine most likely refused it because its window was closed (the same end is seen
 *    when a person cancels, or the numbers did not match, so the text says "did not accept", not "window closed");
 *  - a write the machine refused for lack of an authenticated, encrypted link.
 *
 * Pure logic, no Android types, so each transition is a plain test. The state is per connection and starts empty.
 */
class RmPairingTracker(private val publish: (RmKeyStatus?) -> Unit) {

    enum class Phase { IDLE, BONDING, BONDED, REFUSED, WEAK, LINK_REFUSED }

    var phase: Phase = Phase.IDLE
        private set

    private var variantSeen = false

    /** A new connection: the earlier connection's notice is over. [bonded] is whether Android already holds a bond with this machine. */
    fun onConnected(bonded: Boolean) {
        variantSeen = false
        phase = if (bonded) Phase.BONDED else Phase.IDLE
        publish(null)
    }

    fun onDisconnected() {
        phase = Phase.IDLE
        variantSeen = false
        // The notice stays: it is what the conductor needs to read after the link dropped because of it.
    }

    /** The phone asked Android to pair. */
    fun onBondRequested() {
        if (phase != Phase.BONDED) {
            phase = Phase.BONDING
            variantSeen = false
        }
    }

    /** Android showed a pairing request with [variant] (BluetoothDevice.EXTRA_PAIRING_VARIANT). */
    fun onPairingRequest(variant: Int) {
        variantSeen = true
        if (variant !in AUTHENTICATED_VARIANTS) {
            phase = Phase.WEAK
            publish(
                RmKeyStatus(
                    "This phone paired without comparing a number, so the Return Machine will not trust it. Remove the Return Machine " +
                        "from Android's Bluetooth settings, hold its button while switching it on, and pair again within 60 seconds.",
                    RmKeyStatus.Severity.BAD,
                ),
            )
        }
    }

    /** Android's bond state for this machine changed ([previous] and [now] are BluetoothDevice.BOND_* values). */
    fun onBondChanged(previous: Int, now: Int) {
        when {
            now == BOND_BONDED -> {
                if (phase != Phase.WEAK) {
                    phase = Phase.BONDED
                    publish(null)
                }
            }
            now == BOND_BONDING -> if (phase != Phase.WEAK) phase = Phase.BONDING
            now == BOND_NONE && previous == BOND_BONDING -> {
                if (phase != Phase.WEAK) {
                    phase = Phase.REFUSED
                    publish(
                        RmKeyStatus(
                            "The Return Machine did not accept pairing. Switch it off, hold its button while switching it on, then " +
                                "pair within 60 seconds and check that the number on its screen is the same as on this phone.",
                            RmKeyStatus.Severity.BAD,
                        ),
                    )
                }
            }
            now == BOND_NONE && previous == BOND_BONDED -> {
                // The pairing was removed (in Android settings, or by the machine).
                phase = Phase.IDLE
            }
        }
    }

    /** A GATT write came back with ATT error [attStatus]; 5 (not authenticated) and 15 (not encrypted) mean the link is not good enough. */
    fun onWriteRefused(attStatus: Int) {
        if (attStatus != GATT_INSUFFICIENT_AUTHENTICATION && attStatus != GATT_INSUFFICIENT_ENCRYPTION) return
        if (phase == Phase.WEAK) return
        phase = Phase.LINK_REFUSED
        publish(
            RmKeyStatus(
                "The Return Machine refused a message because this phone is not paired with it securely. Remove the Return Machine " +
                    "from Android's Bluetooth settings, hold its button while switching it on, and pair again within 60 seconds.",
                RmKeyStatus.Severity.BAD,
            ),
        )
    }

    /** The machine's diag reply said the link was not authenticated (status 0xFF on 00, 05, 06, 07 or 08). */
    fun onLinkNotAuthenticated() = onWriteRefused(GATT_INSUFFICIENT_AUTHENTICATION)

    companion object {
        // BluetoothDevice.BOND_NONE / BOND_BONDING / BOND_BONDED, kept as numbers so this class has no Android dependency.
        const val BOND_NONE = 10
        const val BOND_BONDING = 11
        const val BOND_BONDED = 12

        const val GATT_INSUFFICIENT_AUTHENTICATION = 5
        const val GATT_INSUFFICIENT_ENCRYPTION = 15

        /**
         * BluetoothDevice.PAIRING_VARIANT_*: 1 = passkey entered here, 2 = numeric comparison, 4 = passkey shown here. 0 (PIN),
         * 3 (consent, which is Just Works), 5 and 6 are not authenticated pairings for an LE Secure Connections machine.
         */
        val AUTHENTICATED_VARIANTS = setOf(1, 2, 4)
    }
}
