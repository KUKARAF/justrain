/* justrain — vanilla port of Rain.dc.html.
   Canvas rain + thunder flash ported verbatim from the design's tick loop.
   Audio is bundled into the APK and played NATIVELY via a raw AudioTrack
   engine (ported from metiq-xyz/android-app) with a MediaSession purely for
   the OS notification, so it has a media notification and keeps playing
   with the screen off. Play/pause fades happen natively — the webview is
   just the UI + controller. */

const $ = (id) => document.getElementById(id);

const TAURI = window.__TAURI__;
const invoke = TAURI ? TAURI.core.invoke : null;
const NP = "plugin:native-player|";

// Persisted honor-system flag: when on, the daily tip nudge never shows. We
// never verify it — that's the whole point. Wrapped in try/catch because
// localStorage can throw (private mode, blocked storage).
function loadPaid() { try { return localStorage.getItem("justrain.paid") === "1"; } catch (_) { return false; } }
function savePaid(v) { try { localStorage.setItem("justrain.paid", v ? "1" : "0"); } catch (_) {} }
// "pause other audio" (native audio focus) — defaults to on.
function loadExclusive() { try { return localStorage.getItem("justrain.exclusive") !== "0"; } catch (_) { return true; } }
function saveExclusive(v) { try { localStorage.setItem("justrain.exclusive", v ? "1" : "0"); } catch (_) {} }

const state = {
  playing: false,          // becomes true only once audio is actually loaded & started
  vol: 0.72,
  tray: false, chrome: true,   // tray: settings tray pulled out; chrome: peek visible
  thunder: true, softStart: true, background: true, dim: false,
  alreadyPaid: loadPaid(),
  exclusive: loadExclusive(),
};
let idleAt = Date.now();

/* surface failures instead of swallowing them */
// Tauri/plugin rejections often arrive as objects ({message: "..."}), not
// strings — naive string concatenation turns those into "[object Object]".
function errText(e) {
  if (e == null) return "unknown error";
  if (typeof e === "string") return e;
  if (e instanceof Error) return e.message;
  if (typeof e === "object") {
    if (typeof e.message === "string") return e.message;
    if (typeof e.error === "string") return e.error;
    try { return JSON.stringify(e); } catch (_) { /* fall through */ }
  }
  return String(e);
}
function showError(msg, raw) {
  console.error("[justrain]", msg, raw !== undefined ? raw : "");
  const el = $("errmsg"); if (el) el.textContent = String(msg);
  const bar = $("errbar"); if (bar) bar.classList.add("show");
}
function hideError() { const bar = $("errbar"); if (bar) bar.classList.remove("show"); }

/* ─────────────────────────── canvas rain ─────────────────────────── */
const canvas = $("rain");
let ctx, W, H, drops = [], inten = 0, flash = 0, boltT = 6000, lastT = 0;

