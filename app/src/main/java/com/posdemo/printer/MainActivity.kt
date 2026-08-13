package com.posdemo.printer

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.posdemo.printer.data.DeviceStore
import com.posdemo.printer.model.PrinterDevice
import com.posdemo.printer.model.PrinterService
import com.posdemo.printer.net.EscPosPrinter
import com.posdemo.printer.net.LanScanner
import com.posdemo.printer.net.ScanHit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var store: DeviceStore
    private lateinit var scanner: LanScanner
    private val printer = EscPosPrinter()
    private val devices = LinkedHashMap<String, PrinterDevice>()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = DeviceStore(this)
        scanner = LanScanner(this)
        devices.putAll(store.load())

        webView = WebView(this)
        setContentView(webView)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = WebViewClient()
        webView.addJavascriptInterface(Bridge(), "PosNative")
        webView.loadUrl("file:///android_asset/index.html")
    }

    private inner class Bridge {
        @JavascriptInterface
        fun getBootstrapJson(): String {
            val localIp = scanner.detectLocalIpv4().orEmpty()
            val prefix = scanner.detectSubnetPrefix().orEmpty()
            return JSONObject()
                .put("localIp", localIp)
                .put("subnetPrefix", prefix.ifEmpty { "192.168.1" })
                .put("devices", devicesToJson())
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
                    mergeHit(hit)
                    if (identify && 9100 in hit.openPorts) {
                        val body = "IP: ${hit.ip}\nMAC: ${hit.mac ?: "(n/a)"}\nPorts: ${hit.openPorts}"
                        if (printer.printTextTicket(hit.ip, "DEVICE ID", body).isSuccess) identifyOk++
                    }
                }
                store.save(devices)
                evalJs(
                    "onScanDone(${devicesToJson()}, " +
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
                    val key = "ip:${ip.trim()}"
                    devices[key] = PrinterDevice(
                        key = key,
                        name = name.ifBlank { "手動-$ip" },
                        ip = ip.trim(),
                        mac = null,
                        openPorts = emptyList(),
                        service = service,
                    )
                    store.save(devices)
                    evalJs("onDevicesUpdated(${devicesToJson()}, '連不到埠，已先手動保存 $ip')")
                } else {
                    mergeHit(hit, name.ifBlank { null }, service)
                    store.save(devices)
                    evalJs("onDevicesUpdated(${devicesToJson()}, '已保存 ${hit.ip}')")
                }
            }
        }

        @JavascriptInterface
        fun assignService(key: String, serviceId: String) {
            val d = devices[key] ?: return
            devices[key] = d.copy(
                service = PrinterService.fromId(serviceId.ifBlank { null }),
                lastSeen = System.currentTimeMillis(),
            )
            store.save(devices)
            lifecycleScope.launch {
                evalJs("onDevicesUpdated(${devicesToJson()}, '已綁定服務')")
            }
        }

        @JavascriptInterface
        fun removeDevice(key: String) {
            devices.remove(key)
            store.save(devices)
            lifecycleScope.launch {
                evalJs("onDevicesUpdated(${devicesToJson()}, '已移除')")
            }
        }

        @JavascriptInterface
        fun clearAll() {
            devices.clear()
            store.clear()
            lifecycleScope.launch {
                evalJs("onDevicesUpdated(${devicesToJson()}, '已清除全部')")
            }
        }

        @JavascriptInterface
        fun printService(serviceId: String, message: String) {
            val service = PrinterService.fromId(serviceId) ?: return
            val targets = devices.values.filter { it.service == service }
            lifecycleScope.launch {
                if (targets.isEmpty()) {
                    evalJs("onPrintLog('沒有綁定「${service.label}」的設備', true)")
                    return@launch
                }
                var ok = 0
                targets.forEach { d ->
                    if (!d.canRawPrint) {
                        evalJs("onPrintLog('略過 ${d.ip}：無 9100', true)")
                        return@forEach
                    }
                    val body = "Service: ${service.label}\nIP: ${d.ip}\nMAC: ${d.mac ?: "-"}\n\n$message\n"
                    val r = printer.printTextTicket(d.ip, service.label, body)
                    if (r.isSuccess) {
                        ok++
                        evalJs("onPrintLog('已列印 → ${service.label} ${d.ip}', false)")
                    } else {
                        evalJs(
                            "onPrintLog('列印失敗 ${d.ip}: ${
                                (r.exceptionOrNull()?.message ?: "error").replace("'", "")
                            }', true)"
                        )
                    }
                }
                evalJs("onPrintLog('「${service.label}」成功 $ok / ${targets.size}', false)")
            }
        }
    }

    private fun mergeHit(
        hit: ScanHit,
        preferredName: String? = null,
        serviceOverride: PrinterService? = null,
    ) {
        val mac = hit.mac?.uppercase()
        val key = mac?.let { "mac:$it" } ?: "ip:${hit.ip}"
        val oldByIp = devices["ip:${hit.ip}"]
        val existing = devices[key] ?: oldByIp
        if (oldByIp != null && key.startsWith("mac:")) devices.remove("ip:${hit.ip}")
        val name = preferredName
            ?: existing?.name
            ?: hit.hostname
            ?: when {
                9100 in hit.openPorts -> "Raw9100-${hit.ip.substringAfterLast('.')}"
                631 in hit.openPorts -> "IPP-${hit.ip.substringAfterLast('.')}"
                else -> "HTTP-${hit.ip.substringAfterLast('.')}"
            }
        devices[key] = PrinterDevice(
            key = key,
            name = name,
            ip = hit.ip,
            mac = mac ?: existing?.mac,
            openPorts = hit.openPorts,
            service = serviceOverride ?: existing?.service,
            lastSeen = System.currentTimeMillis(),
        )
    }

    private fun devicesToJson(): JSONArray {
        val arr = JSONArray()
        devices.values.sortedByDescending { it.lastSeen }.forEach { d ->
            arr.put(
                JSONObject()
                    .put("key", d.key)
                    .put("name", d.name)
                    .put("ip", d.ip)
                    .put("mac", d.mac ?: "")
                    .put("openPorts", JSONArray(d.openPorts))
                    .put("service", d.service?.id ?: "")
                    .put("canRawPrint", d.canRawPrint)
            )
        }
        return arr
    }

    private fun evalJs(script: String) {
        webView.post { webView.evaluateJavascript(script, null) }
    }
}
