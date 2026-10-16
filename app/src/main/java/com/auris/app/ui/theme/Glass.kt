package com.auris.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * The glass language, shared by every screen.
 *
 * The auth gate defined the app's look — OLED black, frosted panes with a lit top rim,
 * a brand sweep behind the content. These are the primitives for it, so the rest of the
 * app inherits that treatment from one place instead of re-deriving it per screen.
 *
 * ### Why layered translucency instead of a blur
 * A real backdrop blur is `RenderEffect`, which needs API 31. The app's floor is 26, so
 * a blur would silently no-op on a chunk of devices and the look would fork. Stacking
 * translucent fills over the black base gives the same read on every API.
 */

/**
 * Turns any surface into a frosted pane: translucent fill that dims top-to-bottom, and a
 * hairline rim brightest along the top edge. This is the single glass recipe — the auth
 * card, the nav pill and list rows are all this modifier with different shapes.
 */
fun Modifier.glassPanel(
    shape: Shape = RoundedCornerShape(AurisRadii.sheet),
    fillTop: Color = GlassFill,
    fillBottom: Color = GlassFillSubtle,
    edgeTop: Color = GlassEdge,
    edgeBottom: Color = GlassEdgeSubtle,
): Modifier = this
    .clip(shape)
    .background(Brush.verticalGradient(listOf(fillTop, fillBottom)))
    .border(1.dp, Brush.verticalGradient(listOf(edgeTop, edgeBottom)), shape)

/** A [glassPanel] wrapping a column of content at the app's standard card padding. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(AurisRadii.sheet),
    contentPadding: PaddingValues = PaddingValues(horizontal = 18.dp, vertical = 20.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .glassPanel(shape)
            .padding(contentPadding),
        content = content,
    )
}

/**
 * OLED base with the brand sweep behind the content — the auth gate's backdrop, offered
 * app-wide so screens share one background instead of each painting their own.
 */
@Composable
fun AurisBackdrop(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AurisBackground),
    ) {
        BrandGlow(Modifier.fillMaxSize())
        content()
    }
}

/**
 * The brand sweep drawn over the OLED base: indigo through transparent to purple.
 *
 * Split out of [AurisBackdrop] so the artwork-driven screens can lay their colour wash over the
 * same base instead of repainting their own opaque background. That is what keeps a screen with a
 * loud cover reading as the auth gate (OLED plus a hint of brand) rather than a different app.
 */
@Composable
fun BrandGlow(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(
            Brush.linearGradient(
                colors = listOf(
                    BrandIndigo.copy(alpha = 0.30f),
                    AurisBackground.copy(alpha = 0f),
                    BrandPurple.copy(alpha = 0.16f),
                ),
            ),
        ),
    )
}
