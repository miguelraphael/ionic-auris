package com.auris.app.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.auris.app.data.repository.AuthRepository
import com.auris.app.data.repository.SignUpOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Everything the login screen renders. */
data class AuthUiState(
    val isRegisterMode: Boolean = false,
    val email: String = "",
    val password: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
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
        _state.value = _state.value.copy(email = email)
    }

    fun onPasswordChange(password: String) {
        _state.value = _state.value.copy(password = password)
    }

    fun toggleMode() {
        val current = _state.value
        _state.value = current.copy(
            isRegisterMode = !current.isRegisterMode,
            error = null,
            notice = null,
        )
    }

    /**
     * Fills the form with the placeholder demo credentials for a UI walkthrough.
     *
     * Deliberately not a sign-in: nothing is sent and no session is forged, so the
     * button cannot become a bypass. The user still presses Sign in, which runs the
     * exact same path as a hand-typed login. Switches to sign-in mode so the prefilled
     * pair matches the action the user is about to take.
     */
    fun useDemoAccount() {
        _state.value = _state.value.copy(
            isRegisterMode = false,
            email = DEMO_EMAIL,
            password = DEMO_PASSWORD,
            error = null,
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
        _state.value = _state.value.copy(isLoading = false, error = null, notice = null)
    }

    fun submit() {
        val email = _state.value.email.trim()
        val password = _state.value.password
        if (!email.contains("@")) {
            _state.value = _state.value.copy(error = "Enter a valid email address")
            return
        }
        if (password.length < MIN_PASSWORD_LENGTH) {
            _state.value = _state.value.copy(
                error = "Password needs at least $MIN_PASSWORD_LENGTH characters",
            )
            return
        }

        _state.value = _state.value.copy(isLoading = true, error = null, notice = null)
        viewModelScope.launch {
            if (_state.value.isRegisterMode) {
                authRepository.signUp(email, password)
                    .onSuccess { outcome ->
                        _state.value = when (outcome) {
                            // The gate observes the session and proceeds by itself.
                            SignUpOutcome.SignedIn -> _state.value.copy(isLoading = false)
                            SignUpOutcome.ConfirmEmailRequired -> _state.value.copy(
                                isLoading = false,
                                isRegisterMode = false,
                                password = "",
                                notice = "Account created — confirm your email, then sign in.",
                            )
                        }
                    }
                    .onFailure { error ->
                        _state.value = _state.value.copy(
                            isLoading = false,
                            error = error.message?.takeIf { it.isNotBlank() }
                                ?: "Registration failed. Check your connection and try again.",
                        )
                    }
            } else {
                authRepository.signIn(email, password)
                    .onFailure { error ->
                        _state.value = _state.value.copy(
                            isLoading = false,
                            error = error.message?.takeIf { it.isNotBlank() }
                                ?: "Sign-in failed. Check your connection and try again.",
                        )
                    }
                // Success clears itself: the gate routes away on the new session.
                // The loading flag is intentionally left on so the button cannot
                // be double-tapped during the handover.
            }
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
         * Placeholder credentials for the demo prefill. These are UI fixtures only —
         * they are never sent anywhere until the user taps Sign in, and they are not
         * a shipped account. Point them at a real Supabase user you have created if
         * you want the demo path to actually authenticate.
         */
        const val DEMO_EMAIL = "demo@auris.app"
        const val DEMO_PASSWORD = "demo123"

        fun factory(authRepository: AuthRepository): ViewModelProvider.Factory =
            Factory(authRepository)
    }
}
