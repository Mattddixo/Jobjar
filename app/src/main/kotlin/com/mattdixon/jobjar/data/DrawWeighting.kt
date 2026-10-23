package com.mattdixon.jobjar.data

import kotlin.random.Random

/**
 * The least chance a job that fits ever gets, relative to the best fit. Keeps a 5-minute job
 * possible in a 4-hour window instead of practically never drawn.
 */
const val MIN_DRAW_WEIGHT = 0.05

/**
 * How big a slip a job is in the jar when "Favour jobs that fill my time" is on.
 *
 * Each pick aims for a fair share of the time that's left: [remainingMinutes] split over the
 * [picksLeft] jobs still to draw (null = "All", which aims for the whole remainder, so the first
 * pick leans big and later ones fill the gap). A job right on its share weighs 1. Shorter ones
 * weigh less in proportion (half the share = 0.5). Longer ones fall off faster, (share / length)
 * squared (twice the share = 0.25): undershooting leaves time for the later picks, but a job far
 * over its share starves them. Never below [MIN_DRAW_WEIGHT].
 *
 * With plenty of jobs around the share this barely changes small windows, where everything that
 * fits is about the same size anyway.
 */
fun drawWeight(neededMinutes: Int, remainingMinutes: Int, picksLeft: Int?): Double {
    val picks = if (picksLeft == null || picksLeft < 1) 1 else picksLeft
    val share = maxOf(remainingMinutes.toDouble() / picks, 1.0)
    // Parents can have 0 or fewer minutes left once their subtasks overrun; treat that as tiny.
    val needed = maxOf(neededMinutes, 1).toDouble()
    val ratio = needed / share
    val weight = if (ratio <= 1.0) ratio else (1.0 / ratio) * (1.0 / ratio)
    return weight.coerceIn(MIN_DRAW_WEIGHT, 1.0)
}

/**
 * Priority's nudge on the draw, always applied: high-priority jobs come up twice as often as
 * normal ones, low half as often. It multiplies with [drawWeight], so how well a job fits the time
 * still leads and priority tips the balance between similar fits.
 */
fun priorityWeight(priority: Priority): Double = when (priority) {
    Priority.HIGH -> 2.0
    Priority.NORMAL -> 1.0
    Priority.LOW -> 0.5
}

/**
 * Picks one item at random, each with a chance proportional to [weight]. Weights must be positive
 * (as [drawWeight]'s are). Null if [items] is empty.
 */
fun <T> pickWeighted(items: List<T>, random: Random, weight: (T) -> Double): T? {
    if (items.isEmpty()) return null
    val weights = items.map(weight)
    var roll = random.nextDouble() * weights.sum()
    for ((index, w) in weights.withIndex()) {
        roll -= w
        if (roll < 0) return items[index]
    }
    // Only reachable through floating-point rounding on the very last item.
    return items.last()
}