function initCanvas() {
  const dpr = Math.min(2, window.devicePixelRatio || 1);
  const w = canvas.clientWidth, h = canvas.clientHeight;
  canvas.width = w * dpr; canvas.height = h * dpr;
  ctx = canvas.getContext("2d");
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  W = w; H = h;
  drops = []; inten = 0; flash = 0; boltT = 6000;
}
function newDrop() {
  const near = Math.random() > 0.74;
  return {
    x: Math.random() * W * 1.3 - W * 0.15,
    y: -30 - Math.random() * 300,
    len: near ? 30 + Math.random() * 46 : 10 + Math.random() * 22,
    sp: near ? 14 + Math.random() * 8 : 6 + Math.random() * 5,
    w: near ? 1.5 : 0.8,
    a: near ? 0.4 + Math.random() * 0.22 : 0.13 + Math.random() * 0.14,
    near,
  };
}
function tick(t) {
  requestAnimationFrame(tick);
  if (canvas.clientWidth > 0 && (!ctx || W !== canvas.clientWidth || H !== canvas.clientHeight)) initCanvas();
  if (!ctx) return;
  const dt = Math.min(48, t - (lastT || t)); lastT = t;
  const k = dt / 16.67;
  const s = state;
  const target = s.playing ? 0.82 : 0;
  const rate = s.softStart && s.playing ? 0.012 : 0.03;
  inten += (target - inten) * rate * k;
  const I = inten, vol = 0.45 + s.vol * 0.55;
  ctx.clearRect(0, 0, W, H);

  const want = I * 5.2 * k;
  for (let i = 0; i < want; i++) if (Math.random() < want - i + 1) drops.push(newDrop());
  ctx.globalCompositeOperation = "lighter";
  ctx.lineCap = "round";
  for (let i = drops.length - 1; i >= 0; i--) {
    const d = drops[i];
    const sp = d.sp * (0.72 + I * 0.5);
    d.y += sp * k; d.x += sp * 0.16 * k;
    if (d.y - d.len > H) { drops.splice(i, 1); continue; }
    ctx.strokeStyle = "rgba(" + (d.near ? "210,206,253," : "178,182,202,") + (d.a * vol) + ")";
    ctx.lineWidth = d.w;
    ctx.beginPath(); ctx.moveTo(d.x, d.y); ctx.lineTo(d.x - d.len * 0.16, d.y - d.len); ctx.stroke();
  }
  ctx.globalCompositeOperation = "source-over";

  if (s.thunder && s.playing) {
    boltT -= dt;
    if (boltT <= 0) { flash = 1; boltT = 9000 + Math.random() * 14000; }
  }
  if (flash > 0.004) {
    flash *= Math.pow(0.9, k);
    if (Math.random() < 0.05 && flash > 0.25) flash = Math.min(1, flash + 0.45);
    const f = flash;
    const g = ctx.createLinearGradient(0, 0, 0, H);
    g.addColorStop(0, "rgba(210,206,253," + f * 0.26 + ")");
    g.addColorStop(0.6, "rgba(145,132,217," + f * 0.07 + ")");
    g.addColorStop(1, "rgba(145,132,217,0)");
    ctx.fillStyle = g; ctx.fillRect(0, 0, W, H);
  }
}

/* ─────────────────────────── native audio ─────────────────────────── */
// Play/pause fades happen natively (AudioEngine); the webview just tells it
// to play/pause and sets the instantaneous volume level for slider drags.
let audioReady = false, startedOnce = false;

// Right after page load, Tauri may not have finished registering the native
// plugin instance yet — invoke() rejects with "Plugin native-player not
// initialized" for a brief window. Retry a few times before surfacing it.
function sleep(ms) { return new Promise((r) => setTimeout(r, ms)); }
async function npInvoke(cmd, args) {
  const attempts = 8;
  for (let i = 0; i < attempts; i++) {
    try { return await invoke(NP + cmd, args); }
    catch (e) {
      const notInit = errText(e).toLowerCase().includes("not initialized");
      if (notInit && i < attempts - 1) { await sleep(150); continue; }
      throw e;
    }
  }
}

let volErrShown = false;
async function npSetVol(v) {
  const clamped = Math.max(0, Math.min(1, v));
  try { await npInvoke("set_volume", { volume: clamped }); }
  catch (e) {
    console.error("[justrain] set_volume", e);
    if (!volErrShown) { volErrShown = true; showError("volume control failed: " + errText(e), e); }
  }
}
function setVolImmediate() { npSetVol(state.vol); }
async function npPlay(soft) {
  try {
    const r = await npInvoke("play", { soft: !!soft, exclusive: state.exclusive });
    // {playing:false}: audio focus denied (e.g. during a call) — not an error.
    if (r && r.playing === false) { syncPlaying(false); return false; }
    return true;
  }
  catch (e) { showError("play failed: " + errText(e), e); return false; }
}
async function npPause() {
  try { await npInvoke("pause"); }
  catch (e) { showError("pause failed: " + errText(e), e); }
}

// Reflect state.playing onto the native player. The native engine owns the
// fade (soft ~18s start-of-day fade-in vs a quick ~400ms toggle fade).
async function applyPlayState() {
  if (!audioReady) return;
  if (state.playing) {
    await npSetVol(state.vol);
    await npPlay(state.softStart && !startedOnce);
    startedOnce = true;
  } else {
    await npPause();
  }
}

