/*
 * Test der Web-App (docs/) im echten Browser gegen ein nachgebautes Microsoft 365.
 * Beispieldaten: build/web-beispiel aus RapportTest (genau das Format der Handy-App).
 *
 *   cd zimmerer-holzliste && gradle testDebugUnitTest --tests '*RapportTest'   # erzeugt die Beispieldaten
 *   node web-test/webtest.js                                                    # braucht Playwright + Chromium
 *
 * Bilder landen in web-test/bilder/.
 */
"use strict";
const { chromium } = require("playwright");
const http = require("http");
const fs = require("fs");
const path = require("path");
const assert = require("assert");

const DOCS = path.join(__dirname, "..", "..", "docs");
const BEISPIEL = path.join(__dirname, "..", "app", "build", "web-beispiel");
const BILDER = path.join(__dirname, "bilder");
const CLIENT = "11111111-2222-3333-4444-555555555555";

// ---------------------------------------------------------------- statischer Server für docs/
function server() {
  const typen = { ".html": "text/html", ".js": "text/javascript", ".css": "text/css" };
  return new Promise(ok => {
    const s = http.createServer((req, res) => {
      const p = new URL(req.url, "http://x").pathname;
      const f = path.join(DOCS, p === "/" ? "index.html" : p);
      if (!f.startsWith(DOCS) || !fs.existsSync(f)) { res.writeHead(404); res.end(); return; }
      res.writeHead(200, { "Content-Type": typen[path.extname(f)] || "application/octet-stream" });
      res.end(fs.readFileSync(f));
    });
    s.listen(0, () => ok(s));
  });
}

// ---------------------------------------------------------------- nachgebautes Microsoft 365
const m365 = {
  teams: [{ id: "t1", displayName: "EFH Huber – Dachstuhl", description: "Satteldach 38°" },
          { id: "t2", displayName: "Carport Maier", description: null }],
  dateien: new Map(), // "drive/pfad" → {id, eTag, version, daten}
  puts: [],
  naechste: 1,
  ablegen(key, daten) {
    let e = this.dateien.get(key);
    if (!e) { e = { id: "id" + this.naechste++, version: 0 }; this.dateien.set(key, e); }
    e.version++;
    e.eTag = `"{${e.id}},${e.version}"`;
    e.daten = Buffer.from(daten);
    return e;
  },
  json(key) { return JSON.parse(this.dateien.get(key).daten.toString("utf8")); },
};
m365.ablegen("drive-t1/Materialliste/baustelle.json", fs.readFileSync(path.join(BEISPIEL, "baustelle.json")));
for (const f of fs.readdirSync(BEISPIEL)) if (f !== "baustelle.json") m365.ablegen("drive-t1/Materialliste/Dateien/" + f, fs.readFileSync(path.join(BEISPIEL, f)));
m365.ablegen("drive-t1/Rapporte/Rapport_2026-11-25_EFH_Huber_Dachstuhl.pdf", "%PDF-1.4");
const DATEI = "drive-t1/Materialliste/baustelle.json";

const CORS = { "Access-Control-Allow-Origin": "*", "Access-Control-Allow-Headers": "*", "Access-Control-Allow-Methods": "GET,PUT,POST,OPTIONS",
  "Access-Control-Expose-Headers": "*" };
const antwort = (route, status, obj) => route.fulfill({ status, headers: { ...CORS, "Content-Type": "application/json" }, body: JSON.stringify(obj) });
const meta = (e, pfad) => ({ id: e.id, eTag: e.eTag, name: pfad.split("/").pop(), size: e.daten.length,
  webUrl: "https://firma.sharepoint.com/" + encodeURI(pfad), "@microsoft.graph.downloadUrl": "https://download.fake/" + e.id });

async function graphRoute(route) {
  const req = route.request();
  if (req.method() === "OPTIONS") return route.fulfill({ status: 204, headers: CORS });
  assert.strictEqual(req.headers()["authorization"], "Bearer zugang");
  const u = new URL(req.url());
  const p = decodeURIComponent(u.pathname.replace("/v1.0", ""));
  if (p === "/me") return antwort(route, 200, { displayName: "Max Muster" });
  if (p === "/me/joinedTeams") return antwort(route, 200, { value: m365.teams });
  let m;
  if ((m = p.match(/^\/teams\/([^/]+)\/primaryChannel$/))) return antwort(route, 200, { id: "kanal-" + m[1] });
  if ((m = p.match(/^\/teams\/([^/]+)\/channels\/[^/]+\/filesFolder$/))) return antwort(route, 200, { id: "ordner-" + m[1], parentReference: { driveId: "drive-" + m[1] } });
  if ((m = p.match(/^\/drives\/([^/]+)\/items\/[^/:]+:\/(.+?)(:\/content)?$/))) {
    const key = m[1] + "/" + m[2];
    const e = m365.dateien.get(key);
    if (req.method() === "GET") return e ? antwort(route, 200, meta(e, m[2])) : antwort(route, 404, { error: { message: "itemNotFound" } });
    if (req.method() === "PUT" && m[3]) {
      const ifMatch = req.headers()["if-match"];
      if (e && u.search.includes("conflictBehavior=fail")) return antwort(route, 409, { error: { message: "nameAlreadyExists" } });
      if (ifMatch && (!e || e.eTag !== ifMatch)) return antwort(route, 412, { error: { message: "resourceModified" } });
      m365.puts.push({ key, ifMatch });
      return antwort(route, 200, meta(m365.ablegen(key, req.postDataBuffer()), m[2]));
    }
  }
  throw new Error("Unerwartet: " + req.method() + " " + req.url());
}

