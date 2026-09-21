# print-agent-android

澳門 POS（`macauPosSystem`）的 **Android 打印代理**。包名 `com.macau.pos.printagent`。

它只做一件事：**把 web POS 落嘅打印單，變成熱敏機食得嘅 ESC/POS 字節**。

目標硬體：**Android 平板 / 點餐機**（如美團點餐機），不是手機。

---

## ⚠️ 改動前必讀：版本號紀律

版本號在 `app/build.gradle.kts`：

```kotlin
versionCode = 10         // 每次改行為都要 +1
versionName = "1.1.5"
```

**改行為就一定要 bump，兩個都要。** 門店靠這個分辨「到底裝咗邊個 build」——
改完唔重裝 = 行為零變化（倉內註釋已明言）。派版流程：
`gradlew.bat assembleDebug` → 傳 APK → 店內重裝 → 用「測試列印」確認版本頁。

---

## 🔴 三種打印環境（2026-09-18 起）

POS 網頁側用 `detectPrintEnvironment()`（`src/lib/print-bridge/native-environment.ts`）
判定當前環境，**三者互斥**：

| 環境 | 偵測依據 | 出紙 | 裝置查詢 | setup 介面 |
|---|---|---|---|---|
| **純 website** | 冇任何 bridge 標記 | Cloud Print Relay | ❌（冇本地代理） | website |
| **desktop companion** | `window.companionShell` | `http://127.0.0.1:9311`（Electron agent，7 端點） | `:9311/api/usb`、`/api/discover`、`/api/bluetooth` | website |
| **Android native**（本倉） | `window.PosNative.printJob` | `window.PosNative.printJob()`（in-process） | `window.PosNative.listUsbPrinters()` 等 | website |

> **Setup 一律在 website 端完成。** 本 APK 只提供「一雙能碰硬體的手」——
> 同 desktop companion 嘅角色一樣，差別只在 desktop 用 HTTP loopback、
> Android 用 in-process bridge。

### Android 唯一保留的 HTTP：`NativeCompanionServer`

本 APK 起一個**只綁 loopback** 的極簡 HTTP 伺服器在 **`:9311`**（同 desktop 同埠），
**只有 3 個端點**：

| 端點 | 為何需要 |
|---|---|
| `GET /api/health` | POS wizard 嘅 `isCompanionAvailable(true)` 閘 —— 冇佢，USB 掃描精靈在 Android 上第一步就短路 return |
| `GET /api/config` | `tryAutoPairCompanion()` 自動配對（寫入 `localStorage`） |
| `POST /api/probe-lan` | wizard「測試連接」掣 |

其餘 5 個端點（`/api/discover` / `/api/usb` / `/api/bluetooth` / `/api/printers` /
`POST /api/print`）在 Android 上**一律 404** —— 因為 Android 走 `window.PosNative.*`，
唔需要 HTTP。`GET /` 亦回 404（**零 HTML 介面**）。

> ⚠️ 這**不是**舊 `printerhub`（`:8787`，已於 2026-09 完全刪除）。
> 舊 Hub 有 HTML 設定頁、區網掃描、設備綁定、以及一條**不同源**的打印旁路；
> 新 server 三者皆無，只回 3 個 JSON 探測端點。

---

## 🔴 渲染合約（最重要，唔可以繞過）

`EscPosRenderer` 是**唯一**字節出口。
三個 repo（web `renderEscPosLines` / desktop-companion `renderEscPos` / 本倉 `EscPosRenderer`）**必須同源**。

### 三條鐵律

| # | 規則 | 違反後果 |
|---|---|---|
| 1 | **紙寬格數由 POS `buildSnapshot()` 算好寫進 `template.cols`**（58mm→32 / 80mm→48）。Android 只做 fallback，**唔可以**自己再判一次 | 各 repo 唔一致，價錢甩去下一行 |
| 2 | **每一行開頭都要 `clearMagnify()`**（`GS !` + `ESC !` + `FS !` 三套歸零） | `GS !` / `FS !` 是打印機**常駐狀態**，`ESC !` 清唔走 → 純 ASCII 的分格線變雙闊 → 一行放唔落 → **一條線變兩條** |
| 3 | **放大指令語意各有不同，唔可以撈亂** | 菜品名「拉長變形」 |

放大指令對照（**最易踩的地雷**）：

| 指令 | 語意 | s | m | l |
|---|---|---|---|---|
| `ESC ! n` | bit | `0x00` | `0x20` | `0x30` |
| `GS ! n` | nibble | `0x00` | `0x01` | `0x11` |
| `FS ! n` | bit | `0x00` | `0x04` | `0x0C` |

