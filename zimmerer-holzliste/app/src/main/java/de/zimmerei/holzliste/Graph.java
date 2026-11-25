package de.zimmerei.holzliste;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Kleiner Client für Microsoft Graph (Teams, Kanäle, Dateien, Beiträge) – ohne fremde Bibliotheken.
 * Alle Aufrufe blockieren: nur im Hintergrund-Thread verwenden. Der {@link Transport} ist austauschbar (Tests).
 */
public class Graph {

    public static final String BASIS = "https://graph.microsoft.com/v1.0";
    /** Bis zu dieser Größe ein einfacher Upload, darüber eine Upload-Sitzung in Stücken. */
    static final int KLEIN = 4 * 1024 * 1024;
    static final int STUECK = 5 * 320 * 1024; // Vielfaches von 320 KiB (Vorgabe von Microsoft)

    /** HTTP-Schicht. In der App {@link HttpTransport}, in Tests ein nachgebautes Microsoft 365. */
    public interface Transport {
        Antwort senden(String methode, String url, Map<String, String> header, byte[] body) throws IOException;
    }

    /** Liefert ein gültiges Zugriffstoken (erneuert es bei Bedarf). */
    public interface Token {
        String token() throws IOException;
    }

    public static final class Antwort {
        public final int code;
        public final byte[] body;
        public final Map<String, String> header;

        public Antwort(int code, byte[] body, Map<String, String> header) {
            this.code = code;
            this.body = body == null ? new byte[0] : body;
            this.header = header == null ? new HashMap<>() : header;
        }

        public String text() { return new String(body, StandardCharsets.UTF_8); }

        public JSONObject json() throws IOException {
            try {
                return body.length == 0 ? new JSONObject() : new JSONObject(text());
            } catch (JSONException e) {
                throw new IOException("Unerwartete Antwort von Microsoft", e);
            }
        }
    }

    /** Fehler mit HTTP-Code (401 = Anmeldung abgelaufen, 404 = nicht gefunden, 412 = inzwischen geändert …). */
    public static class Fehler extends IOException {
        public final int code;

        public Fehler(int code, String text) {
            super(text);
            this.code = code;
        }
    }

    public static Transport standard = new HttpTransport();

    private final Transport t;
    private final Token token;

    public Graph(Transport t, Token token) {
        this.t = t;
        this.token = token;
    }

    // ================================================================ Teams und Kanäle

    public static final class Team {
        public String id, name, beschreibung;
    }

    /** Alle Teams, in denen der angemeldete Benutzer Mitglied ist (alphabetisch). */
    public List<Team> meineTeams() throws IOException {
        List<Team> out = new ArrayList<>();
        String url = BASIS + "/me/joinedTeams?$select=id,displayName,description";
        while (url != null) {
            JSONObject o = anfrage("GET", url, null, null, null).json();
            JSONArray a = o.optJSONArray("value");
            if (a != null) for (int i = 0; i < a.length(); i++) {
                JSONObject x = a.optJSONObject(i);
                if (x == null) continue;
                Team tm = new Team();
                tm.id = x.optString("id");
                tm.name = x.optString("displayName");
                tm.beschreibung = x.optString("description", "");
                if (tm.beschreibung.equals("null")) tm.beschreibung = "";
                out.add(tm);
            }
            url = o.optString("@odata.nextLink", null);
        }
        out.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        return out;
    }

    public String meinName() throws IOException {
        return anfrage("GET", BASIS + "/me?$select=displayName", null, null, null).json().optString("displayName", "");
    }

    /** Standardkanal („Allgemein“) des Teams: {id, displayName}. */
    public JSONObject standardKanal(String teamId) throws IOException {
        return anfrage("GET", BASIS + "/teams/" + teamId + "/primaryChannel?$select=id,displayName", null, null, null).json();
    }

    /** Ordner „Dateien“ des Kanals: liefert {driveId, folderId}. */
    public String[] dateiOrdner(String teamId, String kanalId) throws IOException {
        JSONObject o = anfrage("GET", BASIS + "/teams/" + teamId + "/channels/" + kanalId + "/filesFolder", null, null, null).json();
        JSONObject p = o.optJSONObject("parentReference");
        String drive = p == null ? "" : p.optString("driveId", "");
        if (drive.isEmpty() || o.optString("id", "").isEmpty()) throw new IOException("Dateiordner des Teams nicht gefunden");
        return new String[]{drive, o.optString("id")};
    }

