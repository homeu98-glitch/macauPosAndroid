package com.macau.pos.printagent.net

import com.macau.pos.printagent.model.PrintJobDto
import com.macau.pos.printagent.model.PrinterCfgDto
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ESC/POS 渲染器 —— 對應 web 端 print-bridge/src/escpos.mjs。
 * 用 Android 內建 Charset（預設 GB18030）做中文編碼，每台打印機可經
 * printer.charset 覆蓋（gb18030 / big5 / utf-8）。
 *
 * 只負責「產生 byte[]」，唔理 socket；socket 寫入由 EscPosPrinter.printRaw 做。
 */
object EscPosRenderer {

    private const val ESC = 0x1B
    private const val FS = 0x1C
    private const val GS = 0x1D
    private const val LF = 0x0A

    /** 3 檔字型（ESC ! n，0x1B 0x21 n 標準位元）：s=正常(1x1) / m=雙寬(2x1,0x20) / l=雙高雙寬(2x2,0x30)。
     *  ⚠️ 舊值 l:0x60 = 0x20|0x40，bit6(0x40) 係保留位多數機忽略 → 當雙寬同 m；改 0x30 先真雙高雙寬。
     *  同 desktop-companion/companion-server.mjs SIZE_BYTE 同源（見 docs/70）。 */
    private val SIZE_BYTE = mapOf("s" to 0x00, "m" to 0x20, "l" to 0x30)

    /** GS ! n — 標準 Epson nibble 語意：n = ((h-1)<<4)|(w-1)；s=1×1、m=2闊1高、l=2×2。
     *  ⚠️ 唔可以用 ESC ! 嘅 0x20 / 0x30：GS ! 係 nibble 語意，0x20 → (h=3,w=1) = 2 闊 3 高，
     *  會令菜品名「拉長變形」（docs/99 §1、docs/114 §2.4）。呢個係「字體異常」嘅元兇之一。 */
    private val GS_SIZE_BYTE = mapOf("s" to 0x00, "m" to 0x01, "l" to 0x11)

    /** FS ! n — 標準 ESC/POS Kanji 放大：bit 0x04 = 雙闊、0x08 = 雙高、0x0C = 2×2。 */
    private val FS_SIZE_BYTE = mapOf("s" to 0x00, "m" to 0x04, "l" to 0x0C)

    /** 判斷字串是否含中日韓字符（CJK Unified / 兼容 / 全角 / 全角標點）。 */
    private fun hasCJK(s: String): Boolean {
        for (c in s) {
            val cp = c.code
            if ((cp in 0x3400..0x9FFF) || (cp in 0xF900..0xFAFF) ||
                (cp in 0xFF00..0xFFEF) || (cp in 0x3000..0x303F)) return true
        }
        return false
    }

    // ---- 收據兩欄對齊（docs/95 §1，同 desktop-companion/companion-server.mjs 同源）----
    //
    // 熱敏機冇 flex，要靠空格 padding 先做到「品名靠左 / 數量+價錢靠右」。
    // 中文字佔 2 格、ASCII 佔 1 格，所以用 displayWidth 而唔係 text.length，
    // 否則「珍珠奶茶」(4 字 = 8 格) 會當 4 格計，對齊全部歪晒。
    private const val RECEIPT_PAPER_COLUMNS = 48 // 80mm 熱敏紙 font A 每行格數（576 dots ÷ 12）
    private const val PAPER_COLUMNS_58MM = 32 // 58mm 熱敏紙 font A 每行格數（384 dots ÷ 12）

    /**
     * 該部機每行得幾多格 —— 58mm → 32 格，其餘（80mm / 未指定）→ 48 格。
     *
     * ⚠️ docs/96 §9：**58mm 係 Sunmi V2 內置打印機嘅紙寬**。改之前 `renderTemplateTicket`
     * 所有 `twoColumn()` 都唔傳 `cols` → 永遠用 48 格（80mm）。喺 58mm 機上，
     * 「品名 …… 價錢」嗰行會長過紙寬 → 打印機自動 wrap，價錢甩去下一行，成張單散晒。
     * `renderKitchenTicket` / `renderReceiptTicket` 一早有 `contains("58")` 判斷，
     * 淨係模板路徑（而家嘅主路徑）漏咗。
     */
    private fun paperColumns(printer: PrinterCfgDto?): Int =
        if ((printer?.paperSize ?: "").contains("58")) PAPER_COLUMNS_58MM else RECEIPT_PAPER_COLUMNS

