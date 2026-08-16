import SwiftUI

enum Route: Hashable {
    case jobDetail(UUID)
    case addEditJob(jobId: UUID?, parentId: UUID?)
}

struct ContentView: View {
    var body: some View {
        TabView {
            DrawTab()
                .tabItem { Label("Jar", systemImage: "shuffle") }
            JobsTab()
                .tabItem { Label("Jobs", systemImage: "list.bullet") }
            NavigationStack {
                StatsView()
            }
            .tabItem { Label("Stats", systemImage: "chart.bar") }
        }
    }
}

/// Each top-level tab owns its own NavigationPath, mirroring the Android app's single NavHost
/// with per-destination `popUpTo`/`saveState`/`restoreState`: switching tabs preserves whatever
/// push stack you left behind in the other one.
private struct DrawTab: View {
    @State private var path = NavigationPath()

    var body: some View {
        NavigationStack(path: $path) {
            DrawView(onOpenJob: { id in path.append(Route.jobDetail(id)) })
                .navigationDestination(for: Route.self) { route in
                    RouteDestination(route: route, path: $path)
                }
        }
    }
}

private struct JobsTab: View {
    @State private var path = NavigationPath()

    var body: some View {
        NavigationStack(path: $path) {
            JobListView(
                onAddJob: { path.append(Route.addEditJob(jobId: nil, parentId: nil)) },
                onOpenJob: { id in path.append(Route.jobDetail(id)) }
            )
            .navigationDestination(for: Route.self) { route in
                RouteDestination(route: route, path: $path)
            }
        }
    }
}

/// Shared push-destination resolver for both tabs that can navigate into job detail/add/edit -
/// the equivalent of the Android app's NavHost composable() routes.
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
        }
    }
}
