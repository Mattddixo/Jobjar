import SwiftUI

/// Choices for "Show it early", in days. Only the ones shorter than the repeat are offered.
private let leadChoices = [0, 1, 3, 7, 14, 30, 60]

/// Weeks of the month offered for "on the first Monday".
private let weeksOfMonth = [1, 2, 3, 4, lastDayOfMonth]

/// The quick picks at the top. All of them start on set days, from today.
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

/// The repeat part of the job form. Same as Android: everything the rule might need is kept (so
/// switching modes or units doesn't lose what you'd picked) and `rule` takes the parts that apply.
/// Every change fills in defaults, keeps "show it early" shorter than the repeat, and suggests one
/// until you pick it yourself.
struct RepeatForm: Equatable {
    /// Nil = doesn't repeat. 0 while the number is being retyped.
    var interval: Int? = nil
    var unit: RepeatUnit = .week
    var anchor: RepeatAnchor = .schedule
    /// Weekly on set days. Monday = bit 0. Never empty once set.
    var weekdays = 0
    /// Monthly by date and yearly.
    var monthDay: Int? = nil
    /// Monthly on "the first Monday" instead of a date.
    var monthByWeekday = false
    /// 1...4, or `lastDayOfMonth`.
    var weekOfMonth = 1
    /// Calendar weekday number, Sunday = 1.
    var monthWeekday = 2
    var month: Int? = nil
    var leadDays = 0
    var leadChosen = false
    /// The day picked with "Change". Nil = from today, or keep the current due date.
    var firstDue: Date? = nil
    /// The rule and due date as loaded, to show what an edit keeps.
    var originalRule: RepeatRule? = nil
    var originalNextDue: Date? = nil

    init() {}

    /// The job's repeat as the form shows it.
    init(job: Job) {
        guard let rule = job.repeatRule else { return }
        let calendar = Calendar.current
        let day = job.nextDueAt ?? .now
        interval = rule.interval
        unit = rule.unit
        anchor = rule.anchor
        weekdays = rule.unit == .week ? rule.weekdays ?? 0 : 0
        monthDay = rule.monthDay
        monthByWeekday = rule.byWeekdayOfMonth
        weekOfMonth = rule.weekOfMonth ?? 1
        let ruleDay = mondayFirstWeekdays.first { (rule.weekdays ?? 0) & weekdayBit(calendarWeekday: $0) != 0 }
        monthWeekday = rule.byWeekdayOfMonth ? ruleDay ?? 2 : calendar.component(.weekday, from: day)
        month = rule.month
        leadDays = rule.leadDays
        leadChosen = true
        originalRule = rule
        originalNextDue = job.nextDueAt
        normalize(today: day)
    }

    var repeats: Bool { interval != nil }
    var onSetDays: Bool { anchor == .schedule }
    var isValid: Bool { (interval ?? 1) > 0 }

    var rule: RepeatRule? {
        guard let n = interval else { return nil }
        guard onSetDays else { return RepeatRule(interval: n, unit: unit, anchor: anchor, leadDays: leadDays) }
        switch unit {
        case .day:
            return RepeatRule(interval: n, unit: unit, anchor: anchor, leadDays: leadDays)
        case .week:
            return RepeatRule(interval: n, unit: unit, weekdays: weekdays != 0 ? weekdays : nil, anchor: anchor, leadDays: leadDays)
        case .month:
            if monthByWeekday {
                return RepeatRule(
                    interval: n, unit: unit, weekdays: weekdayBit(calendarWeekday: monthWeekday), anchor: anchor,
                    weekOfMonth: weekOfMonth, leadDays: leadDays
                )
            }
            return RepeatRule(interval: n, unit: unit, anchor: anchor, monthDay: monthDay, leadDays: leadDays)
        case .year:
            return RepeatRule(interval: n, unit: unit, anchor: anchor, monthDay: monthDay, month: month, leadDays: leadDays)
        }
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

    /// Keeps days or dates you'd already picked; otherwise they start from today.
    mutating func apply(_ preset: RepeatPreset, today: Date = .now) {
        interval = preset.interval
        unit = preset.unit
        anchor = .schedule
        normalize(today: today)
    }

    mutating func setInterval(_ value: Int) { interval = value; normalize() }
    mutating func setUnit(_ value: RepeatUnit) { unit = value; normalize() }
    mutating func setMode(_ value: RepeatAnchor) { anchor = value; normalize() }
    mutating func setMonthDay(_ value: Int) { monthDay = value; normalize() }
    mutating func setWeekOfMonth(_ value: Int) { weekOfMonth = value; normalize() }
    mutating func setMonthWeekday(_ value: Int) { monthWeekday = value; normalize() }

    /// Switching to "same weekday" starts from today's: the 29th, a Thursday, is the last Thursday.
    mutating func setMonthByWeekday(_ value: Bool, today: Date = .now) {
        if value && !monthByWeekday {
            let calendar = Calendar.current
            let week = (calendar.component(.day, from: today) - 1) / 7 + 1
            weekOfMonth = week > 4 ? lastDayOfMonth : week
            monthWeekday = calendar.component(.weekday, from: today)
        }
        monthByWeekday = value
        normalize(today: today)
    }

    /// Keeps the day valid for the new month: the 31st becomes the 30th in April. Feb 29 is allowed.
    mutating func setMonth(_ value: Int) {
        month = value
        if let day = monthDay, day > maxDaysInMonth(value) { monthDay = maxDaysInMonth(value) }
        normalize()
    }

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

    /// When it'll next be due, for the "Next:" line (set days only): the picked start, else the
    /// current due date if the edit keeps it, else the first of the rule's dates from today. Nil
    /// means due now.
    func nextDue(now: Date = .now) -> Date? {
        guard onSetDays, (interval ?? 0) > 0, let current = rule else { return nil }
        if let firstDue { return current.firstOnOrAfter(firstDue) }
        if let originalRule, current.sameShape(as: originalRule) { return originalNextDue }
        return current.initialDue(now: now)
    }

    /// Defaults for whatever the current unit needs, then the lead window.
    private mutating func normalize(today: Date = .now) {
        let calendar = Calendar.current
        let parts = calendar.dateComponents([.month, .day, .weekday], from: today)
        if weekdays == 0 { weekdays = weekdayBit(calendarWeekday: parts.weekday ?? 2) }
        if monthDay == nil { monthDay = parts.day }
        if month == nil { month = parts.month }
        if unit == .year {
            // Yearly picks a real date, so "last day" from a monthly rule becomes that month's last.
            let maxDay = maxDaysInMonth(month ?? 1)
            let day = monthDay ?? 1
            monthDay = day == lastDayOfMonth ? maxDay : min(max(day, 1), maxDay)
        }
        guard (interval ?? 0) > 0, let current = rule else { return }
        let lead = leadChosen ? leadDays : suggestedLeadDays(current)
        leadDays = min(max(lead, 0), maxLeadDays(current))
    }
}

/// The repeat settings, shown once Repeat is on: quick picks, On set days / After I finish it,
/// "Every [2] [weeks]" with whatever set days need, when it's next due, and show it early.
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

