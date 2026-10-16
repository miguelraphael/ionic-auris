package com.auris.app.ui.auth

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.auris.app.ui.theme.AurisAccent
import com.auris.app.ui.theme.AurisBackground
import com.auris.app.ui.theme.AurisLogo
import com.auris.app.ui.theme.AurisOnSurface
import com.auris.app.ui.theme.AurisOnSurfaceVariant
import com.auris.app.ui.theme.AurisRadii
import com.auris.app.ui.theme.AurisTrack
import com.auris.app.ui.theme.BrandIndigo
import com.auris.app.ui.theme.BrandPurple

// ---- Glass tokens -----------------------------------------------------------------
// Aurora fills read as frosted panes on the OLED base without a real backdrop blur,
// which only exists from API 31 and would silently no-op on the app's API 26 floor.

private val GlassCardTop = Color.White.copy(alpha = 0.10f)
private val GlassCardBottom = Color.White.copy(alpha = 0.03f)
private val GlassCardEdgeTop = Color.White.copy(alpha = 0.22f)
private val GlassCardEdgeBottom = Color.White.copy(alpha = 0.05f)
private val GlassFieldFill = Color.White.copy(alpha = 0.06f)
private val GlassFieldEdge = Color.White.copy(alpha = 0.10f)
private val GhostEdge = Color.White.copy(alpha = 0.16f)

/**
 * Mandatory sign-in gate: the library syncs per user, so there is no
 * anonymous mode and no way past this screen without a session.
 */
@Composable
fun AuthScreen(
    state: AuthUiState,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onToggleMode: () -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
    onDemoAccount: () -> Unit = {},
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AurisBackground),
    ) {
        // Brand glow: a soft indigo-to-purple sweep behind the glass, so the card
        // has something to sit on top of rather than floating on flat black.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            BrandIndigo.copy(alpha = 0.30f),
                            AurisBackground.copy(alpha = 0f),
                            BrandPurple.copy(alpha = 0.16f),
                        ),
                    ),
                ),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            AurisLogo(
                includeWordmark = true,
                size = 148.dp,
                background = AurisBackground,
            )
            Spacer(Modifier.height(14.dp))
            Text(
                text = if (state.isRegisterMode) {
                    "Create an account to sync your library"
                } else {
                    "Sign in to sync your library"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = AurisOnSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(22.dp))

            GlassCard {
                ModeToggle(
                    isRegisterMode = state.isRegisterMode,
                    onToggle = onToggleMode,
                    enabled = !state.isLoading,
                )

                Spacer(Modifier.height(16.dp))

                AuthField(
                    value = state.email,
                    onValueChange = onEmailChange,
                    hint = "Email",
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Email,
                        imeAction = ImeAction.Next,
                    ),
                    enabled = !state.isLoading,
                )

                Spacer(Modifier.height(12.dp))

                var passwordVisible by remember { mutableStateOf(false) }
                AuthField(
                    value = state.password,
                    onValueChange = onPasswordChange,
                    hint = "Password",
                    visualTransformation = if (passwordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { onSubmit() }),
                    enabled = !state.isLoading,
                    trailing = {
                        Icon(
                            imageVector = if (passwordVisible) {
                                Icons.Filled.VisibilityOff
                            } else {
                                Icons.Filled.Visibility
                            },
                            contentDescription = if (passwordVisible) "Hide password" else "Show password",
                            tint = AurisOnSurfaceVariant,
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .clickable(
                                    indication = null,
                                    interactionSource = remember { MutableInteractionSource() },
                                    onClick = { passwordVisible = !passwordVisible },
                                ),
                        )
                    },
                )

                if (state.error != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = state.error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                if (state.notice != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = state.notice,
                        style = MaterialTheme.typography.bodyMedium,
                        color = AurisAccent,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Spacer(Modifier.height(20.dp))

                PrimaryButton(
                    label = if (state.isRegisterMode) "Create account" else "Sign in",
                    isLoading = state.isLoading,
                    onClick = onSubmit,
                )

                Spacer(Modifier.height(12.dp))

                GhostButton(
                    text = "Use demo account",
                    enabled = !state.isLoading,
                    onClick = onDemoAccount,
                )

                Spacer(Modifier.height(8.dp))

                Text(
                    text = "Prefills placeholder credentials for a UI preview — nothing is sent until you sign in.",
                    style = MaterialTheme.typography.labelMedium,
                    color = AurisOnSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * Frosted pane the auth controls sit on: a translucent fill, a brighter top edge
 * that falls away toward the bottom, and rounded corners. The linear-gradient
 * border is what sells the "lit from above" glass read.
 */
@Composable
private fun GlassCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(AurisRadii.sheet)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                Brush.verticalGradient(listOf(GlassCardTop, GlassCardBottom)),
            )
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(listOf(GlassCardEdgeTop, GlassCardEdgeBottom)),
                shape = shape,
            )
            .padding(horizontal = 18.dp, vertical = 20.dp),
        content = content,
    )
}

