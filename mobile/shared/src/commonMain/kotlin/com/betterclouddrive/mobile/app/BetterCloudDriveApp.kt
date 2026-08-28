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

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun BetterCloudDriveApp(container: AppContainer, debugToolsEnabled: Boolean) {
    val controller = container.controller
    val state by controller.state.collectAsState()
    LaunchedEffect(controller) { controller.start() }

    val strings = mobileStringsFor(container.localeProvider.preferredLanguageTags())
    CompositionLocalProvider(LocalMobileStrings provides strings) {
        BetterCloudDriveTheme(state.account.themeMode) {
            BackHandler(
                enabled = state.phase == AppPhase.LOGGED_OUT && state.account.publicPage != PublicAuthPage.LOGIN,
                onBack = controller::showLogin,
            )
            Surface(Modifier.fillMaxSize()) {
                when (state.phase) {
                    AppPhase.LOADING -> StartupLoadingScreen()
                    AppPhase.RESTORE_FAILED -> RestoreFailedScreen(state, controller)
                    AppPhase.LOGGED_OUT -> when (state.account.publicPage) {
                        PublicAuthPage.LOGIN -> LoginScreen(state, controller)
                        PublicAuthPage.REGISTER -> RegistrationScreen(state, controller)
                        PublicAuthPage.FORGOT_PASSWORD -> ForgotPasswordScreen(state, controller)
                    }
                    AppPhase.LOGGED_IN -> ProductShell(
                        state = state,
                        documentPicker = container.documentPicker,
                        officeRenderer = container.officeRenderer,
                         mediaPreviewRenderer = container.mediaPreviewRenderer,
                         clipboard = container.clipboard,
                         localDateTime = container.localDateTime,
                        roomTaskCount = { container.database.transferTaskDao().count() },
                        actions = ProductShellActions(
                            activatePersonalRoute = controller::activatePersonalRoute,
                            prepareOpenFolder = controller::openPersonalFolder,
                            refreshFiles = controller::refreshFiles,
                            loadMoreFiles = controller::loadMoreFiles,
                            changeFileSort = controller::changeFileSort,
                            changeFileViewMode = controller::changeFileViewMode,
                            selectPersonalFile = controller::selectPersonalFile,
                            togglePersonalSelection = controller::togglePersonalSelection,
                            clearPersonalSelection = controller::clearSelection,
                            clearPersonalTransientUi = controller::clearPersonalTransientUi,
                            createPersonalFolder = controller::createPersonalFolder,
                            renamePersonalFile = controller::renamePersonalFile,
                            deletePersonalFiles = controller::deletePersonalFiles,
                            openFolderPicker = controller::openFolderPicker,
                            openPickerFolder = controller::openPickerFolder,
                            pickerBack = controller::pickerBack,
                            retryFolderPicker = controller::retryFolderPicker,
                            closeFolderPicker = controller::closeFolderPicker,
                            confirmFolderPicker = controller::confirmFolderPicker,
                            consumeFileFeedback = controller::consumeFileFeedback,
                            updatePersonalScrollAnchor = controller::updatePersonalScrollAnchor,
                            updateSearchQuery = controller::updateSearchQuery,
                            submitSearch = controller::submitSearch,
                            retrySearch = controller::retrySearch,
                            loadMoreSearch = controller::loadMoreSearch,
                            updateSearchScrollAnchor = controller::updateSearchScrollAnchor,
                            prepareOpenSearchLocation = controller::prepareOpenSearchLocation,
                            activateFavorites = controller::activateFavorites,
                            updateFavoritesQuery = controller::updateFavoritesQuery,
                            refreshFavorites = controller::refreshFavorites,
                            loadMoreFavorites = controller::loadMoreFavorites,
                            updateFavoritesScrollAnchor = controller::updateFavoritesScrollAnchor,
                            loadFavoriteStatus = controller::loadFavoriteStatus,
                            toggleFavorite = controller::toggleFavorite,
                            consumeFavoriteFeedback = controller::consumeFavoriteFeedback,
                            activateTags = controller::activateTags,
                            updateTagsQuery = controller::updateTagsQuery,
                            refreshTags = controller::refreshTags,
                            createPersonalTag = controller::createPersonalTag,
                            updatePersonalTag = controller::updatePersonalTag,
                            deletePersonalTag = controller::deletePersonalTag,
                            activateTagFiles = controller::activateTagFiles,
                            updateTagFilesQuery = controller::updateTagFilesQuery,
                            refreshTagFiles = controller::refreshTagFiles,
                            loadMoreTagFiles = controller::loadMoreTagFiles,
                            updateTagFilesScrollAnchor = controller::updateTagFilesScrollAnchor,
                            removeFileFromActiveTag = controller::removeFileFromActiveTag,
                            openFileTagEditor = controller::openFileTagEditor,
                            toggleFileTag = controller::toggleFileTag,
                            saveFileTags = controller::saveFileTags,
                            createTagInEditor = controller::createTagInEditor,
                            closeFileTagEditor = controller::closeFileTagEditor,
                             consumeTagFeedback = controller::consumeTagFeedback,
                             activateShares = controller::activateShares,
                             updateSharesQuery = controller::updateSharesQuery,
                             setShareFilters = controller::setShareFilters,
                             refreshShares = controller::refreshShares,
                             loadMoreShares = controller::loadMoreShares,
                             openShareCreator = controller::openShareCreator,
                             closeShareCreator = controller::closeShareCreator,
                             createPersonalShare = controller::createPersonalShare,
                             openShareDetail = controller::openShareDetail,
                             closeShareDetail = controller::closeShareDetail,
                             toggleSharePassword = controller::toggleSharePassword,
                             updatePersonalShare = controller::updatePersonalShare,
                             cancelPersonalShare = controller::cancelPersonalShare,
                             consumeShareFeedback = controller::consumeShareFeedback,
                            openPreview = controller::openPreview,
                            retryPreview = controller::retryPreview,
                            closePreview = controller::closePreview,
                            updatePreviewPosition = controller::updatePreviewPosition,
                            reportPreviewError = controller::reportPreviewError,
                            upload = controller::upload,
                            consumeTransferFeedback = controller::consumeTransferFeedback,
                            download = controller::download,
                            pause = controller::pause,
                            resume = controller::resume,
                            cancel = controller::cancel,
                            retry = controller::retry,
                            clearFinishedTransfers = controller::clearFinishedTransfers,
                            refreshProfile = controller::refreshProfile,
                            updateAccountLocale = controller::updateAccountLocale,
                            setThemeMode = controller::setThemeMode,
                            requestServerSwitch = controller::requestServerSwitch,
                            cancelServerSwitch = controller::cancelServerSwitch,
                            confirmServerSwitch = controller::confirmServerSwitch,
                            logout = controller::logout,
                            loadDiagnosticOffice = controller::loadDiagnosticOffice,
                            updateDiagnosticStatus = controller::updateDiagnosticStatus,
                        ),
                        debugToolsEnabled = debugToolsEnabled,
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
private fun RestoreFailedScreen(state: AppUiState, controller: AppController) {
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
            onClick = controller::retryRestore,
            modifier = Modifier.fillMaxWidth().testTag("restore-retry"),
        ) { Text(strings[MobileString.Retry]) }
        OutlinedButton(
            onClick = controller::discardSession,
            modifier = Modifier.fillMaxWidth().testTag("restore-discard"),
        ) { Text(strings[MobileString.UseAnotherAccount]) }
    }
}

@Composable
private fun LoginScreen(state: AppUiState, controller: AppController) {
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
        LocaleChoices(state.account.locale, controller::selectPublicLocale)
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
            onClick = { controller.login(server, username, password) },
            modifier = Modifier.fillMaxWidth().testTag("login-submit"),
        ) {
            if (state.loginBusy) {
                CircularProgressIndicator(Modifier.height(20.dp))
            } else {
                Text(strings[MobileString.Login])
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { controller.showRegistration(server) }, modifier = Modifier.testTag("login-register")) {
                Text(strings[MobileString.Register])
            }
            TextButton(onClick = { controller.showForgotPassword(server) }, modifier = Modifier.testTag("login-forgot")) {
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
private fun RegistrationScreen(state: AppUiState, controller: AppController) {
    val strings = LocalMobileStrings.current
    var username by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var confirmation by rememberSaveable { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp).testTag("register-screen"),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(strings[MobileString.Register], style = androidx.compose.material3.MaterialTheme.typography.headlineMedium)
        LocaleChoices(state.account.locale, controller::selectPublicLocale)
        OutlinedTextField(username, { username = it }, label = { Text(strings[MobileString.Username]) }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(email, { email = it }, label = { Text(strings[MobileString.Email]) }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(code, { code = it }, label = { Text(strings[MobileString.VerificationCode]) }, modifier = Modifier.fillMaxWidth())
        OutlinedButton(
            onClick = { controller.sendRegistrationCode(email) },
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
            onClick = { controller.register(username, email, code, password, confirmation) },
            enabled = !state.account.registerBusy,
            modifier = Modifier.fillMaxWidth().testTag("register-submit"),
        ) { Text(strings[MobileString.Register]) }
        TextButton(onClick = controller::showLogin, modifier = Modifier.fillMaxWidth()) { Text(strings[MobileString.BackToLogin]) }
    }
}

@Composable
private fun ForgotPasswordScreen(state: AppUiState, controller: AppController) {
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
                onClick = { controller.requestPasswordReset(email) },
                enabled = !state.account.resetBusy && email.isNotBlank(),
                modifier = Modifier.fillMaxWidth().testTag("forgot-send"),
            ) { Text(strings[MobileString.SendCode]) }
        } else {
            Text(strings[MobileString.ResetCodeSent])
            OutlinedTextField(code, { code = it }, label = { Text(strings[MobileString.VerificationCode]) }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(password, { password = it }, label = { Text(strings[MobileString.NewPassword]) }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(confirmation, { confirmation = it }, label = { Text(strings[MobileString.ConfirmPassword]) }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Button(
                onClick = { controller.resetPassword(code, password, confirmation) },
                enabled = !state.account.resetBusy,
                modifier = Modifier.fillMaxWidth().testTag("forgot-reset"),
            ) { Text(strings[MobileString.ResetPassword]) }
        }
        state.account.resetError?.let { Text(strings.resolve(it).orEmpty(), color = androidx.compose.material3.MaterialTheme.colorScheme.error) }
        TextButton(onClick = controller::showLogin, modifier = Modifier.fillMaxWidth()) { Text(strings[MobileString.BackToLogin]) }
    }
}
