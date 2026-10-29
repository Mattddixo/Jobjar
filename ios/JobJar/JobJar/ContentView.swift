import SwiftUI

enum Route: Hashable {
    case jobDetail(UUID)
    case addEditJob(jobId: UUID?, parentId: UUID?)
    case comingUp
}

enum AppTab: Hashable {
    case jar, jobs, stats
}

struct ContentView: View {
    @Environment(\.modelContext) private var modelContext
    @Environment(\.scenePhase) private var scenePhase
    @State private var selectedTab = AppTab.jar
    /// A job to open on the Jobs tab, from a calendar event's `jobjar://job/<id>` link.
    @State private var jobToOpen: UUID? = nil

    var body: some View {
        TabView(selection: $selectedTab) {
            DrawTab()
                .tabItem { Label("Jar", systemImage: "shuffle") }
                .tag(AppTab.jar)
            JobsTab(jobToOpen: $jobToOpen)
                .tabItem { Label("Jobs", systemImage: "list.bullet") }
                .tag(AppTab.jobs)
            NavigationStack {
                StatsView()
                    .settingsButton()
            }
            .tabItem { Label("Stats", systemImage: "chart.bar") }
            .tag(AppTab.stats)
        }
        .overlay(alignment: .bottom) { UndoToast() }
        .task { JobRepository(modelContext: modelContext).applyLaunchRules() }
        .onOpenURL { url in
            guard let id = DeviceCalendar.jobId(from: url) else { return }
            selectedTab = .jobs
            jobToOpen = id
        }
        // Pick up anything moved or deleted in the Calendar app while we were away.
        .onChange(of: scenePhase, initial: true) { _, phase in
            if phase == .active { JobRepository(modelContext: modelContext).syncCalendar() }
        }
    }
}

/// Each tab keeps its own navigation stack, so switching tabs doesn't lose your place.
private struct DrawTab: View {
    @State private var path = NavigationPath()

    var body: some View {
        NavigationStack(path: $path) {
            DrawView(
                onOpenJob: { id in path.append(Route.jobDetail(id)) },
                onOpenComingUp: { path.append(Route.comingUp) }
            )
                .settingsButton()
                .navigationDestination(for: Route.self) { route in
                    RouteDestination(route: route, path: $path)
                }
        }
    }
}

private struct JobsTab: View {
    @Binding var jobToOpen: UUID?
    @State private var path = NavigationPath()

    var body: some View {
        NavigationStack(path: $path) {
            JobListView(
                onAddJob: { path.append(Route.addEditJob(jobId: nil, parentId: nil)) },
                onOpenJob: { id in path.append(Route.jobDetail(id)) }
            )
            .settingsButton()
            .navigationDestination(for: Route.self) { route in
                RouteDestination(route: route, path: $path)
            }
        }
        // Initial too: on a cold start the link can arrive before this tab exists.
        .onChange(of: jobToOpen, initial: true) { _, id in
            guard let id else { return }
            path = NavigationPath([Route.jobDetail(id)])
            jobToOpen = nil
        }
    }
}

/// Destinations shared by the Jar and Jobs tabs.
private struct RouteDestination: View {
    let route: Route
    @Binding var path: NavigationPath

    var body: some View {
        switch route {
        case .jobDetail(let id):
            JobDetailView(
                jobId: id,
                onEdit: { path.append(Route.addEditJob(jobId: id, parentId: nil)) },
                onAddSubtask: { path.append(Route.addEditJob(jobId: nil, parentId: id)) },
                onOpenJob: { openId in path.append(Route.jobDetail(openId)) },
                onBack: { if !path.isEmpty { path.removeLast() } }
            )
        case .addEditJob(let jobId, let parentId):
            AddEditJobView(
                jobId: jobId,
                parentId: parentId,
                onDone: { if !path.isEmpty { path.removeLast() } },
                onOpenSubtask: { openId in path.append(Route.jobDetail(openId)) },
                onAddSubtask: { newParentId in path.append(Route.addEditJob(jobId: nil, parentId: newParentId)) }
            )
        case .comingUp:
            ComingUpView(onOpenJob: { id in path.append(Route.jobDetail(id)) })
        }
    }
}
