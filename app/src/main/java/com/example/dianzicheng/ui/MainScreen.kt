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

@Composable
fun MainScreen(
    scaleViewModel: ScaleViewModel,
    historyViewModel: HistoryViewModel,
    profileViewModel: ProfileViewModel,
    isPairingComplete: Boolean,
    onPairingComplete: () -> Unit
) {
    val navController = rememberNavController()
    var selectedItem by remember { mutableIntStateOf(0) }
    val items = listOf("测量", "历史", "我的")
    val icons = listOf(Icons.Default.Home, Icons.Default.DateRange, Icons.Default.Person)

    if (!isPairingComplete) {
        PairingScreen(scaleViewModel, onPairingComplete)
        return
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                items.forEachIndexed { index, item ->
                    NavigationBarItem(
                        icon = { Icon(icons[index], contentDescription = item) },
                        label = { Text(item) },
                        selected = selectedItem == index,
                        onClick = {
                            selectedItem = index
                            when (index) {
                                0 -> navController.navigate("dashboard") {
                                    popUpTo(navController.graph.startDestinationId)
                                    launchSingleTop = true
                                }
                                1 -> navController.navigate("history") {
                                    launchSingleTop = true
                                }
                                2 -> navController.navigate("profile") {
                                    launchSingleTop = true
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
