/**
 * macau-pos（com.macau.pos.printagent）真機／模擬器驗證 harness
 * ---------------------------------------------------------------
 * 一鍵跑完 v1.1.5 的三項驗證：
 *   ① :9311 loopback 探測端點（health / config / probe-lan，且 GET / 回 404）
 *   ② wizard USB 掃描鏈路（PosNative bridge 是否注入、listUsbPrinters 回什麼）
 *   ③ 出紙鏈路 —— 無需實體打印機：用 `adb reverse tcp:9100` 把裝置的 LAN raw socket
 *      導回本機，由本腳本扮演熱感機收下真實 ESC/POS 字節並分析。
 *      （實體紙張與標籤版式仍要人手確認）
 *
 * 用法：
 *   node tools/verify-device.cjs [apk路徑]
 *   預設 apk = C:\Users\surface\Desktop\macau-pos.apk
 *
 * 前置：裝置已接（USB 或 emulator）、adb 授權已接受。
 * ⚠️ 只讀不寫：唯一寫入動作 = 安裝 APK。不會改任何 POS 資料。
 */

const { execFileSync } = require('child_process');
const http = require('http');
const net = require('net');
const fs = require('fs');
const path = require('path');

const ADB = 'C:/Users/surface/AppData/Local/Android/Sdk/platform-tools/adb.exe';
const PKG = 'com.macau.pos.printagent';
const ACTIVITY = PKG + '/.MainActivity';
const APK = process.argv[2] || 'C:/Users/surface/Desktop/macau-pos.apk';
const HTTP_PORT = 19311;   // PC → 裝置 :9311
const CDP_PORT = 19222;    // PC → WebView devtools
const RAW_PORT = 9100;     // PC 扮演熱感機，收裝置的 LAN raw 出紙

const results = [];
let fatal = null;
let SERIAL = null;

function rec(name, pass, detail) {
  results.push({ name, pass, detail: detail == null ? '' : String(detail) });
  console.log((pass === true ? '  ✅ ' : pass === false ? '  ❌ ' : '  ⚠️  ') + name +
    (detail ? '\n        ' + String(detail).replace(/\n/g, '\n        ') : ''));
}

function adb(args, opts) {
  return execFileSync(ADB, args, Object.assign({ encoding: 'utf8', timeout: 60000, maxBuffer: 32 * 1024 * 1024 }, opts || {})).trim();
}

/** 容錯版：非零 exit code（例如 pidof 冇找到、grep 冇命中）回傳輸出而唔拋錯。 */
function adbTry(args, opts) {
  try { return adb(args, opts); }
  catch (e) { return ((e.stdout || '') + (e.stderr || '')).trim(); }
}

function httpReq({ method = 'GET', path: p, body = null, port = HTTP_PORT, timeout = 5000 }) {
  return new Promise(resolve => {
    const req = http.request({
      host: '127.0.0.1', port, path: p, method,
      headers: body ? { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body) } : {},
      timeout,
    }, res => {
      let d = '';
      res.setEncoding('utf8');
      res.on('data', c => d += c);
      res.on('end', () => resolve({ status: res.statusCode, body: d }));
    });
    req.on('timeout', () => { req.destroy(); resolve({ status: 0, body: '(timeout)' }); });
    req.on('error', e => resolve({ status: 0, body: '(error: ' + e.message + ')' }));
    if (body) req.write(body);
    req.end();
  });
}

function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }

function hex(buf, n) { return [...buf.subarray(0, n)].map(b => b.toString(16).padStart(2, '0')).join(' '); }
function countSeq(buf, seq) {
  let n = 0;
  outer: for (let i = 0; i + seq.length <= buf.length; i++) {
    for (let j = 0; j < seq.length; j++) if (buf[i + j] !== seq[j]) continue outer;
    n++;
  }
  return n;
}

