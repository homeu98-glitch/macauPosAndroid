package com.posdemo.printer.hub

import android.content.Context
import android.util.Log
import com.posdemo.printer.data.HubAuth
import com.posdemo.printer.model.PrinterService
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.Charset
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class LanHttpServer(
    context: Context,
    private val port: Int = PrinterHub.PORT,
) {
    private val app = context.applicationContext
    private val hub = PrinterHub.get(app)
    private val auth = HubAuth(app)
    private val assembleLock = Any()
    private val assembleJobs = LinkedHashMap<String, AssembleJob>()
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress("0.0.0.0", port))
        serverSocket = ss
        hub.listening = true
        Thread({
            while (running.get()) {
                try {
                    val client = ss.accept()
                    pool.execute {
                        try {
                            handle(client)
                        } catch (t: Throwable) {
                            Log.w(TAG, "client fatal: ${t.message}", t)
                        }
                    }
                } catch (e: Exception) {
                    if (running.get()) Log.w(TAG, "accept: ${e.message}")
                }
            }
        }, "pos-lan-http").start()
    }

    fun stop() {
        running.set(false)
        hub.listening = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = 12_000
        socket.use { s ->
            try {
                val req = readRequest(s.getInputStream()) ?: return
                val res = route(req)
                s.getOutputStream().write(res)
                s.getOutputStream().flush()
            } catch (t: Throwable) {
                Log.w(TAG, "client: ${t.message}", t)
            }
        }
    }

    private fun route(req: HttpRequest): ByteArray {
        if (req.method == "OPTIONS") {
            return http(204, "text/plain", ByteArray(0))
        }
        val path = req.path
        return when {
            req.method == "GET" && (path == "/" || path == "/pos" || path == "/pos.html") ->
                asset("pos.html", "text/html; charset=utf-8")
            req.method == "GET" && (path == "/setup.html" || path == "/setup" || path == "/remote.html") ->
                asset("setup.html", "text/html; charset=utf-8")
            req.method == "GET" && path == "/api/health" -> json(
                JSONObject().put("ok", true).put("hubUrl", hub.hubUrl()).put("posPath", "/pos"),
            )
            req.method == "GET" && path == "/api/status" ->
                if (!authorized(req)) deny() else json(statusJson())
            req.method == "GET" && path == "/api/devices" ->
                if (!authorized(req)) deny() else json(JSONObject().put("ok", true).put("devices", hub.devicesJson()))
            req.method == "GET" && path == "/api/scan" ->
                if (!authorized(req)) deny() else json(scanJson())
            req.method == "POST" && path == "/api/scan" ->
                if (!authorized(req)) deny() else handlePostScan(req)
            req.method == "POST" && path == "/api/assign" ->
                if (!authorized(req)) deny() else handlePostAssign(req)
            req.method == "POST" && path == "/api/manual" ->
                if (!authorized(req)) deny() else handlePostManual(req)
            req.method == "POST" && path == "/api/remove" ->
                if (!authorized(req)) deny() else handlePostRemove(req)
            req.method == "POST" && path == "/api/clear" -> {
                if (!authorized(req)) deny()
                else {
                    hub.clearAll()
                    json(JSONObject().put("ok", true).put("devices", hub.devicesJson()))
                }
            }
            req.method == "POST" && path == "/api/ticket" -> handlePostTicket(req)
            req.method == "GET" && path == "/print" -> handlePrintHtml(req.query)
            req.method == "GET" && (path == "/beacon" || path == "/beacon.png") ->
                handlePrintBeacon(req.query)
            req.method == "GET" && path == "/api/print" ->
                if (!authorized(req)) deny()
                else handlePrintJson(req.query["service"].orEmpty(), req.query["ip"].orEmpty(), req.query["title"].orEmpty(), req.query["message"].orEmpty())
            req.method == "POST" && path == "/api/print" ->
                if (!authorized(req)) deny() else handlePostPrint(req)
            else -> json(
                JSONObject().put("ok", false).put("error", "not found"),
                404,
            )
        }
    }

    private fun handlePostScan(req: HttpRequest): ByteArray {
        val fields = parsePostFields(req) ?: emptyMap()
        val prefix = fields["prefix"].orEmpty().ifBlank { hub.subnetPrefix() }
        val identify = fields["identify"].equals("true", ignoreCase = true)
        val started = hub.requestScan(prefix, identify)
        return json(
            JSONObject()
                .put("ok", started)
                .put("started", started)
                .put("running", hub.scanRunning)
                .put("message", if (started) hub.scanMessage else "掃描進行中或網段無效")
                .put("prefix", prefix),
            if (started) 200 else 409,
        )
    }

    private fun handlePostAssign(req: HttpRequest): ByteArray {
        val fields = parsePostFields(req) ?: return json(JSONObject().put("ok", false), 400)
        val key = fields["key"].orEmpty()
        if (key.isBlank()) return json(JSONObject().put("ok", false).put("error", "key required"), 400)
        hub.assignService(key, fields["service"].orEmpty())
        return json(JSONObject().put("ok", true).put("devices", hub.devicesJson()))
    }

    private fun handlePostManual(req: HttpRequest): ByteArray {
        val fields = parsePostFields(req) ?: return json(JSONObject().put("ok", false), 400)
        val ip = fields["ip"].orEmpty().trim()
        if (ip.isBlank()) return json(JSONObject().put("ok", false).put("error", "ip required"), 400)
        hub.addManualBlocking(ip, fields["name"].orEmpty(), fields["service"].orEmpty())
        return json(JSONObject().put("ok", true).put("devices", hub.devicesJson()))
    }

    private fun handlePostRemove(req: HttpRequest): ByteArray {
        val fields = parsePostFields(req) ?: return json(JSONObject().put("ok", false), 400)
        val key = fields["key"].orEmpty()
        if (key.isBlank()) return json(JSONObject().put("ok", false).put("error", "key required"), 400)
        hub.removeDevice(key)
        return json(JSONObject().put("ok", true).put("devices", hub.devicesJson()))
    }

    private fun scanJson(): JSONObject = JSONObject()
        .put("ok", true)
        .put("running", hub.scanRunning)
        .put("message", hub.scanMessage)
        .put("devices", hub.devicesJson())

    private fun handlePrintHtml(query: Map<String, String>): ByteArray {
        val service = query["service"].orEmpty()
        val ip = query["ip"].orEmpty()
        val message = query["message"].orEmpty()
        val result = runPrint(service, ip, query["title"].orEmpty(), message)
        val label = serviceLabel(service, ip)
        val logs = result.logs.joinToString("<br>") {
            val color = if (it.warn) "#fbbf24" else "#34d399"
            "<span style=\"color:$color\">${escapeHtml(it.text)}</span>"
        }
        val heading = if (result.printed > 0) "已送到印表機" else "Hub 已收到"
        val html = """
            <!DOCTYPE html>
            <html lang="zh-Hant">
            <head>
              <meta charset="UTF-8" />
              <meta name="viewport" content="width=device-width, initial-scale=1" />
              <title>$heading</title>
            </head>
            <body style="font-family:sans-serif;background:#0f1419;color:#e8eef6;padding:20px">
              <h1 style="font-size:1.2rem">$heading</h1>
              <p>給 <b>${escapeHtml(label)}</b></p>
              <p>${escapeHtml(message)}</p>
              <p>$logs</p>
              <p style="color:#94a3b8;font-size:0.85rem">此頁即將自動關閉</p>
              <script>setTimeout(function(){ window.close(); }, 700);</script>
            </body>
            </html>
        """.trimIndent()
        val accepted = message.isNotBlank() && (service.isNotBlank() || ip.isNotBlank())
        return http(if (accepted) 200 else 400, "text/html; charset=utf-8", html.toByteArray(Charsets.UTF_8))
    }

    private fun handlePrintBeacon(query: Map<String, String>): ByteArray {
        val jobId = query["job"].orEmpty()
        if (jobId.isNotBlank()) {
            val ready = takeChunk(query)
            if (ready != null) {
                runPrint(ready.service, ready.ip, ready.title, ready.message)
            }
        } else {
            runPrint(
                query["service"].orEmpty(),
                query["ip"].orEmpty(),
                query["title"].orEmpty(),
                query["message"].orEmpty(),
            )
        }
        return http(200, "image/png", PIXEL_PNG)
    }

    private fun handlePostPrint(req: HttpRequest): ByteArray {
        if (req.tooLarge) {
            return json(JSONObject().put("ok", false).put("error", "payload too large"), 413)
        }
        val fields = parsePostFields(req) ?: return json(
            JSONObject().put("ok", false).put("error", "invalid body"),
            400,
        )
        return handlePrintJson(
            fields["service"].orEmpty(),
            fields["ip"].orEmpty(),
            fields["title"].orEmpty(),
            fields["message"].orEmpty(),
        )
    }

    private fun handlePostTicket(req: HttpRequest): ByteArray {
        if (!authorized(req)) return deny()
        if (req.tooLarge) {
            return json(JSONObject().put("ok", false).put("error", "payload too large"), 413)
        }
        val obj = try {
            JSONObject(req.body.ifBlank { "{}" })
        } catch (_: Exception) {
            return json(JSONObject().put("ok", false).put("error", "invalid json"), 400)
        }
        val lines = obj.optJSONArray("lines")
        if (lines == null || lines.length() == 0) {
            return json(JSONObject().put("ok", false).put("error", "lines required"), 400)
        }
        val remark = obj.optString("remark").trim()
        val grouped = LinkedHashMap<String, MutableList<String>>()
        for (i in 0 until lines.length()) {
            val line = lines.optJSONObject(i) ?: continue
            val name = line.optString("name").trim()
            val qty = line.optInt("qty", 1).coerceIn(1, 99)
            val dest = PrinterService.fromId(line.optString("dest").ifBlank { "kitchen" })?.id ?: "kitchen"
            if (name.isBlank()) continue
            grouped.getOrPut(dest) { ArrayList() }.add("$name  x$qty")
        }
        if (grouped.isEmpty()) {
            return json(JSONObject().put("ok", false).put("error", "no valid lines"), 400)
        }
        val ticketNo = auth.nextTicketNo()
        val title = "單 #$ticketNo"
        var printed = 0
        var total = 0
        val logs = JSONArray()
        grouped.forEach { (dest, items) ->
            val body = buildString {
                items.forEach { append(it).append('\n') }
                if (remark.isNotBlank()) append('\n').append("備註: ").append(remark)
            }
            val result = runPrint(dest, "", title, body)
            printed += result.printed
            total += result.total
            result.logs.forEach { logs.put(JSONObject().put("text", it.text).put("warn", it.warn)) }
        }
        return json(
            JSONObject()
                .put("ok", true)
                .put("ticketNo", ticketNo)
                .put("printed", printed)
                .put("total", total)
                .put("logs", logs),
        )
    }

    private fun authorized(req: HttpRequest): Boolean {
        val fromHeader = req.headers["x-hub-token"]
            ?: req.headers["authorization"]?.removePrefix("Bearer ")?.trim()
        val fromQuery = req.query["token"]
        val fromBody = try {
            if (req.body.contains("\"token\"")) JSONObject(req.body).optString("token") else ""
        } catch (_: Exception) {
            ""
        }
        return auth.matches(fromHeader) || auth.matches(fromQuery) || auth.matches(fromBody)
    }

    private fun deny(): ByteArray =
        json(JSONObject().put("ok", false).put("error", "token required"), 401)

    private fun parsePostFields(req: HttpRequest): Map<String, String>? {
        val ct = req.contentType.lowercase()
        return try {
            if (ct.contains("json")) {
                val obj = JSONObject(req.body.ifBlank { "{}" })
                val map = HashMap<String, String>()
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = obj.opt(k)
                    map[k] = when (v) {
                        null, JSONObject.NULL -> ""
                        is Boolean, is Number -> v.toString()
                        else -> obj.optString(k)
                    }
                }
                map
            } else {
                parseQuery(req.body)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun takeChunk(query: Map<String, String>): ReadyJob? {
        val jobId = query["job"].orEmpty()
        val seq = query["seq"]?.toIntOrNull() ?: return null
        val total = query["total"]?.toIntOrNull() ?: return null
        if (total !in 1..200 || seq !in 0 until total) return null
        val chunk = query["chunk"].orEmpty()
        synchronized(assembleLock) {
            pruneJobsLocked()
            val job = assembleJobs[jobId] ?: AssembleJob(
                service = query["service"].orEmpty(),
                ip = query["ip"].orEmpty(),
                title = query["title"].orEmpty(),
                parts = arrayOfNulls(total),
            ).also { assembleJobs[jobId] = it }
            if (job.parts.size != total) return null
            job.parts[seq] = chunk
            if (job.parts.any { it == null }) return null
            assembleJobs.remove(jobId)
            val message = job.parts.joinToString(separator = "") { it ?: "" }
            if (message.length > MAX_MESSAGE) return null
            return ReadyJob(job.service, job.ip, job.title, message)
        }
    }

    private fun pruneJobsLocked() {
        val now = System.currentTimeMillis()
        val stale = assembleJobs.entries.filter { now - it.value.createdAt > 60_000 }.map { it.key }
        stale.forEach { assembleJobs.remove(it) }
        while (assembleJobs.size > 32) {
            val oldest = assembleJobs.keys.firstOrNull() ?: break
            assembleJobs.remove(oldest)
        }
    }

    private fun handlePrintJson(service: String, ip: String, title: String, message: String): ByteArray {
        val result = runPrint(service, ip, title, message)
        val logs = org.json.JSONArray()
        result.logs.forEach { logs.put(JSONObject().put("text", it.text).put("warn", it.warn)) }
        val accepted = message.isNotBlank() && (service.isNotBlank() || ip.isNotBlank())
        val code = if (accepted) 200 else 400
        return json(
            JSONObject()
                .put("ok", accepted)
                .put("received", accepted)
                .put("printed", result.printed)
                .put("total", result.total)
                .put("logs", logs),
            code,
        )
    }

    private fun runPrint(service: String, ip: String, title: String, message: String): PrintJobResult {
        if (message.isBlank()) {
            return PrintJobResult(0, 0, listOf(PrintLog("message required", true)))
        }
        val result = runBlocking {
            when {
                ip.isNotBlank() -> hub.printToIp(ip, title.ifBlank { "POS" }, message)
                service.isNotBlank() -> hub.printService(service, message)
                else -> PrintJobResult(0, 0, listOf(PrintLog("需要 service 或 ip", true)))
            }
        }
        val label = serviceLabel(service, ip)
        try {
            hub.webCommandListener?.onWebCommand(service.ifBlank { ip }, label, message, result.printed)
        } catch (e: Exception) {
            Log.w(TAG, "notify UI: ${e.message}")
        }
        return result
    }

    private fun serviceLabel(service: String, ip: String): String =
        PrinterService.fromId(service)?.label
            ?: if (ip.isNotBlank()) "IP $ip" else service.ifBlank { "未知" }

    private fun escapeHtml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun statusJson(): JSONObject {
        val devices = hub.snapshot()
        return JSONObject()
            .put("ok", true)
            .put("listening", hub.listening)
            .put("port", port)
            .put("localIp", hub.localIp())
            .put("subnetPrefix", hub.subnetPrefix().ifBlank { "192.168.1" })
            .put("hubUrl", hub.hubUrl())
            .put("posUrl", hub.posUrl(auth.token()))
            .put("scanRunning", hub.scanRunning)
            .put("scanMessage", hub.scanMessage)
            .put("deviceCount", devices.size)
            .put("bound", devices.count { it.service != null })
            .put("devices", hub.devicesJson())
    }

    private fun asset(name: String, contentType: String): ByteArray {
        return try {
            http(200, contentType, app.assets.open(name).use { it.readBytes() })
        } catch (_: Exception) {
            json(JSONObject().put("ok", false).put("error", "asset missing"), 500)
        }
    }

    private fun json(obj: JSONObject, code: Int = 200): ByteArray =
        http(code, "application/json; charset=utf-8", obj.toString().toByteArray(Charsets.UTF_8))

    private fun http(code: Int, contentType: String, body: ByteArray): ByteArray {
        val reason = when (code) {
            200 -> "OK"
            204 -> "No Content"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            409 -> "Conflict"
            413 -> "Payload Too Large"
            502 -> "Bad Gateway"
            else -> "Error"
        }
        val head = buildString {
            append("HTTP/1.1 $code $reason\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
            append("Access-Control-Allow-Headers: Content-Type, Authorization, X-Hub-Token\r\n")
            append("Access-Control-Allow-Private-Network: true\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Cache-Control: no-store, no-cache\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }.toByteArray(Charsets.US_ASCII)
        return head + body
    }

    private fun readRequest(input: InputStream): HttpRequest? {
        val headerBuf = ByteArrayOutputStream()
        val window = ByteArray(4)
        var filled = 0
        while (true) {
            val b = input.read()
            if (b < 0) return null
            headerBuf.write(b)
            if (filled < 4) {
                window[filled++] = b.toByte()
            } else {
                window[0] = window[1]
                window[1] = window[2]
                window[2] = window[3]
                window[3] = b.toByte()
            }
            if (filled == 4 &&
                window[0] == '\r'.code.toByte() && window[1] == '\n'.code.toByte() &&
                window[2] == '\r'.code.toByte() && window[3] == '\n'.code.toByte()
            ) {
                break
            }
            if (headerBuf.size() > 64 * 1024) return null
        }
        val headerText = String(headerBuf.toByteArray(), Charset.forName("ISO-8859-1"))
        val lines = headerText.split("\r\n")
        if (lines.isEmpty()) return null
        val parts = lines[0].split(" ")
        if (parts.size < 2) return null
        val method = parts[0].uppercase()
        val uri = parts[1]
        val rawPath = uri.substringBefore("?")
        val query = parseQuery(uri.substringAfter("?", ""))
        var contentLength = 0
        var contentType = ""
        val headers = HashMap<String, String>()
        for (i in 1 until lines.size) {
            val line = lines[i]
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val name = line.substring(0, idx).trim().lowercase()
            val value = line.substring(idx + 1).trim()
            headers[name] = value
            if (name == "content-length") {
                contentLength = value.toIntOrNull() ?: 0
            } else if (name == "content-type") {
                contentType = value
            }
        }
        if (contentLength > MAX_BODY) {
            return HttpRequest(method, rawPath, query, "", contentType, headers, tooLarge = true)
        }
        val body = if (contentLength > 0) {
            val buf = ByteArray(contentLength)
            var off = 0
            while (off < contentLength) {
                val n = input.read(buf, off, contentLength - off)
                if (n < 0) break
                off += n
            }
            String(buf, 0, off, Charsets.UTF_8)
        } else ""
        return HttpRequest(method, rawPath, query, body, contentType, headers, tooLarge = false)
    }

    private fun parseQuery(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        return raw.split("&").mapNotNull { pair ->
            if (pair.isEmpty()) return@mapNotNull null
            val i = pair.indexOf('=')
            val k = if (i < 0) pair else pair.substring(0, i)
            val v = if (i < 0) "" else pair.substring(i + 1)
            decodeComp(k) to decodeComp(v)
        }.toMap()
    }

    private fun decodeComp(s: String): String = try {
        URLDecoder.decode(s.replace("+", "%20"), "UTF-8")
    } catch (_: Exception) {
        s
    }

    private data class HttpRequest(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val body: String,
        val contentType: String = "",
        val headers: Map<String, String> = emptyMap(),
        val tooLarge: Boolean = false,
    )

    private class AssembleJob(
        val service: String,
        val ip: String,
        val title: String,
        val parts: Array<String?>,
        val createdAt: Long = System.currentTimeMillis(),
    )

    private data class ReadyJob(
        val service: String,
        val ip: String,
        val title: String,
        val message: String,
    )

    companion object {
        private const val TAG = "LanHttpServer"
        private const val MAX_BODY = 512 * 1024
        private const val MAX_MESSAGE = 256 * 1024

        /** 1x1 透明 PNG，給 HTTPS 頁用隱藏圖片打區網 HTTP（被動 mixed content）。 */
        private val PIXEL_PNG = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x08, 0x06,
            0x00, 0x00, 0x00, 0x1F, 0x15.toByte(), 0xC4.toByte(), 0x89.toByte(),
            0x00, 0x00, 0x00, 0x0A, 0x49, 0x44, 0x41, 0x54,
            0x78, 0x9C.toByte(), 0x63, 0x00, 0x01, 0x00, 0x00, 0x05, 0x00, 0x01,
            0x0D, 0x0A, 0x2D, 0xB4.toByte(),
            0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44,
            0xAE.toByte(), 0x42, 0x60, 0x82.toByte(),
        )
    }
}
