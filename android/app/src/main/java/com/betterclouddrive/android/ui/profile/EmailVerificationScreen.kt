package com.betterclouddrive.android.ui.profile

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.betterclouddrive.android.ui.auth.AuthViewModel
import com.betterclouddrive.android.util.UiEvent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmailVerificationScreen(
    onNavigateBack: () -> Unit,
    viewModel: AuthViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    var code by remember { mutableStateOf("") }
    var sent by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.error) { uiState.error?.let { snackbarHostState.showSnackbar(it) } }

    val events by viewModel.events.collectAsState(initial = null)
    LaunchedEffect(events) {
        if (events is UiEvent.ShowSnackbar) snackbarHostState.showSnackbar((events as? UiEvent.ShowSnackbar)?.message ?: "")
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("邮箱验证") },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.Default.ArrowBack, "返回") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp)) {
            Text("验证您的邮箱地址", style = MaterialTheme.typography.headlineMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text("我们将发送6位验证码到您的注册邮箱", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(24.dp))

            if (!sent) {
                Button(
                    onClick = { viewModel.sendVerificationCode(); sent = true },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) {
                    Text("发送验证码")
                }
            } else {
                OutlinedTextField(
                    value = code,
                    onValueChange = { if (it.length <= 6) code = it },
                    label = { Text("验证码") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = { viewModel.verifyEmail(code) },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    enabled = code.length == 6,
                ) {
                    Text("验证")
                }
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = { viewModel.sendVerificationCode() }) {
                    Text("重新发送验证码")
                }
            }
        }
    }
}
