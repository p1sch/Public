/*
 * Materialliste – Web-App für den PC.
 * Zwei Wege:
 *  - Ohne Microsoft: Baustellen liegen im Browser dieses PCs; Austausch mit der Handy-App per Datei
 *    („Baustelle als Datei senden“ / „Datei speichern“).
 *  - Mit Microsoft Teams: direkt auf der Baustellen-Datei im Team (Materialliste/baustelle.json), genau wie die
 *    Android-App: Anmeldung mit dem Firmenkonto (OAuth 2.0 + PKCE im Browser), Daten über Microsoft Graph.
 * Kein eigener Server.
 */
"use strict";

const SCOPES = "openid profile offline_access User.Read Team.ReadBasic.All Channel.ReadBasic.All ChannelMessage.Send Files.ReadWrite.All";
const GRAPH = "https://graph.microsoft.com/v1.0";
const DATEI = "Materialliste/baustelle.json";
const DATEIEN = "Materialliste/Dateien/";
const OHNE_MAENGEL = "Erfolgt ohne Mängel";

const $ = (s, el = document) => el.querySelector(s);
const esc = s => String(s == null ? "" : s).replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const leer = s => s == null || String(s).trim() === "";

// Browser-Speicher kann gesperrt sein (privates Fenster) – dann ohne merken weiterarbeiten.
const speicher = {
  get(k) { try { return JSON.parse(localStorage.getItem(k)); } catch (e) { return null; } },
  set(k, v) { try { localStorage.setItem(k, JSON.stringify(v)); } catch (e) { /* egal */ } },
  weg(k) { try { localStorage.removeItem(k); } catch (e) { /* egal */ } },
};
const sitzung = {
  get(k) { try { return sessionStorage.getItem(k); } catch (e) { return null; } },
  set(k, v) { try { sessionStorage.setItem(k, v); } catch (e) { /* egal */ } },
};

// ================================================================ Anmeldung (Microsoft, PKCE)

function konfig() { return speicher.get("ml_konfig") || {}; }
function mandant() { return leer(konfig().mandant) ? "organizations" : konfig().mandant.trim(); }
/** Adresse dieser Seite ohne „index.html“ – muss genau so in der App-Registrierung stehen. */
function redirectUri() { return location.origin + location.pathname.replace(/index\.html$/, ""); }

function b64url(bytes) {
  let s = "";
  for (const b of new Uint8Array(bytes)) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

async function anmelden() {
  const verifier = b64url(crypto.getRandomValues(new Uint8Array(48)));
  const state = b64url(crypto.getRandomValues(new Uint8Array(16)));
  const challenge = b64url(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(verifier)));
  sitzung.set("ml_verifier", verifier);
  sitzung.set("ml_state", state);
  sitzung.set("ml_ziel", location.hash || "#/");
  const p = new URLSearchParams({
    client_id: konfig().clientId, response_type: "code", redirect_uri: redirectUri(), response_mode: "query",
    scope: SCOPES, state, code_challenge: challenge, code_challenge_method: "S256", prompt: "select_account",
  });
  location.assign("https://login.microsoftonline.com/" + mandant() + "/oauth2/v2.0/authorize?" + p);
}

async function tokensHolen(felder) {
  const body = new URLSearchParams(Object.assign({ client_id: konfig().clientId, redirect_uri: redirectUri(), scope: SCOPES }, felder));
  const a = await fetch("https://login.microsoftonline.com/" + mandant() + "/oauth2/v2.0/token", {
    method: "POST", headers: { "Content-Type": "application/x-www-form-urlencoded" }, body,
  });
  const o = await a.json().catch(() => ({}));
  if (!a.ok || !o.access_token) {
    if (o.error === "invalid_grant") abmelden(true);
    throw fehler(a.status || 400, "Anmeldung: " + String(o.error_description || o.error || a.status).split(/\r|\n/)[0]);
  }
  const t = speicher.get("ml_token") || {};
  t.access = o.access_token;
  t.ablauf = Date.now() + (o.expires_in || 3600) * 1000;
  if (o.refresh_token) t.refresh = o.refresh_token;
  speicher.set("ml_token", t);
}

/** Rückkehr von der Microsoft-Anmeldung (?code=…&state=…). */
async function rueckkehr() {
  const q = new URLSearchParams(location.search);
  if (!q.has("code") && !q.has("error")) return false;
  const ziel = sitzung.get("ml_ziel") || "#/";
  history.replaceState(null, "", redirectUri() + ziel);
  if (q.has("error")) throw fehler(400, String(q.get("error_description") || q.get("error")).split(/\r|\n/)[0]);
  if (q.get("state") !== sitzung.get("ml_state")) throw fehler(400, "Anmeldung abgelaufen – bitte noch einmal anmelden.");
  await tokensHolen({ grant_type: "authorization_code", code: q.get("code"), code_verifier: sitzung.get("ml_verifier") || "" });
  return true;
}

let erneuern = null;
async function token() {
  const t = speicher.get("ml_token") || {};
  if (t.access && Date.now() < t.ablauf - 120000) return t.access;
  if (!t.refresh) throw fehler(401, "Nicht angemeldet");
  if (!erneuern) erneuern = tokensHolen({ grant_type: "refresh_token", refresh_token: t.refresh }).finally(() => { erneuern = null; });
  await erneuern;
  return speicher.get("ml_token").access;
}

const angemeldet = () => !!(speicher.get("ml_token") || {}).refresh;

function abmelden(still) {
  speicher.weg("ml_token");
  speicher.weg("ml_name");
  Z.teams = null;
  Z.baustellen = {};
  if (!still) zeigen();
}

// ================================================================ Microsoft Graph

function fehler(status, text) {
  const e = new Error(text || "Fehler " + status);
  e.status = status;
  return e;
}

async function graph(methode, pfad, opt = {}) {
  const url = pfad.startsWith("http") ? pfad : GRAPH + pfad;
  const senden = async () => {
    const h = { Authorization: "Bearer " + await token() };
    if (opt.typ) h["Content-Type"] = opt.typ;
    if (opt.ifMatch) h["If-Match"] = opt.ifMatch;
    return fetch(url, { method: methode, headers: h, body: opt.body });
  };
  let a = await senden();
  if (a.status === 429 || a.status === 503) {
    await new Promise(r => setTimeout(r, Math.min(30, Number(a.headers.get("Retry-After")) || 2) * 1000));
    a = await senden();
  }
  if (!a.ok) {
    let text = "";
    try { text = (await a.json()).error.message; } catch (e) { /* kein JSON */ }
    if (a.status === 401) text = "Anmeldung abgelaufen – bitte neu anmelden.";
    if (a.status === 403) text = "Keine Berechtigung (fehlt die Freigabe durch den Admin?)" + (text ? " – " + text : "");
    throw fehler(a.status, text || "Fehler " + a.status + " von Microsoft");
  }
  return a.status === 204 ? {} : a.json();
}

function itemUrl(b, pfad) {
  const p = pfad.split("/").filter(Boolean).map(encodeURIComponent).join("/");
  return "/drives/" + b.drive + "/items/" + b.ordner + ":/" + p;
}

async function datei(b, pfad) {
  try { return await graph("GET", itemUrl(b, pfad)); } catch (e) { if (e.status === 404) return null; throw e; }
}

// ================================================================ Zustand

const Z = {
  teams: null,          // [{id, displayName, description}]
  baustellen: {},       // teamId → {team, kanal, drive, ordner, eTag, daten, fehlt}
  bilder: {},           // Dateiname → Download-URL
  pdfs: {},             // Pfad → webUrl oder ""
  filter: "alle",
  offen: new Set(),     // aufgeklappte Berichte
};

async function teamsLaden() {
  const l = [];
  let url = "/me/joinedTeams?$select=id,displayName,description";
  while (url) {
    const o = await graph("GET", url);
    l.push(...(o.value || []));
    url = o["@odata.nextLink"] || null;
  }
  l.sort((a, b) => a.displayName.localeCompare(b.displayName, "de"));
  Z.teams = l;
  if (!speicher.get("ml_name")) {
    graph("GET", "/me?$select=displayName").then(m => { speicher.set("ml_name", m.displayName); kopfRechts(); }).catch(() => {});
  }
}

async function baustelleLaden(teamId) {
  if (!Z.teams) await teamsLaden();
  const team = Z.teams.find(t => t.id === teamId);
  if (!team) throw fehler(404, "Dieses Team gibt es nicht (mehr) oder du bist kein Mitglied.");
  const kanal = (await graph("GET", "/teams/" + teamId + "/primaryChannel?$select=id")).id;
  const o = await graph("GET", "/teams/" + teamId + "/channels/" + kanal + "/filesFolder");
  const b = { team, kanal, drive: o.parentReference.driveId, ordner: o.id, basis: "#/team/" + encodeURIComponent(teamId) };
  await datenLaden(b);
  Z.baustellen[teamId] = b;
  return b;
}

async function datenLaden(b) {
  const it = await datei(b, DATEI);
  if (!it) { b.fehlt = true; b.daten = null; return; }
  const a = await fetch(it["@microsoft.graph.downloadUrl"]);
  if (!a.ok) throw fehler(a.status, "Baustellen-Datei konnte nicht geladen werden");
  b.daten = await a.json();
  b.eTag = it.eTag;
  b.fehlt = false;
}

/**
 * Ändert die Baustelle und speichert sie im Team. Hat inzwischen jemand anders gespeichert (412),
 * wird der neue Stand geladen und dieselbe Änderung noch einmal darauf angewendet.
 */
