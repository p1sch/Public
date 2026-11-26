/* Holz-CAD – 2D-Zeichnen für Aufmaß und Details im Holzbau.
   Reines HTML/JS ohne Bibliotheken. Alle Maße intern in mm, y-Achse nach oben. */
'use strict';
(function () {

// ---------- Hilfen ----------
const $ = (s, r = document) => r.querySelector(s);
const $$ = (s, r = document) => [...r.querySelectorAll(s)];
const LS = {
  get(k, d) { try { const v = localStorage.getItem(k); return v == null ? d : JSON.parse(v); } catch (e) { return d; } },
  set(k, v) { try { localStorage.setItem(k, JSON.stringify(v)); return true; } catch (e) { return false; } },
  del(k) { try { localStorage.removeItem(k); } catch (e) { /* egal */ } },
};
const uid = () => Date.now().toString(36) + Math.random().toString(36).slice(2, 7);
const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const clamp = (v, a, b) => Math.max(a, Math.min(b, v));
const DEG = Math.PI / 180;

const LAYERS = {
  holz:    { name: 'Holz / Neu',  color: '#9a4f17', w: 2.4, dxf: 'HOLZ',       aci: 30 },
  bestand: { name: 'Bestand',     color: '#2b2b2b', w: 1.6, dxf: 'BESTAND',    aci: 7 },
  mass:    { name: 'Maße',        color: '#1565c0', w: 1.0, dxf: 'MASSE',      aci: 5 },
  hilf:    { name: 'Hilfslinie',  color: '#9e9e9e', w: 1.0, dxf: 'HILFSLINIE', aci: 8, dash: [6, 4] },
  notiz:   { name: 'Notiz',       color: '#c62828', w: 1.5, dxf: 'NOTIZ',      aci: 1 },
};
const SELCOL = '#1e88e5', DRAFTCOL = '#1e88e5', SNAPCOL = '#e65100';
// Gängige Querschnitte b/h in cm
const PRESETS = ['4/6', '6/8', '6/12', '6/14', '6/16', '6/18', '6/20', '6/24', '8/10', '8/12', '8/14', '8/16', '8/18', '8/20', '8/22', '8/24',
  '10/10', '10/12', '10/16', '10/20', '10/22', '12/12', '12/16', '12/20', '12/24', '14/14', '14/20', '14/28', '16/16', '16/20', '16/32', '20/20', '2,4/4,8', '3/5'];
const SNAPNAME = { end: 'Endpunkt', inter: 'Schnittpunkt', mid: 'Mitte', center: 'Zentrum', quad: 'Quadrant', near: 'auf Linie', grid: 'Raster' };
const UNIT_F = { mm: 1, cm: 10, m: 1000 };

// ---------- Einstellungen ----------
const S = Object.assign({ grid: true, snap: true, ortho: 90, labels: true, unit: 'mm', layer: 'holz', lastAng: 0,
  beam: { b: 80, h: 160, anchor: 'c' }, textH: 0 }, LS.get('holzcad.settings', {}));
const saveSettings = () => LS.set('holzcad.settings', S);

// ---------- Zahlen ----------
function fmtNum(v, dec = 2) {
  if (!isFinite(v)) return '';
  let s = (Math.round(v * 10 ** dec) / 10 ** dec).toFixed(dec);
  if (s.includes('.')) s = s.replace(/0+$/, '').replace(/\.$/, '');
  if (s === '-0') s = '0';
  return s.replace('.', ',');
}
function fmtLen(mm, unit = S.unit) {
  if (unit === 'cm') return fmtNum(mm / 10, 1);
  if (unit === 'm') return fmtNum(mm / 1000, 3);
  return fmtNum(mm, 1);
}
function evalExpr(s) {
  s = String(s ?? '').trim().replace(/,/g, '.').replace(/[×x]/gi, '*').replace(/[÷:]/g, '/');
  if (!s || !/^[0-9+\-*/(). ]+$/.test(s)) return NaN;
  try { const v = Function('"use strict";return (' + s + ')')(); return typeof v === 'number' && isFinite(v) ? v : NaN; } catch (e) { return NaN; }
}
const parseLen = s => evalExpr(s) * UNIT_F[S.unit];
function niceRound(v) {
  if (!(v > 0)) return 1;
  const e = 10 ** Math.floor(Math.log10(v)), f = v / e;
  return (f < 1.5 ? 1 : f < 2.2 ? 2 : f < 3.5 ? 2.5 : f < 7.5 ? 5 : 10) * e;
}
const normAng = a => { a %= 360; if (a < 0) a += 360; return Math.abs(a - 360) < 1e-9 ? 0 : a; };

// ---------- Geometrie ----------
const add = (a, b) => ({ x: a.x + b.x, y: a.y + b.y });
const sub = (a, b) => ({ x: a.x - b.x, y: a.y - b.y });
const mul = (a, k) => ({ x: a.x * k, y: a.y * k });
const dist = (a, b) => Math.hypot(a.x - b.x, a.y - b.y);
const mid = (a, b) => ({ x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 });
const cp = p => ({ x: p.x, y: p.y });
const angOf = (a, b) => normAng(Math.atan2(b.y - a.y, b.x - a.x) / DEG);
const polar = (p, len, angDeg) => ({ x: p.x + Math.cos(angDeg * DEG) * len, y: p.y + Math.sin(angDeg * DEG) * len });
function segDist(p, a, b) {
  const dx = b.x - a.x, dy = b.y - a.y, L = dx * dx + dy * dy;
  let t = L ? ((p.x - a.x) * dx + (p.y - a.y) * dy) / L : 0;
  t = clamp(t, 0, 1);
  const q = { x: a.x + t * dx, y: a.y + t * dy };
  return { d: dist(p, q), q };
}
function segInter(a, b, c, d) {
  const r = sub(b, a), s = sub(d, c), den = r.x * s.y - r.y * s.x;
  if (Math.abs(den) < 1e-12) return null;
  const t = ((c.x - a.x) * s.y - (c.y - a.y) * s.x) / den, u = ((c.x - a.x) * r.y - (c.y - a.y) * r.x) / den;
  if (t < -1e-9 || t > 1 + 1e-9 || u < -1e-9 || u > 1 + 1e-9) return null;
  return { x: a.x + t * r.x, y: a.y + t * r.y };
}
function polyArea(pts) { let s = 0; for (let i = 0; i < pts.length; i++) { const a = pts[i], b = pts[(i + 1) % pts.length]; s += a.x * b.y - b.x * a.y; } return s / 2; }
function polyCentroid(pts) {
  const A = polyArea(pts);
  if (Math.abs(A) < 1e-9) return pts.reduce((m, p) => ({ x: m.x + p.x / pts.length, y: m.y + p.y / pts.length }), { x: 0, y: 0 });
  let cx = 0, cy = 0;
  for (let i = 0; i < pts.length; i++) { const a = pts[i], b = pts[(i + 1) % pts.length], f = a.x * b.y - b.x * a.y; cx += (a.x + b.x) * f; cy += (a.y + b.y) * f; }
  return { x: cx / (6 * A), y: cy / (6 * A) };
}
const rectCorners = e => [{ x: e.x, y: e.y }, { x: e.x + e.w, y: e.y }, { x: e.x + e.w, y: e.y + e.h }, { x: e.x, y: e.y + e.h }];
function rectFrom(p, q) { return { x: Math.min(p.x, q.x), y: Math.min(p.y, q.y), w: Math.abs(q.x - p.x), h: Math.abs(q.y - p.y) }; }
function dimFrame(e) {
  const L = dist(e.a, e.b); if (L < 1e-9) return null;
  const u = { x: (e.b.x - e.a.x) / L, y: (e.b.y - e.a.y) / L }, n = { x: -u.y, y: u.x };
  return { L, u, n, A: add(e.a, mul(n, e.off)), B: add(e.b, mul(n, e.off)) };
}

function segmentsOf(e) {
  switch (e.t) {
    case 'line': return [[e.a, e.b]];
    case 'rect': { const c = rectCorners(e); return [[c[0], c[1]], [c[1], c[2]], [c[2], c[3]], [c[3], c[0]]]; }
    case 'poly': { const s = []; for (let i = 0; i < e.pts.length - 1; i++) s.push([e.pts[i], e.pts[i + 1]]); if (e.closed && e.pts.length > 2) s.push([e.pts[e.pts.length - 1], e.pts[0]]); return s; }
    default: return [];
  }
}
function snapPts(e) {
  const o = [];
  switch (e.t) {
    case 'line': o.push({ p: e.a, k: 'end' }, { p: e.b, k: 'end' }, { p: mid(e.a, e.b), k: 'mid' }); break;
    case 'rect': { const c = rectCorners(e); c.forEach((p, i) => { o.push({ p, k: 'end' }, { p: mid(p, c[(i + 1) % 4]), k: 'mid' }); }); o.push({ p: { x: e.x + e.w / 2, y: e.y + e.h / 2 }, k: 'center' }); break; }
    case 'circle': o.push({ p: e.c, k: 'center' }); [0, 90, 180, 270].forEach(a => o.push({ p: polar(e.c, e.r, a), k: 'quad' })); break;
    case 'poly': e.pts.forEach(p => o.push({ p, k: 'end' })); segmentsOf(e).forEach(s => o.push({ p: mid(s[0], s[1]), k: 'mid' })); break;
    case 'dim': o.push({ p: e.a, k: 'end' }, { p: e.b, k: 'end' }); break;
    case 'text': o.push({ p: e.p, k: 'end' }); break;
  }
  return o;
}
// Griffe zum Ziehen (link = verbundene Punkte anderer Elemente ziehen mit)
function grips(e) {
  switch (e.t) {
    case 'line': return [{ i: 0, p: e.a, link: true }, { i: 1, p: e.b, link: true }];
    case 'rect': return rectCorners(e).map((p, i) => ({ i, p }));
    case 'circle': return [{ i: 0, p: e.c }, { i: 1, p: polar(e.c, e.r, 0) }];
    case 'poly': return e.pts.map((p, i) => ({ i, p, link: true }));
    case 'dim': { const f = dimFrame(e); const g = [{ i: 0, p: e.a, link: true }, { i: 1, p: e.b, link: true }]; if (f) g.push({ i: 2, p: mid(f.A, f.B) }); return g; }
    case 'text': return [{ i: 0, p: e.p }];
  }
  return [];
}
function setGrip(e, i, p) {
  switch (e.t) {
    case 'line': if (i === 0) e.a = cp(p); else e.b = cp(p); break;
    case 'rect': { const c = rectCorners(e)[(i + 2) % 4]; Object.assign(e, rectFrom(c, p)); break; }
    case 'circle': if (i === 0) e.c = cp(p); else e.r = Math.max(0.1, dist(e.c, p)); break;
    case 'poly': e.pts[i] = cp(p); break;
    case 'dim': if (i === 0) e.a = cp(p); else if (i === 1) e.b = cp(p); else { const f = dimFrame(e); if (f) e.off = (p.x - e.a.x) * f.n.x + (p.y - e.a.y) * f.n.y; } break;
    case 'text': e.p = cp(p); break;
  }
}
function linkedGrips(p, excludeIds) {
  const out = [];
  for (const e of doc.entities) {
    if (excludeIds.has(e.id)) continue;
    for (const g of grips(e)) if (g.link && dist(g.p, p) < 0.05) out.push({ e, i: g.i });
  }
  return out;
}
function transformEnt(e, f, mirror) {
  switch (e.t) {
    case 'line': e.a = f(e.a); e.b = f(e.b); break;
    case 'poly': e.pts = e.pts.map(f); break;
    case 'dim': e.a = f(e.a); e.b = f(e.b); if (mirror) e.off = -e.off; break;
    case 'text': e.p = f(e.p); break;
    case 'circle': e.c = f(e.c); break;
    case 'rect': { const c = rectCorners(e).map(f); Object.assign(e, rectFrom({ x: Math.min(...c.map(q => q.x)), y: Math.min(...c.map(q => q.y)) }, { x: Math.max(...c.map(q => q.x)), y: Math.max(...c.map(q => q.y)) })); break; }
  }
}
function textW(e) { return e.s.length * e.h * 0.58; }
function bboxOf(e) {
  let pts;
  switch (e.t) {
    case 'line': pts = [e.a, e.b]; break;
    case 'rect': pts = rectCorners(e); break;
    case 'circle': pts = [{ x: e.c.x - e.r, y: e.c.y - e.r }, { x: e.c.x + e.r, y: e.c.y + e.r }]; break;
    case 'poly': pts = e.pts; break;
    case 'dim': { const f = dimFrame(e); pts = f ? [e.a, e.b, f.A, f.B] : [e.a]; break; }
    case 'text': pts = [e.p, { x: e.p.x + textW(e), y: e.p.y + e.h }]; break;
    default: pts = [];
  }
  return pts;
}
function bboxAll(list) {
  let x0 = Infinity, y0 = Infinity, x1 = -Infinity, y1 = -Infinity;
  for (const e of list) for (const p of bboxOf(e)) { x0 = Math.min(x0, p.x); y0 = Math.min(y0, p.y); x1 = Math.max(x1, p.x); y1 = Math.max(y1, p.y); }
  return isFinite(x0) ? { x0, y0, x1, y1 } : null;
}
function hitDist(e, p) {
  switch (e.t) {
    case 'circle': return Math.abs(dist(e.c, p) - e.r);
    case 'dim': { const f = dimFrame(e); return f ? segDist(p, f.A, f.B).d : Infinity; }
    case 'text': { const w = textW(e); if (p.x >= e.p.x && p.x <= e.p.x + w && p.y >= e.p.y - e.h * 0.25 && p.y <= e.p.y + e.h) return 0; return dist(p, e.p); }
    case 'poly': if (e.closed && e.pts.length > 2 && pointInPoly(p, e.pts)) { const d = Math.min(...segmentsOf(e).map(s => segDist(p, s[0], s[1]).d)); return Math.min(d, 6 / view.s); }
    // fällt durch
    default: { let d = Infinity; for (const s of segmentsOf(e)) d = Math.min(d, segDist(p, s[0], s[1]).d); return d; }
  }
}
function pointInPoly(p, pts) {
  let ins = false;
  for (let i = 0, j = pts.length - 1; i < pts.length; j = i++) {
    const a = pts[i], b = pts[j];
    if ((a.y > p.y) !== (b.y > p.y) && p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x) ins = !ins;
  }
  return ins;
}

// ---------- Datenhaltung ----------
let index = LS.get('holzcad.index', []);
let doc = null;
const saveIndex = () => LS.set('holzcad.index', index);
function saveDoc() {
  if (!doc) return;
  doc.updated = Date.now();
  if (!$('#editor').hidden && stageW > 0) doc.view = { ...view, w: stageW, h: stageH };
  const ok = LS.set('holzcad.doc.' + doc.id, doc);
  const meta = { id: doc.id, name: doc.name, site: doc.site, updated: doc.updated, n: doc.entities.length };
  const i = index.findIndex(m => m.id === doc.id);
  if (i >= 0) index[i] = meta; else index.unshift(meta);
  saveIndex();
  if (!ok) toast('Achtung: Speicher voll – bitte Zeichnungen exportieren.');
}
function newDocObj(name, site, kind) {
  return { format: 'holzcad', version: 1, id: uid(), name, site, kind, created: Date.now(), updated: Date.now(), entities: [], lastPt: { x: 0, y: 0 } };
}

// ---------- Zustand Editor ----------
const cv = $('#cv'), ctx = cv.getContext('2d');
let view = { ox: 100, oy: 400, s: 0.1 }; // Bildschirm = Welt*s + ox ; y gespiegelt
let stageW = 300, stageH = 300, dpr = 1;
let tool = 'select', draft = null, sel = new Set();
let cur = { sx: 0, sy: 0, p: { x: 0, y: 0 }, kind: null, valid: false };
let undoStack = [], redoStack = [];
let isTouch = matchMedia('(pointer:coarse)').matches;
let gridStep = 100;

const W2S = (p, v = view) => ({ x: p.x * v.s + v.ox, y: -p.y * v.s + v.oy });
const S2W = (x, y, v = view) => ({ x: (x - v.ox) / v.s, y: -(y - v.oy) / v.s });
function calcGrid(s) {
  for (const c of [1, 2, 5, 10, 20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000, 50000]) if (c * s >= 11) return c;
  return 100000;
}

// ---------- Undo ----------
const snapshot = () => JSON.stringify(doc.entities);
function pushUndo(snap) { undoStack.push(snap); if (undoStack.length > 200) undoStack.shift(); redoStack = []; updUndoBtns(); }
function change(fn) { const s = snapshot(); fn(); pushUndo(s); saveDoc(); refreshPanel(); redraw(); }
function restore(json) {
  doc.entities = JSON.parse(json);
  const ids = new Set(doc.entities.map(e => e.id));
  sel = new Set([...sel].filter(id => ids.has(id)));
  saveDoc(); refreshPanel(); redraw(); updUndoBtns();
}
function undo() {
  if (draft && draft.t === 'poly' && draft.pts.length > 1) { draft.pts.pop(); redraw(); return; }
  if (!undoStack.length) { if (draft) { draft = null; refreshPanel(); redraw(); } return; }
  let chainBack = null;
  if (draft && draft.t === 'line' && draft.pts.length) {
    const last = doc.entities[doc.entities.length - 1];
    if (last && last.t === 'line' && dist(last.b, draft.pts[0]) < 1e-6) chainBack = cp(last.a);
  }
  redoStack.push(snapshot());
  const snap = undoStack.pop();
  if (chainBack) { draft.pts = [chainBack]; doc.lastPt = chainBack; } else draft = null;
  restore(snap);
}
function redo() { if (!redoStack.length) return; undoStack.push(snapshot()); draft = null; restore(redoStack.pop()); }
function updUndoBtns() { $('#btnUndo').disabled = !undoStack.length && !draft; $('#btnRedo').disabled = !redoStack.length; }

// ---------- Fang ----------
function snapAt(sx, sy, excl) {
  const raw = S2W(sx, sy), tol = (isTouch ? 22 : 12) / view.s;
  gridStep = calcGrid(view.s);
  if (S.snap) {
    let best = null;
    const rank = { end: 0, inter: 0, center: 1, mid: 1, quad: 2 };
    const consider = (p, k) => { const d = dist(p, raw); if (d < tol && (!best || rank[k] < rank[best.k] || (rank[k] === rank[best.k] && d < best.d))) best = { p, k, d }; };
    const near = [];
    for (const e of doc.entities) {
      if (excl && excl.has(e.id)) continue;
      for (const sp of snapPts(e)) consider(sp.p, sp.k);
      for (const s of segmentsOf(e)) { const r = segDist(raw, s[0], s[1]); if (r.d < tol) near.push({ s, r }); }
    }
    if (draft && draft.pts) draft.pts.forEach(p => consider(p, 'end'));
    for (let i = 0; i < near.length; i++) for (let j = i + 1; j < near.length; j++) {
      const q = segInter(near[i].s[0], near[i].s[1], near[j].s[0], near[j].s[1]); if (q) consider(q, 'inter');
    }
    if (best) return { p: cp(best.p), kind: best.k };
    let nb = null; for (const n of near) if (n.r.d < tol * 0.6 && (!nb || n.r.d < nb.r.d)) nb = n;
    if (nb) return { p: nb.r.q, kind: 'near', seg: nb.s };
  }
  if (S.grid) return { p: { x: Math.round(raw.x / gridStep) * gridStep, y: Math.round(raw.y / gridStep) * gridStep }, kind: 'grid' };
  return { p: raw, kind: null };
}
function draftBase() {
  if (!draft || !draft.pts || !draft.pts.length) return null;
  if (draft.t === 'line' || draft.t === 'poly') return draft.pts[draft.pts.length - 1];
  if (draft.t === 'dim' && draft.pts.length === 1) return draft.pts[0];
  return null;
}
function applyOrtho(base, p) {
  const dx = p.x - base.x, dy = p.y - base.y, L = Math.hypot(dx, dy);
  if (L < 1e-9) return p;
  const st = S.ortho * DEG;
  const a = Math.round(Math.atan2(dy, dx) / st) * st;
  const len = S.grid ? Math.round(L / gridStep) * gridStep : L;
  return { x: base.x + Math.cos(a) * len, y: base.y + Math.sin(a) * len };
}
function updateCursor(sx, sy, excl) {
  const s = snapAt(sx, sy, excl);
  let p = s.p, kind = s.kind;
  const base = draftBase();
  if (base && S.ortho) {
    const raw = S2W(sx, sy);
    if (!kind || kind === 'grid') { p = applyOrtho(base, raw); kind = null; }
    else if (kind === 'near') { // Winkel halten und auf der Linie enden
      const o = applyOrtho(base, raw), d = sub(o, base), L = Math.hypot(d.x, d.y);
      if (L > 1e-9) {
        const u = mul(d, 1e7 / L), q = segInter(sub(base, u), add(base, u), s.seg[0], s.seg[1]);
        if (q && dist(q, raw) < (isTouch ? 22 : 12) / view.s) { p = q; kind = 'inter'; }
      }
    }
  }
  cur = { sx, sy, p, kind, valid: true };
}

// ---------- Zeichnen ----------
let rafPending = false;
function redraw() { if (rafPending) return; rafPending = true; requestAnimationFrame(() => { rafPending = false; paint(); }); }
function paint() {
  if (!doc) return;
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  render(ctx, view, stageW, stageH, {});
  const c = $('#coords');
  c.textContent = cur.valid ? `X ${fmtLen(cur.p.x)}  Y ${fmtLen(cur.p.y)} ${S.unit}${cur.kind && cur.kind !== 'grid' ? '  · ' + SNAPNAME[cur.kind] : ''}` : `Maße in ${S.unit}`;
}
function render(g, v, w, h, o) {
  const K = o.k || 1;
  g.save();
  g.fillStyle = '#fff'; g.fillRect(0, 0, w, h);
  if (!o.exp && S.grid) drawGrid(g, v, w, h);
  if (!o.exp) drawOrigin(g, v);
  for (const e of doc.entities) if (e.t === 'poly' && e.closed) fillPoly(g, e, v);
  for (const e of doc.entities) drawEnt(g, e, v, K, !o.exp && sel.has(e.id));
  if (!o.exp) { drawDraft(g, v); drawGrips(g, v); drawCursor(g, v, w, h); }
  g.restore();
}
function drawGrid(g, v, w, h) {
  const minor = calcGrid(v.s); gridStep = minor;
  const lead = minor / 10 ** Math.floor(Math.log10(minor) + 1e-9);
  const major = minor * (lead === 1 ? 10 : lead === 2 ? 5 : 2);
  const tl = S2W(0, 0, v), br = S2W(w, h, v);
  const lines = (step, col) => {
    if (step * v.s < 4) return;
    g.beginPath(); g.strokeStyle = col; g.lineWidth = 1;
    for (let x = Math.ceil(tl.x / step) * step; x <= br.x; x += step) { const sx = Math.round(x * v.s + v.ox) + 0.5; g.moveTo(sx, 0); g.lineTo(sx, h); }
    for (let y = Math.ceil(br.y / step) * step; y <= tl.y; y += step) { const sy = Math.round(-y * v.s + v.oy) + 0.5; g.moveTo(0, sy); g.lineTo(w, sy); }
    g.stroke();
  };
  lines(minor, '#f1eeea'); lines(major, '#e2dcd4');
}
function drawOrigin(g, v) {
  const o = W2S({ x: 0, y: 0 }, v);
  g.strokeStyle = '#d7c3b0'; g.lineWidth = 1; g.setLineDash([]);
  g.beginPath(); g.moveTo(o.x - 10, o.y); g.lineTo(o.x + 10, o.y); g.moveTo(o.x, o.y - 10); g.lineTo(o.x, o.y + 10); g.stroke();
}
function setStroke(g, ly, col, K, f = 1) {
  g.strokeStyle = col; g.fillStyle = col; g.lineWidth = ly.w * K * f;
  g.setLineDash(ly.dash ? ly.dash.map(d => d * K) : []); g.lineCap = 'round'; g.lineJoin = 'round';
}
function fillPoly(g, e, v) {
  const ly = LAYERS[e.layer] || LAYERS.bestand;
  g.beginPath(); e.pts.forEach((p, i) => { const s = W2S(p, v); i ? g.lineTo(s.x, s.y) : g.moveTo(s.x, s.y); }); g.closePath();
  g.globalAlpha = 0.09; g.fillStyle = ly.color; g.fill(); g.globalAlpha = 1;
}
function label(g, a, b, txt, K, col, side = 1, off = 7) {
  const dx = b.x - a.x, dy = b.y - a.y, L = Math.hypot(dx, dy);
  g.font = `600 ${12 * K}px system-ui,sans-serif`;
  const tw = g.measureText(txt).width;
  if (L < Math.max(26 * K, tw * 0.9)) return;
  let ang = Math.atan2(dy, dx);
  if (ang > Math.PI / 2 - 1e-3 || ang < -Math.PI / 2 - 1e-3) { ang += Math.PI; side = -side; }
  g.save();
  g.translate((a.x + b.x) / 2, (a.y + b.y) / 2); g.rotate(ang);
  g.textAlign = 'center'; g.textBaseline = side > 0 ? 'bottom' : 'top';
  g.setLineDash([]); g.lineJoin = 'round';
  g.lineWidth = 3.5 * K; g.strokeStyle = '#fff'; g.strokeText(txt, 0, -side * off * K);
  g.fillStyle = col; g.fillText(txt, 0, -side * off * K);
  g.restore();
}
function centerText(g, p, txt, K, col, size = 12) {
  g.font = `600 ${size * K}px system-ui,sans-serif`; g.textAlign = 'center'; g.textBaseline = 'middle'; g.setLineDash([]);
  g.lineWidth = 3.5 * K; g.strokeStyle = '#fff'; g.lineJoin = 'round'; g.strokeText(txt, p.x, p.y);
  g.fillStyle = col; g.fillText(txt, p.x, p.y);
}
function drawEnt(g, e, v, K, isSel, colOverride) {
  const ly = LAYERS[e.layer] || LAYERS.bestand, col = colOverride || (isSel ? SELCOL : ly.color);
  const P = p => W2S(p, v);
  const labelCol = colOverride || (isSel ? SELCOL : (e.layer === 'holz' || e.layer === 'bestand' ? '#3d3d3d' : ly.color));
  setStroke(g, ly, col, K, isSel ? 1.3 : 1);
  switch (e.t) {
    case 'line': {
      const a = P(e.a), b = P(e.b);
      g.beginPath(); g.moveTo(a.x, a.y); g.lineTo(b.x, b.y); g.stroke();
      if (S.labels && e.layer !== 'hilf' && !hasDim(e.a, e.b)) label(g, a, b, fmtLen(dist(e.a, e.b)), K, labelCol);
      break;
    }
    case 'rect': {
      const c = rectCorners(e).map(P);
      g.beginPath(); c.forEach((p, i) => i ? g.lineTo(p.x, p.y) : g.moveTo(p.x, p.y)); g.closePath(); g.stroke();
      if (e.holz) {
        g.lineWidth = Math.max(0.8, ly.w * 0.45) * K; g.setLineDash([]);
        g.beginPath(); g.moveTo(c[0].x, c[0].y); g.lineTo(c[2].x, c[2].y); g.moveTo(c[1].x, c[1].y); g.lineTo(c[3].x, c[3].y); g.stroke();
      }
      if (e.label) {
        const top = mid(c[3], c[2]);
        g.font = `700 ${12 * K}px system-ui,sans-serif`;
        centerText(g, { x: top.x, y: top.y - 10 * K }, e.label, K, labelCol);
      } else if (S.labels && e.layer !== 'hilf') {
        label(g, c[0], c[1], fmtLen(e.w), K, labelCol, -1);
        label(g, c[3], c[0], fmtLen(e.h), K, labelCol, -1);
      }
      break;
    }
    case 'circle': {
      const c = P(e.c), r = e.r * v.s;
      g.beginPath(); g.arc(c.x, c.y, r, 0, Math.PI * 2); g.stroke();
      if (S.labels && r > 22 * K) centerText(g, c, 'Ø ' + fmtLen(e.r * 2), K, labelCol);
      else { g.beginPath(); g.moveTo(c.x - 3 * K, c.y); g.lineTo(c.x + 3 * K, c.y); g.moveTo(c.x, c.y - 3 * K); g.lineTo(c.x, c.y + 3 * K); g.lineWidth = 1 * K; g.stroke(); }
      break;
    }
    case 'poly': {
      if (e.pts.length < 2) break;
      const s = e.pts.map(P);
      g.beginPath(); s.forEach((p, i) => i ? g.lineTo(p.x, p.y) : g.moveTo(p.x, p.y)); if (e.closed) g.closePath(); g.stroke();
      if (S.labels && e.layer !== 'hilf') segmentsOf(e).forEach(sg => label(g, P(sg[0]), P(sg[1]), fmtLen(dist(sg[0], sg[1])), K, labelCol));
      if (e.closed && e.pts.length > 2) {
        const A = Math.abs(polyArea(e.pts)) / 1e6;
        centerText(g, P(polyCentroid(e.pts)), fmtNum(A, 2) + ' m²', K, labelCol, 13);
      }
      break;
    }
    case 'dim': {
      const f = dimFrame(e); if (!f) break;
      const sg = Math.sign(e.off) || 1, gap = 3 * K / v.s, ext = 5 * K / v.s, tk = 4.5 * K / v.s;
      g.lineWidth = 1 * K; g.setLineDash([]);
      g.beginPath();
      if (Math.abs(e.off) > gap) {
        for (const [p, Q] of [[e.a, f.A], [e.b, f.B]]) {
          const s1 = P(add(p, mul(f.n, sg * gap))), s2 = P(add(Q, mul(f.n, sg * ext)));
          g.moveTo(s1.x, s1.y); g.lineTo(s2.x, s2.y);
        }
      }
      const d1 = P(sub(f.A, mul(f.u, ext))), d2 = P(add(f.B, mul(f.u, ext)));
      g.moveTo(d1.x, d1.y); g.lineTo(d2.x, d2.y); g.stroke();
      const t = { x: (f.u.x + f.n.x) / Math.SQRT2, y: (f.u.y + f.n.y) / Math.SQRT2 };
      g.lineWidth = 1.8 * K; g.beginPath();
      for (const Q of [f.A, f.B]) { const s1 = P(sub(Q, mul(t, tk))), s2 = P(add(Q, mul(t, tk))); g.moveTo(s1.x, s1.y); g.lineTo(s2.x, s2.y); }
      g.stroke();
      label(g, P(f.A), P(f.B), e.txt || fmtLen(f.L), K, col, sg, 3);
      break;
    }
    case 'text': {
      const p = P(e.p), size = e.h * v.s;
      if (size < 2) break;
      g.font = `${size}px system-ui,sans-serif`; g.textAlign = 'left'; g.textBaseline = 'alphabetic'; g.fillStyle = col;
      g.fillText(e.s, p.x, p.y);
      if (isSel) { g.lineWidth = 1; g.setLineDash([3, 3]); g.strokeRect(p.x - 2, p.y - size, textW(e) * v.s + 4, size * 1.25); }
      break;
    }
  }
}
// Linie schon bemaßt? Dann keine doppelte Längenangabe
function hasDim(a, b) {
  return doc.entities.some(d => d.t === 'dim' && ((dist(d.a, a) < 0.05 && dist(d.b, b) < 0.05) || (dist(d.a, b) < 0.05 && dist(d.b, a) < 0.05)));
}
function tip(g, x, y, txt, w, h) {
  g.font = '600 13px system-ui,sans-serif';
  const tw = g.measureText(txt).width + 14;
  let tx = x + 18, ty = y - 40;
  if (tx + tw > w - 4) tx = x - 18 - tw;
  if (ty < 44) ty = y + 22;
  g.fillStyle = 'rgba(30,30,30,.85)'; g.beginPath();
  if (g.roundRect) g.roundRect(tx, ty, tw, 24, 6); else g.rect(tx, ty, tw, 24);
  g.fill();
  g.fillStyle = '#fff'; g.textAlign = 'left'; g.textBaseline = 'middle'; g.fillText(txt, tx + 7, ty + 12);
}
function beamRect(p) {
  const { b, h, anchor } = S.beam;
  const x = anchor === 'bl' ? p.x : p.x - b / 2, y = anchor === 'c' ? p.y - h / 2 : p.y;
  return { t: 'rect', x, y, w: b, h, holz: true, label: fmtNum(b / 10, 1) + '/' + fmtNum(h / 10, 1) };
}
function drawDraft(g, v) {
  const P = p => W2S(p, v), c = cur.p;
  const ghost = e => drawEnt(g, Object.assign({ layer: S.layer }, e), v, 1, false, DRAFTCOL);
  g.setLineDash([]);
  if (tool === 'beam' && cur.valid) { g.globalAlpha = 0.55; ghost(beamRect(c)); g.globalAlpha = 1; return; }
  if (!draft) return;
  g.strokeStyle = DRAFTCOL; g.lineWidth = 1.5; g.setLineDash([6, 4]);
  const line = (a, b) => { const s1 = P(a), s2 = P(b); g.beginPath(); g.moveTo(s1.x, s1.y); g.lineTo(s2.x, s2.y); g.stroke(); };
  switch (draft.t) {
    case 'line': if (cur.valid) { const a = draft.pts[0]; line(a, c); tip(g, cur.sx, cur.sy, `${fmtLen(dist(a, c))}  ·  ${fmtNum(angOf(a, c), 1)}°`, stageW, stageH); } break;
    case 'poly': {
      const s = draft.pts.map(P);
      g.setLineDash([]); g.lineWidth = 2; g.beginPath(); s.forEach((p, i) => i ? g.lineTo(p.x, p.y) : g.moveTo(p.x, p.y)); g.stroke();
      g.setLineDash([6, 4]); g.lineWidth = 1.5;
      if (cur.valid) {
        const a = draft.pts[draft.pts.length - 1]; line(a, c);
        if (draft.pts.length > 1) { g.globalAlpha = 0.35; line(c, draft.pts[0]); g.globalAlpha = 1; }
        tip(g, cur.sx, cur.sy, `${fmtLen(dist(a, c))}  ·  ${fmtNum(angOf(a, c), 1)}°`, stageW, stageH);
      }
      s.forEach(p => { g.fillStyle = DRAFTCOL; g.fillRect(p.x - 3, p.y - 3, 6, 6); });
      break;
    }
    case 'rect': if (cur.valid) { const r = rectFrom(draft.pts[0], c); ghost({ t: 'rect', ...r }); tip(g, cur.sx, cur.sy, `${fmtLen(r.w)} × ${fmtLen(r.h)}`, stageW, stageH); } break;
    case 'circle': if (cur.valid) { const r = dist(draft.pts[0], c); ghost({ t: 'circle', c: draft.pts[0], r }); line(draft.pts[0], c); tip(g, cur.sx, cur.sy, `Ø ${fmtLen(2 * r)}`, stageW, stageH); } break;
    case 'dim':
      if (draft.pts.length === 1 && cur.valid) { line(draft.pts[0], c); tip(g, cur.sx, cur.sy, fmtLen(dist(draft.pts[0], c)), stageW, stageH); }
      else if (draft.pts.length === 2 && cur.valid) ghost({ t: 'dim', a: draft.pts[0], b: draft.pts[1], off: dimOffset(draft.pts[0], draft.pts[1], c) });
      break;
    case 'text': { const p = P(draft.p); g.setLineDash([]); g.strokeRect(p.x - 4, p.y - 4, 8, 8); break; }
  }
  g.setLineDash([]);
}
function dimOffset(a, b, p) { const f = dimFrame({ a, b, off: 0 }); return f ? (p.x - a.x) * f.n.x + (p.y - a.y) * f.n.y : 0; }
function drawGrips(g, v) {
  if (tool !== 'select' || sel.size !== 1) return;
  const e = doc.entities.find(x => sel.has(x.id)); if (!e) return;
  const r = isTouch ? 7 : 5;
  g.setLineDash([]); g.lineWidth = 1.5;
  for (const gp of grips(e)) { const s = W2S(gp.p, v); g.fillStyle = '#fff'; g.strokeStyle = SELCOL; g.fillRect(s.x - r, s.y - r, 2 * r, 2 * r); g.strokeRect(s.x - r, s.y - r, 2 * r, 2 * r); }
}
function drawCursor(g, v, w, h) {
  if (!cur.valid || tool === 'select') return;
  const s = W2S(cur.p, v);
  g.setLineDash([]); g.lineWidth = 1; g.strokeStyle = 'rgba(30,136,229,.45)';
  g.beginPath();
  if (isTouch) { g.moveTo(0, s.y + 0.5); g.lineTo(w, s.y + 0.5); g.moveTo(s.x + 0.5, 0); g.lineTo(s.x + 0.5, h); }
  else { g.moveTo(s.x - 14, s.y); g.lineTo(s.x + 14, s.y); g.moveTo(s.x, s.y - 14); g.lineTo(s.x, s.y + 14); }
  g.stroke();
  if (cur.kind && cur.kind !== 'grid') {
    g.strokeStyle = SNAPCOL; g.lineWidth = 2; const r = 7; g.beginPath();
    switch (cur.kind) {
      case 'end': g.rect(s.x - r, s.y - r, 2 * r, 2 * r); break;
      case 'mid': g.moveTo(s.x, s.y - r); g.lineTo(s.x + r, s.y + r); g.lineTo(s.x - r, s.y + r); g.closePath(); break;
      case 'center': g.arc(s.x, s.y, r, 0, Math.PI * 2); break;
      case 'quad': g.moveTo(s.x, s.y - r); g.lineTo(s.x + r, s.y); g.lineTo(s.x, s.y + r); g.lineTo(s.x - r, s.y); g.closePath(); break;
      case 'inter': g.moveTo(s.x - r, s.y - r); g.lineTo(s.x + r, s.y + r); g.moveTo(s.x + r, s.y - r); g.lineTo(s.x - r, s.y + r); break;
      case 'near': g.moveTo(s.x - r, s.y - r); g.lineTo(s.x + r, s.y - r); g.lineTo(s.x - r, s.y + r); g.lineTo(s.x + r, s.y + r); g.closePath(); break;
    }
    g.stroke();
  } else { g.fillStyle = DRAFTCOL; g.beginPath(); g.arc(s.x, s.y, 3, 0, Math.PI * 2); g.fill(); }
}

// ---------- Ansicht ----------
function resize() {
  const r = $('#stage').getBoundingClientRect();
  if (!r.width || !r.height) return;
  const ow = stageW, oh = stageH;
  stageW = r.width; stageH = r.height; dpr = window.devicePixelRatio || 1;
  cv.width = Math.round(stageW * dpr); cv.height = Math.round(stageH * dpr);
  // Breite: Mitte beibehalten (Drehen des Handys); Höhe: oben festhalten, damit nichts springt
  if (ow > 0) view.ox += (stageW - ow) / 2;
  if (oh > 0 && Math.abs(stageH - oh) > 200) view.oy += (stageH - oh) / 2;
  redraw();
}
function fitView(bb, w, h, margin) {
  const bw = Math.max(bb.x1 - bb.x0, 1), bh = Math.max(bb.y1 - bb.y0, 1);
  const s = clamp(Math.min((w - 2 * margin) / bw, (h - 2 * margin) / bh), 0.0005, 40);
  const cx = (bb.x0 + bb.x1) / 2, cy = (bb.y0 + bb.y1) / 2;
  return { s, ox: w / 2 - cx * s, oy: h / 2 + cy * s };
}
function zoomFit() {
  const bb = bboxAll(doc.entities);
  if (!bb) { defaultView(); return; }
  view = fitView(bb, stageW, stageH - 40, 50); view.oy += 20;
  redraw();
}
function defaultView() {
  const span = doc.kind === 'detail' ? 600 : 8000;
  view = { s: stageW / span, ox: stageW * 0.15, oy: stageH * 0.8 };
  redraw();
}
function zoomAt(sx, sy, f) {
  const w = S2W(sx, sy), s = clamp(view.s * f, 0.0005, 40);
  view = { s, ox: sx - w.x * s, oy: sy + w.y * s };
  redraw();
}

// ---------- Eingabe Zeigegerät ----------
const ptrs = new Map();
let gest = null;
const posOf = e => { const r = cv.getBoundingClientRect(); return { x: e.clientX - r.left, y: e.clientY - r.top }; };
function selectedEnts() { return doc.entities.filter(e => sel.has(e.id)); }
function hitAt(sx, sy) {
  const p = S2W(sx, sy), tol = (isTouch ? 16 : 8) / view.s;
  let best = null, bd = Infinity;
  for (let i = doc.entities.length - 1; i >= 0; i--) {
    const e = doc.entities[i], d = hitDist(e, p);
    if (d < tol && d < bd) { bd = d; best = e; }
  }
  return best;
}
function gripAt(sx, sy) {
  if (sel.size !== 1) return null;
  const e = selectedEnts()[0]; if (!e) return null;
  const r = isTouch ? 20 : 10;
  for (const g of grips(e)) { const s = W2S(g.p); if (Math.abs(s.x - sx) <= r && Math.abs(s.y - sy) <= r) return { e, g }; }
  return null;
}
function startPinch() {
  const [a, b] = [...ptrs.values()];
  const c = mid(a, b);
  gest = { type: 'pinch', d: Math.max(dist(a, b), 1), c, v: { ...view }, wc: S2W(c.x, c.y) };
}
cv.addEventListener('pointerdown', e => {
  if (!doc) return;
  closeMenu();
  try { cv.setPointerCapture(e.pointerId); } catch (x) { /* egal */ }
  isTouch = e.pointerType !== 'mouse';
  const p = posOf(e);
  ptrs.set(e.pointerId, p);
  if (ptrs.size === 2) { startPinch(); redraw(); return; }
  if (ptrs.size > 2) return;
  if (e.button === 1 || e.button === 2 || (e.pointerType === 'mouse' && (e.altKey || e.ctrlKey && tool !== 'select'))) { gest = { type: 'pan', x: p.x, y: p.y, lx: p.x, ly: p.y, moved: false }; return; }
  if (tool === 'select') {
    const gr = gripAt(p.x, p.y);
    if (gr) {
      const ex = new Set([gr.e.id]), links = gr.g.link ? linkedGrips(gr.g.p, ex) : [];
      links.forEach(l => ex.add(l.e.id));
      gest = { type: 'grip', e: gr.e, i: gr.g.i, links, snap: snapshot(), moved: false, x: p.x, y: p.y, ex };
      return;
    }
    const hit = hitAt(p.x, p.y);
    if (hit) {
      if (e.shiftKey || e.ctrlKey || e.metaKey) { sel.has(hit.id) ? sel.delete(hit.id) : sel.add(hit.id); }
      else if (!sel.has(hit.id)) sel = new Set([hit.id]);
      refreshPanel();
      updateCursor(p.x, p.y, sel);
      gest = { type: 'move', last: cur.p, snap: snapshot(), moved: false, x: p.x, y: p.y };
    } else gest = { type: 'pan', x: p.x, y: p.y, lx: p.x, ly: p.y, moved: false, tap: true };
    redraw();
    return;
  }
  updateCursor(p.x, p.y);
  gest = { type: 'aim', x: p.x, y: p.y, moved: false };
  redraw();
});
cv.addEventListener('pointermove', e => {
  if (!doc) return;
  const p = posOf(e);
  if (!ptrs.has(e.pointerId)) { // Maus ohne Taste
    if (e.pointerType === 'mouse') { isTouch = false; if (tool !== 'select') { updateCursor(p.x, p.y); redraw(); } }
    return;
  }
  ptrs.set(e.pointerId, p);
  if (!gest) return;
  if (gest.type === 'pinch') {
    if (ptrs.size < 2) return;
    const [a, b] = [...ptrs.values()], c = mid(a, b), d = Math.max(dist(a, b), 1);
    const s = clamp(gest.v.s * d / gest.d, 0.0005, 40);
    view = { s, ox: c.x - gest.wc.x * s, oy: c.y + gest.wc.y * s };
    redraw(); return;
  }
  if (Math.hypot(p.x - gest.x, p.y - gest.y) > (isTouch ? 8 : 4)) gest.moved = true;
  switch (gest.type) {
    case 'pan': view.ox += p.x - gest.lx; view.oy += p.y - gest.ly; gest.lx = p.x; gest.ly = p.y; break;
    case 'aim': updateCursor(p.x, p.y); break;
    case 'grip': if (gest.moved) { updateCursor(p.x, p.y, gest.ex); setGrip(gest.e, gest.i, cur.p); gest.links.forEach(l => setGrip(l.e, l.i, cur.p)); refreshPanel(true); } break;
    case 'move': if (gest.moved) {
      updateCursor(p.x, p.y, sel);
      const d = sub(cur.p, gest.last);
      if (d.x || d.y) { selectedEnts().forEach(en => transformEnt(en, q => add(q, d))); gest.last = cur.p; refreshPanel(true); }
    } break;
  }
  redraw();
});
function endPointer(e, cancel) {
  if (!ptrs.has(e.pointerId)) return;
  ptrs.delete(e.pointerId);
  const g = gest;
  if (g && g.type === 'pinch') { gest = ptrs.size ? { type: 'none' } : null; return; }
  if (ptrs.size) return;
  gest = null;
  if (!g || cancel) { redraw(); return; }
  switch (g.type) {
    case 'aim': commitPoint(cur.p); break;
    case 'pan': if (g.tap && !g.moved) { sel.clear(); refreshPanel(); } break;
    case 'move': case 'grip':
      if (g.moved) { pushUndo(g.snap); saveDoc(); }
      cur.valid = false; refreshPanel(); break;
  }
  if (!isTouch && tool !== 'select') { const p = posOf(e); updateCursor(p.x, p.y); }
  redraw();
}
cv.addEventListener('pointerup', e => endPointer(e, false));
cv.addEventListener('pointercancel', e => endPointer(e, true));
cv.addEventListener('pointerleave', e => { if (e.pointerType === 'mouse' && !ptrs.size) { cur.valid = false; redraw(); } });
cv.addEventListener('contextmenu', e => e.preventDefault());
cv.addEventListener('wheel', e => { e.preventDefault(); const p = posOf(e); zoomAt(p.x, p.y, Math.exp(-e.deltaY * (e.deltaMode ? 0.05 : 0.0015))); }, { passive: false });

// ---------- Werkzeuge ----------
function addEnt(e) { e.id = uid(); if (!e.layer) e.layer = S.layer; doc.entities.push(e); return e; }
function commitPoint(p) {
  p = cp(p);
  switch (tool) {
    case 'line':
      if (!draft) { draft = { t: 'line', pts: [p] }; doc.lastPt = p; refreshPanel(); break; }
      { const a = draft.pts[0];
        if (dist(a, p) < 1e-6) { draft = null; refreshPanel(); break; }
        change(() => addEnt({ t: 'line', a: cp(a), b: p }));
        draft.pts = [p]; doc.lastPt = p; S.lastAng = Math.round(angOf(a, p) * 100) / 100; saveSettings(); saveDoc(); syncAngField(); }
      break;
    case 'poly':
      if (!draft) { draft = { t: 'poly', pts: [p] }; refreshPanel(); break; }
      if (draft.pts.length >= 3 && dist(p, draft.pts[0]) < 1e-6) { finishPoly(true); break; }
      if (dist(p, draft.pts[draft.pts.length - 1]) < 1e-6) break;
      S.lastAng = Math.round(angOf(draft.pts[draft.pts.length - 1], p) * 100) / 100; syncAngField();
      draft.pts.push(p); doc.lastPt = p; updUndoBtns(); refreshPanel();
      break;
    case 'rect':
      if (!draft) { draft = { t: 'rect', pts: [p] }; refreshPanel(); break; }
      { const r = rectFrom(draft.pts[0], p); draft = null;
        if (r.w > 1e-6 && r.h > 1e-6) change(() => addEnt({ t: 'rect', ...r })); else refreshPanel(); }
      break;
    case 'circle':
      if (!draft) { draft = { t: 'circle', pts: [p] }; refreshPanel(); break; }
      { const c = draft.pts[0], r = dist(c, p); draft = null;
        if (r > 1e-6) change(() => addEnt({ t: 'circle', c, r })); else refreshPanel(); }
      break;
    case 'dim':
      if (!draft) { draft = { t: 'dim', pts: [p] }; refreshPanel(); break; }
      if (draft.pts.length === 1) { if (dist(draft.pts[0], p) > 1e-6) draft.pts.push(p); refreshPanel(); break; }
      { const [a, b] = draft.pts, off = dimOffset(a, b, p); draft = null;
        change(() => addEnt({ t: 'dim', a, b, off, layer: 'mass' })); }
      break;
    case 'text':
      draft = { t: 'text', p }; refreshPanel();
      setTimeout(() => { const i = $('#panel [data-p="txt"]'); if (i) i.focus(); }, 30);
      break;
    case 'beam':
      change(() => addEnt(Object.assign(beamRect(p), { layer: S.layer === 'mass' ? 'holz' : S.layer })));
      break;
  }
  updUndoBtns();
  redraw();
}
function finishPoly(closed) {
  if (!draft || draft.t !== 'poly') return;
  const pts = draft.pts; draft = null;
  if (pts.length >= (closed ? 3 : 2)) change(() => addEnt({ t: 'poly', pts, closed }));
  else refreshPanel();
  redraw();
}
function numericStep(lenStr, angStr) {
  const len = parseLen(lenStr), ang = evalExpr(angStr);
  if (!(len > 0)) { toast('Bitte eine Länge eingeben'); return false; }
  const a = isFinite(ang) ? ang : 0;
  S.lastAng = normAng(a); saveSettings();
  if (!draft) {
    const start = doc.lastPt || { x: 0, y: 0 };
    draft = { t: tool, pts: [cp(start)] };
  }
  const base = draft.pts[draft.pts.length - 1];
  commitPoint(polar(base, len, a));
  return true;
}
function setTool(t) {
  if (draft && draft.t === 'poly' && t !== 'poly') finishPoly(false);
  tool = t; draft = null;
  if (t !== 'select') sel.clear();
  $$('#tools button').forEach(b => b.classList.toggle('on', b.dataset.tool === t));
  cur.valid = false;
  refreshPanel(); updUndoBtns(); redraw();
}

// ---------- Eigenschaften / Eingabeleiste ----------
const field = (p, lab, val, cls = '', type = 'text', mode = 'decimal') =>
  `<label class="f ${cls}">${lab}<input data-p="${p}" type="${type}" inputmode="${mode}" enterkeyhint="done" autocomplete="off" value="${esc(val)}"></label>`;
const btn = (act, txt, cls = '', extra = '') => `<button class="b ${cls}" data-act="${act}" ${extra}>${txt}</button>`;
const layerSelect = (val) => `<label class="f">Ebene<select data-p="layer">${Object.entries(LAYERS).map(([k, l]) => `<option value="${k}" ${k === val ? 'selected' : ''}>${l.name}</option>`).join('')}</select></label>`;
const U = () => S.unit;
const dirButtons = () => `<div class="grp">${[[0, '→'], [90, '↑'], [180, '←'], [270, '↓']].map(([a, s]) => btn('dir', s, 'dir', `data-ang="${a}" title="${a}°"`)).join('')}</div>`;

function refreshPanel(soft) {
  const pn = $('#panel');
  if (soft) { // nur Werte aktualisieren, Eingabefeld mit Fokus nicht anfassen
    if (tool === 'select' && sel.size === 1) {
      const e = selectedEnts()[0], vals = propValues(e);
      $$('input[data-p]', pn).forEach(i => { if (i !== document.activeElement && i.dataset.p in vals) i.value = vals[i.dataset.p]; });
      const inf = $('.info', pn); if (inf) inf.innerHTML = infoText(e);
    }
    return;
  }
  const focused = document.activeElement && pn.contains(document.activeElement) ? document.activeElement.dataset.p : null;
  let h = '';
  switch (tool) {
    case 'line': case 'poly':
      h += `<div class="hint">${draft ? (tool === 'poly' ? 'Nächsten Eckpunkt antippen oder Länge + Richtung eingeben. Ersten Punkt antippen = schließen.' : 'Nächsten Punkt antippen oder Länge + Richtung eingeben (Kette).') : (tool === 'poly' ? '<b>Fläche:</b> Eckpunkte antippen oder Maße eingeben – zeigt m² und Umfang.' : '<b>Linie:</b> Startpunkt antippen – oder einfach Länge eingeben und Richtung wählen.')}</div>`;
      h += field('len', `Länge (${U()})`, '') + field('ang', 'Winkel °', fmtNum(S.lastAng, 2)) + dirButtons() + btn('step', '↵', 'go', 'title="Eintragen"');
      if (draft) h += tool === 'poly' ? btn('closePoly', 'Schließen', 'go') + btn('endPoly', 'Offen fertig') : btn('end', 'Fertig');
      break;
    case 'rect':
      h += `<div class="hint"><b>Rechteck:</b> zwei Ecken antippen – oder Breite/Höhe eingeben${draft ? ' (ab der ersten Ecke)' : ' (ab letztem Punkt)'}.</div>`;
      h += field('rw', `Breite (${U()})`, '') + field('rh', `Höhe (${U()})`, '') + btn('rectNum', '↵', 'go');
      if (draft) h += btn('end', 'Abbrechen');
      break;
    case 'circle':
      h += `<div class="hint"><b>Kreis:</b> Mittelpunkt, dann Radius antippen – oder Durchmesser eingeben.</div>`;
      h += field('cd', `Ø (${U()})`, '') + btn('circNum', '↵', 'go');
      if (draft) h += btn('end', 'Abbrechen');
      break;
    case 'beam': {
      const b = S.beam, cur = fmtNum(b.b / 10, 1) + '/' + fmtNum(b.h / 10, 1);
      h += `<div class="hint"><b>Balken-Querschnitt:</b> Querschnitt wählen und Einfügepunkt antippen.</div>`;
      h += `<label class="f">Querschnitt<select data-p="preset">${(PRESETS.includes(cur) ? '' : `<option selected>${cur}</option>`) + PRESETS.map(p => `<option ${p === cur ? 'selected' : ''}>${p}</option>`).join('')}</select></label>`;
      h += field('bb', 'b (cm)', fmtNum(b.b / 10, 1)) + field('bh', 'h (cm)', fmtNum(b.h / 10, 1)) + btn('beamSwap', '⇄', '', 'title="b und h tauschen"');
      h += `<label class="f">Einfügepunkt<select data-p="anchor">${[['c', 'Mitte'], ['bc', 'Mitte unten'], ['bl', 'Ecke unten links']].map(([k, n]) => `<option value="${k}" ${k === b.anchor ? 'selected' : ''}>${n}</option>`).join('')}</select></label>`;
      break;
    }
    case 'dim':
      h += `<div class="hint"><b>Bemaßung:</b> ${!draft ? 'ersten Punkt antippen' : draft.pts.length === 1 ? 'zweiten Punkt antippen' : 'Abstand der Maßlinie antippen'}. Tipp: In der Auswahl eine Linie antippen → „Bemaßen“.</div>`;
      if (draft) h += btn('end', 'Abbrechen');
      break;
    case 'text':
      if (!draft) h += `<div class="hint"><b>Text:</b> Position antippen.</div>`;
      else h += field('txt', 'Text', '', 'wide', 'text', 'text') + field('th', `Größe (${U()})`, fmtLen(S.textH || defaultTextH())) + btn('textOk', 'OK', 'go') + btn('end', 'Abbrechen');
      break;
    case 'select': h += selectPanel(); break;
  }
  pn.innerHTML = h;
  if (focused) { const i = $(`[data-p="${focused}"]`, pn); if (i) i.focus(); }
}
function defaultTextH() { return niceRound(16 / view.s); }
function syncAngField() { const i = $('#panel [data-p="ang"]'); if (i) i.value = fmtNum(S.lastAng, 2); }
function infoText(e) {
  switch (e.t) {
    case 'line': { const a = angOf(e.a, e.b), r = ((a % 180) + 180) % 180, inc = r > 90 ? 180 - r : r;
      return inc > 0.01 && inc < 89.99 ? `Neigung ${fmtNum(inc, 2)}° = ${fmtNum(Math.tan(inc * DEG) * 100, 1)} %` : (inc < 0.01 ? 'waagrecht' : 'senkrecht'); }
    case 'rect': return `Fläche ${fmtNum(e.w * e.h / 1e6, 3)} m² · Umfang ${fmtNum(2 * (e.w + e.h) / 1000, 2)} m`;
    case 'circle': return `Fläche ${fmtNum(Math.PI * e.r * e.r / 1e6, 3)} m² · Umfang ${fmtNum(2 * Math.PI * e.r / 1000, 2)} m`;
    case 'poly': { const per = segmentsOf(e).reduce((s, g) => s + dist(g[0], g[1]), 0);
      return (e.closed ? `Fläche ${fmtNum(Math.abs(polyArea(e.pts)) / 1e6, 3)} m² · ` : '') + `${e.closed ? 'Umfang' : 'Länge'} ${fmtNum(per / 1000, 3)} m`; }
    case 'dim': return `Maß ${fmtLen(dist(e.a, e.b))} ${S.unit}`;
  }
  return '';
}
function propValues(e) {
  switch (e.t) {
    case 'line': return { len: fmtLen(dist(e.a, e.b)), ang: fmtNum(angOf(e.a, e.b), 2) };
    case 'rect': return { w: fmtLen(e.w), h: fmtLen(e.h), label: e.label || '' };
    case 'circle': return { d: fmtLen(e.r * 2) };
    case 'dim': return { off: fmtLen(e.off), dtxt: e.txt || '' };
    case 'text': return { s: e.s, th: fmtLen(e.h) };
    default: return {};
  }
}
function selectPanel() {
  const es = selectedEnts();
  if (!es.length) return `<div class="hint"><b>Auswahl:</b> Element antippen zum Bearbeiten · Element ziehen = verschieben · Ecke ziehen = ändern (verbundene Linien ziehen mit) · leere Fläche ziehen = Ansicht schieben · 2 Finger = zoomen.</div>`;
  let h = '';
  if (es.length === 1) {
    const e = es[0], v = propValues(e);
    const name = { line: 'Linie', rect: e.holz ? 'Balken / Querschnitt' : 'Rechteck', circle: 'Kreis', poly: e.closed ? 'Fläche' : 'Polylinie', dim: 'Bemaßung', text: 'Text' }[e.t];
    h += `<div class="hint"><b>${name}</b></div>`;
    switch (e.t) {
      case 'line': h += field('len', `Länge (${U()})`, v.len) + field('ang', 'Winkel °', v.ang); break;
      case 'rect': h += field('w', `Breite (${U()})`, v.w) + field('h', `Höhe (${U()})`, v.h) + `<label class="chk"><input type="checkbox" data-p="holz" ${e.holz ? 'checked' : ''}>Holz-Kreuz</label>` + field('label', 'Beschriftung', v.label, '', 'text', 'text'); break;
      case 'circle': h += field('d', `Ø (${U()})`, v.d); break;
      case 'poly': h += `<label class="chk"><input type="checkbox" data-p="closed" ${e.closed ? 'checked' : ''}>geschlossen</label>`; break;
      case 'dim': h += field('off', `Abstand (${U()})`, v.off) + field('dtxt', 'Text statt Maß', v.dtxt, '', 'text', 'text'); break;
      case 'text': h += field('s', 'Text', v.s, 'wide', 'text', 'text') + field('th', `Größe (${U()})`, v.th); break;
    }
    h += layerSelect(e.layer);
    const inf = infoText(e); h += inf ? `<div class="info">${inf}</div>` : '<div class="info" hidden></div>';
    h += '<div class="sep"></div>';
    if (e.t === 'line' || e.t === 'rect' || e.t === 'poly') h += btn('dimSel', 'Bemaßen');
  } else {
    h += `<div class="hint"><b>${es.length} Elemente ausgewählt</b> (Shift/Strg-Klick oder „+ Auswahl“)</div>`;
    h += layerSelect(es.every(e => e.layer === es[0].layer) ? es[0].layer : '');
    h += '<div class="sep"></div>';
  }
  h += btn('moveAsk', '↔ Verschieben') + btn('dup', 'Kopie') + btn('rot', '⟲ 90°') + btn('mir', '⇋ Spiegeln') + btn('selAll', '+ Alle') + btn('del', 'Löschen', 'danger');
  return h;
}
function applyProp(e, k, val) {
  const L = () => parseLen(val);
  switch (k) {
    case 'len': { const l = L(); if (!(l > 0)) return false; const nb = polar(e.a, l, angOf(e.a, e.b)); moveLinked(e, 1, e.b, nb); return true; }
    case 'ang': { const a = evalExpr(val); if (!isFinite(a)) return false; const nb = polar(e.a, dist(e.a, e.b), a); moveLinked(e, 1, e.b, nb); return true; }
    case 'w': { const l = L(); if (!(l > 0)) return false; e.w = l; return true; }
    case 'h': { const l = L(); if (!(l > 0)) return false; e.h = l; return true; }
    case 'd': { const l = L(); if (!(l > 0)) return false; e.r = l / 2; return true; }
    case 'off': { const l = L(); if (!isFinite(l)) return false; e.off = l; return true; }
    case 'th': { const l = L(); if (!(l > 0)) return false; e.h = l; S.textH = l; saveSettings(); return true; }
    case 's': e.s = val; return !!val;
    case 'label': e.label = val.trim(); return true;
    case 'dtxt': e.txt = val.trim(); return true;
    case 'layer': if (!LAYERS[val]) return false; e.layer = val; return true;
    case 'holz': e.holz = val; return true;
    case 'closed': e.closed = val; return true;
  }
  return false;
}
function moveLinked(e, gi, oldP, newP) {
  const links = linkedGrips(oldP, new Set([e.id]));
  setGrip(e, gi, newP); links.forEach(l => setGrip(l.e, l.i, newP));
}
$('#panel').addEventListener('change', ev => {
  const t = ev.target, k = t.dataset.p; if (!k) return;
  const val = t.type === 'checkbox' ? t.checked : t.value;
  if (tool === 'select') {
    const es = selectedEnts(); if (!es.length) return;
    if (k === 'mx' || k === 'my') return;
    const s = snapshot(); let ok = false;
    for (const e of es) ok = applyProp(e, k, val) || ok;
    if (ok) { pushUndo(s); saveDoc(); refreshPanel(); redraw(); } else { toast('Ungültige Eingabe'); refreshPanel(); }
    return;
  }
  if (tool === 'beam') {
    if (k === 'preset') { const [b, h] = val.split('/').map(x => evalExpr(x) * 10); if (b > 0 && h > 0) S.beam = { ...S.beam, b, h }; }
    if (k === 'bb' || k === 'bh') { const v = evalExpr(val) * 10; if (v > 0) S.beam[k === 'bb' ? 'b' : 'h'] = v; }
    if (k === 'anchor') S.beam.anchor = val;
    saveSettings(); refreshPanel(); redraw();
  }
  if (k === 'ang') { const a = evalExpr(val); if (isFinite(a)) { S.lastAng = normAng(a); saveSettings(); } }
});
$('#panel').addEventListener('keydown', ev => {
  if (ev.key !== 'Enter') return;
  const k = ev.target.dataset.p;
  ev.preventDefault();
  if (tool === 'select') { if (k === 'mx' || k === 'my') doAct('moveBy'); else ev.target.blur(); return; }
  if (k === 'len' || (k === 'ang' && (tool === 'line' || tool === 'poly'))) { if (k === 'ang' && !$('#panel [data-p="len"]').value) { ev.target.blur(); return; } doAct('step'); }
  else if (k === 'rw' || k === 'rh') doAct('rectNum');
  else if (k === 'cd') doAct('circNum');
  else if (k === 'txt' || (k === 'th' && tool === 'text')) doAct('textOk');
  else if (k === 'mx' || k === 'my') doAct('moveBy');
  else ev.target.blur();
});
$('#panel').addEventListener('click', ev => { const b = ev.target.closest('[data-act]'); if (b) doAct(b.dataset.act, b); });
function pv(k) { const i = $(`#panel [data-p="${k}"]`); return i ? i.value : ''; }
function doAct(a, el) {
  switch (a) {
    case 'dir': { const ang = el.dataset.ang; $('#panel [data-p="ang"]').value = ang; S.lastAng = +ang; saveSettings();
      if (pv('len').trim()) doAct('step'); else $('#panel [data-p="len"]').focus(); break; }
    case 'step': if (numericStep(pv('len'), pv('ang'))) { const i = $('#panel [data-p="len"]'); if (i) { i.value = ''; i.focus(); } } break;
    case 'end': draft = null; refreshPanel(); updUndoBtns(); redraw(); break;
    case 'closePoly': finishPoly(true); break;
    case 'endPoly': finishPoly(false); break;
    case 'rectNum': {
      const w = parseLen(pv('rw')), h = parseLen(pv('rh'));
      if (!(w > 0 && h > 0)) { toast('Breite und Höhe eingeben'); break; }
      const p = draft ? draft.pts[0] : cp(doc.lastPt || { x: 0, y: 0 }); draft = null;
      change(() => addEnt({ t: 'rect', x: p.x, y: p.y, w, h })); doc.lastPt = { x: p.x + w, y: p.y }; break;
    }
    case 'circNum': {
      const d = parseLen(pv('cd')); if (!(d > 0)) { toast('Durchmesser eingeben'); break; }
      const c = draft ? draft.pts[0] : cp(doc.lastPt || { x: 0, y: 0 }); draft = null;
      change(() => addEnt({ t: 'circle', c, r: d / 2 })); break;
    }
    case 'beamSwap': S.beam = { ...S.beam, b: S.beam.h, h: S.beam.b }; saveSettings(); refreshPanel(); redraw(); break;
    case 'textOk': {
      const s = pv('txt').trim(), h = parseLen(pv('th'));
      if (!s) { toast('Text eingeben'); break; }
      const p = draft.p; draft = null; if (h > 0) { S.textH = h; saveSettings(); }
      change(() => addEnt({ t: 'text', p, s, h: h > 0 ? h : defaultTextH(), layer: S.layer === 'holz' ? 'notiz' : S.layer })); break;
    }
    case 'del': { const ids = new Set(sel); sel.clear(); change(() => { doc.entities = doc.entities.filter(e => !ids.has(e.id)); }); break; }
    case 'dup': {
      const off = niceRound(30 / view.s), copies = selectedEnts().map(e => JSON.parse(JSON.stringify(e)));
      change(() => { sel.clear(); copies.forEach(c => { transformEnt(c, q => ({ x: q.x + off, y: q.y - off })); addEnt(c); sel.add(c.id); }); }); break;
    }
    case 'rot': case 'mir': {
      const es = selectedEnts(), bb = bboxAll(es); if (!bb) break;
      const cx = (bb.x0 + bb.x1) / 2, cy = (bb.y0 + bb.y1) / 2;
      const f = a === 'rot' ? q => ({ x: cx - (q.y - cy), y: cy + (q.x - cx) }) : q => ({ x: 2 * cx - q.x, y: q.y });
      change(() => es.forEach(e => transformEnt(e, f, a === 'mir'))); break;
    }
    case 'moveBy': {
      const dx = parseLen(pv('mx') || '0'), dy = parseLen(pv('my') || '0');
      if (!isFinite(dx) || !isFinite(dy) || (!dx && !dy)) { toast('X und/oder Y eingeben'); break; }
      change(() => selectedEnts().forEach(e => transformEnt(e, q => ({ x: q.x + dx, y: q.y + dy })))); break;
    }
    case 'moveAsk': {
      ask('Verschieben um', [{ k: 'x', label: `X (${S.unit}, + rechts / − links)`, value: '0' }, { k: 'y', label: `Y (${S.unit}, + hoch / − runter)`, value: '0' }]).then(r => {
        if (!r) return;
        const dx = parseLen(r.x || '0'), dy = parseLen(r.y || '0');
        if (!isFinite(dx) || !isFinite(dy)) { toast('Ungültige Eingabe'); return; }
        if (dx || dy) change(() => selectedEnts().forEach(e => transformEnt(e, q => ({ x: q.x + dx, y: q.y + dy }))));
      });
      break;
    }
    case 'selAll': sel = new Set(doc.entities.map(e => e.id)); refreshPanel(); redraw(); break;
    case 'dimSel': {
      const e = selectedEnts()[0]; if (!e) break;
      const off = niceRound(25 / view.s), segs = e.t === 'rect' ? segmentsOf(e).slice(0, 1).concat([[rectCorners(e)[3], rectCorners(e)[0]]]) : segmentsOf(e);
      change(() => segs.forEach(sg => addEnt({ t: 'dim', a: cp(sg[0]), b: cp(sg[1]), off: -off, layer: 'mass' })));
      break;
    }
  }
  updUndoBtns();
}

// ---------- Werkzeugleiste ----------
const ICONS = {
  select: '<path d="M5 3l14 8-6 1.5L10 19z"/>',
  line: '<path d="M4 20L20 4"/><circle cx="4" cy="20" r="1.6"/><circle cx="20" cy="4" r="1.6"/>',
  poly: '<path d="M4 18L7 6l10 2 3 10z"/><path d="M9 13h6" stroke-dasharray="2 2"/>',
  rect: '<rect x="4" y="6" width="16" height="12"/>',
  circle: '<circle cx="12" cy="12" r="8"/><path d="M12 12h8"/>',
  beam: '<rect x="7" y="3" width="10" height="18"/><path d="M7 3l10 18M17 3L7 21"/>',
  dim: '<path d="M3 15h18M3 11v8M21 11v8M5 17l2-4M17 17l2-4"/><path d="M9 9h6" stroke-width="1.2"/>',
  text: '<path d="M5 6V4h14v2M12 4v16M9 20h6"/>',
};
const TOOLS = [['select', 'Auswahl', 'v'], ['line', 'Linie', 'l'], ['poly', 'Fläche', 'f'], ['rect', 'Rechteck', 'r'], ['circle', 'Kreis', 'c'], ['beam', 'Balken', 'b'], ['dim', 'Maß', 'd'], ['text', 'Text', 't']];
$('#tools').innerHTML = TOOLS.map(([t, n, k]) => `<button data-tool="${t}" title="${n} (${k.toUpperCase()})"><svg viewBox="0 0 24 24">${ICONS[t]}</svg>${n}</button>`).join('');
$('#tools').addEventListener('click', e => { const b = e.target.closest('[data-tool]'); if (b) setTool(b.dataset.tool); });

// ---------- Chips ----------
function updChips() {
  $$('#chips [data-chip]').forEach(b => {
    const k = b.dataset.chip;
    if (k === 'ortho') { b.textContent = S.ortho ? `∠ ${S.ortho}°` : '∠ frei'; b.classList.toggle('on', !!S.ortho); }
    else if (k === 'unit') b.textContent = S.unit;
    else b.classList.toggle('on', !!S[k]);
  });
  $('#layerSel').innerHTML = Object.entries(LAYERS).map(([k, l]) => `<option value="${k}" ${k === S.layer ? 'selected' : ''}>✎ ${l.name}</option>`).join('');
}
$('#chips').addEventListener('click', e => {
  const b = e.target.closest('[data-chip]'); if (!b) return;
  const k = b.dataset.chip;
  if (k === 'ortho') S.ortho = { 0: 90, 90: 45, 45: 15, 15: 0 }[S.ortho] ?? 90;
  else if (k === 'unit') S.unit = { mm: 'cm', cm: 'm', m: 'mm' }[S.unit];
  else S[k] = !S[k];
  if (k === 'ortho') toast(S.ortho ? `Winkelfang ${S.ortho}°` : 'Winkel frei');
  if (k === 'unit') toast(`Maße in ${S.unit}`);
  saveSettings(); updChips(); refreshPanel(); redraw();
});
$('#layerSel').addEventListener('change', e => { S.layer = e.target.value; saveSettings(); });

// ---------- Tastatur ----------
document.addEventListener('keydown', e => {
  if (!doc || $('#editor').hidden || $('#dlg').open) return;
  const inInput = /INPUT|SELECT|TEXTAREA/.test(document.activeElement?.tagName);
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'z' && !inInput) { e.preventDefault(); e.shiftKey ? redo() : undo(); return; }
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'y' && !inInput) { e.preventDefault(); redo(); return; }
  if (e.key === 'Escape') {
    if (inInput) document.activeElement.blur();
    if (draft) { if (draft.t === 'poly') finishPoly(false); else { draft = null; refreshPanel(); } }
    else if (sel.size) { sel.clear(); refreshPanel(); }
    else setTool('select');
    updUndoBtns(); redraw(); return;
  }
  if (inInput || e.ctrlKey || e.metaKey || e.altKey) return;
  if ((e.key === 'Delete' || e.key === 'Backspace') && sel.size) { e.preventDefault(); doAct('del'); return; }
  if (/^[0-9]$/.test(e.key) && (tool === 'line' || tool === 'poly')) { const i = $('#panel [data-p="len"]'); if (i) { i.focus(); i.value = e.key; e.preventDefault(); } return; }
  const t = TOOLS.find(x => x[2] === e.key.toLowerCase()); if (t) { setTool(t[0]); return; }
  if (e.key === 'Enter' && draft && draft.t === 'poly') finishPoly(true);
});

