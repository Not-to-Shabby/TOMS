package ph.toms.conductor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangeTest {

    @Test fun `change is what was given minus the fare`() {
        assertEquals(ChangeResult.Give(3000), Change.of(dueCentavos = 7000, tenderedCentavos = 10000))
        assertEquals(ChangeResult.Give(50), Change.of(1450, 1500))
    }

    @Test fun `exact payment and short payment are reported`() {
        assertEquals(ChangeResult.Exact, Change.of(4500, 4500))
        assertEquals(ChangeResult.Short(500), Change.of(4500, 4000))
    }

    @Test fun `a free fare needs no money`() {
        assertEquals(ChangeResult.Exact, Change.of(0, 0))
        assertEquals(ChangeResult.Give(2000), Change.of(0, 2000))
    }

    @Test fun `negative amounts are refused`() {
        assertTrue(runCatching { Change.of(-1, 0) }.isFailure)
        assertTrue(runCatching { Change.of(0, -1) }.isFailure)
    }

    @Test fun `the largest amounts give the exact answer at both ends`() {
        // Both inputs are never negative, so their difference always fits an Int and the Long arithmetic in
        // Change.of is a safeguard, not something these inputs can distinguish. The boundary is still worth pinning.
        assertEquals(ChangeResult.Short(Int.MAX_VALUE), Change.of(Int.MAX_VALUE, 0))
        assertEquals(ChangeResult.Give(Int.MAX_VALUE), Change.of(0, Int.MAX_VALUE))
        assertEquals(ChangeResult.Exact, Change.of(Int.MAX_VALUE, Int.MAX_VALUE))
        assertEquals(ChangeResult.Give(1), Change.of(Int.MAX_VALUE - 1, Int.MAX_VALUE))
    }

    @Test fun `quick bills are only those that cover the fare`() {
        assertEquals(listOf(50, 100, 200, 500, 1000), Change.billsThatCover(2500))
        assertEquals(listOf(20, 50, 100, 200, 500, 1000), Change.billsThatCover(2000))
        assertEquals(listOf(50, 100, 200, 500, 1000), Change.billsThatCover(2001))
        assertEquals(emptyList<Int>(), Change.billsThatCover(100_001))
    }
}

class KeypadTest {

    private fun type(vararg keys: Any): Keypad {
        var k = Keypad()
        for (key in keys) k = when (key) {
            is Int -> k.digit(key)
            "." -> k.point()
            "<" -> k.backspace()
            else -> error("bad key")
        }
        return k
    }

    @Test fun `digits build a whole amount`() {
        assertEquals(15000, type(1, 5, 0).centavos())
        assertEquals("150", type(1, 5, 0).text)
    }

    @Test fun `centavos are read as a decimal fraction, not as a count`() {
        assertEquals(1250, type(1, 2, ".", 5).centavos())   // 12.5 is 12.50
        assertEquals(1205, type(1, 2, ".", 0, 5).centavos()) // 12.05
        assertEquals(1234, type(1, 2, ".", 3, 4).centavos())
    }

    @Test fun `a third centavo digit is ignored, in the amount and in what is shown`() {
        assertEquals(1234, type(1, 2, ".", 3, 4, 9).centavos())
        assertEquals("12.34", type(1, 2, ".", 3, 4, 9).text)
        assertEquals(type(1, 2, ".", 3, 4), type(1, 2, ".", 3, 4, 9, 9))
    }

    @Test fun `a leading point means zero pesos`() {
        assertEquals(50, type(".", 5).centavos())
        assertEquals("0.5", type(".", 5).text)
    }

    @Test fun `a second point is ignored`() {
        assertEquals(type(1, ".", 5), type(1, ".", 5, "."))
    }

    @Test fun `leading zeros collapse`() {
        assertEquals(500, type(0, 0, 5).centavos())
        assertEquals("5", type(0, 0, 5).text)
    }

    @Test fun `empty is zero and shows 0`() {
        assertEquals(0, Keypad().centavos())
        assertEquals("0", Keypad().text)
    }

    @Test fun `backspace removes the last digit, then the point, then the whole digits`() {
        assertEquals(type(1, 2, ".", 3), type(1, 2, ".", 3, 4, "<"))
        assertEquals(type(1, 2), type(1, 2, ".", "<"))
        assertEquals(type(1), type(1, 2, "<"))
        assertEquals(Keypad(), Keypad().backspace())
    }

    @Test fun `the number stops growing at the digit limit`() {
        var k = Keypad()
        repeat(15) { k = k.digit(9) }
        assertEquals(MAX_KEYPAD_DIGITS, k.whole.length)
    }

    @Test fun `the largest typed amount still fits in centavos without wrapping`() {
        var k = Keypad()
        repeat(MAX_KEYPAD_DIGITS) { k = k.digit(9) }
        k = k.point().digit(9).digit(9)
        assertEquals(999_999_999, k.centavos()) // 9,999,999.99 pesos
        assertTrue(k.centavos() > 0)
    }
}

class GeneralCalcTest {

    private fun run(vararg keys: Any): GeneralCalc {
        var c = GeneralCalc()
        for (key in keys) c = when (key) {
            is Int -> c.digit(key)
            is CalcOp -> c.operate(key)
            "=" -> c.equals()
            "." -> c.point()
            "C" -> c.clear()
            else -> error("bad key")
        }
        return c
    }

