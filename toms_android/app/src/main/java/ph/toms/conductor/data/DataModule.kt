package ph.toms.conductor.data

import android.content.Context
import androidx.room.Room
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import ph.toms.conductor.data.db.ALL_MIGRATIONS
import ph.toms.conductor.data.db.DatabaseKeyProvider
import ph.toms.conductor.data.db.TomsDatabase
import ph.toms.conductor.feedback.DeviceFeedback
import ph.toms.conductor.feedback.Feedback
import ph.toms.conductor.settings.DeviceConfigStore
import ph.toms.conductor.settings.PrefsDeviceConfig
import ph.toms.conductor.settings.PrefsSettings
import ph.toms.conductor.settings.SettingsStore
import ph.toms.conductor.sync.CardRegistrySync
import ph.toms.conductor.sync.EventUploader
import ph.toms.conductor.sync.OutboxFlusher
import ph.toms.conductor.sync.RetrofitEventUploader

@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {
    @Binds
    abstract fun sessionRepository(impl: StubSessionRepository): SessionRepository

    @Binds
    abstract fun feedback(impl: DeviceFeedback): Feedback

    @Binds
    abstract fun settings(impl: PrefsSettings): SettingsStore

    @Binds
    abstract fun deviceConfig(impl: PrefsDeviceConfig): DeviceConfigStore

    @Binds
    abstract fun uploader(impl: RetrofitEventUploader): EventUploader

    @Binds
    abstract fun cardRegistrySync(impl: RetrofitEventUploader): CardRegistrySync
}

@Module
@InstallIn(SingletonComponent::class)
object ProvidersModule {

    @Provides @Singleton
    fun database(@ApplicationContext context: Context): TomsDatabase {
        System.loadLibrary("sqlcipher")
        context.deleteDatabase("toms.db")
        val factory = SupportOpenHelperFactory(DatabaseKeyProvider(context).passphrase())
        return Room.databaseBuilder(context, TomsDatabase::class.java, "toms_enc.db")
            .openHelperFactory(factory)
            .addMigrations(*ALL_MIGRATIONS)
            .build()
    }

    @Provides @Singleton
    fun deviceId(config: DeviceConfigStore): String = config.deviceId

    @Provides @Singleton
    fun okHttpClient(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
            .build()

    @Provides @Singleton
    fun tripRepository(db: TomsDatabase, deviceId: String) = TripRepository(db, deviceId)

    @Provides @Singleton
    fun flusher(db: TomsDatabase, uploader: EventUploader) = OutboxFlusher(db.outbox(), uploader)
}
