import SwiftUI

/// Choices for "Show it early", in days. Only the ones shorter than the repeat are offered.
private let leadChoices = [0, 1, 3, 7, 14, 30, 60]

/// The quick picks at the top. All of them start on set days, from the start date.
enum RepeatPreset: CaseIterable, Identifiable {
    case daily, weekly, fortnightly, monthly, quarterly, yearly

    var id: Self { self }

    var interval: Int {
        switch self {
        case .daily, .weekly, .monthly, .yearly: return 1
        case .fortnightly: return 2
        case .quarterly: return 3
        }
    }

    var unit: RepeatUnit {
        switch self {
        case .daily: return .day
        case .weekly, .fortnightly: return .week
        case .monthly, .quarterly: return .month
        case .yearly: return .year
        }
    }

    var label: String {
        switch self {
        case .daily: return "Daily"
        case .weekly: return "Weekly"
        case .fortnightly: return "Every 2 weeks"
        case .monthly: return "Monthly"
        case .quarterly: return "Every 3 months"
        case .yearly: return "Yearly"
        }
    }

    /// "Every 14 days" counts as every 2 weeks, so older jobs still light up their chip.
    func matches(_ form: RepeatForm) -> Bool {
        guard let interval = form.interval else { return false }
        if form.unit == unit { return interval == self.interval }
        return unit == .week && form.unit == .day && interval == self.interval * 7
    }
}

/// Which day of the month a monthly job lands on: a date ("the 29th", or the last day), or a
/// weekday ("the fifth Thursday" is always "the last Thursday").
struct MonthOption: Hashable {
    var monthDay: Int? = nil
    var weekOfMonth: Int? = nil
    /// Calendar weekday number, Sunday = 1.
    var weekday: Int? = nil

    var byWeekday: Bool { weekOfMonth != nil }

    var label: String {
        if let week = weekOfMonth, let weekday {
            return "On the \(formatWeekOfMonth(week)) \(Calendar.current.weekdaySymbols[weekday - 1])"
        }
        if monthDay == lastDayOfMonth { return "On the last day" }
        return "On the \(ordinal(monthDay ?? 1))"
    }

    /// What `date` could mean: always its date, the last day if it is one, its weekday of the
    /// month (first to fourth), and "the last <weekday>" if it is the last one.
    static func choices(for date: Date, calendar: Calendar = .current) -> [MonthOption] {
        let day = calendar.component(.day, from: date)
        let weekday = calendar.component(.weekday, from: date)
        let length = calendar.range(of: .day, in: .month, for: date)?.count ?? 31
        let week = (day - 1) / 7 + 1
        var options = [MonthOption(monthDay: day)]
        if day == length { options.append(MonthOption(monthDay: lastDayOfMonth)) }
        if week <= 4 { options.append(MonthOption(weekOfMonth: week, weekday: weekday)) }
        if day + 7 > length { options.append(MonthOption(weekOfMonth: lastDayOfMonth, weekday: weekday)) }
        return options
    }
}

/// The repeat part of the job form. Same as Android:
///
/// One date drives everything: `startDate`, the first (or next) due date. Monthly and yearly
/// dates come from it, and weekly on one day moves to its weekday, so there are no long lists of
/// day numbers. New jobs start today; picking a date changes it.
///
/// Everything the rule might need is kept, so switching modes or units doesn't lose what you'd
/// picked, and `rule` takes the parts that apply. "Show it early" stays shorter than the repeat
/// and is suggested until you pick it.
struct RepeatForm: Equatable {
    /// Nil = doesn't repeat. 0 while the number is being retyped.
    var interval: Int? = nil
    var unit: RepeatUnit = .week
    var anchor: RepeatAnchor = .schedule
    /// Weekly on set days. Monday = bit 0. Never empty once set.
    var weekdays = 0
    /// Monthly. Nil until filled in from `startDate`.
    var monthOption: MonthOption? = nil
    /// Yearly: month and day.
    var month: Int? = nil
    var yearDay: Int? = nil
    var leadDays = 0
    var leadChosen = false
    /// The date the repeat starts from (start of day): today for new jobs, the next due date when editing.
    var startDate = Calendar.current.startOfDay(for: .now)
    /// You picked `startDate` yourself.
    var startPicked = false
    /// The rule and due date as loaded, to tell what an edit keeps.
    var originalRule: RepeatRule? = nil
    var originalNextDue: Date? = nil

