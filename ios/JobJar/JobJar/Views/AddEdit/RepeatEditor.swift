import SwiftUI

/// Choices for "Show it early", in days.
private let leadChoices = [0, 1, 3, 7, 14, 30, 60]

/// The quick picks above the "Every N ..." line.
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
        guard let interval = form.interval, form.rule?.onWeekdays != true else { return false }
        if form.unit == unit { return interval == self.interval }
        return unit == .week && form.unit == .day && interval == self.interval * 7
    }
}

/// The repeat part of the job form. Same defaults as Android: presets fill everything in, a new
/// unit gets its usual "counts from", and "show it early" follows the rule until you pick one.
struct RepeatForm: Equatable {
    /// Nil = doesn't repeat. 0 while the number is being retyped.
    var interval: Int? = nil
    var unit: RepeatUnit = .week
    /// Every week only. Nil or 0 = no set days.
    var weekdays: Int? = nil
    var anchor: RepeatAnchor = .completion
    var monthDay: Int? = nil
    var month: Int? = nil
    var leadDays = 0
    /// Picked "show it early" by hand, so changing the rule stops suggesting one.
    var leadChosen = false

    init() {}

    init(rule: RepeatRule?) {
        guard let rule else { return }
        interval = rule.interval
        unit = rule.unit
        weekdays = rule.weekdays
        anchor = rule.anchor
        monthDay = rule.monthDay
        month = rule.month
        leadDays = rule.leadDays
        leadChosen = true
        fillDateDefaults()
    }

    var repeats: Bool { interval != nil }

    /// Set days only make sense for "every 1 week".
    var canPickWeekdays: Bool { unit == .week && interval == 1 }

    var isValid: Bool { (interval ?? 1) > 0 }

    var rule: RepeatRule? {
        guard let interval else { return nil }
        return RepeatRule(
            interval: interval,
            unit: unit,
            weekdays: canPickWeekdays && (weekdays ?? 0) != 0 ? weekdays : nil,
            anchor: anchor,
            monthDay: monthDay,
            month: month,
            leadDays: leadDays
        )
    }

    /// Defaults to weekly.
    mutating func setRepeats(_ on: Bool) {
        if !on {
            interval = nil
        } else if !repeats {
            apply(.weekly)
        }
    }

    mutating func apply(_ preset: RepeatPreset) {
        interval = preset.interval
        unit = preset.unit
        weekdays = nil
        anchor = defaultAnchor(preset.unit)
        fillDateDefaults()
        suggestLead()
    }

    mutating func setInterval(_ value: Int) {
        interval = value
        suggestLead()
    }

    mutating func setUnit(_ value: RepeatUnit) {
        guard value != unit else { return }
        unit = value
        anchor = defaultAnchor(value)
        fillDateDefaults()
        suggestLead()
    }

    mutating func setAnchor(_ value: RepeatAnchor) {
        anchor = value
        fillDateDefaults()
    }

    /// Keeps the day valid for the new month: the 31st becomes the 30th in April. Feb 29 is allowed.
    mutating func setMonth(_ value: Int) {
        month = value
        if let day = monthDay, day > maxDaysInMonth(value) { monthDay = maxDaysInMonth(value) }
    }

    mutating func setLeadDays(_ days: Int) {
        leadDays = days
        leadChosen = true
    }

    mutating func toggleWeekday(_ bit: Int) {
        weekdays = (weekdays ?? 0) ^ bit
        suggestLead()
    }

    /// Monthly and yearly on a schedule need a date: start from today's.
    private mutating func fillDateDefaults() {
        guard anchor == .schedule else { return }
        let today = Calendar.current.dateComponents([.month, .day], from: .now)
        switch unit {
        case .month:
            if monthDay == nil { monthDay = today.day }
        case .year:
            let chosenMonth = month ?? today.month ?? 1
            let maxDay = maxDaysInMonth(chosenMonth)
            month = chosenMonth
            // Yearly picks a real date, so "last day" from a monthly rule becomes that month's last.
            switch monthDay {
            case nil: monthDay = min(today.day ?? 1, maxDay)
            case .some(lastDayOfMonth): monthDay = maxDay
            case let day?: monthDay = min(max(day, 1), maxDay)
            }
        default:
            break
        }
    }

    private mutating func suggestLead() {
        guard !leadChosen, (interval ?? 0) > 0, let rule else { return }
        leadDays = suggestedLeadDays(rule)
    }
}

/// The repeat settings, shown once Repeat is on: quick picks, then the rule spelled out as
/// "Every [2] [weeks]" with whatever that rule needs underneath.
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

            if form.canPickWeekdays {
                Text("On these days (optional)").font(.subheadline)
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 6) {
                        ForEach(mondayFirstWeekdays, id: \.self) { weekday in
                            let bit = weekdayBit(calendarWeekday: weekday)
                            FilterChip(
                                label: Calendar.current.shortWeekdaySymbols[weekday - 1],
                                selected: (form.weekdays ?? 0) & bit != 0
                            ) {
                                form.toggleWeekday(bit)
                            }
                        }
                    }
                }
            }

            if rule.onWeekdays {
                explainer("Comes back each chosen day, even if you were late last time.")
            } else {
                if form.anchor == .schedule && (form.unit == .month || form.unit == .year) {
                    datePickers
                }
                VStack(alignment: .leading, spacing: 6) {
                    Text("Next one counts from").font(.subheadline)
                    Picker("Next one counts from", selection: Binding(get: { form.anchor }, set: { form.setAnchor($0) })) {
                        Text("The schedule").tag(RepeatAnchor.schedule)
                        Text("When I finish it").tag(RepeatAnchor.completion)
                    }
                    .pickerStyle(.segmented)
                    .labelsHidden()
                }
                explainer(
                    form.anchor == .schedule
                        ? "Stays on its dates, however early or late you finish."
                        : "Counts from the day you finish it."
                )
            }

            Picker("Show it early", selection: Binding(get: { form.leadDays }, set: { form.setLeadDays($0) })) {
                ForEach(Array(Set(leadChoices + [form.leadDays])).sorted(), id: \.self) { days in
                    Text(leadLabel(days)).tag(days)
                }
            }
            .pickerStyle(.menu)
            if form.leadDays > 0 {
                explainer("It goes back in the jar, and on Coming up, that far ahead.")
            }
        }
    }

    /// "On the [15th]" for months, "On [March] [3]" for years.
    @ViewBuilder
    private var datePickers: some View {
        HStack(spacing: 10) {
            Text("On")
            if form.unit == .year {
                let month = form.month ?? 1
                Picker("Month", selection: Binding(get: { month }, set: { form.setMonth($0) })) {
                    ForEach(1...12, id: \.self) { m in
                        Text(Calendar.current.monthSymbols[m - 1]).tag(m)
                    }
                }
                .pickerStyle(.menu)
                .labelsHidden()
                Picker("Day", selection: Binding(get: { form.monthDay ?? 1 }, set: { form.monthDay = $0 })) {
                    ForEach(1...maxDaysInMonth(month), id: \.self) { d in
                        Text("\(d)").tag(d)
                    }
                }
                .pickerStyle(.menu)
                .labelsHidden()
            } else {
                Picker("Day", selection: Binding(get: { form.monthDay ?? 1 }, set: { form.monthDay = $0 })) {
                    ForEach(Array(1...31) + [lastDayOfMonth], id: \.self) { d in
                        Text(d == lastDayOfMonth ? "Last day" : "The \(ordinal(d))").tag(d)
                    }
                }
                .pickerStyle(.menu)
                .labelsHidden()
            }
            Spacer(minLength: 0)
        }
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