// Native -> JS sync. The notification, headset/Bluetooth buttons, unplugging
// headphones and audio focus (calls, Spotify) all play/pause natively; the
// plugin pushes a "state" event for each, and get_state pulls it on demand.
// lockPaused: WE paused because the screen locked with "keep playing when
// locked" off — that pause must not flip state.playing, or we'd not resume.
let lockPaused = false;
function syncPlaying(p) {
  if (p) lockPaused = false;
  else if (lockPaused) return;
  if (state.playing === p) return;
  state.playing = p;
  state.chrome = true;
  idleAt = Date.now();
  render();
}
async function resyncPlaying() {
  try { const r = await npInvoke("get_state"); if (r) syncPlaying(!!r.playing); }
  catch (e) { console.error("[justrain] get_state", e); }
}
// Same not-initialized retry as npInvoke (addPluginListener invokes the plugin too).
async function listenNative() {
  for (let i = 0; i < 8; i++) {
    try { await TAURI.core.addPluginListener("native-player", "state", (e) => syncPlaying(!!(e && e.playing))); return; }
    catch (e) {
      if (errText(e).toLowerCase().includes("not initialized") && i < 7) { await sleep(150); continue; }
      console.error("[justrain] state listener", e); return;
    }
  }
}

// Boot: the rain audio is bundled in the APK, so the native player is ready
// as soon as the plugin binds to its service. Start playing immediately.
async function bootAudio() {
  if (!invoke) { showError("not running under Tauri — audio unavailable"); return; }
  audioReady = true;
  await listenNative();
  try {
    state.playing = true;
    await applyPlayState();
  } catch (e) {
    state.playing = false;
    showError("couldn't start audio: " + errText(e), e);
  }
  render();
}

// "keep playing when locked": when off, pause on background and resume on return
// — but only if that pause was ours (lockPaused). Otherwise (or if the user
// played/paused from the notification meanwhile) just adopt the native state.
document.addEventListener("visibilitychange", () => {
  if (!audioReady) return;
  if (document.hidden) {
    if (!state.background && state.playing) { lockPaused = true; npPause(); }
  } else if (lockPaused) {
    lockPaused = false; npPlay(); setVolImmediate();
  } else {
    resyncPlaying();
  }
});

/* ─────────────────────────── tip (in-app purchase) ─────────────────────────── */
// The app is free; this is an optional consumable "buy me a coffee" handled by
// the billing plugin (Google Play Billing). Same not-initialized retry as audio.
const BILLING = "plugin:billing|";
async function billingInvoke(cmd, args) {
  const attempts = 8;
  for (let i = 0; i < attempts; i++) {
    try { return await invoke(BILLING + cmd, args); }
    catch (e) {
      if (errText(e).toLowerCase().includes("not initialized") && i < attempts - 1) { await sleep(150); continue; }
      throw e;
    }
  }
}

// Shared purchase flow for both the settings button and the daily popup. On a
// successful (or pending) tip we flip alreadyPaid so we stop nudging someone
// who just paid.
async function doTip() {
  try {
    const r = await billingInvoke("tip");
    if (r && (r.status === "purchased" || r.status === "pending")) {
      state.alreadyPaid = true;
      savePaid(true);
      hideTipPopup();
      const thanks = $("tipThanks");
      if (thanks) {
        thanks.textContent = r.status === "pending" ? "payment pending — thank you 💜" : "thank you 💜";
        thanks.classList.add("show");
      }
      const sBtn = $("tipBtn"); if (sBtn) sBtn.style.display = "none";
      render();  // reflect the "i already paid" toggle in settings
      return true;
    }
  } catch (err) {
    // Backing out of the Play sheet isn't worth an error banner.
    if (!errText(err).toLowerCase().includes("cancel")) showError("tip failed: " + errText(err), err);
  }
  return false;
}

/* once-a-day tip nudge (honor system; never gates the app) */
function todayStr() { return new Date().toISOString().slice(0, 10); }
function showTipPopup() { const p = $("tipPopup"); if (p) p.classList.add("show"); }
function hideTipPopup() { const p = $("tipPopup"); if (p) p.classList.remove("show"); }
function maybeShowDailyTip() {
  if (state.alreadyPaid || !invoke) return;
  let last = null;
  try { last = localStorage.getItem("justrain.lastNag"); } catch (_) {}
  if (last === todayStr()) return;
  try { localStorage.setItem("justrain.lastNag", todayStr()); } catch (_) {}
  showTipPopup();
}

