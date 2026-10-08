package com.twentyfourpi.lifelog

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import com.twentyfourpi.lifelog.ui.LifeLogRoot
import com.twentyfourpi.lifelog.ui.LifeLogTheme
import com.twentyfourpi.lifelog.ui.MainViewModel

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<MainViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val darkMode by viewModel.darkMode.collectAsStateWithLifecycle()
            LifeLogTheme(darkTheme = darkMode) { LifeLogRoot(viewModel) }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.onAppResumed()
    }
}
