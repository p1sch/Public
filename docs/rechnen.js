/*
 * Materialliste – Rechenlogik für die Web-App.
 * 1:1 aus der Android-App übernommen (Position.java, Einheit.java, Fmt.java, EditorActivity.details/werte),
 * damit PC und Handy dieselben Mengen, Plattenanzahlen und Beträge zeigen.
 * Eine Position ist das JSON-Objekt aus baustelle.json (Schlüssel: typ, bez, stk, b, h, l, lfm, fl, flL, flB, vs,
 * fb, fh, holzart, bem, menge, einh, pack, packName, preis, preisEinh, artNr).
 */
"use strict";

const ML = (() => {
  const HOLZ = "holz", LFM = "lfm", PLATTE = "platte", MATERIAL = "material", TITEL = "titel";

  // ------------------------------------------------------------------ Einheiten

  function norm(s) {
    if (s == null) return "";
    const t = String(s).trim();
    const u = t.toUpperCase().replace(/\./g, "");
    switch (u) {
      case "": return "";
      case "M2": case "QM": case "M²": case "QUADRATMETER": return "m²";
      case "M3": case "CBM": case "M³": case "KUBIKMETER": return "m³";
      case "M": case "LFM": case "LFDM": case "LFD.M": case "LM": case "MTR": case "METER": return "m";
      case "STK": case "ST": case "STCK": case "STÜCK": case "STUECK": case "PCE": case "PCS": case "PLATTE": case "PLATTEN": return "Stk";
      case "KG": case "KILO": return "kg";
      case "T": case "TO": case "TONNE": return "t";
      case "L": case "LTR": case "LITER": return "l";
      case "SA": case "SAC": case "SCK": case "SACK": return "Sack";
      case "PAK": case "PAKET": case "PA": case "PCK": case "PACK": return "Paket";
      case "RO": case "ROL": case "ROLLE": case "RLL": return "Rolle";
      case "EI": case "EIM": case "EIMER": return "Eimer";
      case "KAR": case "KRT": case "KTN": case "KARTON": return "Karton";
      case "PAL": case "PALETTE": return "Palette";
      case "BD": case "BUND": return "Bund";
      case "SAT": case "SATZ": case "SET": return "Satz";
      case "DS": case "DOSE": return "Dose";
      case "KARTUSCHE": case "KTS": return "Kartusche";
      default: return t;
    }
  }

  function ganzzahlig(e) {
    e = norm(e);
    return !(e === "m" || e === "m²" || e === "m³" || e === "kg" || e === "t" || e === "l" || e === "");
  }

  // ------------------------------------------------------------------ Zahlen (deutsches Format)

  const fmtCache = {};
  function nf(min, max, gruppe) {
    const k = min + "/" + max + "/" + gruppe;
    if (!fmtCache[k]) fmtCache[k] = new Intl.NumberFormat("de-DE", { minimumFractionDigits: min, maximumFractionDigits: max, useGrouping: gruppe });
    return fmtCache[k];
  }
  /** Feste Nachkommastellen mit Tausenderpunkt: 1.234,50 */
  const fix = (d, n) => nf(n, n, true).format(d);
  /** Ohne überflüssige Nullen, ohne Tausenderpunkt: 12 / 7,5 / 4,25 */
  const num = d => d == null ? "" : nf(0, 3, false).format(d);
  /** Menge: höchstens 2 Nachkommastellen: 620 / 12,6 */
  const mengeText = d => nf(0, 2, true).format(d);
  const euro = d => fix(d, 2) + " €";
  const querschnitt = (b, h) => (b == null && h == null) ? "" : (b == null ? "–" : num(b)) + "/" + (h == null ? "–" : num(h));

  // ------------------------------------------------------------------ Positionen

  const v = d => d == null ? 0 : d;
  const ist = (p, t) => (p.typ || HOLZ) === t;
  const leer = s => s == null || String(s).trim() === "";

  function hatFlaechenEingabe(p) {
    return ist(p, PLATTE) && ((p.flL != null && p.flB != null) || p.fl != null);
  }
  function flaeche(p) {
    if (!ist(p, PLATTE)) return 0;
    if (p.flL != null && p.flB != null) return p.flL * p.flB;
    if (p.fl != null) return p.fl;
    return v(p.stk) * v(p.l) * v(p.b) / 100.0;
  }
  function plattenFlaecheEinzeln(p) {
    return ist(p, PLATTE) && p.l != null && p.b != null ? p.l * p.b / 100.0 : 0;
  }
  function plattenAnzahl(p) {
    if (!ist(p, PLATTE)) return null;
    if (!hatFlaechenEingabe(p)) return p.stk == null ? null : p.stk;
    const pf = plattenFlaecheEinzeln(p);
    if (pf <= 0) return null;
    return Math.ceil(flaeche(p) * (1 + v(p.vs) / 100.0) / pf - 1e-9);
  }
  function plattenFlaecheGesamt(p) {
    const n = plattenAnzahl(p);
    return n == null ? 0 : n * plattenFlaecheEinzeln(p);
  }
  function bedarf(p) {
    if (!ist(p, MATERIAL) || p.menge == null) return null;
    let b = Math.round(p.menge * (1 + v(p.vs) / 100.0) * 1e6) / 1e6;
    if (ganzzahlig(p.einh) && p.pack == null) b = Math.ceil(b - 1e-9);
    return b;
  }
  const hatPackung = p => ist(p, MATERIAL) && p.pack != null && p.pack > 0;
  function packungen(p) {
    const b = bedarf(p);
    if (b == null || !hatPackung(p)) return null;
    return Math.ceil(b / p.pack - 1e-9);
  }
  const packName = p => leer(p.packName) ? "Packung" : String(p.packName).trim();

  /** Was bestellt wird, z. B. „3 Paket“, „74 Platten“, „620 m“, „24 Stk“. */
  function bestellText(p) {
    if (ist(p, MATERIAL)) {
      const pk = packungen(p);
      if (pk != null) return num(pk) + " " + packName(p);
      const b = bedarf(p);
      return b == null ? "" : mengeText(b) + " " + norm(p.einh);
    }
    if (ist(p, PLATTE)) {
      const n = plattenAnzahl(p);
      return n == null ? "" : num(n) + (n === 1 ? " Platte" : " Platten");
    }
    if (ist(p, LFM)) return p.lfm == null ? "" : mengeText(p.lfm) + " m";
    if (ist(p, HOLZ)) return p.stk == null ? "" : num(p.stk) + " Stk";
    return "";
  }

  function preisEinheit(p) {
    if (!leer(p.preisEinh)) return norm(p.preisEinh);
    switch (p.typ || HOLZ) {
      case PLATTE: return "m²";
      case MATERIAL: return hatPackung(p) ? packName(p) : norm(p.einh);
      case HOLZ: case LFM: return "m";
      default: return "";
    }
  }

  function laufmeter(p) {
    if (ist(p, HOLZ)) return v(p.stk) * v(p.l);
    if (ist(p, LFM)) return v(p.lfm);
    return 0;
  }

  function mengeIn(p, einh) {
    const u = norm(einh);
    switch (p.typ || HOLZ) {
      case MATERIAL: {
        const pk = packungen(p);
        if (pk != null && u.toLowerCase() === packName(p).toLowerCase()) return pk;
        if (u.toLowerCase() === norm(p.einh).toLowerCase() || u === "") return pk != null ? pk * p.pack : bedarf(p);
        if (pk != null && u === "Stk") return pk;
        return null;
      }
      case PLATTE:
        if (u === "Stk") return plattenAnzahl(p);
        if (u === "m²") return plattenAnzahl(p) != null && plattenFlaecheEinzeln(p) > 0
          ? plattenFlaecheGesamt(p) : flaeche(p) * (1 + v(p.vs) / 100.0);
        if (u === "m³") return volumen(p);
        return null;
      case HOLZ: case LFM:
        if (u === "m") return laufmeter(p);
        if (u === "m³") return volumen(p);
        if (u === "Stk") return ist(p, HOLZ) ? (p.stk == null ? null : p.stk) : null;
        return null;
      default:
        return null;
    }
  }

  function betrag(p) {
    if (p.preis == null || ist(p, TITEL)) return null;
    const m = mengeIn(p, preisEinheit(p));
    return m == null ? null : m * p.preis;
  }

  function volumen(p) {
    if (ist(p, TITEL)) return 0;
    if (ist(p, MATERIAL)) {
      if (norm(p.einh) !== "m³") return 0;
      const m = mengeIn(p, "m³");
      return m == null ? 0 : m;
    }
    if (ist(p, PLATTE)) {
      const n = plattenAnzahl(p);
      if (n != null && plattenFlaecheEinzeln(p) > 0) return n * plattenFlaecheEinzeln(p) * v(p.h) / 100.0;
      return flaeche(p) * (1 + v(p.vs) / 100.0) * v(p.h) / 100.0;
    }
    return laufmeter(p) * v(p.b) * v(p.h) / 10000.0;
  }

  /** Zeile unter der Bezeichnung: Material und Maße in Klartext (wie in der App). */
  function details(p) {
    const teile = [];
    const ha = p.holzart || "";
    if (ha && ha !== (p.bez || "")) teile.push(ha);
    let mass = "";
    if (ist(p, HOLZ) || ist(p, LFM)) {
      if (p.b != null || p.h != null) mass = num(p.b) + " × " + num(p.h) + " cm";
      if (ist(p, HOLZ) && p.l != null) mass += (mass ? ", " : "") + fix(p.l, 2) + " m lang";
      if (p.fb != null || p.fh != null) mass += " (fertig " + querschnitt(p.fb, p.fh) + ")";
    } else if (ist(p, PLATTE)) {
      if (p.l != null && p.b != null) mass = num(p.l * 100) + " × " + num(p.b) + " cm";
      if (p.h != null) mass += (mass ? ", " : "") + num(p.h * 10) + " mm";
      mass += (mass ? "  ·  " : "") + "für " + fix(flaeche(p), 2) + " m²"
        + (p.vs != null && p.vs !== 0 ? " + " + num(p.vs) + " %" : "");
    } else if (ist(p, MATERIAL)) {
      const b = bedarf(p);
      if (hatPackung(p) && b != null)
        mass = "Bedarf " + mengeText(b) + " " + norm(p.einh) + ", " + packName(p) + " à " + mengeText(p.pack) + " " + norm(p.einh);
      else if (p.vs != null && p.vs !== 0 && p.menge != null)
        mass = mengeText(p.menge) + " " + norm(p.einh) + " + " + num(p.vs) + " % Reserve";
    }
    if (mass) teile.push(mass);
    if (!leer(p.bem)) teile.push("„" + p.bem + "“");
    return teile.join("\n");
  }

  /** Rechenwerte (m, m³, Plattenfläche) ohne Betrag. */
  function werte(p) {
    const t = [];
    if (ist(p, HOLZ) || ist(p, LFM)) {
      if (laufmeter(p) > 0 && ist(p, HOLZ)) t.push(fix(laufmeter(p), 2) + " m");
      if (volumen(p) > 0) t.push(fix(volumen(p), 3) + " m³");
    } else if (ist(p, PLATTE) && plattenFlaecheGesamt(p) > 0) {
      t.push(fix(plattenFlaecheGesamt(p), 2) + " m² Platten");
    }
    return t.join("  ·  ");
  }

  // ------------------------------------------------------------------ Summen der Baustelle

  function summen(liste) {
    const pos = (liste.positionen || []).filter(p => !ist(p, TITEL));
    const s = { anzahl: pos.length, holzM3: 0, platten: 0, betrag: 0, hatPreise: false };
    for (const p of pos) {
      if (ist(p, HOLZ) || ist(p, LFM)) s.holzM3 += volumen(p);
      if (ist(p, PLATTE)) s.platten += v(plattenAnzahl(p));
      const b = betrag(p);
      if (b != null) { s.betrag += b; s.hatPreise = true; }
    }
    return s;
  }

  function titel(liste) {
    return leer(liste.projekt) ? "Liste ohne Namen" : String(liste.projekt).trim();
  }

  // ------------------------------------------------------------------ Rapporte

  const nettoMin = z => Math.max(0, (z.bis || 0) - (z.von || 0) - (z.pause || 0));
  const summeMin = r => (r.zeiten || []).reduce((a, z) => a + nettoMin(z), 0);
  const dauer = min => Math.floor(min / 60) + ":" + String(min % 60).padStart(2, "0");
  const uhr = min => String(Math.floor(min / 60)).padStart(2, "0") + ":" + String(min % 60).padStart(2, "0");
  const art = r => r.art || "rapport";
  const symbol = a => a === "doku" ? "📷" : a === "abnahme" ? "✅" : "📋";
  const mitMaengeln = r => art(r) === "abnahme" && (r.ergebnis || "Erfolgt ohne Mängel") !== "Erfolgt ohne Mängel";

  function anwesende(r) {
    if (!leer(r.anwesend)) return String(r.anwesend).trim();
    const s = [];
    for (const z of r.zeiten || []) if (!leer(z.mitarbeiter) && !s.includes(z.mitarbeiter.trim())) s.push(z.mitarbeiter.trim());
    return s.join(", ");
  }

  /** Dateiname der PDF im Team, wie RapportPdf.dateiname in der App. */
  function pdfName(liste, r) {
    const a = art(r) === "abnahme" ? "Abnahme" : art(r) === "doku" ? "Bautagebuch" : "Rapport";
    const d = new Date(r.tag || 0);
    const datum = d.getFullYear() + "-" + String(d.getMonth() + 1).padStart(2, "0") + "-" + String(d.getDate()).padStart(2, "0");
    let s = (a + "_" + datum + "_" + titel(liste)).replace(/[^A-Za-z0-9ÄÖÜäöüß_\-]+/g, "_").replace(/_+/g, "_");
    if (s.length > 70) s = s.substring(0, 70);
    return s + ".pdf";
  }
  const pdfOrdner = r => art(r) === "abnahme" ? "Abnahmen" : art(r) === "doku" ? "Bautagebuch" : "Rapporte";

  return {
    HOLZ, LFM, PLATTE, MATERIAL, TITEL,
    norm, ganzzahlig, fix, num, mengeText, euro,
    flaeche, plattenAnzahl, bedarf, packungen, bestellText, preisEinheit, mengeIn, betrag, volumen, laufmeter,
    details, werte, summen, titel,
    nettoMin, summeMin, dauer, uhr, art, symbol, mitMaengeln, anwesende, pdfName, pdfOrdner,
  };
})();

if (typeof module !== "undefined") module.exports = ML;