其他既有規則：

- **分格線放大時 dash 數量要減半**（`dashLine()`），否則 48 個雙闊 dash 一樣爆行。
- `displayWidth()` 用 **codePoint**、寬字符算 2 格 —— 這是兩欄對齊（`twoColumn`）的基礎。
- **折扣副線用 `ESC { 1` 反白**：熱敏紙只有 1-bit，網頁預覽的琥珀色底物理上印唔出。
- **QR 用 `GS v 0` 點陣圖**而非 `GS ( k` 原生指令（舊機 / 平價機唔支援，會印亂碼）；
  點陣由 POS 端 `encodeQrPayload()` 預先編好，三倉共用同一矩陣 → **出紙 == 預覽**。
- 抬頭表（三端一字不差）：`receipt` = `＊＊＊ 收據 ＊＊＊` / `label` = **空**（刻意不印）/
  `kitchen` = `＊＊＊ 廚房 ＊＊＊` / `shift` = 無（由模板 `header` 自帶）。

---

## 🖨 USB 打印機型號表（`net/UsbPrinterDb.kt`）

由 VID/PID 自動認出品牌 / 型號 / 編碼 / 紙張 / 中文倍大指令 + **`family`**。

**20 個 VID entry**，跨三倉同源：

| 倉 | 檔案 |
|---|---|
| POS 網頁 | `src/lib/print-bridge/printer-models.ts` |
| desktop | `companion-server.mjs` L75-518 |
| **Android** | **`net/UsbPrinterDb.kt`** |

三層 fallback（`UsbPrinterDb.resolve()`）：

1. **命中已知 VID**
   - 命中型號 → 型號值 → 品牌預設（`generic = false`）
   - 未命中型號 → 品牌預設（`generic = true`，**唔靠品牌瞎猜紙寬**）
2. **`deviceClass == 7`**（USB Printer Class，driverless）→ 通用 ESC/POS 即插即用
   （`gb18030` / `80mm` / **`GS!`**）→ 商頌 POS-80 等機「插上就見」
3. 其他 → `null`（唔係打印機，唔枚舉滑鼠 / 鍵盤）

> 🔴 **`kanjiEnlarge` 預設值唔可以統一**：命中已知 VID 用 `"FS!"`，
> class-7 通用 fallback 用 `"GS!"`（商頌 POS-80 實機對照測試結論）。

> 🔴 **`family` 一定要帶去 POS 側**（`"receipt"` / `"label"` / `"portable"`）。
> 冇佢，POS wizard 分唔到「漢印 SL42 係標籤機」同「漢印 TP805 係票據機」
> → 標籤機被當票據機排 80mm 連續紙版面 → **出紙亂版**。

---

## 出紙路徑

| 路徑 | 入口 | 渲染 | 傳輸 |
|---|---|---|---|
| **A 主路** | `PosNative.printJob` → `MainActivity.Bridge` | `EscPosRenderer` | LAN raw 9100 → fallback 廠商 SDK；USB / BT 走 SDK |
| **B 雲中繼** | Supabase `pos_print_jobs` → Realtime → `RelayService` → `JobRunner.drain` | `EscPosRenderer` | Sunmi AIDL / `SdkPrinter.printBytes` |

（路徑 C「本機 Hub」已於 2026-09-18 隨 `printerhub` 一併刪除。）

---

## 專案結構

