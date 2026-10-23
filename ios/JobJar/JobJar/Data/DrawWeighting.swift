import Foundation

/// The least chance a job that fits ever gets, relative to the best fit. Keeps a 5-minute job
/// possible in a 4-hour window instead of practically never drawn.
let minDrawWeight = 0.05

/// How big a slip a job is in the jar when "Favour jobs that fill my time" is on.
///
/// Each pick aims for a fair share of the time that's left: `remainingMinutes` split over the
/// `picksLeft` jobs still to draw (nil = "All", which aims for the whole remainder, so the first
/// pick leans big and later ones fill the gap). A job right on its share weighs 1. Shorter ones
/// weigh less in proportion (half the share = 0.5). Longer ones fall off faster, (share / length)
/// squared (twice the share = 0.25): undershooting leaves time for the later picks, but a job far
/// over its share starves them. Never below `minDrawWeight`. Same as the Android app.
func drawWeight(neededMinutes: Int, remainingMinutes: Int, picksLeft: Int?) -> Double {
    let picks = max(picksLeft ?? 1, 1)
    let share = max(Double(remainingMinutes) / Double(picks), 1)
    // Parents can have 0 or fewer minutes left once their subtasks overrun; treat that as tiny.
    let needed = Double(max(neededMinutes, 1))
    let ratio = needed / share
    let weight = ratio <= 1 ? ratio : (1 / ratio) * (1 / ratio)
    return min(max(weight, minDrawWeight), 1)
}

/// Picks one item at random, each with a chance proportional to `weight`. `unit` is a random
/// number in 0..<1. Weights must be positive (as `drawWeight`'s are). Nil if `items` is empty.
func pickWeighted<T>(_ items: [T], unit: Double, weight: (T) -> Double) -> T? {
    guard !items.isEmpty else { return nil }
    let weights = items.map(weight)
    var roll = unit * weights.reduce(0, +)
    for (index, w) in weights.enumerated() {
        roll -= w
        if roll < 0 { return items[index] }
    }
    // Only reachable through floating-point rounding on the very last item.
    return items.last
}
