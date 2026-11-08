// Chalaiden's screens, redrawn in Canvas2D so they can animate frame by frame.
// Layout is measured from docs/screenshots (540 x 1188 logical px; the canvas is 2x).

export const SCREEN_W = 540;
export const SCREEN_H = 1188;
const S = 2;

const C = {
  bg: '#000000', surface: '#1C1C1E', raised: '#2C2C2F', ink: '#FFFFFF',
  soft: '#9A9AA1', line: '#3A3A3D', accent: '#3D7EFF',
};
const NUMBER = ['01712', '345', '678'];
export const AMOUNT = '250';

const clamp = (x, a = 0, b = 1) => Math.min(b, Math.max(a, x));
const ease = (x) => { x = clamp(x); return x < 0.5 ? 4 * x * x * x : 1 - Math.pow(-2 * x + 2, 3) / 2; };
const easeOut = (x) => 1 - Math.pow(1 - clamp(x), 3);
const backOut = (x) => { x = clamp(x); const c1 = 1.70158, c3 = c1 + 1; return 1 + c3 * Math.pow(x - 1, 3) + c1 * Math.pow(x - 1, 2); };
const span = (t, a, b) => clamp((t - a) / (b - a));

function rr(ctx, x, y, w, h, r) {
  r = Math.min(r, w / 2, h / 2);
  ctx.beginPath();
  ctx.moveTo(x + r, y);
  ctx.arcTo(x + w, y, x + w, y + h, r);
  ctx.arcTo(x + w, y + h, x, y + h, r);
  ctx.arcTo(x, y + h, x, y, r);
  ctx.arcTo(x, y, x + w, y, r);
  ctx.closePath();
}
function text(ctx, s, x, y, { size = 18, weight = 400, color = C.ink, align = 'left', family = 'Geist', spacing = 0, alpha = 1 } = {}) {
  ctx.save();
  ctx.globalAlpha *= alpha;
  ctx.font = `${weight} ${size}px ${family}`;
  ctx.fillStyle = color;
  ctx.textAlign = align;
  ctx.textBaseline = 'alphabetic';
  if ('letterSpacing' in ctx) ctx.letterSpacing = `${spacing}px`;
  ctx.fillText(s, x, y);
  ctx.restore();
}
function circle(ctx, x, y, r, fill) { ctx.beginPath(); ctx.arc(x, y, r, 0, Math.PI * 2); ctx.fillStyle = fill; ctx.fill(); }
function stroke(ctx, color, w, fn) {
  ctx.save(); ctx.strokeStyle = color; ctx.lineWidth = w; ctx.lineCap = 'round'; ctx.lineJoin = 'round';
  ctx.beginPath(); fn(); ctx.stroke(); ctx.restore();
}

// ---------- shared pieces ----------

function statusBar(ctx, { dark = false } = {}) {
  const fg = C.ink;
  text(ctx, '9:12', 30, 42, { size: 19, weight: 600, color: fg });
  circle(ctx, 270, 35, 9.5, '#050505');
  // cellular bars (USSD needs only the voice network)
  for (let i = 0; i < 4; i++) { ctx.fillStyle = fg; rr(ctx, 418 + i * 6.5, 42 - 5 - i * 3.6, 4, 5 + i * 3.6, 1); ctx.fill(); }
  // no-data mark: wifi slashed
  ctx.save(); ctx.globalAlpha = 0.45;
  stroke(ctx, fg, 2.2, () => { ctx.arc(396, 44, 13, -Math.PI * 0.78, -Math.PI * 0.22); });
  stroke(ctx, fg, 2.2, () => { ctx.arc(396, 44, 7.5, -Math.PI * 0.78, -Math.PI * 0.22); });
  ctx.restore();
  stroke(ctx, fg, 2.2, () => { ctx.moveTo(386, 27); ctx.lineTo(406, 45); });
  // battery
  ctx.save(); ctx.strokeStyle = fg; ctx.lineWidth = 1.6; rr(ctx, 452, 27, 26, 15, 4); ctx.stroke(); ctx.restore();
  ctx.fillStyle = fg; rr(ctx, 455, 30, 15, 9, 2); ctx.fill(); rr(ctx, 479.5, 32, 2.5, 5, 1); ctx.fill();
  text(ctx, '64%', 486, 42, { size: 16, weight: 500, color: fg });
  if (dark) return;
  // gesture bar
  ctx.fillStyle = 'rgba(255,255,255,0.75)'; rr(ctx, 182, 1170, 176, 6, 3); ctx.fill();
}

