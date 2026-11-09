package com.auris.app.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.auris.app.ui.components.ErrorBanner
import com.auris.app.ui.components.SuccessBanner
import com.auris.app.ui.theme.AurisAccent
import com.auris.app.ui.theme.AurisBackdrop
import com.auris.app.ui.theme.AurisBackground
import com.auris.app.ui.theme.AurisLogo
import com.auris.app.ui.theme.AurisOnSurface
import com.auris.app.ui.theme.AurisOnSurfaceVariant
import com.auris.app.ui.theme.AurisOutline
import com.auris.app.ui.theme.AurisRadii
import com.auris.app.ui.theme.AurisSurfaceOpaque

/** Mandatory sign-in gate; authentication and account ownership remain in the ViewModel. */
@Composable
fun AuthScreen(
    state: AuthUiState,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onToggleMode: () -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AurisBackdrop(modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding()
                .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AurisLogo(includeWordmark = true, size = 112.dp, background = AurisBackground)
            Spacer(Modifier.height(16.dp))
            Text(
                text = if (state.isRegisterMode) "Create an account to sync your library" else "Sign in to sync your library",
                style = MaterialTheme.typography.bodyLarge,
                color = AurisOnSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 420.dp),
            )
            Spacer(Modifier.height(32.dp))
            Column(
                modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                ModeToggle(state.isRegisterMode, onToggleMode, !state.isLoading)
                AuthField(
                    value = state.email, onValueChange = onEmailChange, hint = "Email",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                    enabled = !state.isLoading, error = state.emailError,
                )
                var passwordVisible by remember { mutableStateOf(false) }
                AuthField(
                    value = state.password, onValueChange = onPasswordChange, hint = "Password",
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (!state.isLoading) onSubmit() }),
                    enabled = !state.isLoading, error = state.passwordError,
                    trailing = {
                        IconButton(
                            onClick = { passwordVisible = !passwordVisible },
                            enabled = !state.isLoading,
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(
                                if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (passwordVisible) "Hide password" else "Show password",
                                tint = AurisOnSurfaceVariant,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    },
                )
                state.error?.let { ErrorBanner(it) }
                state.notice?.let { SuccessBanner(it) }
                Button(
                    onClick = onSubmit, enabled = !state.isLoading,
                    shape = RoundedCornerShape(AurisRadii.button),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AurisAccent, contentColor = AurisBackground,
                        disabledContainerColor = AurisSurfaceOpaque, disabledContentColor = AurisOnSurfaceVariant,
                    ),
                ) {
                    if (state.isLoading) {
                        CircularProgressIndicator(Modifier.size(22.dp), color = AurisOnSurfaceVariant, strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                    }
                    Text(if (state.isRegisterMode) "Create account" else "Sign in", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Composable
private fun ModeToggle(isRegisterMode: Boolean, onToggle: () -> Unit, enabled: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(AurisRadii.chip))
            .background(AurisSurfaceOpaque).padding(4.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        listOf(false to "Sign in", true to "Create account").forEach { (register, label) ->
            val selected = register == isRegisterMode
            Box(
                modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (selected) MaterialTheme.colorScheme.surfaceContainerHighest else AurisSurfaceOpaque)
                    .selectable(selected = selected, enabled = enabled, role = Role.Tab,
                        onClick = { if (!selected) onToggle() }).padding(horizontal = 8.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = MaterialTheme.typography.labelLarge,
                    color = if (selected) AurisOnSurface else AurisOnSurfaceVariant, textAlign = TextAlign.Center)
            }
        }
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
    error: String? = null,
) {
    // Preserve the existing cursor-at-end behavior when ViewModel values change.
    var field by remember(value) { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    OutlinedTextField(
        value = field,
        onValueChange = { field = it; onValueChange(it.text) },
        label = { Text(hint) },
        singleLine = true, enabled = enabled, isError = error != null,
        visualTransformation = visualTransformation, keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions, trailingIcon = trailing,
        supportingText = if (error != null) { { Text(error) } } else null,
        shape = RoundedCornerShape(AurisRadii.button),
        modifier = Modifier.fillMaxWidth(),
        textStyle = MaterialTheme.typography.bodyLarge,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = AurisSurfaceOpaque, unfocusedContainerColor = AurisSurfaceOpaque,
            disabledContainerColor = AurisSurfaceOpaque, errorContainerColor = AurisSurfaceOpaque,
            focusedBorderColor = AurisOnSurfaceVariant, unfocusedBorderColor = AurisOutline,
            focusedLabelColor = AurisOnSurface, unfocusedLabelColor = AurisOnSurfaceVariant,
            cursorColor = AurisAccent,
        ),
    )
}