    /** 東亞寬字符（全角）判定，同 Companion isWideChar() 逐段對齊。 */
    private fun isWideChar(cp: Int): Boolean =
        (cp in 0x1100..0x115F) ||
            (cp in 0x2E80..0xA4CF) ||
            (cp in 0xAC00..0xD7A3) ||
            (cp in 0xF900..0xFAFF) ||
            (cp in 0xFE30..0xFE4F) ||
            (cp in 0xFF00..0xFF60) ||
            (cp in 0xFFE0..0xFFE6) ||
            (cp in 0x20000..0x3FFFD)

    /** 顯示格數：寛字符 2 格、其他 1 格；用 codePoint 行（surrogate pair 當 1 個字）。 */
    private fun displayWidth(s: String): Int {
        var w = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            w += if (isWideChar(cp)) 2 else 1
            i += Character.charCount(cp)
        }
        return w
    }

    /** left 靠左、right 靠右，中間補空格到 `cols` 格；太窄就退化成兩個空格分隔（唔會削名）。 */
    private fun twoColumn(left: String, right: String, cols: Int = RECEIPT_PAPER_COLUMNS): String {
        val pad = cols - displayWidth(left) - displayWidth(right)
        return if (pad >= 1) left + " ".repeat(pad) + right else "$left  $right"
    }

    // ---- 規格行加購價錢靠右（docs/95 §用戶反饋 R1）----
    //
    // POS 端 `formatSpecLine` 會拼 `"加購:加麵 $5"`，舊 renderer 直接印個 string →
    // $5 黐喺 "加購:加麵" 旁邊，唔同主行 `$95` 嘅右邊對齊。
    // 拆成 (label, price) 後用 twoColumn() → 同一條紙邊。
    // 同 web 預覽 `splitSpecLine` / Companion `splitSpecLine` 逐行對齊（regex 都要一致）。
    private data class SpecParts(val label: String, val price: String?)

    private fun splitSpecLine(s: String): SpecParts {
        val m = Regex("^(.*?)\\s+(-?\\$\\d+|-\\d+)$").find(s)
            ?: return SpecParts(s, null)
        val label = m.groupValues[1].trimEnd()
        val tail = m.groupValues[2]
        return SpecParts(label, if (tail.startsWith("$")) tail else " $tail")
    }

    /**
     * JS template literal `${n}` 嘅等價輸出：30.0 → "30"、30.5 → "30.5"。
     * ⚠️ Kotlin 嘅 Double.toString() 會出 "30.0"，同 web 預覽 / Companion 唔同 → 出紙多咗個 ".0"。
     */
    private fun num(v: Double): String =
        "%.2f".format(Locale.US, v).trimEnd('0').trimEnd('.').ifEmpty { "0" }

    /**
     * 折扣 sub-line 文字，同 desktop-companion 逐字符對齊（docs/95 §3）：
     * `  折扣率 80%（原價 $30）` + 右欄 `折讓 $6`。
     *
     * @param prefix card 版面用兩個空格縮排，list 版面用 `  · ` 跟規格 bullet 對齊。
     */
    private fun discountLine(item: PrintJobDto.Item, prefix: String, cols: Int): String {
        val rate = item.discountRate ?: return ""
        val saving = item.savingAmount ?: return ""
        val rateText = if (rate % 1.0 == 0.0) {
            "${rate.toLong()}%"
        } else {
            "%.1f".format(Locale.US, rate) + "%"
        }
        val original = item.originalUnitPrice?.let { "（原價 \$${num(it)}）" } ?: ""
        val left = "${prefix}折扣率 $rateText$original"
        return twoColumn(left, "折讓 \$${Math.round(saving)}", cols)
    }

    // ---- 收據二維碼（docs/95 §2）----
    //
    // POS 端已經用 encodeQrPayload() 預先編好點陣（`{ size, bits }`），
    // 呢度淨負責「點陣 → ESC/POS 點陣圖（GS v 0）」。
    // 用點陣圖而唔係 GS ( k 原生 QR 指令：舊機 / 平價機未必支援原生 QR，會印亂碼；
    // 點陣圖係所有 ESC/POS 機都識嘅基本指令，而且三個 repo 共用同一個矩陣 → 出紙 == 預覽。
    private const val QR_QUIET_MODULES = 4

    private fun qrRaster(buf: Buf, qr: PrintJobDto.QrPayload, align: String) {
        val size = qr.size
        val bits = qr.bits
        if (size <= 0 || bits.length < size * size) return
        // 每個 module 放大到 scale 點：最終點陣圖維持 ~160 點（≈20mm），掃得到又唔會甩出紙邊
        val scale = (160 / (size + QR_QUIET_MODULES * 2)).coerceIn(2, 6)
        val total = (size + QR_QUIET_MODULES * 2) * scale // 正方形邊長（點）
        val rowBytes = (total + 7) / 8
        val data = ByteArray(rowBytes * total)
        for (r in 0 until size) {
            for (c in 0 until size) {
                if (bits[r * size + c] != '1') continue
                val y0 = (r + QR_QUIET_MODULES) * scale
                val x0 = (c + QR_QUIET_MODULES) * scale
                for (dy in 0 until scale) {
                    val y = y0 + dy
                    for (dx in 0 until scale) {
                        val x = x0 + dx
                        val idx = y * rowBytes + (x shr 3)
                        data[idx] = (data[idx].toInt() or (0x80 shr (x and 7))).toByte()
                    }
                }
            }
        }
        buf.resetMagnify() // 放大狀態會令點陣圖闊度計錯 → 一定要先清
        buf.align(align)
        buf.cmd(LF) // 圖前留空行，唔好黐住上一行
        buf.cmd(GS, 0x76, 0x30, 0x00) // GS v 0 m=0（203dpi 正常模式）
        buf.cmd(rowBytes and 0xFF, (rowBytes shr 8) and 0xFF) // xL xH：每行位元組數
        buf.cmd(total and 0xFF, (total shr 8) and 0xFF) // yL yH：高（點）
        buf.bytes(data)
        buf.cmd(LF) // 圖後留空行
        buf.cmd(ESC, 0x33, 30) // ESC 3 30 還原行距
    }

    private val SUPPORTED = setOf("gb18030", "gbk", "big5", "utf-8", "utf8")

    /** 解析每台打印機嘅 charset；唔支援就 fallback GB18030 再 fallback UTF-8。 */
    fun resolveCharset(name: String?): Charset {
        val n = name?.trim()?.lowercase()
        if (!n.isNullOrBlank() && n in SUPPORTED) {
            return runCatching { Charset.forName(n) }.getOrElse { defaultCharset() }
        }
        return defaultCharset()
    }

    private fun defaultCharset(): Charset =
        runCatching { Charset.forName("GB18030") }.getOrElse { Charset.forName("UTF-8") }

    private fun encode(text: String, cs: Charset): ByteArray =
        text.replace("\r\n", "\n").replace("\r", "\n").toByteArray(cs)

    private class Buf(private val cs: Charset, kanjiEnlarge: String? = null) {
        private val out = java.io.ByteArrayOutputStream()
        /** 中文（Kanji）倍大路線：商頌 POS-80 等機要 GS ! n（0x1D 0x21）；標準 ESC/POS 機用 FS ! n（0x1C 0x21）。
         *  缺省 GS ! n：接上就用嘅安全值，已於商頌 POS-80 實機對照測試證實（FS ! n 喺呢部機係 no-op）。 */
        private val useGs = kanjiEnlarge != "FS!"
        /** 當前字型檔（s/m/l）。真正 emit 留喺 `emitLine`（每行決定，避免 ESC! / GS! 相乘）。 */
        private var curSize: String = "s"
        private var curBold: Boolean = false
        fun cmd(vararg b: Int) = apply { b.forEach { out.write(it) } }

        /**
         * 每行 emit 一次字型指令（對齊 `ESC !` / `GS !` / `FS !` 嘅相乘地雷，docs/80 B2、docs/99 §1）：
         * - GS! 路線 + CJK 行：`ESC !` 唔帶放大 bit（避免同 `GS !` 相乘 → 變形）
         * - 其他情況：`ESC !` 帶 `SIZE_BYTE[size]` 放大（ASCII / 半形）
         * - CJK 行：`FS &` 入 Kanji mode 後，按路線發 `GS !` / `FS !` 對應 size byte
         * - 行距跟縱向倍數（l 雙高 → 行距 double，避免大字重疊變扁，docs/74 B3）
         *
         * ⚠️ 舊版係 `style()` 即刻發 `ESC !` + `KANJI_SIZE_BYTE` 用咗 `ESC !` 嘅位元值（0x20/0x30），
         * 喺 GS! 路線下係 nibble 語意 → 變成 2 闊 3 高 / 3×3 = 「菜品名拉長變形」
         * （docs/99 §2、docs/114 §2.4）。
         *
         * ⚠️⚠️ 2026-09-10（docs/114）：**每行開頭一定要先 `clearMagnify()`**。
         * `GS !` / `FS !` 係打印機**常駐狀態**，`ESC !` 清唔走佢、兩者仲會相乘 →
         * 上一行係放大嘅 CJK（例如菜品名 m/l）時，跟住嗰行純 ASCII（分格線 `----`）
         * 會無聲無息變成雙闊 → 一行放唔落 → 打印機自動折行 → **一條線變兩條**。
         */
        private fun emitLine(s: String, withLf: Boolean, inverse: Boolean = false) {
            val cjk = hasCJK(s)
            clearMagnify()
            val escByte = if (useGs && cjk) 0x00 else (SIZE_BYTE[curSize] ?: 0x00)
            cmd(ESC, 0x21, escByte)
            cmd(ESC, 0x45, if (curBold) 1 else 0)
            cmd(ESC, 0x33, if (curSize == "l") 60 else 30)
            if (cjk) {
                cmd(FS, 0x26) // FS & 入 Kanji mode
                if (useGs) cmd(GS, 0x21, GS_SIZE_BYTE[curSize] ?: 0x00)
                else cmd(FS, 0x21, FS_SIZE_BYTE[curSize] ?: 0x00)
            }
            if (inverse) cmd(ESC, 0x7B, 0x01) // ESC { 1 反白開
            out.write(encode(s, cs))
            if (inverse) cmd(ESC, 0x7B, 0x00) // ESC { 0 反白閂（喺 LF 之前）
            if (cjk) cmd(FS, 0x2E) // FS . 出 Kanji mode（先出 mode 再 LF，同 companion textLine）
            if (withLf) out.write(LF)
        }

        /**
         * 清走三套放大指令嘅殘留狀態（唔改行距、唔改對齊）。
         *
         * 三個都要發：`GS !` 同 `FS !` 係**常駐**嘅，`ESC !` 清唔走佢哋；
         * 而 `GS !` / `FS !` / `ESC !` 喺 Gprinter / 商頌系機器上係**相乘**語義（docs/80 B2）。
         * 所以任何一行（特別係純 ASCII 嘅分格線）印之前都要 call 一次，
         * 唔可以靠「上一行唔會放大」呢個假設（docs/114）。
         */
        fun clearMagnify() = apply {
            cmd(GS, 0x21, 0x00)
            cmd(ESC, 0x21, 0x00)
            cmd(FS, 0x21, 0x00)
        }

        fun str(s: String) = apply { emitLine(s, false) }

        /**
         * @param inverse 反白（黑底白字）= 熱敏紙唯一表達得到嘅「底色 / 強調」（docs/95 §3）。
         *
         * 設計介面（網頁預覽）用琥珀色底表達「呢行係折扣」，但熱敏機物理上只得 1-bit 黑白，
         * 顏色印唔出。ESC/POS 嘅對應手段係 `ESC { n`（0x1B 0x7B）：n=1 反白。
         *
         * ⚠️ 一定係「文字前開、文字後閂」，**唔包 LF**：反白要先喺 encode() 之前開、
         * 喺 LF 之前閂，否則換行位會被反白成一條黑邊，或者反白狀態殘留去下一行。
         */
        fun line(s: String = "", inverse: Boolean = false) = apply { emitLine(s, true, inverse) }
        fun bytes(ba: ByteArray) = apply { out.write(ba) }
        fun toBytes(): ByteArray = out.toByteArray()

        /** 目前 sticky 字型檔（s/m/l）；舊模板分格線「繼承上一行」時讀（docs/114 §3.3）。 */
        fun currentSize(): String = curSize

        /**
         * 印一條分隔線（`width` = 1× 字型一行嘅格數）。
         *
         * 刻意唔經 `emitLine`（線唔應該帶 Kanji mode 指令），所以**冇自動 clearMagnify** →
         * **一定要自己清**：呢行係純 ASCII，前面一行若係放大了嘅 CJK（菜品名 m/l → 發過
         * `GS ! 0x01`），常駐放大狀態會令條線變雙闊 → 一行放唔落 → 自動折行 → 一條變兩條（docs/114）。
         */
        fun sep(width: Int) = apply {
            clearMagnify()
            out.write(encode("-".repeat(width) + "\n", cs))
        }

        /** `ESC ! n` 字型大小 + `ESC E` 粗體（對應 web 預覽 size/bold）。同時記低 curSize 畀中文 GS ! n 用。 */
        fun style(size: String, bold: Boolean) = apply { curSize = size; curBold = bold }
        /** `ESC a` 對齊：center=1 / right=2 / 其他=0（left）。 */
        fun align(a: String) = apply {
            val code = when (a) { "center" -> 1; "right" -> 2; else -> 0 }
            cmd(ESC, 0x61, code)
        }
        /** 重設粗體 + 對齊（避免殘留落去下一行）。 */
        fun reset() = apply {
            curSize = "s"; curBold = false
            cmd(ESC, 0x45, 0x00)
            cmd(ESC, 0x61, 0x00)
        }

        /**
         * 強制清除放大狀態（`GS ! 0x00` + `ESC ! 0x00` + `ESC 3 30`），防殘留落下一張單（docs/81 P1-B）。
         * 印點陣圖（QR）前一定要 call：放大狀態會令 `GS v 0` 嘅闊度計錯 → 圖變形 / 甩出紙邊。
         */
        fun resetMagnify() = apply {
            curSize = "s"; curBold = false
            cmd(GS, 0x21, 0x00)
            cmd(ESC, 0x21, 0x00)
            cmd(ESC, 0x33, 30)
        }
    }

    private fun ticketTypeLabel(ticketType: String?): String = when (ticketType) {
        "addon" -> "【加單】"
        "void" -> "【退菜】"
        else -> "【廚房單】"
    }

    /** 對應 escpos.mjs renderKitchenTicket。job = 我哋 PrintJob JSON。 */
    fun renderKitchenTicket(job: PrintJobDto, printer: PrinterCfgDto?, storeName: String?): ByteArray {
        val width = if ((printer?.paperSize ?: "").contains("58")) 32 else 42
        val cs = resolveCharset(printer?.charset)
        val buf = Buf(cs, printer?.kanjiEnlarge)
        buf.cmd(ESC, 0x40)            // init
        buf.cmd(ESC, 0x61, 0x01)      // center
        buf.line(storeName ?: "Macau POS")
        buf.line(ticketTypeLabel(job.ticketType))
        buf.cmd(ESC, 0x61, 0x00)      // left
        buf.sep(width)

        job.orderNo?.takeIf { it.isNotBlank() }?.let { buf.line("單號: $it") }
        job.tableName?.takeIf { it.isNotBlank() }?.let { buf.line("桌台: $it") }
        job.printerName?.takeIf { it.isNotBlank() }?.let { buf.line("打印機: $it") }
        buf.sep(width)

        for (item in job.items.orEmpty()) {
            val qty = if (item.quantity <= 0) 1 else item.quantity
            buf.line("${item.name}  x$qty")
            for (spec in item.specs.orEmpty()) buf.line("  · $spec")
            // 用語對齊既有慣例「注：」（renderTemplateTicket L255、web escpos-preview、desktop-companion 同源）。
            item.note?.takeIf { it.isNotBlank() }?.let { buf.line("  注：$it") }
        }

        buf.sep(width)
        val ts = runCatching {
            SimpleDateFormat("yyyy/M/d HH:mm:ss", Locale.getDefault())
                .format(Date(job.createdAt ?: System.currentTimeMillis()))
        }.getOrElse { Date().toString() }
        buf.line(ts)
        buf.cmd(LF, LF, LF)
        buf.cmd(GS, 0x56, 0x00)       // cut
        return buf.toBytes()
    }

    /** 對應 escpos.mjs renderTestPage。 */
    fun renderTestPage(printer: PrinterCfgDto?, storeName: String?): ByteArray {
        val cs = resolveCharset(printer?.charset)
        val buf = Buf(cs, printer?.kanjiEnlarge)
        buf.cmd(ESC, 0x40)
        buf.cmd(ESC, 0x61, 0x01)
        buf.line(storeName ?: "Macau POS")
        buf.line("打印測試頁")
        buf.cmd(ESC, 0x61, 0x00)
        buf.sep(32)
        buf.line("打印機: ${printer?.name ?: "-"}")
        val conn = printer?.connectionType ?: "-"
        buf.line("連接: $conn")
        if (conn == "lan") {
            buf.line("IP: ${printer?.ipAddress ?: "-"}:${printer?.lanPort ?: 9100}")
        } else {
            buf.line("USB: ${printer?.usbLabel ?: "-"}")
        }
        buf.line("Charset: ${cs.name()}")
        buf.sep(32)
        buf.line("若看到此行，LAN/USB 橋接正常。")
        buf.cmd(LF, LF, LF)
        buf.cmd(GS, 0x56, 0x00)
        return buf.toBytes()
    }

    /** 對應 escpos.mjs renderReceiptTicket。 */
    fun renderReceiptTicket(
        job: PrintJobDto,
        printer: PrinterCfgDto?,
        storeName: String?,
        paymentMethod: String?,
        total: Double?,
    ): ByteArray {
        val width = if ((printer?.paperSize ?: "").contains("58")) 32 else 42
        val cs = resolveCharset(printer?.charset)
        val buf = Buf(cs, printer?.kanjiEnlarge)
        buf.cmd(ESC, 0x40)
        buf.cmd(ESC, 0x61, 0x01)
        buf.line(storeName ?: "Macau POS")
        buf.line("收據")
        buf.cmd(ESC, 0x61, 0x00)
        buf.sep(width)
        job.orderNo?.takeIf { it.isNotBlank() }?.let { buf.line("單號: $it") }
        job.tableName?.takeIf { it.isNotBlank() }?.let { buf.line("桌台: $it") }
        buf.sep(width)
        for (item in job.items.orEmpty()) {
            buf.line("${item.name} x${item.quantity}")
        }
        buf.sep(width)
        total?.let { buf.line("總計: MOP ${"%.0f".format(it)}") }
        paymentMethod?.takeIf { it.isNotBlank() }?.let { buf.line("支付: $it") }
        val ts = SimpleDateFormat("yyyy/M/d HH:mm:ss", Locale.getDefault()).format(Date())
        buf.line(ts)
        buf.cmd(LF, LF, LF)
        buf.cmd(GS, 0x56, 0x00)
        return buf.toBytes()
    }

    /**
     * 模板驅動渲染 —— 對應 desktop-companion/companion-server.mjs renderEscPos（同 web renderEscPosLines 同源）。
     * 消費 job.template 快照（blocks 嘅 size/bold/align/subSize/layout）+ job.content + job.items，
     * 令 Android 上路徑真正套用用家喺模板設嘅字型大小（修復之前完全 ignore 字型設定嘅 bug，見 docs/70）。
     * caller 應喺 job.template 非空時先 call 呢個；空缺就 fallback renderKitchenTicket/renderReceiptTicket。
     */
    fun renderTemplateTicket(job: PrintJobDto, printer: PrinterCfgDto?): ByteArray {
        val template = job.template
            ?: return EscPosRenderer.renderKitchenTicket(job, printer, null)
        val content = job.content ?: emptyMap()
        val items = job.items.orEmpty()
        val cs = resolveCharset(printer?.charset)
        // docs/96 §9：紙寬跟機（58mm → 32 格，80mm → 48 格）。
        // Sunmi V2 內置打印機係 58mm —— 唔計嘅話兩欄全部超出紙寬、價錢甩行。
        // 每行字符數：優先用 POS 計好寫入快照嘅值（`buildSnapshot` 按打印機紙闊 /
        // 標籤紙尺寸 preset 計），各 repo 讀同一個數；舊 job 冇 → 用本地判斷做 fallback。
        val cols = template.cols ?: paperColumns(printer)
        val buf = Buf(cs, printer?.kanjiEnlarge)
        buf.cmd(ESC, 0x40) // init

        val titleText = when (template.kind) {
            "receipt" -> "＊＊＊ 收據 ＊＊＊"
            "kitchen" -> "＊＊＊ 廚房 ＊＊＊"
            else -> ""
        }
        if (titleText.isNotEmpty()) {
            buf.align("center"); buf.style("m", true); buf.line(titleText)
            buf.reset()
        }

        val dashFull = "-".repeat(cols)
        // ── 分格線（divider）區塊（2026-09-10，POS 端契約見 macauPosSystem docs/114）──
        // 分格線係一行**純 ASCII**（`"-".repeat(n)`），實機有兩個坑：
        //   ① 放大狀態係常駐、`ESC !` 清唔走 `GS !` → 交畀 `emitLine()` 開頭嘅 `clearMagnify()`；
        //   ② 放大之後一行只裝得落 `cols / 2` 個 dash → dash 數量要跟 size 減半，
        //      否則 48 個雙闊 dash 會**自動折行** → 一條線變兩條（實紙 bug，docs/114 §1）。
        //
        //   - 區塊存在 + visible=false → 全張單唔印分格線（dividerOff）
        //   - 區塊存在 + visible=true  → 用區塊嘅 size（三邊一致，同 POS 預覽 / print hub）
        //   - 區塊唔存在（舊模板）      → 沿用「繼承上一行」舊語義（仍然保證一行）
        val dividerBlock = template.blocks.firstOrNull { it.id == "divider" }
        val dividerOff = dividerBlock != null && !dividerBlock.visible
        val fixedDividerSize = dividerBlock?.takeIf { it.visible }?.size
        fun dashLine(size: String): String =
            if (size == "s") dashFull else "-".repeat((cols + 1) / 2)
        fun rule() {
            if (dividerOff) return
            val size = fixedDividerSize ?: buf.currentSize()
            buf.style(size, false)
            buf.line(dashLine(size))
        }
        for (b in template.blocks) {
            if (!b.visible) continue
            // 分格線係設定型區塊：淨提供 size / 開關，自己唔會 emit 行
            if (b.id == "divider") continue
            if (b.id == "items") {
                rule()
                val layout = b.layout
                // 收據先印價錢 / 折扣（同 desktop-companion 同源）：廚房單同標籤單要同客人和廚房
                // 溝通嘅係「乜嘢菜、幾多份」，價錢係收據先有嘅資訊 → 呢段淨對 receipt 生效，
                // kitchen / label 出紙維持原樣（避免改動影響現有單據）。
                val isReceipt = template.kind == "receipt"
                items.forEachIndexed { i, it ->
                    val qty = if (it.quantity <= 0) 1 else it.quantity
                    val qtyText = "x$qty"
                    // 主行右欄：`x數量` +（有就）line 金額。it.price 係「折後單價 × 數量」，
                    // 同 POS 網頁預覽 `x{qty} ${price}` 完全一致（docs/95 §1）。
                    val priceText = if (isReceipt && it.price != null && it.price > 0.0) "  \$${num(it.price)}" else ""
                    val hasDiscount =
                        isReceipt &&
                            it.discountRate != null && it.discountRate > 0.0 && it.discountRate < 100.0 &&
                            it.savingAmount != null && it.savingAmount > 0.0

                    if (layout == "card") {
                        // 分層卡片：序號+品名 → 名下規則線 → 規格成組縮排 → 菜間留白
                        val nameLine = if (isReceipt) {
                            twoColumn("${i + 1}. ${it.name}", "$qtyText$priceText", cols)
                        } else {
                            "${i + 1}. ${it.name}  $qtyText"
                        }
                        buf.align(b.align); buf.style(b.size, b.bold); buf.line(nameLine)
                        rule()
                        for (s in it.specs.orEmpty()) {
                            // 規格行加購價錢靠右（docs/95 §用戶反饋 R1）：拆 (label, price) 後 twoColumn。
                            buf.align(b.align); buf.style(b.subSize, false)
                            val sp = splitSpecLine(s)
                            buf.line(
                                if (sp.price != null) twoColumn("  ${sp.label}", sp.price, cols)
                                else "  ${sp.label}"
                            )
                        }
                        it.note?.takeIf { n -> n.isNotBlank() }?.let { n ->
                            buf.align(b.align); buf.style(b.subSize, false); buf.line("  注：$n")
                        }
                        // 折扣 sub-line（反白）：熱敏紙印唔到設計介面嗰浸琥珀色，用黑底白字表達同一個層次
                        if (hasDiscount) {
                            buf.align(b.align); buf.style(b.subSize, false)
                            buf.line(discountLine(it, prefix = "  ", cols = cols), inverse = true)
                        }
                        if (i < items.size - 1) {
                            buf.align(b.align); buf.style("s", false); buf.line("")
                        }
                    } else {
                        val nameLine = if (isReceipt) {
                            twoColumn(it.name, "$qtyText$priceText", cols)
                        } else {
                            "${it.name}  $qtyText"
                        }
                        buf.align(b.align); buf.style(b.size, b.bold); buf.line(nameLine)
                        for (s in it.specs.orEmpty()) {
                            // 規格行加購價錢靠右（docs/95 §用戶反饋 R1）：同 card 版面邏輯一致。
                            buf.align(b.align); buf.style(b.subSize, false)
                            val sp = splitSpecLine(s)
                            buf.line(
                                if (sp.price != null) twoColumn("  · ${sp.label}", sp.price, cols)
                                else "  · ${sp.label}"
                            )
                        }
                        it.note?.takeIf { n -> n.isNotBlank() }?.let { n ->
                            buf.align(b.align); buf.style(b.subSize, false); buf.line("  注：$n")
                        }
                        if (hasDiscount) {
                            buf.align(b.align); buf.style(b.subSize, false)
                            buf.line(discountLine(it, prefix = "  · ", cols = cols), inverse = true)
                        }
                    }
                }
                rule()
            } else if (b.id == "qr_code") {
                // 收據二維碼：POS 端已 encode 好點陣；空白 / 網址太長 → job.qr 係 null → 乜都唔印
                job.qr?.let { qrRaster(buf, it, b.align.ifBlank { "center" }) }
            } else {
                val text = content[b.id] ?: continue
                buf.align(b.align); buf.style(b.size, b.bold); buf.line(text)
            }
        }
        buf.reset()
        buf.cmd(LF, LF, LF)
        buf.cmd(GS, 0x56, 0x00) // cut
        return buf.toBytes()
    }
}