async function loginRoute(route) {
  const u = new URL(route.request().url());
  if (u.pathname.endsWith("/authorize")) {
    assert.strictEqual(u.searchParams.get("client_id"), CLIENT);
    assert.strictEqual(u.searchParams.get("code_challenge_method"), "S256");
    assert.ok(u.searchParams.get("code_challenge").length >= 43);
    const zurueck = u.searchParams.get("redirect_uri") + "?code=abc&state=" + encodeURIComponent(u.searchParams.get("state"));
    return route.fulfill({ status: 302, headers: { Location: zurueck } });
  }
  if (route.request().method() === "OPTIONS") return route.fulfill({ status: 204, headers: CORS });
  const body = new URLSearchParams(route.request().postData());
  assert.ok(["authorization_code", "refresh_token"].includes(body.get("grant_type")));
  if (body.get("grant_type") === "authorization_code") assert.ok(body.get("code_verifier").length >= 43);
  return antwort(route, 200, { access_token: "zugang", refresh_token: "erneuern", expires_in: 3600 });
}

async function downloadRoute(route) {
  const id = new URL(route.request().url()).pathname.slice(1);
  for (const e of m365.dateien.values()) if (e.id === id) return route.fulfill({ status: 200, headers: CORS, body: e.daten });
  return route.fulfill({ status: 404, headers: CORS });
}

