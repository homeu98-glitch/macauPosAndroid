package com.macau.pos.printagent.net

/**
 * USB 打印機型號表 —— 由 VID/PID 自動認出品牌 / 型號 / 編碼 / 紙張 / 中文倍大指令。
 *
 * ─────────────────────────────────────────────────────────────
 * ⚠️ 跨倉同源（改咗一定要同步三份）
 * ─────────────────────────────────────────────────────────────
 * | 倉 | 檔案 | 符號 |
 * |---|---|---|
 * | POS 網頁 | `src/lib/print-bridge/printer-models.ts` | `USB_PRINTER_DB` |
 * | desktop  | `companion-server.mjs` L75-518 | `USB_PRINTER_DB` |
 * | **Android** | **本檔** | `UsbPrinterDb.TABLE` |
 *
 * desktop 嘅 `node test-crossrepo-parity.mjs` 會掃 desktop ↔ POS 兩份；
 * 本檔改動要人手核對（Android 唔喺該腳本嘅掃描範圍）。
 *
 * ─────────────────────────────────────────────────────────────
 * 點解要呢張表？（Meituan 式「插上就見」）
 * ─────────────────────────────────────────────────────────────
 * 冇佢，商家要自己查 VID/PID、自己揀編碼同紙寬。有咗佢，`UsbPrinter.enumerate()`
 * 一見到設備就可以直接回「芯燁 XP-Q800 / Q200，gb18030，80mm」。
 *
 * 🔴 `family` 一定要帶去 POS 側（`{@link Meta.family}`）。冇佢，
 * POS 嘅 wizard 分唔到「漢印 SL42 係標籤機」定「漢印 TP805 係票據機」
 * → 標籤機會被當票據機排 80mm 連續紙版面 → **出紙亂版**。
 *
 * ─────────────────────────────────────────────────────────────
 * `kanjiEnlarge` 預設值嘅陷阱（唔可以統一！）
 * ─────────────────────────────────────────────────────────────
 * · 命中已知 VID　　→ 預設 `"FS!"`
 * · `deviceClass == 7` 通用 fallback → 預設 `"GS!"`
 *
 * 後者係商頌 POS-80 實機對照測試嘅結論；兩條路畀同一個值會令其中一邊印亂碼。
 */
object UsbPrinterDb {

    /** 硬件族。對應 POS 側 `DevicePrinterConfig.family`。 */
    enum class Family(val wire: String) {
        RECEIPT("receipt"),
        LABEL("label"),
        PORTABLE("portable"),
        ;

        companion object {
            fun fromWire(raw: String?): Family? =
                entries.firstOrNull { it.wire.equals(raw, ignoreCase = true) }
        }
    }

    /** 單一型號（`models` map 嘅 value）。所有欄位除 `model` 外都可缺失。 */
    data class ModelEntry(
        val model: String,
        val charset: String? = null,
        val paperSize: String? = null,
        val kanjiEnlarge: String? = null,
        val family: Family? = null,
        /** 標籤機介質幅寬上限（mm）。⚠️ 只喺**查證過廠商 spec** 才填。 */
        val maxLabelWidthMm: Int? = null,
        /** 標籤機介質幅寬下限（mm）。 */
        val minLabelWidthMm: Int? = null,
    )

    /** 單一品牌（VID entry）。 */
    data class VendorEntry(
        val brand: String,
        val defaultCharset: String,
        val defaultPaperSize: String,
        val defaultFamily: Family,
        val defaultKanjiEnlarge: String? = null,
        val models: Map<String, ModelEntry> = emptyMap(),
    )