async function aendern(b, aenderung, erfolg) {
  if (b.lokal) {
    const neu = JSON.parse(JSON.stringify(b.daten));
    if (aenderung(neu) === false) { meldung("Diese Stelle gibt es nicht mehr"); render(); return; }
    neu.geaendert = Math.max(Date.now(), (b.daten.geaendert || 0) + 1);
    b.daten = neu;
    meldung(lokalSpeichern(neu) ? "✓ " + (erfolg || "Gespeichert") + " – auf diesem PC"
      : "⚠ Der Browser speichert nicht (privates Fenster?) – bitte „⬇ Datei speichern“");
    render();
    return;
  }
  for (let versuch = 0; versuch < 3; versuch++) {
    const neu = JSON.parse(JSON.stringify(b.daten));
    if (aenderung(neu) === false) { meldung("Diese Stelle gibt es im aktuellen Stand nicht mehr"); render(); return; }
    neu.geaendert = Math.max(Date.now(), (b.daten.geaendert || 0) + 1);
    try {
      const it = await graph("PUT", itemUrl(b, DATEI) + ":/content",
        { body: JSON.stringify(neu, null, 1), typ: "application/json", ifMatch: b.eTag });
      b.daten = neu;
      b.eTag = it.eTag;
      meldung("✓ " + (erfolg || "Gespeichert") + " – ist jetzt auch auf den Handys");
      render();
      return;
    } catch (e) {
      if (e.status === 412 && versuch < 2) { await datenLaden(b); continue; }
      meldung("⚠ Nicht gespeichert: " + e.message);
      render();
      return;
    }
  }
}

// ================================================================ Baustellen auf diesem PC (ohne Microsoft)

const LOKAL = "ml_lokal";

function lokaleListe() { return speicher.get(LOKAL) || {}; }

/** Speichert im Browser; false, wenn der Browser nichts speichern lässt (privates Fenster, voll). */
function lokalSpeichern(d) {
  const l = lokaleListe();
  l[d.id] = d;
  try { localStorage.setItem(LOKAL, JSON.stringify(l)); return true; } catch (e) { return false; }
}

function lokalLoeschen(id) {
  const l = lokaleListe();
  delete l[id];
  speicher.set(LOKAL, l);
}

function lokaleBaustelle(id) {
  const d = lokaleListe()[id];
  return d ? { lokal: true, daten: d, basis: "#/lokal/" + encodeURIComponent(id) } : null;
}

const neueId = () => (crypto.randomUUID ? crypto.randomUUID() : Date.now() + "-" + Math.random().toString(16).slice(2));

function leereBaustelle(projekt) {
  return { version: 1, id: neueId(), projekt, kunde: "", bauteil: "", datum: datum(Date.now()), bearbeiter: "", notiz: "",
    awDatei: "", awDatum: "", geaendert: Date.now(), positionen: [], werkzeug: [], aufgaben: [], rapporte: [] };
}

/** Baustelle als Datei herunterladen (zum Senden ans Handy oder zur Sicherung). */
function dateiSpeichern(d) {
  const blob = new Blob([JSON.stringify(d, null, 1)], { type: "application/json" });
  const a = document.createElement("a");
  a.href = URL.createObjectURL(blob);
  // gleicher Name wie in der Handy-App (DateiAustausch.dateiname); nur einfache Zeichen, sonst verwerfen Browser den Namen
  const name = ML.titel(d).replace(/[äöüÄÖÜß]/g, c => ({ ä: "ae", ö: "oe", ü: "ue", Ä: "Ae", Ö: "Oe", Ü: "Ue", ß: "ss" }[c]))
    .replace(/[–—]/g, "-").replace(/[^A-Za-z0-9 _.\-]+/g, "_").trim();
  a.download = (name || "Baustelle") + ".materialliste.json";
  document.body.appendChild(a);
  a.click();
  a.remove();
  meldung("⬇ Gespeichert – die Datei kannst du ans Handy schicken (📥 Baustelle aus Datei laden)");
}

/** Datei aus der Handy-App (oder eine gesicherte) öffnen. */
function dateiOeffnen(datei) {
  const r = new FileReader();
  r.onload = () => {
    let d;
    try { d = JSON.parse(r.result); } catch (e) { meldung("⚠ Das ist keine Baustellen-Datei"); return; }
    if (!d || typeof d !== "object" || !(Array.isArray(d.positionen) || "projekt" in d)) { meldung("⚠ Das ist keine Baustellen-Datei"); return; }
    for (const k of ["positionen", "werkzeug", "aufgaben", "rapporte"]) if (!Array.isArray(d[k])) d[k] = [];
    if (leer(d.id)) d.id = neueId();
    const alt = lokaleListe()[d.id];
    if (alt && !confirm("„" + ML.titel(alt) + "“ ist schon auf diesem PC. Durch die Datei ersetzen?\n(Abbrechen = als zusätzliche Baustelle öffnen)")) d.id = neueId();
    if (!lokalSpeichern(d)) { meldung("⚠ Der Browser speichert nicht (privates Fenster?)"); return; }
    meldung("✓ „" + ML.titel(d) + "“ geöffnet");
    location.hash = "#/lokal/" + encodeURIComponent(d.id);
  };
  r.readAsText(datei);
}

// ================================================================ Darstellung

let meldungTimer;
function meldung(text) {
  const m = $("#meldung");
  m.textContent = text;
  m.classList.add("zeigen");
  clearTimeout(meldungTimer);
  meldungTimer = setTimeout(() => m.classList.remove("zeigen"), 3500);
}

function kopfRechts(extra) {
  const k = $("#kopf-rechts");
  const name = speicher.get("ml_name");
  k.innerHTML = (extra || "") + (angemeldet()
    ? (name ? '<span class="name">' + esc(name) + "</span>" : "") + '<button class="knopf klein" id="abmelden">Abmelden</button>'
    : "");
  const ab = $("#abmelden");
  if (ab) ab.onclick = () => abmelden();
}

const datumLang = t => new Date(t || 0).toLocaleDateString("de-DE", { weekday: "long", day: "numeric", month: "long", year: "numeric" });
const datumKurz = t => new Date(t || 0).toLocaleDateString("de-DE", { weekday: "short", day: "2-digit", month: "2-digit", year: "numeric" });
const datum = t => new Date(t || 0).toLocaleDateString("de-DE", { day: "2-digit", month: "2-digit", year: "numeric" });

function route() {
  const t = (location.hash || "#/").substring(2).split("/").map(decodeURIComponent);
  return { seite: t[0] || "", id: t[1] || "", reiter: t[2] || "uebersicht" };
}

async function zeigen() {
  const r = route();
  const inhalt = $("#inhalt");
  if (r.seite === "lokal") { kopfRechts(); return render(); }
  if (r.seite === "" && !angemeldet()) { kopfRechts(); return startseite(inhalt); }
  if (!konfig().clientId) { kopfRechts(); return einrichtung(inhalt); }
  if (!angemeldet()) { kopfRechts(); return anmeldung(inhalt); }
  kopfRechts();
  try {
    if (r.seite === "team" && r.id) {
      if (!Z.baustellen[r.id]) {
        inhalt.innerHTML = '<p class="hinweis"><span class="lade"></span>Baustelle wird aus dem Team geladen …</p>';
        await baustelleLaden(r.id);
      }
      render();
    } else {
      if (!Z.teams) {
        inhalt.innerHTML = '<p class="hinweis"><span class="lade"></span>Teams werden geladen …</p>';
        await teamsLaden();
      }
      render();
    }
  } catch (e) {
    if (e.status === 401) abmelden(true);
    inhalt.innerHTML = '<div class="karte fehler"><h2>Das hat nicht geklappt</h2><p>' + esc(e.message) +
      '</p><div class="knoepfe"><button class="knopf" id="nochmal">Nochmal versuchen</button>' +
      (angemeldet() ? "" : '<button class="knopf voll" id="anmelden">Anmelden</button>') + "</div></div>";
    $("#nochmal").onclick = () => { Z.teams = null; Z.baustellen = {}; zeigen(); };
    const an = $("#anmelden");
    if (an) an.onclick = anmelden;
  }
}

function render() {
  const r = route();
  if (r.seite === "lokal") {
    const b = r.id ? lokaleBaustelle(r.id) : null;
    if (b) baustelle(b, r.reiter); else lokalSeite();
  } else if (r.seite === "team" && Z.baustellen[r.id]) baustelle(Z.baustellen[r.id], r.reiter);
  else teamsSeite();
}

// ---------------------------------------------------------------- Startseite: ohne Microsoft oder mit Teams

function startseite(el) {
  const n = Object.keys(lokaleListe()).length;
  el.innerHTML = `
    <h1>Materialliste am PC</h1>
    <p class="unter">Materiallisten, Aufgaben, Werkzeug und Berichte deiner Baustellen – am PC bearbeiten und drucken.</p>
    <div class="raster" style="grid-template-columns:repeat(auto-fit,minmax(300px,1fr))">
      <a class="karte kachel" href="#/lokal" style="--farbe:var(--akzent);text-decoration:none;color:inherit">
        <h3>💻 Ohne Microsoft – auf diesem PC</h3>
        <div class="zahl">${n} ${n === 1 ? "Baustelle" : "Baustellen"}</div>
        <p>Sofort loslegen. Baustellen kommen per Datei vom Handy (📤 „Baustelle als Datei senden“) oder werden hier neu angelegt.</p>
      </a>
      <a class="karte kachel" href="#/teams" style="--farbe:var(--blau);text-decoration:none;color:inherit">
        <h3>👥 Mit Microsoft Teams</h3>
        <div class="zahl">${konfig().clientId ? "eingerichtet" : "einrichten"}</div>
        <p>Alle arbeiten am selben Stand wie in der Handy-App. Braucht einmal die Einrichtung durch den Microsoft-365-Admin.</p>
      </a>
    </div>`;
}