// ---------- Startseite ----------
function fmtDate(t) { const d = new Date(t); return d.toLocaleDateString('de-DE') + ' ' + d.toLocaleTimeString('de-DE', { hour: '2-digit', minute: '2-digit' }); }
function renderHome() {
  const q = $('#search').value.trim().toLowerCase();
  index.sort((a, b) => b.updated - a.updated);
  const list = index.filter(m => !q || (m.name + ' ' + (m.site || '')).toLowerCase().includes(q));
  $('#docList').innerHTML = list.map(m => `<li data-id="${m.id}">
    <button class="open" data-a="open"><img class="thumb" alt="" src="${esc(LS.get('holzcad.thumb.' + m.id, '') || 'icon.svg')}"><span class="meta"><b>${esc(m.name)}</b><small>${esc(m.site || '–')}</small><small>${fmtDate(m.updated)} · ${m.n} Elemente</small></span></button>
    <span class="acts"><button data-a="copy">Kopie</button><button data-a="del">Löschen</button></span></li>`).join('');
  $('#emptyHint').hidden = index.length > 0;
}
$('#search').addEventListener('input', renderHome);
$('#docList').addEventListener('click', async e => {
  const b = e.target.closest('[data-a]'); if (!b) return;
  const id = b.closest('li').dataset.id, m = index.find(x => x.id === id);
  if (b.dataset.a === 'open') openDoc(id);
  if (b.dataset.a === 'del' && confirm(`„${m.name}“ wirklich löschen?`)) {
    index = index.filter(x => x.id !== id); saveIndex(); LS.del('holzcad.doc.' + id); LS.del('holzcad.thumb.' + id); renderHome();
  }
  if (b.dataset.a === 'copy') {
    const d = LS.get('holzcad.doc.' + id, null); if (!d) return;
    d.id = uid(); d.name += ' (Kopie)'; d.created = Date.now();
    LS.set('holzcad.doc.' + d.id, d); index.unshift({ id: d.id, name: d.name, site: d.site, updated: Date.now(), n: d.entities.length }); saveIndex();
    LS.set('holzcad.thumb.' + d.id, LS.get('holzcad.thumb.' + id, '')); renderHome();
  }
});
$('#btnNew').addEventListener('click', async () => {
  const today = new Date().toLocaleDateString('de-DE');
  const r = await ask('Neue Zeichnung', [
    { k: 'name', label: 'Name', value: 'Aufmaß ' + today },
    { k: 'site', label: 'Baustelle / Kunde', value: '' },
    { k: 'kind', label: 'Art', type: 'select', value: 'aufmass', options: [['aufmass', 'Aufmaß (Raum, Dach, Wand – Meter)'], ['detail', 'Detail (Anschluss, Querschnitt – Zentimeter)']] },
  ]);
  if (!r) return;
  const d = newDocObj(r.name.trim() || 'Zeichnung ' + today, r.site.trim(), r.kind);
  doc = d; saveDoc(); openDoc(d.id, true);
});
$('#fileImport').addEventListener('change', async e => {
  const f = e.target.files[0]; e.target.value = ''; if (!f) return;
  try {
    const d = JSON.parse(await f.text());
    if (d.format !== 'holzcad' || !Array.isArray(d.entities)) throw new Error('kein Holz-CAD');
    d.id = uid(); d.updated = Date.now();
    LS.set('holzcad.doc.' + d.id, d); index.unshift({ id: d.id, name: d.name, site: d.site, updated: d.updated, n: d.entities.length }); saveIndex();
    renderHome(); toast('Importiert: ' + d.name);
  } catch (x) { toast('Datei konnte nicht gelesen werden'); }
});

