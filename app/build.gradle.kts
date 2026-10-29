plugins {
    id("com.android.application")
}

android {
    namespace = "com.macau.pos.printagent"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.macau.pos.printagent"
        // docs/96：minSdk 26 → 24，等 Sunmi V2（Android 7.1 = API 25）裝到。
        // 現有 deps 最低要求：printer-lib-3.5.3.aar = 21、com.sunmi:printerlibrary = 19、
        // okhttp 4.12 = 21、androidx.activity/lifecycle = 21 → 24 全部安全。
        minSdk = 24
        targetSdk = 36
        // docs/96：雲端中繼改為「Android 自註冊配對」（手動輸入店舖 ID，取代掃 QR）
        // → 配對流程改變，必須 bump（source 改完唔等於生效：要 rebuild APK + 重新派版）
        // 2026-09-02：修 SdkPrinter.connect() 無 timeout 永久掛起（同 Print Hub v1.1.2 同一個 bug）
        // + LAN 打印機改行 raw socket 優先（5s timeout），失敗先 fallback 廠商 SDK。
        // v1.1.3：補「失敗原因要睇得到」——① 雲端中繼失敗寫低原因 + Activity 紅字顯示
        // ② 常駐通知帶埋原因 + BigTextStyle（headless 中繼專用機唯一會俾人睇到嘅嘢）
        // ③ 8787 打印頁失敗時唔好自動閂（700ms 根本睇唔到寫乜）
        // v1.1.4（code 9）：修「分格線一條變兩條」+「菜品名字體拉長變形」（docs/114）——
        // ① `GS !` 改用標準 nibble 語意（s=0x00 / m=0x01 / l=0x11），舊版誤用 ESC! 嘅 0x20/0x30
        //    令 n=0x20 變成「2 闊 3 高」→ 字拉長；② `FS !` 修正為 bit 0x04 / 0x0C；
        // ③ 每行開頭 `clearMagnify()`（GS! / ESC! / FS! 歸零），唔再靠「上一行」假設；
        // ④ 模板 `cols` 由 POS `buildSnapshot()` 統一計算（58→32、80→48），終結各 repo 各自判斷。
        // ⚠️ 版本號一定要擰：門店靠呢個分辨「到底裝咗邊個 build」（改咗唔重裝 = 行為零變化）。
        // v1.1.5（code 10）：移植 desktop Companion 能力 + 刪除 printerhub（docs/desktop-parity-port-plan.md）——
        // ① 刪 `hub/` 全套（PrinterHub / LanHttpServer / PrintHubService / PairQr）+
        //    `assets/{setup,index,remote}.html` + `EscPosPrinter.printTextTicket/buildTicket` 旁路；
        // ② 新增 `net/UsbPrinterDb.kt`（20 VID 型號表，對齊 desktop `USB_PRINTER_DB`）
        //    → `UsbPrinterCandidate` 補 charset/paperSize/kanjiEnlarge/family/max|minLabelWidthMm；
        // ③ 新增 `companion/NativeCompanionServer.kt`（loopback :9311，只 3 端點：
        //    /api/health、/api/config、/api/probe-lan）—— 補上 POS wizard 嘅
        //    `isCompanionAvailable(true)` 閘，否則 USB 掃描精靈喺 Android 上短路；
        // ④ `PrinterCfgDto` 補 family/max|minLabelWidthMm；
        // ⑤ `EscPosPrinter.printRaw` 寫完等 300ms 才收 socket（對齊 desktop printLan，修大單截斷）。
        // v1.1.6（code 11）：**白屏診斷層** —— 門店回報「裝咗 APK 開到空白、冇反應」，
        // 但同一部機瀏覽器開網頁版正常；實測下載到嘅 APK 就係 1.1.5（SHA256 一致），
        // 即係環境問題（網絡／WebView／JS），而舊版對失敗**完全靜默**，現場冇任何線索。
        // ① `PosClient` 監聽主文檔 onReceivedError / onReceivedHttpError / onReceivedSslError
        //    → 一律轉去 APK 內置嘅本地診斷頁（顯示錯誤碼、URL、WebView 版本、網絡狀態）；
        // ② 「載入完成但 DOM 空白」探針（12 秒後查元素數／可見文字）→ 專治 JS 跑唔到嘅白屏；
        // ③ 本地頁 6 秒都渲染唔到 → 原生 TextView 兜底（證明 WebView 組件本身壞）；
        // ④ 開機寫 logcat（tag PRINTAGENT_ENV / PRINTAGENT_WEB）記錄 WebView 版本、UA、
        //    網絡狀態、POS 所有 console 訊息。
        // v1.1.7（code 12）：**P1 離線開機** —— 把 web POS 嘅 production build 打包入
        // `assets/web/`（由 `tools/bundle-web.cjs` 生成，5 條 route：/ /login /pos
        // /select-workbench /retail，約 4.4 MB），線上載入失敗時自動切換：
        //   ① 仍然用同一個 origin（BuildConfig.POS_URL）載入，只喺 shouldInterceptRequest
        //      將 GET 交本地 assets → localStorage / session / 打印機設定全部保留；
        //   ② `/api/*` **唔攔截**（斷網時 fetch 即刻失敗，交返 app 自己嘅 localStorage fallback）；
        //   ③ 未打包嘅 route（會員／報表／庫存）→ 本地「呢個功能需要網絡」提示頁；
        //   ④ 離線時左下角顯示「離線模式 · 點此重試上線」標記。
        // ⚠️ APK 體積會由 ~3.4 MB 增至 ~7.5 MB（bundle 佔 4.4 MB）。
        // ⚠️ 版本號一定要擰：門店靠呢個分辨「到底裝咗邊個 build」（改咗唔重裝 = 行為零變化）。
        // 1.1.8（code 13）：修離線模式三處 ——
        //   ① 靜態資源映射：`/_next/static/x` 曾經被拼成 `next-static/static/x`（多一層），
        //      所有 JS／CSS 都開唔到 → React 唔 mount → 畫面得返「離線模式」徽章
        //      （實測 DOM 只有 44 粒、可見文字 13 字，正好就係徽章文字）；
        //   ② `/sw.js` 改為回應一個「唔快取」空殼 SW（避免換版 APK 後 SW cache 餵舊 chunk）；
        //   ③ `/favicon.ico` 等子資源唔再誤當 route 去搵 `xxx.html`；
        //   ④ 離線徽章注入加 `document.body` 守衛（`/`→`/login` 換頁中會 body=null）；
        //   ⑤ 每頁 `<head>` 注入 SW shim，令 `serviceWorker.register()` 變 no-op
        //      （WebView 嘅 SW 請求唔經 shouldInterceptRequest，離線必失敗）。
        //   離線包由 5 條 route 擴到 15 條（點餐／訂單／沽清／交班／打印／餐牌／設定）。
        // 1.1.9（code 14）：**P3 打印脫離 Sunmi** —— 補兩項裝機能力：
        //   ① `PosNative.scanLanPrinters()` / `stopLanScan()`：把一直只供 desktop
        //      用的 `LanScanner`（掃 /24、port 9100/631/80、64 並發、讀 ARP 攞 MAC）
        //      接上 Android bridge → 店員唔使再手填打印機 IP（DHCP 派、機身冇顯示 =
        //      實際裝唔到機）。進度／結果經 `__onLanScanProgress` / `__onLanScanResult` 回。
        //   ② 藍牙打印機本來喺 APK 側已完整支援（listBtPrinters / scanBtPrinters /
        //      SdkPrinter 嘅 BLUETOOTH 分支），但 web 精靈冇藍牙入口 → 今次配合
        //      `printer-wizard-modal.tsx` 新增藍牙連接方式（見 web 倉）。
        //   ③ 傳輸層審計結論：`SdkPrinter` 一向 LAN 走 raw socket 9100、USB／藍牙走
        //      廠商 AAR，`JobRunner` 只按 `cfg.connectionType` 分流 —— **本來就唔依賴
        //      Sunmi**，只有 `connectionType=="sunmi"` 才會叫 `SunmiPrinter`，
        //      而 web 客戶端從來唔會送呢個值（型別只有 lan/usb/bluetooth）。
        // 1.2.0（code 15）：**藍牙真正可用** —— 1.1.9 有藍牙入口，但傳輸仍然只行廠商
        //   AAR（無 timeout 保證、錯誤只有數字碼），而且未配對就完全冇路可走。
        //   ① `BtPrinter` 改為**自家標準 SPP（RFCOMM）直連優先**：硬 timeout 15s、
        //      1 KB 分塊 + 12ms 間隔（藍牙機 buffer 細，4 KB 連環寫會掉包）、
        //      close 前 600ms drain（同 LAN 300ms 同一個道理：唔等就截尾）。
        //      ⚠️ `BluetoothSocket.connect()` 唔響應 coroutine cancellation →
        //      timeout 時一定要由另一 thread close() 條 socket 才中斷得開。
        //   ② AAR 降為 fallback，而且「未配對／藍牙關／冇權限／MAC 錯」**唔再 fallback**
        //      （呢幾種 AAR 必然救唔到，再等 6 秒只會令店員覺得掣失靈）。
        //   ③ 新增 `PosNative.pairBtPrinter(address)`：app 內 `createBond()` + 監聽
        //      BOND_STATE_CHANGED，結果經 `onBtPairResult` 回。POS 一體機嘅系統設定
        //      好多時被廠商鎖住，逼店員跳出去配對 = 裝唔到機。
        //   ④ `listBtPrinters` 無 BLUETOOTH_CONNECT 時回 `needsPermission:true`（舊版
        //      只回空白清單，店員唔知要授權）；啟動時亦唔再預先彈藍牙權限，改為按需。
        //   ⑤ `PrinterCfgDto` 只接受真 MAC 格式，唔再將裝置名（"POS-58"）當 address
        //      塞落 `getRemoteDevice()`（會拋 IllegalArgumentException，訊息睇唔明）。
        versionCode = 15
        versionName = "1.2.0"

        buildConfigField("String", "POS_URL", "\"https://macau-pos-system.vercel.app\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.google.zxing:core:3.5.3")
    // 廠商 ESC/POS SDK（docs/75 路(b)）：渲染 + 連接統一經 net.posprinter，
    // 順便消滅大字體變扁（行距）同中文唔變大（Kanji）兩個 raw 字節 bug。
    implementation(files("libs/printer-lib-3.5.3.aar"))

    // docs/96 §8：Sunmi 內置打印機（AIDL）。純 Java 包裝、minSdk 19、無 JNI →
    // 同 arm-v7a 嘅 Sunmi V2 無衝突。targetSdk ≥ 30 必須喺 Manifest 加 <queries>
    // 宣告 woyou.aidlservice.jiuiv5.IWoyouService，否則 bindService 靜默失敗。
    implementation("com.sunmi:printerlibrary:1.0.24")

    // docs/96 §7：雲端中繼 —— Supabase Realtime（Phoenix over WSS）+ Vercel REST。
    // OkHttp 4.12 最低 API 21，同 minSdk 24 相容。
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