async function initTip() {
  if (!invoke) { const b = $("tipBtn"); if (b) b.style.display = "none"; return; }  // web build: no billing

  // Show the real localized price on both tip buttons if the product is live;
  // if it isn't configured in Play yet, quietly keep the default labels.
  let label = null;
  try {
    const r = await billingInvoke("get_price");
    if (r && r.price) label = "buy me a coffee · " + r.price;
  } catch (_) { /* product not configured yet */ }
  if (label) ["tipBtn", "tipPopPay"].forEach((id) => { const el = $(id); if (el) el.textContent = label; });

  // settings-sheet button
  const sBtn = $("tipBtn");
  if (sBtn) sBtn.addEventListener("click", async (e) => {
    e.stopPropagation(); sBtn.disabled = true;
    try { await doTip(); } finally { sBtn.disabled = false; }
  });

  // daily-popup buttons
  const payBtn = $("tipPopPay");
  if (payBtn) payBtn.addEventListener("click", async (e) => {
    e.stopPropagation(); payBtn.disabled = true;
    try { await doTip(); } finally { payBtn.disabled = false; }
  });
  const laterBtn = $("tipPopLater");
  if (laterBtn) laterBtn.addEventListener("click", (e) => { e.stopPropagation(); hideTipPopup(); });
  const paidBtn = $("tipPopPaid");
  if (paidBtn) paidBtn.addEventListener("click", (e) => {
    e.stopPropagation();
    state.alreadyPaid = true; savePaid(true); hideTipPopup(); render();
  });
  const pop = $("tipPopup");  // tap the dimmed area behind the card = "maybe tomorrow"
  if (pop) pop.addEventListener("click", (e) => { if (e.target === pop) hideTipPopup(); });

  // nudge once per day, a few seconds after launch so the app settles first
  setTimeout(maybeShowDailyTip, 3500);
}

/* ─────────────────────────── actions ─────────────────────────── */
function reveal() { idleAt = Date.now(); if (!state.chrome) { state.chrome = true; render(); } }
function togglePlay() {
  idleAt = Date.now();
  if (!audioReady) return;
  state.playing = !state.playing;
  state.chrome = true;
  applyPlayState();
  render();
}
function toggleThunder() { idleAt = Date.now(); state.thunder = !state.thunder; render(); }
function toggleSetting(key) {
  state[key] = !state[key];
  if (key === "dim") updateDim();
  if (key === "alreadyPaid") savePaid(state.alreadyPaid);
  if (key === "exclusive") {
    saveExclusive(state.exclusive);
    // takes/releases audio focus right away if playing; otherwise applied on next play
    if (audioReady) npInvoke("set_exclusive", { exclusive: state.exclusive }).catch((e) => console.error("[justrain] set_exclusive", e));
  }
  render();
}

/* ─────────────────────────── settings tray ─────────────────────────── */
// The tray is a bottom sheet. Collapsed, only its top (grip, volume, thunder)
// peeks out; expanded, it slides up to reveal the rest. Its translateY is set
// here: 0 = expanded, trayOff = collapsed, trayOff + 24 (faded) = idle-hidden.
let trayOff = 0, trayDrag = null, trayPushed = false, swallowClick = false;
const safeProbe = document.createElement("div");
safeProbe.style.cssText = "position:absolute;visibility:hidden;pointer-events:none;height:env(safe-area-inset-bottom)";
document.body.appendChild(safeProbe);

function measureTray() {
  const top = $("trayTop");
  const visible = top.offsetTop + top.offsetHeight + 16 + safeProbe.offsetHeight;
  trayOff = Math.max(0, $("tray").offsetHeight - visible);
  $("screen").style.setProperty("--peek-h", visible + "px");
}

