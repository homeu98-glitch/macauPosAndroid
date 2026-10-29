#!/usr/bin/env node
/**
 * 喺 APK 內嘅 WebView 執行一段 JS，並印出結果（P3／P4 驗證用）。
 *
 * 為何要呢個工具
 * ----------------
 * 門店真正要證明嘅係「**未登入／斷網**都印得出紙」。但登入要真帳號，
 * 令「出紙」呢件事永遠驗唔到。經 Chrome DevTools Protocol 可以直接喺頁面
 * 執行 `window.PosNative.testPrint(...)`，繞過 UI 但**仍然行真實嘅
 * PosNative → SdkPrinter → EscPrinter socket 路徑** —— 即係驗到嘅係真嘢。
 *
 * 前置（debug build 已開 WebView 遠端除錯，見 MainActivity `BuildConfig.DEBUG`）
 *   PID=$(adb shell pidof com.macau.pos.printagent)
 *   adb forward tcp:9222 localabstract:webview_devtools_remote_$PID
 *
 * 用法
 *   node tools/cdp-eval.cjs "1+1"
 *   node tools/cdp-eval.cjs "window.PosNative.testPrint(JSON.stringify({printer:{...}}))"
 *   node tools/cdp-eval.cjs --file build/tmp/eval.js
 *
 * 選項
 *   --port <n>     DevTools 端口（預設 9222）
 *   --timeout <ms> 整體逾時（預設 30000）
 *   --file <path>  由檔案讀 expression（避免 shell 引號地獄）
 *   --list         只列出 targets
 */

const fs = require("fs");

function arg(name, fallback) {
  const i = process.argv.indexOf(name);
  return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : fallback;
}

const PORT = Number(arg("--port", 9222));
const TIMEOUT = Number(arg("--timeout", 30_000));
const FILE = arg("--file", null);
const LIST = process.argv.includes("--list");

const expression = FILE
  ? fs.readFileSync(FILE, "utf8")
  : process.argv.slice(2).filter((a) => !a.startsWith("--") && !/^\d+$/.test(a)).join(" ");

async function main() {
  const res = await fetch(`http://127.0.0.1:${PORT}/json`, {
    signal: AbortSignal.timeout(5000),
  });
  const targets = await res.json();
  if (LIST) {
    targets.forEach((t) => console.log(`${t.type}\t${t.title}\t${t.url}`));
    return;
  }
  const page = targets.find((t) => t.type === "page" && t.webSocketDebuggerUrl);
  if (!page) throw new Error("搵唔到可連線嘅 page target（WebView 可能未載入完）");
  console.log(`target: ${page.url}`);

  const ws = new WebSocket(page.webSocketDebuggerUrl);
  const result = await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error("CDP 逾時")), TIMEOUT);
    ws.addEventListener("open", () => {
      ws.send(
        JSON.stringify({
          id: 1,
          method: "Runtime.evaluate",
          params: { expression, awaitPromise: true, returnByValue: true, userGesture: true },
        }),
      );
    });
    ws.addEventListener("message", (ev) => {
      const msg = JSON.parse(String(ev.data));
      if (msg.id !== 1) return;
      clearTimeout(timer);
      resolve(msg);
    });
    ws.addEventListener("error", (e) => {
      clearTimeout(timer);
      reject(new Error(`WebSocket 錯誤：${e.message || e.type}`));
    });
  });

  ws.close();

  const r = result.result || {};
  if (r.exceptionDetails) {
    console.error("JS 拋錯：", r.exceptionDetails.exception?.description || r.exceptionDetails.text);
    process.exitCode = 1;
    return;
  }
  const value = r.result?.value;
  console.log(typeof value === "string" ? value : JSON.stringify(value, null, 2));
}

main().catch((e) => {
  console.error("失敗：", e.message);
  process.exitCode = 1;
});
