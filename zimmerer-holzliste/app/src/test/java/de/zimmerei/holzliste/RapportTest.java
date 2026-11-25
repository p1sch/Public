package de.zimmerei.holzliste;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Rapport: Übernahme von Stunden und Material, Speichern, PDF-Seiten und Bildschirme. Namen und Firma sind ausgedacht. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 34, qualifiers = "w393dp-h851dp-port-xxhdpi")
public class RapportTest {

    @After
    public void aufraeumen() {
        ZeitActivity.testJetzt = 0;
    }

    /** Baustelle mit Materialliste, gestempelten Zeiten am 25.11.2026 und einem ausgefüllten Rapport. */
    static Rapport beispiel(Context c, Holzliste h) throws Exception {
        Storage.speichern(c, h);
        List<Zeiteintrag> z = new ArrayList<>();
        z.add(ZeiterfassungTest.block(ZeiterfassungTest.um(25, 11, 7, 0), ZeiterfassungTest.um(25, 11, 12, 0), 0, h.id, h.titel(), "Abbund & Montage"));
        z.add(ZeiterfassungTest.block(ZeiterfassungTest.um(25, 11, 12, 30), ZeiterfassungTest.um(25, 11, 16, 15), 15, h.id, h.titel(), "Dämmung"));
        z.add(ZeiterfassungTest.block(ZeiterfassungTest.um(25, 11, 8, 0), ZeiterfassungTest.um(25, 11, 9, 0), 0, "andere", "Andere Baustelle", "Aufräumen"));
        z.add(ZeiterfassungTest.block(ZeiterfassungTest.um(24, 11, 7, 0), ZeiterfassungTest.um(24, 11, 16, 0), 30, h.id, h.titel(), "Abbund"));
        Zeiten.speichern(c, z);

        RapportDateien.setFirma(c, "Zimmerei Beispiel GmbH", "Holzweg 12\n12345 Musterstadt\nTel. 01234 / 56 78 90\ninfo@zimmerei-beispiel.de");
        RapportDateien.setBemerkungVorlage(c, RapportDateien.VOB_VORSCHLAG);
        try (FileOutputStream out = new FileOutputStream(RapportDateien.logo(c))) {
            logo().compress(Bitmap.CompressFormat.PNG, 100, out);
        }

        Rapport r = Rapport.neu(ZeiterfassungTest.um(25, 11, 18, 0), h, Zeiten.alle(c), RapportDateien.bemerkungVorlage(c),
                ZeiterfassungTest.um(25, 11, 18, 0));
        Rapport.ZeitZeile kollege = new Rapport.ZeitZeile();
        kollege.mitarbeiter = "Erika Beispiel"; kollege.leistung = "Abbund & Montage"; kollege.vonMin = 7 * 60; kollege.bisMin = 16 * 60 + 15; kollege.pauseMin = 30;
        r.zeiten.add(kollege);
        r.material.add(Rapport.ausPosition(h.positionen.get(1)));  // Sparren
        r.material.add(Rapport.ausPosition(h.positionen.get(12))); // OSB Wandbeplankung
        r.material.add(Rapport.ausPosition(h.positionen.get(15))); // Dämmung
        r.material.get(2).menge = 6.0; // nur ein Teil verbaut
        r.wetter = "⛅ bewölkt";
        r.temperatur = "6";
        r.eintrag = "Sparren abgebunden und aufgestellt, Wandbeplankung innen OSB montiert, Zwischensparrendämmung im Bereich Nordseite eingebracht.";
        r.behinderungen = "Ab 14 Uhr Nieselregen, Dämmarbeiten unterbrochen.";
        r.fotos.add(foto(c, "foto_test1.jpg", Color.parseColor("#B07A4A")));
        r.fotos.add(foto(c, "foto_test2.jpg", Color.parseColor("#7A8FA6")));
        r.fotos.add(foto(c, "foto_test3.jpg", Color.parseColor("#8FA67A")));
        r.unterschrift = RapportDateien.unterschriftSpeichern(c, unterschrift());
        r.unterzeichner = "Familie Huber";
        r.unterschriebenAm = ZeiterfassungTest.um(25, 11, 16, 40);
        h.rapporte.add(r);
        Storage.speichern(c, h);
        return r;
    }

