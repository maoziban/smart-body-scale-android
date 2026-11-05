package com.example.dianzicheng.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

    val items = listOf("首页", "趋势", "我的")
    val routes = listOf("dashboard", "history", "profile")
    val icons = listOf(Icons.Default.Home, Icons.AutoMirrored.Filled.TrendingUp, Icons.Default.Person)

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

    val isSubScreen = currentRoute == "pairing" || currentRoute?.startsWith("detail/") == true || currentRoute == "records"

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (!isSubScreen) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 2.dp,
                    shadowElevation = 8.dp
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                    ) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                            thickness = 0.5.dp
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            horizontalArrangement = Arrangement.SpaceAround,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            items.forEachIndexed { index, item ->
                                val route = routes[index]
                                val isSelected = currentRoute == route
                                val primaryColor = MaterialTheme.colorScheme.primary
                                val unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                                val tintColor = if (isSelected) primaryColor else unselectedColor

                                val interactionSource = remember { MutableInteractionSource() }
                                val isPressed by interactionSource.collectIsPressedAsState()
                                val scale by animateFloatAsState(
                                    targetValue = if (isPressed) 0.92f else 1f,
                                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                                    label = "tab_scale"
                                )

                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .clickable(
                                            interactionSource = interactionSource,
                                            indication = null
                                        ) {
                                            if (currentRoute != route) {
                                                navController.navigate(route) {
                                                    popUpTo(navController.graph.findStartDestination().id) {
                                                        saveState = true
                                                    }
                                                    launchSingleTop = true
                                                    restoreState = true
                                                }
                                            }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center,
                                        modifier = Modifier.graphicsLayer {
                                            scaleX = scale
                                            scaleY = scale
                                        }
                                    ) {
                                        Icon(
                                            imageVector = icons[index],
                                            contentDescription = item,
                                            tint = tintColor,
                                            modifier = Modifier.size(24.dp)
                                        )
                                        Spacer(modifier = Modifier.height(3.dp))
                                        Text(
                                            text = item,
                                            fontSize = 11.5.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            color = tintColor
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "dashboard",
            modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding())
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
                    },
                    onNavigateToRecords = {
                        navController.navigate("records")
                    }
                )
            }
            composable("records") {
                HistoryRecordsScreen(
                    viewModel = historyViewModel,
                    onNavigateBack = { navController.popBackStack() },
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
