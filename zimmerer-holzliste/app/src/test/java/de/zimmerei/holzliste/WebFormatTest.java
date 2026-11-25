package de.zimmerei.holzliste;

import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * Gegenprobe zur PC-Version: liest die Baustellen-Datei, die die Web-App im Browser-Test gespeichert hat
 * (web-test/webtest.js → build/web-beispiel/vom-web.json), und rechnet sie mit der Logik der Handy-App nach.
 * Läuft nur, wenn der Browser-Test vorher gelaufen ist.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class WebFormatTest {

    private static Position finde(Holzliste h, String bez) {
        for (Position p : h.positionen) if (bez.equals(p.bezeichnung)) return p;
        return null;
    }

    @Test
    public void handyLiestWasDerPcGespeichertHat() throws Exception {
        File f = new File("build/web-beispiel/vom-web.json");
        Assume.assumeTrue("Browser-Test noch nicht gelaufen", f.exists());
        Holzliste h = Holzliste.fromJson(new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)));

        Position tr = finde(h, "Trittschalldämmung");
        assertNotNull(tr);
        assertEquals(Position.MATERIAL, tr.typ);
        assertEquals(11.0, tr.getPackungen(), 1e-9);
        assertEquals("11 Paket", tr.bestellText());
        assertEquals(509.08, tr.getBetrag(), 1e-6);

        assertEquals(26.0, finde(h, "Sparren").stueck, 1e-9);
        assertEquals(2.0, finde(h, "Firstpfette").stueck, 1e-9);
        assertNull(finde(h, "Bodenplatten"));
        assertEquals("Vom Handy", h.positionen.get(0).bezeichnung);
        assertEquals(Position.TITEL, finde(h, "Boden EG").typ);
        assertEquals(1502.18, h.summeBetrag(), 1e-6);
        assertEquals(3, h.rapporte.size());
        assertEquals(2, h.aufgaben.size()); // gespeichert vor dem Aufgaben-Teil des Browser-Tests
    }
}