/** Primary call to action, filled with the indigo-to-purple brand sweep. */
@Composable
private fun PrimaryButton(
    label: String,
    isLoading: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AurisRadii.button))
            .background(
                if (isLoading) {
                    Brush.horizontalGradient(listOf(AurisTrack, AurisTrack))
                } else {
                    Brush.horizontalGradient(listOf(BrandIndigo, BrandPurple))
                },
            )
            .clickable(
                enabled = !isLoading,
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick,
            )
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.5.dp,
                color = AurisAccent,
            )
        } else {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
            )
        }
    }
}

/** Secondary, quiet action: hairline outline, no fill. */
@Composable
private fun GhostButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(AurisRadii.button)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(width = 1.dp, color = GhostEdge, shape = shape)
            .clickable(
                enabled = enabled,
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick,
            )
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = AurisOnSurfaceVariant,
        )
    }
}

@Composable
private fun ModeToggle(
    isRegisterMode: Boolean,
    onToggle: () -> Unit,
    enabled: Boolean,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ModeChip(
            label = "Sign in",
            selected = !isRegisterMode,
            onClick = { if (isRegisterMode) onToggle() },
            enabled = enabled,
        )
        ModeChip(
            label = "Create account",
            selected = isRegisterMode,
            onClick = { if (!isRegisterMode) onToggle() },
            enabled = enabled,
        )
    }
}

@Composable
private fun ModeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean,
) {
    Surface(
        shape = RoundedCornerShape(AurisRadii.chip),
        color = if (selected) AurisAccent.copy(alpha = 0.18f) else AurisTrack,
        modifier = Modifier.clickable(
            enabled = enabled,
            indication = null,
            interactionSource = remember { MutableInteractionSource() },
            onClick = onClick,
        ),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) AurisAccent else AurisOnSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
        )
    }
}

@Composable
private fun AuthField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    keyboardOptions: KeyboardOptions,
    enabled: Boolean,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    trailing: @Composable (() -> Unit)? = null,
) {
    // Cursor pinned to the end; see ExtensionSearchField for the reversal this avoids.
    var field by remember(value) {
        mutableStateOf(TextFieldValue(value, TextRange(value.length)))
    }

    Surface(
        shape = RoundedCornerShape(AurisRadii.button),
        color = GlassFieldFill,
        border = BorderStroke(1.dp, GlassFieldEdge),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                if (field.text.isEmpty()) {
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.bodyLarge,
                        color = AurisOnSurfaceVariant,
                    )
                }
                BasicTextField(
                    value = field,
                    onValueChange = { next ->
                        field = next
                        onValueChange(next.text)
                    },
                    singleLine = true,
                    enabled = enabled,
                    textStyle = TextStyle(
                        color = AurisOnSurface,
                        fontSize = MaterialTheme.typography.bodyLarge.fontSize,
                    ),
                    cursorBrush = SolidColor(AurisAccent),
                    visualTransformation = visualTransformation,
                    keyboardOptions = keyboardOptions,
                    keyboardActions = keyboardActions,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (trailing != null) {
                Spacer(Modifier.width(10.dp))
                trailing()
            }
        }
    }
}
