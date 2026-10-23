package com.mattdixon.jobjar.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class CostFormatTest {
    @Test
    fun readsTheUsualWaysOfTypingAnAmount() {
        assertEquals(CostInput.Valid(4000), parseCostInput("40"))
        assertEquals(CostInput.Valid(1250), parseCostInput("12.5"))
        assertEquals(CostInput.Valid(1250), parseCostInput("12,50"))
        assertEquals(CostInput.Valid(123450), parseCostInput("1,234.50"))
        assertEquals(CostInput.Valid(123450), parseCostInput("1.234,50"))
        assertEquals(CostInput.Valid(123400), parseCostInput("1,234"))
        assertEquals(CostInput.Valid(4000), parseCostInput(" $40 "))
        assertEquals(CostInput.Valid(50), parseCostInput(".5"))
    }

    @Test
    fun blankIsNoCostAndNonsenseIsRejected() {
        assertEquals(CostInput.Empty, parseCostInput("  "))
        assertEquals(CostInput.Invalid, parseCostInput("abc"))
        assertEquals(CostInput.Invalid, parseCostInput("-5"))
        assertEquals(CostInput.Invalid, parseCostInput("99999999999"))
    }

    @Test
    fun formatsWholeAmountsWithoutCents() {
        assertEquals("$40", formatCost(4000, Locale.US))
        assertEquals("$12.50", formatCost(1250, Locale.US))
        assertEquals("$1,234.50", formatCost(123450, Locale.US))
    }

    @Test
    fun theCostBoxShowsWhatWasSaved() {
        assertEquals("", costInputText(null, Locale.US))
        assertEquals("40", costInputText(4000, Locale.US))
        assertEquals("12.05", costInputText(1205, Locale.US))
        assertEquals("12,05", costInputText(1205, Locale.GERMANY))
        assertEquals(CostInput.Valid(1205), parseCostInput(costInputText(1205, Locale.GERMANY)))
    }
}