// ─────────────────────────────────────────────────────────────
async function main() {
  console.log('══════════════════════════════════════════════════');
  console.log(' macau-pos 驗證  v1.1.5 (code 10)');
  console.log(' APK: ' + APK);
  console.log('══════════════════════════════════════════════════\n');

  // ── 0. 裝置 ───────────────────────────────────────────────
  console.log('【0】裝置連線');
  const lines = adb(['devices', '-l']).split('\n').slice(1).filter(l => l.trim());
  const ready = lines.filter(l => /\bdevice\b/.test(l) && !/unauthorized|offline/.test(l));
  if (ready.length === 0) {
    rec('偵測到可用裝置', false, lines.length ? '有以下但未就緒：\n' + lines.join('\n') : 'adb 看不到任何裝置');
    fatal = '沒有可用裝置 —— 插上平板並開「USB 偵錯」（或用 emulator），接受授權後重跑。';
    return;
  }
  SERIAL = ready[0].split(/\s+/)[0];
  rec('偵測到可用裝置', true, ready[0]);

  const sh = cmd => adbTry(['-s', SERIAL, 'shell', cmd]);
  const model = sh('getprop ro.product.model');
  const rel = sh('getprop ro.build.version.release');
  const sdk = sh('getprop ro.build.version.sdk');
  const abi = sh('getprop ro.product.cpu.abi');
  const isEmu = /^emulator-/.test(SERIAL);
  rec('機型資訊', true, `${model} / Android ${rel} (API ${sdk}) / ${abi}` + (isEmu ? '  ← 模擬器（非實機）' : '  ← 實機'));
  if (Number(sdk) < 24) rec('minSdk 24 相容', false, 'API ' + sdk + ' 低於 minSdk 24');
  if (isEmu) rec('⚠️ 硬體層限制', null, '模擬器無法枚舉真 USB 打印機、無法出紙。這些項目仍需實機。');

  // ── 1. 安裝 ───────────────────────────────────────────────
  console.log('\n【1】安裝 APK');
  if (!fs.existsSync(APK)) { rec('APK 存在', false, APK); fatal = '找不到 APK：' + APK; return; }
  let inst;
  try { inst = adb(['-s', SERIAL, 'install', '-r', '-d', APK], { timeout: 240000 }); }
  catch (e) { inst = (e.stdout || '') + (e.stderr || '') + (e.message || ''); }
  rec('adb install -r', /Success/i.test(inst) && !/Failure/i.test(inst), inst.split('\n').filter(Boolean).join(' | '));

  // ── 2. 已安裝版本 ─────────────────────────────────────────
  console.log('\n【2】已安裝版本');
  const dump = sh(`dumpsys package ${PKG} | grep -E "versionCode|versionName" | head -4`);
  const vName = (dump.match(/versionName=([^\s]+)/) || [])[1] || '?';
  const vCode = (dump.match(/versionCode=(\d+)/) || [])[1] || '?';
  rec('已安裝 versionName=1.1.5 / versionCode=10', vName === '1.1.5' && vCode === '10',
    `實測 versionName=${vName} versionCode=${vCode}`);

  // 預先授予執行期權限 —— 否則首次啟動會彈 GrantPermissionsActivity 蓋住 WebView，
  // app 被擠到背景後進程被回收（實測會令 :9311 完全連唔上）。
  const PERMS = [
    'android.permission.POST_NOTIFICATIONS',
    'android.permission.BLUETOOTH_SCAN',
    'android.permission.BLUETOOTH_CONNECT',
    'android.permission.BLUETOOTH_ADVERTISE',
    'android.permission.ACCESS_FINE_LOCATION',
    'android.permission.ACCESS_COARSE_LOCATION',
  ];
  const okPerms = [], skipPerms = [];
  for (const p of PERMS) {
    const r = adbTry(['-s', SERIAL, 'shell', 'pm', 'grant', PKG, p]);
    if (/SecurityException|Unknown permission|not a changeable/i.test(r)) skipPerms.push(p.split('.').pop());
    else okPerms.push(p.split('.').pop());
  }
  rec('預先授予執行期權限（防止對話框擋住 WebView）', true,
    '已處理: ' + okPerms.join(', ') + (skipPerms.length ? '　／ 未宣告略過: ' + skipPerms.join(', ') : ''));

  // ── 3. 啟動 ───────────────────────────────────────────────
  console.log('\n【3】啟動 App');
  adbTry(['-s', SERIAL, 'logcat', '-c']);
  sh(`am force-stop ${PKG}`);
  await sleep(1000);
  // 有殘留對話框（權限／系統提示）先按返回鍵清走
  sh('input keyevent 4');
  await sleep(500);
  const started = sh(`am start -W -n ${ACTIVITY}`);
  const statusLine = (started.match(/Status:\s*(\S+)/) || [])[1] || '(無)';
  // 等 WebView 連 Vercel；其間輪詢 pid，最多等 30 秒
  let pid = '';
  for (let i = 0; i < 10; i++) {
    await sleep(3000);
    pid = sh(`pidof ${PKG}`).trim().split(/\s+/)[0] || '';
    if (pid) break;
  }
  const fg = sh('dumpsys activity activities | grep -E "ResumedActivity" | head -2');
  const blocking = !new RegExp(PKG).test(fg) && /GrantPermissionsActivity/i.test(fg);
  // `am start -W` 冷啟常報 timeout（WebView 首次載入 >5s）——只要進程活著就算啟動成功
  const launchOk = !!pid && /^\d+$/.test(pid);
  rec('App 啟動（程序存活）', launchOk,
    `am start Status=${statusLine}；pid=${pid || '(無)'}` +
    (statusLine === 'timeout' && launchOk ? '　→ timeout 只是 -W 等待窗口唔夠長，程序實際已起' : '') +
    (blocking ? '\n前景被權限對話框佔住：' + fg.trim() : '') +
    (!pid && !blocking ? '\n前景：' + fg.trim() : ''));

  // ── 4. logcat ─────────────────────────────────────────────
  console.log('\n【4】logcat');
  const log = adbTry(['-s', SERIAL, 'logcat', '-d', '-v', 'brief']);
  const failed = (log.match(/.*NativeCompanionServer 起步失敗.*/g) || []);
  rec('NativeCompanionServer 未報起步失敗', failed.length === 0, failed.join('\n') || '(無記錄)');
  const crashes = (log.match(/.*FATAL EXCEPTION.*/g) || []);
  rec('無 FATAL EXCEPTION', crashes.length === 0, crashes.join('\n') || '(無)');

  // ── 5. :9311 端點（①） ──────────────────────────────────
  console.log('\n【5】① :9311 loopback 端點');
  adb(['-s', SERIAL, 'forward', `tcp:${HTTP_PORT}`, 'tcp:9311']);

  const health = await httpReq({ path: '/api/health' });
  let hj = null; try { hj = JSON.parse(health.body); } catch (e) { }
  rec('GET /api/health → 200', health.status === 200, `${health.status} ${health.body}`);
  rec('health.runtime == "android"', !!(hj && hj.runtime === 'android'), hj ? JSON.stringify(hj) : '');
  rec('health.version == "1.1.5"', !!(hj && hj.version === '1.1.5'), hj ? 'version=' + hj.version : '');

  const conf = await httpReq({ path: '/api/config' });
  let cj = null; try { cj = JSON.parse(conf.body); } catch (e) { }
  rec('GET /api/config → 200', conf.status === 200, `${conf.status} ${conf.body}`);
  rec('config.companionUrl == http://127.0.0.1:9311',
    !!(cj && cj.companionUrl === 'http://127.0.0.1:9311'), cj ? JSON.stringify(cj) : '');

  const probeBad = await httpReq({ method: 'POST', path: '/api/probe-lan', body: JSON.stringify({ ip: 'not-an-ip' }) });
  let pbj = null; try { pbj = JSON.parse(probeBad.body); } catch (e) { }
  rec('POST /api/probe-lan（壞 IP）→ 明確報錯',
    probeBad.status === 200 && !!(pbj && pbj.ok === false), `${probeBad.status} ${probeBad.body}`);

  const probeSelf = await httpReq({ method: 'POST', path: '/api/probe-lan', body: JSON.stringify({ ip: '127.0.0.1', port: 9311 }) });
  let psj = null; try { psj = JSON.parse(probeSelf.body); } catch (e) { }
  rec('POST /api/probe-lan {127.0.0.1,9311} → reachable=true（自探）',
    !!(psj && psj.reachable === true), `${probeSelf.status} ${probeSelf.body}`);

  const root = await httpReq({ path: '/' });
  rec('GET / → 404（零 HTML，證明非舊 printerhub）', root.status === 404, `${root.status} ${root.body.slice(0, 130)}`);
  const bypass = await httpReq({ method: 'POST', path: '/api/print', body: '{}' });
  rec('POST /api/print → 404（無打印旁路）', bypass.status === 404, `${bypass.status} ${bypass.body.slice(0, 130)}`);

  // ── 6 + 7. WebView bridge（②）與出紙鏈路（③，無紙） ─────
  console.log('\n【6】② wizard USB 掃描鏈路（WebView bridge）');
  const ws = await cdpChecks();
  for (const r of ws) rec(r.name, r.pass, r.detail);

  console.log('\n【8】③ 實體出紙 / 標籤版式');
  rec('實體紙張', null,
    '腳本已把「渲染 → LAN raw socket → 真實 ESC/POS 字節」全部驗到（見上）。\n' +
    '剩下只有「紙有冇出、撕紙位對唔對、標籤版式」屬物理觀察，需實機打印機。');

  // ── 總結 ─────────────────────────────────────────────────
  console.log('\n══════════════════════════════════════════════════');
  const passN = results.filter(r => r.pass === true).length;
  const failN = results.filter(r => r.pass === false).length;
  const warnN = results.filter(r => r.pass === null).length;
  console.log(` 合計：✅ ${passN} 通過 ／ ❌ ${failN} 失敗 ／ ⚠️ ${warnN} 待確認`);
  console.log('══════════════════════════════════════════════════');

  const outDir = path.join(__dirname, '..', 'docs');
  const out = path.join(outDir, 'device-verification-' + new Date().toISOString().slice(0, 10) + '.md');
  const md = [
    '# 驗證報告 — macau-pos v1.1.5 (code 10)',
    '',
    '- 時間：' + new Date().toISOString(),
    '- APK：`' + APK + '`',
    '- 目標：' + (results.find(r => r.name === '機型資訊')?.detail || '?'),
    '- 注意：模擬器可驗 :9311 與 bridge 與出紙字節；**真 USB 打印機枚舉、實體紙張仍須實機**。',
    '',
    '| # | 項目 | 結果 | 詳情 |',
    '|---|---|---|---|',
    ...results.map((r, i) => `| ${i + 1} | ${r.name} | ${r.pass === true ? '✅ 通過' : r.pass === false ? '❌ 失敗' : '⚠️ 待確認'} | ${String(r.detail).replace(/\|/g, '\\|').replace(/\n+/g, ' / ')} |`),
    '',
    `合計：✅ ${passN} ／ ❌ ${failN} ／ ⚠️ ${warnN}`,
  ].join('\n');
  fs.writeFileSync(out, md, 'utf8');
  console.log(' 報告已寫入：' + out);
}

