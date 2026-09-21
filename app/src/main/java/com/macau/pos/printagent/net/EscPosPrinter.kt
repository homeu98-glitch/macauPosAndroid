package com.macau.pos.printagent.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * LAN 打印機嘅**傳輸層**（raw TCP 9100）。
 *
 * ⚠️ 呢個 class **只負責送 bytes，唔負責排版**。
 *
 * 2026-09-18：舊嘅 `printTextTicket()` / `buildTicket()` 已隨 printerhub 一併刪除。
 * 佢哋係一條旁路：固定 32 個 `-`、硬編 UTF-8、無兩欄／無折扣／無 QR／
 * 而且放大語意用錯（`GS ! 0x11` 誤當 `ESC !` 嘅位元值，且完全冇 clearMagnify）
 * —— 與三方同源嘅 [EscPosRenderer] 唔一致。所有出紙一律經 [SdkPrinter] → [EscPosRenderer]。
 */
class EscPosPrinter {

    suspend fun printRaw(
        ip: String,
        port: Int = 9100,
        payload: ByteArray,
        timeoutMs: Int = RAW_TIMEOUT_MS,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (ip.isBlank()) throw IllegalArgumentException("打印機 IP 為空")
            if (port <= 0 || port > 65535) throw IllegalArgumentException("打印機 port 無效：$port")

            Socket().use { socket ->
                socket.soTimeout = timeoutMs
                // connect timeout：對方 drop SYN（冇 RST）時，呢個 timeout 係唯一嘅保命符
                socket.connect(InetSocketAddress(ip, port), timeoutMs)
                val out: OutputStream = socket.getOutputStream()
                out.write(payload)
                out.flush()
                // ⚠️ 寫完唔即刻收 socket：desktop companion-server.mjs 嘅 printLan 喺
                // write 之後等 300ms 至 sock.end()，註釋明言「大單會 missing print」。
                // 呢度留喺 socket 未 close 之前等，語意同 desktop 對齊。
                try {
                    Thread.sleep(WRITE_DRAIN_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            Unit
        }
    }

    /** 快速探測：只連唔印，用嚟判斷「部機到底有冇聽緊呢個 port」。 */
    suspend fun probe(
        ip: String,
        port: Int = 9100,
        timeoutMs: Int = PROBE_TIMEOUT_MS,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), timeoutMs)
            }
            Unit
        }
    }

    companion object {
        const val RAW_TIMEOUT_MS = 5000
        const val PROBE_TIMEOUT_MS = 1200

        /**
         * 寫完之後、close 之前嘅等待。對齊 desktop `printLan` 嘅 300ms。
         * 短過 150ms 大單（幾 KB 以上嘅模板 + QR 點陣）會被對端 buffer 截斷。
         */
        const val WRITE_DRAIN_MS = 300L
    }
}
