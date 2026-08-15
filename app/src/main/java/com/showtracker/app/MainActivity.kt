package com.showtracker.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.showtracker.app.ui.ShowTrackerNavHost
import com.showtracker.app.ui.theme.ShowTrackerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val container = (application as ShowTrackerApplication).container

        setContent {
            ShowTrackerTheme {
                ShowTrackerNavHost(container)
            }
        }
    }
}
