package app.solarma.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.solarma.ui.create.CreateAlarmScreen
import app.solarma.ui.home.HomeScreen

/**
 * Main navigation graph for the app.
 */
@Composable
fun SolarmaNavHost(
    navController: NavHostController = rememberNavController()
) {
    NavHost(
        navController = navController,
        startDestination = Screen.Home.route
    ) {
        composable(Screen.Home.route) {
            HomeScreen(
                onAddAlarm = { navController.navigate(Screen.CreateAlarm.route) },
                onAlarmClick = { id -> 
                    // TODO: Navigate to alarm details
                },
                onSettingsClick = {
                    navController.navigate(Screen.Settings.route)
                }
            )
        }
        
        composable(Screen.CreateAlarm.route) {
            CreateAlarmScreen(
                onBack = { navController.popBackStack() },
                onSave = { state ->
                    // TODO: Save alarm via ViewModel
                    navController.popBackStack()
                }
            )
        }
        
        composable(Screen.Settings.route) {
            // TODO: Settings screen
        }
    }
}

/**
 * Screen destinations.
 */
sealed class Screen(val route: String) {
    object Home : Screen("home")
    object CreateAlarm : Screen("create_alarm")
    object AlarmDetails : Screen("alarm/{id}") {
        fun withId(id: Long) = "alarm/$id"
    }
    object Settings : Screen("settings")
}