function lokalSeite() {
  const el = $("#inhalt");
  const l = Object.values(lokaleListe()).sort((a, b) => (b.geaendert || 0) - (a.geaendert || 0));
  el.innerHTML = `
    <p class="nicht-drucken" style="margin:0 0 6px"><a href="#/">← Start</a></p>
    <h1>Baustellen auf diesem PC</h1>
    <p class="unter">Ohne Microsoft – gespeichert in diesem Browser. Zum Handy und zurück per Datei.</p>
    <div class="knoepfe nicht-drucken" style="margin-bottom:14px">
      <button class="knopf voll" id="neu">＋ Neue Baustelle</button>
      <label class="knopf" style="margin:0">📂 Datei vom Handy öffnen<input type="file" id="datei" accept=".json,application/json" hidden></label>
    </div>
    ${l.length ? '<div class="raster">' + l.map(d => {
      const s = ML.summen(d);
      const info = [d.kunde, d.bauteil].filter(x => !leer(x)).join(" · ");
      return `<div class="karte kachel" data-id="${esc(d.id)}" style="--farbe:var(--akzent)">
        <h3>${esc(ML.titel(d))}</h3>
        <p>${esc(info || "–")}\n${s.anzahl} Positionen${s.hatPreise ? " · " + ML.euro(s.betrag) : ""} · ${(d.aufgaben || []).length} Aufgaben · ${(d.rapporte || []).length} Berichte</p>
        <div class="knoepfe nicht-drucken" style="margin-top:10px">
          <button class="knopf klein" data-export="${esc(d.id)}">⬇ Datei speichern</button>
          <button class="knopf klein rot" data-weg="${esc(d.id)}">Löschen</button>
        </div></div>`;
    }).join("") + "</div>"
      : '<p class="hinweis">Noch keine Baustelle auf diesem PC.<br>Lege eine neue an oder öffne eine Datei aus der Handy-App<br>(Baustelle → Menü ⋮ → „📤 Baustelle als Datei senden“).</p>'}
    <p class="leer" style="margin-top:16px">Tipp: Die Daten liegen nur in diesem Browser. Mit „⬇ Datei speichern“ sicherst du sie oder schickst sie ans Handy.</p>`;
  $("#neu").onclick = () => {
    const name = prompt("Name der Baustelle (Projekt):", "");
    if (name == null) return;
    const d = leereBaustelle(name.trim() || "Neue Baustelle");
    if (!lokalSpeichern(d)) { meldung("⚠ Der Browser speichert nicht (privates Fenster?)"); return; }
    location.hash = "#/lokal/" + encodeURIComponent(d.id);
  };
  $("#datei").onchange = e => { if (e.target.files[0]) dateiOeffnen(e.target.files[0]); e.target.value = ""; };
  el.querySelectorAll(".kachel[data-id]").forEach(k => k.onclick = e => {
    if (e.target.closest("button")) return;
    location.hash = "#/lokal/" + encodeURIComponent(k.dataset.id);
  });
  el.querySelectorAll("[data-export]").forEach(k => k.onclick = () => dateiSpeichern(lokaleListe()[k.dataset.export]));
  el.querySelectorAll("[data-weg]").forEach(k => k.onclick = () => {
    const d = lokaleListe()[k.dataset.weg];
    if (!confirm("„" + ML.titel(d) + "“ von diesem PC löschen?\nTipp: vorher „⬇ Datei speichern“.")) return;
    lokalLoeschen(k.dataset.weg);
    render();
  });
}

// ---------------------------------------------------------------- Einrichtung und Anmeldung

function einrichtung(el) {
  const q = new URLSearchParams(location.search);
  el.innerHTML = `
    <p style="margin:0 0 6px"><a href="#/">← Start</a> · <a href="#/lokal">💻 ohne Microsoft arbeiten</a></p>
    <h1>Mit Microsoft Teams</h1>
    <p class="unter">Deine Baustellen aus Microsoft Teams – dieselben Daten wie in der Handy-App.</p>
    <div class="karte">
      <h2>Einmal einrichten</h2>
      <p>Trage die <b>Anwendungs-ID (Client-ID)</b> ein – dieselbe wie in der Handy-App (steht in der App-Registrierung bei Microsoft).
      Der Admin muss in der App-Registrierung zusätzlich diese Adresse als <b>Single-Page-Anwendung</b> (Umleitungs-URI) eintragen:</p>
      <p><code id="uri">${esc(redirectUri())}</code></p>
      <label for="client">Anwendungs-ID (Client-ID)</label>
      <input type="text" id="client" placeholder="z. B. 1a2b3c4d-…" value="${esc(q.get("client") || "")}" autocomplete="off">
      <label for="mandant">Verzeichnis-ID (Mandant) – optional</label>
      <input type="text" id="mandant" placeholder="leer lassen = jedes Firmenkonto" value="${esc(q.get("mandant") || "")}" autocomplete="off">
      <div class="knoepfe" style="margin-top:14px"><button class="knopf voll" id="los">Speichern und anmelden</button></div>
    </div>`;
  $("#los").onclick = () => {
    const c = $("#client").value.trim();
    if (!/^[0-9a-fA-F-]{36}$/.test(c)) { meldung("Die Client-ID sieht so aus: 8-4-4-4-12 Zeichen (Ziffern und a–f)"); return; }
    speicher.set("ml_konfig", { clientId: c, mandant: $("#mandant").value.trim() });
    anmelden();
  };
}

function anmeldung(el) {
  el.innerHTML = `
    <p style="margin:0 0 6px"><a href="#/">← Start</a> · <a href="#/lokal">💻 ohne Microsoft arbeiten</a></p>
    <h1>Mit Microsoft Teams</h1>
    <p class="unter">Deine Baustellen aus Microsoft Teams – dieselben Daten wie in der Handy-App.</p>
    <div class="karte">
      <h2>Mit Microsoft anmelden</h2>
      <p>Melde dich mit deinem Firmenkonto an (wie bei Outlook oder Teams).</p>
      <div class="knoepfe"><button class="knopf voll" id="anmelden">Mit Microsoft anmelden</button>
      <button class="knopf" id="aendern">Client-ID ändern</button></div>
    </div>`;
  $("#anmelden").onclick = anmelden;
  $("#aendern").onclick = () => { speicher.weg("ml_konfig"); zeigen(); };
}

// ---------------------------------------------------------------- Teams

function teamsSeite() {
  const el = $("#inhalt");
  const t = Z.teams || [];
  el.innerHTML = `
    <h1>Meine Teams</h1>
    <p class="unter">Ein Team = eine Baustelle. Anklicken zum Arbeiten. · <a href="#/lokal">💻 Baustellen auf diesem PC</a></p>
    <div class="knoepfe nicht-drucken" style="margin-bottom:14px"><button class="knopf klein" id="neu">⟳ Aktualisieren</button></div>
    ${t.length ? '<div class="raster">' + t.map(x => `
      <a class="karte kachel" href="#/team/${encodeURIComponent(x.id)}" style="--farbe:var(--primaer);text-decoration:none;color:inherit">
        <h3>👥 ${esc(x.displayName)}</h3>
        <p>${esc(x.description || "Baustelle öffnen")}</p>
      </a>`).join("") + "</div>"
      : '<p class="hinweis">Du bist noch in keinem Team. Lege in Microsoft Teams für jede Baustelle ein Team an.</p>'}`;
  $("#neu").onclick = () => { Z.teams = null; Z.baustellen = {}; zeigen(); };
}

// ---------------------------------------------------------------- Eine Baustelle

const REITER = [["uebersicht", "Übersicht"], ["material", "📦 Material"], ["aufgaben", "✅ Aufgaben"],
  ["werkzeug", "🔧 Werkzeug"], ["berichte", "📋 Berichte"]];

function baustelle(b, reiter) {
  const el = $("#inhalt");
  if (b.fehlt) return baustelleFehlt(el, b);
  const d = b.daten;
  const info = [d.kunde, d.bauteil, d.datum].filter(x => !leer(x)).join(" · ");
  el.innerHTML = `
    <p class="nicht-drucken" style="margin:0 0 6px">${b.lokal ? '<a href="#/lokal">← Baustellen auf diesem PC</a>' : '<a href="#/teams">← Meine Teams</a>'}</p>
    <div style="display:flex;gap:12px;align-items:flex-start;justify-content:space-between;flex-wrap:wrap">
      <div><h1>${esc(ML.titel(d))}</h1><p class="unter" style="margin:0">${esc(info || (b.lokal ? "💻 auf diesem PC" : "👥 " + b.team.displayName))}</p></div>
      <div class="knoepfe nicht-drucken">
        <button class="knopf klein" id="kopf">✎ Projekt / Kunde</button>
        ${b.lokal ? '<button class="knopf klein" id="export">⬇ Datei speichern</button>' : '<button class="knopf klein" id="neu">⟳ Aktualisieren</button>'}
        <button class="knopf klein" id="drucken">🖨 Drucken</button>
      </div>
    </div>
    <nav class="reiter">${REITER.map(([k, n]) => `<a href="${b.basis}/${k}" class="${k === reiter ? "an" : ""}">${n}</a>`).join("")}</nav>
    <section id="ansicht"></section>`;
  $("#kopf").onclick = () => kopfdaten(b);
  if (b.lokal) $("#export").onclick = () => dateiSpeichern(b.daten);
  else $("#neu").onclick = async () => {
    $("#neu").disabled = true;
    try { await datenLaden(b); meldung("✓ Neuester Stand aus dem Team"); } catch (e) { meldung("⚠ " + e.message); }
    render();
  };
  $("#drucken").onclick = () => window.print();
  const a = $("#ansicht");
  ({ material, aufgaben, werkzeug, berichte }[reiter] || uebersicht)(a, b);
}

