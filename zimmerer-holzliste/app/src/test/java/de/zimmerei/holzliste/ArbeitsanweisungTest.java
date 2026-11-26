package de.zimmerei.holzliste;

import android.content.Intent;
import android.net.Uri;
import android.view.View;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream;
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Arbeitsanweisung (PDF) → Überschriften + Materialvorschläge. Beispieltexte sind ausgedacht. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 34, qualifiers = "w393dp-h851dp-port-xxhdpi")
public class ArbeitsanweisungTest {

    // Aufbau wie eine Arbeitsanweisung aus der Auftrags-Software (Pos. / Menge / Einh. / Beschreibung)
    static final String LV = String.join("\n",
            "Zimmerei Muster GmbH - Hauptstr. 1 - 12345 Musterstadt",
            "Herrn",
            "Max Mustermann",
            "Arbeitsanweisung",
            "Bauvorhaben : Musterweg 5, 12345 Musterstadt Seite :1",
            "Pos. Menge Einh. Beschreibung",
            "1",
            "Beschreibung",
            "\uF0B7 Demontage des alten Geländers",
            "\uF0B7 Erneuern der Konstruktion",
            "1",
            "Baustelleneinrichtung",
            "1.1 1,00 psch Baustelleneinrichtung,",
            "Standard",
            "1.2 4,00 Stück Anfahrtspauschale bis 20 km,",
            "2",
            "Zimmererarbeiten",
            "2.1 40,00 h Stundensatz Facharbeiter,",
            "2.2",
            "20,00 LFM",
            "Konstruktionsvollholz Fichte",
            "120x120x5000 mm",
            "2.3 30,00 LFM Balkonbretter Fi./Ta. 26x142x5100 mm",
            "2.4 2,50 Liter Adler Pullex Plus-Lasur 2,5 lt Palisander",
            "2.5 1,00 Materialkostenschätzung");

    static final String FREI = String.join("\n",
            "Bauvorhaben: EFH Maier, Dachsanierung",
            "Kunde: Familie Maier",
            "1. Dachstuhl",
            "Sparren 8/20 ergänzen, ca. 6 Stück, KVH C24.",
            "2. Dämmung",
            "Zwischensparrendämmung 200 mm, ca. 85 m² einbauen.",
            "Dampfbremse raumseitig verlegen und Stöße mit Klebeband abkleben.",
            "3. Lattung",
            "Konterlattung 40/60 und Dachlattung 30/50 aufbringen, ca. 320 lfm.",
            "Hinweise:",
            "Arbeitsschutz beachten.");

    static Katalog.Eintrag artikel(String nr, String text, String einheit, Double b, Double h) {
        Datanorm.Artikel a = new Datanorm.Artikel();
        a.nr = nr; a.text = text; a.einheit = einheit; a.b = b; a.h = h;
        a.kategorie = Kategorien.zuordnen(text);
        return new Katalog.Eintrag(a, "Inventar");
    }

    static List<Katalog.Eintrag> inventar() {
        List<Katalog.Eintrag> l = new ArrayList<>();
        l.add(artikel("1001", "KVH Fichte NSi C24 120/120 mm", "m", 12.0, 12.0));
        l.add(artikel("1002", "KVH Fichte NSi C24 80/200 mm", "m", 8.0, 20.0));
        l.add(artikel("1003", "Balkonbretter Fichte/Tanne 26x142 mm", "m", 2.6, 14.2));
        l.add(artikel("2001", "Adler Pullex Plus-Lasur Lärche 2,5 l", "Dose", null, null));
        l.add(artikel("2002", "Adler Pullex Plus-Lasur Palisander 2,5 l", "Dose", null, null));
        l.add(artikel("3001", "Isover Klemmfilz 035 140 mm", "m²", null, null));
        l.add(artikel("3002", "Isover Klemmfilz 035 200 mm", "m²", null, null));
        l.add(artikel("3003", "pro clima Intello Plus Dampfbremse 1,5x50 m", "Rolle", null, null));
        return l;
    }

    @Test
    public void leistungsverzeichnis() {
        Arbeitsanweisung.Ergebnis e = Arbeitsanweisung.auswerten(LV, inventar(), "26-00001-AW BV Mustermann Balkon.pdf");
        assertEquals("BV Mustermann Balkon", e.projekt);
        assertEquals("Max Mustermann", e.kunde);
        assertEquals(2, e.arbeitsschritte.size());
        assertEquals(4, e.uebersprungen); // Pauschale, Anfahrt, Stunden, Materialkostenschätzung
        assertEquals(1, e.abschnitte.size());
        assertEquals("Zimmererarbeiten", e.abschnitte.get(0).titel);
        List<Arbeitsanweisung.Vorschlag> v = e.abschnitte.get(0).vorschlaege;
        assertEquals(3, v.size());
        assertEquals(Position.LFM, v.get(0).typ);
        assertEquals(12.0, v.get(0).b, 0);
        assertEquals(20.0, v.get(0).menge, 0);
        assertEquals("1001", v.get(0).artikel.a.nr);
        assertEquals("1003", v.get(1).artikel.a.nr);
        assertEquals("2002", v.get(2).artikel.a.nr); // Palisander, nicht Lärche

        List<Position> p = Arbeitsanweisung.positionen(e);
        assertEquals(Position.TITEL, p.get(0).typ);
        assertEquals("20 m", p.get(1).bestellText());
        assertEquals("2,5 l", p.get(3).bestellText());
    }

