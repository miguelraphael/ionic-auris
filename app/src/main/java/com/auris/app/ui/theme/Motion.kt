package com.auris.app.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset

/**
 * Shared motion tokens tuned for fluid 90Hz/120Hz display refresh rates.
 * Follows Material 3 motion principles with natural acceleration and physical springs.
 */
object AurisMotion {
    const val Quick = 200
    const val Standard = 300
    const val Emphasized = 400

    /** Standard Emphasized Decelerate easing: smooth onset with natural glide. */
    val Ease = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)
    val Decelerate = CubicBezierEasing(0.0f, 0.0f, 0.2f, 1.0f)
    val Accelerate = CubicBezierEasing(0.4f, 0.0f, 1.0f, 1.0f)

    /** Framerate-independent spring for hierarchical screen slides (buttery on 90Hz/120Hz). */
    val SlideSpring = spring<IntOffset>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )
}

/** Draw-only tactile feedback: spring physics provides organic, instantaneous feel. */
@Composable
fun Modifier.pressFeedback(interactionSource: InteractionSource): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale = animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "pressFeedback",
    )
    return graphicsLayer { scaleX = scale.value; scaleY = scale.value }
}