    init() {}

    /// The job's repeat as the form shows it.
    init(job: Job, now: Date = .now) {
        guard let rule = job.repeatRule else { return }
        let calendar = Calendar.current
        let today = calendar.startOfDay(for: now)
        // Start from the next due date: one the rule lands on, so its own choices include it.
        let start = job.nextDueAt.map { calendar.startOfDay(for: $0) } ?? (rule.onSetDays ? rule.firstOnOrAfter(today) : today)
        interval = rule.interval
        unit = rule.unit
        anchor = rule.anchor
        weekdays = rule.unit == .week ? rule.weekdays ?? 0 : 0
        if rule.unit == .month && rule.onSetDays {
            if rule.byWeekdayOfMonth {
                let day = mondayFirstWeekdays.first { (rule.weekdays ?? 0) & weekdayBit(calendarWeekday: $0) != 0 }
                monthOption = MonthOption(weekOfMonth: rule.weekOfMonth, weekday: day)
            } else {
                monthOption = MonthOption(monthDay: rule.monthDay ?? calendar.component(.day, from: start))
            }
        }
        month = rule.month
        yearDay = rule.unit == .year ? rule.monthDay : nil
        leadDays = rule.leadDays
        leadChosen = true
        startDate = start
        originalRule = rule
        originalNextDue = job.nextDueAt
        normalize()
    }

    var repeats: Bool { interval != nil }
    var onSetDays: Bool { anchor == .schedule }
    var isValid: Bool { (interval ?? 1) > 0 }

    var rule: RepeatRule? {
        guard let n = interval else { return nil }
        var base = RepeatRule(interval: n, unit: unit, anchor: anchor, leadDays: leadDays)
        guard onSetDays else { return base }
        let calendar = Calendar.current
        switch unit {
        case .day:
            break
        case .week:
            base.weekdays = weekdays != 0 ? weekdays : nil
        case .month:
            let option = monthOption ?? MonthOption(monthDay: calendar.component(.day, from: startDate))
            base.monthDay = option.monthDay
            base.weekOfMonth = option.weekOfMonth
            base.weekdays = option.weekday.map { weekdayBit(calendarWeekday: $0) }
        case .year:
            base.month = month ?? calendar.component(.month, from: startDate)
            base.monthDay = yearDay ?? calendar.component(.day, from: startDate)
        }
        return base
    }

    /// Monthly choices for the start date, plus the job's current one if the date doesn't suggest
    /// it (a job on the 31st whose next due date is Feb 28).
    var monthChoices: [MonthOption] {
        let choices = MonthOption.choices(for: startDate)
        guard let monthOption, !choices.contains(monthOption) else { return choices }
        return [monthOption] + choices
    }

    /// "Show it early" choices that fit the repeat (always includes "on the day").
    var leadOptions: [Int] {
        guard (interval ?? 0) > 0, let rule else { return [0] }
        let maxLead = maxLeadDays(rule)
        return leadChoices.filter { $0 <= maxLead }
    }

    mutating func setRepeats(_ on: Bool) {
        if !on {
            interval = nil
        } else if !repeats {
            apply(.weekly)
        }
    }

    /// Keeps the days or date you'd picked; otherwise they come from the start date.
    mutating func apply(_ preset: RepeatPreset) {
        interval = preset.interval
        unit = preset.unit
        anchor = .schedule
        normalize()
    }

    mutating func setInterval(_ value: Int) { interval = value; normalize() }
    mutating func setUnit(_ value: RepeatUnit) { unit = value; normalize() }
    mutating func setMode(_ value: RepeatAnchor) { anchor = value; normalize() }
    mutating func setMonthOption(_ value: MonthOption) { monthOption = value; normalize() }