    /**
     * 解析結果 —— 呢個就係要送去 POS 側嘅 `charset` / `paperSize` /
     * `kanjiEnlarge` / `family` / `max|minLabelWidthMm` 五＋一欄位。
     */
    data class Meta(
        val brand: String,
        val model: String,
        val charset: String,
        val paperSize: String,
        val kanjiEnlarge: String,
        /** true = 型號未命中（用品牌預設）或通用 fallback。POS 可用嚟提示「僅供參考」。 */
        val generic: Boolean,
        val family: Family,
        val maxLabelWidthMm: Int? = null,
        val minLabelWidthMm: Int? = null,
    )

    /**
     * 型號表本體。**必須與 desktop `companion-server.mjs` L75-518 逐字一致。**
     *
     * 20 個 VID entry：國內主流 13 + 國外品牌 7。
     */
    val TABLE: Map<String, VendorEntry> = mapOf(
        // ── 國內主流 ──
        "0x0483" to VendorEntry(
            brand = "芯燁 Xprinter",
            defaultCharset = "gb18030",
            defaultPaperSize = "80mm",
            defaultFamily = Family.RECEIPT,
            models = mapOf(
                "0x5740" to ModelEntry("芯燁 XP-Q800 / Q200"),
                "0x7000" to ModelEntry("芯燁 XP-58 / 80 series"),
                "0x7541" to ModelEntry("芯燁 XP-N160II（網口）"),
                "0x7561" to ModelEntry("芯燁 XP-58IIH（便攜）", paperSize = "58mm", family = Family.PORTABLE),
            ),
        ),
        "0x0416" to VendorEntry(
            brand = "佳博 Gprinter",
            defaultCharset = "gb18030",
            defaultPaperSize = "80mm",
            defaultFamily = Family.RECEIPT,
            models = mapOf(
                "0x5011" to ModelEntry("佳博 GP-58 / 80 series"),
                "0xAE01" to ModelEntry("佳博 GP-U80300"),
            ),
        ),
        "0x1A03" to VendorEntry(
            brand = "佳博 Gprinter",
            defaultCharset = "gb18030",
            defaultPaperSize = "80mm",
            defaultFamily = Family.RECEIPT,
            models = mapOf(
                "0x0042" to ModelEntry("佳博 GP-58MBIII", paperSize = "58mm"),
            ),
        ),
        // 佳博標籤機（TSPL 指令集，唔食 ESC/POS）
        "0x28E9" to VendorEntry(
            brand = "佳博 Gprinter",
            defaultCharset = "utf-8",
            defaultPaperSize = "100x75mm",
            defaultFamily = Family.LABEL,
            models = mapOf(
                "0x0189" to ModelEntry("佳博 GP-3120TU（標籤）", family = Family.LABEL),
                "0x018A" to ModelEntry("佳博 GP-2270T（標籤）", family = Family.LABEL),
            ),
        ),
        "0x0DD4" to VendorEntry(
            brand = "新北洋 SNBC",
            defaultCharset = "gb18030",
            defaultPaperSize = "80mm",
            defaultFamily = Family.RECEIPT,
            models = mapOf(
                "0x0203" to ModelEntry("新北洋 BTP-2002NP"),
                "0x0204" to ModelEntry("新北洋 BTP-R580"),
            ),
        ),
        "0x2BDF" to VendorEntry(
            brand = "容大 Rongta",
            defaultCharset = "gb18030",
            defaultPaperSize = "80mm",
            defaultFamily = Family.RECEIPT,
            models = mapOf(
                "0x0101" to ModelEntry("容大 RP80 / RP58"),
                "0x0202" to ModelEntry("容大 RP410（便攜）", paperSize = "58mm", family = Family.PORTABLE),
            ),
        ),
        "0x1FC9" to VendorEntry(
            brand = "中崎 Zjiang",
            defaultCharset = "gb18030",
            defaultPaperSize = "80mm",
            defaultFamily = Family.RECEIPT,
            models = mapOf(
                "0x2016" to ModelEntry("中崎 ZJ-5805 / 5890", paperSize = "58mm"),
                "0x2022" to ModelEntry("中崎 ZJ-80"),
            ),
        ),
        // 漢印 HPRT —— 國內標籤機第一梯隊；同一品牌兩種族，family 要逐型號標
        "0x2A17" to VendorEntry(
            brand = "漢印 HPRT",
            defaultCharset = "utf-8",
            defaultPaperSize = "100x75mm",
            defaultFamily = Family.LABEL,
            models = mapOf(
                "0x0001" to ModelEntry("漢印 SL42（標籤）", family = Family.LABEL),
                "0x0002" to ModelEntry("漢印 N41（標籤）", paperSize = "50x30mm", family = Family.LABEL),
                "0x0003" to ModelEntry("漢印 D45（標籤）", family = Family.LABEL),
                "0x0101" to ModelEntry("漢印 TP805（票據）", charset = "gb18030", paperSize = "80mm", family = Family.RECEIPT),
                "0x0102" to ModelEntry("漢印 TP809（票據）", charset = "gb18030", paperSize = "80mm", family = Family.RECEIPT),
                "0x0201" to ModelEntry("漢印 HM-A300（便攜）", charset = "gb18030", paperSize = "58mm", family = Family.PORTABLE),
            ),
        ),
        "0x28E0" to VendorEntry(
            brand = "得力 Deli",
            defaultCharset = "utf-8",
            defaultPaperSize = "100x75mm",
            defaultFamily = Family.LABEL,
            models = mapOf(
                "0x0001" to ModelEntry("得力 DL-888（標籤）", family = Family.LABEL),
                "0x0002" to ModelEntry("得力 DL-730C（標籤）", paperSize = "50x30mm", family = Family.LABEL),
                "0x0003" to ModelEntry("得力 DL-770D（標籤）", family = Family.LABEL),
                "0x0101" to ModelEntry("得力 DL-801P（票據）", charset = "gb18030", paperSize = "80mm", family = Family.RECEIPT),
                "0x0102" to ModelEntry("得力 DL-380AS（票據）", charset = "gb18030", paperSize = "58mm", family = Family.RECEIPT),
            ),
        ),
        "0x2E8A" to VendorEntry(
            brand = "快麥 KuaiMai",
            defaultCharset = "utf-8",
            defaultPaperSize = "100x75mm",
            defaultFamily = Family.LABEL,
            models = mapOf(
                "0x0001" to ModelEntry("快麥 K30（標籤）", family = Family.LABEL),
                "0x0002" to ModelEntry("快麥 L31（標籤）", paperSize = "50x30mm", family = Family.LABEL),
                "0x0101" to ModelEntry("快麥 K388（票據）", charset = "gb18030", paperSize = "80mm", family = Family.RECEIPT),
            ),
        ),
        "0x1A86" to VendorEntry(
            brand = "啟銳 Qirui",
            defaultCharset = "utf-8",
            defaultPaperSize = "100x75mm",
            defaultFamily = Family.LABEL,
            models = mapOf(
                "0x7523" to ModelEntry("啟銳 QR-386A（標籤）", family = Family.LABEL),
                "0x5523" to ModelEntry("啟銳 QR-668（標籤）", paperSize = "50x30mm", family = Family.LABEL),
            ),
        ),
        "0x0B36" to VendorEntry(
            brand = "立象 Argox",
            defaultCharset = "utf-8",
            defaultPaperSize = "100x75mm",
            defaultFamily = Family.LABEL,
            models = mapOf(
                "0x0001" to ModelEntry("立象 CP-2140（標籤）", family = Family.LABEL),
                "0x0002" to ModelEntry("立象 OS-2140（標籤）", family = Family.LABEL),
            ),
        ),
        "0x6868" to VendorEntry(
            brand = "商頌",
            defaultCharset = "gb18030",
            defaultPaperSize = "80mm",
            defaultFamily = Family.RECEIPT,
            models = mapOf(
                "0x0200" to ModelEntry("商頌 POS-80", kanjiEnlarge = "GS!"),
                "0x0201" to ModelEntry("商頌 POS-58", paperSize = "58mm", kanjiEnlarge = "GS!"),
            ),
        ),
        // ── 國外品牌 ──
        "0x04B8" to VendorEntry(
            brand = "Epson",
            defaultCharset = "gb18030",
            defaultPaperSize = "80mm",
            defaultFamily = Family.RECEIPT,
            models = mapOf(
                "0x0202" to ModelEntry("Epson TM-T88IV"),
                "0x0E03" to ModelEntry("Epson TM-T88V"),
                "0x0E15" to ModelEntry("Epson TM-T88VI"),
                "0x0203" to ModelEntry("Epson TM-T81II"),
            ),
        ),
        "0x0519" to VendorEntry(
            brand = "Star",
            defaultCharset = "gb18030",
            defaultPaperSize = "80mm",
            defaultFamily = Family.RECEIPT,
            models = mapOf(
                "0x0006" to ModelEntry("Star TSP100 (TSP143)"),
                "0x000D" to ModelEntry("Star mC-Print2"),
            ),
        ),
        "0x04CB" to VendorEntry(
            brand = "Citizen",
            defaultCharset = "gb18030",
            defaultPaperSize = "80mm",
            defaultFamily = Family.RECEIPT,
            models = mapOf(
                "0x1005" to ModelEntry("Citizen CT-S310II"),
                "0x109B" to ModelEntry("Citizen CT-S4000"),
            ),
        ),
        "0x04F9" to VendorEntry(
            brand = "Brother",
            defaultCharset = "gb18030",
            defaultPaperSize = "80mm",
            defaultFamily = Family.RECEIPT,
            models = mapOf(
                "0x2049" to ModelEntry("Brother TD-2xxx / RJ series"),
            ),
        ),
        "0x0A5F" to VendorEntry(
            brand = "斑馬 Zebra",
            defaultCharset = "utf-8",
            defaultPaperSize = "100x75mm",
            defaultFamily = Family.LABEL,
            models = mapOf(
                "0x0113" to ModelEntry("斑馬 ZD410（標籤）", family = Family.LABEL),
                "0x2011" to ModelEntry("斑馬 ZD420（標籤）", family = Family.LABEL),
            ),
        ),
        "0x1203" to VendorEntry(
            brand = "台半 TSC",
            defaultCharset = "utf-8",
            defaultPaperSize = "100x75mm",
            defaultFamily = Family.LABEL,
            models = mapOf(
                "0x0002" to ModelEntry("台半 TTP-244 Pro（標籤）", family = Family.LABEL),
                "0x0003" to ModelEntry("台半 TE200 / TE300（標籤）", family = Family.LABEL),
            ),
        ),
        "0x0C2E" to VendorEntry(
            brand = "SAM4S",
            defaultCharset = "gb18030",
            defaultPaperSize = "80mm",
            defaultFamily = Family.RECEIPT,
            models = mapOf(
                "0x0500" to ModelEntry("SAM4S GIANT-100"),
            ),
        ),
        "0x0498" to VendorEntry(
            brand = "Bixolon",
            defaultCharset = "gb18030",
            defaultPaperSize = "80mm",
            defaultFamily = Family.RECEIPT,
            models = mapOf(
                "0x0672" to ModelEntry("Bixolon SRP-350III"),
            ),
        ),
    )

