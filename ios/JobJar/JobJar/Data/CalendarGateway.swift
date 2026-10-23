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
    func updateEvent(_ eventId: String, owner: Job, title: String, start: Date, end: Date) -> Bool
    func deleteEvent(_ eventId: String, owner: Job) -> Bool
    func ownsEvent(_ eventId: String, owner: Job) -> Bool
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
        return event.url == Self.jobURL(owner) ? event : nil
    }

    func insertEvent(for job: Job, start: Date, end: Date) -> String? {
        guard hasAccess, let calendar = store.defaultCalendarForNewEvents else { return nil }
        let event = EKEvent(eventStore: store)
        event.calendar = calendar
        event.title = job.title
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

    func updateEvent(_ eventId: String, owner: Job, title: String, start: Date, end: Date) -> Bool {
        guard let event = ownedEvent(eventId, owner: owner) else { return false }
        event.title = title
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