    mutating func setLeadDays(_ days: Int) {
        leadDays = days
        leadChosen = true
    }

    /// Won't untick the last day: a weekly job on set days needs at least one.
    mutating func toggleWeekday(_ bit: Int) {
        let next = weekdays ^ bit
        guard next != 0 else { return }
        weekdays = next
        normalize()
    }

    /// Picks when it starts. The dates follow: monthly keeps its kind (date, last day, weekday)
    /// but from the new date, yearly becomes that date, and weekly on one day moves to its weekday.
    mutating func setStartDate(_ date: Date) {
        let calendar = Calendar.current
        let day = calendar.startOfDay(for: date)
        let choices = MonthOption.choices(for: day)
        if let kept = monthOption {
            // Every date has at least one weekday choice: first to fourth, or the last.
            let firstWeekday = choices.first(where: \.byWeekday)
            if kept.byWeekday && kept.weekOfMonth == lastDayOfMonth {
                monthOption = choices.first { $0.weekOfMonth == lastDayOfMonth } ?? firstWeekday
            } else if kept.byWeekday {
                monthOption = firstWeekday
            } else if kept.monthDay == lastDayOfMonth {
                monthOption = choices.first { $0.monthDay == lastDayOfMonth } ?? choices[0]
            } else {
                monthOption = choices[0]
            }
        }
        if mondayFirstWeekdays.filter({ weekdays & weekdayBit(calendarWeekday: $0) != 0 }).count == 1 {
            weekdays = weekdayBit(calendarWeekday: calendar.component(.weekday, from: day))
        }
        month = calendar.component(.month, from: day)
        yearDay = calendar.component(.day, from: day)
        startDate = day
        startPicked = true
        normalize()
    }

    /// The first due date to save, or nil to leave it to the repository: an edit that keeps the
    /// kind of repeat keeps its due date, and a new "after I finish it" job without a picked date
    /// is due now. Otherwise it's the rule's first date from the start date (today if that's passed).
    func firstDueToSave(now: Date = .now) -> Date? {
        guard (interval ?? 0) > 0, let current = rule else { return nil }
        if !startPicked {
            if let originalRule, current.sameShape(as: originalRule) { return nil }
            if !onSetDays && (originalRule == nil || originalNextDue == nil) { return nil }
        }
        return current.firstOnOrAfter(max(startDate, Calendar.current.startOfDay(for: now)))
    }

    /// When it'll next be due, for the "Next due" line. Nil = due now.
    func nextDue(now: Date = .now) -> Date? {
        guard (interval ?? 0) > 0, let current = rule else { return nil }
        if !startPicked, let originalRule, current.sameShape(as: originalRule) { return originalNextDue }
        return firstDueToSave(now: now) ?? current.initialDue(now: now)
    }

    /// Fills in whatever the unit needs from the start date, then the lead window.
    private mutating func normalize() {
        let calendar = Calendar.current
        let parts = calendar.dateComponents([.month, .day, .weekday], from: startDate)
        if weekdays == 0 { weekdays = weekdayBit(calendarWeekday: parts.weekday ?? 2) }
        if monthOption == nil { monthOption = MonthOption(monthDay: parts.day) }
        if month == nil || yearDay == nil {
            month = parts.month
            yearDay = parts.day
        }
        guard (interval ?? 0) > 0, let current = rule else { return }
        let lead = leadChosen ? leadDays : suggestedLeadDays(current)
        leadDays = min(max(lead, 0), maxLeadDays(current))
    }
}

/// The repeat settings, shown once Repeat is on: quick picks, On set days / After I finish it,
/// "Every [2] [weeks]" plus the days or monthly choices, the Next due date (the one place a date
/// is picked), and show it early.
struct RepeatEditor: View {
    @Binding var form: RepeatForm

    var body: some View {
        if let rule = form.rule, let interval = form.interval {
            Text(interval > 0 ? formatRecurrence(rule) : "Every").font(.title3.weight(.semibold))

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(RepeatPreset.allCases) { preset in
                        FilterChip(label: preset.label, selected: preset.matches(form)) { form.apply(preset) }
                    }
                }
            }

