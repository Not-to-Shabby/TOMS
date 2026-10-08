package ph.toms.conductor.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface TripDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(trip: TripEntity): Long

    @Query("SELECT * FROM trips ORDER BY createdAtMillis DESC")
    suspend fun all(): List<TripEntity>
}

@Dao
interface CardDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(card: CardEntity)

    @Query("SELECT * FROM cards")
    suspend fun all(): List<CardEntity>
}

@Dao
interface EventDao {
    /** Returns -1 when an event with the same eventId already exists. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(event: EventEntity): Long

    @Query("SELECT COUNT(*) FROM events")
    suspend fun count(): Int
}

@Dao
interface OutboxDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: OutboxEntity): Long

    @Query("SELECT COUNT(*) FROM outbox WHERE deliveredAtMillis IS NULL")
    fun pendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM outbox WHERE deliveredAtMillis IS NULL")
    suspend fun pendingCountNow(): Int

    @Query(
        """
        SELECT e.seq AS seq, e.eventId AS eventId, e.deviceId AS deviceId, e.type AS type,
               e.payload AS payload, e.createdAtMillis AS createdAtMillis, o.attemptCount AS attemptCount
        FROM outbox o JOIN events e ON e.eventId = o.eventId
        WHERE o.deliveredAtMillis IS NULL
        ORDER BY e.seq ASC
        LIMIT :limit
        """,
    )
    suspend fun pending(limit: Int): List<PendingEvent>

    @Query(
        """
        UPDATE outbox SET deliveredAtMillis = :at, deliveryChannel = :channel
        WHERE eventId IN (:eventIds) AND deliveredAtMillis IS NULL
        """,
    )
    suspend fun markDelivered(eventIds: List<String>, channel: String, at: Long): Int

    @Query(
        """
        UPDATE outbox SET attemptCount = attemptCount + 1, lastAttemptAtMillis = :at
        WHERE eventId IN (:eventIds) AND deliveredAtMillis IS NULL
        """,
    )
    suspend fun markAttempt(eventIds: List<String>, at: Long): Int
}

@Dao
interface ConfigDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(row: ConfigCacheEntity)

    @Query("SELECT * FROM config_cache WHERE `key` = :key")
    suspend fun get(key: String): ConfigCacheEntity?
}

@Database(
    entities = [TripEntity::class, CardEntity::class, EventEntity::class, OutboxEntity::class, ConfigCacheEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class TomsDatabase : RoomDatabase() {
    abstract fun trips(): TripDao
    abstract fun cards(): CardDao
    abstract fun events(): EventDao
    abstract fun outbox(): OutboxDao
    abstract fun config(): ConfigDao
}
