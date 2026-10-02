package com.example.dianzicheng.ui

import androidx.compose.foundation.layout.fillMaxSize
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
    isPairingComplete: Boolean?,
    onPairingComplete: () -> Unit,
    onResetPairing: () -> Unit = { profileViewModel.resetPairing() }
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val items = listOf("测量", "历史", "我的")
    val routes = listOf("dashboard", "history", "profile")
    val icons = listOf(Icons.Default.Home, Icons.Default.DateRange, Icons.Default.Person)

    //触发首次弹窗操作
    FirstRunProfileOnboarding(
        onSave = { name, sex, heightCm, birthDateEpochMs ->
            profileViewModel.upsertPrimaryMember(name, sex, heightCm, birthDateEpochMs)
        }
    )

    if (isPairingComplete == null) {
        // DataStore 异步读取中，渲染空背景，杜绝因初值 false 瞬时挂载 PairingScreen 并误触发 startPairingScan()
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {}
        return
    }

    if (!isPairingComplete) {
        PairingScreen(
            viewModel = scaleViewModel,
            onPairingComplete = onPairingComplete,
            onNavigateBack = null
        )
        return
    }

    val isSubScreen = currentRoute == "pairing" || currentRoute?.startsWith("detail/") == true

    Scaffold(
        bottomBar = {
            if (!isSubScreen) {
                NavigationBar {
                    items.forEachIndexed { index, item ->
                        val route = routes[index]
                        val isSelected = currentRoute == route
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
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "dashboard",
            modifier = Modifier.padding(innerPadding)
        ) {
            composable("dashboard") {
                DashboardScreen(
                    viewModel = scaleViewModel,
                    onNavigateToPairing = { navController.navigate("pairing") }
                )
            }
            composable("history") { 
                HistoryScreen(
                    viewModel = historyViewModel,
                    onNavigateToDetail = { id ->
                        navController.navigate("detail/$id")
                    }
                )
            }
            composable("profile") {
                ProfileScreen(
                    viewModel = profileViewModel,
                    onNavigateToPairing = { navController.navigate("pairing") }
                )
            }
            composable("pairing") {
                PairingScreen(
                    viewModel = scaleViewModel,
                    onPairingComplete = { navController.popBackStack() },
                    onNavigateBack = { navController.popBackStack() }
                )
            }
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
