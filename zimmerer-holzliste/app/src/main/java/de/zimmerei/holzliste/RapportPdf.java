package de.zimmerei.holzliste;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Zeichnet den Rapport auf A4-Seiten – als PDF ({@link #pdf}) oder als Bilder für die Vorschau in der App
 * ({@link #bilder}). Beide nutzen denselben Zeichencode, die Vorschau sieht also genau aus wie die PDF.
 *
 * Aufbau: Kopf mit Logo und Firma, Auftraggeber, Zeiten, Material, Dokumentation, Bemerkung,
 * Unterschrift, danach die Fotos (je eine halbe Seite). Fußzeile mit Dokumentennummer und Seite x von y.
 */
public final class RapportPdf {

    /** A4 in Punkt (1/72 Zoll). */
    static final int BREITE = 595, HOEHE = 842;
    private static final float RAND = 50, UNTEN = HOEHE - 62;
    private static final Locale DE = Locale.GERMANY;
    private static final int LINIE = Color.parseColor("#C9CED4");
    private static final int GRAU = Color.parseColor("#6B7280");
    private static final int KOPF_GRUND = Color.parseColor("#F3F4F6");

    private RapportPdf() {}

    /** Ziel der Seiten: PDF-Dokument oder Bitmaps. */
    private interface Ziel {
        Canvas neueSeite(int nr);
        void seiteFertig();
    }

    // ================================================================ öffentliche Aufrufe

    /** Schreibt die PDF in out. */
    public static void pdf(Context c, Holzliste b, Rapport r, OutputStream out) throws IOException {
        Daten d = Daten.von(c, b, r);
        int seiten = zaehlen(d);
        PdfDocument doc = new PdfDocument();
        try {
            zeichnen(d, seiten, new Ziel() {
                PdfDocument.Page page;
                @Override public Canvas neueSeite(int nr) {
                    page = doc.startPage(new PdfDocument.PageInfo.Builder(BREITE, HOEHE, nr).create());
                    return page.getCanvas();
                }
                @Override public void seiteFertig() { doc.finishPage(page); }
            });
            doc.writeTo(out);
        } finally {
            doc.close();
            d.freigeben();
        }
    }

    /** Seiten als Bilder (Faktor 2 = ca. 1190 × 1684 Pixel) für die Vorschau. */
    public static List<Bitmap> bilder(Context c, Holzliste b, Rapport r, float faktor) {
        Daten d = Daten.von(c, b, r);
        List<Bitmap> out = new ArrayList<>();
        try {
            int seiten = zaehlen(d);
            zeichnen(d, seiten, new Ziel() {
                @Override public Canvas neueSeite(int nr) {
                    Bitmap bm = Bitmap.createBitmap(Math.round(BREITE * faktor), Math.round(HOEHE * faktor), Bitmap.Config.ARGB_8888);
                    bm.eraseColor(Color.WHITE);
                    out.add(bm);
                    Canvas cv = new Canvas(bm);
                    cv.scale(faktor, faktor);
                    return cv;
                }
                @Override public void seiteFertig() {}
            });
        } finally {
            d.freigeben();
        }
        return out;
    }

    /** Dateiname wie „Rapport_2026-11-25_Seehaus.pdf“. */
    public static String dateiname(Holzliste b, Rapport r) {
        String s = "Rapport_" + new SimpleDateFormat("yyyy-MM-dd", DE).format(new Date(r.tag)) + "_" + b.titel();
        s = s.replaceAll("[^A-Za-z0-9ÄÖÜäöüß_\\-]+", "_").replaceAll("_+", "_");
        if (s.length() > 70) s = s.substring(0, 70);
        return s + ".pdf";
    }

    // ================================================================ Daten, die zum Zeichnen gebraucht werden

    private static final class Daten {
        Holzliste baustelle;
        Rapport rapport;
        String firmaName, firmaAdresse;
        Bitmap logo, unterschrift;
        final List<File> fotos = new ArrayList<>();

        static Daten von(Context c, Holzliste b, Rapport r) {
            Daten d = new Daten();
            d.baustelle = b;
            d.rapport = r;
            d.firmaName = RapportDateien.firmaName(c);
            d.firmaAdresse = RapportDateien.firmaAdresse(c);
            d.logo = RapportDateien.bild(RapportDateien.logo(c), 600);
            if (r.unterschrieben()) d.unterschrift = RapportDateien.bild(RapportDateien.datei(c, r.unterschrift), 1200);
            for (String f : r.fotos) {
                File x = RapportDateien.datei(c, f);
                if (x.exists()) d.fotos.add(x);
            }
            return d;
        }

        void freigeben() {
            if (logo != null) logo.recycle();
            if (unterschrift != null) unterschrift.recycle();
        }
    }

    // ================================================================ Seitenverwaltung

    /** Erster Durchgang nur zum Zählen der Seiten (für „Seite x von y“). */
    private static int zaehlen(Daten d) {
        final int[] n = {0};
        Bitmap klein = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        zeichnen(d, 0, new Ziel() {
            @Override public Canvas neueSeite(int nr) { n[0] = nr; return new Canvas(klein); }
            @Override public void seiteFertig() {}
        });
        klein.recycle();
        return n[0];
    }

    private static final class Seite {
        final Daten d;
        final Ziel ziel;
        final int gesamt;
        Canvas cv;
        int nr;
        float y;

        Seite(Daten d, Ziel ziel, int gesamt) { this.d = d; this.ziel = ziel; this.gesamt = gesamt; }

        void neu() {
            if (cv != null) { fusszeile(); ziel.seiteFertig(); }
            nr++;
            cv = ziel.neueSeite(nr);
            y = RAND;
        }

        /** Sorgt für Platz; sonst neue Seite. */
        void platz(float h) {
            if (y + h > UNTEN) neu();
        }

        void ende() {
            fusszeile();
            ziel.seiteFertig();
        }

        private void fusszeile() {
            TextPaint p = paint(7.5f, false, Color.DKGRAY);
            cv.drawText("Dokumentennummer: " + d.rapport.nummer, RAND, HOEHE - 32, p);
            String s = (d.rapport.titel.isEmpty() ? "Rapport" : d.rapport.titel) + ", Seite " + nr + " von " + Math.max(gesamt, nr);
            p.setTextAlign(Paint.Align.RIGHT);
            cv.drawText(s, BREITE - RAND, HOEHE - 32, p);
        }
    }

    // ================================================================ Zeichnen

    private static void zeichnen(Daten d, int gesamt, Ziel ziel) {
        Rapport r = d.rapport;
        Holzliste b = d.baustelle;
        Seite s = new Seite(d, ziel, gesamt);
        s.neu();
        float innen = BREITE - 2 * RAND;

        // ---- Kopf: links Titel + Datum, rechts Logo + Firma
        float kopfRechts = 0;
        if (d.logo != null) {
            float maxB = 170, maxH = 70;
            float f = Math.min(maxB / d.logo.getWidth(), maxH / d.logo.getHeight());
            float w = d.logo.getWidth() * f, h = d.logo.getHeight() * f;
            s.cv.drawBitmap(d.logo, null, new RectF(BREITE - RAND - w, RAND, BREITE - RAND, RAND + h), bildPaint());
            kopfRechts = h + 10;
        }
        float yr = RAND + kopfRechts;
        StringBuilder firma = new StringBuilder();
        if (!d.firmaName.trim().isEmpty()) firma.append(d.firmaName.trim());
        if (!d.firmaAdresse.trim().isEmpty()) firma.append(firma.length() > 0 ? "\n" : "").append(d.firmaAdresse.trim());
        if (firma.length() > 0) {
            StaticLayout fl = layout(firma.toString(), paint(9, false, Color.BLACK), 220, Layout.Alignment.ALIGN_OPPOSITE);
            zeichne(s.cv, fl, BREITE - RAND - 220, yr);
            yr += fl.getHeight();
        }
        TextPaint titel = paint(20, false, Color.BLACK);
        s.cv.drawText(r.titel.isEmpty() ? "Rapport" : r.titel, RAND, RAND + 40, titel);
        s.cv.drawText(new SimpleDateFormat("EEEE, d. MMMM yyyy", DE).format(new Date(r.tag)), RAND, RAND + 58, paint(10, false, Color.BLACK));
        float yl = RAND + 90;

        // Auftraggeber (links, unter dem Titel)
        s.cv.drawText("Auftraggeber", RAND, yl, paint(13, false, Color.BLACK));
        yl += 6;
        StringBuilder ag = new StringBuilder();
        anhang(ag, b.kunde);
        anhang(ag, b.projekt);
        anhang(ag, b.bauteil);
        if (ag.length() == 0) ag.append("–");
        StaticLayout agl = layout(ag.toString(), paint(10, false, Color.BLACK), innen - 240, Layout.Alignment.ALIGN_NORMAL);
        zeichne(s.cv, agl, RAND, yl);
        yl += agl.getHeight();
        s.y = Math.max(yl, yr) + 22;

        // ---- Zeiten
        if (!r.zeiten.isEmpty()) {
            ueberschrift(s, "Zeiten");
            float[] sp = {150, 155, 45, 45, 45, 55}; // Mitarbeiter, Leistung, Start, Ende, Pause, Gesamt = 495
            String[] kopf = {"Mitarbeiter", "Leistung", "Start", "Ende", "Pause", "Gesamt"};
            boolean[] rechts = {false, false, true, true, true, true};
            tabellenKopf(s, sp, kopf, rechts);
            for (Rapport.ZeitZeile z : r.zeiten) {
                tabellenZeile(s, sp, rechts, new String[]{z.mitarbeiter, z.leistung, uhr(z.vonMin), uhr(z.bisMin),
                        dauer(z.pauseMin), dauer(z.nettoMin())}, kopf);
            }
            s.platz(20);
            TextPaint fett = paint(9.5f, true, Color.BLACK);
            fett.setTextAlign(Paint.Align.RIGHT);
            rahmenLinie(s);
            s.cv.drawText("Gesamt: " + dauer(r.summeMin()) + " h", BREITE - RAND - 4, s.y + 13, fett);
            s.y += 18;
            linie(s.cv, RAND, s.y, BREITE - RAND, s.y);
            s.y += 18;
        }

        // ---- Material
        if (!r.material.isEmpty()) {
            ueberschrift(s, "Material");
            float[] sp = {60, 55, 380};
            String[] kopf = {"Menge", "Einheit", "Bezeichnung"};
            boolean[] rechts = {true, false, false};
            tabellenKopf(s, sp, kopf, rechts);
            for (Rapport.MatZeile m : r.material) {
                String bez = m.bezeichnung + (m.details.trim().isEmpty() ? "" : "\n" + m.details.trim());
                tabellenZeile(s, sp, rechts, new String[]{m.mengeText(), m.einheit, bez}, kopf);
            }
            s.y += 18;
        }

        // ---- Dokumentation
        boolean doku = !r.eintrag.trim().isEmpty() || !r.behinderungen.trim().isEmpty() || !r.wetter.isEmpty()
                || !r.temperatur.trim().isEmpty() || !r.anwesende().isEmpty();
        if (doku) {
            ueberschrift(s, "Dokumentation");
            String wetter = r.wetter.trim();
            if (!r.temperatur.trim().isEmpty()) wetter += (wetter.isEmpty() ? "" : ", ") + r.temperatur.trim() + " °C";
            feld(s, "Wetter", ohneSymbol(wetter));
            feld(s, "Anwesende Mitarbeiter", r.anwesende());
            feld(s, "Ausgeführte Arbeiten", r.eintrag);
            feld(s, "Behinderungen / Erschwernisse", r.behinderungen);
            s.y += 8;
        }

        // ---- Bemerkung + Bestätigung + Unterschrift: bleiben zusammen auf einer Seite
        String bem = r.bemerkung.trim();
        bem += (bem.isEmpty() ? "" : "\n\n") + "Mit meiner Unterschrift bestätige ich die Richtigkeit der vorstehenden Angaben.";
        TextPaint bemPaint = paint(9.5f, false, Color.BLACK);
        float sigH = 55, sigB = 200, sigBlock = 14 + sigH + 30;
        float block = 22 + layout(bem, bemPaint, innen, Layout.Alignment.ALIGN_NORMAL).getHeight() + sigBlock;
        if (s.y + block > UNTEN && block < UNTEN - RAND) s.neu();
        ueberschrift(s, "Bemerkung");
        absatz(s, bem, bemPaint);

        // ---- Unterschrift
        s.platz(sigBlock);
        s.y += 14;
        if (d.unterschrift != null) {
            float f = Math.min(sigB / d.unterschrift.getWidth(), sigH / d.unterschrift.getHeight());
            float w = d.unterschrift.getWidth() * f, h = d.unterschrift.getHeight() * f;
            s.cv.drawBitmap(d.unterschrift, null, new RectF(RAND, s.y + sigH - h, RAND + w, s.y + sigH), bildPaint());
        }
        s.y += sigH + 2;
        Paint l = new Paint(Paint.ANTI_ALIAS_FLAG);
        l.setColor(Color.DKGRAY);
        l.setStrokeWidth(0.7f);
        s.cv.drawLine(RAND, s.y, RAND + 220, s.y, l);
        String unter = "Unterschrift" + (r.unterzeichner.trim().isEmpty() ? "" : " " + r.unterzeichner.trim());
        s.cv.drawText(unter, RAND + 2, s.y + 11, paint(8, false, Color.BLACK));
        if (r.unterschriebenAm != 0) {
            s.cv.drawText("unterschrieben am " + new SimpleDateFormat("dd.MM.yyyy, HH:mm", DE).format(new Date(r.unterschriebenAm)) + " Uhr",
                    RAND + 2, s.y + 22, paint(7.5f, false, GRAU));
        }
        s.y += 30;

        // ---- Fotos: je eine halbe Seite; beginnen direkt unter der Unterschrift, wenn dort Platz ist
        if (!d.fotos.isEmpty()) {
            float platzH = (UNTEN - RAND - 30) / 2f;
            s.y += 10;
            if (s.y + 22 + platzH > UNTEN) s.neu();
            ueberschrift(s, "Fotos");
            int i = 0;
            for (File f : d.fotos) {
                s.platz(platzH);
                Bitmap bm = RapportDateien.bild(f, 1400);
                if (bm != null) {
                    float fk = Math.min(innen / bm.getWidth(), (platzH - 22) / bm.getHeight());
                    float w = bm.getWidth() * fk, h = bm.getHeight() * fk;
                    float x = RAND + (innen - w) / 2;
                    s.cv.drawBitmap(bm, null, new RectF(x, s.y, x + w, s.y + h), bildPaint());
                    bm.recycle();
                    s.cv.drawText("Foto " + (i + 1), x, s.y + h + 12, paint(8, false, GRAU));
                }
                s.y += platzH;
                i++;
            }
        }
        s.ende();
    }

    // ================================================================ Bausteine

    private static void ueberschrift(Seite s, String t) {
        s.platz(60); // Überschrift nie allein am Seitenende
        s.cv.drawText(t, RAND, s.y + 13, paint(13, false, Color.BLACK));
        s.y += 22;
    }

    private static void feld(Seite s, String label, String wert) {
        if (wert == null || wert.trim().isEmpty()) return;
        s.platz(30);
        s.cv.drawText(label, RAND, s.y + 9, paint(8.5f, false, GRAU));
        s.y += 13;
        absatz(s, wert.trim(), paint(9.5f, false, Color.BLACK));
        s.y += 6;
    }

    /** Fließtext; bricht bei Bedarf zeilenweise auf die nächste Seite um. */
    private static void absatz(Seite s, String text, TextPaint p) {
        StaticLayout sl = layout(text, p, BREITE - 2 * RAND, Layout.Alignment.ALIGN_NORMAL);
        int zeile = 0;
        while (zeile < sl.getLineCount()) {
            s.platz(sl.getLineBottom(zeile) - sl.getLineTop(zeile));
            int bis = zeile;
            float top = sl.getLineTop(zeile);
            while (bis < sl.getLineCount() && s.y + (sl.getLineBottom(bis) - top) <= UNTEN) bis++;
            if (bis == zeile) bis = zeile + 1;
            float h = sl.getLineBottom(bis - 1) - top;
            s.cv.save();
            s.cv.translate(RAND, s.y - top);
            s.cv.clipRect(0, top, BREITE - 2 * RAND, top + h);
            sl.draw(s.cv);
            s.cv.restore();
            s.y += h;
            zeile = bis;
        }
    }

    private static void tabellenKopf(Seite s, float[] sp, String[] kopf, boolean[] rechts) {
        s.platz(40);
        Paint g = new Paint();
        g.setColor(KOPF_GRUND);
        s.cv.drawRect(RAND, s.y, BREITE - RAND, s.y + 17, g);
        linie(s.cv, RAND, s.y, BREITE - RAND, s.y);
        float x = RAND;
        for (int i = 0; i < sp.length; i++) {
            TextPaint p = paint(8.5f, false, GRAU);
            if (rechts[i]) { p.setTextAlign(Paint.Align.RIGHT); s.cv.drawText(kopf[i], x + sp[i] - 4, s.y + 12, p); }
            else s.cv.drawText(kopf[i], x + 4, s.y + 12, p);
            x += sp[i];
        }
        s.y += 17;
        linie(s.cv, RAND, s.y, BREITE - RAND, s.y);
    }

    private static void tabellenZeile(Seite s, float[] sp, boolean[] rechts, String[] werte, String[] kopf) {
        TextPaint p = paint(9.5f, false, Color.BLACK);
        StaticLayout[] ls = new StaticLayout[werte.length];
        float h = 0;
        for (int i = 0; i < werte.length; i++) {
            ls[i] = layout(werte[i] == null ? "" : werte[i], p, (int) sp[i] - 8,
                    rechts[i] ? Layout.Alignment.ALIGN_OPPOSITE : Layout.Alignment.ALIGN_NORMAL);
            h = Math.max(h, ls[i].getHeight());
        }
        h += 9;
        if (s.y + h > UNTEN) {
            s.neu();
            tabellenKopf(s, sp, kopf, rechts);
        }
        float x = RAND;
        for (int i = 0; i < werte.length; i++) {
            zeichne(s.cv, ls[i], x + 4, s.y + 4.5f);
            x += sp[i];
        }
        // senkrechte Linien wie in einer Tabelle
        x = RAND;
        for (int i = 0; i <= sp.length; i++) {
            linie(s.cv, x, s.y, x, s.y + h);
            if (i < sp.length) x += sp[i];
        }
        s.y += h;
        linie(s.cv, RAND, s.y, BREITE - RAND, s.y);
    }

    /** Seitenlinien für die Gesamt-Zeile unter der Tabelle. */
    private static void rahmenLinie(Seite s) {
        linie(s.cv, RAND, s.y, RAND, s.y + 18);
        linie(s.cv, BREITE - RAND, s.y, BREITE - RAND, s.y + 18);
    }

    private static void linie(Canvas cv, float x1, float y1, float x2, float y2) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(LINIE);
        p.setStrokeWidth(0.6f);
        cv.drawLine(x1, y1, x2, y2, p);
    }

    private static TextPaint paint(float pt, boolean fett, int farbe) {
        TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        p.setTextSize(pt);
        p.setColor(farbe);
        p.setTypeface(fett ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        return p;
    }

    private static Paint bildPaint() {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setFilterBitmap(true);
        return p;
    }

    static StaticLayout layout(String text, TextPaint p, float breite, Layout.Alignment a) {
        return StaticLayout.Builder.obtain(text, 0, text.length(), p, Math.max(10, (int) breite))
                .setAlignment(a).setLineSpacing(1.5f, 1f).setIncludePad(false).build();
    }

    private static void zeichne(Canvas cv, StaticLayout l, float x, float y) {
        cv.save();
        cv.translate(x, y);
        l.draw(cv);
        cv.restore();
    }

    private static void anhang(StringBuilder sb, String s) {
        if (s == null || s.trim().isEmpty()) return;
        if (sb.length() > 0) sb.append('\n');
        sb.append(s.trim());
    }

    /** Emojis gehören in die App, nicht in die PDF („☀️ sonnig“ → „sonnig“). */
    static String ohneSymbol(String s) {
        return s.replaceAll("[^\\p{L}\\p{N}\\p{P}\\p{Zs}°+−-]", "").trim();
    }

    static String uhr(int min) {
        return String.format(DE, "%02d:%02d", min / 60, min % 60);
    }

    static String dauer(int min) {
        return (min / 60) + ":" + String.format(DE, "%02d", min % 60);
    }
}
