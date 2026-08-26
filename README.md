# POS Hub（Android）

店內 **一台 Android** 當 Hub：出點餐頁 + 把單打到 LAN 熱感機（廚房／水吧／前台 `:9100`）。  
iPad／Android Pad／Windows Pad 用瀏覽器開 Hub 網頁即可，不必各裝 App。

這是由原本印表機 demo 升級的第一刀（HTTP 出頁，尚未做 LAN HTTPS）。

## 店裡怎麼用

1. 這台 Android 與印表機、點餐平板同一 Wi‑Fi。建議 Hub 與印表機都固定 IP。
2. 打開 App，前景服務會聽 `http://<HubIP>:8787`。
3. 手機畫面會顯示 **點餐網址**、**8 位配對碼**、QR。
4. 平板掃 QR，或瀏覽器開 `http://<HubIP>:8787/pos?token=配對碼`。
5. 印表機設定：從點餐頁右上角「印表機設定」進入（URL 會帶 token）。掃區網，把機器綁到前台／水吧／廚房。
6. 點餐頁加菜 → 送出並打印。水吧品出到水吧機，小食品出到廚房機。

**iOS 測試逐步說明**：[`docs/ios-testing.md`](docs/ios-testing.md)

沒綁 9100 機器時仍會產生單號，只是不出紙。

## 對平板的意義

| 點餐機 | 要不要裝 App |
|--------|----------------|
| iPad / iPhone | 不用。Safari 開 Hub 頁 |
| Android Pad | 不用（除非這台自己當 Hub） |
| Windows Pad | 不用。瀏覽器開 Hub 頁，不必 Companion |

Hub 掛了，所有平板都點不了、也印不了。

## HTTP 代價（已知）

點餐頁是明文 `http://HubIP:8787`：沒有 Service Worker／相機通常不可用、同 Wi‑Fi 看得到流量。  
配對碼擋的是「隨便打 API」，不是加密。之後若要掃券或穩一點，再做 LAN HTTPS。

舊的 GitHub `docs/print.html` + `beacon.png` 仍可用，但 **不要當主路徑**（beacon **不需** token，僅相容舊 demo）。

## API

| 方法 | 路徑 | Token | 說明 |
|------|------|-------|------|
| GET | `/` 或 `/pos` | 否 | 點餐頁（建議 URL 帶 `?token=`） |
| GET | `/setup` | 頁面否；API 要 | 印表機設定（請從點餐頁帶 token 進入） |
| GET | `/api/health` | 否 | Hub 是否在聽 |
| GET | `/api/status` | 要 | 設備與綁定 |
| POST | `/api/ticket` | 要 | `{ lines:[{name,qty,dest}], remark }` → 分區列印 |
| POST | `/api/print` | 要 | 舊單筆打印 API |
| GET | `/beacon.png` | **否** | 舊 GitHub Pages demo（勿作主路徑） |

Token：`X-Hub-Token` header、`?token=`，或 JSON body `token`（不分大小寫）。  
`dest`：`front` / `bar` / `kitchen`。

Version：**1.5.1**（配對閘門、401 清 token、setup 缺碼提示、iOS 測試文件）。

## 建置

複製 `local.properties.example` 為 `local.properties`，JDK 17+：

```bat
gradlew.bat assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

詳見 [`docs/ios-testing.md`](docs/ios-testing.md)。
