package com.auris.app.ui.auth

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.auris.app.data.repository.AuthRepository
import com.auris.app.data.repository.SignUpOutcome
import io.github.jan.supabase.auth.exception.AuthErrorCode
import io.github.jan.supabase.auth.exception.AuthRestException
import io.github.jan.supabase.exceptions.RestException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal fun authFailureMessage(error: Throwable, registering: Boolean): String = when {
    error is AuthRestException && error.errorCode == AuthErrorCode.EmailNotConfirmed ->
        "Confirm your email before signing in."
    error is RestException && error.statusCode == 429 ->
        "Too many attempts. Please wait a few minutes and try again."
    error is RestException && !registering && error.statusCode in listOf(400, 401) ->
        "Sign-in failed. Check your email and password."
    error is LinkageError ->
        "Authentication could not start. Please install the latest app update."
    registering -> "Registration failed. Please try again later."
    else -> "Sign-in failed. Please try again later."
}

/** Everything the login screen renders. */
data class AuthUiState(
    val isRegisterMode: Boolean = false,
    val email: String = "",
    val password: String = "",
    val isLoading: Boolean = false,
    /** Submission-level failure: wrong password, unreachable server, rate limit. */
    val error: String? = null,
    /** Per-field validation, shown under the field rather than at the bottom of the card. */
    val emailError: String? = null,
    val passwordError: String? = null,
    /** Non-error notice, e.g. "confirm your email". Cleared on next submit. */
    val notice: String? = null,
)

/**
 * Sign-in / registration for the mandatory auth gate.
 *
 * The gate itself observes the session flow, so success needs no callback:
 * the repository persists the session and the UI layer routes away on its
 * own. This ViewModel only reports failure (and the confirm-email detour).
 */
class AuthViewModel(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(AuthUiState())
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    fun onEmailChange(email: String) {
        _state.value = _state.value.copy(email = email, emailError = null, error = null)
    }

    fun onPasswordChange(password: String) {
        _state.value = _state.value.copy(password = password, passwordError = null, error = null)
    }

    fun toggleMode() {
        val current = _state.value
        _state.value = current.copy(
            isRegisterMode = !current.isRegisterMode,
            error = null,
            emailError = null,
            passwordError = null,
            notice = null,
        )
    }

    /**
     * Clears the transient state when the gate becomes visible again.
     *
     * The gate ViewModel is Activity-scoped, so it outlives a successful sign-in. On success
     * [isLoading] is deliberately left on until the gate routes away — but signing out later
     * re-shows the *same* instance, and without this reset the button would still be spinning
     * with every field disabled, leaving no way to sign back in until the process is killed.
     */
    fun onGateShown() {
        _state.value = _state.value.copy(
            isLoading = false,
            email = "",
            password = "",
            error = null,
            emailError = null,
            passwordError = null,
            notice = null,
        )
    }

    fun submit() {
        val email = _state.value.email.trim()
        val password = _state.value.password

        // Field validation stays on the field. The single bottom error it replaces made the
        // user hunt for which input was wrong, and it survived edits to the other one.
        val emailError = when {
            email.isEmpty() -> "Enter your email address"
            !EMAIL_PATTERN.matches(email) -> "That email address doesn\u2019t look right"
            else -> null
        }
        val passwordError = when {
            password.isEmpty() -> "Enter your password"
            password.length < MIN_PASSWORD_LENGTH ->
                "Use at least $MIN_PASSWORD_LENGTH characters"
            else -> null
        }

        if (emailError != null || passwordError != null) {
            _state.value = _state.value.copy(
                emailError = emailError,
                passwordError = passwordError,
                error = null,
                notice = null,
            )
            return
        }

        _state.value = _state.value.copy(
            isLoading = true,
            error = null,
            emailError = null,
            passwordError = null,
            notice = null,
        )
        viewModelScope.launch {
            if (_state.value.isRegisterMode) {
                authRepository.signUp(email, password)
                    .onSuccess { outcome ->
                        _state.value = when (outcome) {
                            // The gate observes the session and proceeds by itself.
                            SignUpOutcome.SignedIn -> _state.value.copy(isLoading = false, password = "")
                            SignUpOutcome.ConfirmEmailRequired -> _state.value.copy(
                                isLoading = false,
                                isRegisterMode = false,
                                password = "",
                                notice = "Account created — confirm your email, then sign in.",
                            )
                        }
                    }
                    .onFailure { error ->
                        logFailure(error)
                        _state.value = _state.value.copy(
                            isLoading = false,
                            error = authFailureMessage(error, registering = true),
                        )
                    }
            } else {
                authRepository.signIn(email, password)
                    .onSuccess { _state.value = _state.value.copy(password = "") }
                    .onFailure { error ->
                        logFailure(error)
                        _state.value = _state.value.copy(
                            isLoading = false,
                            error = authFailureMessage(error, registering = false),
                        )
                    }
                // Success clears itself: the gate routes away on the new session.
                // The loading flag is intentionally left on so the button cannot
                // be double-tapped during the handover.
            }
        }
    }

    override fun onCleared() {
        _state.value = _state.value.copy(password = "")
        super.onCleared()
    }

    private fun logFailure(error: Throwable) {
        // Exception messages can contain request bodies or credentials. Record only types
        // and call sites so release failures can be retraced without logging account data.
        generateSequence(error) { it.cause }.take(8).forEach { cause ->
            Log.e("AurisAuth", cause.javaClass.name + "\n" + cause.stackTrace.joinToString("\n"))
        }
    }

    class Factory(
        private val authRepository: AuthRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AuthViewModel(authRepository) as T
    }

    companion object {
        const val MIN_PASSWORD_LENGTH = 6

        /**
         * Deliberately permissive: enough to catch a typo or a username in the email box,
         * without rejecting a valid address the exact RFC would allow. The server is the
         * authority on deliverability.
         */
        private val EMAIL_PATTERN = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")

        fun factory(authRepository: AuthRepository): ViewModelProvider.Factory =
            Factory(authRepository)
    }
}
