package com.betterclouddrive.android.ui.preview

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextPreviewScreen(
    fileId: Long,
    onNavigateBack: () -> Unit,
) {
    var content by remember { mutableStateOf("加载中...") }

    LaunchedEffect(fileId) {
        withContext(Dispatchers.IO) {
            try {
                val url = URL("http://10.0.2.2:8080/api/v1/preview/$fileId")
                val conn = url.openConnection() as HttpURLConnection
                conn.setRequestProperty("Authorization", "Bearer $token")
                content = conn.inputStream.bufferedReader().readText()
            } catch (e: Exception) {
                content = "加载失败: ${e.message}"
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("文本预览") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text(
                text = content,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            )
        }
    }
}

// Token placeholder — will be injected via parameter or Hilt
private val token = ""