    @Test
    public void freitext() {
        Arbeitsanweisung.Ergebnis e = Arbeitsanweisung.auswerten(FREI, inventar(), "Scan_0001.pdf");
        assertEquals("EFH Maier, Dachsanierung", e.projekt);
        assertEquals("Familie Maier", e.kunde);
        assertEquals(3, e.abschnitte.size());
        Arbeitsanweisung.Vorschlag sparren = e.abschnitte.get(0).vorschlaege.get(0);
        assertEquals("Sparren 8/20", sparren.name);
        assertEquals(6.0, sparren.menge, 0);
        assertEquals("1002", sparren.artikel.a.nr);
        assertEquals(1, e.abschnitte.get(0).vorschlaege.size()); // kein doppeltes „Kanthölzer“ wegen „KVH“
        Arbeitsanweisung.Vorschlag daemm = e.abschnitte.get(1).vorschlaege.get(0);
        assertEquals("Dämmung 200 mm", daemm.name);
        assertEquals(85.0, daemm.menge, 0);
        assertEquals("3002", daemm.artikel.a.nr);
        assertNull(e.abschnitte.get(2).vorschlaege.get(1).artikel); // Konterlatte 4/6 nicht im Inventar
        assertEquals(320.0, e.abschnitte.get(2).vorschlaege.get(0).menge, 0); // Dachlatten 3/5
        assertTrue(e.abschnitte.get(2).vorschlaege.get(1).menge == null);
    }

