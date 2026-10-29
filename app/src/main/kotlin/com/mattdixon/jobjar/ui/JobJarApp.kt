package com.mattdixon.jobjar.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.mattdixon.jobjar.R
import com.mattdixon.jobjar.data.JobRepository
import com.mattdixon.jobjar.data.ThemeMode
import com.mattdixon.jobjar.ui.addedit.AddEditJobScreen
import com.mattdixon.jobjar.ui.comingup.ComingUpScreen
import com.mattdixon.jobjar.ui.draw.DrawScreen
import com.mattdixon.jobjar.ui.jobdetail.JobDetailScreen
import com.mattdixon.jobjar.ui.joblist.JobListScreen
import com.mattdixon.jobjar.ui.settings.SettingsScreen
import com.mattdixon.jobjar.ui.stats.StatsScreen
import com.mattdixon.jobjar.ui.theme.LocalAppColors

private const val SETTINGS_ROUTE = "settings"
private const val COMING_UP_ROUTE = "coming-up"

private sealed class TopLevelDestination(val route: String, @StringRes val labelRes: Int, val icon: ImageVector) {
    data object Draw : TopLevelDestination("draw", R.string.tab_jar, Icons.Filled.Shuffle)
    data object Jobs : TopLevelDestination("jobs", R.string.tab_jobs, Icons.Filled.ListAlt)
    data object Stats : TopLevelDestination("stats", R.string.tab_stats, Icons.Filled.BarChart)
}

private val topLevelDestinations = listOf(TopLevelDestination.Draw, TopLevelDestination.Jobs, TopLevelDestination.Stats)

@Composable
fun JobJarApp(
    repository: JobRepository,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    /** A job to open, e.g. from "Open in Job Jar" on a calendar event. */
    openJobId: Long? = null,
    onOpenJobHandled: () -> Unit = {}
) {
    val navController = rememberNavController()
    LaunchedEffect(openJobId) {
        if (openJobId != null) {
            navController.navigate("job/$openJobId") { launchSingleTop = true }
            onOpenJobHandled()
        }
    }
    val appScope = rememberCoroutineScope()
    val undoController = remember(repository) { UndoController(repository, appScope) }
    val openSettings = { navController.navigate(SETTINGS_ROUTE) { launchSingleTop = true } }

    CompositionLocalProvider(LocalUndoController provides undoController) {
        Scaffold(
            bottomBar = {
                val backStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = backStackEntry?.destination
                NavigationBar(containerColor = LocalAppColors.current.card) {
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
                            icon = { Icon(destination.icon, contentDescription = null) },
                            label = { Text(stringResource(destination.labelRes)) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer
                            )
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
                        onOpenSettings = openSettings,
                        onOpenComingUp = { navController.navigate(COMING_UP_ROUTE) { launchSingleTop = true } }
                    )
                }
                composable(COMING_UP_ROUTE) {
                    ComingUpScreen(
                        repository = repository,
                        onOpenJob = { jobId -> navController.navigate("job/$jobId") },
                        onBack = { navController.popBackStack() }
                    )
                }
                composable(TopLevelDestination.Jobs.route) {
                    JobListScreen(
                        repository = repository,
                        onAddJob = { navController.navigate("job/new") },
                        onOpenJob = { jobId -> navController.navigate("job/$jobId") },
                        onOpenSettings = openSettings
                    )
                }
                composable(TopLevelDestination.Stats.route) {
                    StatsScreen(repository = repository, onOpenSettings = openSettings)
                }
                composable(SETTINGS_ROUTE) {
                    SettingsScreen(
                        repository = repository,
                        themeMode = themeMode,
                        onThemeModeChange = onThemeModeChange,
                        onBack = { navController.popBackStack() }
                    )
                }
                composable("job/new") {
                    AddEditJobScreen(
                        repository = repository,
                        jobId = null,
                        onDone = { navController.popBackStack() },
                        onOpenSubtask = { openId -> navController.navigate("job/$openId") },
                        onAddSubtask = { newParentId -> navController.navigate("job/$newParentId/subtask/new") }
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
}