function setTray(open, fromPop) {
  idleAt = Date.now(); state.chrome = true;
  if (state.tray !== open) {
    state.tray = open;
    if (open && !trayPushed) {
      // a history entry so the Android back button collapses the tray
      try { history.pushState({ tray: 1 }, ""); trayPushed = true; } catch (_) {}
    } else if (!open) {
      $("trayMore").scrollTop = 0;
      if (trayPushed && !fromPop) { trayPushed = false; history.back(); }
    }
  }
  render();
}
window.addEventListener("popstate", () => { if (trayPushed) { trayPushed = false; setTray(false, true); } });
document.addEventListener("keydown", (e) => { if (e.key === "Escape" && state.tray) setTray(false); });

// Drag the tray by its top (grip / peek rows). The finger is followed 1:1;
// on release it snaps by fling velocity, else by whether it passed halfway.
function onTrayDown(e) {
  idleAt = Date.now();
  if (e.button > 0 || trayDrag) return;
  const base = state.tray ? 0 : trayOff;
  trayDrag = { id: e.pointerId, y0: e.clientY, base, y: base, moved: false, pts: [[e.timeStamp, e.clientY]] };
  const tray = $("tray"), bd = $("backdrop"), more = $("trayMore");
  const move = (ev) => {
    const d = trayDrag;
    if (!d || ev.pointerId !== d.id) return;
    const dy = ev.clientY - d.y0;
    if (!d.moved) {
      if (Math.abs(dy) < 6) return;
      d.moved = true;
      tray.classList.add("dragging"); bd.classList.add("dragging");
    }
    idleAt = Date.now();
    let y = d.base + dy;
    if (y < 0) y *= 0.25;                 // rubber-band past fully open
    y = Math.min(trayOff, y);
    d.y = y;
    d.pts.push([ev.timeStamp, ev.clientY]);
    while (d.pts.length > 2 && ev.timeStamp - d.pts[0][0] > 100) d.pts.shift();
    const p = trayOff ? 1 - Math.max(0, y) / trayOff : 1;
    tray.style.transform = "translateY(" + y + "px)";
    bd.style.opacity = String(p); more.style.opacity = String(p);
  };
  const up = (ev) => {
    const d = trayDrag;
    if (!d || ev.pointerId !== d.id) return;
    window.removeEventListener("pointermove", move);
    window.removeEventListener("pointerup", up);
    window.removeEventListener("pointercancel", up);
    trayDrag = null;
    if (!d.moved) return;                 // plain tap: let click handlers run
    swallowClick = true; setTimeout(() => { swallowClick = false; }, 350);
    tray.classList.remove("dragging"); bd.classList.remove("dragging");
    bd.style.opacity = ""; more.style.opacity = "";
    const [t0, y0] = d.pts[0], [t1, y1] = d.pts[d.pts.length - 1];
    const v = t1 > t0 ? (y1 - y0) / (t1 - t0) : 0;   // px/ms, + = downward
    const open = v < -0.4 ? true : v > 0.4 ? false : d.y < trayOff / 2;
    setTray(open);
  };
  window.addEventListener("pointermove", move);
  window.addEventListener("pointerup", up);
  window.addEventListener("pointercancel", up);
}

function onVolDown(e) {
  const el = $("volTrack");
  const move = (ev) => {
    const r = el.getBoundingClientRect();
    const x = ev.touches ? ev.touches[0].clientX : ev.clientX;
    idleAt = Date.now();
    state.vol = Math.max(0, Math.min(1, (x - r.left) / r.width));
    setVolImmediate(); render();
  };
  move(e);
  const up = () => { window.removeEventListener("pointermove", move); window.removeEventListener("pointerup", up); };
  window.addEventListener("pointermove", move); window.addEventListener("pointerup", up);
}

/* ─────────────────────────── render ─────────────────────────── */
// thunder lives in the tray's always-visible peek row (#thunderRow), so it is
// not repeated here.
const SETTINGS = [
  { key: "softStart", label: "soft start", sub: "rain fades in over half a minute" },
  { key: "exclusive", label: "pause other audio", sub: "pauses spotify & co. while it rains — turn off to layer rain over music" },
  { key: "background", label: "keep playing when locked", sub: "rain continues with the screen off" },
  { key: "dim", label: "dim the screen", sub: "darkens after the controls fade away" },
  { key: "alreadyPaid", label: "i already paid, promised", sub: "turns off the daily tip reminder — honor system, we don't check" },
];

