package com.macau.pos.printagent.net

import android.util.Log
import com.macau.pos.printagent.model.PrintJobDto
import com.macau.pos.printagent.model.PrinterCfgDto
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * **TSPL / TSPL2 標籤機渲染器**（2026-10-30，Android 版第一版）。
 *
 * ## 為何要有第二套 renderer
 *
 * 漢印 SL42、佳博 GP-3120TU、斑馬 ZD410 呢類**標籤機**物理上就唔係行 ESC/POS：
 * 佢哋食 **TSPL**（`SIZE … GAP … TEXT x,y,… PRINT 1`）。同一張 one-bytes 餵落去，
 * 結果係**出紙亂版／white paper** —— 因為標籤機冇「連續紙」概念，冇 `ESC @`、
 * 冇自動換行、冇字符網格，全部要**絕對座標**。
 *
 * 之前 `EscPosRenderer` 對 `printer.family` 引用數係 0 ⇒ `family:"label"` 一樣出 ESC/POS
 * ⇒ SL42（100×75mm）會被排成 80mm 連續紙。**呢個 renderer 就係補呢個缺口。**
 *
 * ## 觸發條件（唯一）
 *
 * 得 printer 快照 `family == "label"` 先會行呢度，其餘全部照舊行 `EscPosRenderer`
 * （見 `TicketRenderer.renderTicket`）。⇒ **對現有票據機零影響**。
 *
 * ## 🔴 已知限制（第一版，未經實機）
 *
 * 1. **字型靠打印機內建 CJK 點陣**（`TSS16.BF2` / `TSS24.BF2`）。
 *    大部分國產 TSC 兼容機都有；**冇嘅機會出白紙**。要完全唔靠機體字型
 *    就要改成 Canvas 畫Bitmap → `BITMAP` 指令（Phase 3，成本高好多）。
 * 2. **中文字一律 UTF-8**（`CODEPAGE UTF-8`）。舊 firmware 唔認 CODEPAGE 就要郁掉呢一行。
 * 3. **超出標籤高度嘅內容會被截斷**（標籤機唔會自動進第二張），並 `Log.w` 警告。
 * 4. QR 用原生 `QRCODE` 指令（要 `job.qrUrl`）；冇 url 就唔印 QR
 *    （用 `job.qr` 點陣做 `BITMAP` 係 Phase 2）。
 */
object TsplRenderer {

    private const val TAG = "TsplRenderer"

    /** 203 dpi ＝ 8 dots/mm。標籤機主流就係呢個密度（300dpi 嗰啲 Phase 2 先支援）。 */
    private const val DOTS_PER_MM = 8

    /** 左右／上下安全邊距（dots）。標籤卷通常有撕位，唔好貼邊印。 */
    private const val MARGIN_DOTS = 8

    /** 標籤紙規格。冇寫 paperSize 時用 SL42 嘅 100×75mm 做兜底。 */
    private data class LabelSpec(val widthMm: Int, val heightMm: Int, val gapMm: Int)

    /** 一行要印嘅嘢：`right` 非空嗰陣會自己計 x 靠右（模擬 ESC/POS twoColumn）。 */
    private data class LabelLine(
        val text: String,
        /** true = 大字（TSS24.BF2），false = 細字（TSS16.BF2）；有默認值先排得到 named arg。 */
        val big: Boolean = false,
        val right: String? = null,
    )

    /**
     * 由 `printer.paperSize` 解析標籤尺寸。
     *
     * 支援 `"100x75mm"`、`"50x30mm"`、`"100X75 mm"`；解析唔到就返 null
     * （caller 用兜底 100×75mm）。`"58mm"`、`"80mm"` 呢類連續紙写法 **刻意唔認**
     * —— 標籤機唔該食呢個。
     */
    private fun parseLabelSpec(paperSize: String?): LabelSpec? {
        val m = Regex("(\\d+)\\s*[xX×]\\s*(\\d+)\\s*mm").find(paperSize ?: "") ?: return null
        val w = m.groupValues[1].toIntOrNull() ?: return null
        val h = m.groupValues[2].toIntOrNull() ?: return null
        if (w <= 0 || h <= 0) return null
        return LabelSpec(w, h, GAP_MM)
    }

    private const val GAP_MM = 2

