package de.zimmerei.holzliste;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Abgleich einer Baustelle mit ihrem Microsoft-Teams-Team (ein Team pro Baustelle).
 *
 * Im Ordner „Dateien“ des Standardkanals liegt „Materialliste/baustelle.json“ mit allen Daten der Baustelle,
 * Fotos und Unterschriften unter „Materialliste/Dateien/“. Fertige Berichte (PDF) kommen in „Rapporte“,
 * „Bautagebuch“ und „Abnahmen“, dazu ein Beitrag im Kanal.
 *
 * Regeln: Änderungen am Handy werden im Hintergrund hochgeladen – aber nur, wenn im Team seit dem letzten Abgleich
 * niemand etwas geändert hat (eTag). Den Team-Stand holt die App beim Öffnen der Baustelle. Haben beide Seiten
 * geändert, ist das ein Konflikt, den der Benutzer entscheidet. Die Sync-Infos liegen getrennt von den Baustellendaten,
 * damit ein Bildschirm mit älterem Stand sie beim Speichern nicht zurückdreht.
 */
public final class TeamSync {

    private TeamSync() {}

    public enum Ergebnis { AKTUELL, HOCHGELADEN, GELADEN, KONFLIKT, NEU_IM_TEAM }

    static final String DATEI = "Materialliste/baustelle.json";
    static final String DATEIEN = "Materialliste/Dateien/";

    /** Tests: eigener Graph-Client, eigene PDF-Erzeugung und alles sofort im selben Thread. */
    static Graph testGraph;
    static boolean synchron;

    interface PdfMacher { void pdf(Context c, Holzliste h, Rapport r, java.io.OutputStream out) throws IOException; }

    static PdfMacher pdfMacher = RapportPdf::pdf;

    private static final ExecutorService HINTERGRUND = Executors.newSingleThreadExecutor();
    private static final Handler HAUPT = new Handler(Looper.getMainLooper());

    static Graph graph(Context c) {
        return testGraph != null ? testGraph : TeamsKonto.graph(c);
    }

    public static boolean verknuepft(Holzliste h) {
        return h != null && h.teamId != null && !h.teamId.isEmpty();
    }

