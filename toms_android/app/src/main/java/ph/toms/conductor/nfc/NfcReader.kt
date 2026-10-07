package ph.toms.conductor.nfc

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

sealed interface NfcAvailability {
    data object Ready : NfcAvailability
    data object Disabled : NfcAvailability
    data object Unsupported : NfcAvailability
}

data class CardRead(val uidHex: String, val techList: List<String>, val readAtMillis: Long)

fun ByteArray.toUidHex(): String = joinToString(":") { "%02X".format(it) }

/**
 * Wraps NFC reader mode (ISO 14443-A only). Reader mode keeps the foreground app as the sole
 * consumer of the tag, so no system tag-dispatch sound or app chooser interferes with a tap.
 */
@Singleton
class NfcReader @Inject constructor() {

    fun availability(activity: Activity): NfcAvailability {
        val adapter = NfcAdapter.getDefaultAdapter(activity) ?: return NfcAvailability.Unsupported
        return if (adapter.isEnabled) NfcAvailability.Ready else NfcAvailability.Disabled
    }

    fun reads(activity: Activity): Flow<CardRead> = callbackFlow {
        val adapter = NfcAdapter.getDefaultAdapter(activity)
        if (adapter == null || !adapter.isEnabled) {
            close()
            return@callbackFlow
        }
        val flags = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
        adapter.enableReaderMode(activity, { tag: Tag ->
            trySend(
                CardRead(
                    uidHex = tag.id.toUidHex(),
                    techList = tag.techList.map { it.substringAfterLast('.') },
                    readAtMillis = System.currentTimeMillis(),
                ),
            )
        }, flags, null)
        awaitClose { adapter.disableReaderMode(activity) }
    }
}
