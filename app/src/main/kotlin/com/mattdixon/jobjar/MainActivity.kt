package com.mattdixon.jobjar

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.mattdixon.jobjar.data.JobRepository
import com.mattdixon.jobjar.data.ThemePreferences
import com.mattdixon.jobjar.ui.JobJarApp
import com.mattdixon.jobjar.ui.theme.JobJarTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    // Set once, on the first composition, so onNewIntent (which runs outside any composable) can
    // still reach the same NavController the graph is using.
    private lateinit var navController: NavHostController
    private lateinit var repository: JobRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        repository = (application as JobJarApplication).repository

        setContent {
            val navController = rememberNavController()
            this.navController = navController

            val context = LocalContext.current
            val systemDark = isSystemInDarkTheme()
            // Falls back to the system setting until the user explicitly taps the toggle, at
            // which point the choice sticks (persisted via ThemePreferences) regardless of what
            // the system does afterward.
            var darkTheme by remember { mutableStateOf(ThemePreferences.isDarkTheme(context) ?: systemDark) }

            // Cold start via a jobjar:// deep link (e.g. Home Jobs Tracker's "Send to Job Jar").
            // A warm start (app already running) is handled by onNewIntent below instead.
            LaunchedEffect(Unit) {
                intent?.data?.let { uri -> parseIncomingDeepLink(uri)?.let { navigateTo(navController, it) } }
            }

            JobJarTheme(darkTheme = darkTheme) {
                JobJarApp(
                    repository = repository,
                    darkTheme = darkTheme,
                    onToggleTheme = {
                        darkTheme = !darkTheme
                        ThemePreferences.setDarkTheme(context, darkTheme)
                    },
                    navController = navController
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.data?.let { uri -> parseIncomingDeepLink(uri)?.let { navigateTo(navController, it) } }
    }

    private fun navigateTo(navController: NavHostController, link: IncomingDeepLink) {
        when (link) {
            is IncomingDeepLink.ViewJob -> navController.navigate("job/${link.jobId}")
            is IncomingDeepLink.CreateJob -> {
                val params = buildList {
                    link.title?.let { add("title=${Uri.encode(it)}") }
                    link.category?.let { add("category=${Uri.encode(it)}") }
                    link.sourceTrackerJobId?.let { add("sourceTrackerJobId=$it") }
                }
                val route = if (params.isEmpty()) "job/new" else "job/new?" + params.joinToString("&")
                navController.navigate(route)
            }
            is IncomingDeepLink.PickJob -> navController.navigate("jobPicker?returnJobId=${link.returnJobId}")
            is IncomingDeepLink.Linked -> {
                lifecycleScope.launch {
                    repository.setLinkedTrackerJobId(link.jobId, link.otherId)
                    navController.navigate("job/${link.jobId}")
                }
            }
        }
    }
}
