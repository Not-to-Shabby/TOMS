package ph.toms.conductor.ble

/**
 * What the phone can know about how it is paired with the Return Machine, and what to tell the conductor
 * (firmware/PROTOCOL.md sections 8.1, 8.6 and 8.7).
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
 *  - a write the machine refused for lack of an authenticated, encrypted link;
 *  - **a bond the machine cleared** (8.7): the machine has deleted its half of the pairing (button held through the pairing window)
 *    and Android still holds the phone's half. Android therefore never asks to pair again, and the machine refuses to encrypt, so
 *    the link drops before it is usable. The phone cannot see why a link dropped, only that Android still says "bonded" and that
 *    connections keep ending before they are ready. After [STALE_AFTER] such attempts in a row the conductor is told, and the app
 *    offers to remove the old pairing so the next connection pairs afresh.
 *
 * Pure logic, no Android types, so each transition is a plain test. [onStaleChanged] reports whether the "old pairing" prompt is
 * showing, so the screen can offer its button. Calls may come from the Bluetooth thread, a coroutine and the main thread, hence
 * the synchronisation.
 */
class RmPairingTracker(
    private val onStaleChanged: (Boolean) -> Unit = {},
    private val publish: (RmKeyStatus?) -> Unit,
) {

    enum class Phase { IDLE, BONDING, BONDED, REFUSED, WEAK, LINK_REFUSED, STALE_BOND, RE_PAIRING }

    var phase: Phase = Phase.IDLE
        private set

    /** True while the "the machine forgot this phone" prompt is up. */
    var staleBond: Boolean = false
        private set

    private var variantSeen = false

    /** Connections in a row that ended before they were usable while Android still held the bond. */
    private var setupFailures = 0

    /** One connection attempt counts once, however many of its steps fail. */
    private var countedThisAttempt = false

    /**
     * This attempt's link came up (the machine answered) and whether it then became usable. A link that comes up and never becomes
     * usable is what a cleared bond looks like; an attempt that never reached the machine (out of range) is not.
     */
    private var linkUp = false
    private var linkReady = false

    /** The link came up: the machine answered. [bonded] is whether Android holds a bond with it. */
    @Synchronized
    fun onLinkUp(bonded: Boolean) {
        linkUp = true
        linkReady = false
        onConnected(bonded)
    }

    /** The link is usable: it is set up and the machine's notifications are on. */
    @Synchronized
    fun onLinkReady() {
        linkReady = true
        onReady()
    }

    /** The link ended. If it had come up and never became usable, that attempt counts as failed. */
    @Synchronized
    fun onLinkDown(bonded: Boolean) {
        if (linkUp && !linkReady) onSetupFailed(bonded)
        linkUp = false
        linkReady = false
        // The attempt is over, so the next one counts afresh.
        countedThisAttempt = false
        onDisconnected()
    }

    /** A new connection attempt. [bonded] is whether Android already holds a bond with this machine. */
    @Synchronized
    fun onConnected(bonded: Boolean) {
        variantSeen = false
        countedThisAttempt = false
        // The prompt stays until the machine is reached or the old pairing is gone: each retry would otherwise wipe it.
        if (staleBond && bonded) return
        // The pairing disappeared without the app asking (removed in Android's settings): carry on as if the app had removed it.
        if (staleBond) {
            enterRePairing()
            return
        }
        // A re-pairing is under way: its hint stays until the bond is made or refused.
        if (phase == Phase.RE_PAIRING && !bonded) return
        phase = if (bonded) Phase.BONDED else Phase.IDLE
        publish(null)
    }

    @Synchronized
    fun onDisconnected() {
        if (!staleBond && phase != Phase.RE_PAIRING) phase = Phase.IDLE
        variantSeen = false
        // The notice stays: it is what the conductor needs to read after the link dropped because of it.
    }

    /** The link is fully set up (the machine's notifications are on): whatever was wrong is over. */
    @Synchronized
    fun onReady() {
        setupFailures = 0
        val wasShowing = staleBond || phase == Phase.RE_PAIRING
        if (staleBond) {
            staleBond = false
            onStaleChanged(false)
        }
        if (wasShowing) {
            phase = Phase.BONDED
            publish(null)
        }
    }

    /**
     * A connection attempt ended before the link was usable. Only counts when Android still holds the bond ([bonded]); an attempt
     * with no bond is the ordinary pairing path, and is not this problem.
     */
    @Synchronized
    fun onSetupFailed(bonded: Boolean) {
        if (!bonded || countedThisAttempt || phase == Phase.WEAK) return
        countedThisAttempt = true
        setupFailures++
        if (setupFailures >= STALE_AFTER && !staleBond) {
            staleBond = true
            phase = Phase.STALE_BOND
            onStaleChanged(true)
            publish(
                RmKeyStatus(
                    "The Return Machine has forgotten this phone, but this phone still remembers the Return Machine, so they cannot " +
                        "connect. Tap \"Forget old pairing\", then pair again within 60 seconds of the Return Machine showing " +
                        "PAIRING (hold its button while switching it on if it does not).",
                    RmKeyStatus.Severity.BAD,
                ),
            )
        }
    }

    /** Android would not let the app remove the old pairing: say how to do it by hand. */
    @Synchronized
    fun onForgetRefused() {
        if (!staleBond) return
        publish(
            RmKeyStatus(
                "Android did not let the app remove the old pairing. Open Bluetooth settings, tap TOMS-RM, choose Forget, then pair " +
                    "again within 60 seconds of the Return Machine showing PAIRING (hold its button while switching it on if it does not).",
                RmKeyStatus.Severity.BAD,
            ),
        )
    }

    /** The phone asked Android to pair. */
    @Synchronized
    fun onBondRequested() {
        if (phase != Phase.BONDED && !staleBond) {
            phase = Phase.BONDING
            variantSeen = false
        }
    }

    /** Android showed a pairing request with [variant] (BluetoothDevice.EXTRA_PAIRING_VARIANT). */
    @Synchronized
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
    @Synchronized
    fun onBondChanged(previous: Int, now: Int) {
        when {
            now == BOND_BONDED -> {
                if (phase != Phase.WEAK) {
                    // A fresh bond: the old trouble, if any, is over.
                    if (staleBond) {
                        staleBond = false
                        onStaleChanged(false)
                    }
                    setupFailures = 0
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
                // The pairing was removed (by the app, in Android settings, or by Android itself).
                if (staleBond) enterRePairing() else phase = Phase.IDLE
            }
        }
    }

    /** A GATT write came back with ATT error [attStatus]; 5 (not authenticated) and 15 (not encrypted) mean the link is not good enough. */
    @Synchronized
    fun onWriteRefused(attStatus: Int) {
        if (attStatus != GATT_INSUFFICIENT_AUTHENTICATION && attStatus != GATT_INSUFFICIENT_ENCRYPTION) return
        if (phase == Phase.WEAK || staleBond) return
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

    private fun enterRePairing() {
        staleBond = false
        setupFailures = 0
        onStaleChanged(false)
        phase = Phase.RE_PAIRING
        publish(
            RmKeyStatus(
                "The old pairing was removed. When Android asks, compare the number with the one on the Return Machine's screen. " +
                    "If nothing asks within a minute, hold the Return Machine's button while switching it on, then try again.",
                RmKeyStatus.Severity.WARN,
            ),
        )
    }

    companion object {
        // BluetoothDevice.BOND_NONE / BOND_BONDING / BOND_BONDED, kept as numbers so this class has no Android dependency.
        const val BOND_NONE = 10
        const val BOND_BONDING = 11
        const val BOND_BONDED = 12

        const val GATT_INSUFFICIENT_AUTHENTICATION = 5
        const val GATT_INSUFFICIENT_ENCRYPTION = 15

        /**
         * How many connections in a row must end before they are usable, with Android still holding the bond, before the conductor is
         * told. One drop is an ordinary radio glitch; the link retries every few seconds, so three is a matter of seconds, not hours.
         */
        const val STALE_AFTER = 3

        /**
         * BluetoothDevice.PAIRING_VARIANT_*: 1 = passkey entered here, 2 = numeric comparison, 4 = passkey shown here. 0 (PIN),
         * 3 (consent, which is Just Works), 5 and 6 are not authenticated pairings for an LE Secure Connections machine.
         */
        val AUTHENTICATED_VARIANTS = setOf(1, 2, 4)
    }
}
