/* justrain — vanilla port of Rain.dc.html.
   Canvas rain + thunder flash ported verbatim from the design's tick loop.
   Audio (Web Audio API) is the app's own addition on top of the static design. */

const $ = (id) => document.getElementById(id);

const state = {
  playing: true, vol: 0.72, timer: 0, remaining: 0, elapsed: 0,
  sheet: null, chrome: true,
  thunder: true, softStart: true, background: true, dim: false,
};
let idleAt = Date.now();

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
  // last 60s of the timer thins the rain out
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
    if (boltT <= 0) { flash = 1; boltT = 9000 + Math.random() * 14000; triggerThunderAudio(); }
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

/* ─────────────────────────── audio engine ─────────────────────────── */
let audioCtx = null, gainNode = null, source = null, buffer = null, audioReady = false;

async function initAudio() {
  if (audioCtx) return;
  audioCtx = new (window.AudioContext || window.webkitAudioContext)();
  gainNode = audioCtx.createGain();
  gainNode.gain.value = 0;
  gainNode.connect(audioCtx.destination);
  try {
    const res = await fetch("assets/rain.ogg");
    const ab = await res.arrayBuffer();
    buffer = await audioCtx.decodeAudioData(ab);
    audioReady = true;
    if (state.playing) startSource();
    applyGain(state.softStart ? 30 : 0.4);
  } catch (e) { console.error("audio load failed", e); }
}
function startSource() {
  if (!audioReady || source) return;
  source = audioCtx.createBufferSource();
  source.buffer = buffer;
  source.loop = true;              // seamless: the clip is crossfade-looped
  source.connect(gainNode);
  source.start();
}
function stopSource() {
  if (source) { try { source.stop(); } catch (_) {} source.disconnect(); source = null; }
}
function targetGain() {
  if (!state.playing) return 0;
  let g = state.vol;
  if (state.timer > 0 && state.remaining < 60) g *= Math.max(0, state.remaining / 60);
  return g;
}
// ramp gain toward its target over `seconds`
function applyGain(seconds) {
  if (!gainNode || !audioCtx) return;
  const now = audioCtx.currentTime;
  gainNode.gain.cancelScheduledValues(now);
  gainNode.gain.setValueAtTime(Math.max(0.0001, gainNode.gain.value), now);
  const tgt = Math.max(0.0001, targetGain());
  gainNode.gain.linearRampToValueAtTime(tgt, now + Math.max(0.02, seconds));
}
function triggerThunderAudio() { /* rain loop is deliberately thunder-free; thunder is visual only for now */ }

async function resumeAudio() {
  if (!audioCtx) { await initAudio(); return; }
  if (audioCtx.state === "suspended") { try { await audioCtx.resume(); } catch (_) {} }
}

/* ─────────────────────────── screen wake lock ─────────────────────────── */
let wakeLock = null;
async function updateWakeLock() {
  try {
    if (state.playing && "wakeLock" in navigator) {
      if (!wakeLock) wakeLock = await navigator.wakeLock.request("screen");
    } else if (wakeLock) { await wakeLock.release(); wakeLock = null; }
  } catch (_) { wakeLock = null; }
}
document.addEventListener("visibilitychange", () => {
  if (document.visibilityState === "visible" && state.playing) updateWakeLock();
});

/* ─────────────────────────── media session ─────────────────────────── */
function initMediaSession() {
  if (!("mediaSession" in navigator)) return;
  navigator.mediaSession.metadata = new MediaMetadata({ title: "Rain", artist: "petrichor", album: "justrain" });
  navigator.mediaSession.setActionHandler("play", () => { if (!state.playing) togglePlay(); });
  navigator.mediaSession.setActionHandler("pause", () => { if (state.playing) togglePlay(); });
}
function updateMediaSession() {
  if ("mediaSession" in navigator) navigator.mediaSession.playbackState = state.playing ? "playing" : "paused";
}

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
  state.playing = !state.playing; state.chrome = true;
  resumeAudio();
  if (state.playing) { startSource(); applyGain(state.softStart ? 30 : 0.4); }
  else { applyGain(0.6); }
  updateWakeLock(); updateMediaSession(); render();
}
function toggleThunder() { idleAt = Date.now(); state.thunder = !state.thunder; render(); }
function setTimer(m) {
  idleAt = Date.now();
  state.timer = m; state.remaining = m * 60; state.sheet = null;
  if (m > 0) { state.playing = true; resumeAudio(); startSource(); }
  applyGain(0.4); updateWakeLock(); updateMediaSession(); render();
}
function toggleSetting(key) {
  state[key] = !state[key];
  if (key === "dim") updateDim();
  render();
}
function openTimer() { state.sheet = "timer"; state.chrome = true; render(); }
function openSettings() { state.sheet = "settings"; state.chrome = true; render(); }
function closeSheet() { state.sheet = null; render(); }

