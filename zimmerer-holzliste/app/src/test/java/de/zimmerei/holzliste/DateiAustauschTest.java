package de.zimmerei.holzliste;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Baustelle als Datei: Handy → PC → Handy, ohne Microsoft Teams. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class DateiAustauschTest {

    @Test
    public void hinUndZurueck() throws Exception {
        Holzliste h = ScreenshotTest.beispiel();
        h.id = "abc";
        h.teamId = "t1"; h.teamName = "Team"; h.teamDrive = "d"; h.teamOrdner = "o"; h.teamKanal = "k";
        Rapport r = Rapport.neu(Rapport.ABNAHME, 0, h, new java.util.ArrayList<>(), "", 0);
        Rapport.Mangel m = new Rapport.Mangel(); m.text = "Fuge offen";
        r.maengel.add(m);
        h.rapporte.add(r);

        byte[] datei = DateiAustausch.exportieren(h);
        JSONObject o = new JSONObject(new String(datei, StandardCharsets.UTF_8));
        assertFalse("Team-Verknüpfung gehört nicht in die Datei", o.has("team"));
        assertEquals("EFH Huber - Dachstuhl.materialliste.json", DateiAustausch.dateiname(h));
        Holzliste u = new Holzliste();
        u.projekt = "Gartenhütte Müller/Weiß";
        assertEquals("Gartenhuette Mueller_Weiss.materialliste.json", DateiAustausch.dateiname(u));

        Holzliste zurueck = DateiAustausch.lesen(datei);
        assertEquals("abc", zurueck.id);
        assertEquals("", zurueck.teamId);
        assertEquals(h.positionen.size(), zurueck.positionen.size());
        assertEquals(h.summeBetrag(), zurueck.summeBetrag(), 1e-9);
        assertEquals("Fuge offen", zurueck.rapporte.get(0).maengel.get(0).text);
    }

    @Test
    public void keineBaustelle() {
        for (String s : new String[]{"hallo", "{\"x\":1}", ""}) {
            try {
                DateiAustausch.lesen(s.getBytes(StandardCharsets.UTF_8));
                fail("hätte abgelehnt werden müssen: " + s);
            } catch (IOException e) {
                assertEquals("Das ist keine Baustellen-Datei", e.getMessage());
            }
        }
    }

    /** Datei, die die Web-App am PC gespeichert hat (Browser-Test), lässt sich am Handy laden. */
    @Test
    public void dateiVomPc() throws Exception {
        File f = new File("build/web-beispiel/vom-pc.materialliste.json");
        org.junit.Assume.assumeTrue("Browser-Test noch nicht gelaufen", f.exists());
        Holzliste h = DateiAustausch.lesen(Files.readAllBytes(f.toPath()));
        assertEquals("Familie Huber (PC)", h.kunde);
        assertTrue(h.positionen.size() > 10);
        Position sp = null;
        for (Position p : h.positionen) if ("Sparren".equals(p.bezeichnung)) sp = p;
        assertEquals(30.0, sp.stueck, 1e-9);
    }
}