function buildLists() {
  const sl = $("settingsList");
  sl.innerHTML = "";
  SETTINGS.forEach((r) => {
    const b = document.createElement("button");
    b.className = "set"; b.dataset.key = r.key;
    b.innerHTML =
      '<span class="set-text"><span class="set-label"></span><span class="set-sub"></span></span>' +
      '<span class="switch"><span class="switch-knob"></span></span>';
    b.querySelector(".set-label").textContent = r.label;
    b.querySelector(".set-sub").textContent = r.sub;
    b.addEventListener("click", () => toggleSetting(r.key));
    sl.appendChild(b);
  });
}

function render() {
  const s = state;
  const show = s.chrome || !s.playing || s.tray;

  $("pauseIcon").style.display = s.playing ? "block" : "none";
  $("playIcon").style.display = s.playing ? "none" : "block";
  $("playBtn").style.paddingLeft = s.playing ? "0" : "5px";

  $("volFill").style.width = (s.vol * 100) + "%";
  $("volKnob").style.left = (s.vol * 100) + "%";

  $("thunderTrack").classList.toggle("on", s.thunder);

  measureTray();
  if (!trayDrag || !trayDrag.moved) {
    const tray = $("tray");
    tray.style.transform = "translateY(" + (s.tray ? 0 : trayOff + (show ? 0 : 24)) + "px)";
    tray.style.opacity = show ? "1" : "0";
    tray.style.pointerEvents = show ? "auto" : "none";
    tray.classList.toggle("open", s.tray);
    $("backdrop").classList.toggle("show", s.tray);
  }
  $("trayGrip").setAttribute("aria-expanded", String(s.tray));
  $("trayMore").inert = !s.tray;

  $("settingsList").querySelectorAll(".set").forEach((b) => {
    b.querySelector(".switch").classList.toggle("on", !!s[b.dataset.key]);
  });

  updateDim(show);
}
function updateDim(show) {
  if (show === undefined) show = state.chrome || !state.playing || state.tray;
  $("screen").classList.toggle("dim", state.dim && state.playing && !show && !state.tray);
}

/* ─────────────────────────── idle-chrome-hide loop ─────────────────────────── */
setInterval(() => {
  if (state.playing && !state.tray && !trayDrag && state.chrome && Date.now() - idleAt > 4500) {
    state.chrome = false; render();
  }
}, 1000);

/* ─────────────────────────── wire up ─────────────────────────── */
$("screen").addEventListener("click", (e) => {
  if (e.target === $("screen") || e.target === $("rain") || e.target === $("vignette") || e.target === $("glow") || e.target === $("ui") || e.target.classList.contains("center")) reveal();
});
$("playBtn").addEventListener("click", (e) => { e.stopPropagation(); togglePlay(); });
$("thunderRow").addEventListener("click", (e) => { e.stopPropagation(); toggleThunder(); });
$("trayGrip").addEventListener("click", (e) => { e.stopPropagation(); setTray(!state.tray); });
$("trayTop").addEventListener("pointerdown", onTrayDown);
// a drag that started on a row must not also toggle it on release
$("tray").addEventListener("click", (e) => { if (swallowClick) { swallowClick = false; e.stopPropagation(); e.preventDefault(); } }, true);
$("backdrop").addEventListener("click", () => setTray(false));
window.addEventListener("resize", render);
$("volTrack").addEventListener("pointerdown", (e) => { e.stopPropagation(); onVolDown(e); });
$("errclose").addEventListener("click", (e) => { e.stopPropagation(); hideError(); });

buildLists();
$("appVersion").textContent = "justrain · " + (window.__JUSTRAIN_VERSION__ || "dev");
$("tray").classList.add("no-anim");   // place the tray without animating in from y=0
render();
requestAnimationFrame(() => requestAnimationFrame(() => $("tray").classList.remove("no-anim")));
requestAnimationFrame(tick);
bootAudio();
initTip();
