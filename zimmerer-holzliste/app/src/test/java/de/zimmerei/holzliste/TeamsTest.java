package de.zimmerei.holzliste;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Microsoft Teams: Anmeldung, Verknüpfen, Abgleich zwischen zwei Handys, Konflikt und Postausgang –
 * gegen ein nachgebautes Microsoft 365 ({@link FakeM365}), das sich wie Microsoft Graph verhält (eTags, 409/412 …).
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 34, qualifiers = "w393dp-h851dp-port-xxhdpi")
public class TeamsTest {

    /** Nachgebautes Microsoft 365: Teams, Kanäle, Dateiordner (mit eTags) und Kanal-Beiträge. */
    static final class FakeM365 implements Graph.Transport {
        static final class Eintrag { String id, eTag; byte[] daten; int version; }

        final List<Graph.Team> teams = new ArrayList<>();
        final Map<String, Eintrag> dateien = new LinkedHashMap<>(); // "drive/pfad" → Datei
        final List<String> beitraege = new ArrayList<>();
        int naechste = 1;
        boolean offline;

        void team(String id, String name) {
            Graph.Team t = new Graph.Team();
            t.id = id; t.name = name; t.beschreibung = "";
            teams.add(t);
        }

        String text(String drive, String pfad) {
            Eintrag e = dateien.get(drive + "/" + pfad);
            return e == null ? null : new String(e.daten, StandardCharsets.UTF_8);
        }

