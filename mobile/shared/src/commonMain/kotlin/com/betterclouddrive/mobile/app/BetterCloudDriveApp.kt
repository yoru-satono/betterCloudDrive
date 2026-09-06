package com.betterclouddrive.mobile.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.betterclouddrive.mobile.designsystem.BetterCloudDriveTheme
import com.betterclouddrive.mobile.core.localization.LocalMobileStrings
import com.betterclouddrive.mobile.core.localization.MobileString
import com.betterclouddrive.mobile.core.localization.mobileStringsFor
import com.betterclouddrive.mobile.core.localization.MobileLocale
import com.betterclouddrive.mobile.feature.auth.AppPhase
import com.betterclouddrive.mobile.feature.auth.AuthState
import com.betterclouddrive.mobile.feature.auth.PasswordResetStep
import com.betterclouddrive.mobile.feature.auth.PublicAuthPage

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun BetterCloudDriveApp(container: AppContainer, debugToolsEnabled: Boolean) {
    val auth = container.authStore
    val coordinator = container.coordinator
    val state by auth.state.collectAsState()
    val incoming by container.incomingInviteStore.state.collectAsState()
    LaunchedEffect(coordinator) { coordinator.start() }
    LaunchedEffect(state.phase, incoming.awaitingAuthentication) {
        if (state.phase == AppPhase.LOGGED_IN && incoming.awaitingAuthentication) {
            container.incomingInviteStore.resumeAfterAuthentication()
        }
    }

    val strings = mobileStringsFor(container.localeProvider.preferredLanguageTags())
    CompositionLocalProvider(LocalMobileStrings provides strings) {
        BetterCloudDriveTheme(state.account.themeMode) {
            BackHandler(
                enabled = state.phase == AppPhase.LOGGED_OUT && state.account.publicPage != PublicAuthPage.LOGIN,
                onBack = auth::showLogin,
            )
            Surface(Modifier.fillMaxSize()) {
                if (incoming.active && !incoming.awaitingAuthentication && state.phase !in setOf(AppPhase.LOADING, AppPhase.RESTORE_FAILED)) {
                    IncomingInviteScreen(
                        state = incoming,
                        accountEmail = state.user?.email,
                        loggedIn = state.phase == AppPhase.LOGGED_IN,
                        onRetry = container.incomingInviteStore::retry,
                        onLogin = { container.incomingInviteStore.requestAuthentication(); auth.showLogin() },
                        onRegister = {
                            container.incomingInviteStore.requestAuthentication()
                            auth.showInvitationRegistration(state.server, incoming.invite?.email.orEmpty())
                        },
                        onAccept = { container.incomingInviteStore.accept(state.user?.email) },
                        onReject = { container.incomingInviteStore.reject(state.user?.email) },
                        onSwitchAccount = {
                            container.incomingInviteStore.requestAuthentication()
                            coordinator.switchAccountPreservingInvite()
                        },
                        onClose = container.incomingInviteStore::close,
                        onContinue = container.incomingInviteStore::finish,
                    )
                } else when (state.phase) {
                    AppPhase.LOADING -> StartupLoadingScreen()
                    AppPhase.RESTORE_FAILED -> RestoreFailedScreen(
                        state, coordinator::retryRestore, coordinator::discardSession,
                    )
                    AppPhase.LOGGED_OUT -> when (state.account.publicPage) {
                        PublicAuthPage.LOGIN -> LoginScreen(
                            state, auth::selectPublicLocale, coordinator::login,
                            auth::showRegistration, auth::showForgotPassword,
                        )
                        PublicAuthPage.REGISTER -> RegistrationScreen(
                            state, auth::selectPublicLocale, auth::sendRegistrationCode,
                            auth::register, auth::showLogin,
                        )
                        PublicAuthPage.FORGOT_PASSWORD -> ForgotPasswordScreen(
                            state, auth::requestPasswordReset, auth::resetPassword, auth::showLogin,
                        )
                    }
                    AppPhase.LOGGED_IN -> ProductShell(
                        container = container,
                        debugToolsEnabled = debugToolsEnabled,
                        initialTeamId = incoming.acceptedTeamId,
                        onInitialTeamConsumed = container.incomingInviteStore::consumeAcceptedTeam,
                    )
                }
            }
        }
    }
}