/* volume drag */
function onVolDown(e) {
  const el = $("volTrack");
  const move = (ev) => {
    const r = el.getBoundingClientRect();
    const x = (ev.touches ? ev.touches[0].clientX : ev.clientX);
    idleAt = Date.now();
    state.vol = Math.max(0, Math.min(1, (x - r.left) / r.width));
    applyGain(0.1); render();
  };
  move(e);
  const up = () => {
    window.removeEventListener("pointermove", move);
    window.removeEventListener("pointerup", up);
  };
  window.addEventListener("pointermove", move);
  window.addEventListener("pointerup", up);
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

  // play/pause icon
  $("pauseIcon").style.display = s.playing ? "block" : "none";
  $("playIcon").style.display = s.playing ? "none" : "block";
  $("playBtn").style.paddingLeft = s.playing ? "0" : "5px";

  // volume
  $("volFill").style.width = (s.vol * 100) + "%";
  $("volKnob").style.left = (s.vol * 100) + "%";

  // thunder switch
  $("thunderTrack").classList.toggle("on", s.thunder);

  // timer row value
  const tv = $("timerVal");
  tv.textContent = s.timer > 0 ? s.timer + " min" : "off";
  tv.classList.toggle("on", s.timer > 0);

  // chrome + gear visibility
  $("chrome").style.opacity = show ? "1" : "0";
  $("chrome").style.transform = show ? "translateY(0)" : "translateY(14px)";
  $("chrome").style.pointerEvents = show ? "auto" : "none";
  $("gearWrap").style.opacity = show ? "1" : "0";
  $("gearWrap").style.pointerEvents = show ? "auto" : "none";

  // sheets
  $("backdrop").classList.toggle("show", !!s.sheet);
  $("timerSheet").classList.toggle("open", s.sheet === "timer");
  $("settingsSheet").classList.toggle("open", s.sheet === "settings");

  // timer options selected state
  $("timerList").querySelectorAll(".opt").forEach((b) => {
    const m = Number(b.dataset.m);
    b.classList.toggle("sel", s.timer === m);
    b.querySelector(".opt-sub").textContent = m === 0 ? "rain keeps going" : "until " + clock(m);
    b.querySelector(".opt-check").style.display = s.timer === m ? "block" : "none";
  });
  // settings switches
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
      if (s.remaining < 60) applyGain(1.0); // track the rain-fade over the last minute
      if (s.remaining === 0) { s.playing = false; s.timer = 0; s.chrome = true; stopSource(); updateWakeLock(); updateMediaSession(); }
    }
    render();
  }
  if (state.playing && !state.sheet && state.chrome && Date.now() - idleAt > 4500) {
    state.chrome = false; render();
  }
}, 1000);

/* ─────────────────────────── wire up ─────────────────────────── */
$("screen").addEventListener("click", (e) => {
  // tapping empty screen reveals chrome (ignore clicks on buttons/sheets which stopPropagation)
  if (e.target === $("screen") || e.target === $("rain") || e.target === $("vignette") || e.target === $("glow") || e.target === $("ui") || e.target.classList.contains("center")) reveal();
});
$("playBtn").addEventListener("click", (e) => { e.stopPropagation(); togglePlay(); });
$("thunderRow").addEventListener("click", (e) => { e.stopPropagation(); toggleThunder(); });
$("timerRow").addEventListener("click", (e) => { e.stopPropagation(); openTimer(); });
$("gearBtn").addEventListener("click", (e) => { e.stopPropagation(); openSettings(); });
$("backdrop").addEventListener("click", closeSheet);
$("volTrack").addEventListener("pointerdown", (e) => { e.stopPropagation(); onVolDown(e); });

// first user gesture unlocks audio (Android webview autoplay policy)
function firstGesture() { resumeAudio(); window.removeEventListener("pointerdown", firstGesture); window.removeEventListener("touchstart", firstGesture); }
window.addEventListener("pointerdown", firstGesture);
window.addEventListener("touchstart", firstGesture);

buildLists();
initMediaSession();
render();
requestAnimationFrame(tick);
initAudio().then(() => { resumeAudio(); updateWakeLock(); });
