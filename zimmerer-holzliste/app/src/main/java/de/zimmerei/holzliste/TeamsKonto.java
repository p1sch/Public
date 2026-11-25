package de.zimmerei.holzliste;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Base64;

import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;

/**
 * Anmeldung bei Microsoft (Entra ID) mit dem Code-Verfahren + PKCE über den Browser – das übliche Verfahren für
 * Handy-Apps, ohne Passwort in der App und ohne Geheimnis. Die App-Registrierung (Client-ID) legt der Admin einmal an,
 * siehe README „Microsoft Teams einrichten“. Die Tokens liegen nur in den privaten Einstellungen der App.
 */
public final class TeamsKonto {

    private TeamsKonto() {}

    /** Muss in der App-Registrierung unter „Mobil- und Desktopanwendungen“ als Umleitungs-URI eingetragen sein. */
    public static final String REDIRECT = "de.zimmerei.holzliste://auth";
    public static final String SCOPES = "offline_access User.Read Team.ReadBasic.All Channel.ReadBasic.All "
            + "ChannelMessage.Send Files.ReadWrite.All";

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("teams", Context.MODE_PRIVATE);
    }

    // ================================================================ Einstellungen

    public static String clientId(Context c) { return prefs(c).getString("clientId", "").trim(); }

    /** Verzeichnis (Mandant): Mandanten-ID oder „organizations“ (jedes Firmenkonto). */
    public static String mandant(Context c) {
        String m = prefs(c).getString("mandant", "").trim();
        return m.isEmpty() ? "organizations" : m;
    }

    public static void einrichten(Context c, String clientId, String mandant) {
        prefs(c).edit().putString("clientId", clientId.trim()).putString("mandant", mandant.trim()).apply();
    }

    public static boolean eingerichtet(Context c) { return !clientId(c).isEmpty(); }

    public static boolean angemeldet(Context c) { return !prefs(c).getString("refresh", "").isEmpty(); }

    public static String name(Context c) { return prefs(c).getString("name", ""); }

    public static void abmelden(Context c) {
        prefs(c).edit().remove("refresh").remove("access").remove("ablauf").remove("name").apply();
    }

    // ================================================================ Anmelden

    /** Adresse der Microsoft-Anmeldeseite (im Browser öffnen). Merkt sich PKCE-Prüfwert und state. */
    public static Uri anmeldeAdresse(Context c) {
        SecureRandom r = new SecureRandom();
        byte[] v = new byte[48];
        r.nextBytes(v);
        String verifier = b64(v);
        byte[] st = new byte[16];
        r.nextBytes(st);
        String state = b64(st);
        prefs(c).edit().putString("verifier", verifier).putString("state", state).apply();
        return Uri.parse("https://login.microsoftonline.com/" + mandant(c) + "/oauth2/v2.0/authorize").buildUpon()
                .appendQueryParameter("client_id", clientId(c))
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("redirect_uri", REDIRECT)
                .appendQueryParameter("response_mode", "query")
                .appendQueryParameter("scope", SCOPES)
                .appendQueryParameter("state", state)
                .appendQueryParameter("code_challenge", challenge(verifier))
                .appendQueryParameter("code_challenge_method", "S256")
                .appendQueryParameter("prompt", "select_account")
                .build();
    }

    /** Rückkehr aus dem Browser: Code gegen Tokens tauschen (Hintergrund-Thread). */
    public static void rueckkehr(Context c, Uri daten, Graph.Transport t) throws IOException {
        String fehler = daten.getQueryParameter("error");
        if (fehler != null) {
            String text = daten.getQueryParameter("error_description");
            throw new IOException(text == null ? fehler : text.split("\r|\n")[0]);
        }
        String state = daten.getQueryParameter("state");
        if (state == null || !state.equals(prefs(c).getString("state", ""))) throw new IOException("Anmeldung abgelaufen – bitte noch einmal");
        String code = daten.getQueryParameter("code");
        if (code == null) throw new IOException("Keine Anmeldung erhalten");
        Map<String, String> f = new HashMap<>();
        f.put("grant_type", "authorization_code");
        f.put("code", code);
        f.put("code_verifier", prefs(c).getString("verifier", ""));
        tokensHolen(c, t, f);
        prefs(c).edit().remove("verifier").remove("state").apply();
        try {
            String n = new Graph(t, () -> token(c, t)).meinName();
            prefs(c).edit().putString("name", n).apply();
        } catch (IOException ignored) {
            // Name ist nur Anzeige
        }
    }

    /** Gültiges Zugriffstoken; erneuert es mit dem Refresh-Token, wenn es bald abläuft (Hintergrund-Thread). */
    public static synchronized String token(Context c, Graph.Transport t) throws IOException {
        String access = prefs(c).getString("access", "");
        long ablauf = prefs(c).getLong("ablauf", 0);
        if (!access.isEmpty() && System.currentTimeMillis() < ablauf - 120_000) return access;
        String refresh = prefs(c).getString("refresh", "");
        if (refresh.isEmpty()) throw new Graph.Fehler(401, "Nicht bei Microsoft angemeldet");
        Map<String, String> f = new HashMap<>();
        f.put("grant_type", "refresh_token");
        f.put("refresh_token", refresh);
        tokensHolen(c, t, f);
        return prefs(c).getString("access", "");
    }

    public static Graph graph(Context c) {
        Graph.Transport t = Graph.standard;
        return new Graph(t, () -> token(c, t));
    }

    private static void tokensHolen(Context c, Graph.Transport t, Map<String, String> felder) throws IOException {
        felder.put("client_id", clientId(c));
        felder.put("redirect_uri", REDIRECT);
        felder.put("scope", SCOPES);
        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, String> e : felder.entrySet()) {
            if (body.length() > 0) body.append('&');
            body.append(URLEncoder.encode(e.getKey(), "UTF-8")).append('=').append(URLEncoder.encode(e.getValue(), "UTF-8"));
        }
        Map<String, String> h = new HashMap<>();
        h.put("Content-Type", "application/x-www-form-urlencoded");
        Graph.Antwort a = t.senden("POST", "https://login.microsoftonline.com/" + mandant(c) + "/oauth2/v2.0/token", h,
                body.toString().getBytes(StandardCharsets.UTF_8));
        JSONObject o = a.json();
        if (a.code >= 300 || o.optString("access_token").isEmpty()) {
            String d = o.optString("error_description", o.optString("error", "Fehler " + a.code));
            if ("invalid_grant".equals(o.optString("error"))) abmelden(c); // Refresh-Token abgelaufen/widerrufen
            throw new Graph.Fehler(a.code >= 300 ? a.code : 400, "Anmeldung: " + d.split("\r|\n")[0]);
        }
        SharedPreferences.Editor e = prefs(c).edit()
                .putString("access", o.optString("access_token"))
                .putLong("ablauf", System.currentTimeMillis() + o.optLong("expires_in", 3600) * 1000L);
        if (!o.optString("refresh_token").isEmpty()) e.putString("refresh", o.optString("refresh_token"));
        e.apply();
    }

    // ================================================================ PKCE

    static String challenge(String verifier) {
        try {
            return b64(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String b64(byte[] b) {
        return Base64.encodeToString(b, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
    }
}
