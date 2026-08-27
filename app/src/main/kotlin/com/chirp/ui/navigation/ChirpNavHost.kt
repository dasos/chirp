package com.chirp.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
    const val ARG_START_LISTENING = "startListening"

    /** Route to a conversation; pass null for a brand-new one (-1 sentinel). */
    fun conversation(id: Long?, startListening: Boolean = false): String =
        "$CONVERSATION?$ARG_CONVERSATION_ID=${id ?: -1L}&$ARG_START_LISTENING=$startListening"
}

@Composable
fun ChirpNavHost(
    navController: NavHostController = rememberNavController(),
    startDestination: String = Routes.HOME,
    startInSettings: Boolean = false,
) {
    NavHost(navController = navController, startDestination = startDestination) {
        composable(Routes.HOME) {
            HomeScreen(
                onNewConversation = { startListening ->
                    navController.navigate(Routes.conversation(null, startListening))
                },
                onOpenConversation = { id -> navController.navigate(Routes.conversation(id)) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(
            route = "${Routes.CONVERSATION}?${Routes.ARG_CONVERSATION_ID}={${Routes.ARG_CONVERSATION_ID}}&${Routes.ARG_START_LISTENING}={${Routes.ARG_START_LISTENING}}",
            arguments = listOf(
                navArgument(Routes.ARG_CONVERSATION_ID) {
                    type = NavType.LongType
                    defaultValue = -1L
                },
                navArgument(Routes.ARG_START_LISTENING) {
                    type = NavType.BoolType
                    defaultValue = false
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

    // When the app should open straight into Settings (e.g. after onboarding),
    // push it on top of Home so the back button has somewhere to return to.
    LaunchedEffect(startInSettings) {
        if (startInSettings) {
            navController.navigate(Routes.SETTINGS) {
                popUpTo(Routes.HOME) { inclusive = false }
            }
        }
    }
}
