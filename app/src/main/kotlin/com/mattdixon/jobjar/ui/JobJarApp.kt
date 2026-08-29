package com.mattdixon.jobjar.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.mattdixon.jobjar.data.JobRepository
import com.mattdixon.jobjar.ui.addedit.AddEditJobScreen
import com.mattdixon.jobjar.ui.draw.DrawScreen
import com.mattdixon.jobjar.ui.jobdetail.JobDetailScreen
import com.mattdixon.jobjar.ui.joblist.JobListScreen
import com.mattdixon.jobjar.ui.stats.StatsScreen

private sealed class TopLevelDestination(val route: String, val label: String, val icon: ImageVector) {
    data object Draw : TopLevelDestination("draw", "Jar", Icons.Filled.Shuffle)
    data object Jobs : TopLevelDestination("jobs", "Jobs", Icons.Filled.ListAlt)
    data object Stats : TopLevelDestination("stats", "Stats", Icons.Filled.BarChart)
}

private val topLevelDestinations = listOf(TopLevelDestination.Draw, TopLevelDestination.Jobs, TopLevelDestination.Stats)

@Composable
fun JobJarApp(
    repository: JobRepository,
    darkTheme: Boolean,
    onToggleTheme: () -> Unit,
    navController: NavHostController = rememberNavController()
) {
    Scaffold(
        bottomBar = {
            val backStackEntry by navController.currentBackStackEntryAsState()
            val currentDestination = backStackEntry?.destination
            NavigationBar {
                topLevelDestinations.forEach { destination ->
                    NavigationBarItem(
                        selected = currentDestination?.hierarchy?.any { it.route == destination.route } == true,
                        onClick = {
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(destination.icon, contentDescription = destination.label) },
                        label = { Text(destination.label) }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = TopLevelDestination.Draw.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(TopLevelDestination.Draw.route) {
                DrawScreen(
                    repository = repository,
                    onOpenJob = { jobId -> navController.navigate("job/$jobId") },
                    darkTheme = darkTheme,
                    onToggleTheme = onToggleTheme
                )
            }
            composable(TopLevelDestination.Jobs.route) {
                JobListScreen(
                    repository = repository,
                    onAddJob = { navController.navigate("job/new") },
                    onOpenJob = { jobId -> navController.navigate("job/$jobId") },
                    darkTheme = darkTheme,
                    onToggleTheme = onToggleTheme
                )
            }
            composable(TopLevelDestination.Stats.route) {
                StatsScreen(repository = repository, darkTheme = darkTheme, onToggleTheme = onToggleTheme)
            }
            composable(
                route = "job/new?title={title}&category={category}&sourceTrackerJobId={sourceTrackerJobId}",
                arguments = listOf(
                    navArgument("title") { type = NavType.StringType; nullable = true },
                    navArgument("category") { type = NavType.StringType; nullable = true },
                    navArgument("sourceTrackerJobId") { type = NavType.StringType; nullable = true }
                )
            ) { backStackEntry ->
                AddEditJobScreen(
                    repository = repository,
                    jobId = null,
                    onDone = { navController.popBackStack() },
                    onOpenSubtask = { openId -> navController.navigate("job/$openId") },
                    onAddSubtask = { newParentId -> navController.navigate("job/$newParentId/subtask/new") },
                    prefillTitle = backStackEntry.arguments?.getString("title"),
                    prefillCategory = backStackEntry.arguments?.getString("category"),
                    sourceTrackerJobId = backStackEntry.arguments?.getString("sourceTrackerJobId")?.toLongOrNull()
                )
            }
            composable(
                route = "job/{jobId}",
                arguments = listOf(navArgument("jobId") { type = NavType.LongType })
            ) { backStackEntry ->
                val jobId = backStackEntry.arguments?.getLong("jobId") ?: return@composable
                JobDetailScreen(
                    repository = repository,
                    jobId = jobId,
                    onEdit = { navController.navigate("job/$jobId/edit") },
                    onAddSubtask = { navController.navigate("job/$jobId/subtask/new") },
                    onOpenJob = { openId -> navController.navigate("job/$openId") },
                    onBack = { navController.popBackStack() }
                )
            }
            composable(
                route = "job/{jobId}/edit",
                arguments = listOf(navArgument("jobId") { type = NavType.LongType })
            ) { backStackEntry ->
                val jobId = backStackEntry.arguments?.getLong("jobId") ?: return@composable
                AddEditJobScreen(
                    repository = repository,
                    jobId = jobId,
                    onDone = { navController.popBackStack() },
                    onOpenSubtask = { openId -> navController.navigate("job/$openId") },
                    onAddSubtask = { parentId -> navController.navigate("job/$parentId/subtask/new") }
                )
            }
            composable(
                route = "job/{parentId}/subtask/new",
                arguments = listOf(navArgument("parentId") { type = NavType.LongType })
            ) { backStackEntry ->
                val parentId = backStackEntry.arguments?.getLong("parentId") ?: return@composable
                AddEditJobScreen(
                    repository = repository,
                    jobId = null,
                    parentId = parentId,
                    onDone = { navController.popBackStack() }
                )
            }
        }
    }
}
