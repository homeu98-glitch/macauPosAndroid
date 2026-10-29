package com.macau.pos.printagent.net

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.macau.pos.printagent.model.BtPrinterCandidate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID

/**
 * 藍牙 ESC/POS 打印機傳輸層（標準 SPP / RFCOMM）。
 *
 * ─────────────────────────────────────────────────────────────
 * 為何要有呢一層（2026-10-29 · P4 藍牙）
 * ─────────────────────────────────────────────────────────────
 * 舊版藍牙只行廠商 AAR（`POSConnect.DEVICE_TYPE_BLUETOOTH`）。AAR 內部其實都係 SPP，
 * 但佢嘅 `connect(info, callback)` **冇 timeout 保證**、錯誤碼唔透明、亦唔會告訴你
 * 「未配對」定「藍牙未開」。P2 已證實同類問題會令 UI 永久轉圈。
 *
 * 藍牙 ESC/POS 機 99% 都支援 Bluetooth SIG 標準 SPP（UUID 00001101-…），
 * 所以呢度自己做 RFCOMM：可控 timeout、可控 chunk 節奏、錯誤可以講人話。
 * 失敗先由 `SdkPrinter` fallback 去 AAR（應付極少數非標準 UUID 嘅機）。
 *
 * ⚠️ 三個一定要記住嘅 Android 陷阱
 *  1. `BluetoothSocket.connect()` 係**阻塞**且**唔響應 coroutine cancellation**。
 *     到時到候一定要由另一條 thread `close()` 條 socket，connect 才會拋 IOException 出嚟。
 *     單靠 `withTimeoutOrNull { connect() }` 係**冇用**嘅（會等成世）。
 *  2. 探索（discovery）進行中 connect 會極慢甚至直接失敗 → 連線前一定要 `cancelDiscovery()`。
 *  3. Android 12+（API 31）讀 `device.name` / `bondedDevices` 要 BLUETOOTH_CONNECT，
 *     冇權限會拋 SecurityException。所有讀取都要 runCatching，唔可以任佢炸到 app。
 */
class BtPrinter(private val context: Context) {

    private val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? = manager?.adapter

    fun isSupported(): Boolean = adapter != null

    fun isEnabled(): Boolean = runCatching { adapter?.isEnabled == true }.getOrDefault(false)

