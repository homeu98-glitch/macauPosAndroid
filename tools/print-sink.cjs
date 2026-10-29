#!/usr/bin/env node
/**
 * 打印機接收端模擬器（P3 驗證用）—— 喺電腦起一個 raw TCP :9100，
 * 接收 APK 送出嘅 ESC/POS 位元組，寫落檔案俾人核對。
 *
 * 為何要呢個工具
 * ----------------
 * 「打印脫離 Sunmi」呢個結論唔可以靠讀程式碼推出來 —— 要證明「真係有位元組
 * 去到打印機 socket」。實機打印機會出紙但睇唔到 bytes；用呢個 sink 就可以
 * 100% 確認：① 連得上 ② 收到幾多位元組 ③ 開頭係唔係 ESC/POS 指令。
 *
 * 用法（電腦）
 *   node tools/print-sink.cjs 9100
 *
 * 用法（Android 模擬器）
 *   模擬器入面嘅 host loopback = 10.0.2.2
 *   所以打印機設定填 ip=10.0.2.2, port=9100
 *
 * 輸出
 *   build/tmp/print-sink/<timestamp>.bin   —— 原始 bytes
 *   build/tmp/print-sink/<timestamp>.txt   —— 可讀摘要（前 256 bytes hex + ASCII）
 */

const net = require("net");
const fs = require("fs");
const path = require("path");

const PORT = Number(process.argv[2] || 9100);
const OUT = path.join(process.cwd(), "build", "tmp", "print-sink");
fs.mkdirSync(OUT, { recursive: true });

let seq = 0;

function stamp() {
  return new Date().toISOString().replace(/[:.]/g, "-");
}

function summarize(buf) {
  const HEAD = 256;
  const hex = [];
  const ascii = [];
  for (let i = 0; i < Math.min(buf.length, HEAD); i++) {
    const b = buf[i];
    hex.push(b.toString(16).toUpperCase().padStart(2, "0"));
    ascii.push(b >= 0x20 && b <= 0x7e ? String.fromCharCode(b) : ".");
  }
  return [
    `bytes=${buf.length}`,
    "",
    "hex（前 256）：",
    hex.join(" "),
    "",
    "ascii（前 256）：",
    ascii.join(""),
  ].join("\n");
}

const server = net.createServer((sock) => {
  const id = ++seq;
  const tag = `${stamp()}-${String(id).padStart(2, "0")}`;
  const chunks = [];
  const peer = `${sock.remoteAddress}:${sock.remotePort}`;
  console.log(`[#${id}] 連線來自 ${peer}`);

  sock.on("data", (c) => chunks.push(c));
  sock.on("error", (e) => console.log(`[#${id}] socket 錯誤：${e.message}`));
  sock.on("close", () => {
    const buf = Buffer.concat(chunks);
    const binPath = path.join(OUT, `${tag}.bin`);
    const txtPath = path.join(OUT, `${tag}.txt`);
    fs.writeFileSync(binPath, buf);
    fs.writeFileSync(txtPath, summarize(buf));
    console.log(`[#${id}] 收工：${buf.length} bytes → ${binPath}`);
    // 頭幾個 byte 係咩，一眼睇得出係唔係 ESC/POS
    const head = buf.subarray(0, 8);
    console.log(`[#${id}] 開頭：${[...head].map((b) => "0x" + b.toString(16).toUpperCase()).join(" ")}`);
  });
});

server.listen(PORT, "0.0.0.0", () => {
  console.log(`打印機 sink 已啟動：0.0.0.0:${PORT}`);
  console.log(`Android 模擬器請用 10.0.2.2:${PORT}`);
  console.log(`輸出目錄：${OUT}`);
});

process.on("SIGINT", () => {
  console.log("\n收工。");
  server.close(() => process.exit(0));
});
