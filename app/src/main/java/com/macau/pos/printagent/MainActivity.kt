package com.macau.pos.printagent

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.macau.pos.printagent.BuildConfig
import com.macau.pos.printagent.model.PrintJobDto
import com.macau.pos.printagent.model.PrinterCfgDto
import com.macau.pos.printagent.companion.NativeCompanionServer
import com.macau.pos.printagent.net.LanScanner
import com.macau.pos.printagent.net.SdkPrinter
import com.macau.pos.printagent.net.UsbKey
import com.macau.pos.printagent.relay.RelayActivity
import com.macau.pos.printagent.relay.RelayPrefs
import com.macau.pos.printagent.relay.RelayService
import com.macau.pos.printagent.relay.RelayState
import com.macau.pos.printagent.usb.UsbController
import com.macau.pos.printagent.bt.BtController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * POS 外殼：WebView 載入 Vercel POS（BuildConfig.POS_URL），注入 PosNative
 * JS bridge 俾 POS 直接 LAN 打印（無 mixed content、無 Tunnel、斷網照印）。
 *
 * ─────────────────────────────────────────────────────────────
 * 三種環境分流的 Android 端（2026-09-18）
 * ─────────────────────────────────────────────────────────────
 * 本 APK = 用戶所講嘅「Android native app」環境。POS 網頁側會用
 * `detectPrintEnvironment()` 判斷：
 *
 *   · 純 website      → 只有 Cloud Print Relay
 *   · desktop companion → `http://127.0.0.1:9311`（Electron 殼開嘅 loopback agent）
 *   · **Android native** → `window.PosNative.*` in-process bridge（就係本檔嘅 Bridge）
 *                          ＋ `:9311` 由 [NativeCompanionServer] 提供嘅 3 個探測端點
 *
 * `NativeCompanionServer` 存在嘅唯一理由：POS 側嘅 printer wizard 會用
 * `isCompanionAvailable(true)` / `probeCompanion()` / `probeLan()` 探
 * `http://127.0.0.1:9311/api/health|config|probe-lan`。呢三個端點喺 desktop
 * 由 Electron companion 提供，喺 Android 就要由本 APK 自己補上，否則 USB
 * 掃描精靈會喺第一步就短路 return（見 docs/desktop-parity-port-plan.md §2.4②）。
 *
 * ⚠️ 佢**唔係**舊 printerhub（已刪）：無 HTML 介面、無掃描綁定、無打印旁路。
 *    只回 3 個 JSON 端點，其餘一律 404。
 */
