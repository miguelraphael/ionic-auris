package com.auris.app.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/** Shared timing; Compose animations follow Android's animator duration scale. */
object AurisMotion {
    const val Quick = 160
    const val Standard = 260
    const val Emphasized = 340
    val Ease = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)
}

/** Draw-only feedback keeps the touch target and surrounding layout still. */
@Composable
fun Modifier.pressFeedback(interactionSource: InteractionSource): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale = animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = tween(AurisMotion.Quick, easing = AurisMotion.Ease),
        label = "pressFeedback",
    )
    return graphicsLayer { scaleX = scale.value; scaleY = scale.value }
}
