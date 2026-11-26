package de.zimmerei.holzliste;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Werkzeug-Vorschläge lernen aus dem, was der Betrieb abwählt und selbst dazuschreibt. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class WerkzeugErfahrungTest {

    private static Holzliste dachstuhl() {
        Holzliste h = new Holzliste();
        h.positionen.add(ScreenshotTest.p(Position.HOLZ, "Sparren", 10.0, 8.0, 20.0, 6.0, null, null, null, "KVH C24", ""));
        return h;
    }

    private static Holzliste daemmung() {
        Holzliste h = new Holzliste();
        h.positionen.add(ScreenshotTest.material("Zwischensparrendämmung", 50.0, "m²", 5.0, 5.2, "Paket", "Mineralwolle 035"));
        return h;
    }

    private static Werkzeug.Vorschlag finde(List<Werkzeug.Vorschlag> l, String name) {
        for (Werkzeug.Vorschlag v : l) if (v.name.equals(name)) return v;
        return null;
    }

    @Test
    public void abgewaehltWirdSeltener() {
        Werkzeug.Erfahrung erf = new Werkzeug.Erfahrung();
        List<Werkzeug.Vorschlag> v = Werkzeug.vorschlagen(daemmung(), null, null, erf);
        assertTrue(finde(v, "FFP2-Masken").gewaehlt);
        boolean[] haken = new boolean[v.size()];
        for (int i = 0; i < v.size(); i++) haken[i] = !v.get(i).name.equals("FFP2-Masken");

        erf.entschieden(v, haken);
        Werkzeug.Vorschlag ffp = finde(Werkzeug.vorschlagen(daemmung(), null, null, erf), "FFP2-Masken");
        assertFalse("einmal abgewählt → nicht mehr vorangehakt", ffp.gewaehlt);
        assertTrue(finde(Werkzeug.vorschlagen(daemmung(), null, null, erf), "Dämmstoffmesser").gewaehlt);

        erf.entschieden(v, haken);
        erf.entschieden(v, haken);
        assertNull("3× abgewählt, nie genommen → weg", finde(Werkzeug.vorschlagen(daemmung(), null, null, erf), "FFP2-Masken"));
        assertTrue(finde(Werkzeug.vorschlagen(daemmung(), null, null, erf), "Dämmstoffmesser").gewaehlt);
    }

    @Test
    public void selbstDazugeschriebenesKommtWieder() throws Exception {
        Werkzeug.Erfahrung erf = new Werkzeug.Erfahrung();
        assertNull(finde(Werkzeug.vorschlagen(dachstuhl(), null, null, erf), "Kettensäge"));
        erf.selbstHinzugefuegt(dachstuhl(), "Kettensäge");
        assertNull("einmal ist noch keine Gewohnheit", finde(Werkzeug.vorschlagen(dachstuhl(), null, null, erf), "Kettensäge"));
        erf.selbstHinzugefuegt(dachstuhl(), "Kettensäge");
        Werkzeug.Vorschlag k = finde(Werkzeug.vorschlagen(dachstuhl(), null, null, erf), "Kettensäge");
        assertEquals("aus eurer Erfahrung: schon 2× bei „Holz“", k.grund);
        assertTrue(k.gewaehlt);
        assertNull("nur bei ähnlichen Baustellen", finde(Werkzeug.vorschlagen(daemmung(), null, null, erf), "Kettensäge"));

        // gespeichert und wieder geladen
        Werkzeug.Erfahrung zurueck = Werkzeug.Erfahrung.fromJson(erf.toJson());
        assertEquals("Kettensäge", finde(Werkzeug.vorschlagen(dachstuhl(), null, null, zurueck), "Kettensäge").name);

        // schon auf der Liste → kein Vorschlag
        Holzliste h = dachstuhl();
        h.werkzeug.add(new Werkzeug("Kettensäge", ""));
        assertNull(finde(Werkzeug.vorschlagen(h, null, null, zurueck), "Kettensäge"));
    }

    @Test
    public void arbeitenAusDemTextSindMerkmale() {
        Holzliste h = new Holzliste();
        h.aufgaben.add(new Aufgabe("", "Demontage des alten Geländers", ""));
        List<String> m = new ArrayList<>(Werkzeug.merkmale(h, null));
        assertTrue(m.toString(), m.contains("demontage"));
        assertTrue(m.toString(), m.contains("geländer"));
    }
}