    /**
     * 渲染一張標籤票。
     *
     * @param kind `"receipt"`（要錢） / `"kitchen"` / `"test"`。
     */
    fun renderTicket(
        job: PrintJobDto,
        printer: PrinterCfgDto?,
        kind: String,
        storeName: String?,
        paymentMethod: String?,
        total: Double?,
    ): ByteArray {
        val spec = clampTo(printer, parseLabelSpec(printer?.paperSize) ?: LabelSpec(100, 75, GAP_MM))
        val widthDots = spec.widthMm * DOTS_PER_MM
        val heightDots = spec.heightMm * DOTS_PER_MM
        val usableWidth = widthDots - MARGIN_DOTS * 2

        val lines = buildLines(job, kind, storeName, paymentMethod, total)

        val sb = StringBuilder()
        // SIZE / GAP 一定要喺 CLS 之前：呢兩個係「頁面設定」，CLS 用佢哋決定畫布。
        sb.append("SIZE ${spec.widthMm} mm,${spec.heightMm} mm\r\n")
        sb.append("GAP ${spec.gapMm} mm,0 mm\r\n")
        sb.append("DIRECTION 0\r\n")
        sb.append("REFERENCE 0,0\r\n")
        sb.append("CODEPAGE UTF-8\r\n")
        sb.append("CLS\r\n")

        var y = MARGIN_DOTS
        for (line in lines) {
            val rows = wrap(line.text, usableWidth, line.big)
            for ((i, row) in rows.withIndex()) {
                if (y + lineHeight(line.big) > heightDots - MARGIN_DOTS) {
                    Log.w(TAG, "標籤版面超出高度（${spec.widthMm}x${spec.heightMm}mm），內容被截斷")
                    return finish(sb)
                }
                if (i == rows.size - 1 && line.right != null) {
                    // 靠右嗰欄：由右邊界倒計 x（同 ESC/POS twoColumn 嘅「嘗試對齊」同一意圖）
                    val rightX = widthDots - MARGIN_DOTS - textWidth(line.right, line.big)
                    sb.append(text(MARGIN_DOTS, y, row, line.big))
                    if (rightX > MARGIN_DOTS + textWidth(row, line.big)) {
                        sb.append(text(rightX, y, line.right, line.big))
                    }
                } else {
                    sb.append(text(MARGIN_DOTS, y, row, line.big))
                }
                y += lineHeight(line.big)
            }
        }

        // 二維碼：原生 QRCODE（要 qrUrl）。放喺文字下面，留 8 dots 呼吸位。
        val qrUrl = job.qrUrl?.takeIf { it.isNotBlank() }
        if (qrUrl != null && y + 80 <= heightDots - MARGIN_DOTS) {
            sb.append("QRCODE $MARGIN_DOTS,$y,M,4,A,0,\"${escape(qrUrl)}\"\r\n")
        }

        return finish(sb)
    }

    private fun finish(sb: StringBuilder): ByteArray = sb.append("PRINT 1\r\n").toString()
        .toByteArray(Charsets.UTF_8)

    /** 用 `max|minLabelWidthMm` 夾住 —— web 用呢兩個值攔「放唔落嘅標籤紙」，呢度照做。 */
    private fun clampTo(printer: PrinterCfgDto?, spec: LabelSpec): LabelSpec {
        val max = printer?.maxLabelWidthMm
        val min = printer?.minLabelWidthMm
        val w = when {
            max != null && spec.widthMm > max -> {
                Log.w(TAG, "標籤紙寬 ${spec.widthMm}mm 超出機身上限 ${max}mm，改用上限")
                max
            }
            min != null && spec.widthMm < min -> min
            else -> spec.widthMm
        }
        return spec.copy(widthMm = w)
    }

    private fun fontName(big: Boolean): String = if (big) "TSS24.BF2" else "TSS16.BF2"

    /** 行高 = 字高一倍行距；大細字各留少少空間，密排會睇唔清。 */
    private fun lineHeight(big: Boolean): Int = if (big) 30 else 20

    /** 單個字符寬度（dots）：CJK 全寬、ASCII 半寬。 */
    private fun charWidth(cp: Int, big: Boolean): Int {
        val unit = if (big) 12 else 8
        return if (isWide(cp)) unit * 2 else unit
    }

    private fun isWide(cp: Int): Boolean =
        (cp in 0x1100..0x115F) || (cp in 0x2E80..0xA4CF) ||
            (cp in 0xAC00..0xD7A3) || (cp in 0xF900..0xFAFF) ||
            (cp in 0xFE30..0xFE4F) || (cp in 0xFF00..0xFF60) ||
            (cp in 0xFFE0..0xFFE6) || (cp in 0x20000..0x3FFFD)

