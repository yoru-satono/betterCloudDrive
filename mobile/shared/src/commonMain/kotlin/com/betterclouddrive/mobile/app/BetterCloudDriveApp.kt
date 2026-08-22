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

@Composable
fun BetterCloudDriveApp(container: AppContainer, debugToolsEnabled: Boolean) {
    val controller = container.controller
    val state by controller.state.collectAsState()
    LaunchedEffect(controller) { controller.start() }

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
    Column(
        Modifier.fillMaxSize().padding(24.dp).testTag("restore-failed"),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("暂时无法连接服务器", style = androidx.compose.material3.MaterialTheme.typography.headlineSmall)
        Text(state.server)
        Text(state.sessionError ?: "本地会话仍已加密保存")
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = controller::retryRestore,
            modifier = Modifier.fillMaxWidth().testTag("restore-retry"),
        ) { Text("重试") }
        OutlinedButton(
            onClick = controller::discardSession,
            modifier = Modifier.fillMaxWidth().testTag("restore-discard"),
        ) { Text("使用其他账号") }
    }
}

@Composable
private fun LoginScreen(state: AppUiState, controller: AppController) {
    var server by remember(state.server) { mutableStateOf(state.server) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().padding(24.dp).testTag("login-screen"),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("BetterCloudDrive", style = androidx.compose.material3.MaterialTheme.typography.headlineMedium)
        Text("KMP 移动端 · 阶段 4")
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = server,
            onValueChange = { server = it },
            label = { Text("服务器地址") },
            modifier = Modifier.fillMaxWidth().testTag("login-server"),
            singleLine = true,
        )
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("用户名") },
            modifier = Modifier.fillMaxWidth().testTag("login-username"),
            singleLine = true,
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("密码") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth().testTag("login-password"),
            singleLine = true,
        )
        state.sessionError?.let {
            Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error)
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
                Text("登录")
            }
        }
    }
}
