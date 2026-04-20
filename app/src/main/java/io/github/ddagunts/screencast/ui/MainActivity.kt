package io.github.ddagunts.screencast.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat

class MainActivity : ComponentActivity() {

    private val vm: CastViewModel by viewModels()

    private val permissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* no-op; caller falls back if denied */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissionsIfNeeded()
        setContent {
            ScreenCastTheme {
                Surface(Modifier.fillMaxSize()) { AppScaffold(vm) }
            }
        }
    }

    private fun requestPermissionsIfNeeded() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33 &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            needed += Manifest.permission.RECORD_AUDIO
        }
        if (needed.isNotEmpty()) permissionRequest.launch(needed.toTypedArray())
    }
}

// Dynamic color pulls the system's Material You palette on API 31+ so the
// app mirrors the user's wallpaper/accent. Pre-31 devices fall back to the
// stock dark scheme; we don't ship a bespoke palette.
@Composable
private fun ScreenCastTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= 31 &&
            ctx.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_NO ->
                dynamicLightColorScheme(ctx)
        Build.VERSION.SDK_INT >= 31 -> dynamicDarkColorScheme(ctx)
        else -> darkColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}

private enum class Screen { Cast, Settings, Logs }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppScaffold(vm: CastViewModel) {
    var screen by rememberSaveable { mutableStateOf(Screen.Cast) }
    Scaffold(topBar = {
        TopAppBar(
            title = {
                Text(when (screen) {
                    Screen.Cast -> "ScreenCast"
                    Screen.Settings -> "Settings"
                    Screen.Logs -> "Logs"
                })
            },
            navigationIcon = {
                if (screen != Screen.Cast) {
                    IconButton(onClick = { screen = Screen.Cast }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            },
            actions = { if (screen == Screen.Cast) CastScreenActions(onOpenSettings = { screen = Screen.Settings }, onOpenLogs = { screen = Screen.Logs }) },
        )
    }) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when (screen) {
                Screen.Cast -> CastControlScreen(vm)
                Screen.Settings -> SettingsScreen(vm)
                Screen.Logs -> LogPanelScreen()
            }
        }
    }
}

@Composable
private fun CastScreenActions(onOpenSettings: () -> Unit, onOpenLogs: () -> Unit) {
    IconButton(onClick = onOpenSettings) {
        Icon(Icons.Filled.Settings, contentDescription = "Settings")
    }
    var menuOpen by remember { mutableStateOf(false) }
    IconButton(onClick = { menuOpen = true }) {
        Icon(Icons.Filled.MoreVert, contentDescription = "More")
    }
    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
        DropdownMenuItem(
            text = { Text("Logs") },
            onClick = { menuOpen = false; onOpenLogs() },
        )
    }
}