// ---------------------------------------------------------------- Ablauf
(async () => {
  fs.mkdirSync(BILDER, { recursive: true });
  const s = await server();
  const basis = "http://localhost:" + s.address().port + "/";
  const browser = await chromium.launch();
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 860 }, locale: "de-DE", timezoneId: "Europe/Berlin" });
  await ctx.route("https://login.microsoftonline.com/**", loginRoute);
  await ctx.route("https://graph.microsoft.com/**", graphRoute);
  await ctx.route("https://download.fake/**", downloadRoute);
  const page = await ctx.newPage();
  const fehler = [];
  page.on("pageerror", e => fehler.push(e.message));
  // erwartete HTTP-Antworten (412 beim gleichzeitigen Speichern, 404 für fehlende Dateien) zählen nicht
  page.on("console", m => { if (m.type() === "error" && !m.text().startsWith("Failed to load resource")) fehler.push(m.text()); });
  const bild = async (name, opt = {}) => page.screenshot({ path: path.join(BILDER, name + ".png"), fullPage: true, ...opt });

  // 1. Einrichtungs-Link mit Client-ID → Anmeldeseite → Microsoft → zurück → Teams
  await page.goto(basis + "?client=" + CLIENT);
  await page.getByText("Mit Microsoft anmelden", { exact: false }).first().waitFor();
  await bild("web_0_anmelden");
  await page.click("#anmelden");
  await page.getByRole("heading", { name: "Meine Teams" }).waitFor();
  assert.ok(!page.url().includes("code="), "Code bleibt nicht in der Adresse stehen");
  await page.getByText("Max Muster").waitFor();
  await bild("web_1_teams");

  // 2. Baustelle öffnen: Übersicht, Summen wie am Handy
  await page.click("text=EFH Huber – Dachstuhl");
  await page.getByRole("heading", { name: "EFH Huber – Dachstuhl" }).waitFor();
  const uebersicht = await page.textContent("#ansicht");
  assert.ok(uebersicht.includes("993,10 €"), "Summe wie in der Handy-App");
  assert.ok(uebersicht.includes("Anschluss Dampfbremse"), "offener Mangel sichtbar");
  await bild("web_2_uebersicht");

  // 3. Material: Bestellmengen und Summe identisch mit der App
  await page.click("text=📦 Material");
  await page.waitForSelector("table");
  const mat = await page.textContent("#ansicht");
  for (const t of ["24 Stk", "620 m", "7 Platten", "20 Paket", "993,10 €", "Holz "]) assert.ok(mat.includes(t), "Material enthält " + t);
  await bild("web_3_material");

  // 4. Aufgabe hinzufügen → mit If-Match ins Team
  await page.click("text=✅ Aufgaben");
  await page.fill("#a-text", "Gerüst Giebelseite abbauen");
  await page.selectOption("#a-prio", "2");
  await page.click("#a-neu");
  await page.getByText("Aufgabe hinzugefügt").waitFor();
  let d = m365.json(DATEI);
  assert.ok(d.aufgaben.some(a => a.text === "Gerüst Giebelseite abbauen" && a.prio === 2 && a.erledigt === false));
  assert.ok(m365.puts[m365.puts.length - 1].ifMatch, "speichert nur auf bekanntem Stand");

  // 5. Gleichzeitig speichert ein Handy (anderer Stand) → Web lädt neu und wendet das Abhaken erneut an
  const fremd = m365.json(DATEI);
  fremd.notiz = "Vom Handy: Kran am Freitag";
  m365.ablegen(DATEI, JSON.stringify(fremd));
  await page.locator('input[data-t="Gerüst Giebelseite abbauen"]').check();
  await page.getByText("Erledigt – ist jetzt").waitFor();
  d = m365.json(DATEI);
  assert.strictEqual(d.notiz, "Vom Handy: Kran am Freitag", "Handy-Änderung bleibt erhalten");
  assert.ok(d.aufgaben.find(a => a.text === "Gerüst Giebelseite abbauen").erledigt, "Abhaken trotzdem gespeichert");
  await bild("web_4_aufgaben");

  // 6. Werkzeug abhaken
  await page.click("text=🔧 Werkzeug");
  await page.locator('input[data-n="Abbundsäge"]').check();
  await page.getByText("Eingepackt – ist jetzt").waitFor();
  assert.ok(m365.json(DATEI).werkzeug.find(w => w.name === "Abbundsäge").eingepackt);

  // 7. Berichte: Abnahme aufklappen, Fotos + PDF-Link, Mangel abhaken
  await page.click("text=📋 Berichte");
  await page.click("summary:has-text('Abnahmeprotokoll')");
  await page.locator('input[data-m="0"]').check();
  await page.getByText("Mangel als beseitigt markiert").waitFor();
  assert.ok(m365.json(DATEI).rapporte.find(r => r.art === "abnahme").maengel[0].erledigt);
  assert.ok(await page.locator("details[open] summary:has-text('Abnahmeprotokoll')").count() === 1, "bleibt aufgeklappt");
  await page.click("summary:has-text('Rapport')");
  await page.waitForSelector("a:has-text('PDF im Team öffnen')");
  await page.waitForFunction(() => [...document.querySelectorAll("details[open] .fotos img")].some(i => i.complete && i.naturalWidth > 0));
  await bild("web_5_berichte");

  // 8. Gespeicherte Datei ist weiterhin vollständig (Handy kann sie lesen)
  d = m365.json(DATEI);
  const original = JSON.parse(fs.readFileSync(path.join(BEISPIEL, "baustelle.json"), "utf8"));
  assert.strictEqual(d.positionen.length, original.positionen.length);
  assert.strictEqual(d.rapporte.length, original.rapporte.length);
  assert.deepStrictEqual(Object.keys(d.rapporte[0]).sort(), Object.keys(original.rapporte[0]).sort());
  assert.ok(d.geaendert > original.geaendert);

  // 9. Handy-Breite
  await page.setViewportSize({ width: 390, height: 844 });
  const zuBreit = async () => page.evaluate(() => {
    const o = [];
    document.querySelectorAll("body *").forEach(e => { const r = e.getBoundingClientRect(); if (r.right > innerWidth + 1 && !e.closest(".tabelle-rahmen,.reiter")) o.push(e.tagName + "." + e.className + " " + Math.round(r.right)); });
    return { breite: document.documentElement.scrollWidth, o: o.slice(0, 8) };
  });
  for (const r of ["Übersicht", "📦 Material", "✅ Aufgaben", "📋 Berichte"]) {
    await page.click("nav.reiter >> text=" + r);
    await page.waitForTimeout(100);
    const z = await zuBreit();
    assert.ok(z.breite <= 390, r + ": Seite breiter als das Handy: " + JSON.stringify(z));
  }
  await page.click("nav.reiter >> text=Übersicht");
  await page.waitForTimeout(3700); // Meldung ausblenden lassen
  await bild("web_6_handybreite");

  // 10. Team ohne Baustelle
  await page.setViewportSize({ width: 1280, height: 860 });
  await page.goto(basis + "#/team/t2");
  await page.getByText("Im Team gibt es noch keine Baustelle").waitFor();

  assert.deepStrictEqual(fehler, [], "keine Fehler in der Konsole");
  await browser.close();
  s.close();
  console.log("Web-App: alle Prüfungen bestanden");
})().catch(e => { console.error(e); process.exit(1); });