function logoMark(ctx, x, y, s) {
  // ic_launcher mark: a thick "C" arc and a blue dot (from res/drawable/logo_mark.xml, 42 x 36 units)
  ctx.save(); ctx.translate(x, y); ctx.scale(s, s);
  stroke(ctx, C.ink, 7.875, () => ctx.arc(18.495, 18, 13.359, -0.7336, 0.7336, true));
  circle(ctx, 36.356, 18, 4.5, C.accent);
  ctx.restore();
}

function backButton(ctx, alpha = 1, bg = C.raised) {
  ctx.save(); ctx.globalAlpha *= alpha;
  circle(ctx, 57, 104, 32, bg);
  stroke(ctx, C.ink, 2.6, () => { ctx.moveTo(68, 104); ctx.lineTo(47, 104); ctx.moveTo(56, 95); ctx.lineTo(47, 104); ctx.lineTo(56, 113); });
  ctx.restore();
}

/** The number in groups, like the card prints it. reveal: 0..1 digits typed in. */
function numberRail(ctx, x, y, w, h, { size = 46, reveal = 1, box = true, align = 'center' } = {}) {
  if (box) { ctx.fillStyle = C.surface; rr(ctx, x, y, w, h, h * 0.23); ctx.fill(); }
  ctx.save();
  ctx.font = `500 ${size}px Geist`;
  if ('letterSpacing' in ctx) ctx.letterSpacing = `${size * 0.12}px`;
  const gap = size * 0.55;
  const widths = NUMBER.map((g) => ctx.measureText(g).width);
  const total = widths.reduce((a, b) => a + b, 0) + gap * 2;
  let cx = align === 'center' ? x + (w - total) / 2 : x;
  const base = y + h / 2 + size * 0.36;
  let k = 0;
  NUMBER.forEach((g, gi) => {
    for (const ch of g) {
      const p = clamp(reveal * 11 - k);
      ctx.globalAlpha = p;
      ctx.fillStyle = C.ink;
      ctx.textBaseline = 'alphabetic';
      ctx.fillText(ch, cx, base + (1 - easeOut(p)) * 10);
      cx += ctx.measureText(ch).width;
      k++;
    }
    if (gi < 2) {
      ctx.globalAlpha = clamp(reveal * 11 - k + 1);
      ctx.fillStyle = '#4a4a4f';
      ctx.fillRect(cx + gap / 2 - size * 0.12, base - size * 0.62, 1.5, size * 0.42);
      cx += gap;
    }
  });
  ctx.restore();
}

const KEYS = [['1', '2', '3'], ['4', '5', '6'], ['7', '8', '9'], ['', '0', '⌫']];
function keypad(ctx, rows, cols, size, presses = [], L = 0) {
  rows.forEach((y, r) => cols.forEach((x, c) => {
    const k = KEYS[r][c];
    const press = presses.find((p) => p.key === k && L >= p.at && L < p.at + 0.35);
    if (press) {
      const q = (L - press.at) / 0.35;
      circle(ctx, x, y - size * 0.35, 44 * (0.7 + 0.3 * easeOut(q)), `rgba(255,255,255,${0.16 * (1 - q)})`);
    }
    if (k === '⌫') {
      stroke(ctx, C.ink, 2.4, () => {
        const yy = y - size * 0.36;
        ctx.moveTo(x - 6, yy - 12); ctx.lineTo(x + 16, yy - 12); ctx.lineTo(x + 16, yy + 12); ctx.lineTo(x - 6, yy + 12); ctx.lineTo(x - 17, yy); ctx.closePath();
        ctx.moveTo(x - 1, yy - 5); ctx.lineTo(x + 9, yy + 5); ctx.moveTo(x + 9, yy - 5); ctx.lineTo(x - 1, yy + 5);
      });
    } else if (k) text(ctx, k, x, y, { size, weight: 400, align: 'center' });
  }));
}