function openDoc(id, fresh) {
  const d = fresh ? doc : LS.get('holzcad.doc.' + id, null);
  if (!d) { toast('Zeichnung nicht gefunden'); return; }
  doc = d; doc.entities = doc.entities || [];
  undoStack = []; redoStack = []; sel.clear(); draft = null;
  $('#home').hidden = true; $('#editor').hidden = false;
  $('#docName').textContent = doc.name; $('#docSite').textContent = doc.site || 'Antippen für Name/Baustelle';
  stageW = 0; stageH = 0; resize();
  if (!fresh && doc.view && doc.view.s && doc.view.w) { view = { s: doc.view.s, ox: doc.view.ox + (stageW - doc.view.w) / 2, oy: doc.view.oy + (stageH - doc.view.h) / 2 }; }
  else if (doc.entities.length) zoomFit(); else defaultView();
  setTool(doc.entities.length ? 'select' : 'line');
  updChips(); updUndoBtns(); redraw();
  try { history.replaceState(null, '', '#' + doc.id); } catch (e) { /* egal */ }
}
function closeDoc() {
  if (draft && draft.t === 'poly') finishPoly(false);
  if (doc) { saveDoc(); try { LS.set('holzcad.thumb.' + doc.id, thumb()); } catch (e) { /* egal */ } }
  doc = null; $('#editor').hidden = true; $('#home').hidden = false; closeMenu();
  try { history.replaceState(null, '', location.pathname); } catch (e) { /* egal */ }
  renderHome();
}
$('#btnBack').addEventListener('click', closeDoc);
$('#btnUndo').addEventListener('click', undo);
$('#btnRedo').addEventListener('click', redo);
$('#btnFit').addEventListener('click', zoomFit);
$('#titleBox').addEventListener('click', () => menuAction('rename'));

