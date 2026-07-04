package com.betterclouddrive.android.ui.office

import android.annotation.SuppressLint
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.inject.Inject

private const val OFFICE_WEB_VIEW_TAG = "OnlyOfficeWebView"

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
    val documentServerUrl = androidReachableDocumentServerUrl(config.documentServerUrl)
    val html = rememberOnlyOfficeHtml(config, documentServerUrl)
    val documentReady = remember(config.config) { mutableStateOf(false) }
    val officeError = remember(config.config) { mutableStateOf<String?>(null) }

    Box(modifier = modifier) {
        key(config.config) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        isFocusable = true
                        isFocusableInTouchMode = true
                        requestFocus()
                        setOnTouchListener { view, event ->
                            if (event.action == MotionEvent.ACTION_DOWN) {
                                view.requestFocusFromTouch()
                            }
                            false
                        }
                        addJavascriptInterface(
                            OnlyOfficeBridge(
                                onAppReady = { Log.d(OFFICE_WEB_VIEW_TAG, "app ready") },
                                onDocumentReady = {
                                    documentReady.value = true
                                    Log.d(OFFICE_WEB_VIEW_TAG, "document ready")
                                },
                                onError = { message ->
                                    officeError.value = message
                                    Log.e(OFFICE_WEB_VIEW_TAG, "editor error: $message")
                                },
                            ),
                            "bcdAndroidOffice",
                        )
                        webChromeClient = object : WebChromeClient() {
                            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                                Log.d(
                                    OFFICE_WEB_VIEW_TAG,
                                    "${consoleMessage.messageLevel()}: ${consoleMessage.message()} " +
                                        "(${consoleMessage.sourceId()}:${consoleMessage.lineNumber()})",
                                )
                                return true
                            }
                        }
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String) {
                                view.requestFocus()
                                Log.d(OFFICE_WEB_VIEW_TAG, "page finished: $url")
                            }

                            override fun onReceivedError(
                                view: WebView,
                                request: WebResourceRequest,
                                error: WebResourceError,
                            ) {
                                Log.e(
                                    OFFICE_WEB_VIEW_TAG,
                                    "resource error ${error.errorCode}: ${error.description} ${request.url}",
                                )
                            }
                        }
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.allowFileAccess = true
                        settings.allowContentAccess = true
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        settings.loadsImagesAutomatically = true
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            settings.offscreenPreRaster = true
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            settings.forceDark = WebSettings.FORCE_DARK_OFF
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            settings.isAlgorithmicDarkeningAllowed = false
                        }
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                        loadDataWithBaseURL(documentServerUrl, html, "text/html", "UTF-8", null)
                    }
                },
                update = {},
            )
        }
        if (documentReady.value) {
            Box(modifier = Modifier.size(1.dp).testTag("onlyoffice-document-ready"))
        }
        officeError.value?.let { error ->
            Text(
                text = "OnlyOffice 加载失败: $error",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.align(Alignment.TopStart).padding(16.dp),
            )
        }
    }
}

@Composable
private fun rememberOnlyOfficeHtml(config: OfficeConfig, documentServerUrl: String): String {
    val documentServer = documentServerUrl.trimEnd('/')
    val apiUrl = "$documentServer/web-apps/apps/api/documents/api.js"
    val configJson = config.config.toString()
    return """
        <!doctype html>
        <html>
        <head>
          <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0">
          <style>
            html, body {
              margin: 0;
              width: 100vw;
              height: 100vh;
              min-height: 100vh;
              overflow: hidden;
              background: #000;
            }
            #onlyoffice-editor {
              position: fixed;
              inset: 0;
              width: 100vw;
              height: 100vh;
              min-height: 100vh;
              overflow: hidden;
              background: #000;
            }
            #onlyoffice-editor iframe {
              display: block;
              width: 100vw !important;
              height: 100vh !important;
              border: 0;
            }
            #onlyoffice-error { color: #ddd; font-family: sans-serif; padding: 16px; }
          </style>
          <script>
            function showOnlyOfficeError(message) {
              document.body.innerHTML = '<div id="onlyoffice-error">' + String(message) + '</div>';
            }
            window.setTimeout(function () {
              if (!window.bcdEditor) {
                showOnlyOfficeError('OnlyOffice loading timed out: $apiUrl');
              }
            }, 45000);
          </script>
          <script src="$apiUrl" onload="window.bcdOnlyOfficeScriptLoaded = true" onerror="showOnlyOfficeError('OnlyOffice SDK failed to load: $apiUrl')"></script>
        </head>
        <body>
          <div id="onlyoffice-editor"></div>
          <script>
            window.addEventListener('load', function () {
              try {
                if (!window.DocsAPI || !window.DocsAPI.DocEditor) {
                  showOnlyOfficeError('OnlyOffice DocsAPI is unavailable');
                  return;
                }
                var config = $configJson;
                var originalEvents = config.events || {};
                config.events = Object.assign({}, originalEvents, {
                  onAppReady: function () {
                    window.bcdOnlyOfficeAppReady = true;
                    if (window.bcdAndroidOffice) window.bcdAndroidOffice.appReady();
                    if (typeof originalEvents.onAppReady === 'function') originalEvents.onAppReady.apply(this, arguments);
                  },
                  onDocumentReady: function () {
                    window.bcdOnlyOfficeDocumentReady = true;
                    if (window.bcdAndroidOffice) window.bcdAndroidOffice.documentReady();
                    if (typeof originalEvents.onDocumentReady === 'function') originalEvents.onDocumentReady.apply(this, arguments);
                  },
                  onError: function (event) {
                    window.bcdOnlyOfficeLastError = JSON.stringify(event || {});
                    if (window.bcdAndroidOffice) window.bcdAndroidOffice.editorError(window.bcdOnlyOfficeLastError);
                    if (typeof originalEvents.onError === 'function') originalEvents.onError.apply(this, arguments);
                  }
                });
                window.bcdEditor = new window.DocsAPI.DocEditor('onlyoffice-editor', config);
                [300, 1000, 2500].forEach(function (delay) {
                  window.setTimeout(function () {
                    window.dispatchEvent(new Event('resize'));
                  }, delay);
                });
              } catch (error) {
                showOnlyOfficeError(error);
              }
            });
          </script>
        </body>
        </html>
    """.trimIndent()
}

private fun androidReachableDocumentServerUrl(url: String): String {
    val parsed = url.toHttpUrlOrNull() ?: return url.trimEnd('/')
    if (parsed.host != "localhost" && parsed.host != "127.0.0.1") {
        return parsed.toString().trimEnd('/')
    }
    return parsed.newBuilder().host("10.0.2.2").build().toString().trimEnd('/')
}

private class OnlyOfficeBridge(
    private val onAppReady: () -> Unit,
    private val onDocumentReady: () -> Unit,
    private val onError: (String) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun appReady() {
        mainHandler.post(onAppReady)
    }

    @JavascriptInterface
    fun documentReady() {
        mainHandler.post(onDocumentReady)
    }

    @JavascriptInterface
    fun editorError(message: String) {
        mainHandler.post { onError(message) }
    }
}