@Composable
private fun StartupLoadingScreen() {
    Box(
        Modifier.fillMaxSize().testTag("startup-loading"),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun RestoreFailedScreen(state: AuthState, onRetry: () -> Unit, onDiscard: () -> Unit) {
    val strings = LocalMobileStrings.current
    Column(
        Modifier.fillMaxSize().padding(24.dp).testTag("restore-failed"),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(strings[MobileString.RestoreTitle], style = androidx.compose.material3.MaterialTheme.typography.headlineSmall)
        Text(state.server)
        Text(strings.resolve(state.sessionError) ?: strings[MobileString.SessionPreserved])
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onRetry,
            modifier = Modifier.fillMaxWidth().testTag("restore-retry"),
        ) { Text(strings[MobileString.Retry]) }
        OutlinedButton(
            onClick = onDiscard,
            modifier = Modifier.fillMaxWidth().testTag("restore-discard"),
        ) { Text(strings[MobileString.UseAnotherAccount]) }
    }
}

@Composable
private fun LoginScreen(
    state: AuthState,
    onLocale: (MobileLocale) -> Unit,
    onLogin: (String, String, String) -> Unit,
    onRegister: (String) -> Unit,
    onForgotPassword: (String) -> Unit,
) {
    val strings = LocalMobileStrings.current
    var server by rememberSaveable(state.server) { mutableStateOf(state.server) }
    var username by rememberSaveable(state.account.loginUsername) { mutableStateOf(state.account.loginUsername) }
    var password by rememberSaveable { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().padding(24.dp).testTag("login-screen"),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(strings[MobileString.AppName], style = androidx.compose.material3.MaterialTheme.typography.headlineMedium)
        Text(strings[MobileString.StageLabel])
        LocaleChoices(state.account.locale, onLocale)
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = server,
            onValueChange = { server = it },
            label = { Text(strings[MobileString.ServerAddress]) },
            modifier = Modifier.fillMaxWidth().testTag("login-server"),
            singleLine = true,
        )
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text(strings[MobileString.Username]) },
            modifier = Modifier.fillMaxWidth().testTag("login-username"),
            singleLine = true,
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(strings[MobileString.Password]) },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth().testTag("login-password"),
            singleLine = true,
        )
        state.sessionError?.let { message ->
            Text(strings.resolve(message).orEmpty(), color = androidx.compose.material3.MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(12.dp))
        Button(
            enabled = !state.loginBusy && username.isNotBlank() && password.isNotBlank(),
            onClick = { onLogin(server, username, password) },
            modifier = Modifier.fillMaxWidth().testTag("login-submit"),
        ) {
            if (state.loginBusy) {
                CircularProgressIndicator(Modifier.height(20.dp))
            } else {
                Text(strings[MobileString.Login])
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { onRegister(server) }, modifier = Modifier.testTag("login-register")) {
                Text(strings[MobileString.Register])
            }
            TextButton(onClick = { onForgotPassword(server) }, modifier = Modifier.testTag("login-forgot")) {
                Text(strings[MobileString.ForgotPassword])
            }
        }
        if (state.account.registrationSucceeded) Text(strings[MobileString.RegistrationSuccess])
        if (state.account.passwordResetSucceeded) Text(strings[MobileString.ResetSuccess])
    }
}

@Composable
private fun LocaleChoices(selected: MobileLocale, onSelect: (MobileLocale) -> Unit) {
    val strings = LocalMobileStrings.current
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(strings[MobileString.Language])
        listOf(
            MobileLocale.ENGLISH_US to MobileString.LanguageEnglish,
            MobileLocale.SIMPLIFIED_CHINESE_CN to MobileString.LanguageChineseCn,
            MobileLocale.SIMPLIFIED_CHINESE_SG to MobileString.LanguageChineseSg,
        ).forEach { (locale, label) ->
            TextButton(
                onClick = { onSelect(locale) },
                enabled = selected != locale,
                modifier = Modifier.fillMaxWidth().testTag("locale-${locale.languageTag}"),
            ) { Text(strings[label]) }
        }
    }
}