// ---------- Menü ----------
function closeMenu() { $('#menu').hidden = true; }
$('#btnMenu').addEventListener('click', e => { e.stopPropagation(); $('#menu').hidden = !$('#menu').hidden; });
document.addEventListener('click', e => { if (!e.target.closest('#menu') && !e.target.closest('#btnMenu')) closeMenu(); });
$('#menu').addEventListener('click', e => { const b = e.target.closest('[data-m]'); if (b) { closeMenu(); menuAction(b.dataset.m); } });
const safeName = () => (doc.name || 'zeichnung').replace(/[^\wäöüÄÖÜß\- ]+/g, '').trim().replace(/\s+/g, '_') || 'zeichnung';
async function menuAction(m) {
  switch (m) {
    case 'rename': {
      const r = await ask('Zeichnung', [{ k: 'name', label: 'Name', value: doc.name }, { k: 'site', label: 'Baustelle / Kunde', value: doc.site || '' }]);
      if (!r) return;
      doc.name = r.name.trim() || doc.name; doc.site = r.site.trim(); saveDoc();
      $('#docName').textContent = doc.name; $('#docSite').textContent = doc.site || 'Antippen für Name/Baustelle';
      break;
    }
    case 'share': {
      const c = exportCanvas();
      c.toBlob(async b => {
        const f = new File([b], safeName() + '.png', { type: 'image/png' });
        if (navigator.canShare && navigator.canShare({ files: [f] })) { try { await navigator.share({ files: [f], title: doc.name }); } catch (x) { /* abgebrochen */ } }
        else { download(b, f.name); toast('Teilen nicht möglich – Bild gespeichert'); }
      }, 'image/png');
      break;
    }
    case 'png': exportCanvas().toBlob(b => download(b, safeName() + '.png'), 'image/png'); break;
    case 'print': {
      const url = exportCanvas().toDataURL('image/png'), box = $('#print');
      box.innerHTML = ''; const img = new Image(); img.onload = () => setTimeout(() => window.print(), 50); img.src = url; box.appendChild(img);
      break;
    }
    case 'dxf': download(new Blob([toDXF()], { type: 'application/dxf' }), safeName() + '.dxf'); toast('DXF gespeichert (Einheit mm)'); break;
    case 'json': download(new Blob([JSON.stringify(doc, null, 1)], { type: 'application/json' }), safeName() + '.holzcad.json'); break;
    case 'clear': if (doc.entities.length && confirm('Alle Elemente dieser Zeichnung löschen?')) { sel.clear(); draft = null; change(() => { doc.entities = []; }); } break;
    case 'help': showHelp(); break;
  }
}
function download(blob, name) {
  const a = document.createElement('a'); a.href = URL.createObjectURL(blob); a.download = name;
  document.body.appendChild(a); a.click(); a.remove(); setTimeout(() => URL.revokeObjectURL(a.href), 4000);
}

