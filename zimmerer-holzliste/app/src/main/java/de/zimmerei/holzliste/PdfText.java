package de.zimmerei.holzliste;

import android.content.Context;
import android.net.Uri;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException;
import com.tom_roush.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.io.InputStream;

/** Liest den Text einer PDF-Datei (ohne Internet, mit PdfBox). */
public final class PdfText {

    private PdfText() {}

    /** Höchstens so viele Seiten lesen (Arbeitsanweisungen sind kurz, schützt vor riesigen Plänen). */
    private static final int MAX_SEITEN = 40;

    public static String lesen(Context c, Uri uri) throws IOException {
        PDFBoxResourceLoader.init(c.getApplicationContext());
        try (InputStream roh = c.getContentResolver().openInputStream(uri)) {
            if (roh == null) throw new IOException("Datei kann nicht geöffnet werden");
            java.io.BufferedInputStream in = new java.io.BufferedInputStream(roh);
            pruefen(in);
            try (PDDocument doc = PDDocument.load(in)) {
                doc.setAllSecurityToBeRemoved(true);
                PDFTextStripper s = new PDFTextStripper();
                s.setSortByPosition(true); // Spalten „Pos. Menge Einh. Beschreibung“ bleiben in einer Zeile
                s.setStartPage(1);
                s.setEndPage(Math.min(doc.getNumberOfPages(), MAX_SEITEN));
                return s.getText(doc);
            }
        } catch (InvalidPasswordException e) {
            throw new IOException("Die PDF ist mit einem Passwort geschützt.");
        }
    }

    static final String SCAN = "Das ist ein eingescanntes Bild (z. B. TIFF oder JPG), keine PDF mit Text – auch wenn die Datei auf „.pdf“ endet.\n\n"
            + "Tipp: Die Arbeitsanweisung direkt aus dem Programm als PDF speichern oder drucken („Als PDF drucken“), nicht scannen.";

    /** Erkennt Bilder mit „.pdf“-Endung (Scanner speichern oft TIFF/JPG) und andere Dateien, bevor PdfBox sie liest. */
    static void pruefen(java.io.BufferedInputStream in) throws IOException {
        in.mark(1100);
        byte[] b = new byte[1024];
        int n = 0, r;
        while (n < b.length && (r = in.read(b, n, b.length - n)) > 0) n += r;
        in.reset();
        String kopf = new String(b, 0, n, java.nio.charset.StandardCharsets.ISO_8859_1);
        if (kopf.contains("%PDF")) return;
        boolean tiff = n >= 4 && ((b[0] == 'I' && b[1] == 'I' && b[2] == 42 && b[3] == 0) || (b[0] == 'M' && b[1] == 'M' && b[2] == 0 && b[3] == 42));
        boolean jpg = n >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF;
        boolean png = n >= 4 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
        if (tiff || jpg || png) throw new IOException(SCAN);
        throw new IOException("Das ist keine PDF-Datei.");
    }
}
