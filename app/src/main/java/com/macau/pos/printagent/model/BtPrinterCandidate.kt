package com.macau.pos.printagent.model

/**
 * 藍牙打印機列舉結果，對應 web 端 PrinterCandidate（connectionType="bluetooth"）。
 * 穩定鍵 = MAC address（藍牙冇 vid/pid，靠 address 唯一辨識）。
 */
data class BtPrinterCandidate(
    val address: String,
    val name: String?,
    val bonded: Boolean,
) {
    fun toJson(): org.json.JSONObject = org.json.JSONObject()
        .put("connectionType", "bluetooth")
        .put("key", address)
        .put("bluetoothAddress", address)
        .put("bluetoothName", name ?: address)
        .put("name", name ?: address)
        .put("label", name ?: address)
        .put("address", address)
        .put("bonded", bonded)

    companion object {
        /**
         * ⚠️ Android 12+ 冇 BLUETOOTH_CONNECT 權限時 `device.name` 會拋 SecurityException。
         * 呢個方法會被探索 receiver（主線程）呼叫 —— 一拋就係 app crash，
         * 所以讀取一律 runCatching，寧可顯示 MAC 都唔可以炸。
         */
        fun fromDevice(device: android.bluetooth.BluetoothDevice, bonded: Boolean): BtPrinterCandidate =
            BtPrinterCandidate(
                address = runCatching { device.address }.getOrDefault(""),
                name = runCatching { device.name }.getOrNull(),
                bonded = bonded,
            )
    }
}
