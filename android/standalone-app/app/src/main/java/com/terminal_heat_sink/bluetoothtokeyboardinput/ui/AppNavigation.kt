package com.terminal_heat_sink.bluetoothtokeyboardinput.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.terminal_heat_sink.bluetoothtokeyboardinput.ble.ConnectionState
import com.terminal_heat_sink.bluetoothtokeyboardinput.ui.screens.DevicesScreen
import com.terminal_heat_sink.bluetoothtokeyboardinput.ui.screens.KeyboardScreen
import com.terminal_heat_sink.bluetoothtokeyboardinput.ui.screens.ProvisionScreen
import com.terminal_heat_sink.bluetoothtokeyboardinput.ui.screens.ScanScreen
import com.terminal_heat_sink.bluetoothtokeyboardinput.ui.screens.ScriptScreen
import com.terminal_heat_sink.bluetoothtokeyboardinput.ui.screens.SendScreen
import com.terminal_heat_sink.bluetoothtokeyboardinput.ui.screens.SettingsScreen
import com.terminal_heat_sink.bluetoothtokeyboardinput.ui.screens.TrackpadScreen
import com.terminal_heat_sink.bluetoothtokeyboardinput.ui.viewmodel.BleViewModel

sealed class Screen(val route: String, val label: String) {
    object Devices  : Screen("devices",  "Devices")
    object Scan     : Screen("scan",     "Scan")
    object Send     : Screen("send",     "Send")
    object Script   : Screen("script",   "Script")
    object Settings : Screen("settings", "Settings")
    object Provision: Screen("provision","Provision")
    object Keyboard : Screen("keyboard",  "Keyboard")
    object Trackpad : Screen("trackpad",  "Trackpad")
}

// bottomNavItems is built dynamically inside AppNavigation based on mouseEnabled state.

@Composable
fun AppNavigation(
    viewModel: BleViewModel,
    shareIntent: String? = null,
) {
    val navController = rememberNavController()
    val navBackStack by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStack?.destination?.route
    val connectionState by viewModel.connectionState.collectAsState()
    val mouseEnabled    by viewModel.mouseEnabled.collectAsState()

    // Trackpad tab is shown only when the firmware has mouse support enabled.
    val bottomNavItems = if (mouseEnabled)
        listOf(Screen.Send, Screen.Script, Screen.Trackpad, Screen.Keyboard, Screen.Settings)
    else
        listOf(Screen.Send, Screen.Script, Screen.Keyboard, Screen.Settings)

    // Return to Devices automatically if the ESP32 drops the connection unexpectedly
    // (e.g. powered off) while the user is on any connected screen.
    val connectedRoutes = setOf(
        Screen.Send.route, Screen.Script.route, Screen.Keyboard.route, Screen.Trackpad.route,
        Screen.Settings.route, Screen.Provision.route
    )
    LaunchedEffect(connectionState) {
        if (connectionState is ConnectionState.Disconnected && currentRoute in connectedRoutes) {
            navController.navigate(Screen.Devices.route) {
                popUpTo(0) { inclusive = true }
            }
        }
    }

    val showBottomNav = currentRoute in listOf(
        Screen.Send.route, Screen.Script.route, Screen.Keyboard.route, Screen.Trackpad.route,
        Screen.Settings.route, Screen.Provision.route
    )

    Scaffold(
        bottomBar = {
            if (showBottomNav) {
                NavigationBar {
                    bottomNavItems.forEach { screen ->
                        NavigationBarItem(
                            selected = currentRoute == screen.route,
                            onClick = {
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                when (screen) {
                                    Screen.Send     -> Icon(Icons.Default.Send,     contentDescription = null)
                                    Screen.Script   -> Icon(Icons.Default.Edit,     contentDescription = null)
                                    Screen.Trackpad -> Icon(Icons.Default.Mouse,    contentDescription = null)
                                    Screen.Keyboard -> Icon(Icons.Default.Keyboard, contentDescription = null)
                                    else            -> Icon(Icons.Default.Settings, contentDescription = null)
                                }
                            },
                            label = { Text(screen.label) }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Devices.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Screen.Devices.route) {
                DevicesScreen(
                    viewModel = viewModel,
                    onScanClick = { navController.navigate(Screen.Scan.route) },
                    onDeviceConnect = { navController.navigate(Screen.Send.route) },
                )
            }
            composable(Screen.Scan.route) {
                ScanScreen(
                    viewModel = viewModel,
                    onConnected = {
                        navController.navigate(Screen.Send.route) {
                            popUpTo(Screen.Devices.route)
                        }
                    },
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Screen.Send.route) {
                SendScreen(
                    viewModel = viewModel,
                    shareIntentText = shareIntent,
                    onDisconnect = {
                        viewModel.disconnect()
                        navController.navigate(Screen.Devices.route) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                )
            }
            composable(Screen.Script.route) {
                ScriptScreen(viewModel = viewModel)
            }
            composable(Screen.Settings.route) {
                SettingsScreen(
                    viewModel = viewModel,
                    onProvisionClick = { navController.navigate(Screen.Provision.route) },
                    onFactoryReset = {
                        navController.navigate(Screen.Devices.route) {
                            popUpTo(0) { inclusive = true }
                        }
                    },
                )
            }
            composable(Screen.Provision.route) {
                ProvisionScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() },
                    onProvisioned = {
                        navController.navigate(Screen.Devices.route) {
                            popUpTo(0) { inclusive = true }
                        }
                    },
                )
            }
            composable(Screen.Keyboard.route) {
                KeyboardScreen(viewModel = viewModel)
            }
            composable(Screen.Trackpad.route) {
                TrackpadScreen(viewModel = viewModel)
            }
        }
    }
}
