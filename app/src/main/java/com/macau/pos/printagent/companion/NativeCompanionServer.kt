package com.macau.pos.printagent.companion

import android.content.Context
import android.util.Log
import com.macau.pos.printagent.BuildConfig
import com.macau.pos.printagent.net.LanScanner
import com.macau.pos.printagent.net.EscPosPrinter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * **Android 版 Companion 代理** —— 只綁 loopback 嘅極簡 HTTP 伺服器（`:9311`）。
 *
 * ─────────────────────────────────────────────────────────────
 * 點解要有呢個檔案？
 * ─────────────────────────────────────────────────────────────
 * POS 網頁側嘅打印機精靈（`printer-wizard-modal.tsx`）第一步係：
 *
 * ```ts
 * const available = await isCompanionAvailable(true);   // → GET /api/health
 * if (!available) { setUsbScanning(false); return; }     // ← 直接短路
 * const candidates = await enumerateCompanionUsbPrinters();
 * ```
 *
 * 呢個「有冇 Companion」嘅閘，喺 desktop 由 Electron companion 嘅 loopback
 * 伺服器回答。Android 上冇咗呢個伺服器，`isCompanionAvailable(true)` 永遠 false
 * → **USB 掃描精靈喺第一步就 return，`enumerateCompanionUsbPrinters()` 永遠唔會被叫**。
 * 呢個就係本檔存在嘅唯一原因。
 *
 * ─────────────────────────────────────────────────────────────
 * 與 desktop `companion-server.mjs` 嘅對應關係
 * ─────────────────────────────────────────────────────────────
 * | desktop 端點 | 本檔實現 | 說明 |
 * |---|---|---|
 * | `GET  /api/health`    | ✅ 有 | `isCompanionAvailable()` / `probeCompanion()` 用 |
 * | `GET  /api/config`    | ✅ 有 | `tryAutoPairCompanion()` 自動配對用 |
 * | `POST /api/probe-lan` | ✅ 有 | wizard「測試連接」掣用 |
 * | `GET  /api/discover`  | ❌ 404 | Android 唔行 mDNS；LAN 用 IP 直接新增（見 D3） |
 * | `GET  /api/usb`       | ❌ 404 | Android 走 `window.PosNative.listUsbPrinters()` |
 * | `GET  /api/bluetooth` | ❌ 404 | Android 走 `window.PosNative.listBtPrinters()` |
 * | `GET  /api/printers`  | ❌ 404 | 同上 |
 * | `POST /api/print`     | ❌ 404 | Android 走 `window.PosNative.printJob()` |
 *
 * **呢個係「只輸出 native 邏輯」嘅技術實現**：凡 desktop 靠 HTTP 做嘅嘢，
 * Android 一律改行 in-process bridge；HTTP 只保留「POS 側硬性要求探 loopback」
 * 嗰 3 個探測端點。
 *
 * ─────────────────────────────────────────────────────────────
 * ⚠️ 同已刪嘅舊 printerhub（`:8787`）完全唔同
 * ─────────────────────────────────────────────────────────────
 * | | 舊 printerhub（已刪） | 本檔 |
 * |---|---|---|
 * | 埠 | `:8787` | **`:9311`**（同 desktop companion） |
 * | 綁定 | LAN 廣播（同網段任何機都連到） | **只綁 `127.0.0.1`** |
 * | 介面 | `setup.html` / `index.html` / `remote.html` | **零 HTML，`GET /` 回 404** |
 * | 端點 | 掃描 + 綁定 + 裝置清單 + 打印旁路 | **只有 3 個探測端點** |
 * | 渲染 | `EscPosPrinter.buildTicket`（旁路、不同源） | **完全不渲染** |
 * | 打印 | 自己打（用錯嘅放大語意） | **一句都唔打，打印一律交返 PosNative bridge** |
 */
