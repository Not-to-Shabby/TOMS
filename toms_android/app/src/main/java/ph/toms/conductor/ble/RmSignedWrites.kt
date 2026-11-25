package ph.toms.conductor.ble

/**
 * The phone's writes to the Return Machine, with the 8-byte trailer the hardened firmware checks (firmware/PROTOCOL.md sections 6
 * and 8): the first 8 bytes of HMAC-SHA256(key, suffix || payload), where the suffix is the last byte of the characteristic that is
 * written (`state_update` 02, `paid_reply` 04, `time_sync` 06, `ack` 08, diag 0E). One place builds them, so the real link and the
 * fake link send the same bytes.
 *
 * What the machine does with a write (section 8.3, and `verify_write` in `svc.c` of that branch):
 *  - a valid trailer is accepted;
 *  - a **wrong** trailer is refused, on every build;
 *  - **no** trailer is accepted for state_update, paid_reply, time_sync and ack, and counted;
 *  - diag `05` with no trailer is accepted only under an interim rule (a bonded central with an open diagnostic session), which
 *    this phone never meets, so once the machine has a key the phone has to sign `05` for Clear alarm to work.
 *
 * So a wrong trailer is worse than none. [writeKey] decides which key may sign: only one the machine has been seen to hold.
 */
object RmSignedWrites {

    /**
     * The key the phone signs its writes with, or null to send them without a trailer.
     *
     * Only the **active** key, which is the key a message from the machine has already verified under: the machine holds it. A key
     * that is only pending (written, not yet confirmed) is not used, because if the machine did not take it, a trailer made with it
     * would be a wrong trailer and the write would be refused. During a key replace the active key stays the old one until the
     * first message verifies under the new one, and the machine accepts the old key for that step (section 8.4), so signing
     * continues without a gap. A machine flagged as mismatched is not signed for either: the phone does not know what it holds.
     */
    fun writeKey(trust: RmMachineTrust, machineId: String?): ByteArray? {
        if (machineId == null) return null
        if (trust.mismatchReason(machineId) != null) return null
        return trust.activeKey(machineId)
    }

    fun paidReply(key: ByteArray?, checkSeq: Long, verdict: Int): ByteArray =
        RmSignature.signWrite(key, RmSignature.SUFFIX_PAID_REPLY, RmProtocol.encodePaidReply(checkSeq, verdict))

    fun stateUpdate(key: ByteArray?, tableVersion: Long, snapshot: Boolean, entries: List<RmProtocol.StateEntry>): ByteArray =
        RmSignature.signWrite(key, RmSignature.SUFFIX_STATE_UPDATE, RmProtocol.encodeStateUpdate(tableVersion, snapshot, entries))

    fun timeSync(key: ByteArray?, nowMillis: Long): ByteArray =
        RmSignature.signWrite(key, RmSignature.SUFFIX_TIME_SYNC, RmProtocol.encodeTimeSync(nowMillis))

    fun readAck(key: ByteArray?, upToReadSeq: Long): ByteArray =
        RmSignature.signWrite(key, RmSignature.SUFFIX_ACK, RmProtocol.encodeReadAck(upToReadSeq))

    fun alarmClear(key: ByteArray?): ByteArray = RmProtocol.encodeAlarmClear(key)
}
