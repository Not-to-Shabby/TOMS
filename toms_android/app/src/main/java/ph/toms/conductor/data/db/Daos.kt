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

    @Query("SELECT * FROM trips WHERE id = :id")
    suspend fun byId(id: String): TripEntity?
}

@Dao
interface ShiftDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(shift: ShiftEntity)

    @Query("SELECT * FROM shifts WHERE endedAtMillis IS NULL ORDER BY startedAtMillis DESC LIMIT 1")
    suspend fun open(): ShiftEntity?

    @Query("SELECT * FROM shifts WHERE id = :id")
    suspend fun byId(id: String): ShiftEntity?

    /** Closes the shift only if it is still open, so ending twice changes nothing the second time. */
    @Query("UPDATE shifts SET endedAtMillis = :at, endedBy = :by, summaryJson = :summary WHERE id = :id AND endedAtMillis IS NULL")
    suspend fun close(id: String, at: Long, by: String, summary: String): Int
}

@Dao
interface CardDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(card: CardEntity)

    @Query("SELECT * FROM cards")
    suspend fun all(): List<CardEntity>

    /** The card whose latest trip is [tripId]. Null once the card has started another trip. */
    @Query("SELECT * FROM cards WHERE currentTripId = :tripId")
    suspend fun byTrip(tripId: String): CardEntity?
}

@Dao
interface EventDao {
    /** Returns -1 when an event with the same eventId already exists. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(event: EventEntity): Long

    @Query("SELECT COUNT(*) FROM events")
    suspend fun count(): Int

    @Query("SELECT * FROM events ORDER BY seq ASC")
    suspend fun all(): List<EventEntity>

    @Query("SELECT * FROM events WHERE tripId IS NOT NULL ORDER BY seq ASC")
    suspend fun tripFacts(): List<EventEntity>

    @Query("SELECT * FROM events WHERE type = :type ORDER BY seq ASC")
    suspend fun ofType(type: String): List<EventEntity>
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
    entities = [TripEntity::class, CardEntity::class, EventEntity::class, OutboxEntity::class, ConfigCacheEntity::class, ShiftEntity::class],
    version = 3,
    exportSchema = true,
)
abstract class TomsDatabase : RoomDatabase() {
    abstract fun shifts(): ShiftDao
    abstract fun trips(): TripDao
    abstract fun cards(): CardDao
    abstract fun events(): EventDao
    abstract fun outbox(): OutboxDao
    abstract fun config(): ConfigDao
}
