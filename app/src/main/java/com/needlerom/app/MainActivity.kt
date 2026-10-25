package com.needlerom.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.needlerom.app.ui.NeedleTheme
import com.needlerom.app.ui.Root

class MainActivity : ComponentActivity() {
    private val state: AppState by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NeedleTheme { Root(state, onExit = { finish() }) }
        }
    }

    override fun onStop() {
        super.onStop()
        state.saveNow()
    }
}
