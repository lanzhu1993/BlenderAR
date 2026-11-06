package com.blenderar.client.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.blenderar.client.ui.ar.ARScreen
import com.blenderar.client.ui.connect.ConnectScreen

/** 路由常量 */
object Routes {
    const val CONNECT = "connect"
    const val AR = "ar"
}

/**
 * 应用导航根:连接页 <-> AR 主页。
 */
@Composable
fun AppRoot() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.CONNECT) {
        composable(Routes.CONNECT) {
            ConnectScreen(
                onEnterAR = { navController.navigate(Routes.AR) { popUpTo(Routes.CONNECT) { inclusive = false } } },
            )
        }
        composable(Routes.AR) {
            ARScreen(
                onBack = { navController.popBackStack() },
            )
        }
    }
}