class NativeCompanionServer(
    context: Context,
    private val scanner: LanScanner,
) {

    private val app = context.applicationContext
    private val running = AtomicBoolean(false)
    private var server: ServerSocket? = null
    private var worker: Thread? = null

    fun isListening(): Boolean = running.get() && server?.isClosed == false

    /** 起伺服器。port 被佔／任何 IO 錯誤 → 回 false（唔會 throw，唔可以連累 app 啟動）。 */
    fun start(): Boolean {
        if (running.get()) return true
        return try {
            // 只綁 loopback：同 desktop companion-server.mjs 一致。
            // 呢個係**安全邊界** —— 同一個 LAN 嘅其他機（包括訪客 Wi-Fi）連唔到。
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(LOOPBACK, PORT), 8)
            server = ss
            running.set(true)
            worker = thread(name = "native-companion-server", isDaemon = true) {
                acceptLoop(ss)
            }
            Log.i(TAG, "NativeCompanionServer listening on http://$LOOPBACK:$PORT")
            true
        } catch (e: Exception) {
            Log.w(TAG, "NativeCompanionServer 起步失敗：${e.message}")
            running.set(false)
            runCatching { server?.close() }
            server = null
            false
        }
    }

    fun stop() {
        running.set(false)
        runCatching { server?.close() }
        server = null
        worker = null
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running.get()) {
            val sock = try {
                ss.accept()
            } catch (e: SocketException) {
                break // stop() 關咗 server → 正常退出
            } catch (e: Exception) {
                Log.w(TAG, "accept 失敗：${e.message}")
                continue
            }
            runCatching { handle(sock) }
        }
    }

    private fun handle(sock: Socket) {
        sock.use { s ->
            s.soTimeout = READ_TIMEOUT_MS
            val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))

            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val path = parts[1].substringBefore('?')

            // headers
            var contentLength = 0
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                }
            }

            // body（只喺需要時讀，避免阻塞）
            val body = if (contentLength > 0) {
                val buf = CharArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = reader.read(buf, read, contentLength - read)
                    if (n < 0) break
                    read += n
                }
                String(buf, 0, read)
            } else ""

            val (status, payload) = route(method, path, body)
            respond(s, status, payload)
        }
    }

    /** 路由：只認 3 個端點，其餘一律 404（包括 `GET /`）。 */
    private fun route(method: String, path: String, body: String): Pair<Int, String> = when {
        path == "/api/health" && method == "GET" -> 200 to healthJson()
        path == "/api/config" && method == "GET" -> 200 to configJson()
        path == "/api/probe-lan" && method == "POST" -> 200 to probeLanJson(body)
        else -> 404 to JSONObject()
            .put("ok", false)
            .put("error", "not found")
            .put("note", "Android native agent 只提供 /api/health、/api/config、/api/probe-lan")
            .toString()
    }

    /** 與 desktop `/api/health` 同形狀：`{ ok: true, version }`。 */
    private fun healthJson(): String = JSONObject()
        .put("ok", true)
        .put("version", BuildConfig.VERSION_NAME)
        .put("runtime", "android")
        .toString()

    /**
     * 與 desktop `/api/config` 同形狀：`{ companionUrl, posUrl, tokenEnabled }`。
     *
     * `companionUrl` 必須係 `http://127.0.0.1:9311` —— POS 側
     * `tryAutoPairCompanion()` 會將佢寫入 `localStorage["macau-pos-companion-url"]`。
     */
    private fun configJson(): String = JSONObject()
        .put("companionUrl", "http://$LOOPBACK:$PORT")
        .put("posUrl", BuildConfig.POS_URL)
        .put("tokenEnabled", false)
        .toString()

    /** 與 desktop `POST /api/probe-lan` 同形狀。body = `{ ip, port? }`。 */
    private fun probeLanJson(body: String): String {
        val ip = try {
            JSONObject(body).optString("ip").trim()
        } catch (e: Exception) {
            ""
        }
        if (!ip.matches(Regex("""\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}"""))) {
            return JSONObject().put("ok", false).put("error", "IP 格式不正確：$ip").toString()
        }
        val port = try {
            JSONObject(body).optInt("port", 9100).let { if (it in 1..65535) it else 9100 }
        } catch (e: Exception) {
            9100
        }
        val reachable = probeLanBlocking(ip, port)
        return JSONObject().put("ok", true).put("ip", ip).put("port", port)
            .put("reachable", reachable).toString()
    }

    /**
     * 阻塞式 TCP 探測 —— 共用實現（PosNative bridge 同 `/api/probe-lan` 都行呢度）。
     * timeout 1.2s，同 `LanScanner.probeIp` 預設一致。
     */
    fun probeLanBlocking(ip: String, port: Int): Boolean = try {
        Socket().use { s ->
            s.connect(InetSocketAddress(ip, port), PROBE_TIMEOUT_MS)
            true
        }
    } catch (e: Exception) {
        false
    }

    private fun respond(sock: Socket, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 $status ${if (status == 200) "OK" else "Not Found"}\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            // 同 desktop 一致：容許從 HTTPS 網頁 fetch loopback
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Headers: Content-Type, X-POS-Token, x-companion-token\r\n")
            append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        sock.getOutputStream().let {
            it.write(head.toByteArray(Charsets.UTF_8))
            it.write(bytes)
            it.flush()
        }
    }

    companion object {
        private const val TAG = "NativeCompanion"

        /**
         * **與 desktop companion 同一個埠**。
         * 唔可以改 —— POS 側 `COMPANION_DEFAULT_URL` 硬編 `http://127.0.0.1:9311`。
         */
        const val PORT = 9311

        /** 只綁 loopback（唔綁 `0.0.0.0`）—— LAN 上其他機連唔到，同 desktop 一致。 */
        const val LOOPBACK = "127.0.0.1"

        private const val READ_TIMEOUT_MS = 5000
        private const val PROBE_TIMEOUT_MS = 1200
    }
}