function pill(ctx, x, y, w, h, label, { bg = C.ink, fg = '#000', press = 0, alpha = 1 } = {}) {
  ctx.save(); ctx.globalAlpha *= alpha;
  const s = 1 - 0.035 * Math.sin(Math.PI * clamp(press));
  ctx.translate(x + w / 2, y + h / 2); ctx.scale(s, s); ctx.translate(-(x + w / 2), -(y + h / 2));
  ctx.fillStyle = bg; rr(ctx, x, y, w, h, h / 2); ctx.fill();
  text(ctx, label, x + w / 2, y + h / 2 + 7, { size: 20, weight: 600, color: fg, align: 'center' });
  ctx.restore();
}

// ---------- screens ----------

function home(ctx, L, a) {
  statusBar(ctx);
  logoMark(ctx, 32, 140, 2.5);
  text(ctx, 'chalaiden', 28, 333, { size: 78, weight: 700, spacing: -2.4 });
  const labels = ['Pay', 'Send', 'Cash out', 'Balance'];
  labels.forEach((lab, i) => {
    const x = 90 + i * 120, y = 467;
    circle(ctx, x, y, 46, i === 0 ? C.ink : C.raised);
    const fg = i === 0 ? '#000' : C.ink;
    if (i === 0) {
      stroke(ctx, fg, 3, () => {
        const s = 14, l = 7;
        for (const [sx, sy] of [[-1, -1], [1, -1], [-1, 1], [1, 1]]) {
          ctx.moveTo(x + sx * s, y + sy * (s - l)); ctx.lineTo(x + sx * s, y + sy * s); ctx.lineTo(x + sx * (s - l), y + sy * s);
        }
        ctx.moveTo(x - 9, y); ctx.lineTo(x + 9, y);
      });
    } else if (i === 1) {
      stroke(ctx, fg, 3, () => { ctx.moveTo(x - 9, y + 9); ctx.lineTo(x + 9, y - 9); ctx.moveTo(x - 4, y - 9); ctx.lineTo(x + 9, y - 9); ctx.lineTo(x + 9, y + 4); });
    } else if (i === 2) {
      stroke(ctx, fg, 2.6, () => { rr(ctx, x - 13, y - 14, 26, 15, 3); ctx.moveTo(x - 15, y + 6); ctx.lineTo(x - 4, y + 13); ctx.lineTo(x + 14, y + 9); });
      circle(ctx, x, y - 6.5, 3.2, fg);
    } else {
      stroke(ctx, fg, 2.6, () => { rr(ctx, x - 15, y - 12, 30, 24, 6); rr(ctx, x + 1, y - 5, 14, 10, 4); });
    }
    text(ctx, lab, x, 548, { size: 19, align: 'center' });
  });
  // "What to scan" card
  ctx.fillStyle = C.raised; rr(ctx, 30, 620, 196, 278, 18); ctx.fill();
  ctx.fillStyle = C.line; rr(ctx, 90, 636, 76, 7, 3.5); ctx.fill(); rr(ctx, 104, 650, 48, 5, 2.5); ctx.fill();
  ctx.fillStyle = '#fff'; rr(ctx, 48, 674, 160, 202, 14); ctx.fill();
  drawQr(ctx, 60, 686, 136);
  text(ctx, '01x xxxx xxxx', 128, 858, { size: 15, align: 'center', color: '#000', spacing: 0.6 });
  stroke(ctx, C.accent, 2, () => {
    const x0 = 62, y0 = 836, x1 = 194, y1 = 866, l = 8;
    ctx.moveTo(x0, y0 + l); ctx.lineTo(x0, y0); ctx.lineTo(x0 + l, y0);
    ctx.moveTo(x1 - l, y0); ctx.lineTo(x1, y0); ctx.lineTo(x1, y0 + l);
    ctx.moveTo(x0, y1 - l); ctx.lineTo(x0, y1); ctx.lineTo(x0 + l, y1);
    ctx.moveTo(x1 - l, y1); ctx.lineTo(x1, y1); ctx.lineTo(x1, y1 - l);
  });
  text(ctx, 'What to scan', 256, 694, { size: 25, weight: 500 });
  ['Aim at the number printed', "under the shop's bKash", 'QR, not the QR itself.', 'Chalaiden reads it and', 'dials *247# for you.']
    .forEach((s, i) => text(ctx, s, 256, 732 + i * 28, { size: 18, color: C.soft }));
  // tap on Pay near the end of the home beat
  if (a.tapPayAt != null && L > a.tapPayAt) {
    const q = clamp((L - a.tapPayAt) / 0.4);
    circle(ctx, 90, 467, 46 + 26 * easeOut(q), `rgba(61,126,255,${0.35 * (1 - q)})`);
  }
}

