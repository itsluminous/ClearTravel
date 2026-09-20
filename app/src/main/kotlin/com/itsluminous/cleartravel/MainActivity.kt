package com.itsluminous.cleartravel

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.itsluminous.cleartravel.core.designsystem.theme.ClearTravelTheme
import com.itsluminous.cleartravel.ui.ClearTravelApp
import dagger.hilt.android.AndroidEntryPoint

/**
 * Single-activity Compose shell. The theme setting (light/dark/system) is a stub for
 * now — [ClearTravelTheme] follows the system by default; the Menu milestone wires the
 * DataStore-backed preference through here.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // AndroidX splash (Theme.ClearTravel.Splash): must be installed before
        // super.onCreate() so the handoff to postSplashScreenTheme is seamless.
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            ClearTravelTheme {
                ClearTravelApp()
            }
        }
    }
}