@Composable
private fun RegistrationScreen(
    state: AuthState,
    onLocale: (MobileLocale) -> Unit,
    onSendCode: (String) -> Unit,
    onRegister: (String, String, String, String, String) -> Unit,
    onBack: () -> Unit,
) {
    val strings = LocalMobileStrings.current
    var username by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable(state.account.registrationEmail) { mutableStateOf(state.account.registrationEmail) }
    var code by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var confirmation by rememberSaveable { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp).testTag("register-screen"),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(strings[MobileString.Register], style = androidx.compose.material3.MaterialTheme.typography.headlineMedium)
        LocaleChoices(state.account.locale, onLocale)
        OutlinedTextField(username, { username = it }, label = { Text(strings[MobileString.Username]) }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            email, { email = it }, enabled = !state.account.registrationEmailLocked,
            label = { Text(strings[MobileString.Email]) }, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(code, { code = it }, label = { Text(strings[MobileString.VerificationCode]) }, modifier = Modifier.fillMaxWidth())
        OutlinedButton(
            onClick = { onSendCode(email) },
            enabled = !state.account.registrationCodeBusy && state.account.registrationCodeSeconds == 0 && email.isNotBlank(),
            modifier = Modifier.fillMaxWidth().testTag("register-send-code"),
        ) {
            Text(if (state.account.registrationCodeSeconds > 0) {
                strings.format(MobileString.ResendIn, state.account.registrationCodeSeconds)
            } else strings[MobileString.SendCode])
        }
        OutlinedTextField(password, { password = it }, label = { Text(strings[MobileString.Password]) }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(confirmation, { confirmation = it }, label = { Text(strings[MobileString.ConfirmPassword]) }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        state.account.registrationError?.let { Text(strings.resolve(it).orEmpty(), color = androidx.compose.material3.MaterialTheme.colorScheme.error) }
        Button(
            onClick = { onRegister(username, email, code, password, confirmation) },
            enabled = !state.account.registerBusy,
            modifier = Modifier.fillMaxWidth().testTag("register-submit"),
        ) { Text(strings[MobileString.Register]) }
        TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text(strings[MobileString.BackToLogin]) }
    }
}

@Composable
private fun ForgotPasswordScreen(
    state: AuthState,
    onRequest: (String) -> Unit,
    onReset: (String, String, String) -> Unit,
    onBack: () -> Unit,
) {
    val strings = LocalMobileStrings.current
    var email by rememberSaveable(state.account.resetEmail) { mutableStateOf(state.account.resetEmail) }
    var code by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var confirmation by rememberSaveable { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp).testTag("forgot-screen"),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(strings[MobileString.ResetPassword], style = androidx.compose.material3.MaterialTheme.typography.headlineMedium)
        if (state.account.resetStep == PasswordResetStep.EMAIL) {
            OutlinedTextField(email, { email = it }, label = { Text(strings[MobileString.Email]) }, modifier = Modifier.fillMaxWidth())
            Button(
                onClick = { onRequest(email) },
                enabled = !state.account.resetBusy && email.isNotBlank(),
                modifier = Modifier.fillMaxWidth().testTag("forgot-send"),
            ) { Text(strings[MobileString.SendCode]) }
        } else {
            Text(strings[MobileString.ResetCodeSent])
            OutlinedTextField(code, { code = it }, label = { Text(strings[MobileString.VerificationCode]) }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(password, { password = it }, label = { Text(strings[MobileString.NewPassword]) }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(confirmation, { confirmation = it }, label = { Text(strings[MobileString.ConfirmPassword]) }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Button(
                onClick = { onReset(code, password, confirmation) },
                enabled = !state.account.resetBusy,
                modifier = Modifier.fillMaxWidth().testTag("forgot-reset"),
            ) { Text(strings[MobileString.ResetPassword]) }
        }
        state.account.resetError?.let { Text(strings.resolve(it).orEmpty(), color = androidx.compose.material3.MaterialTheme.colorScheme.error) }
        TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text(strings[MobileString.BackToLogin]) }
    }
}
