import EventKit
import Foundation

/// What the repository needs from a calendar. A protocol so tests can use a fake.
///
/// Anything that touches an existing event passes the job it should belong to, and the
/// implementation checks that first. Event identifiers can end up pointing at the wrong event
/// after a restore, so an id on its own isn't enough.
@MainActor
protocol CalendarGateway {
    /// Returns the new event's identifier, or nil if it couldn't be written.
    func insertEvent(for job: Job, start: Date, end: Date) -> String?
    func updateEvent(_ eventId: String, owner: Job, title: String, notes: String, start: Date, end: Date) -> Bool
    func deleteEvent(_ eventId: String, owner: Job) -> Bool
    func ownsEvent(_ eventId: String, owner: Job) -> Bool
    /// Where `owner`'s event is now, for picking up changes made in the Calendar app. Looks it up
    /// by `eventId` first, then by the job's link in case the id changed. `.unknown` whenever it
    /// can't tell (no access), so callers never unschedule on a guess.
    func findEvent(_ eventId: String, owner: Job) -> EventLookup
}

enum EventLookup: Equatable {
    /// Couldn't check. Leave the booking alone.
    case unknown
    /// Checked, and the event is gone (deleted in the Calendar app).
    case missing
    case found(eventId: String, start: Date)
}

/// Settings > Calendar, kept in UserDefaults.
enum CalendarSettings {
    static let calendarIdKey = "calendar_id"
    static let alertMinutesKey = "calendar_alert_minutes"
    /// Alert choices, in minutes before the start, like the Calendar app offers.
    static let alertChoices = [0, 5, 10, 15, 30, 60, 1440]
    /// Fresh installs remind you 15 minutes before. -1 stored = no alert.
    static let defaultAlertMinutes = 15
    static let noAlert = -1

    /// Minutes before the start to alert, 0 = at the start, nil = no alert.
    static func alertMinutes(_ defaults: UserDefaults = .standard) -> Int? {
        let stored = defaults.object(forKey: alertMinutesKey) as? Int ?? defaultAlertMinutes
        return stored == noAlert ? nil : stored
    }

    static func alertLabel(_ minutes: Int?) -> String {
        switch minutes {
        case nil: return "None"
        case 0: return "At start"
        case 60: return "1 hour before"
        case 1440: return "1 day before"
        case let m?: return "\(m) minutes before"
        }
    }
}

/// A calendar you can pick in Settings.
struct CalendarOption: Identifiable, Hashable {
    let id: String
    let name: String
    let account: String
}

/// What goes in the event's notes: the job's notes, then where the event came from.
func eventDescription(_ notes: String) -> String {
    let footer = "Scheduled from Job Jar. Mark it complete in the app and this event goes away."
    let trimmed = notes.trimmingCharacters(in: .whitespacesAndNewlines)
    return trimmed.isEmpty ? footer : "\(trimmed)\n\n\(footer)"
}

/// Writes scheduled jobs to the phone's calendar with EventKit. Every event gets a
/// `jobjar://job/<id>` URL, and we only edit or delete an event whose URL matches.
@MainActor
final class DeviceCalendar: CalendarGateway {
    static let shared = DeviceCalendar()

    let store = EKEventStore()

    var hasAccess: Bool {
        EKEventStore.authorizationStatus(for: .event) == .fullAccess
    }

    /// Asks for calendar access if it hasn't been decided yet. True if we have it.
    func requestAccess() async -> Bool {
        if hasAccess { return true }
        return (try? await store.requestFullAccessToEvents()) ?? false
    }

    static func jobURL(_ job: Job) -> URL? {
        URL(string: "jobjar://job/\(job.id.uuidString)")
    }

    private func ownedEvent(_ eventId: String, owner: Job) -> EKEvent? {
        guard hasAccess, let event = store.event(withIdentifier: eventId) else { return nil }
        return isOurs(event, owner: owner) ? event : nil
    }

