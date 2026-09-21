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
        // ⚠️ 版本號一定要擰：門店靠呢個分辨「到底裝咗邊個 build」（改咗唔重裝 = 行為零變化）。
        versionCode = 10
        versionName = "1.1.5"

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
