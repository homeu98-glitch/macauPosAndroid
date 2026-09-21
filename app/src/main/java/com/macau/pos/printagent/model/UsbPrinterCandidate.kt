package com.macau.pos.printagent.model

import com.macau.pos.printagent.net.UsbKey
import com.macau.pos.printagent.net.UsbPrinterDb
import org.json.JSONObject

/**
 * 一部已偵測到嘅 USB 打印機。
 *
 * ─────────────────────────────────────────────────────────────
 * 2026-09-18：欄位對齊 desktop `/api/usb`
 * ─────────────────────────────────────────────────────────────
 * 原本只有 `vendorId / productId / deviceName / productName / serialNumber /
 * key / brandLabel / hasPermission` —— 即係 POS 側 wizard 收到嘅候選**冇**
 * `charset` / `paperSize` / `kanjiEnlarge` / `family`，全部走 fallback。
 *
 * 後果（具體）：漢印 SL42 係標籤機（`family=label`、`charset=utf-8`、
 * `paperSize=100x75mm`），Android 上被當 `receipt` → 套 80mm 連續紙版面 →
 * 打去 100×75 標籤卷 → **出紙亂版**。
 *
 * 而家 [meta] 由 [UsbPrinterDb.resolve] 解析，[toJson] 全部帶去 POS 側，
 * 與 desktop `enumerateUsbPrinters()` 回嘅欄位一致。
 */
data class UsbPrinterCandidate(
    val vendorId: Int,
    val productId: Int,
    val deviceName: String?,
    val productName: String?,
    val serialNumber: String?,
    val hasPermission: Boolean,
    /** 型號表解析結果；未知 VID 又唔係 class 7 → null（理論上唔會入到 enumerate）。 */
    val meta: UsbPrinterDb.Meta?,
) {
    /** 穩定識別（與 web 端 `DevicePrinterConfig.usbVendorId/usbProductId` 對齊）。 */
    val key: String get() = UsbKey(vendorId, productId, serialNumber).stableKey()

    val vendorHex: String get() = UsbPrinterDb.toHexId(vendorId) ?: ""
    val productHex: String get() = UsbPrinterDb.toHexId(productId) ?: ""

    /** 顯示名（品牌 + 型號）。 */
    val brandLabel: String get() = meta?.brand ?: brandLabelFor(vendorId, productName, deviceName)

    /** 型號表認到嘅具體型號；未命中型號（generic）回品牌名。 */
    val modelLabel: String get() = meta?.model ?: brandLabel

    fun toJson(): JSONObject = JSONObject()
        .put("connectionType", "usb")
        .put("key", key)
        .put("vendorId", vendorId)
        .put("productId", productId)
        .put("deviceName", deviceName ?: "")
        .put("productName", productName ?: "")
        .put("serialNumber", serialNumber ?: "")
        .put("label", brandLabel)
        .put("name", brandLabel)
        .put("usbLabel", brandLabel)
        .put("hasPermission", hasPermission)
        // ── 以下 6 個（+ generic）係 2026-09-18 補上，對齊 desktop /api/usb ──
        // POS 側 printer-wizard-modal.tsx 嘅 selectUsbDevice() 直接消費
        // charset / paperSize / kanjiEnlarge / family 呢 4 個。
        .put("model", meta?.model ?: "")
        .put("charset", meta?.charset ?: "")
        .put("paperSize", meta?.paperSize ?: "")
        .put("kanjiEnlarge", meta?.kanjiEnlarge ?: "")
        .put("family", meta?.family?.wire ?: "")
        .put("generic", meta?.generic ?: true)
        .put("recognized", meta != null)
        .put("maxLabelWidthMm", meta?.maxLabelWidthMm ?: JSONObject.NULL)
        .put("minLabelWidthMm", meta?.minLabelWidthMm ?: JSONObject.NULL)

    companion object {
        /** 手寫嘅廠商名表（**只作 fallback 顯示**；型號表認到就唔會用到）。 */
        private val VENDOR_NAMES = mapOf(
            0x04B8 to "Epson",
            0x0519 to "Star",
            0x04C8 to "Citizen",
            0x1504 to "Bixolon",
            0x042A to "Seiko",
            0x0483 to "Xprinter",
            0x0416 to "Gprinter",
            0x0403 to "FTDI",
            0x067B to "Prolific",
            0x1A86 to "QinHeng",
        )

        fun fromDevice(
            vendorId: Int,
            productId: Int,
            deviceName: String?,
            productName: String?,
            serialNumber: String?,
            hasPermission: Boolean,
            deviceClass: Int = 0,
        ): UsbPrinterCandidate = UsbPrinterCandidate(
            vendorId = vendorId,
            productId = productId,
            deviceName = deviceName,
            productName = productName,
            serialNumber = serialNumber,
            hasPermission = hasPermission,
            meta = UsbPrinterDb.resolve(vendorId, productId, deviceClass),
        )

        private fun brandLabelFor(vendorId: Int, productName: String?, deviceName: String?): String =
            productName?.takeIf { it.isNotBlank() }
                ?: VENDOR_NAMES[vendorId]
                ?: deviceName?.takeIf { it.isNotBlank() }
                ?: "USB 打印機 (VID ${vendorId.toString(16).uppercase().padStart(4, '0')})"
    }
}
