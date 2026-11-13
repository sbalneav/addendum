package ca.balneaves.addendum

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ca.balneaves.addendum.ui.FormScreen
import ca.balneaves.addendum.ui.HostKeyDialog
import ca.balneaves.addendum.ui.ProfileScreen
import ca.balneaves.addendum.ui.SettingsScreen

/** Which screen is showing. A full navigation library isn't worth it for three screens. */
private const val FORM = "form"
private const val SETTINGS = "settings"
private const val NEW_PROFILE = "profile:"   // "profile:<id>" edits, "profile:" alone is a new one

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val colors = if (isSystemInDarkTheme())
                darkColorScheme(primary = Color(0xFF9DB6E0), secondary = Color(0xFFE0A869))
            else lightColorScheme(primary = Color(0xFF34507A), secondary = Color(0xFFC77B2A))
            MaterialTheme(colorScheme = colors) {
                val vm: MainViewModel = viewModel()
                val state by vm.state.collectAsStateWithLifecycle()
                var screen by rememberSaveable { mutableStateOf(FORM) }
                BackHandler(enabled = screen != FORM) {
                    screen = if (screen.startsWith(NEW_PROFILE)) SETTINGS else FORM
                }

                when {
                    screen == SETTINGS -> SettingsScreen(
                        state, vm,
                        onBack = { screen = FORM },
                        onEditProfile = { id -> screen = NEW_PROFILE + (id ?: "") },
                    )
                    screen.startsWith(NEW_PROFILE) -> {
                        val id = screen.removePrefix(NEW_PROFILE)
                        ProfileScreen(
                            state, vm,
                            existing = state.profiles.firstOrNull { it.id == id },
                            onDone = { screen = if (it) FORM else SETTINGS },
                        )
                    }
                    else -> FormScreen(
                        state, vm,
                        onSettings = { screen = SETTINGS },
                        onNewProfile = { screen = NEW_PROFILE },
                    )
                }
                state.pendingHostKey?.let { HostKeyDialog(it, vm::trustHostKey) }
            }
        }
    }
}
