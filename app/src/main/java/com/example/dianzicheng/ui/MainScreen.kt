package com.example.dianzicheng.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavType
import androidx.navigation.navArgument

import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.NavGraph.Companion.findStartDestination

@Composable
fun MainScreen(
    scaleViewModel: ScaleViewModel,
    historyViewModel: HistoryViewModel,
    profileViewModel: ProfileViewModel,
    isPairingComplete: Boolean,
    onPairingComplete: () -> Unit
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val items = listOf("测量", "历史", "我的")
    val routes = listOf("dashboard", "history", "profile")
    val icons = listOf(Icons.Default.Home, Icons.Default.DateRange, Icons.Default.Person)

    if (!isPairingComplete) {
        PairingScreen(scaleViewModel, onPairingComplete)
        return
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                items.forEachIndexed { index, item ->
                    val route = routes[index]
                    val isSelected = when (route) {
                        "history" -> currentRoute == "history" || currentRoute?.startsWith("detail/") == true
                        else -> currentRoute == route
                    }
                    NavigationBarItem(
                        icon = { Icon(icons[index], contentDescription = item) },
                        label = { Text(item) },
                        selected = isSelected,
                        onClick = {
                            if (currentRoute != route) {
                                navController.navigate(route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "dashboard",
            modifier = Modifier.padding(innerPadding)
        ) {
            composable("dashboard") { DashboardScreen(scaleViewModel) }
            composable("history") { 
                HistoryScreen(
                    viewModel = historyViewModel,
                    onNavigateToDetail = { id ->
                        navController.navigate("detail/$id")
                    }
                )
            }
            composable("profile") { ProfileScreen(profileViewModel) }
            composable(
                route = "detail/{id}",
                arguments = listOf(navArgument("id") { type = NavType.StringType })
            ) { backStackEntry ->
                val id = backStackEntry.arguments?.getString("id") ?: ""
                MeasurementDetailScreen(
                    measurementId = id,
                    viewModel = historyViewModel,
                    onNavigateBack = { navController.popBackStack() }
                )
            }
        }
    }
}
