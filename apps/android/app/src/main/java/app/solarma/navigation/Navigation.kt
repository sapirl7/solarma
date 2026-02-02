package app.solarma.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.solarma.ui.create.CreateAlarmScreen
import app.solarma.ui.details.AlarmDetailsScreen
import app.solarma.ui.history.HistoryScreen
import app.solarma.ui.home.HomeScreen
import app.solarma.ui.settings.SettingsScreen

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
                    navController.navigate(Screen.AlarmDetails.withId(id))
                },
                onSettingsClick = {
                    navController.navigate(Screen.Settings.route)
                }
            )
        }
        
        composable(Screen.CreateAlarm.route) {
            CreateAlarmScreen(
                onBack = { navController.popBackStack() }
            )
        }
        
        composable(
            route = Screen.AlarmDetails.route,
            arguments = listOf(navArgument("id") { type = NavType.LongType })
        ) { backStackEntry ->
            val alarmId = backStackEntry.arguments?.getLong("id") ?: -1L
            AlarmDetailsScreen(
                alarmId = alarmId,
                onBack = { navController.popBackStack() },
                onViewHistory = { navController.navigate(Screen.History.route) }
            )
        }
        
        composable(Screen.History.route) {
            HistoryScreen(
                onBack = { navController.popBackStack() }
            )
        }
        
        composable(Screen.Settings.route) {
            SettingsScreen(
                onBack = { navController.popBackStack() }
            )
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
    object History : Screen("history")
    object Settings : Screen("settings")
}
