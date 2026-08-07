/* justrain — vanilla port of Rain.dc.html.
   Canvas rain + thunder flash ported verbatim from the design's tick loop.
   Audio is bundled into the APK and played NATIVELY via a Media3
   (ExoPlayer + MediaSessionService) plugin so it has a media notification and
   keeps playing with the screen off. The webview is just the UI + controller. */

const $ = (id) => document.getElementById(id);

const TAURI = window.__TAURI__;
const invoke = TAURI ? TAURI.core.invoke : null;
const NP = "plugin:native-player|";

const state = {
  playing: false,          // becomes true only once audio is actually loaded & started
  vol: 0.72, timer: 0, remaining: 0, elapsed: 0,
  sheet: null, chrome: true,
  thunder: true, softStart: true, background: true, dim: false,
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
  const fade = s.timer > 0 && s.remaining < 60 ? Math.max(0, s.remaining / 60) : 1;
  const target = s.playing ? 0.82 * fade : 0;
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
let audioReady = false, startedOnce = false, fadeTimer = null, curVol = 0;

function targetVol() {
  if (!state.playing) return 0;
  let g = state.vol;
  if (state.timer > 0 && state.remaining < 60) g *= Math.max(0, state.remaining / 60);
  return Math.max(0, Math.min(1, g));
}
function clearFade() { if (fadeTimer) { clearInterval(fadeTimer); fadeTimer = null; } }
let volErrShown = false;
async function npSetVol(v) {
  curVol = Math.max(0, Math.min(1, v));
  try { await invoke(NP + "set_volume", { volume: curVol }); }
  catch (e) {
    console.error("[justrain] set_volume", e);
    if (!volErrShown) { volErrShown = true; showError("volume control failed: " + errText(e), e); }
  }
}
function fadeTo(target, ms) {
  clearFade();
  target = Math.max(0, Math.min(1, target));
  if (ms <= 0) { npSetVol(target); return; }
  const start = curVol, steps = Math.max(1, Math.round(ms / 120)), stepMs = ms / steps;
  let i = 0;
  fadeTimer = setInterval(() => {
    i++; const k = Math.min(1, i / steps);
    npSetVol(start + (target - start) * k);
    if (k >= 1) clearFade();
  }, stepMs);
}
function setVolImmediate() { clearFade(); npSetVol(targetVol()); }
async function npPlay() {
  try { await invoke(NP + "play"); return true; }
  catch (e) { showError("play failed: " + errText(e), e); return false; }
}
async function npPause() {
  try { await invoke(NP + "pause"); }
  catch (e) { showError("pause failed: " + errText(e), e); }
}

// Reflect state.playing onto the native player, with a soft/quick fade.
async function applyPlayState() {
  if (!audioReady) return;
  if (state.playing) {
    const tgt = targetVol();
    const soft = state.softStart && !startedOnce;
    await npSetVol(soft ? Math.min(tgt, tgt * 0.25) : tgt);   // set volume before play, no blip
    await npPlay();
    startedOnce = true;
    fadeTo(tgt, soft ? 18000 : 400);
  } else {
    fadeTo(0, 600);
    setTimeout(() => { if (!state.playing) npPause(); }, 700);
  }
}

// Boot: the rain audio is bundled in the APK, so the native player is ready
// as soon as the plugin binds to its service. Start playing immediately.
async function bootAudio() {
  if (!invoke) { showError("not running under Tauri — audio unavailable"); return; }
  audioReady = true;
  try {
    state.playing = true;
    await applyPlayState();
  } catch (e) {
    state.playing = false;
    showError("couldn't start audio: " + errText(e), e);
  }
  render();
}

// "keep playing when locked": when off, pause on background and resume on return.
document.addEventListener("visibilitychange", () => {
  if (!audioReady || state.background) return;
  if (document.hidden) { if (state.playing) npPause(); }
  else if (state.playing) { npPlay(); setVolImmediate(); }
});

/* ─────────────────────────── helpers (ported) ─────────────────────────── */
function fmt(s) { const m = Math.floor(s / 60), r = Math.floor(s % 60); return m + ":" + String(r).padStart(2, "0"); }
function clock(minsFromNow) {
  const d = new Date(Date.now() + minsFromNow * 60000);
  let h = d.getHours(); const ap = h >= 12 ? "pm" : "am"; h = h % 12 || 12;
  return h + ":" + String(d.getMinutes()).padStart(2, "0") + " " + ap;
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
function setTimer(m) {
  idleAt = Date.now();
  state.timer = m; state.remaining = m * 60; state.sheet = null;
  if (m > 0 && audioReady) state.playing = true;
  applyPlayState();
  render();
}
function toggleSetting(key) {
  state[key] = !state[key];
  if (key === "dim") updateDim();
  render();
}
function openTimer() { state.sheet = "timer"; state.chrome = true; render(); }
function openSettings() { state.sheet = "settings"; state.chrome = true; render(); }
function closeSheet() { state.sheet = null; render(); }

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
const TIMER_OPTS = [0, 15, 30, 45, 60, 90];
const SETTINGS = [
  { key: "thunder", label: "thunder", sub: "a distant roll every few minutes" },
  { key: "softStart", label: "soft start", sub: "rain fades in over half a minute" },
  { key: "background", label: "keep playing when locked", sub: "rain continues with the screen off" },
  { key: "dim", label: "dim the screen", sub: "darkens after the controls fade away" },
];

function buildLists() {
  const tl = $("timerList");
  tl.innerHTML = "";
  TIMER_OPTS.forEach((m) => {
    const b = document.createElement("button");
    b.className = "opt"; b.dataset.m = m;
    b.innerHTML =
      '<span class="opt-text"><span class="opt-label"></span><span class="opt-sub"></span></span>' +
      '<svg class="opt-check" width="15" height="12" viewBox="0 0 15 12" fill="none" style="display:none"><path d="M1.5 6.4l4 4L13.5 1.6" stroke="#9184d9" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"></path></svg>';
    b.querySelector(".opt-label").textContent = m === 0 ? "off" : m + " minutes";
    b.addEventListener("click", () => setTimer(m));
    tl.appendChild(b);
  });
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
  const show = s.chrome || !s.playing;

  $("stateWord").textContent = s.playing ? "raining" : "paused";
  $("bigTime").textContent = s.timer > 0 ? fmt(s.remaining) : fmt(s.elapsed);
  $("bigLabel").textContent = s.timer > 0 ? "stops at " + clock(s.remaining / 60) : (s.playing ? "raining for" : "held");

  $("pauseIcon").style.display = s.playing ? "block" : "none";
  $("playIcon").style.display = s.playing ? "none" : "block";
  $("playBtn").style.paddingLeft = s.playing ? "0" : "5px";

  $("volFill").style.width = (s.vol * 100) + "%";
  $("volKnob").style.left = (s.vol * 100) + "%";

  $("thunderTrack").classList.toggle("on", s.thunder);

  const tv = $("timerVal");
  tv.textContent = s.timer > 0 ? s.timer + " min" : "off";
  tv.classList.toggle("on", s.timer > 0);

  $("chrome").style.opacity = show ? "1" : "0";
  $("chrome").style.transform = show ? "translateY(0)" : "translateY(14px)";
  $("chrome").style.pointerEvents = show ? "auto" : "none";
  $("gearWrap").style.opacity = show ? "1" : "0";
  $("gearWrap").style.pointerEvents = show ? "auto" : "none";

  $("backdrop").classList.toggle("show", !!s.sheet);
  $("timerSheet").classList.toggle("open", s.sheet === "timer");
  $("settingsSheet").classList.toggle("open", s.sheet === "settings");

  $("timerList").querySelectorAll(".opt").forEach((b) => {
    const m = Number(b.dataset.m);
    b.classList.toggle("sel", s.timer === m);
    b.querySelector(".opt-sub").textContent = m === 0 ? "rain keeps going" : "until " + clock(m);
    b.querySelector(".opt-check").style.display = s.timer === m ? "block" : "none";
  });
  $("settingsList").querySelectorAll(".set").forEach((b) => {
    b.querySelector(".switch").classList.toggle("on", !!s[b.dataset.key]);
  });

  updateDim(show);
}
function updateDim(show) {
  if (show === undefined) show = state.chrome || !state.playing;
  $("screen").classList.toggle("dim", state.dim && state.playing && !show && !state.sheet);
}

/* ─────────────────────────── clock loop ─────────────────────────── */
setInterval(() => {
  const s = state;
  if (s.playing) {
    s.elapsed += 1;
    if (s.timer > 0) {
      s.remaining = Math.max(0, s.remaining - 1);
      if (s.remaining < 60) setVolImmediate();       // track the rain-fade over the last minute
      if (s.remaining === 0) { s.playing = false; s.timer = 0; s.chrome = true; applyPlayState(); }
    }
    render();
  }
  if (state.playing && !state.sheet && state.chrome && Date.now() - idleAt > 4500) {
    state.chrome = false; render();
  }
}, 1000);

/* ─────────────────────────── wire up ─────────────────────────── */
$("screen").addEventListener("click", (e) => {
  if (e.target === $("screen") || e.target === $("rain") || e.target === $("vignette") || e.target === $("glow") || e.target === $("ui") || e.target.classList.contains("center")) reveal();
});
$("playBtn").addEventListener("click", (e) => { e.stopPropagation(); togglePlay(); });
$("thunderRow").addEventListener("click", (e) => { e.stopPropagation(); toggleThunder(); });
$("timerRow").addEventListener("click", (e) => { e.stopPropagation(); openTimer(); });
$("gearBtn").addEventListener("click", (e) => { e.stopPropagation(); openSettings(); });
$("backdrop").addEventListener("click", closeSheet);
$("volTrack").addEventListener("pointerdown", (e) => { e.stopPropagation(); onVolDown(e); });
$("errclose").addEventListener("click", (e) => { e.stopPropagation(); hideError(); });

buildLists();
$("appVersion").textContent = "justrain · " + (window.__JUSTRAIN_VERSION__ || "dev");
render();
requestAnimationFrame(tick);
bootAudio();
