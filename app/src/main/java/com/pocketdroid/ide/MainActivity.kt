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
        AssetsBundle.init(this)
        TextMateBootstrap.init(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                IdeScreen(this)
            }
        }
    }
}
