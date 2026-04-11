package com.tryptz.neuron.ui.animation

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.ui.unit.IntOffset

/**
 * Centralized animation design language for the entire app.
 * Standardizes all curves, durations, and spring configs to ensure
 * a cohesive, flagship-tier motion feel on the 165Hz LTPO display.
 *
 * Prefer springs over tweens for natural, interruptible motion.
 */
object MotionTokens {

    // ── Spring Configurations ──

    object Spring {
        /** Settings panels sliding in, tooltips appearing */
        val GENTLE = spring<Float>(stiffness = 200f, dampingRatio = 0.7f)
        val GENTLE_INT = spring<Int>(stiffness = 200f, dampingRatio = 0.7f)
        val GENTLE_OFFSET = spring<IntOffset>(stiffness = 200f, dampingRatio = 0.7f)

        /** Button presses, toggle switches, selection states */
        val RESPONSIVE = spring<Float>(stiffness = 400f, dampingRatio = 0.8f)
        val RESPONSIVE_INT = spring<Int>(stiffness = 400f, dampingRatio = 0.8f)

        /** Bottom sheet drag release, swipe-to-dismiss */
        val SNAPPY = spring<Float>(stiffness = 800f, dampingRatio = 0.6f)
        val SNAPPY_OFFSET = spring<IntOffset>(stiffness = 800f, dampingRatio = 0.6f)

        /** Download complete celebration, achievement unlocks */
        val BOUNCY = spring<Float>(stiffness = 300f, dampingRatio = 0.4f)
    }

    // ── Tween Easing Curves ──

    object Easing {
        /** Screen transitions, 500ms */
        val EMPHASIZED = CubicBezierEasing(0.2f, 0f, 0f, 1f)
        const val EMPHASIZED_DURATION = 500

        /** Entering elements, 400ms */
        val EMPHASIZED_DECELERATE = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
        const val ENTER_DURATION = 400

        /** Exiting elements, 200ms */
        val EMPHASIZED_ACCELERATE = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
        const val EXIT_DURATION = 200
    }

    // ── Stagger Delays ──

    object Stagger {
        const val ITEM_DELAY_MS = 50
        const val CASCADE_DELAY_MS = 30
    }

    // ── Pre-built Enter/Exit transitions ──

    fun messageEnter(index: Int = 0): EnterTransition =
        slideInVertically(
            animationSpec = tween(
                durationMillis = Easing.ENTER_DURATION,
                delayMillis = index * Stagger.ITEM_DELAY_MS,
                easing = Easing.EMPHASIZED_DECELERATE
            ),
            initialOffsetY = { it / 3 }
        ) + fadeIn(
            animationSpec = tween(
                durationMillis = Easing.ENTER_DURATION,
                delayMillis = index * Stagger.ITEM_DELAY_MS,
                easing = Easing.EMPHASIZED_DECELERATE
            )
        )

    fun messageExit(): ExitTransition =
        slideOutVertically(
            animationSpec = tween(
                durationMillis = Easing.EXIT_DURATION,
                easing = Easing.EMPHASIZED_ACCELERATE
            ),
            targetOffsetY = { -it / 4 }
        ) + fadeOut(
            animationSpec = tween(
                durationMillis = Easing.EXIT_DURATION,
                easing = Easing.EMPHASIZED_ACCELERATE
            )
        )

    fun settingsExpand(): EnterTransition =
        expandVertically(
            animationSpec = spring(
                stiffness = 200f,
                dampingRatio = 0.7f
            )
        ) + fadeIn(
            animationSpec = tween(
                durationMillis = 300,
                easing = Easing.EMPHASIZED_DECELERATE
            )
        )

    fun settingsCollapse(): ExitTransition =
        shrinkVertically(
            animationSpec = spring(
                stiffness = 400f,
                dampingRatio = 0.8f
            )
        ) + fadeOut(
            animationSpec = tween(
                durationMillis = Easing.EXIT_DURATION,
                easing = Easing.EMPHASIZED_ACCELERATE
            )
        )

    fun panelSlideIn(): EnterTransition =
        slideInHorizontally(
            animationSpec = spring(
                stiffness = 800f,
                dampingRatio = 0.6f
            ),
            initialOffsetX = { it }
        )

    fun panelSlideOut(): ExitTransition =
        slideOutHorizontally(
            animationSpec = spring(
                stiffness = 800f,
                dampingRatio = 0.6f
            ),
            targetOffsetX = { it }
        )
}
