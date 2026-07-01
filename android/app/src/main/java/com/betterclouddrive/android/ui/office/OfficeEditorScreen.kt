package com.betterclouddrive.android.ui.office

import android.annotation.SuppressLint
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.betterclouddrive.android.data.repository.TeamRepository
import com.betterclouddrive.android.domain.model.OfficeConfig
import com.betterclouddrive.android.util.NetworkResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class OfficeEditorUiState(
    val config: OfficeConfig? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class OfficeEditorViewModel @Inject constructor(
    private val repository: TeamRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(OfficeEditorUiState())
    val uiState = _uiState.asStateFlow()

    fun load(teamId: Long, fileId: Long) {
        viewModelScope.launch {
            _uiState.value = OfficeEditorUiState(isLoading = true)
            when (val result = repository.getOfficeConfig(teamId, fileId)) {
                is NetworkResult.Success -> _uiState.value = OfficeEditorUiState(config = result.data)
                is NetworkResult.Error -> _uiState.value = OfficeEditorUiState(error = result.message)
                is NetworkResult.Loading -> Unit
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfficeEditorScreen(
    teamId: Long,
    fileId: Long,
    onNavigateBack: () -> Unit,
    viewModel: OfficeEditorViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    LaunchedEffect(teamId, fileId) { viewModel.load(teamId, fileId) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (uiState.config?.editable == true) "文档编辑" else "文档预览") },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.Default.ArrowBack, "返回") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            when {
                uiState.isLoading -> CircularProgressIndicator()
                uiState.error != null -> Text(
                    text = "OnlyOffice 加载失败: ${uiState.error}",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(androidx.compose.foundation.layout.PaddingValues(16.dp)),
                )
                uiState.config != null -> OnlyOfficeWebView(
                    config = uiState.config!!,
                    modifier = Modifier.fillMaxSize().testTag("onlyoffice-webview"),
                )
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun OnlyOfficeWebView(config: OfficeConfig, modifier: Modifier = Modifier) {
    val html = rememberOnlyOfficeHtml(config)
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                webChromeClient = WebChromeClient()
                webViewClient = WebViewClient()
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.databaseEnabled = true
                settings.allowFileAccess = true
                settings.allowContentAccess = true
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                loadDataWithBaseURL(config.documentServerUrl, html, "text/html", "UTF-8", null)
            }
        },
        update = { webView ->
            webView.loadDataWithBaseURL(config.documentServerUrl, html, "text/html", "UTF-8", null)
        },
    )
}

@Composable
private fun rememberOnlyOfficeHtml(config: OfficeConfig): String {
    val documentServer = config.documentServerUrl.trimEnd('/')
    val apiUrl = "$documentServer/web-apps/apps/api/documents/api.js"
    val configJson = config.config.toString()
    return """
        <!doctype html>
        <html>
        <head>
          <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0">
          <script src="$apiUrl"></script>
          <style>
            html, body, #onlyoffice-editor { margin: 0; width: 100%; height: 100%; overflow: hidden; background: #000; }
            #onlyoffice-error { color: #ddd; font-family: sans-serif; padding: 16px; }
          </style>
        </head>
        <body>
          <div id="onlyoffice-editor"></div>
          <script>
            window.addEventListener('load', function () {
              try {
                if (!window.DocsAPI || !window.DocsAPI.DocEditor) {
                  document.body.innerHTML = '<div id="onlyoffice-error">OnlyOffice DocsAPI is unavailable</div>';
                  return;
                }
                window.bcdEditor = new window.DocsAPI.DocEditor('onlyoffice-editor', $configJson);
              } catch (error) {
                document.body.innerHTML = '<div id="onlyoffice-error">' + String(error) + '</div>';
              }
            });
          </script>
        </body>
        </html>
    """.trimIndent()
}
