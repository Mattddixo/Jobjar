import EventKit
import SwiftUI

/// Schedule flow: asks for calendar access if it hasn't been decided, then a date and time.
/// Hands back the chosen time; the caller does the actual scheduling.
struct ScheduleSheet: View {
    var initialDate: Date? = nil
    let onConfirm: (Date) -> Void

    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL
    @State private var date = defaultScheduleTime()
    @State private var access: Access = .checking

    private enum Access { case checking, granted, denied }

    var body: some View {
        NavigationStack {
            Group {
                switch access {
                case .checking:
                    ProgressView()
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                case .granted:
                    Form {
                        DatePicker("When", selection: $date, in: Date.now..., displayedComponents: [.date, .hourAndMinute])
                            .datePickerStyle(.graphical)
                        if date <= .now {
                            Text("That time has already passed.").foregroundStyle(.red)
                        }
                    }
                case .denied:
                    VStack(alignment: .leading, spacing: 16) {
                        Text("Allow calendar access").font(.title3.weight(.semibold))
                        Text("To schedule a job, Job Jar adds it to your calendar. Turn on calendar access in Settings to use this.")
                            .foregroundStyle(.secondary)
                        Button("Open settings") {
                            dismiss()
                            if let url = URL(string: UIApplication.openSettingsURLString) { openURL(url) }
                        }
                        .buttonStyle(.borderedProminent)
                        Spacer()
                    }
                    .padding(20)
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            .navigationTitle("Schedule")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(access == .denied ? "Not now" : "Cancel") { dismiss() }
                }
                if access == .granted {
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Schedule") {
                            onConfirm(date)
                            dismiss()
                        }
                        // The sheet can sit open past the chosen time.
                        .disabled(date <= .now)
                    }
                }
            }
        }
        .presentationDetents([.large])
        .task {
            if let initialDate, initialDate > .now { date = initialDate }
            // iOS only shows the system prompt once. After a no, Settings is the only way.
            access = await DeviceCalendar.shared.requestAccess() ? .granted : .denied
        }
    }
}

extension View {
    /// Shows the schedule sheet for `job` while it's set.
    func scheduleSheet(for job: Binding<Job?>, onConfirm: @escaping (Job, Date) -> Void) -> some View {
        sheet(item: job) { target in
            ScheduleSheet(initialDate: target.scheduledDate) { date in onConfirm(target, date) }
        }
    }
}

/// Today's date, for the top of the main tabs. Tap to open the Calendar app.
struct TodayDateButton: View {
    @Environment(\.openURL) private var openURL

    var body: some View {
        Button {
            // calshow takes seconds since 2001.
            if let url = URL(string: "calshow:\(Int(Date.now.timeIntervalSinceReferenceDate))") { openURL(url) }
        } label: {
            Text(formatTodayLabel())
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
        .accessibilityLabel("Open calendar, today is \(formatTodayLabel())")
    }
}

extension View {
    /// Today's date in the top-left corner of a tab's root screen.
    func todayDateButton() -> some View {
        toolbar {
            ToolbarItem(placement: .topBarLeading) { TodayDateButton() }
        }
    }
}