function baustelleFehlt(el, b) {
  el.innerHTML = `
    <p style="margin:0 0 6px"><a href="#/teams">← Meine Teams</a></p>
    <h1>👥 ${esc(b.team.displayName)}</h1>
    <div class="karte">
      <h2>Im Team gibt es noch keine Baustelle</h2>
      <p>Am einfachsten öffnest du das Team einmal in der Handy-App (👥 Meine Teams → Team antippen). Oder du legst hier eine leere Baustelle an.</p>
      <div class="knoepfe"><button class="knopf voll" id="anlegen">＋ Leere Baustelle anlegen</button></div>
    </div>`;
  $("#anlegen").onclick = async () => {
    const neu = {
      version: 1, id: crypto.randomUUID ? crypto.randomUUID() : String(Date.now()), projekt: b.team.displayName, kunde: "", bauteil: "",
      datum: datum(Date.now()), bearbeiter: speicher.get("ml_name") || "", notiz: "", awDatei: "", awDatum: "", geaendert: Date.now(),
      team: { id: b.team.id, name: b.team.displayName, kanal: b.kanal, drive: b.drive, ordner: b.ordner },
      positionen: [], werkzeug: [], aufgaben: [], rapporte: [],
    };
    try {
      await graph("PUT", itemUrl(b, DATEI) + ":/content?@microsoft.graph.conflictBehavior=fail",
        { body: JSON.stringify(neu, null, 1), typ: "application/json" });
      await datenLaden(b);
      meldung("✓ Baustelle angelegt");
    } catch (e) { meldung("⚠ " + e.message); }
    render();
  };
}

// ---------------------------------------------------------------- Kopfdaten

function kopfdaten(b) {
  const d = b.daten;
  const dlg = document.createElement("dialog");
  dlg.className = "editor";
  dlg.innerHTML = `<form method="dialog" novalidate>
    <h2>Projekt / Kunde</h2>
    <label for="k-projekt">Projekt / Baustelle</label><input type="text" id="k-projekt" value="${esc(d.projekt)}">
    <label for="k-kunde">Kunde</label><input type="text" id="k-kunde" value="${esc(d.kunde)}">
    <label for="k-bauteil">Bauteil</label><input type="text" id="k-bauteil" value="${esc(d.bauteil)}" placeholder="z. B. Dachstuhl, Carport">
    <label for="k-datum">Datum</label><input type="text" id="k-datum" value="${esc(d.datum)}" placeholder="TT.MM.JJJJ">
    <div class="knoepfe dialog-knoepfe"><span style="flex:1"></span>
      <button type="button" class="knopf" id="k-ab">Abbrechen</button><button type="submit" class="knopf voll">Speichern</button></div>
  </form>`;
  document.body.appendChild(dlg);
  const zu = () => { dlg.close(); dlg.remove(); };
  dlg.querySelector("#k-ab").onclick = zu;
  dlg.addEventListener("cancel", e => { e.preventDefault(); zu(); });
  dlg.querySelector("form").onsubmit = e => {
    e.preventDefault();
    const w = id => dlg.querySelector("#" + id).value.trim();
    const neu = { projekt: w("k-projekt"), kunde: w("k-kunde"), bauteil: w("k-bauteil"), datum: w("k-datum") };
    zu();
    aendern(b, n => { Object.assign(n, neu); }, "Kopfdaten gespeichert");
  };
  dlg.showModal();
}

// ---------------------------------------------------------------- Übersicht

function offeneMaengel(d) {
  const l = [];
  for (const r of d.rapporte || []) {
    if (!ML.mitMaengeln(r)) continue;
    (r.maengel || []).forEach((m, i) => { if (!m.erledigt) l.push({ r, m, i }); });
  }
  return l;
}

function uebersicht(el, b) {
  const d = b.daten;
  const s = ML.summen(d);
  const auf = d.aufgaben || [], wz = d.werkzeug || [], rap = d.rapporte || [];
  const erl = auf.filter(a => a.erledigt).length;
  const dringend = auf.filter(a => !a.erledigt && a.prio === 2).length;
  const stunden = rap.filter(r => ML.art(r) === "rapport").reduce((x, r) => x + ML.summeMin(r), 0);
  const maengel = offeneMaengel(d);
  const letzte = rap.slice().sort((x, y) => (y.tag || 0) - (x.tag || 0)).slice(0, 4);
  const link = k => b.basis + "/" + k;
  el.innerHTML = `
    <div class="raster">
      <a class="karte kachel" href="${link("material")}" style="--farbe:var(--holz);text-decoration:none;color:inherit">
        <h3>📦 Material</h3><div class="zahl">${s.hatPreise ? ML.euro(s.betrag) : s.anzahl + " Pos."}</div>
        <p>${s.anzahl} Positionen${s.holzM3 > 0 ? " · Holz " + ML.fix(s.holzM3, 2) + " m³" : ""}${s.platten > 0 ? " · " + ML.fix(s.platten, 0) + " Platten" : ""}</p></a>
      <a class="karte kachel" href="${link("aufgaben")}" style="--farbe:var(--gruen);text-decoration:none;color:inherit">
        <h3>✅ Aufgaben</h3><div class="zahl">${erl} / ${auf.length}</div>
        <p>erledigt${dringend ? " · 🔴 " + dringend + " dringend offen" : ""}</p></a>
      <a class="karte kachel" href="${link("werkzeug")}" style="--farbe:var(--blau);text-decoration:none;color:inherit">
        <h3>🔧 Werkzeug</h3><div class="zahl">${wz.filter(w => w.eingepackt).length} / ${wz.length}</div><p>eingepackt</p></a>
      <a class="karte kachel" href="${link("berichte")}" style="--farbe:var(--akzent);text-decoration:none;color:inherit">
        <h3>📋 Berichte</h3><div class="zahl">${rap.length}</div>
        <p>${stunden ? ML.dauer(stunden) + " h in Rapporten" : "Rapporte, Bautagebuch, Abnahmen"}${maengel.length ? "\n⚠ " + maengel.length + " offene Mängel" : ""}</p></a>
    </div>
    <div class="raster" style="margin-top:12px;grid-template-columns:repeat(auto-fit,minmax(320px,1fr))">
      <div class="karte">
        <h2>📝 Notiz</h2>
        <textarea id="notiz" placeholder="Notiz zur Baustelle">${esc(d.notiz)}</textarea>
        <div class="knoepfe nicht-drucken" style="margin-top:8px"><button class="knopf klein" id="notiz-speichern">Speichern</button></div>
      </div>
      <div class="karte">
        <h2>⚠ Offene Mängel</h2>
        ${maengel.length ? maengel.map(x => `
          <div class="punkt"><input type="checkbox" data-r="${esc(x.r.id)}" data-i="${x.i}" aria-label="Mangel erledigt">
          <div class="text">${esc(x.m.text)}<div class="info">Abnahme ${datum(x.r.tag)}${x.r.frist ? " · Frist " + datum(x.r.frist) : ""}</div></div></div>`).join("")
          : '<p class="leer">Keine offenen Mängel.</p>'}
      </div>
      <div class="karte">
        <h2>📋 Letzte Berichte</h2>
        ${letzte.length ? letzte.map(r => `<div class="punkt"><div class="text"><a href="${link("berichte")}">${ML.symbol(ML.art(r))} ${esc(r.titel)} · ${datumKurz(r.tag)}</a>
          <div class="info">${esc(berichtKurz(r))}</div></div></div>`).join("") : '<p class="leer">Noch keine Berichte.</p>'}
      </div>
    </div>`;
  $("#notiz-speichern").onclick = () => {
    const t = $("#notiz").value.trim();
    aendern(b, n => { n.notiz = t; }, "Notiz gespeichert");
  };
  el.querySelectorAll("input[data-r]").forEach(c => c.onchange = () => mangelSetzen(b, c.dataset.r, Number(c.dataset.i), c.checked));
}

function mangelSetzen(b, rid, i, erledigt) {
  const r0 = (b.daten.rapporte || []).find(r => r.id === rid);
  const text = r0 && r0.maengel[i] ? r0.maengel[i].text : null;
  aendern(b, n => {
    const r = (n.rapporte || []).find(x => x.id === rid);
    const m = r && (r.maengel || []).find(x => x.text === text);
    if (!m) return false;
    m.erledigt = erledigt;
  }, erledigt ? "Mangel als beseitigt markiert" : "Mangel wieder offen");
}

// ---------------------------------------------------------------- Material

