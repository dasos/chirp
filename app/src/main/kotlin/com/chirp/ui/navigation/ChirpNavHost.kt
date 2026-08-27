package com.chirp.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.chirp.ui.conversation.ConversationScreen
import com.chirp.ui.home.HomeScreen
import com.chirp.ui.settings.SettingsScreen

object Routes {
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val CONVERSATION = "conversation"
    const val ARG_CONVERSATION_ID = "conversationId"

    /** Route to a conversation; pass null for a brand-new one (-1 sentinel). */
    fun conversation(id: Long?): String = "$CONVERSATION?$ARG_CONVERSATION_ID=${id ?: -1L}"
}

@Composable
fun ChirpNavHost(
    navController: NavHostController = rememberNavController(),
    startDestination: String = Routes.HOME,
) {
    NavHost(navController = navController, startDestination = startDestination) {
        composable(Routes.HOME) {
            HomeScreen(
                onNewConversation = { navController.navigate(Routes.conversation(null)) },
                onOpenConversation = { id -> navController.navigate(Routes.conversation(id)) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(
            route = "${Routes.CONVERSATION}?${Routes.ARG_CONVERSATION_ID}={${Routes.ARG_CONVERSATION_ID}}",
            arguments = listOf(
                navArgument(Routes.ARG_CONVERSATION_ID) {
                    type = NavType.LongType
                    defaultValue = -1L
                },
            ),
        ) {
            ConversationScreen(
                onBack = { navController.popBackStack() },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