    // ================================================================ Sync-Zustand (getrennt von den Daten)

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("teamsync", Context.MODE_PRIVATE);
    }

    private static JSONObject zustand(Context c, String id) {
        try {
            return new JSONObject(prefs(c).getString(id, "{}"));
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    private static void zustand(Context c, String id, JSONObject z) {
        prefs(c).edit().putString(id, z.toString()).commit();
    }

    private static void setze(Context c, String id, String key, Object wert) {
        JSONObject z = zustand(c, id);
        try { z.put(key, wert); } catch (JSONException ignored) {}
        zustand(c, id, z);
    }

    /** Lokale Änderungen, die noch nicht im Team sind. */
    public static boolean offen(Context c, String id) { return zustand(c, id).optBoolean("offen", false); }

    public static boolean konflikt(Context c, String id) { return zustand(c, id).optBoolean("konflikt", false); }

    /** Zeitpunkt des letzten erfolgreichen Abgleichs (0 = noch nie). */
    public static long stand(Context c, String id) { return zustand(c, id).optLong("stand", 0); }

    /** Letzte Fehlermeldung (z. B. kein Netz), leer wenn alles gut ging. */
    public static String fehler(Context c, String id) { return zustand(c, id).optString("fehler", ""); }

    public static String statusText(Context c, Holzliste h) {
        if (!verknuepft(h)) return "";
        if (konflikt(c, h.id)) return "⚠ im Team und hier geändert – antippen";
        if (offen(c, h.id)) return fehler(c, h.id).isEmpty() ? "⟳ wird hochgeladen …" : "⟳ wartet aufs Netz";
        long s = stand(c, h.id);
        return s == 0 ? "noch nicht abgeglichen" : "✓ abgeglichen " + Zeiten.datum(s) + " " + Zeiten.uhr(s);
    }

    /** Wird von {@link Storage#speichern} aufgerufen: merken und im Hintergrund hochladen. */
    static void geaendert(Context c, Holzliste h) {
        if (!verknuepft(h)) return;
        setze(c, h.id, "offen", true);
        anstossen(c);
    }

    /** Hochladen offener Baustellen und Postausgang im Hintergrund. */
    public static void anstossen(Context c) {
        Context app = c.getApplicationContext();
        Runnable r = () -> {
            for (Holzliste h : Storage.alle(app)) {
                if (!verknuepft(h) || !offen(app, h.id) || konflikt(app, h.id)) continue;
                try {
                    abgleichen(app, h.id, true);
                } catch (IOException e) {
                    setze(app, h.id, "fehler", e.getMessage() == null ? "Fehler" : e.getMessage());
                }
            }
            postausgang(app);
        };
        if (synchron) r.run();
        else HINTERGRUND.execute(r);
    }

    /** Für die Oberfläche: Arbeit im Hintergrund, Ergebnis im Haupt-Thread. */
    public interface Fertig<T> { void fertig(T ergebnis, Exception fehler); }

    public interface Arbeit<T> { T tun() throws Exception; }

    public static <T> void imHintergrund(Arbeit<T> a, Fertig<T> f) {
        Runnable r = () -> {
            T e = null;
            Exception x = null;
            try { e = a.tun(); } catch (Exception ex) { x = ex; }
            T e2 = e;
            Exception x2 = x;
            if (synchron) f.fertig(e2, x2);
            else HAUPT.post(() -> f.fertig(e2, x2));
        };
        if (synchron) r.run();
        else HINTERGRUND.execute(r);
    }

    // ================================================================ Verknüpfen und Laden

    /** Die lokale Baustelle zu einem Team, null wenn es keine gibt. */
    public static Holzliste lokal(Context c, String teamId) {
        for (Holzliste h : Storage.alle(c)) if (teamId.equals(h.teamId)) return h;
        return null;
    }

    /** Ermittelt Standardkanal und Dateiordner und trägt sie in die Baustelle ein (ohne zu speichern). */
    static void ortErmitteln(Context c, Holzliste h, Graph.Team t) throws IOException {
        Graph g = graph(c);
        String kanal = g.standardKanal(t.id).optString("id");
        String[] ordner = g.dateiOrdner(t.id, kanal);
        h.teamId = t.id;
        h.teamName = t.name;
        h.teamKanal = kanal;
        h.teamDrive = ordner[0];
        h.teamOrdner = ordner[1];
    }

    /** Gibt es im Team schon eine Baustelle? Dann deren Stand laden und lokal anlegen (oder ersetzen). */
    public static Holzliste ausTeamLaden(Context c, Graph.Team t) throws IOException {
        Holzliste ort = new Holzliste();
        ortErmitteln(c, ort, t);
        Graph.Datei d = graph(c).datei(ort.teamDrive, ort.teamOrdner, DATEI);
        if (d == null) return null;
        Holzliste vorhanden = lokal(c, t.id);
        Holzliste h = herunterladen(c, ort, d, vorhanden == null ? null : vorhanden.id);
        return h;
    }

    /**
     * Verknüpft eine Baustelle mit einem Team und lädt sie hoch. Liegt im Team schon eine Baustelle, wird nichts
     * überschrieben: Ergebnis {@link Ergebnis#KONFLIKT} – der Benutzer entscheidet ({@link #loesen}).
     */
    public static Ergebnis verknuepfen(Context c, Holzliste h, Graph.Team t) throws IOException {
        ortErmitteln(c, h, t);
        if (h.projekt == null || h.projekt.trim().isEmpty()) h.projekt = t.name;
        Storage.schreiben(c, h, false);
        JSONObject z = new JSONObject();
        try { z.put("offen", true); } catch (JSONException ignored) {}
        zustand(c, h.id, z);
        Graph.Datei d = graph(c).datei(h.teamDrive, h.teamOrdner, DATEI);
        if (d != null) {
            setze(c, h.id, "konflikt", true);
            setze(c, h.id, "teamETag", d.eTag);
            return Ergebnis.KONFLIKT;
        }
        return abgleichen(c, h.id, false);
    }

    public static void trennen(Context c, Holzliste h) {
        String id = h.id;
        h.teamId = h.teamName = h.teamKanal = h.teamDrive = h.teamOrdner = "";
        Storage.schreiben(c, h, false);
        prefs(c).edit().remove(id).commit();
    }

    // ================================================================ Abgleich

    /**
     * Gleicht eine Baustelle ab. nurHochladen: im Hintergrund nach dem Speichern – lädt nie herunter
     * (ein offener Bildschirm hätte sonst einen veralteten Stand); geänderter Team-Stand wird dann als Konflikt
     * bzw. beim nächsten Öffnen geladen.
     */
    public static synchronized Ergebnis abgleichen(Context c, String id, boolean nurHochladen) throws IOException {
        Holzliste h = Storage.laden(c, id);
        if (!verknuepft(h)) return Ergebnis.AKTUELL;
        Graph g = graph(c);
        JSONObject z = zustand(c, id);
        boolean offen = z.optBoolean("offen", false);
        String bekannt = z.optString("eTag", "");
        Graph.Datei d = g.datei(h.teamDrive, h.teamOrdner, DATEI);
        Ergebnis e;
        if (d == null) {
            hochladen(c, g, h, "");
            e = Ergebnis.HOCHGELADEN;
        } else if (d.eTag.equals(bekannt)) {
            if (!offen) e = Ergebnis.AKTUELL;
            else {
                try {
                    hochladen(c, g, h, bekannt);
                    e = Ergebnis.HOCHGELADEN;
                } catch (Graph.Fehler f) {
                    if (f.code != 412 && f.code != 409) throw f;
                    setze(c, id, "konflikt", true); // genau jetzt hat jemand anders gespeichert
                    return Ergebnis.KONFLIKT;
                }
            }
        } else if (!offen && !nurHochladen) {
            herunterladen(c, h, d, id);
            e = Ergebnis.GELADEN;
        } else if (!offen) {
            return Ergebnis.AKTUELL; // neuer Team-Stand: beim nächsten Öffnen laden
        } else {
            setze(c, id, "konflikt", true);
            setze(c, id, "teamETag", d.eTag);
            return Ergebnis.KONFLIKT;
        }
        JSONObject n = zustand(c, id);
        try {
            n.put("stand", System.currentTimeMillis());
            n.put("fehler", "");
            n.put("konflikt", false);
        } catch (JSONException ignored) {}
        zustand(c, id, n);
        return e;
    }

    /** Konflikt lösen: teamStand = den Stand aus dem Team übernehmen, sonst den eigenen Stand hochladen (überschreibt). */
    public static synchronized void loesen(Context c, String id, boolean teamStand) throws IOException {
        Holzliste h = Storage.laden(c, id);
        if (!verknuepft(h)) return;
        Graph g = graph(c);
        Graph.Datei d = g.datei(h.teamDrive, h.teamOrdner, DATEI);
        if (teamStand && d != null) {
            herunterladen(c, h, d, id);
        } else {
            hochladen(c, g, h, null);
        }
        JSONObject n = zustand(c, id);
        try {
            n.put("konflikt", false);
            n.put("stand", System.currentTimeMillis());
            n.put("fehler", "");
        } catch (JSONException ignored) {}
        zustand(c, id, n);
    }

    private static void hochladen(Context c, Graph g, Holzliste h, String eTag) throws IOException {
        byte[] json;
        try {
            json = h.toJson().toString(1).getBytes(StandardCharsets.UTF_8);
        } catch (JSONException e) {
            throw new IOException(e);
        }
        // erst die Dateien (Fotos, Unterschriften), damit die Daten nie auf Fehlendes zeigen
        JSONObject z = zustand(c, h.id);
        Set<String> oben = new LinkedHashSet<>();
        JSONArray a = z.optJSONArray("dateien");
        if (a != null) for (int i = 0; i < a.length(); i++) oben.add(a.optString(i));
        for (String name : dateien(h)) {
            if (oben.contains(name)) continue;
            File f = RapportDateien.datei(c, name);
            if (!f.exists()) continue;
            g.hochladen(h.teamDrive, h.teamOrdner, DATEIEN + name, Files.readAllBytes(f.toPath()),
                    name.endsWith(".png") ? "image/png" : "image/jpeg", null);
            oben.add(name);
            setze(c, h.id, "dateien", new JSONArray(oben));
        }
        Graph.Datei d = g.hochladen(h.teamDrive, h.teamOrdner, DATEI, json, "application/json", eTag);
        JSONObject n = zustand(c, h.id);
        try {
            n.put("eTag", d.eTag);
            // nur „erledigt“, wenn seit dem Lesen nicht erneut gespeichert wurde
            Holzliste jetzt = Storage.laden(c, h.id);
            n.put("offen", jetzt != null && jetzt.geaendert != h.geaendert);
        } catch (JSONException ignored) {}
        zustand(c, h.id, n);
    }

    /** Lädt den Team-Stand und speichert ihn lokal (unter lokaleId, sonst unter der ID aus dem Team). */
    private static Holzliste herunterladen(Context c, Holzliste ort, Graph.Datei d, String lokaleId) throws IOException {
        Graph g = graph(c);
        Holzliste neu;
        try {
            neu = Holzliste.fromJson(new JSONObject(new String(g.laden(ort.teamDrive, d.id), StandardCharsets.UTF_8)));
        } catch (JSONException e) {
            throw new IOException("Die Baustellen-Datei im Team ist beschädigt", e);
        }
        if (lokaleId != null) neu.id = lokaleId;
        if (neu.id == null || neu.id.isEmpty()) neu.id = java.util.UUID.randomUUID().toString();
        neu.teamId = ort.teamId;
        neu.teamName = ort.teamName;
        neu.teamKanal = ort.teamKanal;
        neu.teamDrive = ort.teamDrive;
        neu.teamOrdner = ort.teamOrdner;
        // fehlende Fotos und Unterschriften holen
        Set<String> oben = new LinkedHashSet<>();
        for (String name : dateien(neu)) {
            File f = RapportDateien.datei(c, name);
            oben.add(name);
            if (f.exists()) continue;
            Graph.Datei x = g.datei(ort.teamDrive, ort.teamOrdner, DATEIEN + name);
            if (x == null) continue;
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(g.laden(ort.teamDrive, x.id));
            }
        }
        Storage.schreiben(c, neu, false);
        JSONObject z = zustand(c, neu.id);
        try {
            z.put("eTag", d.eTag);
            z.put("offen", false);
            z.put("konflikt", false);
            z.put("stand", System.currentTimeMillis());
            z.put("fehler", "");
            z.put("dateien", new JSONArray(oben));
        } catch (JSONException ignored) {}
        zustand(c, neu.id, z);
        return neu;
    }

    /** Alle Dateinamen (Fotos, Unterschriften), auf die die Baustelle zeigt. */
    static List<String> dateien(Holzliste h) {
        List<String> l = new ArrayList<>();
        for (Rapport r : h.rapporte) {
            l.addAll(r.fotos);
            if (r.unterschrieben()) l.add(r.unterschrift);
            if (r.unterschrieben2()) l.add(r.unterschrift2);
        }
        return l;
    }

    // ================================================================ Berichte ins Team (Postausgang)

    private static SharedPreferences ausgang(Context c) {
        return c.getApplicationContext().getSharedPreferences("postausgang", Context.MODE_PRIVATE);
    }

    /** Bericht ins Team legen: kommt in den Postausgang und geht raus, sobald Netz da ist. */
    public static void ablegen(Context c, Holzliste h, Rapport r) {
        ausgang(c).edit().putString(r.id, h.id).commit();
        setzeAbgelegt(c, r.id, 0, "");
        anstossen(c);
    }

    public static boolean wartet(Context c, String rapportId) {
        return ausgang(c).contains(rapportId);
    }

    /** Wann der Bericht im Team abgelegt wurde (0 = noch nicht) und der Link zur PDF. */
    public static long abgelegtAm(Context c, String rapportId) {
        return prefs(c).getLong("abgelegt:" + rapportId, 0);
    }

    public static String abgelegtUrl(Context c, String rapportId) {
        return prefs(c).getString("url:" + rapportId, "");
    }

    private static void setzeAbgelegt(Context c, String rapportId, long am, String url) {
        prefs(c).edit().putLong("abgelegt:" + rapportId, am).putString("url:" + rapportId, url).commit();
    }

    /** Arbeitet den Postausgang ab. Gibt die Anzahl abgelegter Berichte zurück. */
    static synchronized int postausgang(Context c) {
        int n = 0;
        for (String rid : new ArrayList<>(ausgang(c).getAll().keySet())) {
            String lid = ausgang(c).getString(rid, "");
            Holzliste h = Storage.laden(c, lid);
            Rapport r = null;
            if (h != null) for (Rapport x : h.rapporte) if (x.id.equals(rid)) r = x;
            if (r == null || !verknuepft(h)) {
                ausgang(c).edit().remove(rid).commit(); // gelöscht oder Team getrennt
                continue;
            }
            try {
                Graph g = graph(c);
                ByteArrayOutputStream pdf = new ByteArrayOutputStream();
                pdfMacher.pdf(c, h, r, pdf);
                String pfad = ordner(r) + "/" + RapportPdf.dateiname(h, r);
                Graph.Datei d = g.hochladen(h.teamDrive, h.teamOrdner, pfad, pdf.toByteArray(), "application/pdf", null);
                g.beitrag(h.teamId, h.teamKanal, beitragHtml(h, r, d.webUrl, RapportPdf.dateiname(h, r)));
                setzeAbgelegt(c, rid, System.currentTimeMillis(), d.webUrl);
                ausgang(c).edit().remove(rid).commit();
                n++;
            } catch (Exception e) {
                // bleibt im Postausgang; nächster Versuch beim nächsten Anstoß
            }
        }
        return n;
    }

    static String ordner(Rapport r) {
        return r.istAbnahme() ? "Abnahmen" : r.istDoku() ? "Bautagebuch" : "Rapporte";
    }

    /** Beitrag im Kanal, z. B. „📋 Rapport 25.11.2026 – 17:15 h · 3 Material · ✍ unterschrieben“. */
    static String beitragHtml(Holzliste h, Rapport r, String url, String datei) {
        StringBuilder s = new StringBuilder();
        s.append("<p><b>").append(Rapport.symbol(r.art)).append(' ').append(esc(r.titel)).append(' ')
                .append(Zeiten.datum(r.tag)).append("</b> – ").append(esc(h.titel())).append("</p><p>");
        if (r.istRapport()) {
            s.append(RapportPdf.dauer(r.summeMin())).append(" h");
            if (!r.material.isEmpty()) s.append(" · ").append(r.material.size()).append(" Material");
        } else if (r.istAbnahme()) {
            s.append(esc(r.abnahmeArt)).append(": ").append(esc(r.ergebnis));
            int offen = 0;
            for (Rapport.Mangel m : r.maengel) if (!m.erledigt) offen++;
            if (r.mitMaengeln()) s.append(" · ").append(offen).append(" offene Mängel")
                    .append(r.frist == 0 ? "" : ", Frist " + Zeiten.datum(r.frist));
        } else {
            String e = r.eintrag.trim();
            s.append(esc(e.length() > 160 ? e.substring(0, 157) + " …" : e));
        }
        if (!r.fotos.isEmpty()) s.append(" · ").append(r.fotos.size()).append(r.fotos.size() == 1 ? " Foto" : " Fotos");
        if (r.unterschrieben()) s.append(" · ✍ unterschrieben").append(r.unterzeichner.isEmpty() ? "" : " von " + esc(r.unterzeichner));
        s.append("</p><p>📄 <a href=\"").append(esc(url)).append("\">").append(esc(datei)).append("</a></p>");
        return s.toString();
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