    /** API 31+ 需要 BLUETOOTH_CONNECT runtime 權限先可以 connect / 讀裝置名。 */
    fun hasConnectPermission(): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        return context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
    }

    /**
     * 探索權限：
     *  · API 31+ → BLUETOOTH_SCAN
     *  · API 23–30 → ACCESS_FINE_LOCATION（Android 系統硬性要求；冇就靜默掃唔到）
     *  · API < 23 → 唔使 runtime 權限
     */
    fun hasScanPermission(): Boolean {
        if (Build.VERSION.SDK_INT < 23) return true
        if (Build.VERSION.SDK_INT >= 31) {
            return context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED
        }
        return context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }

    /**
     * 已配對（bonded）嘅藍牙設備，可直接打印。
     * 冇權限 / 藍牙關閉 → 回空清單（呼叫端另有狀態查詢，唔會靜默當「冇機」）。
     */
    fun enumerateBonded(): List<BtPrinterCandidate> {
        if (!isSupported() || !isEnabled() || !hasConnectPermission()) return emptyList()
        val devices = runCatching { adapter?.bondedDevices }.getOrNull() ?: return emptyList()
        return devices.mapNotNull { dev ->
            // 單一裝置讀名失敗唔應該令整個清單消失
            runCatching { BtPrinterCandidate.fromDevice(dev, bonded = true) }.getOrNull()
        }.sortedWith(compareByDescending<BtPrinterCandidate> { looksLikePrinter(it.name) }
            .thenBy { it.name ?: it.address })
    }

    /** 由 MAC 取 device；格式唔啱 / 未配對 → null。 */
    fun findBonded(address: String): BluetoothDevice? {
        val mac = normalizeMac(address) ?: return null
        if (!hasConnectPermission()) return null
        val dev = runCatching { adapter?.getRemoteDevice(mac) }.getOrNull() ?: return null
        return dev.takeIf { runCatching { it.bondState }.getOrDefault(BluetoothDevice.BOND_NONE) == BluetoothDevice.BOND_BONDED }
    }

    /**
     * 打印 byte[] 去指定 MAC。
     *
     * timeoutMs 係**整條連線 + 傳輸**嘅硬上限（預設 15s）。
     * 任何情況都一定返 Result，絕不靜默掛起。
     */
    suspend fun printBytes(
        address: String,
        data: ByteArray,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (!isSupported()) {
            return@withContext fail(BT_UNSUPPORTED, "呢部機冇藍牙（或藍牙服務未啟動）")
        }
        if (!hasConnectPermission()) {
            return@withContext fail(BT_NEEDS_PERMISSION, "藍牙打印機需要授權，請允許「附近的裝置」權限後重試")
        }
        if (!isEnabled()) {
            return@withContext fail(BT_DISABLED, "藍牙未開啟，請喺通知欄／設定開啟藍牙再試")
        }
        val mac = normalizeMac(address)
            ?: return@withContext fail(BT_BAD_ADDRESS, "藍牙位址格式不正確：$address（應該係 AA:BB:CC:DD:EE:FF）")
        val dev = runCatching { adapter?.getRemoteDevice(mac) }.getOrNull()
            ?: return@withContext fail(BT_BAD_ADDRESS, "搵唔到藍牙裝置 $mac")

        val bondState = runCatching { dev.bondState }.getOrDefault(BluetoothDevice.BOND_NONE)
        if (bondState != BluetoothDevice.BOND_BONDED) {
            return@withContext fail(
                BT_NOT_BONDED,
                "「${safeName(dev, mac)}」未配對，請先喺精靈撳「配對」或系統設定配對一次",
            )
        }

        var socket: BluetoothSocket? = null
        // ⚠️ 刻意用**獨立 scope**而唔用 coroutineScope{}：
        // coroutineScope 會等齊所有子 coroutine 才返回。如果某些廠商 ROM 嘅 close()
        // 中斷唔到阻塞緊嘅 connect()，printBytes 就會變相永久掛住 —— 違反
        // 「任何情況都一定返 Result」嘅鐵律。獨立 scope 超時後即刻返回，
        // 卡住嘅背景 job 由 scope.cancel() + socket.close() 收拾。
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            socket = dev.createRfcommSocketToServiceRecord(SPP_UUID)
            // 探索會嚴重拖慢連線（部分機會直接 connect timeout）
            runCatching { adapter?.cancelDiscovery() }

            val sock = socket
            val job = scope.async { runCatching { transfer(sock, data) } }
            val result: Result<Unit>? = withTimeoutOrNull(timeoutMs.toLong()) { job.await() }

            if (result == null) {
                // 阻塞中嘅 connect／write 唔響應 cancellation，一定要 close 條 socket 先解到
                runCatching { sock.close() }
                job.cancel()
                scope.cancel()
                return@withContext fail(
                    BT_TIMEOUT,
                    "藍牙連線逾時（${timeoutMs / 1000}s 冇完成）— 打印機關咗／距離太遠／已被另一個 app 佔用",
                )
            }
            val err = result.exceptionOrNull()
            if (err != null) {
                Log.w(TAG, "BT 傳輸失敗 $mac", err)
                return@withContext fail(
                    BT_IO,
                    "藍牙傳輸失敗：${err.message ?: err.javaClass.simpleName}" +
                        "（如果一直失敗，喺系統設定「取消配對」再重新配對一次）",
                )
            }
            // 同 LAN raw 一樣：close 之前要畀打印機食完 buffer，否則尾段（總數／二維碼）會被截。
            delay(DRAIN_MS)
            Result.success(Unit)
        } catch (e: Exception) {
            Log.w(TAG, "BT 連線失敗 $mac", e)
            Result.failure(
                BtPrintError(
                    BT_IO,
                    e.message ?: "藍牙打印失敗",
                    "藍牙打印失敗：${e.message ?: e.javaClass.simpleName}",
                )
            )
        } finally {
            runCatching { socket?.close() }
            scope.cancel()
        }
    }

    /** connect + 分塊寫入。喺 async 內執行，畀外面用 timeout + close 中斷。 */
    private suspend fun transfer(socket: BluetoothSocket, data: ByteArray) {
        socket.connect()
        val out = socket.outputStream
        var offset = 0
        while (offset < data.size) {
            val end = minOf(offset + CHUNK, data.size)
            out.write(data, offset, end - offset)
            offset = end
            // 藍牙機 receive buffer 通常細（1–2 KB）。4 KB 連環寫會掉包 → 出紙亂碼／截尾。
            if (offset < data.size) delay(CHUNK_GAP_MS)
        }
        out.flush()
    }

    private fun safeName(dev: BluetoothDevice, fallback: String): String =
        runCatching { dev.name }.getOrNull()?.takeIf { it.isNotBlank() } ?: fallback

    private fun fail(code: String, message: String): Result<Unit> =
        Result.failure(BtPrintError(code, message, message))

    /**
     * 藍牙打印錯誤。`code` 畀 native / 診斷分辨（BT_DISABLED / BT_NOT_BONDED / BT_TIMEOUT …），
     * `userMessage` 係直接顯示畀店員睇嘅人話。
     */
    class BtPrintError(
        val code: String,
        message: String,
        val userMessage: String = message,
    ) : Exception(message)

    companion object {
        private const val TAG = "BtPrinter"

        /** Bluetooth SIG 標準 Serial Port Profile。所有 ESC/POS 藍牙機都行呢個。 */
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        const val DEFAULT_TIMEOUT_MS = 15_000

        /** 1 KB／包：配合 12ms 間隔，約 80 KB/s，足夠有餘又唔會谷爆機仔 buffer。 */
        private const val CHUNK = 1024
        private const val CHUNK_GAP_MS = 12L

        /** close 前等打印機吐完紙（藍牙比 LAN 慢）。 */
        private const val DRAIN_MS = 600L

        const val BT_UNSUPPORTED = "BT_UNSUPPORTED"
        const val BT_NEEDS_PERMISSION = "BT_NEEDS_PERMISSION"
        const val BT_DISABLED = "BT_DISABLED"
        const val BT_BAD_ADDRESS = "BT_BAD_ADDRESS"
        const val BT_NOT_BONDED = "BT_NOT_BONDED"
        const val BT_TIMEOUT = "BT_TIMEOUT"
        const val BT_IO = "BT_IO"

        private val MAC_RE = Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")

        /** 只接受真正 MAC；名稱／空字串 → null（避免 getRemoteDevice 拋 IllegalArgumentException）。 */
        fun normalizeMac(raw: String?): String? {
            val s = raw?.trim().orEmpty()
            if (!MAC_RE.matches(s)) return null
            return s.uppercase()
        }

        /** 名稱似打印機 → 排前面（藍牙機通常叫 POS-58 / Printer / XP-…）。 */
        fun looksLikePrinter(name: String?): Boolean {
            val n = name?.lowercase() ?: return false
            return listOf("pos", "print", "printer", "xp-", "sp-", "gp-", "58", "80mm", "thermal", "receipt")
                .any { n.contains(it) }
        }
    }
}
