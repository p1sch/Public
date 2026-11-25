package de.zimmerei.holzliste;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Baustellen-Dokument: Rapport (Tagesbericht mit Stunden und Material), Dokumentation (Bautagebuch)
 * oder Abnahme (Abnahmeprotokoll mit Mängeln). Alle drei haben Fotos, optional Unterschrift und
 * werden als PDF erzeugt. Wird als PDF erzeugt ({@link RapportPdf}) und gehört zur Baustelle
 * ({@link Holzliste#rapporte}). Fotos und Unterschrift liegen als Dateien in {@link RapportDateien}.
 */
public class Rapport {

    public static final String RAPPORT = "rapport", DOKU = "doku", ABNAHME = "abnahme";
    public static final String TEILABNAHME = "Teilabnahme", GESAMTABNAHME = "Gesamtabnahme";
    public static final String OHNE_MAENGEL = "Erfolgt ohne Mängel", MIT_MAENGELN = "Erfolgt mit aufgeführten Mängeln",
            ABGELEHNT = "Abgelehnt";

    public String id = UUID.randomUUID().toString();
    /** Art des Dokuments: {@link #RAPPORT}, {@link #DOKU} oder {@link #ABNAHME}. */
    public String art = RAPPORT;
    /** Tag des Rapports (Tagesbeginn in Millisekunden). */
    public long tag;
    public String titel = "Rapport";
    /** Fortlaufende Dokumentennummer, z. B. 2026_11_25_14_03_4711 (wird beim Anlegen vergeben). */
    public String nummer = "";
    public final List<ZeitZeile> zeiten = new ArrayList<>();
    public final List<MatZeile> material = new ArrayList<>();
    /** Wetter (z. B. „☀️ sonnig“) und Temperatur in °C als Text. */
    public String wetter = "";
    public String temperatur = "";
    /** Ausgeführte Arbeiten. */
    public String eintrag = "";
    public String behinderungen = "";
    public String bemerkung = "";
    /** Dateinamen der Fotos in {@link RapportDateien#ordner}. */
    public final List<String> fotos = new ArrayList<>();
    public String unterzeichner = "";
    /** Dateiname der Unterschrift (PNG), leer = noch nicht unterschrieben. */
    public String unterschrift = "";
    public long unterschriebenAm;
    /** Dokumentation: anwesende Mitarbeiter (Komma-getrennt). Beim Rapport kommen sie aus den Zeiten. */
    public String anwesend = "";

    // Abnahme
    public String abnahmeArt = GESAMTABNAHME;
    public String ergebnis = OHNE_MAENGEL;
    /** Leistung erbracht von/bis (Tagesbeginn, 0 = nicht angegeben) und Frist zur Mängelbeseitigung. */
    public long leistungVon, leistungBis, frist;
    public final List<Mangel> maengel = new ArrayList<>();
    /** Zweite Unterschrift (Abnahme: Auftragnehmer). */
    public String unterzeichner2 = "";
    public String unterschrift2 = "";
    public long unterschrieben2Am;

    public boolean unterschrieben() {
        return unterschrift != null && !unterschrift.isEmpty();
    }

    public boolean unterschrieben2() {
        return unterschrift2 != null && !unterschrift2.isEmpty();
    }

    public boolean istRapport() { return RAPPORT.equals(art); }
    public boolean istDoku() { return DOKU.equals(art); }
    public boolean istAbnahme() { return ABNAHME.equals(art); }

    /** Abnahme mit Mängeln oder abgelehnt: Mängelliste und Frist sind gefragt. */
    public boolean mitMaengeln() {
        return istAbnahme() && !OHNE_MAENGEL.equals(ergebnis);
    }

    public static String standardTitel(String art) {
        switch (art) {
            case DOKU: return "Bautagebuch";
            case ABNAHME: return "Abnahmeprotokoll";
            default: return "Rapport";
        }
    }

    public static String symbol(String art) {
        switch (art) {
            case DOKU: return "📷";
            case ABNAHME: return "✅";
            default: return "📋";
        }
    }

    /** Ein Mangel im Abnahmeprotokoll. */
    public static class Mangel {
        public String text = "";
        public boolean erledigt;

        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("text", text);
            o.put("erledigt", erledigt);
            return o;
        }

        static Mangel fromJson(JSONObject o) {
            Mangel m = new Mangel();
            m.text = o.optString("text", "");
            m.erledigt = o.optBoolean("erledigt", false);
            return m;
        }
    }

    /** Eine Zeile der Stundentabelle. Zeiten in Minuten ab Mitternacht. */
    public static class ZeitZeile {
        public String mitarbeiter = "";
        public String leistung = "";
        public int vonMin;
        public int bisMin;
        public int pauseMin;

        public int nettoMin() {
            return Math.max(0, bisMin - vonMin - pauseMin);
        }

        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("mitarbeiter", mitarbeiter);
            o.put("leistung", leistung);
            o.put("von", vonMin);
            o.put("bis", bisMin);
            o.put("pause", pauseMin);
            return o;
        }

        static ZeitZeile fromJson(JSONObject o) {
            ZeitZeile z = new ZeitZeile();
            z.mitarbeiter = o.optString("mitarbeiter", "");
            z.leistung = o.optString("leistung", "");
            z.vonMin = o.optInt("von", 0);
            z.bisMin = o.optInt("bis", 0);
            z.pauseMin = o.optInt("pause", 0);
            return z;
        }
    }

    /** Eine Zeile „verbautes Material“. */
    public static class MatZeile {
        public String bezeichnung = "";
        /** Zusatz wie Querschnitt, Länge, Qualität (klein unter der Bezeichnung). */
        public String details = "";
        public Double menge;
        public String einheit = "";

        public String mengeText() {
            return menge == null ? "" : Fmt.mengeText(menge);
        }

        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("bezeichnung", bezeichnung);
            o.put("details", details);
            if (menge != null) o.put("menge", menge);
            o.put("einheit", einheit);
            return o;
        }

        static MatZeile fromJson(JSONObject o) {
            MatZeile m = new MatZeile();
            m.bezeichnung = o.optString("bezeichnung", "");
            m.details = o.optString("details", "");
            m.menge = o.has("menge") ? o.optDouble("menge") : null;
            if (m.menge != null && m.menge.isNaN()) m.menge = null;
            m.einheit = o.optString("einheit", "");
            return m;
        }
    }

    // ================================================================ Berechnung

    public int summeMin() {
        int s = 0;
        for (ZeitZeile z : zeiten) s += z.nettoMin();
        return s;
    }

    /** Anwesende: eingetragen (Dokumentation) oder alle Mitarbeiter aus der Stundentabelle, ohne Doppelte. */
    public String anwesende() {
        if (anwesend != null && !anwesend.trim().isEmpty()) return anwesend.trim();
        Set<String> s = new LinkedHashSet<>();
        for (ZeitZeile z : zeiten) if (!z.mitarbeiter.trim().isEmpty()) s.add(z.mitarbeiter.trim());
        return String.join(", ", s);
    }

    public static String neueNummer(long jetzt) {
        return new SimpleDateFormat("yyyy_MM_dd_HH_mm", Locale.GERMANY).format(new Date(jetzt))
                + "_" + (1000 + (int) (Math.abs(UUID.randomUUID().getLeastSignificantBits()) % 9000));
    }

    /** Neuer Rapport für einen Tag: Stunden kommen aus der Zeiterfassung dieser Baustelle. */
    public static Rapport neu(long tag, Holzliste baustelle, List<Zeiteintrag> alleZeiten, String bemerkungVorlage, long jetzt) {
        return neu(RAPPORT, tag, baustelle, alleZeiten, bemerkungVorlage, jetzt);
    }

    /**
     * Neues Dokument. Rapport: Stunden aus der Zeiterfassung, Hinweistext aus der Vorlage.
     * Dokumentation: Anwesende aus den gestempelten Zeiten des Tages. Abnahme: Leistungszeitraum bis heute.
     */
    public static Rapport neu(String art, long tag, Holzliste baustelle, List<Zeiteintrag> alleZeiten, String bemerkungVorlage, long jetzt) {
        Rapport r = new Rapport();
        r.art = art;
        r.titel = standardTitel(art);
        r.tag = Zeiten.tagesStart(tag);
        r.nummer = neueNummer(jetzt);
        if (RAPPORT.equals(art)) {
            r.bemerkung = bemerkungVorlage == null ? "" : bemerkungVorlage;
            r.zeitenUebernehmen(baustelle, alleZeiten, jetzt);
        } else if (DOKU.equals(art)) {
            r.zeitenUebernehmen(baustelle, alleZeiten, jetzt);
            r.anwesend = r.anwesende();
            r.zeiten.clear();
        } else if (ABNAHME.equals(art)) {
            r.leistungBis = r.tag;
        }
        return r;
    }

    /** Offene Mängel als dringende Aufgaben in die Baustelle übernehmen (ohne Doppelte). Gibt die Anzahl neuer Aufgaben zurück. */
    public int maengelAlsAufgaben(Holzliste baustelle) {
        String abschnitt = "Mängel aus Abnahme " + Zeiten.datum(tag);
        String info = frist == 0 ? "" : "Frist bis " + Zeiten.datum(frist);
        int n = 0;
        for (Mangel m : maengel) {
            if (m.erledigt || m.text.trim().isEmpty()) continue;
            boolean schon = false;
            for (Aufgabe a : baustelle.aufgaben) if (a.abschnitt.equals(abschnitt) && a.text.equals(m.text.trim())) schon = true;
            if (schon) continue;
            Aufgabe a = new Aufgabe(abschnitt, m.text.trim(), info);
            a.prio = Werkzeug.ROT;
            baustelle.aufgaben.add(a);
            n++;
        }
        return n;
    }

    /** Ersetzt die Stundentabelle durch die gestempelten Zeiten des Tages auf dieser Baustelle. */
    public void zeitenUebernehmen(Holzliste baustelle, List<Zeiteintrag> alleZeiten, long jetzt) {
        zeiten.clear();
        long von = Zeiten.tagesStart(tag), bis = Zeiten.plusTage(von, 1);
        for (Zeiteintrag z : Zeiten.zeitraum(alleZeiten, von, bis, baustelle.id)) {
            if (z.istAbwesenheit()) continue;
            ZeitZeile zz = new ZeitZeile();
            zz.mitarbeiter = z.mitarbeiter;
            zz.leistung = z.taetigkeit;
            zz.vonMin = minuten(z.start, von);
            zz.bisMin = minuten(z.laeuft() ? Math.max(z.start, jetzt) : z.ende, von);
            zz.pauseMin = z.pauseMin(jetzt);
            zeiten.add(zz);
        }
    }

    private static int minuten(long t, long tagesStart) {
        return (int) Math.max(0, Math.min(24 * 60, (t - tagesStart) / 60000L));
    }

    /** Material-Zeile aus einer Position der Materialliste (Menge = Bestellmenge). */
    public static MatZeile ausPosition(Position p) {
        MatZeile m = new MatZeile();
        m.bezeichnung = p.bezeichnung == null || p.bezeichnung.trim().isEmpty() ? p.holzart : p.bezeichnung.trim();
        StringBuilder d = new StringBuilder();
        if (p.holzart != null && !p.holzart.trim().isEmpty() && !p.holzart.equals(m.bezeichnung)) d.append(p.holzart.trim());
        if ((p.isHolz() || p.isLfm()) && (p.breite != null || p.hoehe != null)) {
            d.append(d.length() > 0 ? ", " : "").append(Fmt.num(p.breite)).append(" × ").append(Fmt.num(p.hoehe)).append(" cm");
            if (p.isHolz() && p.laenge != null) d.append(", ").append(Fmt.fix(p.laenge, 2)).append(" m lang");
        } else if (p.isPlatte() && p.hoehe != null) {
            d.append(d.length() > 0 ? ", " : "").append(Fmt.num(p.hoehe * 10)).append(" mm");
        }
        m.details = d.toString();
        if (p.isMaterial()) {
            Double pk = p.getPackungen();
            if (pk != null) { m.menge = pk; m.einheit = p.packNameOderStandard(); }
            else { m.menge = p.getBedarf(); m.einheit = Einheit.norm(p.einheit); }
        } else if (p.isPlatte()) {
            m.menge = p.getPlattenAnzahl(); m.einheit = "Platten";
        } else if (p.isLfm()) {
            m.menge = p.laufmeter; m.einheit = "m";
        } else if (p.isHolz()) {
            m.menge = p.stueck; m.einheit = "Stk";
        }
        return m;
    }

    // ================================================================ JSON

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("tag", tag);
        o.put("titel", titel);
        o.put("nummer", nummer);
        JSONArray z = new JSONArray();
        for (ZeitZeile x : zeiten) z.put(x.toJson());
        o.put("zeiten", z);
        JSONArray m = new JSONArray();
        for (MatZeile x : material) m.put(x.toJson());
        o.put("material", m);
        o.put("wetter", wetter);
        o.put("temperatur", temperatur);
        o.put("eintrag", eintrag);
        o.put("behinderungen", behinderungen);
        o.put("bemerkung", bemerkung);
        JSONArray f = new JSONArray();
        for (String x : fotos) f.put(x);
        o.put("fotos", f);
        o.put("unterzeichner", unterzeichner);
        o.put("unterschrift", unterschrift);
        o.put("unterschriebenAm", unterschriebenAm);
        o.put("art", art);
        o.put("anwesend", anwesend);
        o.put("abnahmeArt", abnahmeArt);
        o.put("ergebnis", ergebnis);
        o.put("leistungVon", leistungVon);
        o.put("leistungBis", leistungBis);
        o.put("frist", frist);
        JSONArray mg = new JSONArray();
        for (Mangel x : maengel) mg.put(x.toJson());
        o.put("maengel", mg);
        o.put("unterzeichner2", unterzeichner2);
        o.put("unterschrift2", unterschrift2);
        o.put("unterschrieben2Am", unterschrieben2Am);
        return o;
    }

    public static Rapport fromJson(JSONObject o) {
        Rapport r = new Rapport();
        r.id = o.optString("id", r.id);
        r.tag = o.optLong("tag", 0);
        r.titel = o.optString("titel", "Rapport");
        r.nummer = o.optString("nummer", "");
        JSONArray z = o.optJSONArray("zeiten");
        if (z != null) for (int i = 0; i < z.length(); i++) {
            JSONObject x = z.optJSONObject(i);
            if (x != null) r.zeiten.add(ZeitZeile.fromJson(x));
        }
        JSONArray m = o.optJSONArray("material");
        if (m != null) for (int i = 0; i < m.length(); i++) {
            JSONObject x = m.optJSONObject(i);
            if (x != null) r.material.add(MatZeile.fromJson(x));
        }
        r.wetter = o.optString("wetter", "");
        r.temperatur = o.optString("temperatur", "");
        r.eintrag = o.optString("eintrag", "");
        r.behinderungen = o.optString("behinderungen", "");
        r.bemerkung = o.optString("bemerkung", "");
        JSONArray f = o.optJSONArray("fotos");
        if (f != null) for (int i = 0; i < f.length(); i++) {
            String x = f.optString(i, "");
            if (!x.isEmpty()) r.fotos.add(x);
        }
        r.unterzeichner = o.optString("unterzeichner", "");
        r.unterschrift = o.optString("unterschrift", "");
        r.unterschriebenAm = o.optLong("unterschriebenAm", 0);
        r.art = o.optString("art", RAPPORT);
        r.anwesend = o.optString("anwesend", "");
        r.abnahmeArt = o.optString("abnahmeArt", GESAMTABNAHME);
        r.ergebnis = o.optString("ergebnis", OHNE_MAENGEL);
        r.leistungVon = o.optLong("leistungVon", 0);
        r.leistungBis = o.optLong("leistungBis", 0);
        r.frist = o.optLong("frist", 0);
        JSONArray mg = o.optJSONArray("maengel");
        if (mg != null) for (int i = 0; i < mg.length(); i++) {
            JSONObject x = mg.optJSONObject(i);
            if (x != null) r.maengel.add(Mangel.fromJson(x));
        }
        r.unterzeichner2 = o.optString("unterzeichner2", "");
        r.unterschrift2 = o.optString("unterschrift2", "");
        r.unterschrieben2Am = o.optLong("unterschrieben2Am", 0);
        return r;
    }
}
