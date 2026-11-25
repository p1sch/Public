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
        java.lang.reflect.Method m = RapportActivity.class.getDeclaredMethod("unterschreiben");
        m.setAccessible(true);
        m.invoke(ed);
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
