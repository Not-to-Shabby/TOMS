package ph.toms.conductor.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.migration.Migration
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull

/**
 * 1 to 2: a card now covers a group, so each trip stores its passenger lines.
 *
 * Trips saved before this carried exactly one passenger. They are rewritten as a single line using
 * the category and fare already on the row, so nothing is lost and the totals stay the same.
 * The backfill runs in Kotlin on purpose: it does not depend on the phone's SQLite having JSON support.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE trips ADD COLUMN passengersJson TEXT NOT NULL DEFAULT '[]'")
        db.query("SELECT id, discountCategoryId, fareCentavos, discountCentavos FROM trips").use { c ->
            while (c.moveToNext()) {
                val line = listOf(
                    PassengerLineRecord(
                        categoryId = if (c.isNull(1)) null else c.getString(1),
                        count = 1,
                        perPersonCentavos = c.getInt(2),
                        fareCentavos = c.getInt(2),
                        discountCentavos = c.getInt(3),
                    ),
                )
                db.execSQL(
                    "UPDATE trips SET passengersJson = ? WHERE id = ?",
                    arrayOf(PassengerJson.encode(line), c.getString(0)),
                )
            }
        }
    }
}

/**
 * 2 to 3: shifts. A new table, and a shift tag on trips and events. Everything already saved keeps a null
 * tag, which means "belongs to no shift", so old trips are never counted into a shift they were not part of.
 * Events also gain the trip and card state they are about, backfilled from the payload for the two types
 * that carry them, so the summary can read facts without parsing JSON.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS shifts (
                id TEXT NOT NULL PRIMARY KEY, conductor TEXT NOT NULL, vehicleId TEXT NOT NULL, routeId TEXT NOT NULL,
                startedAtMillis INTEGER NOT NULL, endedAtMillis INTEGER, endedBy TEXT, summaryJson TEXT)""",
        )
        db.execSQL("ALTER TABLE trips ADD COLUMN shiftId TEXT")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_trips_shiftId ON trips (shiftId)")
        db.execSQL("ALTER TABLE events ADD COLUMN shiftId TEXT")
        db.execSQL("ALTER TABLE events ADD COLUMN tripId TEXT")
        db.execSQL("ALTER TABLE events ADD COLUMN cardState TEXT")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_events_shiftId ON events (shiftId)")

        val json = Json { ignoreUnknownKeys = true }
        db.query("SELECT seq, type, payload FROM events WHERE type IN ('trip_created', 'card_state_changed')").use { c ->
            while (c.moveToNext()) {
                val obj = runCatching { json.parseToJsonElement(c.getString(2)) as? kotlinx.serialization.json.JsonObject }.getOrNull() ?: continue
                val tripId = (obj["tripId"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                val state = if (c.getString(1) == "card_state_changed") (obj["state"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull else null
                db.execSQL("UPDATE events SET tripId = ?, cardState = ? WHERE seq = ?", arrayOf(tripId, state, c.getLong(0)))
            }
        }
    }
}

/**
 * 3 to 4: an outbox event the server refuses is parked with its reason instead of being retried forever.
 * Rows already there stay as they were: pending ones are still pending, delivered ones still delivered.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE outbox ADD COLUMN parkedAtMillis INTEGER")
        db.execSQL("ALTER TABLE outbox ADD COLUMN parkedReason TEXT")
    }
}

/**
 * 4 to 5: a table for the fares, stops and discounts downloaded from the server. It starts empty, which means
 * "no fares loaded yet": nothing is seeded, so an upgraded phone never charges from invented numbers.
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS server_config (
                id INTEGER NOT NULL PRIMARY KEY, version INTEGER NOT NULL, routeName TEXT NOT NULL,
                json TEXT NOT NULL, fetchedAtMillis INTEGER NOT NULL)""",
        )
    }
}

val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)

@kotlinx.serialization.Serializable
data class PassengerLineRecord(
    val categoryId: String?,
    val count: Int,
    val perPersonCentavos: Int,
    val fareCentavos: Int,
    val discountCentavos: Int,
)

object PassengerJson {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(lines: List<PassengerLineRecord>): String = json.encodeToString(lines)

    /** An unreadable or empty value yields no lines, which the caller treats as "one regular passenger". */
    fun decode(text: String): List<PassengerLineRecord> =
        runCatching { json.decodeFromString<List<PassengerLineRecord>>(text) }.getOrDefault(emptyList())
}
