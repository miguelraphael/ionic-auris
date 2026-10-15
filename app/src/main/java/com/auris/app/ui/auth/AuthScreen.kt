package com.auris.app.ui.auth

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.foundation.shape.CircleShape
import com.auris.app.ui.theme.AurisAccent
import com.auris.app.ui.theme.AurisBackground
import com.auris.app.ui.theme.AurisOnSurface
import com.auris.app.ui.theme.AurisOnSurfaceVariant
import com.auris.app.ui.theme.AurisRadii
import com.auris.app.ui.theme.AurisSurface
import com.auris.app.ui.theme.AurisTrack

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
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Auris",
            style = MaterialTheme.typography.displayLarge,
            color = AurisOnSurface,
        )
        Spacer(Modifier.height(6.dp))
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

        Spacer(Modifier.height(28.dp))

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

        Surface(
            shape = RoundedCornerShape(AurisRadii.button),
            color = if (state.isLoading) AurisTrack else AurisAccent,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    enabled = !state.isLoading,
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = onSubmit,
                ),
        ) {
            Box(
                modifier = Modifier.padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (state.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.5.dp,
                        color = AurisAccent,
                    )
                } else {
                    Text(
                        text = if (state.isRegisterMode) "Create account" else "Sign in",
                        style = MaterialTheme.typography.labelLarge,
                        color = AurisBackground,
                    )
                }
            }
        }
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
        color = AurisSurface,
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