export let qrModulesCache = null;
export function qrModules(n = 29, seed = 7) {
  if (qrModulesCache && qrModulesCache.length === n) return qrModulesCache;
  let s = seed;
  const rnd = () => ((s = (s * 1103515245 + 12345) & 0x7fffffff) / 0x7fffffff);
  const g = Array.from({ length: n }, () => Array(n).fill(false));
  const res = Array.from({ length: n }, () => Array(n).fill(false));
  const finder = (ox, oy) => {
    for (let y = -1; y <= 7; y++) for (let x = -1; x <= 7; x++) {
      const gx = ox + x, gy = oy + y;
      if (gx < 0 || gy < 0 || gx >= n || gy >= n) continue;
      res[gy][gx] = true;
      const ring = Math.max(Math.abs(x - 3), Math.abs(y - 3));
      g[gy][gx] = x >= 0 && x <= 6 && y >= 0 && y <= 6 && ring !== 2;
    }
  };
  finder(0, 0); finder(n - 7, 0); finder(0, n - 7);
  for (let y = -2; y <= 2; y++) for (let x = -2; x <= 2; x++) {
    const gx = n - 7 + x, gy = n - 7 + y; res[gy][gx] = true;
    g[gy][gx] = Math.max(Math.abs(x), Math.abs(y)) !== 1;
  }
  for (let i = 8; i < n - 8; i++) { g[6][i] = g[i][6] = i % 2 === 0; res[6][i] = res[i][6] = true; }
  for (let y = 0; y < n; y++) for (let x = 0; x < n; x++) if (!res[y][x]) g[y][x] = rnd() < 0.47;
  return (qrModulesCache = g);
}
export function drawQr(ctx, x, y, size, color = '#000') {
  const m = qrModules();
  const c = size / m.length;
  ctx.fillStyle = color;
  m.forEach((row, j) => row.forEach((on, i) => { if (on) ctx.fillRect(x + i * c, y + j * c, c + 0.4, c + 0.4); }));
}

