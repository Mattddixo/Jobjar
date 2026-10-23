import Foundation

/// "$40" for whole amounts, "$12.50" otherwise, in the phone's currency.
func formatCost(_ cents: Int, locale: Locale = .current) -> String {
    let formatter = NumberFormatter()
    formatter.numberStyle = .currency
    formatter.locale = locale
    if cents % 100 == 0 {
        formatter.minimumFractionDigits = 0
        formatter.maximumFractionDigits = 0
    }
    return formatter.string(from: NSNumber(value: Double(cents) / 100)) ?? "\(cents / 100)"
}

/// "$", "€", "£"... for the cost box.
func currencySymbol(locale: Locale = .current) -> String {
    let formatter = NumberFormatter()
    formatter.numberStyle = .currency
    formatter.locale = locale
    return formatter.currencySymbol ?? "$"
}

/// How the cost box shows a saved cost: "40" or "12.50", using the phone's decimal mark.
func costInputText(_ cents: Int?, locale: Locale = .current) -> String {
    guard let cents else { return "" }
    if cents % 100 == 0 { return "\(cents / 100)" }
    let separator = locale.decimalSeparator ?? "."
    let fraction = cents % 100 < 10 ? "0\(cents % 100)" : "\(cents % 100)"
    return "\(cents / 100)\(separator)\(fraction)"
}

enum CostInput: Equatable {
    case empty
    case valid(cents: Int)
    case invalid
}

/// Reads what was typed in the cost box. Takes "40", "12.5", "12,50", "1,234.50", "1.234,50" and
/// ignores a currency sign. A "." or "," followed by one or two digits at the end is the decimal
/// mark; any other is a thousands separator.
func parseCostInput(_ text: String) -> CostInput {
    if text.trimmingCharacters(in: .whitespaces).isEmpty { return .empty }
    let cleaned = text.filter { $0.isASCII && ($0.isNumber || $0 == "." || $0 == ",") }
    if cleaned.isEmpty || text.contains("-") { return .invalid }

    var whole = cleaned
    var fraction = ""
    if let lastSeparator = cleaned.lastIndex(where: { $0 == "." || $0 == "," }) {
        let decimals = cleaned[cleaned.index(after: lastSeparator)...]
        if (1...2).contains(decimals.count) {
            whole = String(cleaned[..<lastSeparator])
            fraction = String(decimals)
        }
    }
    var wholeDigits = whole.filter(\.isNumber)
    if wholeDigits.isEmpty { wholeDigits = "0" }
    guard wholeDigits.count <= 9, let wholeValue = Int(wholeDigits) else { return .invalid }
    let fractionValue = Int(fraction.padding(toLength: 2, withPad: "0", startingAt: 0)) ?? 0
    return .valid(cents: wholeValue * 100 + fractionValue)
}