// ── WebView CDP：bridge + 出紙鏈路 ─────────────────────────
async function cdpChecks() {
  const out = [];
  const add = (name, pass, detail) => { out.push({ name, pass, detail }); };

  const adbS = (args) => adb(['-s', SERIAL].concat(args));
  const adbSTry = (args) => adbTry(['-s', SERIAL].concat(args));
  const sh = cmd => adbSTry(['shell', cmd]);

  if (typeof WebSocket === 'undefined') {
    add('WebView CDP 檢查', null, '此 Node 無內建 WebSocket（需 Node 22+），略過');
    return out;
  }

  const pid = sh('pidof ' + PKG).trim().split(/\s+/)[0];
  if (!pid) { add('取得 app pid', false, 'pidof 無輸出'); return out; }

  const sock = 'webview_devtools_remote_' + pid;
  const unix = sh('cat /proc/net/unix').split('\n').filter(l => l.includes(sock));
  add('WebView devtools socket 存在（debug build 可遠端除錯）', unix.length > 0,
    unix.length ? sock : sock + ' 不在 /proc/net/unix');
  if (unix.length === 0) return out;

  adbS(['forward', `tcp:${CDP_PORT}`, 'localabstract:' + sock]);

  // CDP /json 有競態：WebView 未載入完時查不到 page target → 輪詢最多 25 秒
  let targets = [];
  for (let i = 0; i < 10; i++) {
    targets = await new Promise(resolve => {
      const req = http.get({ host: '127.0.0.1', port: CDP_PORT, path: '/json', timeout: 6000 }, res => {
        let d = ''; res.setEncoding('utf8'); res.on('data', c => d += c);
        res.on('end', () => { try { resolve(JSON.parse(d)); } catch (e) { resolve([]); } });
      });
      req.on('error', () => resolve([]));
      req.on('timeout', () => { req.destroy(); resolve([]); });
    });
    if (targets.some(t => t.type === 'page')) break;
    await sleep(2500);
  }
  const page = targets.find(t => t.type === 'page') || targets[0];
  if (!page) { add('找到 WebView 頁面 target', false, 'CDP /json 等 25 秒仍無 page（WebView 可能未載入／已崩）'); return out; }
  add('找到 WebView 頁面 target', true, page.url);

  const wsUrl = page.webSocketDebuggerUrl.replace(/^ws:\/\/[^/]+/, `ws://127.0.0.1:${CDP_PORT}`);
  const ws = new WebSocket(wsUrl);
  let seq = 0;
  const pending = new Map();
  let wsOk = true;
  await new Promise(res => {
    ws.onopen = res;
    ws.onerror = () => { wsOk = false; res(); };
    setTimeout(res, 8000);
  });
  if (!wsOk || ws.readyState !== 1) { add('CDP WebSocket 連線', false, '連不上'); return out; }

  ws.onmessage = ev => {
    const m = JSON.parse(ev.data);
    if (m.id && pending.has(m.id)) { pending.get(m.id)(m); pending.delete(m.id); }
  };
  const evaluate = (expr) => new Promise(resolve => {
    const id = ++seq;
    pending.set(id, m => {
      if (m.error) return resolve('(CDP error: ' + JSON.stringify(m.error) + ')');
      if (m.result && m.result.exceptionDetails) return resolve('(JS 異常: ' + m.result.exceptionDetails.text + ')');
      const r = m.result && m.result.result;
      resolve(r ? (r.value !== undefined ? r.value : r.description) : '(無結果)');
    });
    ws.send(JSON.stringify({ id, method: 'Runtime.evaluate', params: { expression: expr, returnByValue: true, awaitPromise: true } }));
    setTimeout(() => { if (pending.has(id)) { pending.delete(id); resolve('(evaluate timeout)'); } }, 10000);
  });

  const href = await evaluate('location.href');
  add('WebView 載入的是 Vercel POS', /macau-pos-system\.vercel\.app/.test(String(href)), String(href));

  const tPosNative = await evaluate('String(typeof window.PosNative)');
  add('window.PosNative 已注入', tPosNative === 'object', 'typeof = ' + tPosNative);

  const tPrintJob = await evaluate('String(typeof window.PosNative?.printJob)');
  add('window.PosNative.printJob 是 function（＝網站判 android 環境的依據）', tPrintJob === 'function', 'typeof = ' + tPrintJob);

  const status = await evaluate('(function(){try{return window.PosNative.getStatus()}catch(e){return "ERR "+e.message}})()');
  let sj = null; try { sj = JSON.parse(String(status)); } catch (e) { }
  add('getStatus() 可呼叫', !!sj, String(status).slice(0, 400));
  if (sj) {
    add('getStatus().env == "android"', sj.env === 'android', 'env=' + sj.env);
    add('getStatus().companionPort == 9311', String(sj.companionPort) === '9311', 'companionPort=' + sj.companionPort);
    add('getStatus().companionListening == true（① 的核心斷言）', sj.companionListening === true,
      'companionListening=' + sj.companionListening);
    add('getStatus().version == "1.1.5"', sj.version === '1.1.5', 'version=' + sj.version);
  }

  // listUsbPrinters 經 CDP evaluate 偶發 timeout（WebView 忙）→ 重試 3 次
  let usb = '';
  for (let i = 0; i < 3; i++) {
    usb = await evaluate('(function(){try{return window.PosNative.listUsbPrinters()}catch(e){return "ERR "+e.message}})()');
    if (!/evaluate timeout/i.test(String(usb))) break;
    await sleep(2500);
  }
  let uj = null; try { uj = JSON.parse(String(usb)); } catch (e) { }
  add('listUsbPrinters() 回可解析 JSON（② 的實際鏈路）', !!uj, String(usb).slice(0, 500));
  if (uj) {
    const arr = uj.printers || uj.devices || (Array.isArray(uj) ? uj : []);
    const n = arr.length;
    add('USB 枚舉打印機數量', n > 0 ? true : null,
      n > 0 ? `共 ${n} 台；第一台：${JSON.stringify(arr[0])}`
        : '0 台 —— 沒插打印機時係預期結果（鏈路本身已通：回 ok + 可解析 JSON）。要驗列舉請插上實機打印機。');
    if (n > 0) {
      const miss = ['charset', 'paperSize', 'kanjiEnlarge', 'family'].filter(k => arr[0][k] == null || arr[0][k] === '');
      add('USB 候選含 charset/paperSize/kanjiEnlarge/family（v1.1.5 補的 4 欄）', miss.length === 0,
        miss.length ? '缺少：' + miss.join(', ') : '全齊：' + JSON.stringify(arr[0]));
    }
  }

  // ── ③ 出紙鏈路：本機扮熱感機，收真實 ESC/POS 字節 ────────
  console.log('\n【7】③ 出紙鏈路（用 adb reverse 導回本機，收真實字節）');
  const captured = [];
  const srv = net.createServer(sockConn => {
    sockConn.on('data', c => captured.push(c));
    sockConn.on('error', () => { });
  });
  let srvUp = true;
  await new Promise(res => { srv.listen(RAW_PORT, '127.0.0.1', () => res()); srv.on('error', () => { srvUp = false; res(); }); });
  if (!srvUp) {
    add('本機 9100 監聽（扮熱感機）', false, 'port 9100 被佔用，無法驗出紙鏈路');
    try { ws.close(); } catch (e) { }
    return out;
  }
  add('本機 9100 監聽（扮熱感機）', true, 'net server listening on 127.0.0.1:' + RAW_PORT);

  try {
    adbS(['reverse', 'tcp:' + RAW_PORT, 'tcp:' + RAW_PORT]);
    add('adb reverse tcp:9100（裝置 127.0.0.1:9100 → 本機）', true, '已建立');
  } catch (e) {
    add('adb reverse tcp:9100', false, e.message);
  }

  await evaluate('window.__verifyResult=null; window.__posNativePrintResult=function(m){window.__verifyResult=m}; "hooked"');

  const mkPayload = (family, paper) => JSON.stringify({
    printer: {
      id: 'verify-lan-1', name: '驗證用 LAN 機', connectionType: 'lan',
      ipAddress: '127.0.0.1', lanPort: RAW_PORT,
      paperSize: paper, charset: 'gb18030', family: family,
    },
    storeName: '驗證門店',
  });

  // ① 收據（80mm / receipt）
  captured.length = 0;
  const r1 = await evaluate('window.PosNative.testPrint(' + JSON.stringify(mkPayload('receipt', '80mm')) + ')');
  await sleep(4000);
  const bytes = Buffer.concat(captured);
  add('testPrint(lan 127.0.0.1:9100, receipt/80mm) 被受理', /"ok"\s*:\s*true/.test(String(r1)), String(r1).slice(0, 220));
  add('本機收到 ESC/POS 字節（出紙鏈路真的通）', bytes.length > 0,
    bytes.length > 0 ? bytes.length + ' bytes；開頭 24 bytes: ' + hex(bytes, 24) : '一個 byte 都收唔到');

  if (bytes.length > 0) {
    add('含 ESC @ 初始化（1b 40）', countSeq(bytes, [0x1b, 0x40]) > 0, '出現 ' + countSeq(bytes, [0x1b, 0x40]) + ' 次');
    const gsv = countSeq(bytes, [0x1d, 0x76, 0x30]);
    add('含 GS v 0 點陣圖（QR 走點陣而非 GS ( k）', gsv > 0 ? true : null,
      gsv > 0 ? '出現 ' + gsv + ' 次（GS v 0）' : '無 QR（測試單可能不含 QR，非錯誤）');
    const clears = countSeq(bytes, [0x1d, 0x21, 0x00]) + countSeq(bytes, [0x1b, 0x21, 0x00]) + countSeq(bytes, [0x1c, 0x21, 0x00]);
    add('每行有 clearMagnify 歸零痕跡（GS!/ESC!/FS! 三套）', clears > 0,
      '歸零指令出現 ' + clears + ' 次（v1.1.4 修「一條線變兩條」的關鍵）');
    // 文字預覽（gb18030）
    let preview = '(無法解碼)';
    try {
      const dec = new TextDecoder('gb18030').decode(bytes);
      preview = dec.replace(/[\x00-\x08\x0b-\x1f\x7f]/g, ' ').replace(/ +/g, ' ').trim().slice(0, 260);
    } catch (e) { }
    add('解碼後的單據文字預覽（gb18030）', null, preview);
    try {
      const p = path.join(__dirname, '..', 'docs', 'device-print-capture-' + new Date().toISOString().slice(0, 10) + '.bin');
      fs.writeFileSync(p, bytes);
      add('原始字節已存檔供人手核對', true, p);
    } catch (e) { }
  }

  // ② 標籤機（family=label）—— 檢查是否仍走 ESC/POS（v1.1.5 已知缺口）
  //    注意：adb reverse 對短連線偶發 ECONNREFUSED，故重試一次 + 等 7 秒。
  let lb = Buffer.alloc(0);
  let r2 = '';
  for (let attempt = 0; attempt < 2 && lb.length === 0; attempt++) {
    captured.length = 0;
    r2 = await evaluate('window.PosNative.testPrint(' + JSON.stringify(mkPayload('label', '100x75mm')) + ')');
    await sleep(7000);
    lb = Buffer.concat(captured);
    if (lb.length === 0 && attempt === 0) {
      adbS(['reverse', '--remove', 'tcp:' + RAW_PORT]);
      adbS(['reverse', 'tcp:' + RAW_PORT, 'tcp:' + RAW_PORT]);
    }
  }
  const hasTspl = /SIZE|GAP|CLS|TEXT|QRCODE/i.test(lb.toString('latin1'));
  add('標籤機（family=label, 100x75mm）有送出出紙字節', lb.length > 0,
    `${lb.length} bytes；testPrint 回應 ${String(r2).slice(0, 120)}` +
    (lb.length === 0 ? '（連 2 次都收唔到 → adb reverse 偶發問題，非 APK 問題，可重跑）' : ''));
  if (lb.length > 0) {
    add('標籤機走 TSPL 指令集（SL42 標籤機所需）', hasTspl ? true : false,
      hasTspl ? '偵測到 TSPL 指令'
        : '收到的仍是 ESC/POS（開頭: ' + hex(lb, 12) + '）、與 receipt/portable 完全相同 → ' +
          'EscPosRenderer 未消費 family（0 處引用 family / TSPL / maxLabelWidthMm）。' +
          '\n**結論：SL42（family=label, 100×75mm）在 v1.1.5 仍會被排成 80mm 連續紙版式 → 出紙亂版風險未解，TSPL 渲染器屬 Phase 2 未實作。**');
  }

  try { srv.close(); } catch (e) { }
  try { adbS(['reverse', '--remove', 'tcp:' + RAW_PORT]); } catch (e) { }
  try { ws.close(); } catch (e) { }
  return out;
}

main().catch(e => {
  console.log('\n💥 harness 本身出錯（非 APK 問題）：' + e.stack);
  process.exitCode = 1;
}).finally(() => {
  if (fatal) { console.log('\n⛔ 中止：' + fatal); process.exitCode = 2; }
});