            Picker("Repeat", selection: Binding(get: { form.anchor }, set: { form.setMode($0) })) {
                Text("On set days").tag(RepeatAnchor.schedule)
                Text("After I finish it").tag(RepeatAnchor.completion)
            }
            .pickerStyle(.segmented)
            .labelsHidden()

            HStack(spacing: 10) {
                Text("Every")
                TextField(
                    "1",
                    text: Binding(
                        get: { interval == 0 ? "" : String(interval) },
                        set: { text in form.setInterval(Int(String(text.filter(\.isNumber).prefix(3))) ?? 0) }
                    )
                )
                .keyboardType(.numberPad)
                .multilineTextAlignment(.center)
                .frame(width: 56)
                .textFieldStyle(.roundedBorder)
                Picker("Unit", selection: Binding(get: { form.unit }, set: { form.setUnit($0) })) {
                    ForEach(RepeatUnit.allCases) { unit in
                        Text(unitName(unit, count: interval)).tag(unit)
                    }
                }
                .pickerStyle(.menu)
                .labelsHidden()
                Spacer(minLength: 0)
            }

            if form.onSetDays && form.unit == .week {
                weekdayPicker
            }
            if form.onSetDays && form.unit == .month {
                Picker(
                    "Day of the month",
                    selection: Binding(get: { form.monthOption ?? form.monthChoices[0] }, set: { form.setMonthOption($0) })
                ) {
                    ForEach(form.monthChoices, id: \.self) { option in
                        Text(option.label).tag(option)
                    }
                }
                .pickerStyle(.inline)
                .labelsHidden()
            }

            nextDueRow
            explainer(
                form.onSetDays
                    ? "Stays on these days, however early or late you finish."
                    : "Counts from the day you finish it, so it moves if you're early or late."
            )

            // Daily on set days has nowhere to show it early, so the row would only ever say "On the day".
            if form.leadOptions.count > 1 {
                Picker("Show it early", selection: Binding(get: { form.leadDays }, set: { form.setLeadDays($0) })) {
                    ForEach(form.leadOptions, id: \.self) { days in
                        Text(leadLabel(days)).tag(days)
                    }
                }
                .pickerStyle(.menu)
                if form.leadDays > 0 {
                    explainer("It goes back in the jar, and on Coming up, that far ahead.")
                }
            }
        }
    }

    private var weekdayPicker: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 6) {
                Text("On").font(.subheadline)
                ForEach(mondayFirstWeekdays, id: \.self) { weekday in
                    let bit = weekdayBit(calendarWeekday: weekday)
                    FilterChip(label: Calendar.current.shortWeekdaySymbols[weekday - 1], selected: form.weekdays & bit != 0) {
                        form.toggleWeekday(bit)
                    }
                }
            }
        }
    }

    /// "Next due [Oct 29, 2026]": the first (or next) due date, and the one place a date is picked.
    /// On set days the date snaps to the rule's days.
    private var nextDueRow: some View {
        let today = Calendar.current.startOfDay(for: .now)
        return DatePicker(
            "Next due",
            selection: Binding(get: { max(form.nextDue() ?? today, today) }, set: { form.setStartDate($0) }),
            in: today...,
            displayedComponents: .date
        )
    }

    private func explainer(_ text: String) -> some View {
        Text(text).font(.footnote).foregroundStyle(.secondary)
    }
}

/// "day"/"days" etc. "Every [0] days" while retyping reads better than "day".
private func unitName(_ unit: RepeatUnit, count: Int) -> String {
    let one = count == 1
    switch unit {
    case .day: return one ? "day" : "days"
    case .week: return one ? "week" : "weeks"
    case .month: return one ? "month" : "months"
    case .year: return one ? "year" : "years"
    }
}

private func leadLabel(_ days: Int) -> String {
    switch days {
    case 0: return "On the day"
    case 1: return "1 day before"
    default: return "\(days) days before"
    }
}
