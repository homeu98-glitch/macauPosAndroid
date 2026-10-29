package com.macau.pos.printagent.bt

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.macau.pos.printagent.model.BtPrinterCandidate
import com.macau.pos.printagent.net.BtPrinter
import org.json.JSONArray
import org.json.JSONObject

/**
 * 藍牙打印機總控：擁有 BtPrinter + 探索（discovery）+ 配對（bond）+ WebView 回調。
 *
 * 回調（全部掛喺 `window`）：
 *  · `onBtPrinterFound(candidate)`     探索到裝置（含未配對）
 *  · `onBtDiscoveryFinished()`         探索完
 *  · `onBtDiscoveryError(msg)`         藍牙未開／冇權限
 *  · `onBtPermissionNeeded()`          Android 12+ 未授權
 *  · `onBtPairResult({ok,address,name,message})`  配對結果
 *
 * 為何要 app 內配對（2026-10-29）：POS 一體機／平板的系統設定好多時被廠商鎖住，
 * 叫店員「去設定 → 藍牙 → 配對」等於裝唔到機。`createBond()` 唔使離開 app。
 */
class BtController(
    private val context: Context,
    private val postJs: (String) -> Unit,
) {
    private val btPrinter = BtPrinter(context)
    private var discoveryReceiver: BroadcastReceiver? = null
    private var bondReceiver: BroadcastReceiver? = null
    private var pendingBondAddress: String? = null
    private val handler = Handler(Looper.getMainLooper())

    fun isSupported(): Boolean = btPrinter.isSupported()

    fun isEnabled(): Boolean = btPrinter.isEnabled()

    fun hasConnectPermission(): Boolean = btPrinter.hasConnectPermission()

    /** 探索權限（API 31+ = BLUETOOTH_SCAN；API 23–30 = ACCESS_FINE_LOCATION）。 */
    fun hasScanPermission(): Boolean = btPrinter.hasScanPermission()

    fun enumerateBonded(): List<BtPrinterCandidate> = btPrinter.enumerateBonded()

    fun bondedJson(): String {
        val arr = JSONArray()
        enumerateBonded().forEach { arr.put(it.toJson()) }
        return arr.toString()
    }

    /** 畀 `getStatus` 用：藍牙到底係「冇硬件」定「未開」定「未授權」，一眼睇得出。 */
    fun stateJson(): JSONObject = JSONObject()
        .put("supported", isSupported())
        .put("enabled", isEnabled())
        .put("connectPermission", hasConnectPermission())
        .put("scanPermission", hasScanPermission())
        .put("bondedCount", runCatching { enumerateBonded().size }.getOrDefault(0))

    fun startDiscovery() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            postJs("onBtDiscoveryError('呢部機冇藍牙')")
            return
        }
        if (!runCatching { adapter.isEnabled }.getOrDefault(false)) {
            postJs("onBtDiscoveryError('藍牙未開啟，請喺通知欄／設定開啟藍牙')")
            return
        }
        if (!hasScanPermission()) {
            postJs("onBtPermissionNeeded()")
            return
        }
        if (discoveryReceiver != null) return // 已經喺探索

        discoveryReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val dev = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                        // Android 12+ 讀 name 要 BLUETOOTH_CONNECT；冇權限會拋 SecurityException，
                        // 呢個 receiver 喺主線程 → 一拋就係 app crash。一定要包。
                        val cand = dev?.let {
                            runCatching {
                                val bonded = it.bondState == BluetoothDevice.BOND_BONDED
                                BtPrinterCandidate.fromDevice(it, bonded)
                            }.onFailure { e -> Log.w(TAG, "讀取藍牙裝置失敗", e) }.getOrNull()
                        }
                        cand?.let { postJs("onBtPrinterFound(${it.toJson().toString()})") }
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                        postJs("onBtDiscoveryFinished()")
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        // Android 13+（API 33）規定 implicit filter registerReceiver 要落
        // RECEIVER_EXPORTED / RECEIVER_NOT_EXPORTED，否則掟 SecurityException。
        // 呢個 receiver 只收探索結果，用 NOT_EXPORTED。
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(discoveryReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(discoveryReceiver, filter)
            }
        }.onFailure {
            Log.w(TAG, "註冊探索 receiver 失敗", it)
            discoveryReceiver = null
            postJs("onBtDiscoveryError('無法開始藍牙搜尋（系統限制）')")
            return
        }
        runCatching { adapter.cancelDiscovery() }
        if (!runCatching { adapter.startDiscovery() }.getOrDefault(false)) {
            postJs("onBtDiscoveryError('藍牙搜尋啟動失敗，請確認藍牙已開啟')")
        }
    }

    fun stopDiscovery() {
        discoveryReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        discoveryReceiver = null
        runCatching { BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery() }
    }

    /**
     * 配對指定 MAC。結果**一定**會經 `onBtPairResult` 返（成功／失敗／逾時）。
     *
     * 已配對 → 即刻回 ok。正在配對中 → 唔重複叫 createBond（會回 false）。
     * 30 秒仍停在 BONDING → 回逾時（唔強行 cancelBondProcess，可能係打印機要按確認）。
     */
    fun pair(address: String) {
        val mac = BtPrinter.normalizeMac(address)
        if (mac == null) {
            postPairResult(false, address, null, "藍牙位址格式不正確")
            return
        }
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            postPairResult(false, mac, null, "呢部機冇藍牙")
            return
        }
        if (!runCatching { adapter.isEnabled }.getOrDefault(false)) {
            postPairResult(false, mac, null, "藍牙未開啟，請先開啟藍牙")
            return
        }
        if (!hasConnectPermission()) {
            postPairResult(false, mac, null, "需要藍牙權限，請允許後重試")
            return
        }
        val dev = runCatching { adapter.getRemoteDevice(mac) }.getOrNull()
        if (dev == null) {
            postPairResult(false, mac, null, "搵唔到藍牙裝置 $mac")
            return
        }

        val name = runCatching { dev.name }.getOrNull()
        if (runCatching { dev.bondState }.getOrDefault(BluetoothDevice.BOND_NONE) == BluetoothDevice.BOND_BONDED) {
            postPairResult(true, mac, name, "已配對")
            return
        }
        if (pendingBondAddress != null) {
            postPairResult(false, mac, name, "另一個配對正在進行，請稍等")
            return
        }

        ensureBondReceiver()
        pendingBondAddress = mac
        // 探索進行中 createBond 好易失敗（藍牙 stack 忙於 inquiry）→ 先停探索，
        // 並主動通知 web 掃描已結束（cancelDiscovery 唔保證會派 DISCOVERY_FINISHED，
        // 唔通知就會永遠顯示「正在搜尋…」）。
        stopDiscovery()
        postJs("onBtDiscoveryFinished()")
        val started = runCatching { dev.createBond() }.getOrDefault(false)
        if (!started) {
            pendingBondAddress = null
            postPairResult(false, mac, name, "無法發起配對（請確認打印機喺配對模式）")
            return
        }
        // createBond 有機會永遠停在 BONDING（打印機冇回應）→ 一定要有上限
        handler.postDelayed({
            if (pendingBondAddress == mac) {
                pendingBondAddress = null
                val now = runCatching { dev.bondState }.getOrDefault(BluetoothDevice.BOND_NONE)
                if (now == BluetoothDevice.BOND_BONDED) {
                    postPairResult(true, mac, name, "已配對")
                } else {
                    postPairResult(false, mac, name, "配對逾時（30s）— 請確認打印機已開機並喺配對模式")
                }
            }
        }, BOND_TIMEOUT_MS)
    }

    private fun ensureBondReceiver() {
        if (bondReceiver != null) return
        bondReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
                val dev = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                val prev = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.BOND_NONE)
                val mac = runCatching { dev.address }.getOrNull() ?: return
                if (pendingBondAddress != null && !mac.equals(pendingBondAddress, ignoreCase = true)) return
                val name = runCatching { dev.name }.getOrNull()
                when (state) {
                    BluetoothDevice.BOND_BONDED -> {
                        pendingBondAddress = null
                        postPairResult(true, mac, name, "配對成功")
                    }
                    BluetoothDevice.BOND_NONE -> {
                        // 由 BONDING 變 NONE = 失敗／對方拒絕／逾時
                        if (prev == BluetoothDevice.BOND_BONDING) {
                            pendingBondAddress = null
                            postPairResult(false, mac, name, "配對失敗，請確認打印機喺配對模式再試")
                        }
                    }
                }
            }
        }
        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(bondReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(bondReceiver, filter)
            }
        }.onFailure {
            Log.w(TAG, "註冊配對 receiver 失敗", it)
            bondReceiver = null
        }
    }

    private fun postPairResult(ok: Boolean, address: String, name: String?, message: String) {
        val json = JSONObject()
            .put("ok", ok)
            .put("address", address)
            .put("name", name ?: address)
            .put("message", message)
            .toString()
        postJs("onBtPairResult($json)")
    }

    fun unregister() {
        stopDiscovery()
        bondReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        bondReceiver = null
        pendingBondAddress = null
        handler.removeCallbacksAndMessages(null)
    }

    suspend fun printBytes(address: String, data: ByteArray, timeoutMs: Int = BtPrinter.DEFAULT_TIMEOUT_MS) =
        btPrinter.printBytes(address, data, timeoutMs)

    companion object {
        private const val TAG = "BtController"
        private const val BOND_TIMEOUT_MS = 30_000L
    }
}
