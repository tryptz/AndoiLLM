package com.tryptz.neuron.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
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
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface NeuronNavEntryPoint {
    fun codeExecutor(): CodeExecutor
}

object Routes {
    const val CHAT = "chat"
    const val MODEL_MANAGER = "model_manager"
    const val EDITOR = "editor/{code}/{language}"

    fun editor(code: String, language: String) = "editor/${java.net.URLEncoder.encode(code, "UTF-8")}/$language"
}

@Composable
fun NeuronNavHost() {
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
        composable(Routes.CHAT) {
            ChatScreen(
                onNavigateToModelManager = { navController.navigate(Routes.MODEL_MANAGER) },
                onNavigateToEditor = { code, lang -> navController.navigate(Routes.editor(code, lang)) }
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
            val code = java.net.URLDecoder.decode(
                backStackEntry.arguments?.getString("code") ?: "", "UTF-8"
            )
            val language = backStackEntry.arguments?.getString("language") ?: "js"
            val context = LocalContext.current
            val codeExecutor = remember(context) {
                EntryPointAccessors
                    .fromApplication(context.applicationContext, NeuronNavEntryPoint::class.java)
                    .codeExecutor()
            }

            CodeEditorScreen(
                initialCode = code,
                initialLanguage = language,
                codeExecutor = codeExecutor,
                onSendToChat = { /* Would send back via shared ViewModel */ },
                onBack = { navController.popBackStack() }
            )
        }
    }
}