function scan(ctx, L, a) {
  // The camera feed is a separate layer under this canvas; here only the UI chrome.
  const top = ctx.createLinearGradient(0, 0, 0, 260);
  top.addColorStop(0, 'rgba(0,0,0,0.6)'); top.addColorStop(1, 'rgba(0,0,0,0)');
  ctx.fillStyle = top; ctx.fillRect(0, 0, SCREEN_W, 260);
  const bot = ctx.createLinearGradient(0, 820, 0, SCREEN_H);
  bot.addColorStop(0, 'rgba(0,0,0,0)'); bot.addColorStop(1, 'rgba(0,0,0,0.75)');
  ctx.fillStyle = bot; ctx.fillRect(0, 820, SCREEN_W, SCREEN_H - 820);
  statusBar(ctx, { dark: true });
  backButton(ctx, 1, 'rgba(40,40,44,0.7)');
  circle(ctx, 483, 104, 32, 'rgba(40,40,44,0.7)');
  stroke(ctx, C.ink, 2.4, () => { ctx.moveTo(477, 92); ctx.lineTo(489, 92); ctx.lineTo(486, 104); ctx.lineTo(486, 117); ctx.lineTo(480, 117); ctx.lineTo(480, 104); ctx.closePath(); });
  text(ctx, "Point at the number on the shop's", 270, 182, { size: 20, weight: 500, align: 'center' });
  text(ctx, 'bKash card', 270, 210, { size: 20, weight: 500, align: 'center' });

  // detection box (screen-space rect from the 3D projection)
  const lock = a.lock ?? 0;
  const box = a.box;
  if (box) {
    const col = lock > 0.5 ? C.accent : 'rgba(255,255,255,0.9)';
    const { x0, y0, x1, y1 } = box;
    const l = Math.min(22, (x1 - x0) / 4);
    ctx.save();
    ctx.shadowColor = 'rgba(0,0,0,0.4)'; ctx.shadowBlur = 8;
    stroke(ctx, col, 4, () => {
      ctx.moveTo(x0, y0 + l); ctx.lineTo(x0, y0); ctx.lineTo(x0 + l, y0);
      ctx.moveTo(x1 - l, y0); ctx.lineTo(x1, y0); ctx.lineTo(x1, y0 + l);
      ctx.moveTo(x0, y1 - l); ctx.lineTo(x0, y1); ctx.lineTo(x0 + l, y1);
      ctx.moveTo(x1 - l, y1); ctx.lineTo(x1, y1); ctx.lineTo(x1, y1 - l);
    });
    ctx.restore();
  }
  // status line
  const q = clamp((lock - 0.5) * 2);
  if (q < 1) text(ctx, 'Looking for an 11-digit number…', 270, 905, { size: 19, align: 'center', color: 'rgba(255,255,255,0.8)', alpha: 1 - q });
  if (q > 0) {
    ctx.save(); ctx.globalAlpha = q;
    ctx.fillStyle = 'rgba(20,20,22,0.82)'; rr(ctx, 95, 866, 350, 62, 31); ctx.fill();
    numberRail(ctx, 95, 866, 350, 62, { size: 27, box: false });
    ctx.restore();
  }
  // controls
  circle(ctx, 130, 1040, 30, 'rgba(40,40,44,0.75)');
  stroke(ctx, C.ink, 2.2, () => { rr(ctx, 118, 1029, 24, 22, 4); ctx.moveTo(121, 1047); ctx.lineTo(129, 1039); ctx.lineTo(134, 1044); ctx.lineTo(137, 1041); ctx.lineTo(141, 1046); });
  circle(ctx, 410, 1040, 30, 'rgba(40,40,44,0.75)');
  for (let r = 0; r < 3; r++) for (let c = 0; c < 3; c++) circle(ctx, 400 + c * 10, 1030 + r * 10, 2.4, C.ink);
  const shutterPress = a.shutterAt != null ? Math.sin(Math.PI * span(L, a.shutterAt, a.shutterAt + 0.3)) : 0;
  ctx.save(); ctx.lineWidth = 5; ctx.strokeStyle = lock > 0.5 ? C.accent : C.ink;
  ctx.beginPath(); ctx.arc(270, 1040, 44, 0, Math.PI * 2); ctx.stroke(); ctx.restore();
  circle(ctx, 270, 1040, 35 * (1 - 0.12 * shutterPress), lock > 0.5 ? C.accent : C.ink);
  if (lock > 0.5) stroke(ctx, C.ink, 3.4, () => { ctx.moveTo(257, 1040); ctx.lineTo(266, 1049); ctx.lineTo(284, 1031); });
  // capture flash
  if (a.shutterAt != null && L > a.shutterAt) {
    const f = 1 - span(L, a.shutterAt + 0.05, a.shutterAt + 0.45);
    ctx.fillStyle = `rgba(255,255,255,${0.55 * f * (L > a.shutterAt + 0.05 ? 1 : 0)})`; ctx.fillRect(0, 0, SCREEN_W, SCREEN_H);
  }
}

function numberScreen(ctx, L, a) {
  statusBar(ctx);
  backButton(ctx);
  text(ctx, "Shop's bKash number", 110, 113, { size: 24, weight: 500 });
  text(ctx, 'From the card', 30, 182, { size: 17, color: C.soft });
  // straightened crop
  const p = easeOut(span(L, 0.1, 0.7));
  ctx.save();
  ctx.globalAlpha = p;
  rr(ctx, 30, 197, 480, 82, 22); ctx.clip();
  if (a.crop) ctx.drawImage(a.crop, 30, 197 + (1 - p) * 8, 480, 82);
  ctx.restore();
  text(ctx, 'Chalaiden read', 30, 322, { size: 17, color: C.soft });
  numberRail(ctx, 30, 340, 480, 112, { reveal: span(L, 0.5, 1.6) });
  text(ctx, 'Check every digit against the photo.', 30, 488, { size: 17, color: C.soft, alpha: easeOut(span(L, 1.4, 1.9)) });
  keypad(ctx, [672, 773, 874, 975], [110, 270, 430], 38);
  pill(ctx, 30, 1052, 480, 82, 'Continue', { press: a.continueAt != null ? span(L, a.continueAt, a.continueAt + 0.3) : 0 });
}

