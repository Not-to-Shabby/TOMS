package ph.toms.conductor.reader

import android.util.Log
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import ph.toms.conductor.BuildConfig
import ph.toms.conductor.nfc.NfcReader
import ph.toms.conductor.settings.SettingsStore

@Module
@InstallIn(SingletonComponent::class)
abstract class ReaderBindings {
    @Binds
    abstract fun usbHost(impl: AndroidUsbReaderHost): UsbReaderHost

    companion object {
        /** One scope for the readers, alive as long as the app; a reader fault never takes it down. */
        @Provides
        @Singleton
        fun readerScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        @Provides
        @Singleton
        fun cardReaderManager(
            nfc: NfcReader,
            host: UsbReaderHost,
            settings: SettingsStore,
            scope: CoroutineScope,
        ): CardReaderManager {
            // The host listens for plug and unplug from the moment the readers are created.
            (host as? AndroidUsbReaderHost)?.ensureListening()
            // A short UID prefix may be logged, and only in a debug build.
            val log: (String) -> Unit = if (BuildConfig.DEBUG) { m -> Log.d("UsbReader", m) } else { _ -> }
            return CardReaderManager(
                nfc = NfcCardReader(nfc, scope),
                usb = UsbCcidCardReader(host, scope, Dispatchers.IO, log = log),
                choice = settings.readerChoice,
                scope = scope,
            )
        }
    }
}