    private fun textWidth(s: String, big: Boolean): Int {
        var w = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            w += charWidth(cp, big)
            i += Character.charCount(cp)
        }
        return w
    }

    /** TSPL 冇自動換行 —— **一定要自己 wrap**，否則長品名會印出紙外。 */
    private fun wrap(text: String, usableWidth: Int, big: Boolean): List<String> {
        val out = mutableListOf<String>()
        var line = StringBuilder()
        var width = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val cw = charWidth(cp, big)
            if (width + cw > usableWidth && line.isNotEmpty()) {
                out.add(line.toString())
                line = StringBuilder()
                width = 0
            }
            line.appendCodePoint(cp)
            width += cw
            i += Character.charCount(cp)
        }
        if (line.isNotEmpty() || out.isEmpty()) out.add(line.toString())
        return out
    }

    /**
     * TEXT 指今：句尾一定要 `\r\n`（TSPL 係行導向）。
     * 引號用 `escape()` 處理；字串入面嘅換行一定要清走（一行 TEXT = 一行）。
     */
    private fun text(x: Int, y: Int, content: String, big: Boolean): String =
        "TEXT $x,$y,\"${fontName(big)}\",0,1,1,\"${escape(content)}\"\r\n"

    /** TSPL 用雙引號包內容 ⇒ 內容有 `"` 或換行會**整句指令壞掉**。 */
    private fun escape(s: String): String = s
        .replace("\r", " ")
        .replace("\n", " ")
        .replace("\"", "'")
        .trim()

    // ---- 版面：由 job 砌出標籤要印嘅行 ----

    private fun buildLines(
        job: PrintJobDto,
        kind: String,
        storeName: String?,
        paymentMethod: String?,
        total: Double?,
    ): List<LabelLine> {
        val isReceipt = kind == "receipt"
        val out = mutableListOf<LabelLine>()

        if (kind == "test") {
            out += LabelLine(storeName?.takeIf { it.isNotBlank() } ?: "MACAU POS", big = true)
            out += LabelLine("TSPL 測試頁", big = true)
            out += LabelLine(SimpleDateFormat("yyyy/M/d HH:mm:ss", Locale.getDefault()).format(Date()))
            return out
        }

        // 標題 + 單號：標籤最醒目嗰行一定要係「邊張單」
        job.orderNo?.takeIf { it.isNotBlank() }?.let { out += LabelLine("單號 $it", big = true) }
        job.tableName?.takeIf { it.isNotBlank() }?.let { out += LabelLine("枱／位置：$it") }
        when (kind) {
            "receipt" -> out += LabelLine("收據", big = true)
            "kitchen" -> out += LabelLine("廚房", big = true)
        }

        // 分格線：TSPL 冇 dash 概念，用一條底線（BAR）會更好，
        // 但第一版先用一行 "-" 頂住（純 ASCII，斷行都睇得出係分隔）。
        out += LabelLine("-".repeat(24))

        job.items.orEmpty().forEachIndexed { i, it ->
            val qty = if (it.quantity <= 0) 1 else it.quantity
            val priceText = if (isReceipt && it.price != null && it.price > 0.0) {
                "\$${"%.2f".format(Locale.US, it.price).trimEnd('0').trimEnd('.').ifEmpty { "0" }}"
            } else {
                null
            }
            out += LabelLine("${i + 1}. ${it.name}", big = true, right = "x$qty")
            if (priceText != null) out += LabelLine("", right = priceText)
            for (s in it.specs.orEmpty()) {
                out += LabelLine("　 · ${s.trim()}")
            }
            it.note?.takeIf { n -> n.isNotBlank() }?.let { n -> out += LabelLine("　 注：$n") }
        }

        out += LabelLine("-".repeat(24))
        if (isReceipt) {
            total?.let { out += LabelLine("總計", big = true, right = "MOP ${"%.0f".format(it)}") }
            paymentMethod?.takeIf { it.isNotBlank() }?.let { out += LabelLine("支付", right = it) }
        }
        storeName?.takeIf { it.isNotBlank() }?.let { out += LabelLine(it) }
        out += LabelLine(SimpleDateFormat("yyyy/M/d HH:mm:ss", Locale.getDefault()).format(Date()))
        return out
    }
}