    /** USB Printer Class（USB-IF 定義 `bDeviceClass = 07h`）—— driverless 通用打印機。 */
    private const val USB_CLASS_PRINTER = 7

    /** 未命中型號時嘅預設中文倍大指令（標準 ESC/POS 機）。 */
    private const val DEFAULT_KANJI_ENLARGE = "FS!"

    /** 通用 fallback 嘅中文倍大指令 —— **唔同上面嗰個**（商頌 POS-80 實測係 GS!）。 */
    private const val GENERIC_KANJI_ENLARGE = "GS!"

    /**
     * 硬件族：型號級 → 品牌級 → `"receipt"`。
     *
     * 分三層係因為「同一品牌出兩種機」好常見（佳博 / 漢印 / 得力 / 容大 都係）。
     */
    fun resolveFamily(vendor: VendorEntry?, model: ModelEntry?): Family =
        model?.family ?: vendor?.defaultFamily ?: Family.RECEIPT

    /**
     * 由 VID/PID + deviceClass 解析品牌 / 型號 / 編碼 / 紙張 / 中文倍大指令。
     *
     * 三層 fallback（**必須與 desktop `resolveUsbMeta` 逐字對齊**）：
     *  1. 命中已知 VID
     *     a. 命中型號 → 型號值 → 品牌預設
     *     b. 未命中型號 → 品牌預設（`generic = true`，**唔靠品牌瞎猜紙寬**）
     *  2. `deviceClass == 7` → 通用 ESC/POS 即插即用（`charset=gb18030`,
     *     `paperSize=80mm`, `kanjiEnlarge=GS!`），令商頌 POS-80 等機「插上就見」
     *  3. 其他 → `null`（唔係打印機，唔枚舉滑鼠 / 鍵盤）
     *
     * @param vendorId 十進位 VID（`UsbDevice.vendorId`，例如 0x0483 = 1155）
     * @param productId 十進位 PID
     * @param deviceClass `UsbDevice.deviceClass`；唔確定就傳 0
     */
    fun resolve(vendorId: Int, productId: Int, deviceClass: Int = 0): Meta? {
        val vid = toHexId(vendorId)
        val vendor = vid?.let { TABLE[it] }
        if (vendor != null) {
            val pid = toHexId(productId)
            val model = pid?.let { vendor.models[it] }
            if (model != null) {
                return Meta(
                    brand = vendor.brand,
                    model = model.model,
                    charset = model.charset ?: vendor.defaultCharset,
                    paperSize = model.paperSize ?: vendor.defaultPaperSize,
                    kanjiEnlarge = model.kanjiEnlarge
                        ?: vendor.defaultKanjiEnlarge
                        ?: DEFAULT_KANJI_ENLARGE,
                    generic = false,
                    family = resolveFamily(vendor, model),
                    // 標籤機介質幅寬：只喺查證過廠商 spec 才填，填錯比唔填更差。
                    maxLabelWidthMm = model.maxLabelWidthMm,
                    minLabelWidthMm = model.minLabelWidthMm,
                )
            }
            return Meta(
                brand = vendor.brand,
                model = vendor.brand,
                charset = vendor.defaultCharset,
                paperSize = vendor.defaultPaperSize,
                kanjiEnlarge = vendor.defaultKanjiEnlarge ?: DEFAULT_KANJI_ENLARGE,
                generic = true,
                family = resolveFamily(vendor, null),
                // 未命中型號 → 唔知紙寬，唔可以靠品牌瞎猜。
                maxLabelWidthMm = null,
                minLabelWidthMm = null,
            )
        }
        // USB Printer Class（07h）通用即插即用 fallback：
        // 未知 VID 都照認，令商頌 POS-80 等機「插上就見」。
        if (deviceClass == USB_CLASS_PRINTER) {
            return Meta(
                brand = "USB 打印機",
                model = "通用 ESC/POS (USB Printer Class)",
                charset = "gb18030",
                paperSize = "80mm",
                kanjiEnlarge = GENERIC_KANJI_ENLARGE,
                generic = true,
                family = Family.RECEIPT,
            )
        }
        return null
    }

    /**
     * 數字 VID/PID → `"0xXXXX"` 大寫十六進位（與 desktop `toHexId` 同口徑）。
     * 無效（≤ 0）返 null。
     */
    fun toHexId(raw: Int): String? {
        if (raw <= 0) return null
        return "0x" + raw.toString(16).uppercase().padStart(4, '0')
    }
}
