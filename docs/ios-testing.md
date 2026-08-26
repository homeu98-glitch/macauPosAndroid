# iOS（iPad／iPhone）點餐測試指引

v1.5.1+ 的 POS Hub 讓 **iOS 免裝 App**，用 Safari 開 Hub 上的 HTTP 點餐頁即可。本文件供真機驗收用。

## 前置條件

| 項目 | 要求 |
|------|------|
| Hub | 一台 Android 跑 POS Hub App，前景服務已啟動 |
| 網路 | Hub、iOS 裝置、印表機（若有）**同一 Wi‑Fi** |
| iOS | Safari（建議 iOS 15+）；勿用 Chrome 內建瀏覽器做首次驗收 |
| 印表機 | 可選；LAN 熱感機 `:9100`，在 `/setup` 綁定 |

## 快速流程

1. **Android Hub** 開 App，記下畫面上的：
   - 本機 IP（例 `192.168.31.88`）
   - 8 位配對碼（例 `AB3K7M2P`）
   - 或掃 **點餐 QR**（URL 已含 token）

2. **iPad／iPhone Safari** 開其中一種：
   - 掃 Hub 畫面上的 QR
   - 手動輸入：`http://<HubIP>:8787/pos?token=<配對碼>`

3. 若只開 `http://<HubIP>:8787/pos`（無 token），會出現 **配對碼閘門**；輸入 Hub 畫面上的 8 位碼。

4. **（有印表機時）** 點餐頁右上角「印表機設定」→ 掃區網 → 綁前台／水吧／廚房 →「完成，回到出單」。

5. 加菜 → **送出並打印** → 確認 Hub App 日誌／印表機出紙。

## iOS 特別注意

### 區網權限

iOS 14+ 首次連區網 IP 時，Safari 可能跳出 **「允許尋找並連接到區域網路上的裝置」** → 必須 **允許**，否則無法載入 Hub 頁。

### HTTP 明文

點餐頁是 `http://`（非 HTTPS）：

- 無 secure context：Service Worker、相機 API **不可用**（本 demo 不需要）
- 同 Wi‑Fi 其他裝置理論上可嗅探流量；配對碼只防誤打 API，**不是加密**

### 配對碼儲存

成功進入後 token 存在 **Safari localStorage**（同 origin）。清除網站資料後需重輸或重掃 QR。

401（配對碼錯／Hub 重裝）時點餐頁會自動回到閘門。

### 勿直接開 `/setup`

`/setup` 若無 token 且 localStorage 也無配對碼，會顯示「缺少配對碼」。請從點餐頁連結進入。

## 驗收檢查表

- [ ] Safari 可開點餐頁（非「無法連線」）
- [ ] QR 掃描後免手打 token
- [ ] 加／減數量、自訂品名、備註正常
- [ ] 送出後顯示單號與 `printed/total`
- [ ] 水吧品 → 水吧機；廚房品 → 廚房機（已綁定時）
- [ ] 未綁印表機時仍可送單（只記單不出紙）
- [ ] 印表機設定頁可掃描、綁定、返回點餐頁
- [ ] Hub 斷線時提示「連不到 Hub（確認同一 Wi‑Fi）」

## 常見問題

| 現象 | 處理 |
|------|------|
| Safari 一直轉圈 | 確認 iOS 與 Hub 同 Wi‑Fi；關 VPN；檢查 Hub 前景服務 |
| 區網權限被拒 | 設定 → Safari → 進階 → 網站資料 → 刪除 Hub IP 後重開 |
| 401 / 配對碼無效 | Hub 重裝會換 token；重掃 QR 或看 Hub 畫面新碼 |
| 有單號但沒出紙 | `/setup` 確認該 dest 已綁 `:9100` 且 `canRawPrint` |
| 混合內容錯誤 | 勿用 `https://` 開 Hub；本版僅 HTTP `:8787` |

## 與 Macau-Ledger 的關係

此 Hub **尚未**同步 Ledger 菜單或線上訂單；為 offline POS brainstorm 的第一刀（HTTP 出頁 + LAN 打印）。後續見 `Macau-Ledger/output/brainstorm/offlinePos/SUMMARY.md`。

## 建置與側載 Hub APK

```bat
cd pos-printer-android
gradlew.bat assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Package：`com.posdemo.printer`（與 macau-ledger-merchant **不同**，可並存）。