function material(el, b) {
  const d = b.daten;
  const pos = d.positionen || [];
  const s = ML.summen(d);
  let nr = 0;
  const pfeile = i => `<td class="r nicht-drucken pfeile"><button class="klein-pfeil" data-hoch="${i}" title="nach oben" aria-label="nach oben" ${i === 0 ? "disabled" : ""}>↑</button><button class="klein-pfeil" data-runter="${i}" title="nach unten" aria-label="nach unten" ${i === pos.length - 1 ? "disabled" : ""}>↓</button></td>`;
  const zeilen = pos.map((p, i) => {
    if (p.typ === ML.TITEL) return `<tr class="abschnitt klickbar" data-i="${i}"><td colspan="6">${esc(p.bez)}</td>${pfeile(i)}</tr>`;
    nr++;
    const bt = ML.betrag(p);
    return `<tr class="klickbar" data-i="${i}"><td class="r ausblenden-schmal">${nr}</td><td class="menge">${esc(ML.bestellText(p))}</td>
      <td><b>${esc(p.bez || p.holzart)}</b><div class="klein">${esc(ML.details(p))}</div></td>
      <td class="r ausblenden-schmal">${esc(ML.werte(p))}</td>
      <td class="r ausblenden-schmal">${p.preis != null ? ML.euro(p.preis) + " / " + esc(ML.preisEinheit(p)) : ""}</td>
      <td class="r">${bt != null ? ML.euro(bt) : ""}</td>${pfeile(i)}</tr>`;
  }).join("");
  el.innerHTML = `
    <div class="knoepfe nicht-drucken" style="margin-bottom:12px">
      <button class="knopf voll klein" id="neu-pos">＋ Position</button>
      <button class="knopf klein" id="neu-titel">＋ Überschrift</button>
      <button class="knopf klein" id="csv">⬇ Für Excel (CSV)</button>
    </div>
    <div class="karte tabelle-rahmen">
      ${pos.length ? `<table>
        <thead><tr><th class="r ausblenden-schmal">Pos.</th><th>Bestellen</th><th>Bezeichnung</th><th class="r ausblenden-schmal">Werte</th>
        <th class="r ausblenden-schmal">Preis</th><th class="r">Betrag</th><th class="nicht-drucken"></th></tr></thead>
        <tbody>${zeilen}</tbody>
        <tfoot><tr><td class="ausblenden-schmal"></td><td colspan="2">${s.anzahl} Positionen${s.holzM3 > 0 ? " · Holz " + ML.fix(s.holzM3, 2) + " m³" : ""}${s.platten > 0 ? " · " + ML.fix(s.platten, 0) + " Platten" : ""}</td>
        <td class="ausblenden-schmal"></td><td class="ausblenden-schmal r">Summe netto</td><td class="r">${s.hatPreise ? ML.euro(s.betrag) : "–"}</td><td class="nicht-drucken"></td></tr></tfoot>
      </table>` : '<p class="hinweis">Die Materialliste ist noch leer – „＋ Position“ anlegen.</p>'}
    </div>
    <p class="leer nicht-drucken" style="margin-top:10px">Zeile anklicken zum Ändern. Artikel aus DATANORM- und Inventarlisten wählt man in der Handy-App.</p>`;
  $("#csv").onclick = () => csv(d);
  $("#neu-pos").onclick = () => positionBearbeiten(b, -1, { typ: ML.HOLZ });
  $("#neu-titel").onclick = () => positionBearbeiten(b, -1, { typ: ML.TITEL });
  el.querySelectorAll("tr.klickbar").forEach(tr => tr.onclick = e => {
    if (e.target.closest("button")) return;
    const i = Number(tr.dataset.i);
    positionBearbeiten(b, i, pos[i]);
  });
  el.querySelectorAll("[data-hoch],[data-runter]").forEach(k => k.onclick = () => {
    const i = Number(k.dataset.hoch != null ? k.dataset.hoch : k.dataset.runter);
    verschieben(b, i, k.dataset.hoch != null ? -1 : 1);
  });
}

/** Position im aktuellen Stand wiederfinden: über den Inhalt, nicht die Nummer (ein Handy kann inzwischen umsortiert haben). */
function findePosition(liste, vorlage, beiIndex) {
  const s = JSON.stringify(vorlage);
  if (JSON.stringify(liste[beiIndex]) === s) return beiIndex;
  return liste.findIndex(p => JSON.stringify(p) === s);
}

function verschieben(b, i, richtung) {
  const p = b.daten.positionen[i];
  aendern(b, n => {
    const l = n.positionen || [];
    const j = findePosition(l, p, i);
    if (j < 0 || j + richtung < 0 || j + richtung >= l.length) return false;
    [l[j], l[j + richtung]] = [l[j + richtung], l[j]];
  }, "Verschoben");
}

/** Zahl wie in der App: Komma oder Punkt, „1.234,5“ geht auch. Leer → null, ungültig → NaN. */
function zahl(s) {
  s = String(s == null ? "" : s).trim().replace(/\s/g, "");
  if (!s) return null;
  if (s.includes(",")) s = s.replace(/\./g, "").replace(",", ".");
  const d = Number(s);
  return isFinite(d) ? d : NaN;
}

const TYPEN = [[ML.HOLZ, "🌲 Balken & Kanthölzer"], [ML.LFM, "📏 Latten & Bretter"], [ML.PLATTE, "▦ Platten"], [ML.MATERIAL, "📦 Material"], [ML.TITEL, "≡ Überschrift"]];

