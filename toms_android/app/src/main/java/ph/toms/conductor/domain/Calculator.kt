package ph.toms.conductor.domain

/** Common bills a passenger hands over, in whole pesos. */
val BILL_PESOS = listOf(20, 50, 100, 200, 500, 1000)

/**
 * The most whole-peso digits the keypad accepts. Seven digits (up to 9,999,999.99) is the most that fits in
 * an Int of centavos; one more would wrap around to a wrong, possibly negative, amount. A bus fare never
 * comes close.
 */
const val MAX_KEYPAD_DIGITS = 7

sealed interface ChangeResult {
    /** The passenger gave exactly the fare. */
    data object Exact : ChangeResult

    /** Change to hand back, in centavos. */
    data class Give(val centavos: Int) : ChangeResult

    /** Not enough yet: this much is still owed, in centavos. */
    data class Short(val centavos: Int) : ChangeResult
}

object Change {

    /**
     * What to hand back. [dueCentavos] is the fare; [tenderedCentavos] is what the passenger gave.
     * All money is whole centavos, so there is no floating-point rounding anywhere.
     */
    fun of(dueCentavos: Int, tenderedCentavos: Int): ChangeResult {
        require(dueCentavos >= 0) { "amount due cannot be negative" }
        require(tenderedCentavos >= 0) { "amount given cannot be negative" }
        val diff = tenderedCentavos.toLong() - dueCentavos.toLong()
        return when {
            diff == 0L -> ChangeResult.Exact
            diff > 0 -> ChangeResult.Give(diff.toInt())
            else -> ChangeResult.Short((-diff).toInt())
        }
    }

    /** Bills worth offering as quick buttons: the common ones that cover [dueCentavos]. */
    fun billsThatCover(dueCentavos: Int): List<Int> = BILL_PESOS.filter { it * 100 >= dueCentavos }
}

/**
 * The keypad's entry: digits typed so far for an amount in whole pesos, with an optional centavo part.
 * Kept as text so what the conductor sees is exactly what was typed.
 */
data class Keypad(val whole: String = "", val cents: String? = null) {

    /** Adds a digit. Ignored once the number is long enough or the centavo part is full. */
    fun digit(d: Int): Keypad {
        require(d in 0..9)
        return if (cents != null) {
            if (cents.length >= 2) this else copy(cents = cents + d)
        } else {
            val next = if (whole == "0") d.toString() else whole + d
            if (next.length > MAX_KEYPAD_DIGITS) this else copy(whole = next)
        }
    }

    fun point(): Keypad = if (cents == null) copy(whole = whole.ifEmpty { "0" }, cents = "") else this

    fun backspace(): Keypad = when {
        cents != null && cents.isNotEmpty() -> copy(cents = cents.dropLast(1))
        cents != null -> copy(cents = null)
        else -> copy(whole = whole.dropLast(1))
    }

    fun clear() = Keypad()

    /** The amount in centavos. Empty is 0. Typing "12.5" means 12.50, not 12.05. */
    fun centavos(): Int {
        val pesos = whole.ifEmpty { "0" }.toLong()
        val c = (cents ?: "").padEnd(2, '0').take(2).toLong()
        return (pesos * 100 + c).toInt()
    }

    val text: String get() = (whole.ifEmpty { "0" }) + (cents?.let { ".$it" } ?: "")
}

enum class CalcMode { CHANGE, GENERAL }

/** Arithmetic for the plain calculator: operations are applied left to right as they are keyed. */
enum class CalcOp { ADD, SUBTRACT, MULTIPLY, DIVIDE }

data class GeneralCalc(
    val entry: Keypad = Keypad(),
    val accumulator: Long? = null,
    val pending: CalcOp? = null,
    val error: Boolean = false,
) {
    /** Keypad works in centavos, so everything here is exact integer math on centavos. */
    private fun entered(): Long = entry.centavos().toLong()

    fun digit(d: Int) = if (error) this else copy(entry = entry.digit(d))
    fun point() = if (error) this else copy(entry = entry.point())
    fun backspace() = if (error) this else copy(entry = entry.backspace())
    fun clear() = GeneralCalc()

    fun operate(op: CalcOp): GeneralCalc {
        if (error) return this
        val applied = apply()
        return if (applied.error) applied else applied.copy(pending = op, entry = Keypad(), accumulator = applied.accumulator)
    }

    fun equals(): GeneralCalc {
        if (error) return this
        val applied = apply()
        return if (applied.error) applied else applied.copy(pending = null, entry = Keypad())
    }

    /** What the display shows: the number being typed, or the running result. */
    fun display(): String = when {
        error -> "Error"
        entry.whole.isNotEmpty() || entry.cents != null -> entry.text
        accumulator != null -> formatCentavos(accumulator)
        else -> "0"
    }

    private fun apply(): GeneralCalc {
        val current = entered()
        val acc = accumulator
        val hasEntry = entry.whole.isNotEmpty() || entry.cents != null
        if (acc == null || pending == null) return copy(accumulator = if (hasEntry || acc == null) current else acc)
        if (!hasEntry) return this
        val result: Long = when (pending) {
            CalcOp.ADD -> acc + current
            CalcOp.SUBTRACT -> acc - current
            CalcOp.MULTIPLY -> acc * current / 100
            CalcOp.DIVIDE -> {
                if (current == 0L) return copy(error = true)
                // Round half away from zero: 100.00 / 3 = 33.33, 200.00 / 3 = 66.67.
                val scaled = acc * 100
                val q = scaled / current
                val r = scaled % current
                if (kotlin.math.abs(r) * 2 >= kotlin.math.abs(current)) q + (if ((scaled < 0) == (current < 0)) 1 else -1) else q
            }
            null -> current
        }
        if (result > Int.MAX_VALUE || result < Int.MIN_VALUE) return copy(error = true)
        return copy(accumulator = result)
    }
}

/** Centavos as "12.50", with no currency symbol and no floating point. */
fun formatCentavos(centavos: Long): String {
    val sign = if (centavos < 0) "-" else ""
    val abs = kotlin.math.abs(centavos)
    return "$sign${abs / 100}.${(abs % 100).toString().padStart(2, '0')}"
}

fun formatPesos(centavos: Int): String = "₱" + formatCentavos(centavos.toLong()).removeSuffix(".00")
