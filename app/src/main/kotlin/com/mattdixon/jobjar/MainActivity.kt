package com.mattdixon.jobjar

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.mattdixon.jobjar.data.ThemePreferences
import com.mattdixon.jobjar.ui.JobJarApp
import com.mattdixon.jobjar.ui.theme.JobJarTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val repository = (application as JobJarApplication).repository

        setContent {
            val context = LocalContext.current
            val systemDark = isSystemInDarkTheme()
            // Falls back to the system setting until the user explicitly taps the toggle, at
            // which point the choice sticks (persisted via ThemePreferences) regardless of what
            // the system does afterward.
            var darkTheme by remember { mutableStateOf(ThemePreferences.isDarkTheme(context) ?: systemDark) }

            JobJarTheme(darkTheme = darkTheme) {
                JobJarApp(
                    repository = repository,
                    darkTheme = darkTheme,
                    onToggleTheme = {
                        darkTheme = !darkTheme
                        ThemePreferences.setDarkTheme(context, darkTheme)
                    }
                )
            }
        }
    }
}
