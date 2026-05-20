package com.example

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val webView = WebView(this)
        webView.settings.javaScriptEnabled = true
        webView.webViewClient = WebViewClient()
        webView.addJavascriptInterface(WebAppInterface(this), "app")
        webView.loadUrl("file:///android_asset/index.html")
        setContentView(webView)
    }
}

class WebAppInterface(private val activity: ComponentActivity) {
    @JavascriptInterface
    fun LaunchApp(packageName: String) {
        val launchIntent = activity.packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent != null) {
            activity.startActivity(launchIntent)
        }
    }
    
    @JavascriptInterface
    fun OpenUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        activity.startActivity(intent)
    }

    @JavascriptInterface
    fun MakeCall(number: String) {
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))
        activity.startActivity(intent)
    }
}
