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
import com.posdemo.printer.hub.PrintHubService
import com.posdemo.printer.hub.PrinterHub
import com.posdemo.printer.model.PrinterService
import com.posdemo.printer.net.LanScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var scanner: LanScanner
    private lateinit var hub: PrinterHub

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hub = PrinterHub.get(this)
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
            val hubUrl = if (localIp.isBlank()) "" else "http://$localIp:${PrinterHub.PORT}"
            return JSONObject()
                .put("localIp", localIp)
                .put("subnetPrefix", prefix.ifEmpty { "192.168.1" })
                .put("hubPort", PrinterHub.PORT)
                .put("hubUrl", hubUrl)
                .put("hubListening", hub.listening)
                .put("devices", hub.devicesJson())
                .toString()
        }

        @JavascriptInterface
        fun detectSubnet(): String = scanner.detectSubnetPrefix().orEmpty()

        @JavascriptInterface
        fun detectLocalIp(): String = scanner.detectLocalIpv4().orEmpty()

        @JavascriptInterface
        fun startScan(prefix: String, identify: Boolean) {
            lifecycleScope.launch {
                evalJs("onScanProgress('準備掃描…')")
                val hits = withContext(Dispatchers.IO) {
                    scanner.scanSubnet(prefix) { p ->
                        lifecycleScope.launch {
                            evalJs("onScanProgress('掃描中 ${p.checked}/${p.total}｜發現 ${p.found}')")
                        }
                    }
                }
                var identifyOk = 0
                hits.forEach { hit ->
                    hub.mergeHit(hit)
                    if (identify && 9100 in hit.openPorts) {
                        val body = "IP: ${hit.ip}\nMAC: ${hit.mac ?: "(n/a)"}\nPorts: ${hit.openPorts}"
                        if (hub.printIdentify(hit.ip, body)) identifyOk++
                    }
                }
                hub.save()
                evalJs(
                    "onScanDone(${hub.devicesJson()}, " +
                        "'掃描完成：發現 ${hits.size} 台｜識別列印 $identifyOk')"
                )
            }
        }

        @JavascriptInterface
        fun addManual(ip: String, name: String, serviceId: String) {
            lifecycleScope.launch {
                val hit = withContext(Dispatchers.IO) { scanner.probeIp(ip.trim()) }
                val service = PrinterService.fromId(serviceId.ifBlank { null })
                if (hit == null) {
                    hub.putManual(ip.trim(), name, service)
                    evalJs("onDevicesUpdated(${hub.devicesJson()}, '連不到埠，已先手動保存 ${ip.trim()}')")
                } else {
                    hub.mergeHit(hit, name.ifBlank { null }, service)
                    hub.save()
                    evalJs("onDevicesUpdated(${hub.devicesJson()}, '已保存 ${hit.ip}')")
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
