package com.stash.feature.settings.components

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Full-screen Discord login via WebView. Same shape as [SpotifyLoginWebView],
 * but Discord's own account token lives in `localStorage`, not a cookie — so
 * extraction is via `evaluateJavascript` on `onPageFinished`, once the WebView
 * has redirected past login to `discord.com/app` (or `/channels/@me`).
 *
 * We also patch `localStorage.removeItem` on `onPageStarted` (before any of
 * Discord's own JS runs) so a client-side token rotation can't clear it out
 * from under us between the redirect landing and our read.
 */
private const val TAG = "DiscordLogin"
private const val LOGIN_URL = "https://discord.com/login"

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscordLoginWebView(
    onTokenExtracted: (String) -> Unit,
    onDismiss: () -> Unit,
    onManualFallback: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isLoading by remember { mutableStateOf(true) }
    var tokenFound by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Sign in to Discord") },
            navigationIcon = {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            },
            actions = {
                TextButton(onClick = onManualFallback) {
                    Text("Paste token", style = MaterialTheme.typography.labelMedium)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        )

        if (isLoading) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
            )
        }

        if (tokenFound) {
            Text(
                text = "Login successful, connecting...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            AndroidView(
                factory = { context ->
                    CookieManager.getInstance().apply {
                        setAcceptCookie(true)
                        flush()
                    }

                    WebView(context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            useWideViewPort = true
                            loadWithOverviewMode = true
                            setSupportZoom(true)
                            builtInZoomControls = true
                            displayZoomControls = false
                            cacheMode = WebSettings.LOAD_NO_CACHE
                        }

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                isLoading = true
                                // Guard the token from being cleared by Discord's own
                                // client-side logic before we get a chance to read it.
                                view?.evaluateJavascript(
                                    """
                                    (function(){
                                        if (window.__stashPatchedLocal) return;
                                        window.__stashPatchedLocal = true;
                                        var _remove = window.localStorage.removeItem.bind(window.localStorage);
                                        window.localStorage.removeItem = function(k) {
                                            if (k === 'token') return true;
                                            return _remove(k);
                                        };
                                    })();
                                    """.trimIndent(),
                                    null,
                                )
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                isLoading = false
                                if (url != null && (url.startsWith("https://discord.com/app") ||
                                        url.startsWith("https://discord.com/channels"))
                                ) {
                                    view?.evaluateJavascript(
                                        "window.localStorage.getItem('token')",
                                    ) { result ->
                                        val token = result?.trim('"').orEmpty()
                                        if (token.length > 40 && token != "null") {
                                            Log.i(TAG, "Discord token extracted (length=${token.length})")
                                            tokenFound = true
                                            onTokenExtracted(token)
                                        }
                                    }
                                }
                            }

                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?,
                            ): Boolean = false
                        }

                        loadUrl(LOGIN_URL)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        HorizontalDivider()
        Surface(color = MaterialTheme.colorScheme.surface) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Sign-in blocked or not working?",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onManualFallback) {
                    Text("Paste token instead")
                }
            }
        }
    }
}