package com.macau.pos.printagent.net

import android.util.Log
import com.macau.pos.printagent.model.PrintJobDto
import com.macau.pos.printagent.model.PrinterCfgDto

/**
 * **出紙渲染的唯一分派位**（2026-10-30）。
 *
 * 之前 `SdkPrinter.print`（Android 直連／路径 A）同 `JobRunner.render`（雲中繼／路径 B）
 * 各自寫住同一個 `when(kind)` → 兩處都要改先知有新指令集。
 * 而家統一由呢度決定「ESC/POS 定 TSPL」，兩個 caller 都 call 同一個入口 ⇒
 * **加第三套指令集（例如 ZPL）淨係改一個位**。
 *
 * ## 分派規則
 *
 * | printer.family | 走邊度 | 原因 |
 * |---|---|---|
 * | `"label"` | `TsplRenderer`（TSPL/TSPL2） | 標籤機唔食 ESC/POS，混指令會出紙亂版 |
 * | 其他／冇值 | `EscPosRenderer` | 票據／廚房單既有行為**完全不變** |
 *
 * ⚠️ 判定**只**用 `family`，唔好再摻埋 `paperSize` 有冇 "x"（例如 `"100x75mm"`）：
 * `UsbPrinterDb` 對標籤機一定會帶 `family`，而且 POS 側打印機設定都係以 `family` 為準。
 */
object TicketRenderer {

    private const val TAG = "TicketRenderer"

    /** 呢部機係咪要走 TSPL？ */
    fun isLabelPrinter(printer: PrinterCfgDto?): Boolean =
        (printer?.family?.trim() ?: "") == "label"

    /**
     * 渲染一張票 —— 兩條出紙路徑都 call 呢個入口。
     *
     * @param kind `"receipt"` / `"kitchen"` / `"test"` / 其他。
     */
    fun renderTicket(
        job: PrintJobDto,
        printer: PrinterCfgDto?,
        kind: String,
        storeName: String?,
        paymentMethod: String?,
        total: Double?,
    ): ByteArray {
        if (isLabelPrinter(printer)) {
            Log.i(
                TAG,
                "標籤機（family=label，${printer?.name ?: printer?.id}）→ 出 TSPL，" +
                    "paperSize=${printer?.paperSize ?: "(未填)"}",
            )
            return TsplRenderer.renderTicket(job, printer, kind, storeName, paymentMethod, total)
        }
        return when {
            job.template != null -> EscPosRenderer.renderTemplateTicket(job, printer)
            kind == "receipt" -> EscPosRenderer.renderReceiptTicket(job, printer, storeName, paymentMethod, total)
            kind == "test" -> EscPosRenderer.renderTestPage(printer, storeName)
            else -> EscPosRenderer.renderKitchenTicket(job, printer, storeName)
        }
    }
}
