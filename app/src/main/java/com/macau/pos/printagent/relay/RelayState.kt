package com.macau.pos.printagent.relay

/**
 * 中繼嘅 in-memory 狀態，淨係畀 UI（RelayActivity）讀。
 *
 * 刻意唔用 LiveData / Flow：呢個 app 得一個 UI 畫面，加 lifecycle 依賴唔抵；
 * Activity 用 1s handler 輪詢就得（見 RelayActivity.refresh()）。
 */
object RelayState {

    /** idle / pairing / connecting / online / offline / error */
    @Volatile
    var phase: String = "idle"

    @Volatile
    var realtimeConnected: Boolean = false

    @Volatile
    var lastWakeAt: Long = 0L

    @Volatile
    var lastClaimAt: Long = 0L

    @Volatile
    var lastHeartbeatAt: Long = 0L

    @Volatile
    var printedCount: Int = 0

    @Volatile
    var failedCount: Int = 0

    @Volatile
    var lastMessage: String = ""

    @Volatile
    var sunmiReady: Boolean = false

    @Volatile
    var sunmiModal: String? = null

    @Volatile
    var localIp: String? = null

    fun note(msg: String) {
        lastMessage = msg
    }

    fun reset() {
        phase = "idle"
        realtimeConnected = false
        lastWakeAt = 0L
        lastClaimAt = 0L
        lastHeartbeatAt = 0L
        printedCount = 0
        failedCount = 0
        lastMessage = ""
    }
}
