package com.pocketdroid.ide

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import com.pocketdroid.ide.ui.IdeScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.pocketdroid.ide.core.CoreBridge.init(this)
        AssetsBundle.init(this)
        TextMateBootstrap.init(this)
        RuntimeManager.writeLaunchers(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                IdeScreen(this)
            }
        }
    }

    override fun onDestroy() {
        // Sandbox lifecycle hygiene: never leave orphaned shells/servers behind.
        runCatching { com.pocketdroid.ide.LocalServer.stop() }
        runCatching { com.pocketdroid.ide.ui.TerminalSessions.closeAll() }
        super.onDestroy()
    }
}
