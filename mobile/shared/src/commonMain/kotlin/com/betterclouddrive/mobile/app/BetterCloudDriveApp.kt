package com.betterclouddrive.mobile.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.betterclouddrive.mobile.designsystem.BetterCloudDriveTheme
import com.betterclouddrive.mobile.core.localization.LocalMobileStrings
import com.betterclouddrive.mobile.core.localization.MobileString
import com.betterclouddrive.mobile.core.localization.mobileStringsFor

@Composable
fun BetterCloudDriveApp(container: AppContainer, debugToolsEnabled: Boolean) {
    val controller = container.controller
    val state by controller.state.collectAsState()
    LaunchedEffect(controller) { controller.start() }

    val strings = mobileStringsFor(container.localeProvider.preferredLanguageTags())
    CompositionLocalProvider(LocalMobileStrings provides strings) {
        BetterCloudDriveTheme {
            Surface(Modifier.fillMaxSize()) {
                when (state.phase) {
                    AppPhase.LOADING -> StartupLoadingScreen()
                    AppPhase.RESTORE_FAILED -> RestoreFailedScreen(state, controller)
                    AppPhase.LOGGED_OUT -> LoginScreen(state, controller)
                    AppPhase.LOGGED_IN -> ProductShell(
                        state = state,
                        documentPicker = container.documentPicker,
                        officeRenderer = container.officeRenderer,
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
                            upload = controller::upload,
                            download = controller::download,
                            pause = controller::pause,
                            resume = controller::resume,
                            cancel = controller::cancel,
                            retry = controller::retry,
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
    var server by remember(state.server) { mutableStateOf(state.server) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().padding(24.dp).testTag("login-screen"),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(strings[MobileString.AppName], style = androidx.compose.material3.MaterialTheme.typography.headlineMedium)
        Text(strings[MobileString.StageLabel])
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
    }
}
