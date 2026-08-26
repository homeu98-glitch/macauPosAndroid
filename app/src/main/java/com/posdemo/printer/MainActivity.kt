package com.posdemo.printer

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.posdemo.printer.data.HubAuth
import com.posdemo.printer.hub.PairQr
import com.posdemo.printer.hub.PrintHubService
import com.posdemo.printer.hub.PrinterHub
import com.posdemo.printer.net.LanScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var scanner: LanScanner
    private lateinit var hub: PrinterHub
    private lateinit var auth: HubAuth

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hub = PrinterHub.get(this)
        auth = HubAuth(this)
        scanner = LanScanner(this)
        maybeRequestNotifyPermission()
        PrintHubService.start(this)

        webView = WebView(this)
        setContentView(webView)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = WebViewClient()
        webView.addJavascriptInterface(Bridge(), "PosNative")
        webView.loadUrl("file:///android_asset/index.html")

        hub.webCommandListener = PrinterHub.WebCommandListener { serviceId, label, message, printed ->
            evalJs(
                "onWebCommand(${JSONObject.quote(serviceId)}, ${JSONObject.quote(label)}, " +
                    "${JSONObject.quote(message)}, $printed)"
            )
        }
    }

    override fun onDestroy() {
        hub.webCommandListener = null
        super.onDestroy()
    }

    private fun maybeRequestNotifyPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
    }

    private inner class Bridge {
        @JavascriptInterface
        fun getBootstrapJson(): String {
            val localIp = scanner.detectLocalIpv4().orEmpty()
            val prefix = scanner.detectSubnetPrefix().orEmpty()
            val token = auth.token()
            val hubUrl = if (localIp.isBlank()) "" else "http://$localIp:${PrinterHub.PORT}"
            val posUrl = if (hubUrl.isBlank()) "" else "$hubUrl/pos?token=$token"
            return JSONObject()
                .put("localIp", localIp)
                .put("subnetPrefix", prefix.ifEmpty { "192.168.1" })
                .put("hubPort", PrinterHub.PORT)
                .put("hubUrl", hubUrl)
                .put("posUrl", posUrl)
                .put("hubToken", token)
                .put("hubListening", hub.listening)
                .put("devices", hub.devicesJson())
                .toString()
        }

        @JavascriptInterface
        fun getPairQrDataUrl(): String {
            val ip = scanner.detectLocalIpv4().orEmpty()
            if (ip.isBlank()) return ""
            return PairQr.dataUrl(hub.posUrl(auth.token()))
        }

        @JavascriptInterface
        fun detectSubnet(): String = scanner.detectSubnetPrefix().orEmpty()

        @JavascriptInterface
        fun detectLocalIp(): String = scanner.detectLocalIpv4().orEmpty()

        @JavascriptInterface
        fun startScan(prefix: String, identify: Boolean) {
            hub.requestScan(prefix, identify)
        }

        @JavascriptInterface
        fun addManual(ip: String, name: String, serviceId: String) {
            lifecycleScope.launch(Dispatchers.IO) {
                hub.addManualBlocking(ip, name, serviceId)
                withContext(Dispatchers.Main) {
                    evalJs("onDevicesUpdated(${hub.devicesJson()}, '已保存')")
                }
            }
        }

        @JavascriptInterface
        fun assignService(key: String, serviceId: String) {
            hub.assignService(key, serviceId)
            lifecycleScope.launch {
                evalJs("onDevicesUpdated(${hub.devicesJson()}, '已綁定服務')")
            }
        }

        @JavascriptInterface
        fun removeDevice(key: String) {
            hub.removeDevice(key)
            lifecycleScope.launch {
                evalJs("onDevicesUpdated(${hub.devicesJson()}, '已移除')")
            }
        }

        @JavascriptInterface
        fun clearAll() {
            hub.clearAll()
            lifecycleScope.launch {
                evalJs("onDevicesUpdated(${hub.devicesJson()}, '已清除全部')")
            }
        }

        @JavascriptInterface
        fun printService(serviceId: String, message: String) {
            lifecycleScope.launch {
                val result = hub.printService(serviceId, message)
                result.logs.forEach { log ->
                    evalJs("onPrintLog('${escapeJs(log.text)}', ${log.warn})")
                }
            }
        }
    }

    private fun escapeJs(s: String): String =
        s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ")

    private fun evalJs(script: String) {
        webView.post { webView.evaluateJavascript(script, null) }
    }
}