        /** Ein anderes Handy speichert direkt im Team. */
        void fremdSpeichern(String drive, String pfad, String inhalt) {
            Eintrag e = dateien.get(drive + "/" + pfad);
            e.version++;
            e.eTag = "\"{" + e.id + "}," + e.version + "\"";
            e.daten = inhalt.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public Graph.Antwort senden(String m, String url, Map<String, String> h, byte[] body) throws IOException {
            if (offline) throw new IOException("Kein Netz");
            if (url.startsWith("https://login.microsoftonline.com/")) {
                return json(200, "{\"access_token\":\"zugang\",\"refresh_token\":\"erneuern\",\"expires_in\":3600}");
            }
            assertEquals("Bearer zugang", h.get("Authorization"));
            String pfad = url.substring(Graph.BASIS.length());
            String ohneQuery = pfad.split("\\?")[0];
            if (ohneQuery.equals("/me")) return json(200, "{\"displayName\":\"Max Muster\"}");
            if (ohneQuery.equals("/me/joinedTeams")) {
                JSONArray a = new JSONArray();
                for (Graph.Team t : teams) a.put(obj("id", t.id, "displayName", t.name, "description", t.beschreibung));
                return json(200, obj("value", a).toString());
            }
            if (ohneQuery.matches("/teams/[^/]+/primaryChannel")) return json(200, obj("id", "kanal-" + ohneQuery.split("/")[2], "displayName", "Allgemein").toString());
            if (ohneQuery.matches("/teams/[^/]+/channels/[^/]+/filesFolder")) {
                String t = ohneQuery.split("/")[2];
                return json(200, obj("id", "ordner-" + t, "parentReference", obj("driveId", "drive-" + t)).toString());
            }
            if (ohneQuery.matches("/teams/[^/]+/channels/[^/]+/messages") && m.equals("POST")) {
                try {
                    beitraege.add(new JSONObject(new String(body, StandardCharsets.UTF_8)).getJSONObject("body").getString("content"));
                } catch (org.json.JSONException ex) {
                    throw new AssertionError(ex);
                }
                return json(201, "{\"id\":\"m1\"}");
            }
            if (ohneQuery.matches("/drives/[^/]+/items/[^/:]+/content")) {
                for (Eintrag e : dateien.values()) if (ohneQuery.contains("/items/" + e.id + "/")) return new Graph.Antwort(200, e.daten, null);
                return json(404, "{}");
            }
            if (ohneQuery.startsWith("/drives/") && ohneQuery.contains(":/")) {
                String drive = ohneQuery.split("/")[2];
                String rest = ohneQuery.substring(ohneQuery.indexOf(":/") + 2);
                boolean inhalt = rest.endsWith(":/content");
                if (inhalt) rest = rest.substring(0, rest.length() - ":/content".length());
                String dpfad = URLDecoder.decode(rest.replace("+", "%2B"), "UTF-8");
                String key = drive + "/" + dpfad;
                Eintrag e = dateien.get(key);
                if (m.equals("GET")) return e == null ? json(404, "{\"error\":{\"message\":\"itemNotFound\"}}") : json(200, meta(e, dpfad));
                if (m.equals("PUT") && inhalt) {
                    if (e != null && url.contains("conflictBehavior=fail")) return json(409, "{\"error\":{\"message\":\"nameAlreadyExists\"}}");
                    String ifMatch = h.get("If-Match");
                    if (ifMatch != null && (e == null || !ifMatch.equals(e.eTag))) return json(412, "{\"error\":{\"message\":\"resourceModified\"}}");
                    if (e == null) {
                        e = new Eintrag();
                        e.id = "id" + (naechste++);
                        dateien.put(key, e);
                    }
                    e.version++;
                    e.eTag = "\"{" + e.id + "}," + e.version + "\"";
                    e.daten = body;
                    return json(200, meta(e, dpfad));
                }
            }
            throw new AssertionError("Unerwartet: " + m + " " + url);
        }

        private static String meta(Eintrag e, String pfad) {
            return obj("id", e.id, "eTag", e.eTag, "name", pfad.substring(pfad.lastIndexOf('/') + 1),
                    "webUrl", "https://firma.sharepoint.com/" + pfad.replace(" ", "%20"), "size", e.daten.length).toString();
        }

        private static Graph.Antwort json(int code, String s) {
            return new Graph.Antwort(code, s.getBytes(StandardCharsets.UTF_8), new HashMap<>());
        }

        private static JSONObject obj(Object... kv) {
            JSONObject o = new JSONObject();
            try {
                for (int i = 0; i < kv.length; i += 2) o.put((String) kv[i], kv[i + 1]);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return o;
        }
    }

    private FakeM365 m365;
    private Context c;

    @Before
    public void vorbereiten() {
        c = RuntimeEnvironment.getApplication();
        m365 = new FakeM365();
        m365.team("t1", "EFH Huber – Dachstuhl");
        m365.team("t2", "Carport Maier");
        TeamSync.testGraph = new Graph(m365, () -> "zugang");
        TeamSync.synchron = true;
        TeamSync.pdfMacher = (ctx, h, r, out) -> out.write(("%PDF-1.4 " + r.titel).getBytes(StandardCharsets.UTF_8));
    }

    @After
    public void aufraeumen() {
        TeamSync.testGraph = null;
        TeamSync.synchron = false;
        TeamSync.pdfMacher = RapportPdf::pdf;
        TeamsActivity.testTeams = null;
        ZeitActivity.testJetzt = 0;
    }

    /** Neues Handy: alles Lokale weg (Baustellen, Fotos, Sync-Zustand, Postausgang). */
    private void anderesHandy() {
        for (Holzliste h : Storage.alle(c)) new File(new File(c.getFilesDir(), "holzlisten"), h.id + ".json").delete();
        File[] f = RapportDateien.ordner(c).listFiles();
        if (f != null) for (File x : f) x.delete();
        c.getSharedPreferences("teamsync", Context.MODE_PRIVATE).edit().clear().commit();
        c.getSharedPreferences("postausgang", Context.MODE_PRIVATE).edit().clear().commit();
    }

    private Holzliste baustelleMitRapport() throws Exception {
        Holzliste h = ScreenshotTest.beispiel();
        Storage.speichern(c, h);
        Rapport r = Rapport.neu(Rapport.RAPPORT, ZeiterfassungTest.um(25, 11, 18, 0), h, new ArrayList<>(), "", 0);
        Bitmap b = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888);
        b.eraseColor(Color.RED);
        String foto = "foto_team.jpg";
        try (FileOutputStream out = new FileOutputStream(RapportDateien.datei(c, foto))) {
            b.compress(Bitmap.CompressFormat.JPEG, 80, out);
        }
        r.fotos.add(foto);
        r.eintrag = "Sparren gestellt";
        h.rapporte.add(r);
        Storage.speichern(c, h);
        return h;
    }