            if form.onSetDays {
                switch form.unit {
                case .day:
                    EmptyView()
                case .week:
                    weekdayPicker
                case .month:
                    monthlyPickers
                case .year:
                    yearlyPickers
                }
                nextDueRow
                explainer("Stays on these days, however early or late you finish.")
            } else {
                explainer("Counts from the day you finish it, so it moves if you're early or late.")
            }

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

    /// "Same date / Same weekday", then "On the [15th]" or "On the [first] [Monday]".
    @ViewBuilder
    private var monthlyPickers: some View {
        Picker("Monthly", selection: Binding(get: { form.monthByWeekday }, set: { form.setMonthByWeekday($0) })) {
            Text("Same date").tag(false)
            Text("Same weekday").tag(true)
        }
        .pickerStyle(.segmented)
        .labelsHidden()
        HStack(spacing: 10) {
            Text("On the")
            if form.monthByWeekday {
                Picker("Week", selection: Binding(get: { form.weekOfMonth }, set: { form.setWeekOfMonth($0) })) {
                    ForEach(weeksOfMonth, id: \.self) { week in
                        Text(formatWeekOfMonth(week)).tag(week)
                    }
                }
                .pickerStyle(.menu)
                .labelsHidden()
                Picker("Day", selection: Binding(get: { form.monthWeekday }, set: { form.setMonthWeekday($0) })) {
                    ForEach(mondayFirstWeekdays, id: \.self) { weekday in
                        Text(Calendar.current.weekdaySymbols[weekday - 1]).tag(weekday)
                    }
                }
                .pickerStyle(.menu)
                .labelsHidden()
            } else {
                Picker("Day", selection: Binding(get: { form.monthDay ?? 1 }, set: { form.setMonthDay($0) })) {
                    ForEach(Array(1...31) + [lastDayOfMonth], id: \.self) { day in
                        Text(day == lastDayOfMonth ? "last day" : ordinal(day)).tag(day)
                    }
                }
                .pickerStyle(.menu)
                .labelsHidden()
            }
            Spacer(minLength: 0)
        }
    }

    /// "On [March] [3]".
    private var yearlyPickers: some View {
        HStack(spacing: 10) {
            Text("On")
            let month = form.month ?? 1
            Picker("Month", selection: Binding(get: { month }, set: { form.setMonth($0) })) {
                ForEach(1...12, id: \.self) { m in
                    Text(Calendar.current.monthSymbols[m - 1]).tag(m)
                }
            }
            .pickerStyle(.menu)
            .labelsHidden()
            Picker("Day", selection: Binding(get: { form.monthDay ?? 1 }, set: { form.setMonthDay($0) })) {
                ForEach(1...maxDaysInMonth(month), id: \.self) { d in
                    Text("\(d)").tag(d)
                }
            }
            .pickerStyle(.menu)
            .labelsHidden()
            Spacer(minLength: 0)
        }
    }

    /// "Next: [Wed, Nov 4]". Tap the date to pick when it starts; the rule snaps it to its own dates.
    @ViewBuilder
    private var nextDueRow: some View {
        let today = Calendar.current.startOfDay(for: .now)
        if let next = form.nextDue() {
            DatePicker(
                "Next",
                selection: Binding(get: { max(next, today) }, set: { form.firstDue = Calendar.current.startOfDay(for: $0) }),
                in: today...,
                displayedComponents: .date
            )
        } else {
            HStack {
                Text("Next: due now")
                Spacer()
                Button("Change") { form.firstDue = today }
            }
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