function positionBearbeiten(b, index, vorlage) {
  const neu = index < 0;
  const p = JSON.parse(JSON.stringify(vorlage));
  const z = d => d == null ? "" : ML.num(d);
  const dlg = document.createElement("dialog");
  dlg.className = "editor";
  const liste = (id, l) => `<datalist id="${id}">${l.map(x => `<option value="${esc(x)}">`).join("")}</datalist>`;
  const bekannt = [...new Set((b.daten.positionen || []).map(x => x.holzart).filter(x => !leer(x)))];
  dlg.innerHTML = `
    <form method="dialog" novalidate>
      <h2>${neu ? (p.typ === ML.TITEL ? "Überschrift hinzufügen" : "Position hinzufügen") : "Ändern"}</h2>
      <label for="f-typ">Art</label>
      <select id="f-typ">${TYPEN.map(([k, n]) => `<option value="${k}" ${k === (p.typ || ML.HOLZ) ? "selected" : ""}>${n}</option>`).join("")}</select>
      <label for="f-bez" id="l-bez">Was? (Bezeichnung)</label>
      <input type="text" id="f-bez" value="${esc(p.bez)}" list="v-bez" autocomplete="off">
      <div data-nur="holz" class="zwei">
        <div><label for="f-stk">Wie viele Stück?</label><input type="text" inputmode="decimal" id="f-stk" value="${z(p.stk)}"></div>
        <div><label for="f-l">Länge je Stück [m]</label><input type="text" inputmode="decimal" id="f-l" value="${p.typ === ML.HOLZ ? z(p.l) : ""}"></div>
      </div>
      <div data-nur="lfm"><label for="f-lfm">Wie viele Meter insgesamt?</label><input type="text" inputmode="decimal" id="f-lfm" value="${z(p.lfm)}"></div>
      <div data-nur="holz lfm" class="zwei">
        <div><label for="f-b">Querschnitt Breite [cm]</label><input type="text" inputmode="decimal" id="f-b" value="${p.typ === ML.PLATTE ? "" : z(p.b)}"></div>
        <div><label for="f-h">Höhe [cm]</label><input type="text" inputmode="decimal" id="f-h" value="${p.typ === ML.PLATTE ? "" : z(p.h)}"></div>
      </div>
      <div data-nur="platte">
        <div class="abschnitt-titel">Welche Fläche wird belegt?</div>
        <div class="zwei">
          <div><label for="f-flL">Länge [m]</label><input type="text" inputmode="decimal" id="f-flL" value="${z(p.flL)}"></div>
          <div><label for="f-flB">Breite [m]</label><input type="text" inputmode="decimal" id="f-flB" value="${z(p.flB)}"></div>
        </div>
        <div class="zwei">
          <div><label for="f-fl">oder gleich m²</label><input type="text" inputmode="decimal" id="f-fl" value="${z(p.fl)}"></div>
          <div><label for="f-vs">Verschnitt [%]</label><input type="text" inputmode="decimal" id="f-vs" value="${p.typ === ML.PLATTE ? z(p.vs) : "10"}"></div>
        </div>
        <div class="abschnitt-titel">Plattengröße</div>
        <div class="drei">
          <div><label for="f-pl">Länge [cm]</label><input type="text" inputmode="decimal" id="f-pl" value="${p.typ === ML.PLATTE && p.l != null ? ML.num(p.l * 100) : ""}"></div>
          <div><label for="f-pb">Breite [cm]</label><input type="text" inputmode="decimal" id="f-pb" value="${p.typ === ML.PLATTE ? z(p.b) : ""}"></div>
          <div><label for="f-pd">Dicke [mm]</label><input type="text" inputmode="decimal" id="f-pd" value="${p.typ === ML.PLATTE && p.h != null ? ML.num(p.h * 10) : ""}"></div>
        </div>
      </div>
      <div data-nur="material">
        <div class="zwei">
          <div><label for="f-menge">Wie viel?</label><input type="text" inputmode="decimal" id="f-menge" value="${z(p.menge)}"></div>
          <div><label for="f-einh">Einheit</label><input type="text" id="f-einh" value="${esc(p.einh || "")}" placeholder="Stk" list="v-einh"></div>
        </div>
        <div class="zwei">
          <div><label for="f-mvs">Reserve / Verschnitt [%]</label><input type="text" inputmode="decimal" id="f-mvs" value="${p.typ === ML.MATERIAL ? z(p.vs) : ""}"></div><div></div>
        </div>
        <div class="abschnitt-titel">Packung (optional) – z. B. Paket à 5,2 m²</div>
        <div class="zwei">
          <div><label for="f-pack">Inhalt je Packung</label><input type="text" inputmode="decimal" id="f-pack" value="${z(p.pack)}"></div>
          <div><label for="f-packname">Packung</label><input type="text" id="f-packname" value="${esc(p.packName || "")}" placeholder="Paket" list="v-pack"></div>
        </div>
      </div>
      <div data-nicht="titel">
        <label for="f-mat" id="l-mat">Holzart / Qualität</label>
        <input type="text" id="f-mat" value="${esc(p.holzart || "")}" list="v-mat" autocomplete="off">
        <div class="ergebnis" id="ergebnis"></div>
        <details id="mehr" ${p.preis != null || !leer(p.bem) || !leer(p.artNr) || p.fb != null || p.fh != null ? "open" : ""}>
          <summary>Weitere Angaben (Preis, Bemerkung, Fertigmaß)</summary>
          <div class="zwei">
            <div><label for="f-preis">Preis [€]</label><input type="text" inputmode="decimal" id="f-preis" value="${p.preis == null ? "" : ML.fix(p.preis, 2).replace(/\./g, "")}"></div>
            <div><label for="f-peinh">je</label><input type="text" id="f-peinh" value="${esc(p.preisEinh || "")}" list="v-einh"></div>
          </div>
          <label for="f-art">Artikelnummer</label><input type="text" id="f-art" value="${esc(p.artNr || "")}">
          <label for="f-bem">Bemerkung</label><input type="text" id="f-bem" value="${esc(p.bem || "")}" placeholder="z. B. gehobelt, imprägniert, Farbe …">
          <div data-nur="holz lfm" class="zwei">
            <div><label for="f-fb">Fertigmaß Breite [cm]</label><input type="text" inputmode="decimal" id="f-fb" value="${z(p.fb)}"></div>
            <div><label for="f-fh">Höhe [cm]</label><input type="text" inputmode="decimal" id="f-fh" value="${z(p.fh)}"></div>
          </div>
        </details>
      </div>
      <p class="fehlertext" id="fehlertext"></p>
      <div class="knoepfe dialog-knoepfe">
        ${neu ? "" : '<button type="button" class="knopf rot" id="f-weg">Löschen</button>'}
        <span style="flex:1"></span>
        <button type="button" class="knopf" id="f-abbrechen">Abbrechen</button>
        <button type="submit" class="knopf voll" id="f-ok">Speichern</button>
      </div>
      ${liste("v-bez", [])}${liste("v-mat", [])}${liste("v-einh", VORSCHLAEGE.EINH_ALLE)}${liste("v-pack", VORSCHLAEGE.EINH_PACKUNGEN)}
    </form>`;
  document.body.appendChild(dlg);
  const f = id => dlg.querySelector("#" + id);
  const typ = () => f("f-typ").value;

  function sichtbar() {
    const t = typ();
    dlg.querySelectorAll("[data-nur]").forEach(x => { x.hidden = !x.dataset.nur.split(" ").includes(t); });
    dlg.querySelectorAll("[data-nicht]").forEach(x => { x.hidden = x.dataset.nicht === t; });
    const platte = t === ML.PLATTE, mat = t === ML.MATERIAL;
    f("l-bez").textContent = t === ML.TITEL ? "Überschrift (z. B. Dachstuhl, Dämmung, Decke EG)" : "Was? (Bezeichnung)";
    const bez = platte ? VORSCHLAEGE.BEZ_PLATTE : mat ? VORSCHLAEGE.BEZ_MATERIAL : t === ML.LFM ? VORSCHLAEGE.BEZ_LFM : VORSCHLAEGE.BEZ_HOLZ;
    f("v-bez").innerHTML = t === ML.TITEL ? "" : bez.map(x => `<option value="${esc(x)}">`).join("");
    f("f-bez").placeholder = t === ML.TITEL ? "z. B. Dachstuhl" : "z. B. " + bez[0];
    f("l-mat").textContent = platte ? "Plattenmaterial" : mat ? "Produkt / Material" : "Holzart / Qualität";
    const mats = [...new Set([...bekannt, ...(platte ? VORSCHLAEGE.PLATTEN : mat ? VORSCHLAEGE.MATERIALIEN : VORSCHLAEGE.HOLZARTEN)])];
    f("v-mat").innerHTML = mats.map(x => `<option value="${esc(x)}">`).join("");
    f("f-mat").placeholder = platte ? "z. B. OSB/3" : mat ? "z. B. Mineralwolle 140 mm" : "z. B. KVH NSi C24";
    vorschau();
  }

  /** Liest die Felder der gewählten Art (wie PositionDialog.lesen in der App); falsche Zahlen → {fehler: Feld}. */
  function lesen() {
    const t = typ();
    const x = { typ: t, bez: f("f-bez").value.trim() };
    if (t === ML.TITEL) { x.holzart = ""; x.bem = ""; return { x }; }
    const felder = { holz: ["f-stk", "f-l", "f-b", "f-h", "f-fb", "f-fh"], lfm: ["f-lfm", "f-b", "f-h", "f-fb", "f-fh"],
      platte: ["f-flL", "f-flB", "f-fl", "f-vs", "f-pl", "f-pb", "f-pd"], material: ["f-menge", "f-mvs", "f-pack"] }[t].concat(["f-preis"]);
    const w = {};
    for (const id of felder) {
      const v = zahl(f(id).value);
      if (Number.isNaN(v)) return { fehler: id };
      w[id] = v;
    }
    const setze = (k, v) => { if (v != null) x[k] = v; };
    x.holzart = f("f-mat").value.trim();
    x.bem = f("f-bem").value.trim();
    if (t === ML.HOLZ || t === ML.LFM) {
      if (t === ML.HOLZ) { setze("stk", w["f-stk"]); setze("l", w["f-l"]); }
      if (t === ML.LFM) setze("lfm", w["f-lfm"]);
      setze("b", w["f-b"]); setze("h", w["f-h"]); setze("fb", w["f-fb"]); setze("fh", w["f-fh"]);
    } else if (t === ML.PLATTE) {
      setze("l", w["f-pl"] == null ? null : w["f-pl"] / 100); setze("b", w["f-pb"]); setze("h", w["f-pd"] == null ? null : w["f-pd"] / 10);
      setze("fl", w["f-fl"]); setze("flL", w["f-flL"]); setze("flB", w["f-flB"]); setze("vs", w["f-vs"]);
    } else if (t === ML.MATERIAL) {
      setze("menge", w["f-menge"]);
      x.einh = leer(f("f-einh").value) ? "Stk" : ML.norm(f("f-einh").value);
      setze("vs", w["f-mvs"]); setze("pack", w["f-pack"]);
      if (!leer(f("f-packname").value)) x.packName = f("f-packname").value.trim();
    }
    setze("preis", w["f-preis"]);
    if (!leer(f("f-peinh").value)) x.preisEinh = f("f-peinh").value.trim();
    if (!leer(f("f-art").value)) x.artNr = f("f-art").value.trim();
    return { x };
  }

  /** Ergebnis-Zeile wie in der App („→ Du brauchst 74 Platten“). */
  function vorschau() {
    const e = f("ergebnis");
    const r = lesen();
    if (r.fehler || typ() === ML.TITEL) { e.textContent = ""; return; }
    const t = r.x;
    const zeilen = [];
    if (t.typ === ML.PLATTE) {
      const fl = ML.flaeche(t), vsp = t.vs || 0, n = ML.plattenAnzahl(t);
      zeilen.push(ML.fix(fl, 2) + " m²" + (vsp ? " + " + ML.num(vsp) + " % = " + ML.fix(fl * (1 + vsp / 100), 2) + " m²" : ""));
      if (n != null) zeilen.push("→ Du brauchst " + ML.fix(n, 0) + (n === 1 ? " Platte" : " Platten"));
      else zeilen.push(fl > 0 ? "Plattengröße eingeben → Anzahl wird berechnet" : "Fläche eingeben: Länge × Breite oder m²");
    } else if (t.typ === ML.MATERIAL) {
      const bed = ML.bedarf(t);
      if (bed == null) zeilen.push("Menge eingeben");
      else {
        const einh = ML.norm(t.einh);
        zeilen.push((t.vs ? ML.mengeText(t.menge) + " " + einh + " + " + ML.num(t.vs) + " % = " : "") + ML.mengeText(bed) + " " + einh);
        const pk = ML.packungen(t);
        if (pk != null) zeilen.push("→ Du brauchst " + ML.num(pk) + " " + (t.packName || "Packung") + " à " + ML.mengeText(t.pack) + " " + einh);
      }
    } else {
      zeilen.push("= " + ML.fix(ML.laufmeter(t), 2) + " m  ·  " + ML.fix(ML.volumen(t), 3) + " m³");
    }
    if (t.preis != null) {
      const bt = ML.betrag(t);
      zeilen.push(bt != null ? "Preis: " + ML.euro(bt) : "Preis: Einheit „" + ML.preisEinheit(t) + "“ passt nicht zur Menge");
    }
    f("f-peinh").placeholder = ML.preisEinheit(t) || "Einheit";
    e.textContent = zeilen.join("\n");
  }

  const zu = () => { dlg.close(); dlg.remove(); };
  f("f-typ").onchange = sichtbar;
  dlg.addEventListener("input", vorschau);
  f("f-abbrechen").onclick = zu;
  dlg.addEventListener("cancel", e => { e.preventDefault(); zu(); });
  if (!neu) f("f-weg").onclick = () => {
    if (!confirm("„" + (vorlage.bez || vorlage.holzart || "Position") + "“ löschen?")) return;
    zu();
    aendern(b, n => {
      const j = findePosition(n.positionen || [], vorlage, index);
      if (j < 0) return false;
      n.positionen.splice(j, 1);
    }, "Gelöscht");
  };
  dlg.querySelector("form").onsubmit = e => {
    e.preventDefault();
    dlg.querySelectorAll(".falsch").forEach(x => x.classList.remove("falsch"));
    const r = lesen();
    if (r.fehler) {
      f(r.fehler).classList.add("falsch");
      if (r.fehler === "f-preis") f("mehr").open = true;
      f(r.fehler).focus();
      f("fehlertext").textContent = "Bitte eine gültige Zahl eingeben (z. B. 7,5).";
      return;
    }
    if (r.x.typ === ML.TITEL && !r.x.bez) { f("fehlertext").textContent = "Bitte eine Überschrift eingeben."; f("f-bez").focus(); return; }
    zu();
    aendern(b, n => {
      const l = n.positionen = n.positionen || [];
      if (neu) { l.push(r.x); return; }
      const j = findePosition(l, vorlage, index);
      if (j < 0) return false;
      l[j] = r.x;
    }, neu ? "Hinzugefügt" : "Geändert");
  };
  sichtbar();
  dlg.showModal();
  f("f-bez").focus();
}

