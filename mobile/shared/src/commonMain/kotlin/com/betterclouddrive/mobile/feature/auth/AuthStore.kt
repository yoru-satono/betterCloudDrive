package com.betterclouddrive.mobile.feature.auth

import com.betterclouddrive.mobile.app.ResettableStore
import com.betterclouddrive.mobile.app.uiError
import com.betterclouddrive.mobile.app.storeChildScope
import com.betterclouddrive.mobile.core.localization.MobileLocale
import com.betterclouddrive.mobile.core.localization.MobileString
import com.betterclouddrive.mobile.core.localization.UiMessage
import com.betterclouddrive.mobile.core.model.SessionInvalidationReason
import com.betterclouddrive.mobile.core.model.SessionRestoreResult
import com.betterclouddrive.mobile.core.model.UserProfile
import com.betterclouddrive.mobile.core.network.NetworkDefaults
import com.betterclouddrive.mobile.core.platform.ApplicationLocaleController
import com.betterclouddrive.mobile.core.platform.ThemeMode
import com.betterclouddrive.mobile.core.platform.ThemePreferenceStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AppPhase { LOADING, RESTORE_FAILED, LOGGED_OUT, LOGGED_IN }
enum class PublicAuthPage { LOGIN, REGISTER, FORGOT_PASSWORD }
enum class PasswordResetStep { EMAIL, RESET }

data class AccountState(
    val publicPage: PublicAuthPage = PublicAuthPage.LOGIN,
    val loginUsername: String = "",
    val registerBusy: Boolean = false,
    val registrationCodeBusy: Boolean = false,
    val registrationCodeSeconds: Int = 0,
    val registrationError: UiMessage? = null,
    val registrationSucceeded: Boolean = false,
    val resetStep: PasswordResetStep = PasswordResetStep.EMAIL,
    val resetBusy: Boolean = false,
    val resetEmail: String = "",
    val resetError: UiMessage? = null,
    val resetCodeSent: Boolean = false,
    val passwordResetSucceeded: Boolean = false,
    val profileRefreshing: Boolean = false,
    val profileError: UiMessage? = null,
    val localeSaving: Boolean = false,
    val localeError: UiMessage? = null,
    val locale: MobileLocale = MobileLocale.ENGLISH_US,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val serverCandidate: String = "",
    val serverSwitchConfirming: Boolean = false,
    val serverSwitching: Boolean = false,
    val serverError: UiMessage? = null,
)

data class AuthState(
    val phase: AppPhase = AppPhase.LOADING,
    val server: String = NetworkDefaults.DEFAULT_SERVER,
    val user: UserProfile? = null,
    val loginBusy: Boolean = false,
    val sessionError: UiMessage? = null,
    val account: AccountState = AccountState(),
)

