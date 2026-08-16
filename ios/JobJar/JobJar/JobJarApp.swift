import SwiftData
import SwiftUI

@main
struct JobJarApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
        }
        .modelContainer(for: Job.self)
    }
}
