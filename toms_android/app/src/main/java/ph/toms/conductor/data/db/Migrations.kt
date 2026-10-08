package ph.toms.conductor.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.migration.Migration
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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

val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2)

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