function csv(d) {
  const z = n => n == null ? "" : ML.fix(n, 3).replace(/\./g, "");
  const q = s => '"' + String(s == null ? "" : s).replace(/"/g, '""') + '"';
  const zeilen = [["Pos.", "Bestellen", "Bezeichnung", "Material/Maße", "Laufmeter m", "Volumen m³", "Preis €", "Preiseinheit", "Betrag €", "Art.-Nr."].map(q).join(";")];
  let nr = 0;
  for (const p of d.positionen || []) {
    if (p.typ === ML.TITEL) { zeilen.push(["", "", p.bez].map(q).join(";")); continue; }
    nr++;
    zeilen.push([nr, ML.bestellText(p), p.bez || p.holzart, ML.details(p).replace(/\n/g, " · "), z(ML.laufmeter(p) || null),
      z(ML.volumen(p) || null), z(p.preis), ML.preisEinheit(p), z(ML.betrag(p)), p.artNr || ""].map(q).join(";"));
  }
  const blob = new Blob(["﻿" + zeilen.join("\r\n")], { type: "text/csv;charset=utf-8" });
  const a = document.createElement("a");
  a.href = URL.createObjectURL(blob);
  a.download = ML.titel(d).replace(/[^A-Za-z0-9ÄÖÜäöüß_\- ]+/g, "_") + " – Materialliste.csv";
  document.body.appendChild(a);
  a.click();
  a.remove();
}

// ---------------------------------------------------------------- Aufgaben

function aufgaben(el, b) {
  const auf = b.daten.aufgaben || [];
  const gruppen = [];
  for (const a of auf) {
    const k = a.abschnitt || "";
    let g = gruppen.find(x => x.k === k);
    if (!g) { g = { k, l: [] }; gruppen.push(g); }
    g.l.push(a);
  }
  for (const g of gruppen) g.l.sort((x, y) => (x.erledigt - y.erledigt) || ((y.prio == null ? 1 : y.prio) - (x.prio == null ? 1 : x.prio)));
  const abschnitte = gruppen.map(g => g.k).filter(Boolean);
  el.innerHTML = `
    ${gruppen.length ? gruppen.map(g => `
      <div class="karte"><h2>${esc(g.k || "Allgemein")}</h2>
      ${g.l.map(a => `<div class="punkt ${a.erledigt ? "erledigt" : ""}">
        <input type="checkbox" ${a.erledigt ? "checked" : ""} data-k="${esc(g.k)}" data-t="${esc(a.text)}" aria-label="erledigt">
        <span class="prio prio-${a.prio == null ? 1 : a.prio}" title="${["Hat Zeit", "Normal", "Dringend"][a.prio == null ? 1 : a.prio]}"></span>
        <div class="text">${esc(a.text)}${a.heute ? '<span class="marke-heute">☀ heute</span>' : ""}${leer(a.info) ? "" : '<div class="info">' + esc(a.info) + "</div>"}</div>
        <button class="weg" data-k="${esc(g.k)}" data-t="${esc(a.text)}" title="Löschen" aria-label="Löschen">×</button>
      </div>`).join("")}</div>`).join("") : '<p class="hinweis">Noch keine Aufgaben.</p>'}
    <div class="karte nicht-drucken" style="margin-top:12px">
      <h2>＋ Neue Aufgabe</h2>
      <div class="neu-zeile">
        <input type="text" id="a-text" placeholder="Was ist zu tun?">
        <input type="text" id="a-abschnitt" placeholder="Abschnitt (optional)" list="abschnitte">
        <select id="a-prio"><option value="2">🔴 Dringend</option><option value="1" selected>🟡 Normal</option><option value="0">🟢 Hat Zeit</option></select>
        <button class="knopf voll" id="a-neu">Hinzufügen</button>
      </div>
      <datalist id="abschnitte">${abschnitte.map(x => `<option value="${esc(x)}">`).join("")}</datalist>
    </div>`;
  const finde = (n, k, t) => (n.aufgaben || []).find(a => (a.abschnitt || "") === k && a.text === t);
  el.querySelectorAll("input[data-t]").forEach(c => c.onchange = () => aendern(b, n => {
    const a = finde(n, c.dataset.k, c.dataset.t);
    if (!a) return false;
    a.erledigt = c.checked;
  }, c.checked ? "Erledigt" : "Wieder offen"));
  el.querySelectorAll("button.weg").forEach(x => x.onclick = () => {
    if (!confirm("Aufgabe „" + x.dataset.t + "“ löschen?")) return;
    aendern(b, n => {
      const a = finde(n, x.dataset.k, x.dataset.t);
      if (!a) return false;
      n.aufgaben.splice(n.aufgaben.indexOf(a), 1);
    }, "Aufgabe gelöscht");
  });
  const neu = () => {
    const t = $("#a-text").value.trim();
    if (!t) { $("#a-text").focus(); return; }
    const a = { text: t, abschnitt: $("#a-abschnitt").value.trim(), info: "", erledigt: false, prio: Number($("#a-prio").value), heute: false };
    aendern(b, n => { (n.aufgaben = n.aufgaben || []).push(a); }, "Aufgabe hinzugefügt");
  };
  $("#a-neu").onclick = neu;
  $("#a-text").onkeydown = e => { if (e.key === "Enter") neu(); };
}

// ---------------------------------------------------------------- Werkzeug

function werkzeug(el, b) {
  const wz = (b.daten.werkzeug || []).slice().sort((x, y) => (x.eingepackt - y.eingepackt) || ((y.prio == null ? 1 : y.prio) - (x.prio == null ? 1 : x.prio)));
  el.innerHTML = `<div class="karte">${wz.length ? wz.map(w => `
    <div class="punkt ${w.eingepackt ? "erledigt" : ""}">
      <input type="checkbox" ${w.eingepackt ? "checked" : ""} data-n="${esc(w.name)}" aria-label="eingepackt">
      <span class="prio prio-${w.prio == null ? 1 : w.prio}"></span>
      <div class="text">${w.anzahl != null ? "<b>" + ML.num(w.anzahl) + " ×</b> " : ""}${esc(w.name)}${leer(w.bemerkung) ? "" : '<div class="info">' + esc(w.bemerkung) + "</div>"}</div>
    </div>`).join("") : '<p class="hinweis">Noch kein Werkzeug eingetragen.</p>'}</div>`;
  el.querySelectorAll("input[data-n]").forEach(c => c.onchange = () => aendern(b, n => {
    const w = (n.werkzeug || []).find(x => x.name === c.dataset.n);
    if (!w) return false;
    w.eingepackt = c.checked;
  }, c.checked ? "Eingepackt" : "Nicht mehr eingepackt"));
}

// ---------------------------------------------------------------- Berichte

function berichtKurz(r) {
  const a = ML.art(r);
  if (a === "abnahme") {
    const offen = (r.maengel || []).filter(m => !m.erledigt).length;
    return (r.abnahmeArt || "Gesamtabnahme") + ": " + (r.ergebnis || OHNE_MAENGEL) + (ML.mitMaengeln(r) ? " · " + offen + " offene Mängel" : "");
  }
  if (a === "doku") return leer(r.eintrag) ? "noch nichts eingetragen" : String(r.eintrag).replace(/\n/g, " ").slice(0, 120);
  const t = [ML.dauer(ML.summeMin(r)) + " h"];
  if ((r.material || []).length) t.push(r.material.length + " Material");
  return t.join(" · ");
}

function unterschriftStatus(r) {
  if (ML.art(r) === "abnahme") {
    const n = (r.unterschrift ? 1 : 0) + (r.unterschrift2 ? 1 : 0);
    return n === 2 ? ['<span class="status ok">✍ beide unterschrieben</span>'] : n === 1 ? ['<span class="status offen">✍ eine Unterschrift fehlt</span>'] : ['<span class="status offen">○ nicht unterschrieben</span>'];
  }
  if (r.unterschrift) return ['<span class="status ok">✍ unterschrieben' + (r.unterzeichner ? " von " + esc(r.unterzeichner) : "") + "</span>"];
  return ML.art(r) === "doku" ? [""] : ['<span class="status offen">○ nicht unterschrieben</span>'];
}

function feld(label, wert) {
  return leer(wert) ? "" : `<div class="feld"><div class="label">${esc(label)}</div><div class="wert">${esc(wert)}</div></div>`;
}

function berichte(el, b) {
  const alle = (b.daten.rapporte || []).slice().sort((x, y) => (y.tag || 0) - (x.tag || 0));
  const l = alle.filter(r => Z.filter === "alle" || ML.art(r) === Z.filter);
  const chips = [["alle", "Alle"], ["rapport", "📋 Rapporte"], ["doku", "📷 Bautagebuch"], ["abnahme", "✅ Abnahmen"]];
  el.innerHTML = `
    <div class="chips nicht-drucken">${chips.map(([k, n]) => `<button class="chip ${Z.filter === k ? "an" : ""}" data-f="${k}">${n}</button>`).join("")}</div>
    ${l.length ? l.map(r => `
      <details class="karte bericht" data-id="${esc(r.id)}" ${Z.offen.has(r.id) ? "open" : ""}>
        <summary><span>${ML.symbol(ML.art(r))}</span><span class="wann">${esc(r.titel)} · ${datumKurz(r.tag)}</span>
          <span class="was">${esc(berichtKurz(r))}</span>${unterschriftStatus(r)[0]}</summary>
        <div style="margin-top:12px">${berichtInhalt(r)}</div>
      </details>`).join("") : '<p class="hinweis">Keine Berichte.</p>'}`;
  el.querySelectorAll(".chip").forEach(c => c.onclick = () => { Z.filter = c.dataset.f; render(); });
  el.querySelectorAll("details.bericht").forEach(dt => {
    dt.addEventListener("toggle", () => {
      if (dt.open) { Z.offen.add(dt.dataset.id); nachladen(b, dt); } else Z.offen.delete(dt.dataset.id);
    });
    if (dt.open) nachladen(b, dt);
  });
  el.querySelectorAll("input[data-m]").forEach(c => c.onchange = () => mangelSetzen(b, c.dataset.r, Number(c.dataset.m), c.checked));
}

function berichtInhalt(r) {
  const a = ML.art(r);
  let h = "";
  if (a === "rapport" && (r.zeiten || []).length) {
    h += `<h2>Zeiten · ${ML.dauer(ML.summeMin(r))} h</h2><div class="tabelle-rahmen"><table><thead><tr><th>Mitarbeiter</th><th>Leistung</th>
      <th class="r">Start</th><th class="r">Ende</th><th class="r">Pause</th><th class="r">Gesamt</th></tr></thead><tbody>
      ${r.zeiten.map(z => `<tr><td>${esc(z.mitarbeiter)}</td><td>${esc(z.leistung)}</td><td class="r">${ML.uhr(z.von || 0)}</td>
      <td class="r">${ML.uhr(z.bis || 0)}</td><td class="r">${ML.dauer(z.pause || 0)}</td><td class="r">${ML.dauer(ML.nettoMin(z))}</td></tr>`).join("")}
      </tbody></table></div>`;
  }
  if (a === "rapport" && (r.material || []).length) {
    h += `<h2 style="margin-top:14px">Material</h2><div class="tabelle-rahmen"><table><thead><tr><th class="r">Menge</th><th>Einheit</th><th>Bezeichnung</th></tr></thead><tbody>
      ${r.material.map(m => `<tr><td class="r">${m.menge == null ? "" : ML.mengeText(m.menge)}</td><td>${esc(m.einheit)}</td>
      <td>${esc(m.bezeichnung)}${leer(m.details) ? "" : '<div class="klein">' + esc(m.details) + "</div>"}</td></tr>`).join("")}</tbody></table></div>`;
  }
  if (a === "abnahme") {
    const zr = [r.leistungVon ? datum(r.leistungVon) : "", r.leistungBis ? datum(r.leistungBis) : ""].filter(Boolean).join(" bis ");
    h += feld("Art der Abnahme", r.abnahmeArt) + feld("Leistung erbracht", zr) + feld("Ergebnis", r.ergebnis || OHNE_MAENGEL);
    if (ML.mitMaengeln(r)) {
      h += `<div class="feld"><div class="label">Mängel${r.frist ? " – beseitigen bis " + datum(r.frist) : ""}</div>` +
        ((r.maengel || []).length ? r.maengel.map((m, i) => `<div class="punkt ${m.erledigt ? "erledigt" : ""}">
          <input type="checkbox" ${m.erledigt ? "checked" : ""} data-r="${esc(r.id)}" data-m="${i}" aria-label="beseitigt">
          <div class="text">${i + 1}. ${esc(m.text)}</div></div>`).join("") : '<p class="leer">keine eingetragen</p>') + "</div>";
    }
  }
  const wetter = [r.wetter ? String(r.wetter).replace(/^[^\p{L}]+/u, "") : "", leer(r.temperatur) ? "" : r.temperatur + " °C"].filter(Boolean).join(", ");
  h += feld("Wetter", wetter) + feld("Anwesende Mitarbeiter", ML.anwesende(r)) + feld("Ausgeführte Arbeiten", r.eintrag) +
    feld("Behinderungen / Erschwernisse", r.behinderungen) + feld("Bemerkung", r.bemerkung);
  const sig = [];
  if (r.unterschrift) sig.push((a === "abnahme" ? "Auftraggeber" : "Unterschrieben") + (r.unterzeichner ? ": " + r.unterzeichner : "") + (r.unterschriebenAm ? " am " + new Date(r.unterschriebenAm).toLocaleString("de-DE") : ""));
  if (r.unterschrift2) sig.push("Auftragnehmer" + (r.unterzeichner2 ? ": " + r.unterzeichner2 : "") + (r.unterschrieben2Am ? " am " + new Date(r.unterschrieben2Am).toLocaleString("de-DE") : ""));
  h += feld("Unterschrift", sig.join("\n"));
  if ((r.fotos || []).length) h += `<div class="feld"><div class="label">Fotos</div><div class="fotos">${r.fotos.map(f => `<a data-foto="${esc(f)}" target="_blank" rel="noopener"><img alt="Foto"></a>`).join("")}</div></div>`;
  h += `<div class="knoepfe nicht-drucken" style="margin-top:12px" data-pdf></div>`;
  return h;
}

/** Fotos und PDF-Link erst beim Aufklappen laden. */
async function nachladen(b, dt) {
  const r = (b.daten.rapporte || []).find(x => x.id === dt.dataset.id);
  if (!r) return;
  if (b.lokal) {
    const f = dt.querySelector(".fotos");
    if (f) f.outerHTML = '<p class="leer">' + r.fotos.length + (r.fotos.length === 1 ? " Foto" : " Fotos") + " – zu sehen in der Handy-App und in der PDF.</p>";
    const box = dt.querySelector("[data-pdf]");
    if (box) box.innerHTML = '<span class="leer">Die PDF erstellst du in der Handy-App („📤 PDF senden“).</span>';
    return;
  }
  for (const a of dt.querySelectorAll("a[data-foto]")) {
    const name = a.dataset.foto;
    try {
      if (Z.bilder[name] === undefined) {
        const it = await datei(b, DATEIEN + name);
        Z.bilder[name] = it ? it["@microsoft.graph.downloadUrl"] : "";
      }
      if (Z.bilder[name]) { a.href = Z.bilder[name]; a.querySelector("img").src = Z.bilder[name]; }
    } catch (e) { /* Foto fehlt */ }
  }
  const box = dt.querySelector("[data-pdf]");
  const pfad = ML.pdfOrdner(r) + "/" + ML.pdfName(b.daten, r);
  try {
    if (Z.pdfs[pfad] === undefined) {
      const it = await datei(b, pfad);
      Z.pdfs[pfad] = it ? it.webUrl : "";
    }
    box.innerHTML = Z.pdfs[pfad] ? `<a class="knopf klein" href="${esc(Z.pdfs[pfad])}" target="_blank" rel="noopener">📄 PDF im Team öffnen</a>`
      : '<span class="leer">PDF ist noch nicht im Team – in der Handy-App „📤 PDF senden → 👥 Ins Team“.</span>';
  } catch (e) { box.innerHTML = ""; }
}

// ================================================================ Start

window.addEventListener("hashchange", zeigen);

(async function start() {
  // Einrichtungs-Link: ?client=…&mandant=… übernimmt die Werte direkt
  const q = new URLSearchParams(location.search);
  if (q.has("client") && /^[0-9a-fA-F-]{36}$/.test(q.get("client"))) {
    speicher.set("ml_konfig", { clientId: q.get("client"), mandant: q.get("mandant") || "" });
    history.replaceState(null, "", redirectUri() + location.hash);
  }
  try {
    await rueckkehr();
  } catch (e) {
    $("#inhalt").innerHTML = '<div class="karte fehler"><h2>Anmeldung fehlgeschlagen</h2><p>' + esc(e.message) +
      "</p><p class=\"leer\">Häufige Ursachen: Die Adresse dieser Seite fehlt in der App-Registrierung als Single-Page-Anwendung, " +
      'oder der Admin hat die Berechtigungen noch nicht freigegeben.</p><div class="knoepfe"><button class="knopf voll" id="anmelden">Nochmal anmelden</button></div></div>';
    $("#anmelden").onclick = anmelden;
    kopfRechts();
    return;
  }
  zeigen();
})();
