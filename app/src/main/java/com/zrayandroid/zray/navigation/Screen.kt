package com.zrayandroid.zray.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AltRoute
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    data object Home : Screen("home", "首页", Icons.Default.Home)
    data object Profiles : Screen("profiles", "配置", Icons.Default.ListAlt)
    data object Routing : Screen("routing", "路由", Icons.Default.AltRoute)
    data object Settings : Screen("settings", "设置", Icons.Default.Settings)
    data object AppList : Screen("app_list", "应用选择", Icons.Default.Apps)
}

val screens = listOf(Screen.Home, Screen.Profiles, Screen.Routing, Screen.Settings)

/** 根据路由获取标题 */
fun screenTitle(route: String?): String = when (route) {
    Screen.Home.route -> Screen.Home.label
    Screen.Profiles.route -> Screen.Profiles.label
    Screen.Routing.route -> Screen.Routing.label
    Screen.Settings.route -> Screen.Settings.label
    Screen.AppList.route -> Screen.AppList.label
    else -> ""
}