```
app/src/main/
  AndroidManifest.xml          權限 + <queries>（Sunmi AIDL 必須，否則 bindService 靜默失敗）
  java/com/macau/pos/printagent/
    App.kt                     Application
    MainActivity.kt            WebView 外殼 + JS Bridge（PosNative.*）
    CrashLog.kt                啟動失敗寫 crash.txt
    companion/
      NativeCompanionServer.kt 🔴 loopback :9311，只 3 端點（health / config / probe-lan）
    model/
      PrintDtos.kt             web payload（camelCase）↔ DB row（snake_case）↔ PrinterCfgDto
      UsbPrinterCandidate.kt   USB 候選 + 型號表解析結果
      BtPrinterCandidate.kt    藍牙候選
    net/
      EscPosRenderer.kt        🔴 唯一字節出口
      EscPosPrinter.kt         LAN raw socket 傳輸（printRaw / probe）
      UsbPrinterDb.kt          🔴 USB 型號表（20 VID）+ resolveUsbMeta
      UsbPrinter.kt            USB Host API：枚舉 / 授權 / bulkTransfer
      SdkPrinter.kt            廠商 AAR：LAN raw 優先 → SDK fallback；USB / BT 走 SDK
      SunmiPrinter.kt          Sunmi AIDL sendRAWData（刻意不用 high-level API）
      LanScanner.kt            區網掃描（9100 / 631 / 80）+ 本機 IP / 子網偵測
      BtPrinter.kt             藍牙 SPP 傳輸
    relay/
      RelayService.kt          雲端中繼前景服務
      RelayApi.kt              Supabase Realtime（Phoenix over WSS）+ Vercel REST
      JobRunner.kt             claim → render → dispatch → report
      RelayActivity.kt         配對 / 狀態 / 測試列印
      RelayPrefs.kt            agentId / token / storeId / defaultPaperSize / relayHome
      BootReceiver.kt          開機自動起身
    usb/UsbController.kt       USB 廣播監聽（attach / detach / permission）+ postJs
    bt/BtController.kt         藍牙掃描 + 傳輸 + 權限

docs/
  desktop-parity-port-plan.md  🔴 desktop 架構說明 + 移植方案（含三種環境分流）
  print.html / index.html / printer.html
apk-archive/                   歷史 APK（按日期命名，唔好喺根目錄放舊版）
```

---

## 建置

1. 複製 `local.properties.example` 為 `local.properties`，填入本機 Android SDK 路徑
2. 需要 JDK 17+（建議 JDK 21）
3. Gradle wrapper 9.4.1；`gradle.properties` 已設 `-Xmx2048m -Dfile.encoding=UTF-8`

```bat
gradlew.bat assembleDebug
```

輸出：`app/build/outputs/apk/debug/app-debug.apk`

### 安裝（USB 偵錯）

```bat
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 依賴

| 依賴 | 用途 |
|---|---|
| `androidx.activity` / `lifecycle-runtime-ktx` | WebView 外殼 + lifecycleScope |
| `kotlinx-coroutines-android` | 全部 IO |
| `com.google.zxing:core` | QR 產生（`RelayActivity` 配對碼） |
| `libs/printer-lib-3.5.3.aar`（`net.posprinter`） | 廠商 SDK：連線 + 傳輸（USB / BT / LAN） |
| `com.sunmi:printerlibrary` | Sunmi 內置打印機 AIDL |
| `okhttp` | Supabase Realtime WSS + Vercel REST |

**為什麼渲染不用廠商 SDK 的 high-level API？** 因為那個 AAR build（3.5.3）沒有
`setLineSpacing` / `selectCodePage`，而 `setCharSet` 的 token 映射不公開。
所以渲染一律交 `EscPosRenderer`，SDK 只負責「連線 + 送 bytes」。
這樣同時消滅三個 bug：大字體變扁、中文唔變大、USB/BT 連線脆弱。

---

## 測試注意

1. 平板與打印機必須同一 Wi-Fi / LAN
2. 廚房熱感機通常有 **9100**，可直接 raw 列印
3. Brother 辦公複合機多半只有 80 / IPP，**掃得到但 9100 會失敗**
4. Android 常讀不到區網設備 MAC（ARP 限制），此時以 IP 當鍵；**建議打印機固定 IP**
5. ⚠️ **本倉完全沒有測試**（`app/src` 之下只有 `main`），亦無 lint / CI。
   改 renderer 等於無安全網 —— 一定要在實機上印過。

---

## 環境陷阱（本機開發）

- `git-bash` 冇 coreutils：`ls` / `grep` / `sed` / `cat` / `find` / `tail` 全部 `command not found`。
  列目錄用 `node -e "fs.readdirSync(...)"`，讀檔用 Read 工具。
  要過濾指令輸出（例如 gradle）就寫個臨時 `.cjs` 用 `spawn` + `slice(-N)`。
- `npm` / `npx` 經 git-bash 跑唔到（`/usr/bin/env: 'bash': No such file or directory`）。
- **`JAVA_HOME` 未設**：gradle 會報 `ERROR: JAVA_HOME is not set`。
  本機用 Android Studio 內置 JBR：
  `C:\Program Files\Android\Android Studio\jbr`。
- git-bash 冇 `dirname`，每條命令會噴一行 `shell-runtime-bash-env.sh: line 3: dirname: not found`
  （**無害**，exit code 仍為 0）。
- `git push` 會無聲掛住（憑證 GUI 彈窗）：要加 `GCM_INTERACTIVE=never`。
