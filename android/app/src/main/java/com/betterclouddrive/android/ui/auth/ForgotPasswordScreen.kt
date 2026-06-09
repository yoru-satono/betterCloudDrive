package com.betterclouddrive.android.ui.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.betterclouddrive.android.util.UiEvent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForgotPasswordScreen(
    onNavigateBack: () -> Unit,
    viewModel: AuthViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    var email by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var step by remember { mutableStateOf(1) } // 1=email, 2=code+password
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.error) {
        uiState.error?.let { snackbarHostState.showSnackbar(it) }
    }

    val events by viewModel.events.collectAsState(initial = null)
    LaunchedEffect(events) {
        when (events) {
            is UiEvent.NavigateBack -> onNavigateBack()
            is UiEvent.ShowSnackbar -> snackbarHostState.showSnackbar((events as? UiEvent.ShowSnackbar)?.message ?: "")
            else -> {}
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("忘记密码") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 24.dp),
        ) {
            Spacer(modifier = Modifier.height(24.dp))

            if (step == 1) {
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("邮箱地址") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MaterialTheme.colorScheme.primary),
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = { viewModel.forgotPassword(email); step = 2 },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    enabled = email.contains("@") && !uiState.isLoading,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                ) {
                    Text("发送验证码")
                }
            } else {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text("验证码") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MaterialTheme.colorScheme.primary),
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = newPassword,
                    onValueChange = { newPassword = it },
                    label = { Text("新密码 (至少8位，含大小写字母+数字)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MaterialTheme.colorScheme.primary),
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = { viewModel.resetPassword(email, code, newPassword) },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    enabled = code.length == 6 && newPassword.length >= 8 && !uiState.isLoading,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                ) {
                    Text("重置密码")
                }
                Spacer(modifier = Modifier.height(12.dp))
                TextButton(onClick = { step = 1 }) {
                    Text("重新发送验证码", color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}
