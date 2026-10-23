package com.mattdixon.jobjar.util

import java.text.NumberFormat
import java.util.Locale

/** "$40" for whole amounts, "$12.50" otherwise, in the phone's currency. */
fun formatCost(cents: Long, locale: Locale = Locale.getDefault()): String {
    val format = NumberFormat.getCurrencyInstance(locale)
    if (cents % 100 == 0L) {
        format.minimumFractionDigits = 0
        format.maximumFractionDigits = 0
    }
    return format.format(cents / 100.0)
}

/** "$", "€", "£"... for the label on the cost box. */
fun currencySymbol(locale: Locale = Locale.getDefault()): String =
    NumberFormat.getCurrencyInstance(locale).currency?.getSymbol(locale) ?: "$"

/** How the cost box shows a saved cost: "40" or "12.50", using the phone's decimal mark. */
fun costInputText(cents: Long?, locale: Locale = Locale.getDefault()): String {
    if (cents == null) return ""
    if (cents % 100 == 0L) return (cents / 100).toString()
    val separator = java.text.DecimalFormatSymbols.getInstance(locale).decimalSeparator
    return "${cents / 100}$separator${(cents % 100).toString().padStart(2, '0')}"
}

sealed interface CostInput {
    data object Empty : CostInput
    data class Valid(val cents: Long) : CostInput
    data object Invalid : CostInput
}

/**
 * Reads what was typed in the cost box. Takes "40", "12.5", "12,50", "1,234.50", "1.234,50" and
 * ignores a currency sign. A "." or "," followed by one or two digits at the end is the decimal
 * mark; any other is a thousands separator.
 */
fun parseCostInput(text: String): CostInput {
    val cleaned = text.filter { it.isDigit() || it == '.' || it == ',' }
    if (text.isBlank()) return CostInput.Empty
    if (cleaned.isEmpty() || text.any { it == '-' }) return CostInput.Invalid
    val lastSeparator = cleaned.indexOfLast { it == '.' || it == ',' }
    val decimals = if (lastSeparator >= 0) cleaned.substring(lastSeparator + 1) else ""
    val (whole, fraction) = if (lastSeparator >= 0 && decimals.length in 1..2) {
        cleaned.substring(0, lastSeparator) to decimals
    } else {
        cleaned to ""
    }
    val wholeDigits = whole.filter { it.isDigit() }.ifEmpty { "0" }
    if (wholeDigits.length > 9) return CostInput.Invalid
    val cents = wholeDigits.toLong() * 100 + fraction.padEnd(2, '0').ifEmpty { "0" }.toLong()
    return CostInput.Valid(cents)
}
