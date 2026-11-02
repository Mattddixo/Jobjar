import SwiftData
import SwiftUI

/// Settings > Reminders: due repeating jobs, a daily nudge, and when they go out. Turning one on
/// asks for notification permission if it hasn't been asked yet.
struct ReminderSettingsSection: View {
    @Query private var allJobs: [Job]
    @AppStorage(remindersDueKey) private var due = false
    @AppStorage(remindersNudgeKey) private var nudge = false
    @AppStorage(remindersTimeKey) private var minutes = defaultReminderMinutes
    @State private var allowed = true

    private var time: Binding<Date> {
        Binding(
            get: { Calendar.current.date(byAdding: .minute, value: minutes, to: Calendar.current.startOfDay(for: .now)) ?? .now },
            set: { date in
                let parts = Calendar.current.dateComponents([.hour, .minute], from: date)
                minutes = (parts.hour ?? 9) * 60 + (parts.minute ?? 0)
            }
        )
    }

    var body: some View {
        Section {
            Toggle(isOn: $due) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Repeating jobs coming due")
                    Text("When one's due or overdue, and when it starts showing early.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
            Toggle(isOn: $nudge) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Daily nudge to draw a job")
                    Text("Tap it to open the app and draw.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
            if due || nudge {
                DatePicker("Time", selection: time, displayedComponents: .hourAndMinute)
                if !allowed {
                    Text("Notifications are off for Job Jar. Turn them on in Settings.")
                        .font(.footnote)
                        .foregroundStyle(.red)
                }
            }
        } header: {
            Text("Reminders")
        }
        .onChange(of: due) { changed() }
        .onChange(of: nudge) { changed() }
        .onChange(of: minutes) { ReminderScheduler.reschedule(jobs: allJobs) }
    }

    private func changed() {
        if due || nudge {
            ReminderScheduler.requestPermission { granted in allowed = granted }
        }
        ReminderScheduler.reschedule(jobs: allJobs)
    }
}