    /** Beitrag im Kanal (HTML). */
    public void beitrag(String teamId, String kanalId, String html) throws IOException {
        try {
            JSONObject b = new JSONObject();
            JSONObject body = new JSONObject();
            body.put("contentType", "html");
            body.put("content", html);
            b.put("body", body);
            anfrage("POST", BASIS + "/teams/" + teamId + "/channels/" + kanalId + "/messages",
                    b.toString().getBytes(StandardCharsets.UTF_8), "application/json", null);
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    // ================================================================ Dateien

    public static final class Datei {
        public String id, eTag, webUrl, name;
        public long groesse;

        static Datei von(JSONObject o) {
            Datei d = new Datei();
            d.id = o.optString("id");
            d.eTag = o.optString("eTag");
            d.webUrl = o.optString("webUrl");
            d.name = o.optString("name");
            d.groesse = o.optLong("size", 0);
            return d;
        }
    }

    /** Datei unter einem Pfad im Ordner, null wenn es sie nicht gibt. */
    public Datei datei(String drive, String ordner, String pfad) throws IOException {
        try {
            return Datei.von(anfrage("GET", item(drive, ordner, pfad) + "?$select=id,eTag,webUrl,name,size", null, null, null).json());
        } catch (Fehler f) {
            if (f.code == 404) return null;
            throw f;
        }
    }

    public byte[] laden(String drive, String itemId) throws IOException {
        return anfrage("GET", BASIS + "/drives/" + drive + "/items/" + itemId + "/content", null, null, null).body;
    }

    /**
     * Lädt hoch (legt fehlende Ordner an). wennETag: nur überschreiben, wenn die Datei noch diesen Stand hat
     * (sonst {@link Fehler} 412); "" = nur anlegen, wenn es sie noch nicht gibt; null = immer überschreiben.
     */
    public Datei hochladen(String drive, String ordner, String pfad, byte[] daten, String typ, String wennETag) throws IOException {
        if (daten.length <= KLEIN) {
            String url = item(drive, ordner, pfad) + ":/content" + ("".equals(wennETag) ? "?@microsoft.graph.conflictBehavior=fail" : "");
            return Datei.von(anfrage("PUT", url, daten, typ, wennETag == null || wennETag.isEmpty() ? null : wennETag).json());
        }
        // große Datei (PDF mit vielen Fotos): Upload-Sitzung
        try {
            JSONObject b = new JSONObject();
            JSONObject it = new JSONObject();
            it.put("@microsoft.graph.conflictBehavior", "".equals(wennETag) ? "fail" : "replace");
            b.put("item", it);
            JSONObject s = anfrage("POST", item(drive, ordner, pfad) + ":/createUploadSession",
                    b.toString().getBytes(StandardCharsets.UTF_8), "application/json", wennETag == null || wennETag.isEmpty() ? null : wennETag).json();
            String upload = s.optString("uploadUrl");
            Antwort letzte = null;
            for (int von = 0; von < daten.length; von += STUECK) {
                int bis = Math.min(daten.length, von + STUECK);
                byte[] teil = java.util.Arrays.copyOfRange(daten, von, bis);
                Map<String, String> h = new HashMap<>();
                h.put("Content-Range", "bytes " + von + "-" + (bis - 1) + "/" + daten.length);
                letzte = t.senden("PUT", upload, h, teil); // Upload-URL ist vorab berechtigt: ohne Token
                if (letzte.code >= 300) throw new Fehler(letzte.code, fehlertext(letzte));
            }
            return Datei.von(letzte.json());
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    /** URL eines Elements über seinen Pfad relativ zum Ordner, z. B. „Materialliste/baustelle.json“. */
    static String item(String drive, String ordner, String pfad) {
        StringBuilder p = new StringBuilder();
        for (String teil : pfad.split("/")) {
            if (teil.isEmpty()) continue;
            if (p.length() > 0) p.append('/');
            try {
                p.append(URLEncoder.encode(teil, "UTF-8").replace("+", "%20"));
            } catch (java.io.UnsupportedEncodingException e) {
                throw new RuntimeException(e);
            }
        }
        return BASIS + "/drives/" + drive + "/items/" + ordner + ":/" + p;
    }

    // ================================================================ HTTP

    private Antwort anfrage(String methode, String url, byte[] body, String typ, String ifMatch) throws IOException {
        Map<String, String> h = new HashMap<>();
        h.put("Authorization", "Bearer " + token.token());
        h.put("Accept", "application/json");
        if (typ != null) h.put("Content-Type", typ);
        if (ifMatch != null) h.put("If-Match", ifMatch);
        Antwort a = t.senden(methode, url, h, body);
        if (a.code == 429 || a.code == 503) {
            // Microsoft bremst: kurz warten (Retry-After), dann einmal wiederholen
            long s = 2;
            try { s = Math.min(30, Long.parseLong(a.header.getOrDefault("Retry-After", "2"))); } catch (NumberFormatException ignored) {}
            try { Thread.sleep(s * 1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            a = t.senden(methode, url, h, body);
        }
        if (a.code >= 300) throw new Fehler(a.code, fehlertext(a));
        return a;
    }

    static String fehlertext(Antwort a) {
        try {
            JSONObject e = new JSONObject(a.text()).optJSONObject("error");
            if (e != null && !e.optString("message").isEmpty()) return "Microsoft: " + e.optString("message") + " (" + a.code + ")";
        } catch (JSONException ignored) {
            // kein JSON
        }
        switch (a.code) {
            case 401: return "Anmeldung abgelaufen – bitte neu anmelden";
            case 403: return "Keine Berechtigung (fehlt die Freigabe durch den Admin?)";
            case 404: return "Nicht gefunden";
            default: return "Fehler " + a.code + " von Microsoft";
        }
    }

    /** Echte Verbindung über HttpURLConnection. */
    public static final class HttpTransport implements Transport {
        @Override
        public Antwort senden(String methode, String url, Map<String, String> header, byte[] body) throws IOException {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(20000);
            c.setReadTimeout(60000);
            c.setRequestMethod(methode);
            c.setInstanceFollowRedirects(true);
            for (Map.Entry<String, String> e : header.entrySet()) c.setRequestProperty(e.getKey(), e.getValue());
            if (body != null) {
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(body.length);
                try (OutputStream out = c.getOutputStream()) {
                    out.write(body);
                }
            } else if (methode.equals("POST") || methode.equals("PUT")) {
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(0);
                c.getOutputStream().close();
            }
            int code = c.getResponseCode();
            Map<String, String> h = new HashMap<>();
            for (Map.Entry<String, List<String>> e : c.getHeaderFields().entrySet()) {
                if (e.getKey() != null && !e.getValue().isEmpty()) h.put(e.getKey(), e.getValue().get(0));
            }
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            byte[] daten = new byte[0];
            if (in != null) {
                try (InputStream i = in) {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = i.read(buf)) > 0) bos.write(buf, 0, n);
                    daten = bos.toByteArray();
                }
            }
            c.disconnect();
            return new Antwort(code, daten, h);
        }
    }
}
