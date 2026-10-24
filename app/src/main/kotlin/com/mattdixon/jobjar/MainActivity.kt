package com.mattdixon.jobjar

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.CalendarContract
import androidx.activity.SystemBarStyle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.lifecycleScope
import com.mattdixon.jobjar.data.CalendarScheduler
import com.mattdixon.jobjar.data.ThemeMode
import com.mattdixon.jobjar.data.ThemePreferences
import com.mattdixon.jobjar.ui.JobJarApp
import com.mattdixon.jobjar.ui.theme.JobJarTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    /** Set when the app is opened for a particular job (from a calendar event). */
    private var openJobId by mutableStateOf<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) openJobId = jobIdFrom(intent)

        val repository = (application as JobJarApplication).repository

        setContent {
            val context = LocalContext.current
            var themeMode by remember { mutableStateOf(ThemePreferences.themeMode(context)) }
            val darkTheme = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }

            // Status bar icons follow the app's theme, which can differ from the phone's.
            DisposableEffect(darkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme },
                    navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { darkTheme }
                )
                onDispose {}
            }

            JobJarTheme(darkTheme = darkTheme) {
                JobJarApp(
                    repository = repository,
                    themeMode = themeMode,
                    onThemeModeChange = { mode ->
                        themeMode = mode
                        ThemePreferences.setThemeMode(context, mode)
                    },
                    openJobId = openJobId,
                    onOpenJobHandled = { openJobId = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        jobIdFrom(intent)?.let { openJobId = it }
    }

    override fun onResume() {
        super.onResume()
        // Pick up anything moved or deleted in the calendar app while we were away.
        val repository = (application as JobJarApplication).repository
        lifecycleScope.launch { repository.syncCalendar() }
    }

    /**
     * The job behind a calendar event's "open in app" link. The calendar app passes our
     * `jobjar://job/<id>` tag; older calendar apps only pass the event, so look it up from that.
     */
    private fun jobIdFrom(intent: Intent?): Long? {
        if (intent?.action != CalendarContract.ACTION_HANDLE_CUSTOM_EVENT) return null
        CalendarScheduler.jobIdFromUri(intent.getStringExtra(CalendarContract.EXTRA_CUSTOM_APP_URI))?.let { return it }
        val eventId = intent.data?.lastPathSegment?.toLongOrNull() ?: return null
        return CalendarScheduler(this).jobIdForEvent(eventId)
    }
}

// The default scrims enableEdgeToEdge uses for 3-button navigation.
private val LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
private val DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