class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var scanner: LanScanner
    private lateinit var companion: NativeCompanionServer
    private lateinit var usbController: UsbController
    private lateinit var btController: BtController

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 整個 launch 路徑用 try/catch 包住：任何非預期錯誤（WebView 初始化、
        // 非 Sunmi 機 class-load LinkageError 等）都唔可以令成個 app 彈出——
        // 改為顯示錯誤文字 + 記低 stack（tag PRINTAGENT_CRASH）等排查。
        try {
            setup()
        } catch (e: Throwable) {
            Log.e("PRINTAGENT_CRASH", "MainActivity.onCreate 失敗", e)
            CrashLog.write(this, "MainActivity.onCreate", e)
            showFatalError(e)
        }
    }

    private fun setup() {
        // docs/96：「呢部機專做中繼」→ 開機直接入中繼畫面，唔開 POS WebView。
        // 預設 false，現有當 POS 終端用嘅機行為完全唔變。
        val relayPrefs = RelayPrefs(this)
        if (relayPrefs.relayHome) {
            startActivity(Intent(this, RelayActivity::class.java))
            finish()
            return
        }
        // 已配對（即係做過中繼設定）先至起中繼服務，避免 POS 終端機白白食網絡同電。
        // runCatching：中繼服務起身失敗（例如非 Sunmi 機初始化擲錯）唔可以連累 MainActivity 彈出。
        if (relayPrefs.isPaired()) runCatching { RelayService.start(this) }

        scanner = LanScanner(this)
        usbController = UsbController(this) { evalJs(it) }
        btController = BtController(this) { evalJs(it) }
        usbController.register()

        // 與 desktop companion 同構嘅 loopback 探測端點（:9311）。
        // 起唔到（port 被佔）唔可以連累 app —— POS 側會當「Companion 未啟動」優雅降級。
        companion = NativeCompanionServer(this, scanner)
        runCatching { companion.start() }
            .onFailure { Log.w("PRINTAGENT", "NativeCompanionServer 起步失敗：${it.message}") }

        webView = WebView(this)
        setContentView(webView)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.databaseEnabled = true
        // POS 喺 HTTPS；native 要允許佢打 loopback HTTP（:9311/api/health 探測）。
        webView.settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = WebViewClient()
        webView.addJavascriptInterface(Bridge(), "PosNative")
        loadPos()

        // 權限 request 一定要擺喺 WebView 建好**之後**：requestPermissions 有可能
        // **同步**回呼 onRequestPermissionsResult → callJs → evalJs，嗰陣 webView
        // 仲係 lateinit 未初始化 → UninitializedPropertyAccessException 彈 app
        // （見 crash.txt 17:23:46）。擺喺後面個 JS callback 先收得到。
        maybeRequestNotifyPermission()
        maybeRequestBtPermissions()
    }

    /** launch 失敗嘅兜底 UI：顯示 exception 訊息 + stack，等用家睇得到、logcat 都執得到。 */
    private fun showFatalError(e: Throwable) {
        val msg = buildString {
            append("啟動失敗（app 冇彈出，但 POS 未開到）\n\n")
            append(e.message ?: e.javaClass.name)
            append("\n\n完整 stack 已寫入：Android/data/com.macau.pos.printagent/files/crash.txt")
            append("\n（用手機連電腦跑：adb pull /sdcard/Android/data/com.macau.pos.printagent/files/crash.txt .）")
            append("\n\n")
            append(Log.getStackTraceString(e))
        }
        val tv = TextView(this)
        tv.text = msg
        tv.setTextIsSelectable(true)
        tv.setPadding(32, 32, 32, 32)
        tv.textSize = 12f
        setContentView(tv)
    }

    private fun loadPos() {
        val url = BuildConfig.POS_URL.trim().ifBlank { DEFAULT_POS_URL }
        webView.loadUrl(url)
    }

    override fun onDestroy() {
        // setup() 有機會喺 lateinit 全部 assign 之前就掟嘢，呢度逐個 guard，
        // 唔可以 uninitialized access 再彈多次（掩蓋咗原本嘅 crash）。
        if (::companion.isInitialized) runCatching { companion.stop() }
        if (::usbController.isInitialized) usbController.unregister()
        if (::btController.isInitialized) btController.unregister()
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    private fun maybeRequestNotifyPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
    }

    /** Android 12+ 需要 BLUETOOTH_SCAN / BLUETOOTH_CONNECT runtime 權限先可以用藍牙打印。 */
    private fun maybeRequestBtPermissions() {
        if (Build.VERSION.SDK_INT < 31) return
        val needed = arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
        ).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) requestPermissions(needed.toTypedArray(), REQ_BT_PERMS)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_BT_PERMS) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            callJs("onBtPermissionResult", allGranted.toString())
        }
    }

    /**
     * PosNative bridge —— POS 透過 window.PosNative.* 呼叫。
     * 所有 @JavascriptInterface method 必須喺主綫程註冊嘅 class 入面。
     *
     * 呢個 bridge **就係** Android native 環境嘅「Companion 代理」本體：
     * desktop 靠 `:9311` HTTP 提供嘅查詢能力，Android 大部分直接喺呢度回。
     */
    private inner class Bridge {

        /** POS 落單後直接印。payload = { job, printer?, meta?, kind? }
         *  kind: "kitchen"（預設）/ "receipt" / "test"
         *  printer: DevicePrinterConfig 子集（id/name/connectionType/ipAddress/lanPort/paperSize/charset）
         *  呢個係主路：POS 帶齊 printer 資料。 */
        @JavascriptInterface
        fun printJob(payloadJson: String): String {
            return try {
                val root = JSONObject(payloadJson)
                val jobObj = root.getJSONObject("job")
                val job = PrintJobDto.fromJson(jobObj)
                val printer = root.optJSONObject("printer")?.let { PrinterCfgDto.fromJson(it) }
                val kind = root.optString("kind", "kitchen")
                val storeName = root.optString("storeName").takeIf { it.isNotBlank() }
                val paymentMethod = root.optString("paymentMethod").takeIf { it.isNotBlank() }
                val total = root.opt("total") as? Double
                    ?: root.optString("total").toDoubleOrNull()

                val t = printer
                    ?: return err("payload 冇 printer（請喺 POS 打印機設定揀好機再打）")

                // 預檢：畀清晰錯誤，唔使等 SDK 連線 timeout（SdkPrinter 內部會再做一次）
                when (t.connectionType) {
                    "usb" -> if (t.usbVendorId == 0 || t.usbProductId == 0) {
                        return err("USB 打印機缺少 VID/PID（請先用 listUsbPrinters 揀機並儲存）")
                    }
                    "bluetooth" -> if (t.bluetoothAddress.isNullOrBlank()) {
                        return err("藍牙打印機缺少 address（請先用 listBtPrinters / scanBtPrinters 揀機並儲存）")
                    }
                    "sunmi" -> Unit
                    else -> if (t.ipAddress.isNullOrBlank() || t.lanPort <= 0) {
                        return err("LAN 打印機缺少 IP／port（請確認 POS 打印機設定）")
                    }
                }

                // 主路：經 net.posprinter AAR 渲染 + 連線（含大字體行距修正，見 SdkPrinter）
                lifecycleScope.launch {
                    val r = SdkPrinter.print(this@MainActivity, job, t, kind, storeName, paymentMethod, total)
                    withContext(Dispatchers.Main) {
                        val m = if (r.isSuccess) ok(job.id) else err(r.exceptionOrNull()?.message ?: "打印失敗")
                        evalJs("window.__posNativePrintResult && __posNativePrintResult(${JSONObject.quote(m)})")
                    }
                }
                return okQueued(job.id, t.connectionType, t.lanPort)
            } catch (e: Exception) {
                err(e.message ?: "printJob 解析失敗")
            }
        }

        /** 測試打印：payload = { printer } */
        @JavascriptInterface
        fun testPrint(payloadJson: String): String {
            return try {
                val root = JSONObject(payloadJson)
                val printer = root.optJSONObject("printer")?.let { PrinterCfgDto.fromJson(it) }
                    ?: return err("缺 printer")

                // 預檢
                when (printer.connectionType) {
                    "usb" -> if (printer.usbVendorId == 0 || printer.usbProductId == 0) return err("USB 打印機缺少 VID/PID")
                    "bluetooth" -> if (printer.bluetoothAddress.isNullOrBlank()) return err("藍牙打印機缺少 address")
                    "sunmi" -> Unit
                    else -> if (printer.ipAddress.isNullOrBlank()) return err("缺 ipAddress")
                }

                // 主路：AAR 渲染 + 連線
                lifecycleScope.launch {
                    val r = SdkPrinter.testPrint(
                        this@MainActivity,
                        printer,
                        root.optString("storeName").takeIf { it.isNotBlank() },
                    )
                    withContext(Dispatchers.Main) {
                        val m = if (r.isSuccess) ok(printer.id) else err(r.exceptionOrNull()?.message ?: "測試打印失敗")
                        evalJs("window.__posNativePrintResult && __posNativePrintResult(${JSONObject.quote(m)})")
                    }
                }
                return okQueued(printer.id, printer.connectionType, printer.lanPort)
            } catch (e: Exception) {
                err(e.message ?: "testPrint 失敗")
            }
        }

        /** POS 健康檢查：{ ok, available, service, localIp, companionPort } */
        @JavascriptInterface
        fun getStatus(): String {
            val localIp = scanner.detectLocalIpv4().orEmpty()
            return JSONObject()
                .put("ok", true)
                .put("available", true)
                .put("service", "macau-pos-print-agent")
                .put("env", "android")
                .put("version", BuildConfig.VERSION_NAME)
                .put("localIp", localIp)
                .put("companionPort", NativeCompanionServer.PORT)
                .put("companionListening", companion.isListening())
                .toString()
        }

        /** docs/96：開「雲端列印中繼」設定／配對畫面。POS 設定頁可以加個掣 call 呢個。 */
        @JavascriptInterface
        fun openRelay() {
            runCatching { RelayService.start(this@MainActivity) }
            webView.post { startActivity(Intent(this@MainActivity, RelayActivity::class.java)) }
        }

        /**
         * docs/96：中繼狀態，POS 用嚟顯示「中繼機在線與否」。
         * `{ enabled, paired, phase, realtimeConnected, sunmiReady, printedCount, failedCount, storeId, localIp }`
         */
        @JavascriptInterface
        fun getRelayStatus(): String {
            val p = RelayPrefs(this@MainActivity)
            return JSONObject()
                .put("ok", true)
                .put("paired", p.isPaired())
                .put("phase", RelayState.phase)
                .put("realtimeConnected", RelayState.realtimeConnected)
                .put("sunmiReady", RelayState.sunmiReady)
                .put("printedCount", RelayState.printedCount)
                .put("failedCount", RelayState.failedCount)
                .put("storeId", p.storeId)
                .put("storeName", p.storeName)
                .put("localIp", RelayState.localIp ?: scanner.detectLocalIpv4().orEmpty())
                .put("lastMessage", RelayState.lastMessage)
                .toString()
        }

        /** USB：列出目前插著嘅 USB 打印機（VID/PID/label/權限 + 型號表解析結果）。 */
        @JavascriptInterface
        fun listUsbPrinters(): String = try {
            JSONObject().put("ok", true).put("printers", JSONArray(usbController.candidatesJson())).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message ?: "listUsbPrinters 失敗").toString()
        }

        /** USB：請求授權某部機（vid/pid）。已授權返 granted=true。 */
        @JavascriptInterface
        fun requestUsbPermission(vid: Int, pid: Int): String {
            val granted = usbController.requestPermission(UsbKey(vid, pid, null))
            return JSONObject().put("ok", true).put("granted", granted)
                .put("vendorId", vid).put("productId", pid).toString()
        }

        /** 藍牙：列出已配對設備。 */
        @JavascriptInterface
        fun listBtPrinters(): String = try {
            JSONObject().put("ok", true).put("printers", JSONArray(btController.bondedJson())).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message ?: "listBtPrinters 失敗").toString()
        }

        /** 藍牙：開始探索（結果經 onBtPrinterFound 回調）。需要 BLUETOOTH_SCAN（Android 12+）。 */
        @JavascriptInterface
        fun scanBtPrinters(): String {
            btController.startDiscovery()
            return JSONObject().put("ok", true).put("scanning", true).toString()
        }

        /** 藍牙：停止探索。 */
        @JavascriptInterface
        fun stopBtScan(): String {
            btController.stopDiscovery()
            return JSONObject().put("ok", true).put("scanning", false).toString()
        }

        /** 藍牙：觸發 runtime 授權（Android 12+）。結果經 onBtPermissionResult 回調。 */
        @JavascriptInterface
        fun requestBtPermission(): String {
            maybeRequestBtPermissions()
            return JSONObject().put("ok", true).put("requested", true).toString()
        }

        /**
         * LAN：探測某個 ip:port 有冇 listening。
         *
         * 對應 desktop `POST /api/probe-lan`（POS wizard「測試連接」掣）。
         * 亦經 [NativeCompanionServer] 嘅 `/api/probe-lan` 對外，兩條路共用同一實現。
         *
         * 同步回（`@JavascriptInterface` 唔可以 suspend）：用阻塞式 Socket，
         * timeout 由 [EscPosPrinter.probe] 內部控住（1.2s），唔會卡死 UI 執行緒。
         */
        @JavascriptInterface
        fun probeLan(ip: String, port: Int): String {
            val target = ip.trim()
            if (!target.matches(Regex("""\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}"""))) {
                return JSONObject().put("ok", false).put("error", "IP 格式不正確：$ip").toString()
            }
            val p = if (port > 0 && port <= 65535) port else 9100
            val reachable = companion.probeLanBlocking(target, p)
            return JSONObject().put("ok", true).put("ip", target).put("port", p)
                .put("reachable", reachable).toString()
        }
    }

    private fun ok(jobId: String) = JSONObject().put("ok", true).put("jobId", jobId).toString()
    private fun okQueued(jobId: String, ip: String, port: Int) =
        JSONObject().put("ok", true).put("queued", true).put("jobId", jobId)
            .put("ip", ip).put("port", port).toString()
    private fun err(msg: String) = JSONObject().put("ok", false).put("error", msg).toString()

    private fun evalJs(script: String) {
        // webView 係 lateinit：任何時機（例如權限結果喺 WebView 建好前同步回呼、
        // UsbController/BtController callback）都有可能未初始化。一定要 guard，
        // 唔可以 UninitializedPropertyAccessException 彈 app（見 crash.txt）。
        if (!::webView.isInitialized) return
        webView.post { webView.evaluateJavascript(script, null) }
    }

    /** 安全呼叫 WebView 全局函數（未定義唔會掟 ReferenceError）。 */
    private fun callJs(fn: String, jsonArgs: String) {
        evalJs("if (typeof window.$fn === 'function') { window.$fn($jsonArgs); }")
    }

    companion object {
        private const val DEFAULT_POS_URL = "https://macau-pos-system.vercel.app"
        private const val REQ_BT_PERMS = 0x4242
    }
}