function amountScreen(ctx, L, a) {
  statusBar(ctx);
  backButton(ctx);
  text(ctx, 'Pay shop', 110, 113, { size: 24, weight: 500 });
  text(ctx, "Shop's number", 30, 182, { size: 17, color: C.soft });
  numberRail(ctx, 30, 200, 480, 112);
  const typed = a.keys.filter((k) => L >= k.at).map((k) => k.key).join('');
  const shown = typed || '0';
  ctx.save();
  ctx.font = '400 104px Geist';
  if ('letterSpacing' in ctx) ctx.letterSpacing = '-3px';
  const w = ctx.measureText(shown).width;
  ctx.restore();
  const total = w + 62;
  const x0 = 270 - total / 2;
  text(ctx, 'Tk', x0, 476, { size: 38, color: C.soft });
  // last digit pops in
  const last = a.keys.filter((k) => L >= k.at).pop();
  const pop = last ? backOut(span(L, last.at, last.at + 0.28)) : 1;
  ctx.save();
  ctx.translate(x0 + 62, 476);
  ctx.font = '400 104px Geist'; ctx.fillStyle = typed ? C.ink : '#555';
  if ('letterSpacing' in ctx) ctx.letterSpacing = '-3px';
  const head = shown.slice(0, -1);
  ctx.fillText(head, 0, 0);
  const hw = ctx.measureText(head).width;
  ctx.translate(hw, 0); ctx.globalAlpha = clamp(pop * 1.3); ctx.translate(0, (1 - pop) * 18);
  ctx.fillText(shown.slice(-1), 0, 0);
  ctx.restore();
  text(ctx, 'Add reference', 270, 559, { size: 21, weight: 600, color: C.accent, align: 'center' });
  keypad(ctx, [672, 773, 874, 975], [110, 270, 430], 38, a.keys, L);
  const ready = typed.length > 0;
  pill(ctx, 30, 1052, 480, 82, ready ? `Pay Tk ${typed}` : 'Pay', {
    bg: ready ? C.ink : C.raised, fg: ready ? '#000' : C.soft,
    press: a.payAt != null ? span(L, a.payAt, a.payAt + 0.3) : 0,
  });
}

function amountHeader(ctx, label) {
  text(ctx, label, 37, 117, { size: 23, color: C.soft });
  text(ctx, `Tk ${AMOUNT}`, 33, 208, { size: 80, weight: 500, spacing: -2.5 });
  numberRail(ctx, 37, 252, 300, 50, { size: 29, box: false, align: 'left' });
}

function pinScreen(ctx, L, a) {
  statusBar(ctx);
  amountHeader(ctx, 'Pay');
  const n = a.keys.filter((k) => L >= k.at).length;
  for (let i = 0; i < 5; i++) {
    const x = 181 + i * 44.5;
    const filled = i < n;
    if (filled) {
      const k = a.keys[i];
      circle(ctx, x, 511, 10 * backOut(span(L, k.at, k.at + 0.22)), C.ink);
    } else {
      ctx.save(); ctx.strokeStyle = '#4a4a4f'; ctx.lineWidth = 2; ctx.beginPath(); ctx.arc(x, 511, 9.5, 0, Math.PI * 2); ctx.stroke(); ctx.restore();
    }
  }
  keypad(ctx, [600, 700, 801, 902], [113, 270, 426], 38, a.keys, L);
  // hold to pay
  const ready = n >= 5;
  const hold = a.holdAt != null ? span(L, a.holdAt, a.holdAt + a.holdFor) : 0;
  const press = a.holdAt != null && L >= a.holdAt ? 1 : 0;
  ctx.save();
  const s = 1 - 0.02 * press * (1 - span(L, a.holdAt + a.holdFor, a.holdAt + a.holdFor + 0.2));
  ctx.translate(270, 1012); ctx.scale(s, s); ctx.translate(-270, -1012);
  ctx.fillStyle = ready ? '#3a3a3e' : C.raised; rr(ctx, 36, 968, 468, 88, 44); ctx.fill();
  if (hold > 0) {
    ctx.save(); rr(ctx, 36, 968, 468, 88, 44); ctx.clip();
    ctx.fillStyle = C.accent; ctx.fillRect(36, 968, 468 * hold, 88);
    ctx.restore();
  }
  text(ctx, hold >= 1 ? 'Paying…' : 'Hold to pay', 270, 1019, { size: 21, weight: 600, align: 'center', color: ready ? C.ink : C.soft });
  ctx.restore();
  // finger press ring
  if (press && hold < 1) circle(ctx, 300, 1012, 52 + 6 * Math.sin(L * 9), 'rgba(255,255,255,0.10)');
  text(ctx, 'Cancel', 270, 1105, { size: 21, weight: 600, align: 'center' });
}

