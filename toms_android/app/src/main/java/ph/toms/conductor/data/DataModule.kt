package ph.toms.conductor.data

import android.content.Context
import androidx.room.Room
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.UUID
import javax.inject.Singleton
import ph.toms.conductor.data.db.TomsDatabase
import ph.toms.conductor.sync.EventUploader
import ph.toms.conductor.sync.NoBackendUploader
import ph.toms.conductor.sync.OutboxFlusher

@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {
    @Binds
    abstract fun sessionRepository(impl: StubSessionRepository): SessionRepository
}

@Module
@InstallIn(SingletonComponent::class)
object ProvidersModule {

    @Provides @Singleton
    fun database(@ApplicationContext context: Context): TomsDatabase =
        Room.databaseBuilder(context, TomsDatabase::class.java, "toms.db").build()

    /** Generated once per install and kept; becomes the per-device identity at enrollment (1.4). */
    @Provides @Singleton
    fun deviceId(@ApplicationContext context: Context): String {
        val prefs = context.getSharedPreferences("toms_device", Context.MODE_PRIVATE)
        return prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("device_id", it).apply()
        }
    }

    @Provides @Singleton
    fun tripRepository(db: TomsDatabase, deviceId: String) = TripRepository(db, deviceId)

    @Provides @Singleton
    fun uploader(): EventUploader = NoBackendUploader()

    @Provides @Singleton
    fun flusher(db: TomsDatabase, uploader: EventUploader) = OutboxFlusher(db.outbox(), uploader)
}