    @Test
    public void stundenUndMaterial() throws Exception {
        Context c = RuntimeEnvironment.getApplication();
        Holzliste h = ScreenshotTest.beispiel();
        Rapport r = beispiel(c, h);

        // nur die beiden Blöcke vom 25.11. auf dieser Baustelle, plus Kollege
        assertEquals(3, r.zeiten.size());
        assertEquals(7 * 60, r.zeiten.get(0).vonMin);
        assertEquals(12 * 60, r.zeiten.get(0).bisMin);
        assertEquals("Dämmung", r.zeiten.get(1).leistung);
        assertEquals(3 * 60 + 30, r.zeiten.get(1).nettoMin());
        assertEquals(5 * 60 + 3 * 60 + 30 + 8 * 60 + 45, r.summeMin());
        assertEquals("Max Muster, Erika Beispiel", r.anwesende());

        // Material aus der Liste: Menge = Bestellmenge in der passenden Einheit
        assertEquals("Sparren", r.material.get(0).bezeichnung);
        assertEquals(24.0, r.material.get(0).menge, 1e-9);
        assertEquals("Stk", r.material.get(0).einheit);
        assertTrue(r.material.get(0).details.contains("8 × 20 cm"));
        assertEquals("Platten", r.material.get(1).einheit);
        assertEquals("Paket", r.material.get(2).einheit);

        // Speichern und wieder laden
        Holzliste neu = Storage.laden(c, h.id);
        assertEquals(1, neu.rapporte.size());
        Rapport r2 = neu.rapporte.get(0);
        assertEquals(r.nummer, r2.nummer);
        assertEquals(3, r2.zeiten.size());
        assertEquals(6.0, r2.material.get(2).menge, 1e-9);
        assertEquals(3, r2.fotos.size());
        assertTrue(r2.unterschrieben());
        assertEquals("Familie Huber", r2.unterzeichner);

        // Kopie einer Baustelle nimmt keine Rapporte mit
        assertTrue(Storage.duplizieren(c, neu).rapporte.isEmpty());

        // Löschen der Baustelle räumt Fotos und Unterschrift weg
        Storage.loeschen(c, h.id);
        assertFalse(RapportDateien.datei(c, r.fotos.get(0)).exists());
        assertFalse(RapportDateien.datei(c, r.unterschrift).exists());
    }