// ---------- Export ----------
function exportCanvas(W = 2480, H = 1754) {
  const c = document.createElement('canvas'); c.width = W; c.height = H;
  const g = c.getContext('2d'), K = W / 1150, titleH = 70 * K;
  const bb = bboxAll(doc.entities) || { x0: 0, y0: 0, x1: 1000, y1: 1000 };
  const v = fitView(bb, W, H - titleH, 70 * K);
  render(g, v, W, H - titleH, { exp: true, k: K });
  g.fillStyle = '#fff'; g.fillRect(0, H - titleH, W, titleH);
  g.strokeStyle = '#333'; g.lineWidth = 1.5 * K; g.strokeRect(10 * K, 10 * K, W - 20 * K, H - 20 * K);
  g.beginPath(); g.moveTo(10 * K, H - titleH); g.lineTo(W - 10 * K, H - titleH); g.stroke();
  g.fillStyle = '#222'; g.textBaseline = 'middle'; g.textAlign = 'left';
  g.font = `700 ${20 * K}px system-ui,sans-serif`; g.fillText(doc.name, 26 * K, H - titleH / 2 - 10 * K);
  g.font = `${14 * K}px system-ui,sans-serif`; g.fillText(doc.site || '', 26 * K, H - titleH / 2 + 15 * K);
  g.textAlign = 'right';
  g.fillText(`Maße in ${S.unit}  ·  ${new Date().toLocaleDateString('de-DE')}  ·  Holz-CAD`, W - 26 * K, H - titleH / 2);
  return c;
}
function thumb() {
  const c = document.createElement('canvas'); c.width = 192; c.height = 138;
  const g = c.getContext('2d'), bb = bboxAll(doc.entities);
  if (!bb) return '';
  const lab = S.labels; S.labels = false;
  render(g, fitView(bb, 192, 138, 10), 192, 138, { exp: true, k: 0.6 });
  S.labels = lab;
  return c.toDataURL('image/png');
}
function toDXF() {
  const out = [], f = n => (Math.round(n * 1000) / 1000).toString();
  const bb = bboxAll(doc.entities), ext = bb ? Math.max(bb.x1 - bb.x0, bb.y1 - bb.y0) : 1000;
  const th = niceRound(ext / 90);
  const L = e => LAYERS[e.layer] || LAYERS.bestand;
  const line = (a, b, ly) => out.push('0', 'LINE', '8', ly.dxf, '62', String(ly.aci), '10', f(a.x), '20', f(a.y), '30', '0', '11', f(b.x), '21', f(b.y), '31', '0');
  const text = (p, s, h, rot, ly, center) => {
    out.push('0', 'TEXT', '8', ly.dxf, '62', String(ly.aci), '10', f(p.x), '20', f(p.y), '30', '0', '40', f(h), '1', s.replace(/[\r\n]+/g, ' '), '50', f(rot));
    if (center) out.push('72', '1', '11', f(p.x), '21', f(p.y), '31', '0');
  };
  const up = a => { a = normAng(a); return a > 90.001 && a <= 270.001 ? a - 180 : a; };
  out.push('0', 'SECTION', '2', 'ENTITIES');
  for (const e of doc.entities) {
    const ly = L(e);
    switch (e.t) {
      case 'line': case 'rect': case 'poly':
        segmentsOf(e).forEach(s => line(s[0], s[1], ly));
        if (e.t === 'rect' && e.holz) { const c = rectCorners(e); line(c[0], c[2], ly); line(c[1], c[3], ly); }
        if (e.t === 'rect' && e.label) text({ x: e.x + e.w / 2, y: e.y + e.h + th * 0.6 }, e.label, th, 0, ly, true);
        if (e.t === 'poly' && e.closed && e.pts.length > 2) text(polyCentroid(e.pts), fmtNum(Math.abs(polyArea(e.pts)) / 1e6, 2) + ' m2', th, 0, ly, true);
        break;
      case 'circle': out.push('0', 'CIRCLE', '8', ly.dxf, '62', String(ly.aci), '10', f(e.c.x), '20', f(e.c.y), '30', '0', '40', f(e.r)); break;
      case 'text': text(e.p, e.s, e.h, 0, ly, false); break;
      case 'dim': {
        const fr = dimFrame(e); if (!fr) break;
        const sg = Math.sign(e.off) || 1, gap = th * 0.3, ex = th * 0.5;
        line(add(e.a, mul(fr.n, sg * gap)), add(fr.A, mul(fr.n, sg * ex)), ly);
        line(add(e.b, mul(fr.n, sg * gap)), add(fr.B, mul(fr.n, sg * ex)), ly);
        line(sub(fr.A, mul(fr.u, ex)), add(fr.B, mul(fr.u, ex)), ly);
        const t = { x: (fr.u.x + fr.n.x) / Math.SQRT2 * th * 0.4, y: (fr.u.y + fr.n.y) / Math.SQRT2 * th * 0.4 };
        line(sub(fr.A, t), add(fr.A, t), ly); line(sub(fr.B, t), add(fr.B, t), ly);
        const a = up(Math.atan2(fr.u.y, fr.u.x) / DEG), tp = add(mid(fr.A, fr.B), mul(fr.n, sg * th * 0.9));
        text(tp, e.txt || fmtNum(fr.L, 1), th, a, ly, true);
        break;
      }
    }
  }
  out.push('0', 'ENDSEC', '0', 'EOF');
  return out.join('\r\n') + '\r\n';
}

