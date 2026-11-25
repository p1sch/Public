package de.zimmerei.holzliste;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

/**
 * Dateien rund um den Rapport (Fotos, Unterschriften, Firmenlogo) und die Firmendaten für den Briefkopf.
 * Alles liegt privat in der App unter files/rapport.
 */
public final class RapportDateien {

    private RapportDateien() {}

    /** Fotos werden auf diese Kantenlänge verkleinert (reicht für A4 und hält die PDF klein). */
    static final int FOTO_MAX = 1600;

    public static File ordner(Context c) {
        File d = new File(c.getFilesDir(), "rapport");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static File datei(Context c, String name) {
        return new File(ordner(c), name);
    }

    public static File logo(Context c) {
        return datei(c, "logo.png");
    }

    /** Löscht Fotos und Unterschrift eines Rapports. */
    public static void loeschen(Context c, Rapport r) {
        for (String f : r.fotos) datei(c, f).delete();
        if (r.unterschrieben()) datei(c, r.unterschrift).delete();
        if (r.unterschrieben2()) datei(c, r.unterschrift2).delete();
    }

    // ================================================================ Firma (Briefkopf)

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("rapport", Context.MODE_PRIVATE);
    }

    public static String firmaName(Context c) { return prefs(c).getString("firmaName", ""); }

    /** Anschrift, mehrzeilig (Straße, PLZ Ort, Telefon …). */
    public static String firmaAdresse(Context c) { return prefs(c).getString("firmaAdresse", ""); }

    public static void setFirma(Context c, String name, String adresse) {
        prefs(c).edit().putString("firmaName", name).putString("firmaAdresse", adresse).apply();
    }

    /** Hinweistext, der bei neuen Rapporten unter „Bemerkung“ steht. */
    public static String bemerkungVorlage(Context c) { return prefs(c).getString("bemerkung", ""); }

    public static void setBemerkungVorlage(Context c, String text) {
        prefs(c).edit().putString("bemerkung", text).apply();
    }

    /** Vorschlag für den Hinweistext – bitte selbst prüfen (keine Rechtsberatung). */
    public static final String VOB_VORSCHLAG = "Wir weisen Sie darauf hin, dass Sie diesem Rapport innerhalb von 6 Werktagen "
            + "nach Zugang widersprechen können (VOB/B § 15 Abs. 3). Ohne Widerspruch gilt er auch ohne Unterschrift als anerkannt "
            + "und wird mit dem im Angebot ausgewiesenen Stundenlohn abgerechnet. Benötigte und verbaute Materialien können "
            + "nachträglich ergänzt werden.";

    /** Zuletzt verwendete Namen (Mitarbeiter, Unterzeichner) für die Schnellauswahl. */
    public static String[] namen(Context c, String art) {
        String s = prefs(c).getString("namen_" + art, "");
        return s.isEmpty() ? new String[0] : s.split("\n");
    }

    public static void merkeName(Context c, String art, String name) {
        if (name == null || name.trim().isEmpty()) return;
        StringBuilder sb = new StringBuilder(name.trim());
        int n = 1;
        for (String alt : namen(c, art)) {
            if (alt.equalsIgnoreCase(name.trim()) || n >= 12) continue;
            sb.append('\n').append(alt);
            n++;
        }
        prefs(c).edit().putString("namen_" + art, sb.toString()).apply();
    }

    // ================================================================ Bilder

    /** Liest ein Bild (Kamera/Galerie), dreht es richtig, verkleinert es und speichert es als JPEG. Gibt den Dateinamen zurück. */
    public static String fotoSpeichern(Context c, Uri quelle) throws IOException {
        Bitmap b = laden(c, quelle, FOTO_MAX);
        if (b == null) throw new IOException("Bild kann nicht gelesen werden");
        String name = "foto_" + UUID.randomUUID() + ".jpg";
        try (FileOutputStream out = new FileOutputStream(datei(c, name))) {
            b.compress(Bitmap.CompressFormat.JPEG, 82, out);
        }
        b.recycle();
        return name;
    }

    /** Firmenlogo übernehmen (als PNG, damit ein transparenter Hintergrund erhalten bleibt). */
    public static void logoSpeichern(Context c, Uri quelle) throws IOException {
        Bitmap b = laden(c, quelle, 800);
        if (b == null) throw new IOException("Bild kann nicht gelesen werden");
        try (FileOutputStream out = new FileOutputStream(logo(c))) {
            b.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        b.recycle();
    }

    /** Unterschrift als PNG speichern; gibt den Dateinamen zurück. */
    public static String unterschriftSpeichern(Context c, Bitmap b) throws IOException {
        String name = "unterschrift_" + UUID.randomUUID() + ".png";
        try (FileOutputStream out = new FileOutputStream(datei(c, name))) {
            b.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        return name;
    }

    /** Bild aus einer Datei, höchstens max Pixel an der langen Kante (null wenn nicht lesbar). */
    public static Bitmap bild(File f, int max) {
        if (!f.exists()) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        o.inSampleSize = sample(o.outWidth, o.outHeight, max);
        o.inJustDecodeBounds = false;
        return BitmapFactory.decodeFile(f.getPath(), o);
    }

    private static Bitmap laden(Context c, Uri uri, int max) throws IOException {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, o);
        }
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        o.inSampleSize = sample(o.outWidth, o.outHeight, max);
        o.inJustDecodeBounds = false;
        Bitmap b;
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            b = BitmapFactory.decodeStream(in, null, o);
        }
        if (b == null) return null;
        int drehung = 0;
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            if (in != null) {
                switch (new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    case ExifInterface.ORIENTATION_ROTATE_90: drehung = 90; break;
                    case ExifInterface.ORIENTATION_ROTATE_180: drehung = 180; break;
                    case ExifInterface.ORIENTATION_ROTATE_270: drehung = 270; break;
                }
            }
        } catch (Exception ignored) {
            // ohne EXIF bleibt das Bild wie es ist
        }
        float f = Math.min(1f, max / (float) Math.max(b.getWidth(), b.getHeight()));
        if (drehung == 0 && f >= 1f) return b;
        Matrix m = new Matrix();
        m.postScale(f, f);
        m.postRotate(drehung);
        Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
        if (r != b) b.recycle();
        return r;
    }

    private static int sample(int w, int h, int max) {
        int s = 1;
        while (Math.max(w, h) / (s * 2) >= max) s *= 2;
        return s;
    }
}
