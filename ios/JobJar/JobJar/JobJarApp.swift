import SwiftData
import SwiftUI

@main
struct JobJarApp: App {
    @AppStorage("themeMode") private var themeMode = ThemeMode.system.rawValue
    @State private var undoCenter = UndoCenter()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environment(undoCenter)
                .preferredColorScheme(ThemeMode(rawValue: themeMode)?.colorScheme)
        }
        .modelContainer(for: Job.self)
    }
}