export const RUN_STEPS = ['Open bKash menu', 'Choose Payment', "Enter shop's number", 'Enter amount', 'Enter reference', "Check bKash's summary", 'Send PIN'];
function runScreen(ctx, L, a) {
  statusBar(ctx);
  amountHeader(ctx, 'Paying');
  const done = a.steps.filter((t) => L >= t).length;
  RUN_STEPS.forEach((label, i) => {
    const y = 400 + i * 62;
    const isDone = i < done;
    const active = i === done;
    const appear = easeOut(span(L, i * 0.05, i * 0.05 + 0.4));
    ctx.save(); ctx.globalAlpha = appear;
    if (isDone) {
      const q = backOut(span(L, a.steps[i], a.steps[i] + 0.25));
      circle(ctx, 52, y, 15 * q, C.accent);
      stroke(ctx, C.ink, 2.6, () => { ctx.moveTo(45, y); ctx.lineTo(50, y + 5); ctx.lineTo(59, y - 5); });
    } else if (active) {
      ctx.save(); ctx.strokeStyle = C.accent; ctx.lineWidth = 3; ctx.lineCap = 'round';
      ctx.beginPath(); ctx.arc(52, y, 13, L * 7, L * 7 + 4.2); ctx.stroke(); ctx.restore();
    } else {
      ctx.save(); ctx.strokeStyle = '#3a3a3e'; ctx.lineWidth = 2; ctx.beginPath(); ctx.arc(52, y, 13, 0, Math.PI * 2); ctx.stroke(); ctx.restore();
    }
    text(ctx, label, 86, y + 7, { size: 21, color: isDone || active ? C.ink : C.soft });
    ctx.restore();
  });
  const confirming = L >= a.confirmAt;
  text(ctx, confirming ? 'Confirming with bKash…' : "Don't close Chalaiden.", 270, 1060, { size: 19, align: 'center', color: C.soft });
}

function resultScreen(ctx, L) {
  statusBar(ctx);
  const q = backOut(span(L, 0.05, 0.5));
  ctx.save(); ctx.translate(72, 168); ctx.scale(q, q);
  circle(ctx, 0, 0, 42, C.accent);
  ctx.restore();
  const ck = span(L, 0.3, 0.6);
  if (ck > 0) stroke(ctx, C.ink, 5, () => {
    const pts = [[-17, 1], [-5, 13], [18, -11]];
    ctx.moveTo(72 + pts[0][0], 168 + pts[0][1]);
    const l1 = Math.min(1, ck * 2), l2 = clamp(ck * 2 - 1);
    ctx.lineTo(72 + pts[0][0] + (pts[1][0] - pts[0][0]) * l1, 168 + pts[0][1] + (pts[1][1] - pts[0][1]) * l1);
    if (l2 > 0) ctx.lineTo(72 + pts[1][0] + (pts[2][0] - pts[1][0]) * l2, 168 + pts[1][1] + (pts[2][1] - pts[1][1]) * l2);
  });
  // ripple
  const r = span(L, 0.2, 1.0);
  if (r > 0 && r < 1) { ctx.save(); ctx.strokeStyle = `rgba(61,126,255,${0.5 * (1 - r)})`; ctx.lineWidth = 3; ctx.beginPath(); ctx.arc(72, 168, 42 + 50 * easeOut(r), 0, Math.PI * 2); ctx.stroke(); ctx.restore(); }
  const t1 = easeOut(span(L, 0.35, 0.8));
  ctx.save(); ctx.globalAlpha = t1; ctx.translate(0, (1 - t1) * 14);
  text(ctx, 'Paid', 30, 268, { size: 44, weight: 600, spacing: -1 });
  text(ctx, `Tk ${AMOUNT}`, 27, 352, { size: 80, weight: 500, spacing: -2.5 });
  numberRail(ctx, 31, 382, 300, 50, { size: 29, box: false, align: 'left' });
  ctx.restore();
  const rows = [['TrxID', 'BKH7Q2M9XE'], ['Time', '9:12 PM'], ['Date', '8 Nov 2026']];
  rows.forEach(([k, v], i) => {
    const p = easeOut(span(L, 0.6 + i * 0.1, 1.0 + i * 0.1));
    ctx.save(); ctx.globalAlpha = p;
    const y = 490 + i * 46;
    text(ctx, k, 30, y, { size: 19, color: C.soft });
    text(ctx, v, 510, y, { size: 19, weight: 600, align: 'right' });
    ctx.restore();
  });
  pill(ctx, 30, 1012, 480, 82, 'Done', { alpha: easeOut(span(L, 0.9, 1.3)) });
  text(ctx, 'Share receipt', 270, 1135, { size: 20, weight: 600, align: 'center', alpha: easeOut(span(L, 1.0, 1.4)) });
}

