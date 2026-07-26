package com.tryptz.neuron.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.tryptz.neuron.code.sandbox.CodeExecutor
import com.tryptz.neuron.ui.animation.MotionTokens
import com.tryptz.neuron.ui.chat.ChatScreen
import com.tryptz.neuron.ui.editor.CodeEditorScreen
import com.tryptz.neuron.ui.modelmanager.ModelManagerScreen

/** SavedStateHandle key for code handed back from the editor to the chat input. */
private const val KEY_EDITOR_CODE = "editor_code"

object Routes {
    const val CHAT = "chat"
    const val MODEL_MANAGER = "model_manager"
    const val EDITOR = "editor/{code}/{language}"

    // Uri.encode (not URLEncoder) — Navigation Compose Uri-decodes route args
    // exactly once, and URLEncoder's "+" for spaces survives that decode as a
    // literal plus sign, corrupting the code.
    fun editor(code: String, language: String) = "editor/${android.net.Uri.encode(code)}/$language"
}

@Composable
fun NeuronNavHost(codeExecutor: CodeExecutor) {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Routes.CHAT,
        enterTransition = {
            slideInHorizontally(
                initialOffsetX = { it },
                animationSpec = tween(MotionTokens.Easing.EMPHASIZED_DURATION, easing = MotionTokens.Easing.EMPHASIZED)
            ) + fadeIn(animationSpec = tween(300))
        },
        exitTransition = {
            slideOutHorizontally(
                targetOffsetX = { -it / 3 },
                animationSpec = tween(MotionTokens.Easing.EMPHASIZED_DURATION, easing = MotionTokens.Easing.EMPHASIZED)
            ) + fadeOut(animationSpec = tween(200))
        },
        popEnterTransition = {
            slideInHorizontally(
                initialOffsetX = { -it / 3 },
                animationSpec = tween(MotionTokens.Easing.EMPHASIZED_DURATION, easing = MotionTokens.Easing.EMPHASIZED)
            ) + fadeIn(animationSpec = tween(300))
        },
        popExitTransition = {
            slideOutHorizontally(
                targetOffsetX = { it },
                animationSpec = tween(MotionTokens.Easing.EMPHASIZED_DURATION, easing = MotionTokens.Easing.EMPHASIZED)
            ) + fadeOut(animationSpec = tween(200))
        }
    ) {
        composable(Routes.CHAT) { entry ->
            val editorCode by entry.savedStateHandle
                .getStateFlow<String?>(KEY_EDITOR_CODE, null)
                .collectAsState()
            ChatScreen(
                onNavigateToModelManager = { navController.navigate(Routes.MODEL_MANAGER) },
                onNavigateToEditor = { code, lang -> navController.navigate(Routes.editor(code, lang)) },
                pendingEditorCode = editorCode,
                onPendingEditorCodeConsumed = { entry.savedStateHandle[KEY_EDITOR_CODE] = null }
            )
        }

        composable(Routes.MODEL_MANAGER) {
            ModelManagerScreen(onBack = { navController.popBackStack() })
        }

        composable(
            route = Routes.EDITOR,
            arguments = listOf(
                navArgument("code") { type = NavType.StringType },
                navArgument("language") { type = NavType.StringType; defaultValue = "js" }
            )
        ) { backStackEntry ->
            // Navigation Compose already Uri-decoded the argument once — a
            // second URLDecoder.decode crashed on any code containing '%'.
            val code = backStackEntry.arguments?.getString("code") ?: ""
            val language = backStackEntry.arguments?.getString("language") ?: "js"

            CodeEditorScreen(
                initialCode = code,
                initialLanguage = language,
                codeExecutor = codeExecutor,
                onSendToChat = { edited ->
                    // Hand the edited code back to the chat input via the
                    // previous back-stack entry (the editor pops itself after).
                    navController.previousBackStackEntry
                        ?.savedStateHandle
                        ?.set(KEY_EDITOR_CODE, "```$language\n$edited\n```\n")
                },
                onBack = { navController.popBackStack() }
            )
        }
    }
}
