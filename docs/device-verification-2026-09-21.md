# 驗證報告 — macau-pos v1.1.5 (code 10)

- 時間：2026-09-21T13:05:11.178Z
- APK：`C:/Users/surface/Desktop/macau-pos.apk`
- 目標：Pixel Tablet / Android 15 (API 35) / x86_64  ← 模擬器（非實機）
- 注意：模擬器可驗 :9311 與 bridge 與出紙字節；**真 USB 打印機枚舉、實體紙張仍須實機**。

| # | 項目 | 結果 | 詳情 |
|---|---|---|---|
| 1 | 偵測到可用裝置 | ✅ 通過 | emulator-5554          device product:sdk_gtablet_x86_64 model:Pixel_Tablet device:emu64xa transport_id:1 |
| 2 | 機型資訊 | ✅ 通過 | Pixel Tablet / Android 15 (API 35) / x86_64  ← 模擬器（非實機） |
| 3 | ⚠️ 硬體層限制 | ⚠️ 待確認 | 模擬器無法枚舉真 USB 打印機、無法出紙。這些項目仍需實機。 |
| 4 | adb install -r | ✅ 通過 | Performing Streamed Install \| Success |
| 5 | 已安裝 versionName=1.1.5 / versionCode=10 | ✅ 通過 | 實測 versionName=1.1.5 versionCode=10 |
| 6 | 預先授予執行期權限（防止對話框擋住 WebView） | ✅ 通過 | 已處理: POST_NOTIFICATIONS, BLUETOOTH_SCAN, BLUETOOTH_CONNECT, BLUETOOTH_ADVERTISE, ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION |
| 7 | App 啟動（程序存活） | ✅ 通過 | am start Status=ok；pid=7057 |
| 8 | NativeCompanionServer 未報起步失敗 | ✅ 通過 | (無記錄) |
| 9 | 無 FATAL EXCEPTION | ✅ 通過 | (無) |
| 10 | GET /api/health → 200 | ✅ 通過 | 200 {"ok":true,"version":"1.1.5","runtime":"android"} |
| 11 | health.runtime == "android" | ✅ 通過 | {"ok":true,"version":"1.1.5","runtime":"android"} |
| 12 | health.version == "1.1.5" | ✅ 通過 | version=1.1.5 |
| 13 | GET /api/config → 200 | ✅ 通過 | 200 {"companionUrl":"http:\/\/127.0.0.1:9311","posUrl":"https:\/\/macau-pos-system.vercel.app","tokenEnabled":false} |
| 14 | config.companionUrl == http://127.0.0.1:9311 | ✅ 通過 | {"companionUrl":"http://127.0.0.1:9311","posUrl":"https://macau-pos-system.vercel.app","tokenEnabled":false} |
| 15 | POST /api/probe-lan（壞 IP）→ 明確報錯 | ✅ 通過 | 200 {"ok":false,"error":"IP 格式不正確：not-an-ip"} |
| 16 | POST /api/probe-lan {127.0.0.1,9311} → reachable=true（自探） | ✅ 通過 | 200 {"ok":true,"ip":"127.0.0.1","port":9311,"reachable":true} |
| 17 | GET / → 404（零 HTML，證明非舊 printerhub） | ✅ 通過 | 404 {"ok":false,"error":"not found","note":"Android native agent 只提供 \/api\/health、\/api\/config、\/api\/probe-lan"} |
| 18 | POST /api/print → 404（無打印旁路） | ✅ 通過 | 404 {"ok":false,"error":"not found","note":"Android native agent 只提供 \/api\/health、\/api\/config、\/api\/probe-lan"} |
| 19 | WebView devtools socket 存在（debug build 可遠端除錯） | ✅ 通過 | webview_devtools_remote_7057 |
| 20 | 找到 WebView 頁面 target | ✅ 通過 | https://macau-pos-system.vercel.app/login |
| 21 | WebView 載入的是 Vercel POS | ✅ 通過 | https://macau-pos-system.vercel.app/login |
| 22 | window.PosNative 已注入 | ✅ 通過 | typeof = object |
| 23 | window.PosNative.printJob 是 function（＝網站判 android 環境的依據） | ✅ 通過 | typeof = function |
| 24 | getStatus() 可呼叫 | ✅ 通過 | {"ok":true,"available":true,"service":"macau-pos-print-agent","env":"android","version":"1.1.5","localIp":"10.0.2.16","companionPort":9311,"companionListening":true} |
| 25 | getStatus().env == "android" | ✅ 通過 | env=android |
| 26 | getStatus().companionPort == 9311 | ✅ 通過 | companionPort=9311 |
| 27 | getStatus().companionListening == true（① 的核心斷言） | ✅ 通過 | companionListening=true |
| 28 | getStatus().version == "1.1.5" | ✅ 通過 | version=1.1.5 |
| 29 | listUsbPrinters() 回可解析 JSON（② 的實際鏈路） | ✅ 通過 | {"ok":true,"printers":[]} |
| 30 | USB 枚舉打印機數量 | ⚠️ 待確認 | 0 台 —— 沒插打印機時係預期結果（鏈路本身已通：回 ok + 可解析 JSON）。要驗列舉請插上實機打印機。 |
| 31 | 本機 9100 監聽（扮熱感機） | ✅ 通過 | net server listening on 127.0.0.1:9100 |
| 32 | adb reverse tcp:9100（裝置 127.0.0.1:9100 → 本機） | ✅ 通過 | 已建立 |
| 33 | testPrint(lan 127.0.0.1:9100, receipt/80mm) 被受理 | ✅ 通過 | {"ok":true,"queued":true,"jobId":"verify-lan-1","ip":"lan","port":9100} |
| 34 | 本機收到 ESC/POS 字節（出紙鏈路真的通） | ✅ 通過 | 378 bytes；開頭 24 bytes: 1b 40 1b 61 01 1d 21 00 1b 21 00 1c 21 00 1b 21 00 1b 45 00 1b 33 1e 1c |
| 35 | 含 ESC @ 初始化（1b 40） | ✅ 通過 | 出現 1 次 |
| 36 | 含 GS v 0 點陣圖（QR 走點陣而非 GS ( k） | ⚠️ 待確認 | 無 QR（測試單可能不含 QR，非錯誤） |
| 37 | 每行有 clearMagnify 歸零痕跡（GS!/ESC!/FS! 三套） | ✅ 通過 | 歸零指令出現 39 次（v1.1.4 修「一條線變兩條」的關鍵） |
| 38 | 解碼後的單據文字預覽（gb18030） | ⚠️ 待確認 | @ a ! ! ! ! E 3 & ! 驗證門店 . /  ! ! ! ! E 3 & ! 打印測試頁 . /  a ! ! ! -------------------------------- /  ! ! ! ! E 3 & ! 打印機: 驗證用 LAN 機 . /  ! ! ! ! E 3 & ! 連接: lan . /  ! ! ! ! E 3 IP: 127.0.0.1:9100 /  ! ! ! ! E 3 Charset: GB18030 /  ! ! ! -------------------------------- /  !  |
| 39 | 原始字節已存檔供人手核對 | ✅ 通過 | C:\dev\macauPosAndroid\docs\device-print-capture-2026-09-21.bin |
| 40 | 標籤機（family=label, 100x75mm）有送出出紙字節 | ✅ 通過 | 378 bytes；testPrint 回應 {"ok":true,"queued":true,"jobId":"verify-lan-1","ip":"lan","port":9100} |
| 41 | 標籤機走 TSPL 指令集（SL42 標籤機所需） | ❌ 失敗 | 收到的仍是 ESC/POS（開頭: 1b 40 1b 61 01 1d 21 00 1b 21 00 1c）、與 receipt/portable 完全相同 → EscPosRenderer 未消費 family（0 處引用 family / TSPL / maxLabelWidthMm）。 / **結論：SL42（family=label, 100×75mm）在 v1.1.5 仍會被排成 80mm 連續紙版式 → 出紙亂版風險未解，TSPL 渲染器屬 Phase 2 未實作。** |
| 42 | 實體紙張 | ⚠️ 待確認 | 腳本已把「渲染 → LAN raw socket → 真實 ESC/POS 字節」全部驗到（見上）。 / 剩下只有「紙有冇出、撕紙位對唔對、標籤版式」屬物理觀察，需實機打印機。 |

合計：✅ 36 ／ ❌ 1 ／ ⚠️ 5