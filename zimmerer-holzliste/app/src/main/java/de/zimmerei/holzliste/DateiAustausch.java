package de.zimmerei.holzliste;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Baustelle als Datei austauschen – ohne Microsoft Teams: Handy → PC (Web-App „Baustellen auf diesem PC“) und zurück.
 * Die Datei ist genau das JSON, das die App auch speichert (Endung .materialliste.json).
 * Fotos und Unterschriften bleiben auf dem Handy; die Berichte selbst (Texte, Zeiten, Material, Mängel) reisen mit.
 */
public final class DateiAustausch {

    private DateiAustausch() {}

    public static final int REQ_LADEN = 7301;

    /** Dateiname wie „EFH Huber - Dachstuhl.materialliste.json“: nur einfache Zeichen (wie die Web-App). */
    static String dateiname(Holzliste h) {
        String n = h.titel().replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("Ä", "Ae").replace("Ö", "Oe")
                .replace("Ü", "Ue").replace("ß", "ss").replaceAll("[–—]", "-").replaceAll("[^A-Za-z0-9 _.\\-]+", "_").trim();
        return (n.isEmpty() ? "Baustelle" : n) + ".materialliste.json";
    }

    static byte[] exportieren(Holzliste h) {
        try {
            JSONObject o = h.toJson();
            o.remove("team"); // die Team-Verknüpfung gilt nur auf diesem Handy
            return o.toString(1).getBytes(StandardCharsets.UTF_8);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }

    /** Liest eine Baustellen-Datei. Wirft IOException mit verständlicher Meldung, wenn es keine ist. */
    static Holzliste lesen(byte[] daten) throws IOException {
        JSONObject o;
        try {
            o = new JSONObject(new String(daten, StandardCharsets.UTF_8));
        } catch (JSONException e) {
            throw new IOException("Das ist keine Baustellen-Datei");
        }
        if (!o.has("positionen") && !o.has("projekt")) throw new IOException("Das ist keine Baustellen-Datei");
        Holzliste h = Holzliste.fromJson(o);
        h.teamId = h.teamName = h.teamKanal = h.teamDrive = h.teamOrdner = "";
        if (h.id == null || h.id.isEmpty()) h.id = UUID.randomUUID().toString();
        return h;
    }

    // ================================================================ Senden

    /** Teilen-Menü (WhatsApp, E-Mail …) mit der Baustellen-Datei. */
    public static void senden(Activity act, Holzliste h) {
        try {
            File dir = new File(act.getCacheDir(), "export");
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, dateiname(h));
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(exportieren(h));
            }
            Uri uri = ExportProvider.uriFor(f.getName());
            Intent s = new Intent(Intent.ACTION_SEND);
            s.setType("application/json");
            s.putExtra(Intent.EXTRA_STREAM, uri);
            s.putExtra(Intent.EXTRA_SUBJECT, "Baustelle " + h.titel());
            s.putExtra(Intent.EXTRA_TEXT, "Baustelle „" + h.titel() + "“ – am PC in der Materialliste-Web-App öffnen "
                    + "(„📂 Datei vom Handy öffnen“), am Handy über „📥 Baustelle aus Datei laden“.");
            s.setClipData(ClipData.newRawUri("", uri));
            s.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            act.startActivity(Intent.createChooser(s, "Baustelle senden an …"));
        } catch (Exception e) {
            Toast.makeText(act, "Datei konnte nicht erstellt werden: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // ================================================================ Laden

    public static void dateiWaehlen(Activity act) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "text/plain", "application/octet-stream"});
        try {
            act.startActivityForResult(i, REQ_LADEN);
        } catch (Exception e) {
            Toast.makeText(act, "Keine Dateiauswahl gefunden", Toast.LENGTH_LONG).show();
        }
    }

    /** Ergebnis der Dateiauswahl. fertig bekommt die gespeicherte Baustelle. */
    public static boolean onActivityResult(Activity act, int req, int res, Intent data, java.util.function.Consumer<Holzliste> fertig) {
        if (req != REQ_LADEN) return false;
        if (res != Activity.RESULT_OK || data == null || data.getData() == null) return true;
        Holzliste h;
        try {
            h = lesen(lesenBytes(act, data.getData()));
        } catch (IOException e) {
            Toast.makeText(act, e.getMessage(), Toast.LENGTH_LONG).show();
            return true;
        }
        Holzliste alt = Storage.laden(act, h.id);
        if (alt == null) {
            speichern(act, h, fertig);
            return true;
        }
        new AlertDialog.Builder(act).setTitle("„" + alt.titel() + "“ ist schon auf dem Handy")
                .setMessage("Durch den Stand aus der Datei ersetzen? Deine Änderungen auf dem Handy seit dem Senden gehen dann verloren."
                        + (TeamSync.verknuepft(alt) ? "\n\nDie Verknüpfung mit dem Team bleibt; der neue Stand geht ins Team." : ""))
                .setPositiveButton("Ersetzen", (d, w) -> {
                    h.teamId = alt.teamId; h.teamName = alt.teamName; h.teamKanal = alt.teamKanal;
                    h.teamDrive = alt.teamDrive; h.teamOrdner = alt.teamOrdner;
                    speichern(act, h, fertig);
                })
                .setNeutralButton("Als Kopie", (d, w) -> { h.id = UUID.randomUUID().toString(); h.projekt = h.projekt + " (aus Datei)"; speichern(act, h, fertig); })
                .setNegativeButton("Abbrechen", null).show();
        return true;
    }

    private static void speichern(Context c, Holzliste h, java.util.function.Consumer<Holzliste> fertig) {
        Storage.speichern(c, h);
        Toast.makeText(c, "✓ „" + h.titel() + "“ geladen", Toast.LENGTH_SHORT).show();
        fertig.accept(h);
    }

    private static byte[] lesenBytes(Context c, Uri uri) throws IOException {
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("Datei kann nicht gelesen werden");
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
                if (bos.size() > 20_000_000) throw new IOException("Datei ist zu groß für eine Baustelle");
            }
            return bos.toByteArray();
        }
    }
}
