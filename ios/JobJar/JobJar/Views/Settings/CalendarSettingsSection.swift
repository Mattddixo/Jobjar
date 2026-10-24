import EventKit
import SwiftUI

/// Settings > Calendar: which calendar bookings go to, and the alert they get.
struct CalendarSettingsSection: View {
    /// Empty = the phone's default calendar.
    @AppStorage(CalendarSettings.calendarIdKey) private var calendarId = ""
    @AppStorage(CalendarSettings.alertMinutesKey) private var alertMinutes = CalendarSettings.defaultAlertMinutes
    @State private var calendars: [CalendarOption] = []
    @State private var hasAccess = DeviceCalendar.shared.hasAccess

    var body: some View {
        Section {
            if hasAccess {
                Picker("Add bookings to", selection: $calendarId) {
                    Text("Phone's default calendar").tag("")
                    ForEach(calendars) { calendar in
                        Text(calendar.account.isEmpty || calendar.account == calendar.name ? calendar.name : "\(calendar.name) (\(calendar.account))")
                            .tag(calendar.id)
                    }
                    // Keeps the picker valid if the chosen calendar has gone; bookings use the default.
                    if !calendarId.isEmpty && !calendars.contains(where: { $0.id == calendarId }) {
                        Text("Phone's default calendar").tag(calendarId)
                    }
                }
            } else {
                Button("Choose a calendar") {
                    Task {
                        hasAccess = await DeviceCalendar.shared.requestAccess()
                        loadCalendars()
                    }
                }
            }
            Picker("Alert", selection: $alertMinutes) {
                Text(CalendarSettings.alertLabel(nil)).tag(CalendarSettings.noAlert)
                ForEach(CalendarSettings.alertChoices, id: \.self) { minutes in
                    Text(CalendarSettings.alertLabel(minutes)).tag(minutes)
                }
            }
        } header: {
            Text("Calendar")
        } footer: {
            Text("Applies to new bookings. You can still change any single event in the Calendar app.")
        }
        .onAppear(perform: loadCalendars)
    }

    private func loadCalendars() {
        hasAccess = DeviceCalendar.shared.hasAccess
        calendars = DeviceCalendar.shared.writableCalendars()
    }
}
