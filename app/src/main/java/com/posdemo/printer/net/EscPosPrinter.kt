package com.posdemo.printer.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.Charset

class EscPosPrinter {

    suspend fun printRaw(
        ip: String,
        port: Int = 9100,
        payload: ByteArray,
        timeoutMs: Int = 4000,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            Socket().use { socket ->
                socket.soTimeout = timeoutMs
                socket.connect(InetSocketAddress(ip, port), timeoutMs)
                val out: OutputStream = socket.getOutputStream()
                out.write(payload)
                out.flush()
            }
        }
    }

    suspend fun printTextTicket(
        ip: String,
        title: String,
        body: String,
        footer: String = "POS Printer Demo",
    ): Result<Unit> {
        val bytes = buildTicket(title, body, footer)
        return printRaw(ip, 9100, bytes)
    }

    fun buildTicket(title: String, body: String, footer: String): ByteArray {
        val out = ArrayList<Byte>()
        fun add(vararg b: Int) = b.forEach { out.add(it.toByte()) }
        fun addStr(s: String) {
            // 多數收據機預設碼頁對中文支援不一；同時送 UTF-8 與可讀 ASCII
            out.addAll(s.toByteArray(Charset.forName("UTF-8")).toList())
        }

        add(0x1B, 0x40) // init
        add(0x1B, 0x61, 0x01) // center
        add(0x1D, 0x21, 0x11) // double size
        addStr(title)
        add(0x0A)
        add(0x1D, 0x21, 0x00)
        add(0x1B, 0x61, 0x00) // left
        addStr("--------------------------------\n")
        addStr(body)
        if (!body.endsWith("\n")) add(0x0A)
        addStr("--------------------------------\n")
        add(0x1B, 0x61, 0x01)
        addStr(footer)
        add(0x0A, 0x0A, 0x0A)
        add(0x1D, 0x56, 0x00) // partial cut
        return out.toByteArray()
    }
}