const DRAW = { home, scan, number: numberScreen, amount: amountScreen, pin: pinScreen, run: runScreen, result: resultScreen };

export class PhoneUI {
  constructor() {
    this.canvas = document.createElement('canvas');
    this.canvas.width = SCREEN_W * S;
    this.canvas.height = SCREEN_H * S;
    this.ctx = this.canvas.getContext('2d');
  }
  /** state: { screen, L, args, prev?, prevL?, prevArgs?, mix (0..1 slide-in progress) } */
  draw(st) {
    const ctx = this.ctx;
    ctx.setTransform(S, 0, 0, S, 0, 0);
    ctx.clearRect(0, 0, SCREEN_W, SCREEN_H);
    const transparent = st.screen === 'scan' || (st.prev === 'scan' && st.mix < 1);
    if (!transparent) { ctx.fillStyle = C.bg; ctx.fillRect(0, 0, SCREEN_W, SCREEN_H); }
    const m = st.prev ? ease(st.mix) : 1;
    if (st.prev && m < 1) {
      ctx.save();
      ctx.translate(-SCREEN_W * 0.28 * m, 0);
      DRAW[st.prev](ctx, st.prevL, st.prevArgs ?? {});
      ctx.restore();
      ctx.fillStyle = `rgba(0,0,0,${0.6 * m})`; ctx.fillRect(0, 0, SCREEN_W, SCREEN_H);
    }
    ctx.save();
    if (st.prev && m < 1) {
      ctx.translate(SCREEN_W * (1 - m), 0);
      ctx.shadowColor = 'rgba(0,0,0,0.6)'; ctx.shadowBlur = 40;
      ctx.fillStyle = st.screen === 'scan' ? 'rgba(0,0,0,0)' : C.bg; ctx.fillRect(0, 0, SCREEN_W, SCREEN_H);
      ctx.shadowBlur = 0;
    }
    DRAW[st.screen](ctx, st.L, st.args ?? {});
    ctx.restore();
    // punch-hole camera always on top
    circle(ctx, 270, 35, 9.5, '#050505');
  }
}

/** An Android-style USSD dialog card, for the "under the hood" beat. */
export function drawUssdCard(prompt, reply, sent = false) {
  const W = 620, H = 470;
  const c = document.createElement('canvas');
  c.width = W * 2; c.height = H * 2;
  const ctx = c.getContext('2d');
  ctx.scale(2, 2);
  ctx.fillStyle = '#2a2b30'; rr(ctx, 0, 0, W, H, 34); ctx.fill();
  ctx.strokeStyle = 'rgba(255,255,255,0.08)'; ctx.lineWidth = 2; rr(ctx, 1, 1, W - 2, H - 2, 33); ctx.stroke();
  const lines = prompt.split('\n');
  const size = lines.length > 5 ? 27 : lines.length > 2 ? 31 : 38;
  const lh = size * 1.3;
  lines.forEach((l, i) => text(ctx, l, 40, 40 + size + i * lh, { size, weight: 500, color: '#f1f1f4' }));
  const fy = H - 168;
  ctx.fillStyle = '#3a3b41'; rr(ctx, 40, fy, W - 80, 70, 16); ctx.fill();
  ctx.strokeStyle = sent ? 'rgba(125,163,255,0.4)' : '#7da3ff'; ctx.lineWidth = 3; rr(ctx, 40, fy, W - 80, 70, 16); ctx.stroke();
  text(ctx, reply, 64, fy + 47, { size: 34, weight: 700, color: '#fff', family: 'GeistMono' });
  text(ctx, 'Cancel', W - 190, H - 42, { size: 27, weight: 600, color: '#9db8ff', align: 'right' });
  text(ctx, 'Send', W - 44, H - 42, { size: 27, weight: 700, color: sent ? '#ffffff' : '#9db8ff', align: 'right' });
  if (sent) { ctx.fillStyle = 'rgba(61,126,255,0.18)'; rr(ctx, W - 130, H - 82, 106, 58, 29); ctx.fill(); }
  return c;
}