class AuthStore(
    private val source: AuthDataSource,
    parentScope: CoroutineScope,
    private val localeController: ApplicationLocaleController,
    private val themePreferences: ThemePreferenceStore,
) : ResettableStore {
    private val scope = parentScope.storeChildScope()
    private val mutableState = MutableStateFlow(AuthState(
        server = source.serverBaseUrl,
        account = AccountState(locale = localeController.currentLocale()),
    ))
    val state: StateFlow<AuthState> = mutableState.asStateFlow()
    private var countdownJob: Job? = null

    init {
        scope.launch { themePreferences.themeMode.collectLatest { mode ->
            mutableState.update { it.copy(account = it.account.copy(themeMode = mode)) }
        } }
    }

    suspend fun restore(): Boolean {
        mutableState.update { it.copy(phase = AppPhase.LOADING, loginBusy = false, sessionError = null) }
        return when (val result = source.restore()) {
            SessionRestoreResult.NoSession, is SessionRestoreResult.Invalidated -> {
                mutableState.update { AuthState(
                    phase = AppPhase.LOGGED_OUT, server = source.serverBaseUrl,
                    account = it.account.copy(publicPage = PublicAuthPage.LOGIN),
                ) }; false
            }
            is SessionRestoreResult.RetryableFailure -> {
                mutableState.update { it.copy(
                    phase = AppPhase.RESTORE_FAILED, server = source.serverBaseUrl,
                    sessionError = UiMessage(MobileString.ErrorSessionPreserved),
                ) }; false
            }
            is SessionRestoreResult.Restored -> {
                applyAccountLocale(result.user.preferredLocale)
                mutableState.update { it.copy(
                    phase = AppPhase.LOGGED_IN, user = result.user, server = source.serverBaseUrl,
                ) }; true
            }
        }
    }

    suspend fun login(server: String, username: String, password: String): Boolean = sessionAction {
        val normalized = source.normalizeServer(server).getOrThrow()
        if (normalized != source.serverBaseUrl) source.switchServer(normalized).getOrThrow()
        val user = source.login(username, password); applyAccountLocale(user.preferredLocale)
        mutableState.update { it.copy(
            phase = AppPhase.LOGGED_IN, user = user, server = source.serverBaseUrl,
            account = it.account.copy(
                publicPage = PublicAuthPage.LOGIN, loginUsername = username,
                registrationError = null, resetError = null,
            ),
        ) }
    }

    suspend fun logout() {
        mutableState.update { it.copy(loginBusy = true, sessionError = null) }
        source.logout(); markLoggedOut()
    }

    suspend fun switchServer(afterLogout: suspend () -> Unit = {}): Boolean {
        val target = state.value.account.serverCandidate
        if (target.isBlank() || state.value.account.serverSwitching) return false
        mutableState.update { it.copy(account = it.account.copy(serverSwitching = true, serverError = null)) }
        return try {
            source.logout()
            afterLogout()
            source.switchServer(target).getOrThrow()
            markLoggedOut()
            true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Throwable) {
            mutableState.update { it.copy(account = it.account.copy(serverSwitching = false, serverError = uiError(error))) }; false
        }
    }

    fun invalidate(reason: SessionInvalidationReason) {
        if (reason !in setOf(SessionInvalidationReason.EXPIRED, SessionInvalidationReason.REVOKED, SessionInvalidationReason.CORRUPT_STORAGE)) return
        mutableState.update { AuthState(
            phase = AppPhase.LOGGED_OUT, server = source.serverBaseUrl,
            sessionError = UiMessage(MobileString.ErrorSessionExpired),
            account = it.account.copy(publicPage = PublicAuthPage.LOGIN),
        ) }
    }

    fun showLogin() = mutableState.update { it.copy(account = it.account.copy(
        publicPage = PublicAuthPage.LOGIN, registrationError = null, resetError = null,
    )) }
    fun showRegistration(server: String) = openPublicPage(server, PublicAuthPage.REGISTER)
    fun showForgotPassword(server: String) = openPublicPage(server, PublicAuthPage.FORGOT_PASSWORD)

    private fun openPublicPage(server: String, page: PublicAuthPage) = scope.launch {
        try {
            val normalized = source.normalizeServer(server).getOrThrow()
            if (normalized != source.serverBaseUrl) source.switchServer(normalized).getOrThrow()
            mutableState.update { it.copy(
                server = source.serverBaseUrl, sessionError = null,
                account = it.account.copy(
                    publicPage = page, registrationError = null, registrationSucceeded = false,
                    resetStep = PasswordResetStep.EMAIL, resetError = null,
                    passwordResetSucceeded = false, resetCodeSent = false,
                ),
            ) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Throwable) { mutableState.update { it.copy(sessionError = uiError(error)) } }
    }

    fun selectPublicLocale(locale: MobileLocale) {
        localeController.applyLocale(locale); mutableState.update { it.copy(account = it.account.copy(locale = locale, localeError = null)) }
    }

    fun sendRegistrationCode(email: String) = scope.launch {
        val account = state.value.account
        if (account.registrationCodeBusy || account.registrationCodeSeconds > 0) return@launch
        mutableState.update { it.copy(account = it.account.copy(registrationCodeBusy = true, registrationError = null)) }
        try {
            source.sendRegistrationCode(email.trim(), state.value.account.locale.languageTag)
            mutableState.update { it.copy(account = it.account.copy(registrationCodeBusy = false, registrationCodeSeconds = 60)) }
            countdownJob?.cancel(); countdownJob = scope.launch { repeat(60) {
                delay(1_000); mutableState.update { value -> value.copy(account = value.account.copy(
                    registrationCodeSeconds = (value.account.registrationCodeSeconds - 1).coerceAtLeast(0),
                )) }
            } }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Throwable) { mutableState.update { it.copy(account = it.account.copy(
            registrationCodeBusy = false, registrationError = uiError(error),
        )) } }
    }

    fun register(username: String, email: String, code: String, password: String, confirmation: String) = scope.launch {
        if (state.value.account.registerBusy) return@launch
        validateRegistration(username, email, code, password, confirmation)?.let { message ->
            mutableState.update { it.copy(account = it.account.copy(registrationError = message)) }; return@launch
        }
        mutableState.update { it.copy(account = it.account.copy(registerBusy = true, registrationError = null)) }
        try {
            val result = source.register(username.trim(), password, email.trim(), code.trim(), state.value.account.locale.languageTag)
            countdownJob?.cancel(); mutableState.update { it.copy(account = it.account.copy(
                publicPage = PublicAuthPage.LOGIN, loginUsername = result.username, registerBusy = false,
                registrationCodeSeconds = 0, registrationSucceeded = true,
            )) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Throwable) { mutableState.update { it.copy(account = it.account.copy(
            registerBusy = false, registrationError = uiError(error),
        )) } }
    }

    fun requestPasswordReset(email: String) = scope.launch {
        if (state.value.account.resetBusy) return@launch
        if (!isEmail(email)) { mutableState.update { it.copy(account = it.account.copy(
            resetError = UiMessage(MobileString.ErrorInvalidInput),
        )) }; return@launch }
        mutableState.update { it.copy(account = it.account.copy(resetBusy = true, resetError = null)) }
        try {
            source.forgotPassword(email.trim()); mutableState.update { it.copy(account = it.account.copy(
                resetBusy = false, resetEmail = email.trim(), resetStep = PasswordResetStep.RESET, resetCodeSent = true,
            )) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Throwable) { mutableState.update { it.copy(account = it.account.copy(resetBusy = false, resetError = uiError(error))) } }
    }

    fun resetPassword(code: String, password: String, confirmation: String) = scope.launch {
        if (state.value.account.resetBusy) return@launch
        if (!code.matches(Regex("\\d{6}")) || password.length !in 6..128 || password != confirmation) {
            mutableState.update { it.copy(account = it.account.copy(resetError = UiMessage(MobileString.ErrorInvalidInput))) }; return@launch
        }
        mutableState.update { it.copy(account = it.account.copy(resetBusy = true, resetError = null)) }
        try {
            source.resetPassword(state.value.account.resetEmail, code, password); mutableState.update { it.copy(account = it.account.copy(
                publicPage = PublicAuthPage.LOGIN, resetBusy = false, resetStep = PasswordResetStep.EMAIL,
                resetCodeSent = false, passwordResetSucceeded = true,
            )) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Throwable) { mutableState.update { it.copy(account = it.account.copy(resetBusy = false, resetError = uiError(error))) } }
    }

    fun refreshProfile() = scope.launch {
        if (state.value.account.profileRefreshing) return@launch
        mutableState.update { it.copy(account = it.account.copy(profileRefreshing = true, profileError = null)) }
        try { val profile = source.getCurrentUser(); mutableState.update { it.copy(
            user = profile, account = it.account.copy(profileRefreshing = false),
        ) } } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Throwable) { mutableState.update { it.copy(account = it.account.copy(
            profileRefreshing = false, profileError = uiError(error),
        )) } }
    }

    fun updateLocale(locale: MobileLocale) = scope.launch {
        val account = state.value.account
        if (account.localeSaving || account.locale == locale) return@launch
        mutableState.update { it.copy(account = it.account.copy(localeSaving = true, localeError = null)) }
        try {
            val profile = source.updatePreferredLocale(locale.languageTag)
            mutableState.update { it.copy(user = profile, account = it.account.copy(localeSaving = false, locale = locale)) }
            localeController.applyLocale(locale)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Throwable) { mutableState.update { it.copy(account = it.account.copy(
            localeSaving = false, localeError = uiError(error),
        )) } }
    }

    fun setThemeMode(mode: ThemeMode) { scope.launch { themePreferences.setThemeMode(mode) } }
    fun requestServerSwitch(value: String) {
        source.normalizeServer(value).fold(onSuccess = { server -> mutableState.update { current ->
            if (server == source.serverBaseUrl) current.copy(account = current.account.copy(
                serverCandidate = "", serverSwitchConfirming = false, serverError = null,
            )) else current.copy(account = current.account.copy(
                serverCandidate = server, serverSwitchConfirming = true, serverError = null,
            ))
        } }, onFailure = { error -> mutableState.update { it.copy(account = it.account.copy(serverError = uiError(error))) } })
    }
    fun cancelServerSwitch() = mutableState.update { it.copy(account = it.account.copy(
        serverCandidate = "", serverSwitchConfirming = false, serverError = null,
    )) }

    fun markLoggedOut() { mutableState.update { AuthState(
        phase = AppPhase.LOGGED_OUT, server = source.serverBaseUrl,
        account = it.account.copy(
            publicPage = PublicAuthPage.LOGIN, serverCandidate = "", serverSwitchConfirming = false, serverSwitching = false,
        ),
    ) } }

    override fun reset() {
        countdownJob?.cancel()
        val preferences = state.value.account
        mutableState.value = AuthState(server = source.serverBaseUrl, account = AccountState(
            locale = preferences.locale, themeMode = preferences.themeMode,
        ))
    }

    private suspend fun sessionAction(block: suspend () -> Unit): Boolean {
        mutableState.update { it.copy(loginBusy = true, sessionError = null) }
        return try { block(); true } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Throwable) { mutableState.update { it.copy(sessionError = uiError(error)) }; false }
        finally { mutableState.update { it.copy(loginBusy = false) } }
    }

    private fun applyAccountLocale(value: String) {
        val locale = when (value.replace('_', '-').lowercase()) {
            "zh-sg", "zh-hans-sg" -> MobileLocale.SIMPLIFIED_CHINESE_SG
            "zh-cn", "zh-hans", "zh-hans-cn" -> MobileLocale.SIMPLIFIED_CHINESE_CN
            else -> MobileLocale.ENGLISH_US
        }
        mutableState.update { it.copy(account = it.account.copy(locale = locale)) }; localeController.applyLocale(locale)
    }

    private fun validateRegistration(username: String, email: String, code: String, password: String, confirmation: String): UiMessage? = when {
        username.trim().length !in 3..64 || !isEmail(email) || !code.trim().matches(Regex("\\d{6}")) -> UiMessage(MobileString.ErrorInvalidInput)
        password.length !in 8..128 || !password.any(Char::isUpperCase) || !password.any(Char::isLowerCase) || !password.any(Char::isDigit) -> UiMessage(MobileString.ErrorInvalidInput)
        password != confirmation -> UiMessage(MobileString.ErrorInvalidInput)
        else -> null
    }
    private fun isEmail(value: String): Boolean = value.trim().let { it.length <= 254 && '@' in it && it.substringAfter('@').contains('.') }
}