// ---------- Dialoge ----------
function ask(title, fields) {
  return new Promise(res => {
    const d = $('#dlg');
    $('#dlgTitle').textContent = title; $('#dlgOk').hidden = false;
    $('#dlgBody').innerHTML = fields.map(f => f.type === 'select'
      ? `<label>${esc(f.label)}<select name="${f.k}">${f.options.map(([v, n]) => `<option value="${v}" ${v === f.value ? 'selected' : ''}>${esc(n)}</option>`).join('')}</select></label>`
      : `<label>${esc(f.label)}<input name="${f.k}" value="${esc(f.value)}" autocomplete="off"></label>`).join('');
    d.onclose = () => {
      if (d.returnValue !== 'ok') return res(null);
      const r = {}; fields.forEach(f => { r[f.k] = $(`#dlgBody [name="${f.k}"]`).value; }); res(r);
    };
    d.returnValue = '';
    d.showModal();
    const first = $('#dlgBody input'); if (first) { first.focus(); first.select(); }
  });
}
function showHelp() {
  const d = $('#dlg');
  $('#dlgTitle').textContent = 'So geht’s';
  $('#dlgOk').hidden = true;
  $('#dlgBody').innerHTML = `<div class="help">
  <h4>Aufmaß ohne Zeichnen</h4>
  <ul><li>Werkzeug <b>Linie</b>, Länge eintippen, Pfeil (→ ↑ ← ↓) drücken – die Wand wird gezeichnet und es geht am Ende weiter.</li>
  <li>Schräge Wände: Winkel eintragen (0° = rechts, 90° = hoch) und ↵.</li>
  <li>Rechnen geht direkt im Feld: <b>1200+450</b> oder <b>3*625</b>.</li>
  <li><b>Fläche</b> zeichnet einen geschlossenen Umriss und zeigt m² und Umfang.</li></ul>
  <h4>Bedienung</h4>
  <ul><li>Punkt setzen: antippen bzw. Finger hinziehen und loslassen (Fadenkreuz zeigt die Position).</li>
  <li>2 Finger: verschieben und zoomen. Am PC: Mausrad zoomt, rechte/mittlere Taste schiebt.</li>
  <li><b>Fang</b> rastet auf Endpunkte, Mitten, Schnittpunkte. <b>∠</b> zieht auf 90°/45°/15°. <b>Raster</b> rastet auf glatte Werte.</li>
  <li><b>Auswahl</b>: Element antippen → Länge, Winkel, Ebene ändern; Ecken ziehen (verbundene Linien ziehen mit); Kopie, Drehen, Spiegeln, Bemaßen.</li>
  <li><b>Balken</b>: Querschnitt wählen (z. B. 8/16) und antippen – mit Holz-Kreuz und Beschriftung.</li></ul>
  <h4>Weitergeben</h4>
  <ul><li>Menü ⋮: als Bild teilen (WhatsApp, Mail…), PNG, Drucken/PDF, DXF für CAD-Programme, oder als Datei für ein anderes Gerät.</li>
  <li>Alles wird nur auf diesem Gerät gespeichert und funktioniert auch ohne Netz.</li></ul>
  <p>PC-Tasten: L Linie · F Fläche · R Rechteck · C Kreis · B Balken · D Maß · T Text · V/Esc Auswahl · Entf löschen · Strg+Z/Y.</p></div>`;
  d.onclose = null; d.returnValue = ''; d.showModal();
}
$('#btnHelpHome').addEventListener('click', showHelp);
let toastT = 0;
function toast(m) { const t = $('#toast'); t.textContent = m; t.hidden = false; clearTimeout(toastT); toastT = setTimeout(() => { t.hidden = true; }, 2200); }

// ---------- Start ----------
new ResizeObserver(() => { if (doc) resize(); }).observe($('#stage'));
window.addEventListener('pagehide', () => { if (doc) saveDoc(); });
document.addEventListener('visibilitychange', () => { if (document.hidden && doc) saveDoc(); });
renderHome();
updChips();
const startId = location.hash.slice(1);
if (startId && index.some(m => m.id === startId)) openDoc(startId);
if ('serviceWorker' in navigator && location.protocol === 'https:') navigator.serviceWorker.register('sw.js').catch(() => {});

// Für Tests
window.__holzcad = { get doc() { return doc; }, get view() { return view; }, toDXF: () => toDXF(), setTool, numericStep, fmtLen, evalExpr };
})();
