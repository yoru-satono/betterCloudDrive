package com.betterclouddrive.android.ui.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
fun RegisterScreen(
    onNavigateBack: () -> Unit,
    viewModel: AuthViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    var username by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.error) {
        uiState.error?.let { snackbarHostState.showSnackbar(it) }
    }

    val events by viewModel.events.collectAsState(initial = null)
    LaunchedEffect(events) {
        when (events) {
            is UiEvent -> {
                val event = events as? UiEvent
                when {
                    event is UiEvent.NavigateBack -> onNavigateBack()
                    event is UiEvent.ShowSnackbar -> snackbarHostState.showSnackbar(event.message)
                }
            }
            else -> {}
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("注册") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
        ) {
            Spacer(modifier = Modifier.height(24.dp))

            OutlinedTextField(
                value = username,
                onValueChange = { username = it; viewModel.clearError() },
                label = { Text("用户名 (3-64 字符)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MaterialTheme.colorScheme.primary),
            )
            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("邮箱 (可选)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MaterialTheme.colorScheme.primary),
            )
            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = password,
                onValueChange = { password = it; viewModel.clearError() },
                label = { Text("密码 (至少8位，含大小写字母+数字)") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MaterialTheme.colorScheme.primary),
            )
            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = confirmPassword,
                onValueChange = { confirmPassword = it; viewModel.clearError() },
                label = { Text("确认密码") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MaterialTheme.colorScheme.primary),
            )

            // Local validation
            val passwordError = when {
                confirmPassword.isNotEmpty() && password != confirmPassword -> "两次密码不匹配"
                password.isNotEmpty() && password.length < 8 -> "密码至少8个字符"
                else -> null
            }
            if (passwordError != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(passwordError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = { viewModel.register(username, password, email.ifBlank { null }) },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                enabled = username.length >= 3 && password.length >= 8 && password == confirmPassword && !uiState.isLoading,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            ) {
                if (uiState.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text("注 册", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
