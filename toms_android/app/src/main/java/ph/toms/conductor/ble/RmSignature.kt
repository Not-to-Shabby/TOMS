package ph.toms.conductor.ble

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The 8-byte trailer the Return Machine appends to the three values it signs (firmware/PROTOCOL.md
 * section 6): the first 8 bytes of HMAC-SHA256(key, characteristicSuffix || payload), where the
 * suffix is the last byte of the characteristic's UUID. Mixing the suffix in means a value signed
 * for one characteristic is not valid on another.
 *
 * Pure functions, no Android types, so the same code runs in unit tests against vectors produced by
 * the firmware's own signing code.
 */
object RmSignature {

    const val TRAILER_LEN = 8
    const val KEY_LEN = 32

    /** The characteristic suffixes that carry a trailer once the machine has a key. */
    const val SUFFIX_CARD_READ = 0x01
    const val SUFFIX_HEARTBEAT = 0x05
    const val SUFFIX_RM_ACK = 0x0A

    /** The trailer for [payload], or throws if the key is the wrong size. */
    fun trailer(key: ByteArray, suffix: Int, payload: ByteArray): ByteArray {
        require(key.size == KEY_LEN) { "The Return Machine key must be $KEY_LEN bytes" }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        mac.update(suffix.toByte())
        mac.update(payload)
        return mac.doFinal().copyOf(TRAILER_LEN)
    }

    /** Appends the trailer. Used by the fake link to build a realistic signed notify. */
    fun sign(key: ByteArray, suffix: Int, payload: ByteArray): ByteArray = payload + trailer(key, suffix, payload)

    /**
     * Whether [trailerBytes] is the right trailer for [payload]. Compared in constant time, so a wrong
     * trailer reveals nothing about how many leading bytes matched.
     */
    fun verify(key: ByteArray, suffix: Int, payload: ByteArray, trailerBytes: ByteArray): Boolean {
        if (trailerBytes.size != TRAILER_LEN || key.size != KEY_LEN) return false
        return MessageDigest.isEqual(trailer(key, suffix, payload), trailerBytes)
    }
}
