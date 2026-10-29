package com.macau.pos.printagent

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ScrollView
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
import kotlinx.coroutines.Job
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

    /**
     * 進行中嘅 LAN 掃描 job（v1.1.9／P3）。
     *
     * 保留一個 handle 係為咗：① 重複按「搜尋」唔會開兩條 254 個 host 嘅掃描
     * （每條 64 並發 = 128 條 socket，開兩條會拖死部機）；② 離開設定頁時
     * 可以 `stopLanScan()` 即刻收工，唔會喺背景繼續掃。
     */
    private var lanScanJob: Job? = null

    // ── v1.1.6 診斷（白屏排查）──────────────────────────────────────────
    // 背景：門店回報「裝咗 APK 開到空白、冇反應」，但同一部機瀏覽器開網頁版正常。
    // 實測下載到嘅 APK 就係 1.1.5（SHA256 一致），所以問題喺環境（網絡／WebView／JS）。
    // 舊版對「載入失敗」完全靜默：WebView 白屏、logcat 亦冇任何記錄 →
    // 現場冇人知發生咩事。以下呢組欄位將每一種失敗都變成「睇得到嘅診斷頁」。
    private var pageLoaded = false          // 主文檔成功載入（冇 main-frame error）
    private var mainFrameError = false      // 主文檔載入期間收過 error
    private var diagnosticsShown = false    // 診斷頁已經顯示（避免重複蓋頁）
    private var expectLocalPage = false     // 而家載入緊本地診斷頁（用嚟驗證 WebView 本身）
    private var localPageVerified = false   // 本地頁確認渲染成功
    private var blankCheckDone = false      // 「載入完成但內容空白」只檢查一次
    private val ui = Handler(Looper.getMainLooper())

    // ── v1.1.7 離線模式（P1）───────────────────────────────────────────
    // 線上載入失敗（斷網／DNS／TLS／伺服器掛）→ 自動切去 APK 內置嘅 web bundle。
    // 關鍵：仍然用同一個 origin（BuildConfig.POS_URL）載入，只喺 shouldInterceptRequest
    // 攔截 GET 請求交本地檔案 → localStorage / session / 打印機設定全部保留，
    // 唔會因為換 origin 而清空。
    private var offlineMode = false
    private var offlineReason = ""
    private var offlineAssetReady = false

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
        // v1.1.9（P3 驗證）：**只喺 debug build** 開 WebView 遠端除錯。
        // 用途：經 `adb forward` + Chrome DevTools Protocol 直接喺頁面執行
        // `window.PosNative.testPrint(...)`，做「唔靠登入、唔靠 UI」嘅出紙驗證
        // （門店真正嘅痛點係「斷網／未登入時印唔印得出」，唔係 UI 有冇撳到）。
        // ⚠️ release build 一定係 false：CDP 可以完全控制頁面（讀 localStorage 嘅
        //    session／打印機設定），唔應該喺出貨版開啟。`BuildConfig.DEBUG` 保證咗呢點。
        if (BuildConfig.DEBUG) {
            runCatching { WebView.setWebContentsDebuggingEnabled(true) }
        }
        // POS 喺 HTTPS；native 要允許佢打 loopback HTTP（:9311/api/health 探測）。
        webView.settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        webView.webChromeClient = object : WebChromeClient() {
            // v1.1.6：POS 任何 JS 錯誤／console 訊息都寫入 logcat（tag PRINTAGENT_WEB）。
            // 舊版完全靜默 —— 白屏時冇人知係 JS 掛咗。
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                Log.w(
                    "PRINTAGENT_WEB",
                    "[${m.messageLevel()}] ${m.message()} @ ${m.sourceId()}:${m.lineNumber()}",
                )
                return false
            }
        }
        webView.webViewClient = PosClient()
        webView.addJavascriptInterface(Bridge(), "PosNative")
        webView.addJavascriptInterface(DiagBridge(), "AndroidDiag")
        logWebViewInfo()
        loadPos()
        ui.postDelayed(loadWatchdog, ONLINE_TIMEOUT_MS)

        // 權限 request 一定要擺喺 WebView 建好**之後**：requestPermissions 有可能
        // **同步**回呼 onRequestPermissionsResult → callJs → evalJs，嗰陣 webView
        // 仲係 lateinit 未初始化 → UninitializedPropertyAccessException 彈 app
        // （見 crash.txt 17:23:46）。擺喺後面個 JS callback 先收得到。
        maybeRequestNotifyPermission()
        // 藍牙權限改為**按需**（2026-10-29）：只用 LAN 打印機嘅店，一開 app 就見兩個
        // 「附近裝置」授權框，會以為 app 想偷聽。改由精靈撳「搜尋藍牙」時才請求
        // （PosNative.listBtPrinters / scanBtPrinters 會回 needsPermission → web 再叫
        //  requestBtPermission）。
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

    // ─────────────────────────────────────────────────────────────
    // v1.1.6 診斷層
    // ─────────────────────────────────────────────────────────────
    //
    // 目標：任何一種「白屏」都唔可以再靜默。三層兜底：
    //   ① 遠端頁載入失敗（DNS／TLS／HTTP）→ 診斷頁（本地 HTML）
    //   ② 載入完成但 DOM 空白（JS 冇跑，WebView 過舊）→ 診斷頁
    //   ③ 連本地 HTML 都渲染唔到（WebView 壞／被停用）→ 原生 TextView 畫面
    //
    // ② 係今次個案嘅關鍵：POS 個 server HTML 只係一個載入外殼（淺灰底＋轉圈），
    //    所以 JS 一掛，畫面就係一片空白 —— 肉眼同「網絡失敗」分唔到。
    //    而家 DOM 探針會直接講出嚟。

    private val loadWatchdog = Runnable {
        if (pageLoaded || diagnosticsShown) return@Runnable
        if (!offlineMode) {
            // 線上失敗 → 先切 APK 內置 bundle（唔好即刻彈診斷嚇用戶）
            switchToOffline("載入超時（${ONLINE_TIMEOUT_MS / 1000} 秒）")
        } else {
            showDiagnostics(
                "離線包都載入唔到",
                "${OFFLINE_TIMEOUT_MS / 1000} 秒內未完成（APK 內置 bundle 可能損壞）",
            )
        }
    }

    /** 主文檔生命週期：線上失敗 → 離線 bundle → 診斷頁。 */
    private inner class PosClient : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = false

        /**
         * 離線模式：同一個 origin 嘅 GET 由 APK assets 回應。
         * 保持 origin 不變 = localStorage / session / 打印機設定全部保留。
         */
        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest,
        ): WebResourceResponse? {
            if (!offlineMode) return null
            if (request.method != "GET") return null
            val url = request.url
            if (!url.host.orEmpty().equals(posHost, ignoreCase = true)) return null
            return serveOffline(request, url)
        }

        override fun onPageFinished(view: WebView, url: String) {
            if (expectLocalPage) {
                // 本地診斷頁渲染完成 → 證明 WebView 本身健康，問題只喺遠端／網絡
                localPageVerified = true
                Log.i("PRINTAGENT_ENV", "本地診斷頁渲染成功（WebView 正常）")
                return
            }
            if (mainFrameError) return // 錯誤頁的 finish 唔算成功
            pageLoaded = true
            ui.removeCallbacks(loadWatchdog)
            Log.i("PRINTAGENT_ENV", "POS 載入完成：$url（離線模式=$offlineMode）")
            if (offlineMode) injectOfflineBadge()
            scheduleBlankCheck()
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError,
        ) {
            if (!request.isForMainFrame) return
            mainFrameError = true
            ui.removeCallbacks(loadWatchdog)
            val code = if (Build.VERSION.SDK_INT >= 23) error.errorCode else -1
            val desc = if (Build.VERSION.SDK_INT >= 23) error.description?.toString().orEmpty() else ""
            Log.e("PRINTAGENT_ENV", "主文檔載入失敗 code=$code desc=$desc url=${request.url}")
            if (!offlineMode) {
                switchToOffline("載入失敗 code=$code $desc")
            } else {
                showDiagnostics("頁面載入失敗", "錯誤碼 $code：$desc（${request.url}）")
            }
        }

        override fun onReceivedHttpError(
            view: WebView,
            request: WebResourceRequest,
            response: WebResourceResponse,
        ) {
            if (!request.isForMainFrame) return
            mainFrameError = true
            ui.removeCallbacks(loadWatchdog)
            if (!offlineMode) {
                switchToOffline("HTTP ${response.statusCode} ${response.reasonPhrase}")
            } else {
                showDiagnostics(
                    "伺服器回應錯誤",
                    "HTTP ${response.statusCode} ${response.reasonPhrase}（${request.url}）",
                )
            }
        }

        override fun onReceivedSslError(
            view: WebView,
            handler: android.webkit.SslErrorHandler,
            error: SslError,
        ) {
            // 憑證錯誤唔可以「照去」（安全風險）。但離線包係本地檔案、唔經 TLS，
            // 所以切去離線模式反而係安全又可用嘅補救。
            handler.cancel()
            mainFrameError = true
            ui.removeCallbacks(loadWatchdog)
            if (!offlineMode) {
                switchToOffline("HTTPS 憑證錯誤 ${error.primaryError}")
            } else {
                showDiagnostics(
                    "HTTPS 憑證錯誤",
                    "SSL error ${error.primaryError}（最常見原因：部機日期／時間唔準）",
                )
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 離線模式（P1）
    // ─────────────────────────────────────────────────────────────

    /** 切去「用 APK 內置 web bundle」載入。只會切一次。 */
    private fun switchToOffline(reason: String) {
        if (offlineMode) return
        offlineMode = true
        offlineReason = reason
        offlineAssetReady = hasOfflineBundle()
        Log.w("PRINTAGENT_ENV", "切換離線模式（bundle=$offlineAssetReady）：$reason")
        if (!offlineAssetReady) {
            showDiagnostics(
                "載入失敗（冇離線包）",
                "$reason；而且 APK 內置離線包缺失（assets/web/index.html 唔存在）",
            )
            return
        }
        pageLoaded = false
        mainFrameError = false
        blankCheckDone = false
        ui.removeCallbacks(loadWatchdog)
        loadPos() // 同一個 URL，但 shouldInterceptRequest 會交本地檔案
        ui.postDelayed(loadWatchdog, OFFLINE_TIMEOUT_MS)
    }

    private fun hasOfflineBundle(): Boolean = runCatching {
        assets.open("web/index.html").use { it.read() }
        true
    }.getOrDefault(false)

    /** 將同 origin 嘅 GET 映射去 APK 內置 assets（web 目錄）。 */
    private fun serveOffline(request: WebResourceRequest, url: Uri): WebResourceResponse? {
        val path = url.path.orEmpty()

        // API 唔攔截：斷網時 fetch 會即刻失敗，交返 app 自己嘅 localStorage fallback；
        // 有網時亦可以正常同步（唔會因為離線模式而卡死）。
        if (path.startsWith("/api/")) return null

        // Next 靜態資源（JS／CSS／字體）
        // ⚠️ 打包時放喺 `next-static/` 而唔係 `_next/`：Android 預設 ignoreAssetsPattern
        //    有 `<dir>_*`，`_` 開頭嘅目錄會被靜靜丟棄（實測 APK 只剩 246KB assets）。
        // ⚠️ 映射基準係 `/_next/static/` 而唔係 `/_next/`：bundler 將 `.next/static/*`
        //    整個複製成 `next-static/*`，所以 `/_next/static/chunks/a.js` == `next-static/chunks/a.js`。
        //    曾經寫錯成 `/_next/`，變成搵 `next-static/static/chunks/...` →
        //    所有 JS／CSS 都 404 → React 唔 mount → 只見到畫面一片空白（徽章孤零零一個）。
        if (path.startsWith("/_next/static/")) {
            val rest = path.removePrefix("/_next/static/")
            return assetResponse("web/next-static/$rest", mimeFor(path))
        }
        if (path == "/manifest.webmanifest") {
            return assetResponse("web/manifest.webmanifest", "application/manifest+json")
        }

        // Service Worker：呢個映射其實**好少機會被叫到** —— WebView 嘅 SW 腳本請求唔會經
        // shouldInterceptRequest（實測仍然報 "An unknown error occurred when fetching the script"）。
        // 真正嘅解法係 serveHtml 時注入 OFFLINE_SW_SHIM 令 register() 變 no-op；
        // 呢度保留只係兜底（換 WebView 版本後行為可能改變）。
        if (path == "/sw.js") return textResponse(OFFLINE_SW_JS, "application/javascript")

        // 導航同 RSC prefetch 才對應去預渲染檔。
        // 其餘子資源（/favicon.ico、/icon 等）如果照樣拼 `xxx.html`，只會搵唔到而誤報「缺檔」，
        // 甚至用 text/html 回應二進位資源。交返 WebView 直上網（斷網時自然失敗，無害）。
        val wantsRsc = url.query?.contains("_rsc") == true
        if (!request.isForMainFrame && !wantsRsc) return null

        // Route → 預渲染 HTML（或 RSC payload）
        val route = path.trim('/').ifEmpty { "index" }
        if (wantsRsc) {
            assetResponse("web/$route.rsc", "text/x-component")?.let { return it }
        } else {
            htmlAsset("web/$route.html")?.let { return it }
        }

        // 未打包嘅 route（會員／報表／庫存等要聯網嘅功能）→ 友善提示
        if (request.isForMainFrame) return htmlResponse(offlineNoticeHtml(path))
        return null
    }

    /**
     * 讀 APK 內嘅 HTML，並喺 `<head>` 後面插一段 shim（見 OFFLINE_SW_SHIM）。
     *
     * 為何唔用 `assetResponse` 直接串流：要改內容就無可避免要讀成字串。
     * 每頁 ~16 KB，一次性開流、即刻關，記憶體壓力可以忽略。
     */
    private fun htmlAsset(assetPath: String): WebResourceResponse? = try {
        val html = assets.open(assetPath).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val shim = "<script>$OFFLINE_SW_SHIM</script>"
        val patched = if (html.contains("<head>")) html.replaceFirst("<head>", "<head>$shim") else shim + html
        textResponse(patched, "text/html")
    } catch (e: Exception) {
        Log.w("PRINTAGENT_ENV", "離線包缺檔：$assetPath")
        null
    }

    private fun assetResponse(assetPath: String, mime: String): WebResourceResponse? = try {
        val stream = assets.open(assetPath)
        val headers = HashMap<String, String>()
        headers["Cache-Control"] = "no-store"
        val encoding = when {
            mime.startsWith("text/") -> "utf-8"
            mime == "application/javascript" -> "utf-8"
            mime == "application/json" -> "utf-8"
            mime == "application/manifest+json" -> "utf-8"
            else -> null
        }
        WebResourceResponse(mime, encoding, 200, "OK", headers, stream)
    } catch (e: Exception) {
        // 靜默失敗係最難查嘅：離線時缺一個 chunk，畫面就會「白得嚟又有少少嘢」。
        Log.w("PRINTAGENT_ENV", "離線包缺檔：$assetPath")
        null
    }

    private fun htmlResponse(html: String): WebResourceResponse = textResponse(html, "text/html")

    /** 由記憶體字串直接回應（診斷頁、提示頁、離線 SW）。 */
    private fun textResponse(body: String, mime: String): WebResourceResponse {
        val headers = HashMap<String, String>()
        headers["Cache-Control"] = "no-store"
        return WebResourceResponse(mime, "utf-8", 200, "OK", headers, body.toByteArray().inputStream())
    }

    private fun offlineNoticeHtml(path: String): String = """
        <!DOCTYPE html><html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <style>body{font-family:sans-serif;padding:24px;line-height:1.7;color:#111}
        h1{font-size:18px;margin:0 0 8px}p{color:#555;font-size:14px}
        button{font-size:15px;padding:10px 16px;border-radius:8px;border:1px solid #ccc;background:#f5f5f5;margin-top:14px}</style>
        </head><body>
        <h1>呢個功能需要網絡</h1>
        <p><b>${esc(path)}</b> 未包含喺離線包入面。</p>
        <p>點餐、打印、訂單、沽清、交班可以離線用；會員、報表、庫存需要聯網。</p>
        <p style="font-size:12px;color:#888">而家係離線模式：${esc(offlineReason)}</p>
        <button onclick="history.back()">返回</button>
        </body></html>
    """.trimIndent()

    /** 離線時喺左下角顯示一個小標記，點一下即重試上線。 */
    private fun injectOfflineBadge() {
        if (!::webView.isInitialized) return
        // ⚠️ 一定要守 `document.body`：`/` 載入完成之後 app 會即刻 `location.replace('/login')`，
        //    evaluateJavascript 落到去嘅時候 document 可能已經喺「換頁中」→ body 係 null。
        //    實測就係喺呢度拋 `Cannot read properties of null (reading 'appendChild')`，
        //    雖然只係一行紅字，但會遮住真正嘅錯誤。
        val js = """
            (function(){
              if(document.getElementById('__offline_badge'))return;
              var d=document.createElement('div');
              d.id='__offline_badge';
              d.style.cssText='position:fixed;left:10px;bottom:10px;z-index:2147483647;background:#b45309;color:#fff;font:12px sans-serif;padding:6px 12px;border-radius:999px;opacity:.92';
              d.textContent='離線模式 · 點此重試上線';
              d.onclick=function(){try{AndroidDiag.retryOnline()}catch(e){}};
              function attach(){try{if(document.body&&!document.getElementById('__offline_badge'))document.body.appendChild(d);}catch(e){}}
              if(document.body){attach();}else{document.addEventListener('DOMContentLoaded',attach);}
            })()
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private val posHost: String by lazy {
        runCatching {
            Uri.parse(BuildConfig.POS_URL.trim().ifBlank { DEFAULT_POS_URL }).host
        }.getOrNull().orEmpty()
    }

    private fun mimeFor(path: String): String = when {
        path.endsWith(".js") -> "application/javascript"
        path.endsWith(".css") -> "text/css"
        path.endsWith(".html") -> "text/html"
        path.endsWith(".json") -> "application/json"
        path.endsWith(".woff2") -> "font/woff2"
        path.endsWith(".woff") -> "font/woff"
        path.endsWith(".png") -> "image/png"
        path.endsWith(".jpg") || path.endsWith(".jpeg") -> "image/jpeg"
        path.endsWith(".svg") -> "image/svg+xml"
        path.endsWith(".ico") -> "image/x-icon"
        else -> "application/octet-stream"
    }


    /**
     * 「載入完成但畫面空白」探針 —— 本次白屏個案嘅核心檢查。
     * POS 個外殼得幾粒 DOM 元素；正常登入頁 / 收銀台會有幾百粒。
     */
    private fun scheduleBlankCheck() {
        if (blankCheckDone) return
        blankCheckDone = true
        ui.postDelayed({
            if (!::webView.isInitialized || diagnosticsShown) return@postDelayed
            webView.evaluateJavascript(PROBE_JS) { raw ->
                Log.i("PRINTAGENT_ENV", "DOM probe: $raw")
                val s = raw ?: return@evaluateJavascript
                val n = Regex("n=(\\d+)").find(s)?.groupValues?.get(1)?.toIntOrNull() ?: -1
                val len = Regex("len=(\\d+)").find(s)?.groupValues?.get(1)?.toIntOrNull() ?: -1
                if (n in 0..12 && len in 0..3) {
                    showDiagnostics(
                        "頁面載入但畫面空白",
                        "HTML 返到咗，但 JS 冇執行（DOM 元素 $n 個、可見文字 $len 字）。" +
                            "多數係部機嘅 Android System WebView 太舊，跑唔到新版 POS 嘅 JS。",
                    )
                }
            }
        }, BLANK_CHECK_MS)
    }

    /** 顯示本地診斷頁（HTML 由 APK 自己生成，唔經網絡）。 */
    private fun showDiagnostics(title: String, detail: String) {
        if (diagnosticsShown || !::webView.isInitialized) return
        diagnosticsShown = true
        Log.e("PRINTAGENT_ENV", "顯示診斷頁：$title — $detail")
        expectLocalPage = true
        runCatching {
            webView.loadDataWithBaseURL(null, buildDiagnosticsHtml(title, detail), "text/html", "utf-8", null)
        }.onFailure {
            showNativeFallback(title, detail)
            return
        }
        // 連本地頁都渲染唔到 → WebView 組件本身有問題 → 原生畫面兜底
        ui.postDelayed({
            if (!localPageVerified) {
                Log.e("PRINTAGENT_ENV", "本地頁都渲染失敗 → WebView 組件不可用")
                showNativeFallback(title, detail)
            }
        }, LOCAL_PAGE_TIMEOUT_MS)
    }

    private fun buildDiagnosticsHtml(title: String, detail: String): String {
        val sb = StringBuilder(2048)
        sb.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
        sb.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
        sb.append("<style>")
        sb.append("body{font-family:sans-serif;margin:0;padding:16px;background:#fff;color:#111;font-size:14px;line-height:1.55}")
        sb.append("h1{font-size:19px;margin:0 0 4px}.sub{color:#777;font-size:12px;margin-bottom:10px}")
        sb.append(".warn{background:#fff4e5;border:1px solid #f0b060;padding:10px 12px;border-radius:8px;margin:10px 0}")
        sb.append(".warn b{display:block;margin-bottom:3px}")
        sb.append("table{width:100%;border-collapse:collapse;font-size:12.5px}")
        sb.append("td{padding:6px 4px;border-bottom:1px solid #eee;vertical-align:top;word-break:break-all}")
        sb.append("td.k{color:#666;width:34%}")
        sb.append("button{font-size:15px;padding:10px 14px;margin:12px 8px 0 0;border-radius:8px;border:1px solid #ccc;background:#f5f5f5}")
        sb.append(".p{background:#1f6feb;color:#fff;border-color:#1f6feb}")
        sb.append("</style></head><body>")
        sb.append("<h1>POS 啟動診斷</h1>")
        sb.append("<div class=\"sub\">呢頁係 APK 內置嘅本地頁 —— 你睇到呢行字，代表 WebView 本身正常。</div>")
        sb.append("<div class=\"warn\"><b>").append(esc(title)).append("</b>").append(esc(detail)).append("</div>")
        sb.append("<table>")
        for ((k, v) in envRows()) {
            sb.append("<tr><td class=\"k\">").append(esc(k)).append("</td><td>").append(esc(v)).append("</td></tr>")
        }
        sb.append("</table>")
        sb.append("<button class=\"p\" onclick=\"Diag.retry()\">重試</button>")
        sb.append("<button onclick=\"Diag.openInBrowser()\">用瀏覽器開</button>")
        sb.append("<script>var Diag={retry:function(){try{AndroidDiag.retry()}catch(e){}},")
        sb.append("openInBrowser:function(){try{AndroidDiag.openInBrowser()}catch(e){}}};</script>")
        sb.append("</body></html>")
        return sb.toString()
    }

    /** 最後兜底：就算 WebView 完全死咗，原生 TextView 都一定睇得到。 */
    private fun showNativeFallback(title: String, detail: String) {
        val text = buildString {
            append("POS 啟動診斷（原生畫面）\n\n")
            append(title).append("\n").append(detail).append("\n\n")
            append("⚠️ 連 APK 內置嘅本地頁都渲染唔到 → 部機嘅 Android System WebView")
            append("已停用／損壞／缺失，任何網頁式方案都救唔到。\n\n")
            for ((k, v) in envRows()) append(k).append("：").append(v).append("\n")
            append("\n可以試：\n")
            append("1) 設定 → 應用 → Android System WebView → 啟用／更新\n")
            append("2) 設定 → 開發人員選項 → WebView 實作 → 揀 Chrome\n")
            append("3) 重開機後再開 app")
        }
        val tv = TextView(this).apply {
            this.text = text
            setTextIsSelectable(true)
            setPadding(32, 32, 32, 32)
            textSize = 12f
        }
        setContentView(ScrollView(this).apply { addView(tv) })
    }

    private fun logWebViewInfo() {
        val pkg = runCatching { WebView.getCurrentWebViewPackage() }.getOrNull()
        val ua = runCatching { webView.settings.userAgentString }.getOrNull().orEmpty()
        Log.i(
            "PRINTAGENT_ENV",
            "App=${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE}) " +
                "Android=${Build.VERSION.RELEASE}(API ${Build.VERSION.SDK_INT}) " +
                "機型=${Build.MANUFACTURER} ${Build.MODEL} " +
                "WebView=${pkg?.packageName}:${pkg?.versionName} " +
                "網絡=${networkSummary()}",
        )
        Log.i("PRINTAGENT_ENV", "UA=$ua")
    }

    private fun envRows(): List<Pair<String, String>> {
        val pkg = runCatching { WebView.getCurrentWebViewPackage() }.getOrNull()
        val ua = runCatching { webView.settings.userAgentString }.getOrNull().orEmpty()
        return listOf(
            "App 版本" to "${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})",
            "目標網址" to BuildConfig.POS_URL,
            "Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "機型" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "WebView 套件" to (pkg?.packageName ?: "（讀唔到）"),
            "WebView 版本" to (pkg?.versionName ?: "（讀唔到）"),
            "網絡" to networkSummary(),
            "本機 IP" to runCatching { scanner.detectLocalIpv4() }.getOrNull().orEmpty(),
            "User-Agent" to ua,
        )
    }

    private fun networkSummary(): String {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return "讀唔到"
        val n = cm.activeNetwork ?: return "冇網絡連線"
        val cap = cm.getNetworkCapabilities(n) ?: return "冇網絡連線"
        val transport = when {
            cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
            cap.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "流動網絡"
            cap.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "有線"
            else -> "其他"
        }
        val internet = cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val validated = cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        return "$transport · 有網=${if (internet) "是" else "否"} · 已驗證=${if (validated) "是" else "否"}"
    }

    private fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** 診斷頁上嘅按鈕（JS → native）。 */
    private inner class DiagBridge {
        @JavascriptInterface
        fun retry() = retryLoad()

        /** 離線標記點擊：重試線上載入（離開離線模式）。 */
        @JavascriptInterface
        fun retryOnline() = retryLoad()

        private fun retryLoad() {
            webView.post {
                resetLoadState()
                setContentView(webView)
                loadPos()
                ui.postDelayed(loadWatchdog, ONLINE_TIMEOUT_MS)
            }
        }

        @JavascriptInterface
        fun openInBrowser() {
            webView.post {
                runCatching {
                    startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse(BuildConfig.POS_URL.trim().ifBlank { DEFAULT_POS_URL }),
                        ),
                    )
                }
            }
        }
    }

    /** 重試前清乾淨所有狀態（包括離線模式，重新由線上試起）。 */
    private fun resetLoadState() {
        diagnosticsShown = false
        expectLocalPage = false
        localPageVerified = false
        pageLoaded = false
        mainFrameError = false
        blankCheckDone = false
        offlineMode = false
        offlineReason = ""
        ui.removeCallbacks(loadWatchdog)
    }

    override fun onDestroy() {
        ui.removeCallbacks(loadWatchdog)
        // v1.1.9：掃描途中關 app 一定要收工。否則 254 個 host 嘅 socket 會繼續跑，
        // 完成後仲會 callJs 落一個已銷毀嘅 WebView（evaluateJavascript 會掟）。
        runCatching { lanScanJob?.cancel() }
        lanScanJob = null
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

    /**
     * 藍牙 runtime 權限。
     *
     *  · Android 12+（API 31）：BLUETOOTH_SCAN + BLUETOOTH_CONNECT
     *  · Android 6–11（API 23–30）：探索需要 **ACCESS_FINE_LOCATION**
     *    （系統硬性要求；冇就 startDiscovery() 靜默回空結果，店員只會見到「搵唔到機」）
     *  · API < 23：唔使 runtime 權限
     */
    private fun maybeRequestBtPermissions() {
        if (Build.VERSION.SDK_INT < 23) return
        val needed: List<String> = if (Build.VERSION.SDK_INT >= 31) {
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
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
                    "bluetooth" -> {
                        if (t.bluetoothAddress.isNullOrBlank()) {
                            return err("藍牙打印機缺少 MAC（請喺打印機精靈重新揀機並儲存）")
                        }
                        if (!btController.isEnabled()) {
                            return err("藍牙未開啟，請喺通知欄／設定開啟藍牙再試")
                        }
                        if (!btController.hasConnectPermission()) {
                            return err("未獲藍牙權限，請喺精靈撳「授權」再試")
                        }
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
                    "bluetooth" -> {
                        if (printer.bluetoothAddress.isNullOrBlank()) return err("藍牙打印機缺少 MAC（請重新揀機）")
                        if (!btController.isEnabled()) return err("藍牙未開啟，請先開啟藍牙")
                        if (!btController.hasConnectPermission()) return err("未獲藍牙權限，請先授權")
                    }
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
                // 診斷用：supported / enabled / connectPermission / scanPermission / bondedCount
                // 真機裝機時一眼分得出「冇藍牙硬件」定「未開藍牙」定「未授權」。
                .put("bluetooth", runCatching { btController.stateJson() }.getOrNull())
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

        /**
         * 藍牙：列出已配對設備。
         *
         * Android 12+ 冇 BLUETOOTH_CONNECT 權限時 `bondedDevices` 會拋 SecurityException。
         * 舊版靠 try/catch 回 `ok:false`，但 web 側只當「冇機」→ 店員見到空白清單，
         * 完全唔知要授權。而家明確回 `needsPermission:true`，由精靈彈授權。
         */
        @JavascriptInterface
        fun listBtPrinters(): String = try {
            if (!btController.hasConnectPermission()) {
                // 店員撳「搜尋藍牙」就代表要用藍牙 → 即刻彈授權，唔好靜靜回空清單。
                // 授權結果經 onBtPermissionResult → wizard 會自動重試掃描。
                //
                // ⚠️ @JavascriptInterface 喺 WebView 嘅 JavaBridge 線程執行（唔係 UI 線程），
                // 而 requestPermissions 一定要主線程 → 一定要 runOnUiThread，否則可能
                // 靜靜失敗（唔彈授權框，店員只見到空清單）。
                runOnUiThread { maybeRequestBtPermissions() }
                JSONObject()
                    .put("ok", false)
                    .put("needsPermission", true)
                    .put("error", "需要藍牙權限")
                    .put("printers", JSONArray())
                    .toString()
            } else {
                JSONObject()
                    .put("ok", true)
                    .put("printers", JSONArray(btController.bondedJson()))
                    .put("bluetooth", btController.stateJson())
                    .toString()
            }
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message ?: "listBtPrinters 失敗").toString()
        }

        /**
         * 藍牙：喺 app 內配對（createBond）。結果經 `onBtPairResult` 回調。
         *
         * POS 一體機嘅系統設定好多時被廠商鎖住 → 唔可以逼店員跳出去配對。
         */
        @JavascriptInterface
        fun pairBtPrinter(address: String): String {
            btController.pair(address)
            return JSONObject().put("ok", true).put("pairing", true).toString()
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
            // JS bridge 唔喺 UI 線程 → requestPermissions 一定要轉主線程
            runOnUiThread { maybeRequestBtPermissions() }
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

        /**
         * LAN：掃描整個網段（1–254），列出有開 9100／631／80 嘅主機。
         *
         * ─────────────────────────────────────────────────────────────
         * 為何要（2026-10-29 · P3「打印脫離 Sunmi」）
         * ─────────────────────────────────────────────────────────────
         * 換走 Sunmi 一體機之後，門店用嘅係普通 Android 平板／盒仔 ＋ **網絡打印機**。
         * 舊版 PosNative 只匯出 `probeLan(ip, port)` —— 即係店員要**自己知打印機 IP**
         * 先裝得到機。而打印機 IP 通常係 DHCP 派、機身又冇顯示，結果就係「裝唔到」。
         *
         * [LanScanner] 早就寫好（64 並發、讀 ARP 攞 MAC、帶進度），但一直只經
         * [NativeCompanionServer] 嘅桌面端點用，Android 側從來冇匯出過。
         * 呢個方法就係把它接上 `window.PosNative`。
         *
         * 非同步：掃 254 個 host 要 2–4 秒，唔可以阻塞 JS 執行緒（`@JavascriptInterface`
         * 係喺 WebView 嘅 JS thread 上跑）。所以立即回 `{ok,scanning,subnet}`，
         * 進度／結果經回呼返：
         *   · `window.__onLanScanProgress({checked,total,found,subnet})`
         *   · `window.__onLanScanResult({ok,subnet,hits:[{ip,mac,openPorts,hostname}]})`
         *
         * ⚠️ 進度回呼**節流**：LanScanner 每個 host 都 call 一次（254 次），
         * 每次都 evaluateJavascript 會令 WebView 忙住跑 JS，反而拖慢掃描。
         * 所以每 10 個 host（或搵到新機）才報一次。
         */
        @JavascriptInterface
        fun scanLanPrinters(): String {
            if (lanScanJob?.isActive == true) {
                return JSONObject().put("ok", true).put("scanning", true).put("already", true).toString()
            }
            val prefix = runCatching { scanner.detectSubnetPrefix() }.getOrNull()
                ?: return JSONObject().put("ok", false)
                    .put("error", "拎唔到本機 IP —— 請確認已連上同一個 Wi-Fi／網線").toString()

            lanScanJob = lifecycleScope.launch {
                var lastReportedChecked = 0
                var lastReportedFound = -1
                try {
                    val hits = scanner.scanSubnet(prefix) { p ->
                        if (p.checked - lastReportedChecked >= 10 || p.found != lastReportedFound) {
                            lastReportedChecked = p.checked
                            lastReportedFound = p.found
                            callJs(
                                "__onLanScanProgress",
                                JSONObject()
                                    .put("checked", p.checked)
                                    .put("total", p.total)
                                    .put("found", p.found)
                                    .put("subnet", p.subnetPrefix)
                                    .toString(),
                            )
                        }
                    }
                    val arr = JSONArray()
                    for (h in hits) {
                        val ports = JSONArray()
                        for (pt in h.openPorts) ports.put(pt)
                        arr.put(
                            JSONObject()
                                .put("ip", h.ip)
                                .put("mac", h.mac ?: "")
                                .put("openPorts", ports)
                                .put("hostname", h.hostname ?: ""),
                        )
                    }
                    callJs(
                        "__onLanScanResult",
                        JSONObject().put("ok", true).put("subnet", prefix).put("hits", arr).toString(),
                    )
                } catch (e: Throwable) {
                    // CancellationException 都要走到呢度：UI 收到 ok=false 就會停轉圈。
                    callJs(
                        "__onLanScanResult",
                        JSONObject()
                            .put("ok", false)
                            .put("subnet", prefix)
                            .put("error", e.message ?: "掃描中斷")
                            .put("hits", JSONArray())
                            .toString(),
                    )
                } finally {
                    lanScanJob = null
                }
            }
            return JSONObject().put("ok", true).put("scanning", true).put("subnet", prefix).toString()
        }

        /** LAN：停止掃描（離開設定頁／重開精靈時叫）。 */
        @JavascriptInterface
        fun stopLanScan(): String {
            lanScanJob?.cancel()
            lanScanJob = null
            return JSONObject().put("ok", true).put("scanning", false).toString()
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
        // runCatching：WebView 已 destroy（例如掃描途中有結果返嚟）時
        // evaluateJavascript 會掟 IllegalStateException，唔可以令 app 彈。
        webView.post { runCatching { webView.evaluateJavascript(script, null) } }
    }

    /** 安全呼叫 WebView 全局函數（未定義唔會掟 ReferenceError）。 */
    private fun callJs(fn: String, jsonArgs: String) {
        evalJs("if (typeof window.$fn === 'function') { window.$fn($jsonArgs); }")
    }

    companion object {
        private const val DEFAULT_POS_URL = "https://macau-pos-system.vercel.app"
        private const val REQ_BT_PERMS = 0x4242

        // v1.1.6／1.1.7 載入時序
        private const val ONLINE_TIMEOUT_MS = 15_000L    // 線上 15 秒未完成 → 切離線 bundle
        private const val OFFLINE_TIMEOUT_MS = 20_000L   // 離線包 20 秒未完成 → 診斷頁
        private const val BLANK_CHECK_MS = 12_000L       // 載入完成後 12 秒探 DOM（JS 有冇跑）
        private const val LOCAL_PAGE_TIMEOUT_MS = 6_000L // 本地頁 6 秒都唔出 → 原生兜底

        /** 探針：回傳 DOM 元素數、可見文字長度、readyState、目前 URL。 */
        private const val PROBE_JS =
            "(function(){try{var b=document.body;var n=document.querySelectorAll('*').length;" +
                "var t=b&&b.innerText?b.innerText.replace(/\\s+/g,' '):'';" +
                "return 'n='+n+'|len='+t.length+'|rs='+document.readyState+'|u='+location.href" +
                "}catch(e){return 'n=-1|len=-1|err='+e.message}})()"

        /**
         * 離線模式專用 Service Worker 本體：只接管生命週期，**完全唔做快取**。
         * ⚠️ 只係兜底 —— 實測 WebView 唔會經 shouldInterceptRequest 攞 SW 腳本，
         *    真正生效嘅係下面嘅 OFFLINE_SW_SHIM。
         *
         * 為何唔直接用網站原著嘅 `sw.js`：
         *   ① 斷網時註冊唔到 → 每次冷啟都拋一個 promise rejection（雖然 app 自己 catch 咗，
         *      但 log 滿屏紅字，真出問題時好難分辨）；
         *   ② 原著 sw.js 會 cache `/_next/static` 嘅回應，而離線時呢啲回應嚟自 APK 內置
         *      bundle。一旦換新版 APK，舊 SW cache 仍然會餵**舊 chunk** →
         *      就會重現今日呢個「白屏」個案（而且更難查，因為連網絡層都冇報錯）。
         * 離線包本身已經係最強嘅 cache，唔需要 SW 再加一層。
         */
        private const val OFFLINE_SW_JS =
            "self.addEventListener('install',function(){self.skipWaiting();});" +
                "self.addEventListener('activate',function(e){e.waitUntil(self.clients.claim());});"

        /**
         * 注入去每頁 `<head>` 嘅 shim：令 `navigator.serviceWorker.register()` 即刻成功但**乜都唔做**。
         *
         * 為何要咁做：
         *   ① WebView 嘅 SW 腳本請求唔會經 `shouldInterceptRequest`，所以離線時
         *      `pwa-register.tsx` 嗰句 `void register()` 一定拋 promise rejection
         *      （"An unknown error occurred when fetching the script"）——
         *      app 自己 catch 咗、畫面照用，但每頁冷啟都多兩行紅字，真出事好難睇。
         *   ② 更重要：萬一將來真的註冊成功，`sw.js` 會 cache `/_next/static` 嘅回應，
         *      而離線時嗰啲回應嚟自 APK bundle。換新版 APK 之後舊 SW cache 會餵**舊 chunk**
         *      → 就係今日呢個「白屏」個案，而且連網絡層都唔會報錯，極難查。
         *
         * 副作用可控：`pwa-register` 之後用 `getRegistration()` 查更新，返 null →
         * `reg?.update()` 自然 no-op，唔會卡住。線上有網時走正常網絡，唔受影響。
         */
        private const val OFFLINE_SW_SHIM =
            "(function(){try{var sw=navigator.serviceWorker;if(!sw)return;" +
                "sw.register=function(){return Promise.resolve({scope:location.origin+'/'," +
                "active:null,installing:null,waiting:null," +
                "update:function(){return Promise.resolve();}," +
                "unregister:function(){return Promise.resolve(true);}," +
                "addEventListener:function(){},removeEventListener:function(){}});};}catch(e){}})();"
    }
}