    @Test
    public void pdfUndBildschirme() throws Exception {
        Context c = RuntimeEnvironment.getApplication();
        ZeitActivity.testJetzt = ZeiterfassungTest.um(25, 11, 18, 0);
        Holzliste h = ScreenshotTest.beispiel();
        Rapport r = beispiel(c, h);

        // Seite 1 Bericht, Seite 2 Bemerkung + Unterschrift (bleiben zusammen) + Foto 1, Seite 3 Fotos 2 und 3
        List<Bitmap> seiten = RapportPdf.bilder(c, h, r, 2f);
        File dir = new File("build/screenshots");
        dir.mkdirs();
        for (int i = 0; i < seiten.size(); i++) {
            try (FileOutputStream out = new FileOutputStream(new File(dir, "rapport_seite" + (i + 1) + ".png"))) {
                seiten.get(i).compress(Bitmap.CompressFormat.PNG, 100, out);
            }
        }
        assertEquals(3, seiten.size());
        // langer Rapport ohne Fotos: Text bricht sauber auf Folgeseiten um
        Rapport lang = Rapport.fromJson(r.toJson());
        lang.fotos.clear();
        for (int i = 0; i < 25; i++) {
            Rapport.ZeitZeile z = new Rapport.ZeitZeile();
            z.mitarbeiter = "Mitarbeiter " + (i + 1); z.leistung = "Montage"; z.vonMin = 420; z.bisMin = 960; z.pauseMin = 30;
            lang.zeiten.add(z);
        }
        assertEquals(2, RapportPdf.bilder(c, h, lang, 1f).size());

        assertEquals("Rapport_2026-11-25_EFH_Huber_Dachstuhl.pdf", RapportPdf.dateiname(h, r));
        // Bildschirme
        Intent i = new Intent(c, BaustelleActivity.class);
        i.putExtra(BaustelleActivity.EXTRA_ID, h.id);
        Activity bau = Robolectric.buildActivity(BaustelleActivity.class, i).setup().get();
        ShadowLooper.idleMainLooper();
        ScreenshotTest.png(bau.getWindow().getDecorView(), "rapport_0_baustelle");

        Intent l = new Intent(c, RapportActivity.class);
        l.putExtra(RapportActivity.EXTRA_BAUSTELLE, h.id);
        Activity liste = Robolectric.buildActivity(RapportActivity.class, l).setup().get();
        ShadowLooper.idleMainLooper();
        ScreenshotTest.png(liste.getWindow().getDecorView(), "rapport_1_liste");

        Intent e = new Intent(c, RapportActivity.class);
        e.putExtra(RapportActivity.EXTRA_BAUSTELLE, h.id);
        e.putExtra(RapportActivity.EXTRA_RAPPORT, r.id);
        Activity ed = Robolectric.buildActivity(RapportActivity.class, e).setup().get();
        ShadowLooper.idleMainLooper();
        ScreenshotTest.png(inhalt(ed), "rapport_2_ausfuellen");

        // Unterschrift-Dialog
        java.lang.reflect.Method m = RapportActivity.class.getDeclaredMethod("unterschreiben", boolean.class);
        m.setAccessible(true);
        m.invoke(ed, false);
        ShadowLooper.idleMainLooper();
        Dialog d = ShadowDialog.getLatestDialog();
        assertNotNull(d);
        ScreenshotTest.png(d.getWindow().getDecorView(), "rapport_3_unterschrift");

        try (ByteArrayOutputStream pdf = new ByteArrayOutputStream()) {
            try {
                RapportPdf.pdf(c, h, r, pdf);
            } catch (RuntimeException | UnsatisfiedLinkError fehler) {
                // PdfDocument ist in Robolectric nicht nachgebildet – auf dem Handy erzeugt Android die PDF;
                // geprüft wird hier der gemeinsame Zeichencode über die Bildausgabe oben.
                System.out.println("PDF in Robolectric nicht verfügbar: " + fehler);
                return;
            }
            try (FileOutputStream out = new FileOutputStream(new File(dir, "rapport.pdf"))) {
                pdf.writeTo(out);
            }
        }
    }