    /** Echte PDF erzeugen, über PdfText lesen, auswerten und die Vorschau als Bild speichern. */
    @Test
    public void pdfLesenUndVorschau() throws Exception {
        PDFBoxResourceLoader.init(RuntimeEnvironment.getApplication());
        File pdf = new File("build/screenshots/test-aw.pdf");
        pdf.getParentFile().mkdirs();
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setFont(PDType1Font.HELVETICA, 10);
                float y = 750;
                for (String z : LV.replace("\uF0B7 ", "- ").split("\n")) {
                    cs.beginText();
                    cs.newLineAtOffset(40, y);
                    cs.showText(z);
                    cs.endText();
                    y -= 14;
                }
            }
            doc.save(pdf);
        }
        String text = PdfText.lesen(RuntimeEnvironment.getApplication(), Uri.fromFile(pdf));
        assertTrue(text, text.contains("Balkonbretter"));
        Arbeitsanweisung.Ergebnis e = Arbeitsanweisung.auswerten(text, inventar(), "26-00001-AW BV Mustermann Balkon.pdf");
        assertEquals(3, e.anzahlVorschlaege());

        Holzliste h = new Holzliste();
        Storage.speichern(RuntimeEnvironment.getApplication(), h);
        Intent i = new Intent(RuntimeEnvironment.getApplication(), EditorActivity.class);
        i.putExtra(EditorActivity.EXTRA_ID, h.id);
        EditorActivity ed = Robolectric.buildActivity(EditorActivity.class, i).setup().get();
        AwImport.vorschau(ed, e, h, () -> {});
        ShadowLooper.idleMainLooper();
        assertNotNull(ShadowDialog.getLatestDialog());
        ScreenshotTest.png(ShadowDialog.getLatestDialog().getWindow().getDecorView(), "11_arbeitsanweisung");
    }

    /** Werkzeug-Reiter: Vorschläge, Speichern, Ansicht. */
    @Test
    public void werkzeug() throws Exception {
        Arbeitsanweisung.Ergebnis e = Arbeitsanweisung.auswerten(LV, inventar(), "26-00001-AW BV Mustermann Balkon.pdf");
        Holzliste h = new Holzliste();
        h.projekt = e.projekt;
        h.positionen.addAll(Arbeitsanweisung.positionen(e));
        List<Werkzeug.Vorschlag> v = Werkzeug.vorschlagen(h, e.arbeitsschritte);
        List<String> namen = new ArrayList<>();
        for (Werkzeug.Vorschlag x : v) namen.add(x.name);
        assertTrue(namen.toString(), namen.contains("Kappsäge"));            // wegen Holz
        assertTrue(namen.toString(), namen.contains("Pinsel & Farbroller")); // wegen Lasur
        assertTrue(namen.toString(), namen.contains("Säbelsäge"));           // wegen „Demontage des alten Geländers“
        for (String std : Werkzeug.GRUNDAUSSTATTUNG) assertTrue(std, !namen.contains(std)); // Standard nicht einzeln
        for (Werkzeug.Vorschlag x : v) if (x.name.equals("Kappsäge")) assertEquals(Werkzeug.ROT, x.prio); // vom Material
        for (int k = 1; k < v.size(); k++) assertTrue(v.get(k - 1).prio >= v.get(k).prio);                  // rot zuerst
        for (Werkzeug.Vorschlag x : v) { Werkzeug w = new Werkzeug(x.name, x.grund); w.prio = x.prio; h.werkzeug.add(w); }
        h.werkzeug.get(0).eingepackt = true;
        h.werkzeug.get(1).anzahl = 2;
        assertTrue(Werkzeug.vorschlagen(h, e.arbeitsschritte).isEmpty()); // nichts doppelt

        Storage.speichern(RuntimeEnvironment.getApplication(), h);
        Holzliste geladen = Storage.laden(RuntimeEnvironment.getApplication(), h.id);
        assertEquals(h.werkzeug.size(), geladen.werkzeug.size());
        assertTrue(geladen.werkzeug.get(0).eingepackt);
        assertEquals(Integer.valueOf(2), geladen.werkzeug.get(1).anzahl);
        assertEquals(h.werkzeug.get(0).prio, geladen.werkzeug.get(0).prio);

        android.app.Activity main = Robolectric.buildActivity(MainActivity.class).setup().get();
        ShadowLooper.idleMainLooper();
        ScreenshotTest.png(main.getWindow().getDecorView(), "12_start");

        Intent i = new Intent(RuntimeEnvironment.getApplication(), EditorActivity.class);
        i.putExtra(EditorActivity.EXTRA_ID, h.id);
        i.putExtra(EditorActivity.EXTRA_REITER, EditorActivity.R_WERKZEUG);
        EditorActivity ed = Robolectric.buildActivity(EditorActivity.class, i).setup().get();
        ShadowLooper.idleMainLooper();
        ScreenshotTest.png(ed.getWindow().getDecorView(), "13_werkzeug");
    }

    /** To-do-Liste aus den Positionen und Arbeitsschritten. */
    @Test
    public void aufgaben() throws Exception {
        Arbeitsanweisung.Ergebnis e = Arbeitsanweisung.auswerten(LV, inventar(), "26-00001-AW BV Mustermann Balkon.pdf");
        List<String> texte = new ArrayList<>();
        for (Aufgabe a : e.aufgaben) texte.add(a.text);
        assertTrue(texte.toString(), texte.contains("Demontage des alten Geländers"));
        assertTrue(texte.toString(), texte.contains("Baustelleneinrichtung"));
        assertTrue(texte.toString(), texte.contains("Balkonbretter Fi./Ta. 26x142x5100 mm"));
        for (String t : texte) assertTrue(t, !t.toLowerCase().contains("stundensatz") && !t.toLowerCase().contains("anfahrt"));
        Aufgabe bretter = e.aufgaben.get(texte.indexOf("Balkonbretter Fi./Ta. 26x142x5100 mm"));
        assertEquals("Zimmererarbeiten", bretter.abschnitt);
        assertEquals("Pos. 2.3 · 30 m", bretter.info);

        Holzliste h = new Holzliste();
        h.projekt = e.projekt;
        h.positionen.addAll(Arbeitsanweisung.positionen(e));
        for (Aufgabe a : e.aufgaben) h.aufgaben.add(a.copy());
        h.aufgaben.get(0).erledigt = true;
        h.aufgaben.get(1).erledigt = true;
        Storage.speichern(RuntimeEnvironment.getApplication(), h);
        Holzliste geladen = Storage.laden(RuntimeEnvironment.getApplication(), h.id);
        assertEquals(h.aufgaben.size(), geladen.aufgaben.size());
        assertEquals(2, geladen.anzahlErledigt());

        Intent i = new Intent(RuntimeEnvironment.getApplication(), EditorActivity.class);
        i.putExtra(EditorActivity.EXTRA_ID, h.id);
        i.putExtra(EditorActivity.EXTRA_REITER, EditorActivity.R_AUFGABEN);
        EditorActivity ed = Robolectric.buildActivity(EditorActivity.class, i).setup().get();
        ShadowLooper.idleMainLooper();
        ScreenshotTest.png(ed.getWindow().getDecorView(), "14_aufgaben");

        // Tages-To-do: drei Aufgaben für heute, eine dringend
        Holzliste h2 = Storage.laden(RuntimeEnvironment.getApplication(), h.id);
        h2.aufgaben.get(2).heute = true;
        h2.aufgaben.get(3).heute = true;
        h2.aufgaben.get(3).prio = Werkzeug.ROT;
        h2.aufgaben.get(4).heute = true;
        h2.aufgaben.get(4).prio = Werkzeug.GRUEN;
        Storage.speichern(RuntimeEnvironment.getApplication(), h2);
        Holzliste g2 = Storage.laden(RuntimeEnvironment.getApplication(), h.id);
        assertTrue(g2.aufgaben.get(3).heute);
        assertEquals(Werkzeug.ROT, g2.aufgaben.get(3).prio);
        assertEquals(3, AufgabenAnsicht.anzahlHeute(g2, false));
        EditorActivity ed2 = Robolectric.buildActivity(EditorActivity.class, i).setup().get();
        ShadowLooper.idleMainLooper();
        assertTrue(AufgabenAnsicht.heuteModus(g2));
        ScreenshotTest.png(ed2.getWindow().getDecorView(), "14b_heute");
        AufgabenAnsicht.heuteWaehlen(ed2, g2, () -> {});
        ShadowLooper.idleMainLooper();
        ScreenshotTest.png(ShadowDialog.getLatestDialog().getWindow().getDecorView(), "14c_heute_waehlen");
    }

    /** Material: Kästchen-Auswahl über langes Drücken. */
    @Test
    public void materialAuswahl() throws Exception {
        Arbeitsanweisung.Ergebnis e = Arbeitsanweisung.auswerten(LV, inventar(), "26-00001-AW BV Mustermann Balkon.pdf");
        Holzliste h = new Holzliste();
        h.projekt = e.projekt;
        h.positionen.addAll(Arbeitsanweisung.positionen(e));
        Storage.speichern(RuntimeEnvironment.getApplication(), h);
        Intent i = new Intent(RuntimeEnvironment.getApplication(), EditorActivity.class);
        i.putExtra(EditorActivity.EXTRA_ID, h.id);
        EditorActivity ed = Robolectric.buildActivity(EditorActivity.class, i).setup().get();
        android.view.ViewGroup karten = (android.view.ViewGroup) ((android.view.ViewGroup) ((android.view.ViewGroup) ((android.view.ViewGroup)
                ((android.view.ViewGroup) ed.findViewById(android.R.id.content)).getChildAt(0)).getChildAt(2)).getChildAt(0)).getChildAt(0);
        int vorher = h.positionen.size();
        // erste echte Karte (nicht Überschrift) lange drücken
        View karte = null;
        for (int k = 0; k < karten.getChildCount() && karte == null; k++)
            if (!h.positionen.get(k).isTitel()) karte = karten.getChildAt(k);
        assertNotNull(karte);
        karte.performLongClick();
        ShadowLooper.idleMainLooper();
        ScreenshotTest.png(ed.getWindow().getDecorView(), "17_material_auswahl");
        assertTrue(vorher > 1);
    }

    /** Baustellen-Übersicht: Kacheln je Ast, die Kopfdaten oben; AW-Felder werden gespeichert. */
    @Test
    public void baustelle() throws Exception {
        Arbeitsanweisung.Ergebnis e = Arbeitsanweisung.auswerten(LV, inventar(), "26-00001-AW BV Mustermann Balkon.pdf");
        Holzliste h = new Holzliste();
        h.projekt = e.projekt;
        h.kunde = "Max Mustermann";
        h.bauteil = "Balkon";
        h.datum = "24.11.2026";
        h.awDatei = "26-00001-AW BV Mustermann Balkon.pdf";
        h.awDatum = "24.11.2026";
        h.positionen.addAll(Arbeitsanweisung.positionen(e));
        for (Aufgabe a : e.aufgaben) h.aufgaben.add(a.copy());
        h.aufgaben.get(0).erledigt = true;
        for (Werkzeug.Vorschlag x : Werkzeug.vorschlagen(h, e.arbeitsschritte)) h.werkzeug.add(new Werkzeug(x.name, x.grund));
        h.werkzeug.get(0).eingepackt = true;
        h.notiz = "Schlüssel beim Nachbarn (Hausnr. 3)";
        Storage.speichern(RuntimeEnvironment.getApplication(), h);
        Holzliste geladen = Storage.laden(RuntimeEnvironment.getApplication(), h.id);
        assertEquals(h.awDatei, geladen.awDatei);
        assertEquals("24.11.2026", geladen.awDatum);
        assertEquals("", Storage.duplizieren(RuntimeEnvironment.getApplication(), geladen).awDatei);

        Intent i = new Intent(RuntimeEnvironment.getApplication(), BaustelleActivity.class);
        i.putExtra(BaustelleActivity.EXTRA_ID, h.id);
        BaustelleActivity b = Robolectric.buildActivity(BaustelleActivity.class, i).setup().get();
        ShadowLooper.idleMainLooper();
        ScreenshotTest.png(b.getWindow().getDecorView(), "15_baustelle");

        // Kachel „Werkzeug“ öffnet die eigene Werkzeug-Seite
        android.view.ViewGroup scroll = (android.view.ViewGroup) ((android.view.ViewGroup) b.findViewById(android.R.id.content)).getChildAt(0);
        android.view.ViewGroup inhalt = (android.view.ViewGroup) scroll.getChildAt(0);
        ((android.view.ViewGroup) inhalt.getChildAt(2)).getChildAt(1).performClick();
        Intent naechste = org.robolectric.Shadows.shadowOf(b).getNextStartedActivity();
        assertEquals(EditorActivity.class.getName(), naechste.getComponent().getClassName());
        assertEquals(EditorActivity.R_WERKZEUG, naechste.getIntExtra(EditorActivity.EXTRA_REITER, -1));

        // Neue Baustelle: Start zeigt die Übersicht mit Kopfdaten-Dialog
        android.app.Activity main = Robolectric.buildActivity(MainActivity.class).setup().get();
        ShadowLooper.idleMainLooper();
        ScreenshotTest.png(main.getWindow().getDecorView(), "16_start");
    }

    /** Schlichte Bestellliste: Holz und Material getrennt, ohne Überschriften und Kundendaten. */
    @Test
    public void bestellliste() throws Exception {
        Holzliste h = new Holzliste();
        h.projekt = "BV Mustermann Balkon";
        h.kunde = "Max Mustermann";
        Position t = new Position(); t.typ = Position.TITEL; t.bezeichnung = "Zimmererarbeiten"; h.positionen.add(t);
        Position k = new Position(); k.typ = Position.HOLZ; k.bezeichnung = "KVH"; k.holzart = "Fichte";
        k.stueck = 4.0; k.breite = 12.0; k.hoehe = 12.0; k.laenge = 5.0; h.positionen.add(k);
        Position s = new Position(); s.typ = Position.MATERIAL; s.bezeichnung = "Terrassenschrauben 5x60";
        s.menge = 400.0; s.einheit = "Stk"; s.packInhalt = 200.0; s.packName = "Paket"; h.positionen.add(s);
        assertEquals(1, Bestellliste.anzahl(h, Bestellliste.HOLZ));
        assertEquals(1, Bestellliste.anzahl(h, Bestellliste.MATERIAL));
        assertEquals(2, Bestellliste.anzahl(h, Bestellliste.ALLES));
        Object[] zk = Bestellliste.zeile(k);
        assertEquals(4.0, (Double) zk[0], 0.001);
        assertEquals("KVH Fichte", zk[2]);
        assertEquals("12/12", zk[3]);
        Object[] zs = Bestellliste.zeile(s);
        assertEquals(2.0, (Double) zs[0], 0.001);
        assertEquals("Paket", zs[1]);
        assertEquals("à 200 Stk", zs[6]);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        Bestellliste.write(h, Bestellliste.HOLZ, out);
        assertTrue(out.size() > 1000);
    }

    /** Ausgedachte AW, bei der unter den Materialpositionen beschrieben ist, was zu tun ist (Langtext). */
    static final String MIT_LANGTEXT = String.join("\n",
            "Zimmerei Muster GmbH - Hauptstr. 1 - 12345 Musterstadt",
            "Arbeitsanweisung",
            "Bauvorhaben : Musterweg 5, 12345 Musterstadt Seite :1",
            "Pos. Menge Einh. Beschreibung",
            "2",
            "Zimmererarbeiten",
            "2.1 12,00 m² Holzfaserdämmplatte 60 mm",
            "Liefern und Verlegen der Holzfaserdämmplatten auf der",
            "vorhandenen Schalung, Stöße dicht stoßen.",
            "Bedarf",
            "2.2 20,00 LFM Konstruktionsvollholz Fichte",
            "120x120x5000 mm",
            "Neue Stützen unter dem Balkon setzen und mit",
            "Zimmerei Muster GmbH - Hauptstr. 1 - 12345 Musterstadt",
            "Arbeitsanweisung",
            "Bauvorhaben : Musterweg 5, 12345 Musterstadt Seite :2",
            "Pos. Menge Einh. Beschreibung",
            "Winkelverbindern am Fundament verschrauben.",
            "Hinweis: Statik beachten, Abstände laut",
            "Plan einhalten.",
            "2.3 30,00 LFM Balkonbretter Fi./Ta. 26x142x5100 mm",
            "2.4 1,00 psch Baustelle räumen",
            "Reinigen der Arbeitsfläche");

    @Test
    public void aufgabenMitBeschreibung() {
        Arbeitsanweisung.Ergebnis e = Arbeitsanweisung.auswerten(MIT_LANGTEXT, inventar(), "26-00002-AW BV Muster Balkon.pdf");
        java.util.Map<String, Aufgabe> nachInfo = new java.util.LinkedHashMap<>();
        for (Aufgabe a : e.aufgaben) nachInfo.put(a.info, a);
        Aufgabe daemm = nachInfo.get("Pos. 2.1 · 12 m² · Holzfaserdämmplatte 60 mm");
        assertEquals(nachInfo.keySet().toString(), "Liefern und Verlegen der Holzfaserdämmplatten auf der vorhandenen Schalung, Stöße dicht stoßen", daemm.text);
        // Seitenkopf mitten in der Beschreibung und der Hinweis werden übersprungen, Maße stehen nicht im Aufgabentext
        Aufgabe stuetzen = nachInfo.get("Pos. 2.2 · 20 m · Konstruktionsvollholz Fichte 120x120x5000 mm");
        assertEquals("Neue Stützen unter dem Balkon setzen und mit Winkelverbindern am Fundament verschrauben", stuetzen.text);
        // ohne Beschreibung bleibt es beim Material
        Aufgabe bretter = nachInfo.get("Pos. 2.3 · 30 m");
        assertEquals("Balkonbretter Fi./Ta. 26x142x5100 mm", bretter.text);
        assertEquals("Zimmererarbeiten", bretter.abschnitt);
        // Arbeitsposition wie bisher: Position + Schritte darunter
        List<String> texte = new ArrayList<>();
        for (Aufgabe a : e.aufgaben) texte.add(a.text);
        assertTrue(texte.toString(), texte.contains("Baustelle räumen"));
        assertTrue(texte.toString(), texte.contains("Reinigen der Arbeitsfläche"));
        for (String t : texte) assertTrue(t, !t.contains("Seite") && !t.contains("Statik") && !t.contains("Bedarf"));
        // Materialliste unverändert: Vorschläge mit Kurztext und Maßen
        List<Arbeitsanweisung.Vorschlag> v = e.abschnitte.get(0).vorschlaege;
        assertEquals(3, v.size());
        assertEquals(12.0, v.get(1).b, 0);
        assertEquals(5.0, v.get(1).laengeM, 0);

        // Werkzeug: die Beschreibung bringt die Arbeit mit („verschrauben“ → Akkuschrauber ist Standard, „Fundament“ → Bohrhammer)
        Holzliste h = new Holzliste();
        for (Aufgabe a : e.aufgaben) h.aufgaben.add(a);
        List<String> w = new ArrayList<>();
        for (Werkzeug.Vorschlag x : Werkzeug.vorschlagen(h, null)) w.add(x.name);
        assertTrue(w.toString(), w.contains("Bohrhammer"));
    }

    // Nachbildung echter Arbeitsanweisungen (Aufbau und Eigenheiten wie aus der Auftrags-Software; Firma, Kunde,
    // Bankdaten und Texte ausgedacht): Bankdaten-Fußzeile und Kunde vor „Arbeitsanweisung : … Seite 2“ beim
    // Seitenwechsel, übereinander gedruckte „Bedarf“/„Alternative“ + Positionsnummer, Aufzählung im Baustrom,
    // Angaben mit Platzhaltern, umbrochene Sätze, Abkürzungen am Zeilenende.
    static final String FUSS_SEITE = String.join("\n",
            "Musterbank Musterstadt Zimmerei Muster GmbH mit Sitz in Musterstadt.",
            "BLZ: 10000000 Ust-ID-Nr. DE000000000, Steuer-Nr: 00000000000 bei Finanzamt",
            "Kto: 1234567 Musterstadt.",
            "IBAN: DE00 1000 0000 0001 2345 67 HRB 000000, Registergericht Musterstadt",
            "Swift: MUSTDEF1XXX",
            "Erika Beispiel",
            "Arbeitsanweisung : 26-09999-AW Seite %d",
            "Pos. Menge Einh. Beschreibung",
            "  ",
            "Übertrag:   0,00 €");
    static final String WIE_ECHT = String.join("\n",
            "Zimmerei Muster GmbH - Hauptstr. 1 - 12345 Musterstadt Zimmerei Muster GmbH",
            "Erika Beispiel Hauptstr. 1",
            "Beispielweg 7 12345 Musterstadt",
            "Arbeitsanweisung",
            "Projektnummer : 26-09999-P Kundennr. :  0 0000 Datum : 01.08.2026",
            "Bauvorhaben : Beispielweg 7 BV Beispiel Seite : 1",
            "Pos. Menge Einh. Beschreibung",
            "1",
            "Baustelleneinrichtung",
            "1.1 1,00 psch Baustelleneinrichtung,",
            "Standard",
            "1.2 1,00 Monat Baustromverteiler",
            "Anschluss-Verteiler-Schrank",
            "Ausstattung:",
            "1 Kabeleinführung mit Zugentlastung",
            "2 CEE-Steckdosen 5/16 A, 400 V, 2 Automaten C 16",
            "6 Schukosteckdosen 3/16 A, 230 V",
            "1.3 1,00 Stück Beispiel Lift 250 Dachdeckerlift",
            "liefern und aufbauen und abbauen",
            "Traufhöhe bis 6,00 m",
            "Hersteller: Beispiel",
            "Tragfähigkeit bis 250kg, Hubgeschwindigkeit 30m/min",
            "A1.l4ternative 1 1,00 StWo Beispiel Lift 250 Dachdeckerlift",
            "Verlängerungsmiete",
            "1.5 1,00 t Verwertung von Holz behandelt A2-A3",
            "AVV 170201",
            "2",
            "Demontage",
            "2.1 40,00 m² Dachlattung entfernen",
            "Dachlattung entfernen und nach unten",
            "abtransportieren, entsorgen in gesonderter Position.",
            "Lattenweite : 320-340 mm",
            "Querschnitte : .....",
            "3",
            "Zimmererarbeiten",
            "B3.e1darf 1,00 m² Sparschalung,",
            "Dachkonstruktion, Holz, 24",
            "mm",
            "Sparschalung aus Brettern, sägerau, auf vorhandene",
            "Dachkonstruktion aus Holz.",
            "Holzart : Fichte / Tanne / Kiefer",
            "Brettabstand : .....",
            "3.2 40,00 m² Konterlattung, 40/60 mm auf Dachkonstruktion",
            "Konterlattung aus ungehobelten Latten aus Nadelholz",
            "auf vorhandene Dachkonstruktion,",
            "Holzfeuchte : mind. halbtrocken (< 30%)",
            String.format(FUSS_SEITE, 2),
            "Querschnitt : 40/60 mm",
            "Befestigung:bis zu 8 x 300 Teilgewindeschraube",
            "3.3 1,00 psch Einschalen der Gaubenseiten",
            "Anbringen einer 60 mm starken",
            "Holzweichfaserdämmplatte an den Gaubenseiten",
            "Dachöffnung herstellen, Wechselholz und",
            "Streichsparren liefern, abbinden und einbauen",
            "4",
            "Spenglerarbeiten",
            "4.1 30,00 m Halbrunde, vorgehängte Dachrinne aus farb.",
            "besch. Aluminium 0,7 mm stark,",
            "Abwicklung 33 cm, große Wulst, mit verz.",
            "Rinnenhaken im",
            "Gefälle montieren.",
            "(Zeitbedarf: 0 Stunde(n) + 0 Minute(n))",
            "4.2 6,00 Stück Bogen 72° aus farb. besch. Aluminium",
            "Durchmesser 100 mm komplett in das",
            "Regenablaufrohr einbauen als Zulage.",
            "4.3 40,00 m² Eindecken der Dachfläche mit beiderseits",
            "verzinnten Edelstahlscharen 0,5",
            "mm",
            "dick, Bandbreite 67 cm, doppelt gefalzter Stehfalz,",
            "Befestigung mit 6 Haften pro qm.",
            "Auftrag erteilt:",
            "Ort, Datum",
            "Unterschrift Auftraggeber",
            "Musterbank Musterstadt Zimmerei Muster GmbH mit Sitz in Musterstadt.",
            "IBAN: DE00 1000 0000 0001 2345 67 HRB 000000, Registergericht Musterstadt");

    @Test
    public void wieEchteArbeitsanweisung() {
        Arbeitsanweisung.Ergebnis e = Arbeitsanweisung.auswerten(WIE_ECHT, inventar(), "26-09999-AW BV Beispiel.pdf");
        assertEquals("Erika Beispiel", e.kunde); // aus der Fußzeile vor „Arbeitsanweisung : … Seite 2“
        List<String> kapitel = new ArrayList<>();
        for (Arbeitsanweisung.Abschnitt a : e.abschnitte) kapitel.add(a.titel);
        assertTrue(kapitel.toString(), !kapitel.toString().contains("CEE")); // Aufzählung ist kein Kapitel

        java.util.Map<String, Aufgabe> pos = new java.util.LinkedHashMap<>();
        for (Aufgabe a : e.aufgaben) if (a.info.startsWith("Pos. ")) pos.put(a.info.split(" · ")[0].substring(5), a);
        // übereinander gedruckt: „A1.l4ternative“ = Alternative 1.4, „B3.e1darf“ = Bedarf 3.1
        assertTrue(pos.keySet().toString(), pos.get("1.4").info.contains("Alternativposition"));
        assertTrue(pos.get("3.1").info.contains("Bedarfsposition"));
        // Lift: Kurztext ist die Aufgabe, technische Daten nicht; Angaben in der zweiten Zeile
        assertEquals("Beispiel Lift 250 Dachdeckerlift liefern und aufbauen und abbauen\nHersteller: Beispiel", pos.get("1.3").text);
        // Entsorgung: Kurztext, keine Abfallschlüssel
        assertEquals("Verwertung von Holz behandelt A2-A3", pos.get("1.5").text);
        // Beschreibung mit Angaben, Platzhalter „.....“ fallen weg
        assertEquals("Dachlattung entfernen und nach unten abtransportieren, entsorgen in gesonderter Position\nLattenweite: 320-340 mm", pos.get("2.1").text);
        assertEquals("Sparschalung aus Brettern, sägerau, auf vorhandene Dachkonstruktion aus Holz\nHolzart: Fichte / Tanne / Kiefer", pos.get("3.1").text);
        // Seitenwechsel mitten in der Beschreibung: Angaben von Seite 2 gehören noch zu 3.2
        assertEquals("Konterlattung aus ungehobelten Latten aus Nadelholz auf vorhandene Dachkonstruktion\n"
                + "Holzfeuchte: mind. halbtrocken (< 30%) · Querschnitt: 40/60 mm · Befestigung: bis zu 8 x 300 Teilgewindeschraube", pos.get("3.2").text);
        // umbrochene Sätze, auch mit Abkürzung („mit verz.“) und Maß-Fortsetzung („Durchmesser 100 mm …“)
        assertEquals("Halbrunde, vorgehängte Dachrinne aus farb. besch. Aluminium 0,7 mm stark, Abwicklung 33 cm, große Wulst, mit verz. "
                + "Rinnenhaken im Gefälle montieren", pos.get("4.1").text);
        assertEquals("Bogen 72° aus farb. besch. Aluminium Durchmesser 100 mm komplett in das Regenablaufrohr einbauen als Zulage", pos.get("4.2").text);
        assertEquals("Eindecken der Dachfläche mit beiderseits verzinnten Edelstahlscharen 0,5 mm dick, Bandbreite 67 cm, doppelt gefalzter "
                + "Stehfalz, Befestigung mit 6 Haften pro qm", pos.get("4.3").text);
        // Checkliste unter Pauschalposition: umbrochene Zeilen zusammen
        List<String> texte = new ArrayList<>();
        for (Aufgabe a : e.aufgaben) texte.add(a.text);
        assertTrue(texte.toString(), texte.contains("Anbringen einer 60 mm starken Holzweichfaserdämmplatte an den Gaubenseiten"));
        assertTrue(texte.toString(), texte.contains("Dachöffnung herstellen, Wechselholz und Streichsparren liefern, abbinden und einbauen"));
        for (String t : texte) assertTrue(t, !t.contains("IBAN") && !t.contains("Erika") && !t.contains(".....") && !t.contains("AVV") && !t.contains("Übertrag"));

        // Material: nur was man bestellt – nicht „Dachlattung entfernen“, „Verwertung“, Lift, Alternative
        List<String> material = new ArrayList<>();
        for (Arbeitsanweisung.Abschnitt a : e.abschnitte) for (Arbeitsanweisung.Vorschlag v : a.vorschlaege) material.add(v.name);
        for (String m : material) assertTrue(material.toString(), !m.contains("entfernen") && !m.contains("Verwertung") && !m.contains("Lift"));
        assertTrue(material.toString(), material.contains("Konterlattung, 40/60 mm auf Dachkonstruktion"));

        // Werkzeug: kein Elektrohobel wegen „ungehobelt“, kein Feuchtemessgerät wegen „Holzfeuchte“, kein Ziegelschneider bei Blech;
        // dafür Falzwerkzeug für den Stehfalz
        Holzliste h = new Holzliste();
        h.positionen.addAll(Arbeitsanweisung.positionen(e));
        h.aufgaben.addAll(e.aufgaben);
        List<String> w = new ArrayList<>();
        for (Werkzeug.Vorschlag x : Werkzeug.vorschlagen(h, e.arbeitsschritte)) w.add(x.name);
        assertTrue(w.toString(), !w.contains("Elektrohobel") && !w.contains("Feuchtemessgerät") && !w.contains("Ziegelschneider / Trennschleifer"));
        assertTrue(w.toString(), w.contains("Falzschließer (Hand/Maschine)") && w.contains("Lötkolben"));
    }

    /** Gescanntes Bild mit „.pdf“-Endung (kam so aus dem Scanner) → verständliche Meldung statt PdfBox-Fehler. */
    @Test
    public void bildStattPdf() throws Exception {
        byte[] tiff = {'I', 'I', 42, 0, 8, 0, 0, 0, 1, 2, 3};
        byte[] jpg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16};
        for (byte[] b : new byte[][]{tiff, jpg}) {
            try {
                PdfText.pruefen(new java.io.BufferedInputStream(new java.io.ByteArrayInputStream(b)));
                org.junit.Assert.fail("hätte als Bild erkannt werden müssen");
            } catch (java.io.IOException e) {
                assertTrue(e.getMessage(), e.getMessage().startsWith("Das ist ein eingescanntes Bild"));
            }
        }
        // echte PDF geht durch
        PdfText.pruefen(new java.io.BufferedInputStream(new java.io.ByteArrayInputStream("%PDF-1.7\n".getBytes())));
    }

    /** Ausgedachte Wartungs-AW (Positionen 1, 2, 3 ohne Kapitel) und Auftragsbestätigung mit Preisspalte. */
    static final String WARTUNG = "Zimmerei Muster GmbH - Hauptstr. 1 - 12345 Musterstadt\n"
            + "Arbeitsanweisung\nBauvorhaben : Musterweg 5 Seite :1\n"
            + "Pos. Menge Einh. Beschreibung\n"
            + "1 1,00 Stück Anfahrtspauschale bis 20 km,\nStandard\nAbrechnung St/Tag\n"
            + "2 1,00 psch Dachwartung Steildach bis 30°\n"
            + "Augenscheinliche Überprüfung der Ziegeldeckung\n"
            + "Reinigen der Dachrinnen und Fallrohre\n"
            + "Verwendung von persönlicher Schutzausrüstung nach\n"
            + "eigenem Ermessen in gesonderter Position.\n"
            + "Dokumentation der Dachsituation.\n"
            + "(Zeitbedarf: 10 Stunde(n) + 0 Minute(n))\n"
            + "3 2,00 Stück Braas Firststein matt dunkelbraun 12,47 € 24,94 €\n"
            + "Bedarf\n"
            + "4 1,00 SAC Sakret Dachdeckermörtel grau 12,96 € 12,96 €\n"
            + "Musterbank eG Zimmerei Muster GmbH mit Sitz in Musterstadt.\n"
            + "IBAN: DE00 0000 0000 0000 0000 00\n";

    @Test
    public void wartungUndPreise() {
        Arbeitsanweisung.Ergebnis e = Arbeitsanweisung.auswerten(WARTUNG, new ArrayList<>(), "26-00001-AW BV Muster Dachwartung.pdf");
        List<String> texte = new ArrayList<>();
        for (Aufgabe a : e.aufgaben) texte.add(a.text);
        assertTrue(texte.toString(), texte.contains("Dachwartung Steildach bis 30°"));
        assertTrue(texte.toString(), texte.contains("Reinigen der Dachrinnen und Fallrohre"));
        assertTrue(texte.toString(), texte.contains("Dokumentation der Dachsituation"));
        for (String t : texte) {
            assertTrue(t, !t.contains("IBAN") && !t.contains("GmbH") && !t.contains("Anfahrt") && !t.contains("Ermessen"));
        }
        List<String> material = new ArrayList<>();
        for (Arbeitsanweisung.Abschnitt a : e.abschnitte) for (Arbeitsanweisung.Vorschlag v : a.vorschlaege) material.add(v.name + "|" + v.einheit);
        assertEquals(material.toString(), 2, material.size());
        assertTrue(material.toString(), material.contains("Braas Firststein matt dunkelbraun|Stk")); // ohne Preise
        assertTrue(material.toString(), material.contains("Sakret Dachdeckermörtel grau|Sack"));
    }
}