    @Test
    public void anmeldungMitPkce() throws Exception {
        // Beispiel aus RFC 7636
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", TeamsKonto.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"));
        TeamsKonto.einrichten(c, "11111111-2222-3333-4444-555555555555", "");
        Uri u = TeamsKonto.anmeldeAdresse(c);
        assertEquals("login.microsoftonline.com", u.getHost());
        assertEquals("/organizations/oauth2/v2.0/authorize", u.getPath());
        assertEquals("S256", u.getQueryParameter("code_challenge_method"));
        assertEquals(TeamsKonto.REDIRECT, u.getQueryParameter("redirect_uri"));
        assertTrue(u.getQueryParameter("scope").contains("Files.ReadWrite.All"));
        String state = u.getQueryParameter("state");

        // falscher state → abgelehnt
        try {
            TeamsKonto.rueckkehr(c, Uri.parse(TeamsKonto.REDIRECT + "?code=abc&state=falsch"), m365);
            throw new AssertionError("hätte scheitern müssen");
        } catch (IOException erwartet) {
            assertFalse(TeamsKonto.angemeldet(c));
        }
        TeamsKonto.rueckkehr(c, Uri.parse(TeamsKonto.REDIRECT + "?code=abc&state=" + Uri.encode(state)), m365);
        assertTrue(TeamsKonto.angemeldet(c));
        assertEquals("Max Muster", TeamsKonto.name(c));
        assertEquals("zugang", TeamsKonto.token(c, m365));
        TeamsKonto.abmelden(c);
        assertFalse(TeamsKonto.angemeldet(c));
    }

    @Test
    public void zweiHandysAmSelbenTeam() throws Exception {
        // Handy A: Baustelle mit Team verknüpfen → alles landet im Team
        Holzliste a = baustelleMitRapport();
        Graph.Team t1 = m365.teams.get(0);
        assertEquals(TeamSync.Ergebnis.HOCHGELADEN, TeamSync.verknuepfen(c, a, t1));
        assertNotNull(m365.text("drive-t1", TeamSync.DATEI));
        assertNotNull(m365.text("drive-t1", TeamSync.DATEIEN + "foto_team.jpg"));
        assertFalse(TeamSync.offen(c, a.id));
        assertTrue(TeamSync.statusText(c, Storage.laden(c, a.id)).startsWith("✓ abgeglichen"));

        // Änderung auf A geht beim Speichern automatisch hoch
        Holzliste a1 = Storage.laden(c, a.id);
        a1.notiz = "Kran kommt am Freitag";
        Storage.speichern(c, a1);
        assertTrue(m365.text("drive-t1", TeamSync.DATEI).contains("Kran kommt am Freitag"));
        assertFalse(TeamSync.offen(c, a.id));

        // Handy B: Team antippen → Baustelle mit Foto aus dem Team
        anderesHandy();
        assertNull(TeamSync.lokal(c, "t1"));
        Holzliste b = TeamSync.ausTeamLaden(c, t1);
        assertNotNull(b);
        assertEquals("Kran kommt am Freitag", b.notiz);
        assertEquals(a.anzahlPositionen(), b.anzahlPositionen());
        assertEquals("t1", b.teamId);
        assertTrue(RapportDateien.datei(c, "foto_team.jpg").exists());
        assertEquals(b.id, TeamSync.lokal(c, "t1").id);

        // Team ohne Baustelle → null (App fragt dann: neu anlegen oder verknüpfen)
        assertNull(TeamSync.ausTeamLaden(c, m365.teams.get(1)));

        // Jemand anders ändert im Team → B holt es beim Öffnen
        String json = m365.text("drive-t1", TeamSync.DATEI).replace("Kran kommt am Freitag", "Kran kommt am Montag");
        m365.fremdSpeichern("drive-t1", TeamSync.DATEI, json);
        assertEquals(TeamSync.Ergebnis.AKTUELL, TeamSync.abgleichen(c, b.id, true)); // im Hintergrund nie herunterladen
        assertEquals(TeamSync.Ergebnis.GELADEN, TeamSync.abgleichen(c, b.id, false));
        assertEquals("Kran kommt am Montag", Storage.laden(c, b.id).notiz);
        assertEquals(TeamSync.Ergebnis.AKTUELL, TeamSync.abgleichen(c, b.id, false));

        // Konflikt: beide ändern → nichts wird überschrieben, Benutzer entscheidet
        m365.fremdSpeichern("drive-t1", TeamSync.DATEI, m365.text("drive-t1", TeamSync.DATEI).replace("Montag", "Dienstag"));
        Holzliste b1 = Storage.laden(c, b.id);
        b1.notiz = "Kran kommt am Mittwoch";
        Storage.speichern(c, b1); // Hintergrund-Upload erkennt den geänderten Team-Stand
        assertTrue(TeamSync.konflikt(c, b.id));
        assertTrue(m365.text("drive-t1", TeamSync.DATEI).contains("Dienstag"));
        TeamSync.loesen(c, b.id, false); // meinen Stand hochladen
        assertFalse(TeamSync.konflikt(c, b.id));
        assertTrue(m365.text("drive-t1", TeamSync.DATEI).contains("Mittwoch"));

        // andersherum: Team-Stand übernehmen
        m365.fremdSpeichern("drive-t1", TeamSync.DATEI, m365.text("drive-t1", TeamSync.DATEI).replace("Mittwoch", "Donnerstag"));
        Holzliste b2 = Storage.laden(c, b.id);
        b2.notiz = "lokal";
        Storage.speichern(c, b2);
        assertTrue(TeamSync.konflikt(c, b.id));
        TeamSync.loesen(c, b.id, true);
        assertEquals("Kran kommt am Donnerstag", Storage.laden(c, b.id).notiz);
        assertFalse(TeamSync.offen(c, b.id));

        // kein Netz: Änderung bleibt offen und geht beim nächsten Anstoß raus
        m365.offline = true;
        Holzliste b3 = Storage.laden(c, b.id);
        b3.notiz = "offline geschrieben";
        Storage.speichern(c, b3);
        assertTrue(TeamSync.offen(c, b.id));
        assertEquals("⟳ wartet aufs Netz", TeamSync.statusText(c, b3));
        m365.offline = false;
        TeamSync.anstossen(c);
        assertFalse(TeamSync.offen(c, b.id));
        assertTrue(m365.text("drive-t1", TeamSync.DATEI).contains("offline geschrieben"));
    }

    @Test
    public void verknuepfenMitBelegtemTeam() throws Exception {
        Holzliste a = baustelleMitRapport();
        TeamSync.verknuepfen(c, a, m365.teams.get(0));
        anderesHandy();
        Holzliste x = ScreenshotTest.beispiel();
        x.projekt = "Andere Liste";
        Storage.speichern(c, x);
        // im Team liegt schon eine Baustelle → nicht überschreiben
        assertEquals(TeamSync.Ergebnis.KONFLIKT, TeamSync.verknuepfen(c, x, m365.teams.get(0)));
        assertTrue(m365.text("drive-t1", TeamSync.DATEI).contains("EFH Huber"));
        TeamSync.loesen(c, x.id, true);
        assertEquals("EFH Huber – Dachstuhl", Storage.laden(c, x.id).projekt);
    }

    @Test
    public void berichtInsTeam() throws Exception {
        Holzliste a = baustelleMitRapport();
        TeamSync.verknuepfen(c, a, m365.teams.get(0));
        Holzliste h = Storage.laden(c, a.id);
        Rapport r = h.rapporte.get(0);

        m365.offline = true;
        TeamSync.ablegen(c, h, r);
        assertTrue(TeamSync.wartet(c, r.id));
        assertEquals(0, TeamSync.abgelegtAm(c, r.id));
        m365.offline = false;
        TeamSync.anstossen(c);
        assertFalse(TeamSync.wartet(c, r.id));
        assertTrue(TeamSync.abgelegtAm(c, r.id) > 0);
        String pdf = m365.text("drive-t1", "Rapporte/" + RapportPdf.dateiname(h, r));
        assertEquals("%PDF-1.4 Rapport", pdf);
        assertTrue(TeamSync.abgelegtUrl(c, r.id).startsWith("https://firma.sharepoint.com/Rapporte/"));
        assertEquals(1, m365.beitraege.size());
        String b = m365.beitraege.get(0);
        assertTrue(b, b.contains("📋 Rapport 25.11.2026"));
        assertTrue(b, b.contains("EFH Huber – Dachstuhl"));
        assertTrue(b, b.contains("1 Foto"));
        assertTrue(b, b.contains("https://firma.sharepoint.com/Rapporte/"));

        // Abnahme kommt in den Ordner „Abnahmen“
        Rapport ab = Rapport.neu(Rapport.ABNAHME, ZeiterfassungTest.um(26, 11, 9, 0), h, new ArrayList<>(), "", 0);
        ab.ergebnis = Rapport.MIT_MAENGELN;
        Rapport.Mangel m = new Rapport.Mangel(); m.text = "Fuge <offen> & undicht";
        ab.maengel.add(m);
        h.rapporte.add(ab);
        Storage.speichern(c, h);
        TeamSync.ablegen(c, h, ab);
        assertNotNull(m365.text("drive-t1", "Abnahmen/" + RapportPdf.dateiname(h, ab)));
        assertTrue(m365.beitraege.get(1).contains("1 offene Mängel"));
    }

    @Test
    public void bildschirme() throws Exception {
        Holzliste a = baustelleMitRapport();
        TeamSync.verknuepfen(c, a, m365.teams.get(0));
        TeamsKonto.einrichten(c, "11111111-2222-3333-4444-555555555555", "");
        m365.team("t3", "Garage Schmidt");
        TeamsActivity.testTeams = new Graph(m365, () -> "zugang").meineTeams();

        Activity teams = Robolectric.buildActivity(TeamsActivity.class).setup().get();
        ShadowLooper.idleMainLooper();
        ScreenshotTest.png(teams.getWindow().getDecorView(), "teams_1_meine_teams");

        TeamsActivity.testTeams = null;
        TeamsKonto.einrichten(c, "", "");
        Activity einr = Robolectric.buildActivity(TeamsActivity.class).setup().get();
        ShadowLooper.idleMainLooper();
        ScreenshotTest.png(einr.getWindow().getDecorView(), "teams_0_einrichten");

        Intent i = new Intent(c, BaustelleActivity.class);
        i.putExtra(BaustelleActivity.EXTRA_ID, a.id);
        Activity bau = Robolectric.buildActivity(BaustelleActivity.class, i).setup().get();
        ShadowLooper.idleMainLooper();
        org.robolectric.shadows.ShadowDialog.getLatestDialog().dismiss();
        ShadowLooper.idleMainLooper();
        android.widget.ScrollView sv = (android.widget.ScrollView) ((android.view.ViewGroup) bau.findViewById(android.R.id.content)).getChildAt(0);
        sv.scrollTo(0, 100000);
        ShadowLooper.idleMainLooper();
        ScreenshotTest.png(bau.getWindow().getDecorView(), "teams_2_baustelle");

        Activity start = Robolectric.buildActivity(MainActivity.class).setup().get();
        ShadowLooper.idleMainLooper();
        ScreenshotTest.png(start.getWindow().getDecorView(), "teams_3_start");
    }
}