    @Test
    public void dokuUndAbnahme() throws Exception {
        Context c = RuntimeEnvironment.getApplication();
        ZeitActivity.testJetzt = ZeiterfassungTest.um(25, 11, 18, 0);
        Holzliste h = ScreenshotTest.beispiel();
        beispiel(c, h); // legt Zeiten, Briefkopf, Logo und einen Rapport an
        long tag = ZeiterfassungTest.um(25, 11, 18, 0);

        // Dokumentation: Anwesende kommen aus den gestempelten Zeiten, keine Stundentabelle
        Rapport d = Rapport.neu(Rapport.DOKU, tag, h, Zeiten.alle(c), "Vorlage nur für Rapporte", tag);
        assertEquals("Bautagebuch", d.titel);
        assertEquals("Max Muster", d.anwesend);
        assertTrue(d.zeiten.isEmpty());
        assertEquals("", d.bemerkung);
        d.anwesend = "Max Muster, Erika Beispiel";
        d.wetter = "🌧 Regen";
        d.temperatur = "4";
        d.eintrag = "Dachlattung Nordseite fertig, Konterlattung Südseite begonnen.";
        d.behinderungen = "Gerüst an der Giebelseite fehlt noch.";
        d.fotos.add(foto(c, "foto_doku1.jpg", Color.parseColor("#6D7F94")));
        h.rapporte.add(d);

        // Abnahme mit Mängeln: zwei Unterschriften, Mängel werden zu dringenden Aufgaben
        Rapport a = Rapport.neu(Rapport.ABNAHME, tag, h, Zeiten.alle(c), "", tag);
        assertEquals("Abnahmeprotokoll", a.titel);
        assertEquals(Zeiten.tagesStart(tag), a.leistungBis);
        a.abnahmeArt = Rapport.TEILABNAHME;
        a.ergebnis = Rapport.MIT_MAENGELN;
        a.leistungVon = ZeiterfassungTest.um(10, 11, 0, 0);
        a.frist = ZeiterfassungTest.um(9, 12, 0, 0);
        Rapport.Mangel m1 = new Rapport.Mangel(); m1.text = "Anschluss Dampfbremse an der Traufe nicht verklebt";
        Rapport.Mangel m2 = new Rapport.Mangel(); m2.text = "Zwei Sparren mit Hobelfehlern im Sichtbereich";
        Rapport.Mangel m3 = new Rapport.Mangel(); m3.text = "Bohrlöcher in der Fußpfette offen"; m3.erledigt = true;
        a.maengel.add(m1); a.maengel.add(m2); a.maengel.add(m3);
        a.unterschrift = RapportDateien.unterschriftSpeichern(c, unterschrift());
        a.unterzeichner = "Familie Huber";
        a.unterschriebenAm = ZeiterfassungTest.um(25, 11, 17, 5);
        a.unterschrift2 = RapportDateien.unterschriftSpeichern(c, unterschrift());
        a.unterzeichner2 = "Max Muster";
        a.unterschrieben2Am = ZeiterfassungTest.um(25, 11, 17, 6);
        h.rapporte.add(a);
        assertTrue(a.mitMaengeln());
        int vorher = h.aufgaben.size();
        assertEquals(2, a.maengelAlsAufgaben(h));        // der beseitigte Mangel nicht
        assertEquals(0, a.maengelAlsAufgaben(h));        // kein zweites Mal
        Aufgabe neu = h.aufgaben.get(vorher);
        assertEquals(Werkzeug.ROT, neu.prio);
        assertEquals("Mängel aus Abnahme 25.11.2026", neu.abschnitt);
        assertEquals("Frist bis 09.12.2026", neu.info);
        Storage.speichern(c, h);

        // Speichern und laden
        Holzliste g = Storage.laden(c, h.id);
        assertEquals(3, g.rapporte.size());
        Rapport a2 = g.rapporte.get(2);
        assertTrue(a2.istAbnahme());
        assertEquals(3, a2.maengel.size());
        assertTrue(a2.maengel.get(2).erledigt);
        assertTrue(a2.unterschrieben2());
        assertEquals(Rapport.TEILABNAHME, a2.abnahmeArt);
        assertEquals("Max Muster, Erika Beispiel", g.rapporte.get(1).anwesende());

        // Beispiel-Datei für den Test der Web-App (docs/test): genau das Format, das die Handy-App ins Team lädt
        File web = new File("build/web-beispiel");
        web.mkdirs();
        Werkzeug w1 = new Werkzeug(); w1.name = "Kettensäge"; w1.prio = Werkzeug.ROT; w1.eingepackt = true;
        Werkzeug w2 = new Werkzeug(); w2.name = "Abbundsäge"; w2.prio = Werkzeug.GELB; w2.anzahl = 2;
        g.werkzeug.add(w1); g.werkzeug.add(w2);
        try (FileOutputStream out = new FileOutputStream(new File(web, "baustelle.json"))) {
            out.write(g.toJson().toString(1).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        for (String name : TeamSync.dateien(g)) {
            java.nio.file.Files.copy(RapportDateien.datei(c, name).toPath(), new File(web, name).toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }

        // PDFs als Bilder
        File dir = new File("build/screenshots");
        dir.mkdirs();
        List<Bitmap> sd = RapportPdf.bilder(c, h, d, 2f);
        List<Bitmap> sa = RapportPdf.bilder(c, h, a, 2f);
        assertEquals(1, sd.size()); // Bautagebuch ohne Unterschrift, Foto passt auf Seite 1
        assertEquals(1, sa.size());
        for (int i = 0; i < sd.size(); i++) speichernPng(sd.get(i), "doku_seite" + (i + 1));
        for (int i = 0; i < sa.size(); i++) speichernPng(sa.get(i), "abnahme_seite" + (i + 1));
        assertEquals("Abnahme_2026-11-25_EFH_Huber_Dachstuhl.pdf", RapportPdf.dateiname(h, a));
        assertEquals("Bautagebuch_2026-11-25_EFH_Huber_Dachstuhl.pdf", RapportPdf.dateiname(h, d));

        // Bildschirme
        Intent ib = new Intent(c, BaustelleActivity.class);
        ib.putExtra(BaustelleActivity.EXTRA_ID, h.id);
        Activity bau = Robolectric.buildActivity(BaustelleActivity.class, ib).setup().get();
        ShadowLooper.idleMainLooper();
        ScreenshotTest.png(inhalt(bau), "rapport_0_baustelle");
        for (Rapport x : new Rapport[]{d, a}) {
            Intent e = new Intent(c, RapportActivity.class);
            e.putExtra(RapportActivity.EXTRA_BAUSTELLE, h.id);
            e.putExtra(RapportActivity.EXTRA_RAPPORT, x.id);
            Activity ed = Robolectric.buildActivity(RapportActivity.class, e).setup().get();
            ShadowLooper.idleMainLooper();
            ScreenshotTest.png(inhalt(ed), x.istDoku() ? "doku_ausfuellen" : "abnahme_ausfuellen");
        }
        Intent l = new Intent(c, RapportActivity.class);
        l.putExtra(RapportActivity.EXTRA_BAUSTELLE, h.id);
        l.putExtra(RapportActivity.EXTRA_ART, Rapport.ABNAHME);
        Activity liste = Robolectric.buildActivity(RapportActivity.class, l).setup().get();
        ShadowLooper.idleMainLooper();
        ScreenshotTest.png(liste.getWindow().getDecorView(), "abnahme_liste");
    }

    private static void speichernPng(Bitmap b, String name) throws Exception {
        try (FileOutputStream out = new FileOutputStream(new File("build/screenshots", name + ".png"))) {
            b.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
    }

    /** Ganzer Inhalt der ScrollView (auch der Teil unterhalb des Bildschirms). */
    private static View inhalt(Activity a) {
        View v = a.getWindow().getDecorView();
        ScrollView sv = finde(v);
        if (sv == null) return v;
        View kind = sv.getChildAt(0);
        int w = kind.getWidth();
        kind.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        kind.layout(0, 0, w, kind.getMeasuredHeight());
        kind.setBackgroundColor(Ui.HINTERGRUND);
        return kind;
    }

    private static ScrollView finde(View v) {
        if (v instanceof ScrollView) return (ScrollView) v;
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) {
            ScrollView s = finde(((ViewGroup) v).getChildAt(i));
            if (s != null) return s;
        }
        return null;
    }

    // ---------------------------------------------------------------- Testbilder

    private static Bitmap logo() {
        Bitmap b = Bitmap.createBitmap(600, 220, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.parseColor("#8D5A2B"));
        c.drawRoundRect(10, 30, 170, 190, 30, 30, p);
        p.setColor(Color.WHITE);
        p.setTextSize(110);
        c.drawText("Z", 55, 150, p);
        p.setColor(Color.parseColor("#8D5A2B"));
        p.setTextSize(52);
        p.setFakeBoldText(true);
        c.drawText("ZIMMEREI", 200, 100, p);
        p.setFakeBoldText(false);
        p.setTextSize(40);
        c.drawText("BEISPIEL GmbH", 200, 160, p);
        return b;
    }

    private static String foto(Context c, String name, int farbe) throws Exception {
        Bitmap b = Bitmap.createBitmap(1600, 1200, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        cv.drawColor(farbe);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.argb(90, 255, 255, 255));
        for (int i = 0; i < 12; i++) cv.drawRect(i * 140, 0, i * 140 + 40, 1200, p); // „Sparren“
        p.setColor(Color.WHITE);
        p.setTextSize(90);
        cv.drawText("Testfoto " + name.replaceAll("\\D", ""), 80, 160, p);
        try (FileOutputStream out = new FileOutputStream(RapportDateien.datei(c, name))) {
            b.compress(Bitmap.CompressFormat.JPEG, 80, out);
        }
        return name;
    }

    private static Bitmap unterschrift() {
        Bitmap b = Bitmap.createBitmap(700, 220, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.parseColor("#0D2A6B"));
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(7);
        p.setStrokeCap(Paint.Cap.ROUND);
        Path s = new Path();
        s.moveTo(20, 150);
        s.cubicTo(60, 20, 110, 20, 120, 120);
        s.cubicTo(130, 200, 180, 60, 220, 110);
        s.cubicTo(260, 160, 300, 40, 340, 120);
        s.cubicTo(380, 190, 430, 70, 480, 110);
        s.cubicTo(530, 150, 600, 90, 680, 100);
        c.drawPath(s, p);
        return b;
    }
}
