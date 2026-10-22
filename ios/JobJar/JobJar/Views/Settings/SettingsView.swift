import SwiftData
import SwiftUI
import UniformTypeIdentifiers

enum ThemeMode: String, CaseIterable, Identifiable {
    case system, light, dark

    var id: String { rawValue }

    var label: String {
        switch self {
        case .system: return "System"
        case .light: return "Light"
        case .dark: return "Dark"
        }
    }

    var colorScheme: ColorScheme? {
        switch self {
        case .system: return nil
        case .light: return .light
        case .dark: return .dark
        }
    }
}

struct SettingsView: View {
    @Environment(\.modelContext) private var modelContext
    @Environment(\.dismiss) private var dismiss
    @Query private var allJobs: [Job]
    @AppStorage("themeMode") private var themeMode = ThemeMode.system.rawValue

    @State private var exportDocument: JobExportDocument? = nil
    @State private var showExporter = false
    @State private var showImporter = false
    @State private var pendingImport: [JobRecord]? = nil
    @State private var message: String? = nil

    private var repository: JobRepository { JobRepository(modelContext: modelContext) }

    private var version: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? ""
    }

    var body: some View {
        Form {
            Section("Appearance") {
                Picker("Theme", selection: $themeMode) {
                    ForEach(ThemeMode.allCases) { mode in
                        Text(mode.label).tag(mode.rawValue)
                    }
                }
                .pickerStyle(.segmented)
            }

            Section {
                Button {
                    if let data = try? JobExport.encode(allJobs) {
                        exportDocument = JobExportDocument(data: data)
                        showExporter = true
                    } else {
                        message = "Couldn't create the export."
                    }
                } label: {
                    Label("Export jobs", systemImage: "square.and.arrow.up")
                }
                Button {
                    showImporter = true
                } label: {
                    Label("Import jobs", systemImage: "square.and.arrow.down")
                }
            } header: {
                Text("Your data")
            } footer: {
                Text(
                    "Your jobs are stored only on this device. Export saves a copy you can keep anywhere and " +
                        "import again later - on this device, a new one, or the Android app."
                )
            }

            Section {
                LabeledContent("Version", value: version)
            } header: {
                Text("About")
            } footer: {
                Text("No account, no ads, no tracking. Your jobs never leave this device unless you export them.")
            }
        }
        .navigationTitle("Settings")
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button("Done") { dismiss() }
            }
        }
        .fileExporter(
            isPresented: $showExporter,
            document: exportDocument,
            contentType: .json,
            defaultFilename: "job-jar-\(Date.now.formatted(.iso8601.year().month().day()))"
        ) { result in
            if case .success = result {
                message = "Exported \(countLabel(allJobs.count, "job", "jobs"))."
            }
        }
        .fileImporter(isPresented: $showImporter, allowedContentTypes: [.json]) { result in
            guard case .success(let url) = result else { return }
            let accessing = url.startAccessingSecurityScopedResource()
            defer { if accessing { url.stopAccessingSecurityScopedResource() } }
            guard let data = try? Data(contentsOf: url) else {
                message = "Couldn't read that file. Nothing was changed."
                return
            }
            switch JobExport.decode(data) {
            case .success(let records):
                pendingImport = records
            case .failure(.newerVersion):
                message = "That export is from a newer version of Job Jar. Update the app, then try again."
            case .failure(.notABackup):
                message = "That file isn't a Job Jar export. Nothing was changed."
            case .failure(.corrupt):
                message = "Couldn't read that file. Nothing was changed."
            }
        }
        .alert(
            "Replace your jobs?",
            isPresented: Binding(get: { pendingImport != nil }, set: { if !$0 { pendingImport = nil } })
        ) {
            Button("Cancel", role: .cancel) { pendingImport = nil }
            Button("Import", role: .destructive) {
                if let records = pendingImport {
                    repository.replaceAll(with: records)
                    message = "Imported \(countLabel(records.count, "job", "jobs"))."
                }
                pendingImport = nil
            }
        } message: {
            let incoming = pendingImport?.count ?? 0
            Text(
                "This file has \(countLabel(incoming, "job", "jobs")). Importing replaces the " +
                    "\(countLabel(allJobs.count, "job", "jobs")) on this device. Export first if you want to keep them."
            )
        }
        .alert(
            message ?? "",
            isPresented: Binding(get: { message != nil }, set: { if !$0 { message = nil } })
        ) {
            Button("OK", role: .cancel) { message = nil }
        }
    }
}

/// Adds the gear button that opens Settings to a tab's root screen.
struct SettingsButton: ViewModifier {
    @State private var showSettings = false

    func body(content: Content) -> some View {
        content
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button {
                        showSettings = true
                    } label: {
                        Image(systemName: "gearshape")
                    }
                    .accessibilityLabel("Settings")
                }
            }
            .sheet(isPresented: $showSettings) {
                NavigationStack { SettingsView() }
            }
    }
}

extension View {
    func settingsButton() -> some View { modifier(SettingsButton()) }
}
