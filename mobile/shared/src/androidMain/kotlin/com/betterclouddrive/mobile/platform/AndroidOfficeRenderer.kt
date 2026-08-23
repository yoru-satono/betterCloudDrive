package com.betterclouddrive.mobile.platform

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.betterclouddrive.mobile.core.model.OfficeConfig
import com.betterclouddrive.mobile.core.platform.OfficeRenderer
import com.betterclouddrive.mobile.core.localization.MobileString
import com.betterclouddrive.mobile.core.localization.UiMessage
import kotlinx.serialization.json.JsonObject

internal class AndroidOfficeRenderer : OfficeRenderer {
    @SuppressLint("SetJavaScriptEnabled")
    @Composable
    override fun Render(config: OfficeConfig, modifier: Modifier, onStatus: (UiMessage) -> Unit) {
        val server = androidReachableUrl(config.documentServerUrl)
        val html = officeHtml(server, config.config.toString().replace("<", "\\u003c"))
        AndroidView(
            modifier = modifier,
            factory = { context ->
                WebView(context).apply {
                    webChromeClient = WebChromeClient()
                    webViewClient = WebViewClient()
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    addJavascriptInterface(OfficeBridge { message -> post { onStatus(message) } }, "bcdKmpOffice")
                    loadDataWithBaseURL(server, html, "text/html", "UTF-8", null)
                }
            },
            update = { if (it.url == null) it.loadDataWithBaseURL(server, html, "text/html", "UTF-8", null) },
            onRelease = { webView ->
                webView.removeJavascriptInterface("bcdKmpOffice")
                webView.stopLoading()
                webView.destroy()
            },
        )
    }
}

private fun androidReachableUrl(input: String): String {
    val normalized = input.trimEnd('/')
        .replace("http://localhost", "http://10.0.2.2")
        .replace("http://127.0.0.1", "http://10.0.2.2")
    val uri = Uri.parse(normalized)
    require(uri.scheme == "http" || uri.scheme == "https") { "office.invalidScheme" }
    require(!uri.host.isNullOrBlank()) { "office.hostMissing" }
    return normalized
}

private fun officeHtml(server: String, configJson: String): String = """
    <!doctype html>
    <html><head>
      <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0">
      <style>html,body,#editor{margin:0;width:100%;height:100%;overflow:hidden}</style>
      <script src="$server/web-apps/apps/api/documents/api.js"></script>
    </head><body><div id="editor"></div><script>
      try {
        var config = $configJson;
        var events = config.events || {};
        config.events = Object.assign({}, events, {
          onAppReady: function () { bcdKmpOffice.appReady(); },
          onDocumentReady: function () { bcdKmpOffice.documentReady(); },
          onError: function (event) { bcdKmpOffice.error(JSON.stringify(event || {})); }
        });
        window.bcdEditor = new DocsAPI.DocEditor('editor', config);
      } catch (error) {
        document.body.innerText = String(error);
        bcdKmpOffice.error(String(error));
      }
    </script></body></html>
""".trimIndent()

private class OfficeBridge(private val onStatus: (UiMessage) -> Unit) {
    @JavascriptInterface fun appReady() = onStatus(UiMessage(MobileString.OfficeAppReady))
    @JavascriptInterface fun documentReady() = onStatus(UiMessage(MobileString.OfficeDocumentReady))
    @JavascriptInterface fun error(message: String) = onStatus(UiMessage(MobileString.OfficeError, listOf(message)))
}