    @Test fun `addition and subtraction`() {
        assertEquals("57.00", run(4, 5, CalcOp.ADD, 1, 2, "=").display())
        assertEquals("33.00", run(4, 5, CalcOp.SUBTRACT, 1, 2, "=").display())
    }

    @Test fun `multiplying a fare by a count`() {
        assertEquals("84.00", run(2, 8, CalcOp.MULTIPLY, 3, "=").display())
        assertEquals("31.25", run(1, 2, ".", 5, CalcOp.MULTIPLY, 2, ".", 5, "=").display())
    }

    @Test fun `division rounds to the nearest centavo`() {
        assertEquals("33.33", run(1, 0, 0, CalcOp.DIVIDE, 3, "=").display())
        assertEquals("66.67", run(2, 0, 0, CalcOp.DIVIDE, 3, "=").display())
        assertEquals("25.00", run(1, 0, 0, CalcOp.DIVIDE, 4, "=").display())
    }

    @Test fun `splitting a bill`() {
        assertEquals("30.00", run(1, 2, 0, CalcOp.DIVIDE, 4, "=").display())
    }

    @Test fun `dividing by zero is an error that clear recovers from`() {
        val e = run(5, CalcOp.DIVIDE, 0, "=")
        assertEquals("Error", e.display())
        assertEquals("0", e.clear().display())
    }

    @Test fun `while in error every key is ignored until clear`() {
        val e = run(5, CalcOp.DIVIDE, 0, "=")
        assertEquals(e, e.digit(7))
        assertEquals(e, e.point())
        assertEquals(e, e.backspace())
        assertEquals(e, e.operate(CalcOp.ADD))
        assertEquals(e, e.equals())
        assertEquals("Error", e.digit(7).display())
        assertEquals("7", e.clear().digit(7).display())
    }

    @Test fun `operations chain left to right as keyed, with no operator precedence`() {
        assertEquals("45.00", run(2, CalcOp.ADD, 3, CalcOp.MULTIPLY, 9, "=").display()) // (2 + 3) * 9, not 2 + 27
    }

    @Test fun `the running result shows after each operator`() {
        assertEquals("5.00", run(2, CalcOp.ADD, 3, CalcOp.MULTIPLY).display())
    }

    @Test fun `a result can be carried into the next operation`() {
        assertEquals("100.00", run(5, 0, CalcOp.ADD, 5, 0, "=").display())
        assertEquals("102.00", run(5, 0, CalcOp.ADD, 5, 0, "=", CalcOp.ADD, 2, "=").display())
    }

    @Test fun `changing the operator before a second number uses the new one`() {
        assertEquals("5.00", run(1, 0, CalcOp.ADD, CalcOp.SUBTRACT, 5, "=").display())
    }

    @Test fun `equals with nothing pending keeps the number`() {
        assertEquals("12.00", run(1, 2, "=").display())
    }

    @Test fun `a very large result is reported as an error, not wrapped around`() {
        assertEquals("Error", run(9, 9, 9, 9, 9, 9, 9, CalcOp.MULTIPLY, 9, 9, 9, 9, 9, 9, 9, "=").display())
        // 9,999,999.99 + 9,999,999.99 = 19,999,999.98, which is still inside range: it must be shown, not flagged.
        assertEquals("19999999.98", run(9, 9, 9, 9, 9, 9, 9, ".", 9, 9, CalcOp.ADD, 9, 9, 9, 9, 9, 9, 9, ".", 9, 9, "=").display())
        // Past 21,474,836.47 an Int of centavos would wrap, so adding a third one must report an error.
        assertEquals(
            "Error",
            run(9, 9, 9, 9, 9, 9, 9, ".", 9, 9, CalcOp.ADD, 9, 9, 9, 9, 9, 9, 9, ".", 9, 9, CalcOp.ADD, 9, 9, 9, 9, 9, 9, 9, ".", 9, 9, "=").display(),
        )
    }

    @Test fun `the largest single entry is a valid amount`() {
        assertEquals("9999999.99", run(9, 9, 9, 9, 9, 9, 9, ".", 9, 9).display())
        assertEquals("9999999.99", run(9, 9, 9, 9, 9, 9, 9, ".", 9, 9, "=").display())
    }

    @Test fun `display shows what is being typed, then the running result`() {
        assertEquals("12", run(1, 2).display())
        assertEquals("12.00", run(1, 2, CalcOp.ADD).display())
        assertEquals("0", run().display())
    }
}

class FormatTest {

    @Test fun `centavos format without floating point`() {
        assertEquals("12.50", formatCentavos(1250))
        assertEquals("0.05", formatCentavos(5))
        assertEquals("0.00", formatCentavos(0))
        assertEquals("-3.07", formatCentavos(-307))
    }

    @Test fun `pesos drop the zero centavos`() {
        assertEquals("₱45", formatPesos(4500))
        assertEquals("₱45.50", formatPesos(4550))
        assertEquals("₱0", formatPesos(0))
    }

    @Test fun `formatting is exact for large values`() {
        assertEquals("21474836.47", formatCentavos(Int.MAX_VALUE.toLong()))
        assertFalse(formatCentavos(1).contains("E"))
    }
}