    /// Our link on the event, or for a calendar that drops links, the same title and start we
    /// wrote. An untagged event that's been moved can't be told apart from someone else's.
    private func isOurs(_ event: EKEvent, owner: Job) -> Bool {
        if let url = event.url { return url == Self.jobURL(owner) }
        return event.title == owner.title && event.startDate == owner.scheduledDate
    }

    /// The job id in a `jobjar://job/<id>` link, or nil if it isn't one.
    static func jobId(from url: URL) -> UUID? {
        guard url.scheme == "jobjar", url.host == "job" else { return nil }
        return UUID(uuidString: url.lastPathComponent)
    }

    /// Calendars you can add events to, for Settings.
    func writableCalendars() -> [CalendarOption] {
        guard hasAccess else { return [] }
        return store.calendars(for: .event)
            .filter(\.allowsContentModifications)
            .map { CalendarOption(id: $0.calendarIdentifier, name: $0.title, account: $0.source.title) }
            .sorted { ($0.account, $0.name) < ($1.account, $1.name) }
    }

    /// The calendar picked in Settings if it's still there, otherwise the phone's default.
    private func targetCalendar() -> EKCalendar? {
        if let chosen = UserDefaults.standard.string(forKey: CalendarSettings.calendarIdKey),
           let calendar = store.calendar(withIdentifier: chosen), calendar.allowsContentModifications {
            return calendar
        }
        return store.defaultCalendarForNewEvents
    }

    func findEvent(_ eventId: String, owner: Job) -> EventLookup {
        guard hasAccess, let scheduled = owner.scheduledDate else { return .unknown }
        if let event = store.event(withIdentifier: eventId) {
            if event.url == nil {
                return isOurs(event, owner: owner) ? .found(eventId: eventId, start: event.startDate) : .unknown
            }
            if isOurs(event, owner: owner) { return .found(eventId: eventId, start: event.startDate) }
        }
        // Identifiers can change when a calendar syncs, so look for our link around the booking
        // before calling it gone. (EventKit searches at most four years at a time.)
        let window = store.predicateForEvents(
            withStart: scheduled.addingTimeInterval(-180 * 86_400),
            end: scheduled.addingTimeInterval(3 * 365 * 86_400),
            calendars: nil
        )
        if let moved = store.events(matching: window).first(where: { $0.url == Self.jobURL(owner) }) {
            return .found(eventId: moved.eventIdentifier, start: moved.startDate)
        }
        return .missing
    }

    func insertEvent(for job: Job, start: Date, end: Date) -> String? {
        guard hasAccess, let calendar = targetCalendar() else { return nil }
        let event = EKEvent(eventStore: store)
        event.calendar = calendar
        if let minutes = CalendarSettings.alertMinutes() {
            // Stays with the event when it's moved; change it in the Calendar app.
            event.addAlarm(EKAlarm(relativeOffset: -Double(minutes) * 60))
        }
        event.title = job.title
        event.notes = eventDescription(job.notes)
        event.startDate = start
        event.endDate = end
        event.url = Self.jobURL(job)
        do {
            try store.save(event, span: .thisEvent)
            return event.eventIdentifier
        } catch {
            return nil
        }
    }

    func updateEvent(_ eventId: String, owner: Job, title: String, notes: String, start: Date, end: Date) -> Bool {
        guard let event = ownedEvent(eventId, owner: owner) else { return false }
        event.title = title
        event.notes = eventDescription(notes)
        event.startDate = start
        event.endDate = end
        return (try? store.save(event, span: .thisEvent)) != nil
    }

    func deleteEvent(_ eventId: String, owner: Job) -> Bool {
        guard let event = ownedEvent(eventId, owner: owner) else { return false }
        return (try? store.remove(event, span: .thisEvent)) != nil
    }

    func ownsEvent(_ eventId: String, owner: Job) -> Bool {
        ownedEvent(eventId, owner: owner) != nil
    }
}
