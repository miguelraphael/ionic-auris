package com.auris.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.unit.Dp
import com.auris.app.ui.navigation.TopLevelDestination
import com.auris.app.ui.theme.AurisOnSurface
import com.auris.app.ui.theme.AurisOnSurfaceVariant
import com.auris.app.ui.theme.AurisSurfaceOpaque
import com.auris.app.ui.theme.NavPillShape

/**
 * Floating, pill-shaped bottom navigation.
 *
 * ### Geometry (measured from `UI/music-player-UI.jpg`, 1080x2400 @ 2.625x)
 *  - Horizontal inset 45dp, bottom inset 28dp (above the gesture bar).
 *  - Pill height 50dp, fully rounded ends.
 *  - Items evenly distributed; the active item is conveyed by colour + scale only, with
 *    no indicator pill, matching the screenshot's flat treatment.
 *
 * ### Why a Modifier param and no NavigationBar
 * A standard `NavigationBar` is a full-width bottom block and cannot float. This composable
 * is drawn on top of the NavHost so screens can extend content behind it.
 */
@Composable
fun FloatingNavigationBar(
    destinations: List<TopLevelDestination>,
    currentRoute: String?,
    onNavigate: (TopLevelDestination) -> Unit,
    modifier: Modifier = Modifier,
    maxWidth: Dp = 420.dp,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 45.dp)
                .padding(bottom = 28.dp)
                .widthIn(max = maxWidth)
                .height(50.dp)
                // Opaque, not glass. The pill floats over album art and shelf cards, and the
                // translucent fill let bright covers through until the icons disappeared.
                .clip(NavPillShape)
                .background(AurisSurfaceOpaque)
                .border(
                    width = 1.dp,
                    color = AurisOnSurfaceVariant.copy(alpha = 0.18f),
                    shape = NavPillShape,
                )
                .selectableGroup(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            destinations.forEach { destination ->
                FloatingNavItem(
                    destination = destination,
                    selected = currentRoute == destination.route,
                    onClick = { onNavigate(destination) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun FloatingNavItem(
    destination: TopLevelDestination,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(destination.labelRes)
    val tint by animateColorAsState(
        targetValue = if (selected) AurisOnSurface else AurisOnSurfaceVariant,
        label = "navItemTint",
    )
    // The active icon is slightly larger — the screenshot's active glyph reads heavier.
    val scale by animateFloatAsState(
        targetValue = if (selected) 1.12f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "navItemScale",
    )
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(50.dp)
            .selectable(
                selected = selected,
                onClick = onClick,
                interactionSource = interactionSource,
                indication = null,
                role = Role.Tab,
            )
            .semantics {
                this.role = Role.Tab
                this.selected = selected
                contentDescription = label
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.scale(scale),
        ) {
            Icon(
                imageVector = destination.icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(24.dp),
            )
            // Labels are hidden: the reference pill is icon-only at this width.
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = Color.Transparent,
                modifier = Modifier.height(0.dp),
            )
        }
    }
}

/**
 * Compact icon button used in the player's secondary tier (device picker, sleep timer,
 * queue, cast). 40dp touch target, 24dp glyph, no background.
 */
@Composable
fun PlayerGhostIcon(
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = AurisOnSurfaceVariant,
    enabled: Boolean = true,
) {
    val scale by animateFloatAsState(
        targetValue = if (enabled) 1f else 0.9f,
        label = "ghostIconScale",
    )
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(
                enabled = enabled,
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick,
            )
            .scale(scale),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) tint else tint.copy(alpha = 0.4f),
            modifier = Modifier.size(22.dp),
        )
    }
}