package de.zimmerei.holzliste;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.Dialog;
import android.app.TimePickerDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.speech.RecognizerIntent;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Rapport (Tagesbericht) einer Baustelle. Ohne {@link #EXTRA_RAPPORT}: Liste aller Rapporte der Baustelle.
 * Mit: ein Rapport zum Ausfüllen – Stunden kommen aus der Zeiterfassung, Material aus der Materialliste.
 * Danach Vorschau, Unterschrift des Kunden am Handy und PDF senden (WhatsApp, E-Mail …).
 */
public class RapportActivity extends Activity {

    public static final String EXTRA_BAUSTELLE = "baustelle";
    public static final String EXTRA_RAPPORT = "rapport";

    private static final int REQ_KAMERA = 41, REQ_GALERIE = 42, REQ_LOGO = 43, REQ_SPRACHE_EINTRAG = 44, REQ_SPRACHE_BEH = 45;
    private static final Locale DE = Locale.GERMANY;
    private static final String[] WETTER = {"☀️ sonnig", "⛅ bewölkt", "🌧 Regen", "❄️ Schnee", "🥶 Frost", "💨 Wind"};

    private Holzliste baustelle;
    private Rapport rapport;
    private LinearLayout inhalt;
    private TextView unterschriftHinweis;
    private EditText eintragFeld, behFeld;
    private String kameraDatei;
    /** Firmendialog offen: Vorschau des Logos nach der Auswahl auffrischen. */
    private ImageView logoVorschau;

    // ================================================================ Lebenszyklus

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) kameraDatei = state.getString("kamera");
        if (getActionBar() != null) getActionBar().setDisplayHomeAsUpEnabled(true);
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setBackgroundColor(Ui.HINTERGRUND);
        inhalt = new LinearLayout(this);
        inhalt.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 12);
        inhalt.setPadding(p, p, p, Ui.dp(this, 24));
        sv.addView(inhalt);
        setContentView(sv);
        if (!laden()) {
            Toast.makeText(this, "Baustelle nicht gefunden", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        render();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("kamera", kameraDatei);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Liste: nach der Rückkehr aus einem Rapport neu laden
        if (rapport == null && baustelle != null && laden()) render();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (rapport != null) speichern(false);
    }

    private boolean laden() {
        baustelle = Storage.laden(this, getIntent().getStringExtra(EXTRA_BAUSTELLE));
        if (baustelle == null) return false;
        String rid = getIntent().getStringExtra(EXTRA_RAPPORT);
        rapport = null;
        if (rid != null) for (Rapport r : baustelle.rapporte) if (r.id.equals(rid)) rapport = r;
        return rid == null || rapport != null;
    }

    private void speichern(boolean neuZeichnen) {
        try {
            Storage.speichern(this, baustelle);
        } catch (RuntimeException e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
        }
        if (neuZeichnen) render();
    }

    /** Jede Änderung nach dem Unterschreiben macht die Unterschrift ungültig. */
    private void geaendert() {
        if (rapport == null || !rapport.unterschrieben()) return;
        RapportDateien.datei(this, rapport.unterschrift).delete();
        rapport.unterschrift = "";
        rapport.unterschriebenAm = 0;
        if (unterschriftHinweis != null) unterschriftHinweis.setVisibility(View.GONE);
        Toast.makeText(this, "Rapport geändert – die Unterschrift wurde entfernt. Bitte neu unterschreiben lassen.", Toast.LENGTH_LONG).show();
    }

    // ================================================================ Liste der Rapporte

    void render() {
        inhalt.removeAllViews();
        unterschriftHinweis = null;
        if (rapport == null) renderListe();
        else renderRapport();
    }

    private void renderListe() {
        setTitle("📋 Rapporte");
        TextView kopf = Ui.text(this, baustelle.titel(), 20, Ui.DUNKEL, true);
        kopf.setPadding(Ui.dp(this, 4), 0, 0, Ui.dp(this, 10));
        inhalt.addView(kopf);

        if (RapportDateien.firmaName(this).isEmpty()) {
            View f = karte("🏢  Briefkopf einrichten", "Firmenname, Anschrift und Logo für die PDF eintragen", Ui.AKZENT);
            f.setOnClickListener(v -> firmaDialog());
            inhalt.addView(f, zeile(10));
        }

        Button neu = knopf("＋  Rapport für heute", Ui.PRIMAER, Ui.WEISS, false);
        neu.setOnClickListener(v -> neuerRapport(ZeitActivity.jetzt()));
        inhalt.addView(neu, hoch(56, 6));
        Button anderer = knopf("📅  Rapport für anderen Tag …", Ui.WEISS, Ui.PRIMAER, true);
        anderer.setOnClickListener(v -> datumWaehlen(ZeitActivity.jetzt(), this::neuerRapport));
        inhalt.addView(anderer, hoch(48, 16));

        List<Rapport> l = new ArrayList<>(baustelle.rapporte);
        Collections.sort(l, (a, b) -> Long.compare(b.tag, a.tag));
        if (l.isEmpty()) {
            TextView t = Ui.text(this, "Noch kein Rapport.\n\nStunden und Material dieser Baustelle werden automatisch übernommen – "
                    + "du ergänzt nur, was gemacht wurde, und lässt den Kunden am Handy unterschreiben.", 15, Ui.GRAU, false);
            t.setGravity(Gravity.CENTER);
            t.setPadding(Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16), 0);
            inhalt.addView(t);
        }
        for (Rapport r : l) {
            StringBuilder s = new StringBuilder();
            s.append(r.summeMin() == 0 ? "keine Stunden" : RapportPdf.dauer(r.summeMin()) + " h");
            if (!r.material.isEmpty()) s.append("  ·  ").append(r.material.size()).append(" Material");
            if (!r.fotos.isEmpty()) s.append("  ·  ").append(r.fotos.size()).append(r.fotos.size() == 1 ? " Foto" : " Fotos");
            s.append(r.unterschrieben() ? "\n✍ unterschrieben" + (r.unterzeichner.isEmpty() ? "" : " von " + r.unterzeichner)
                    : "\n○ noch nicht unterschrieben");
            View k = karte("📋  " + new SimpleDateFormat("EE, dd.MM.yyyy", DE).format(new Date(r.tag)) + "  ·  " + r.titel,
                    s.toString(), r.unterschrieben() ? Ui.GRUEN : Ui.PRIMAER);
            k.setOnClickListener(v -> oeffnen(r));
            k.setOnLongClickListener(v -> { loeschenFragen(r); return true; });
            inhalt.addView(k, zeile(8));
        }
    }

    private void neuerRapport(long tag) {
        for (Rapport r : baustelle.rapporte) {
            if (Zeiten.tagesStart(r.tag) == Zeiten.tagesStart(tag)) {
                new AlertDialog.Builder(this).setTitle("Für diesen Tag gibt es schon einen Rapport")
                        .setPositiveButton("Öffnen", (d, w) -> oeffnen(r))
                        .setNeutralButton("Neuen anlegen", (d, w) -> anlegen(tag))
                        .setNegativeButton("Abbrechen", null).show();
                return;
            }
        }
        anlegen(tag);
    }

    private void anlegen(long tag) {
        Rapport r = Rapport.neu(tag, baustelle, Zeiten.alle(this), RapportDateien.bemerkungVorlage(this), ZeitActivity.jetzt());
        baustelle.rapporte.add(r);
        speichern(false);
        oeffnen(r);
    }

    private void oeffnen(Rapport r) {
        Intent i = new Intent(this, RapportActivity.class);
        i.putExtra(EXTRA_BAUSTELLE, baustelle.id);
        i.putExtra(EXTRA_RAPPORT, r.id);
        startActivity(i);
    }

    private void loeschenFragen(Rapport r) {
        new AlertDialog.Builder(this).setTitle("Rapport löschen?")
                .setMessage(new SimpleDateFormat("EEEE, dd.MM.yyyy", DE).format(new Date(r.tag)) + " – mit Fotos und Unterschrift.")
                .setPositiveButton("Löschen", (d, w) -> {
                    RapportDateien.loeschen(this, r);
                    baustelle.rapporte.remove(r);
                    speichern(false);
                    if (rapport != null) finish(); else render();
                })
                .setNegativeButton("Abbrechen", null).show();
    }

    // ================================================================ Ein Rapport

    private void renderRapport() {
        setTitle("📋 Rapport");
        Rapport r = rapport;

        // Kopf: Baustelle, Datum, Titel
        LinearLayout kopf = box();
        kopf.addView(Ui.text(this, baustelle.titel(), 20, Ui.DUNKEL, true));
        String kunde = baustelle.kunde == null ? "" : baustelle.kunde.trim();
        if (!kunde.isEmpty()) kopf.addView(Ui.text(this, "Auftraggeber: " + kunde, 14, Ui.GRAU, false));
        Button datum = feldKnopf("📅  " + new SimpleDateFormat("EEEE, d. MMMM yyyy", DE).format(new Date(r.tag)));
        datum.setOnClickListener(v -> datumWaehlen(r.tag, t -> {
            r.tag = Zeiten.tagesStart(t);
            geaendert();
            speichern(true);
        }));
        kopf.addView(datum, zeile(0));
        kopf.addView(beschriftung("Titel"));
        EditText titel = eingabe(r.titel, "Rapport", false);
        beiText(titel, s -> r.titel = s);
        kopf.addView(titel);
        inhalt.addView(kopf, zeile(10));

        if (r.unterschrieben()) {
            unterschriftHinweis = Ui.text(this, "✍  Unterschrieben" + (r.unterzeichner.isEmpty() ? "" : " von " + r.unterzeichner)
                    + " am " + new SimpleDateFormat("dd.MM.yyyy, HH:mm", DE).format(new Date(r.unterschriebenAm)) + " Uhr"
                    + "\nÄnderungen entfernen die Unterschrift.", 14, Ui.GRUEN, true);
            int p = Ui.dp(this, 12);
            unterschriftHinweis.setPadding(p, p, p, p);
            unterschriftHinweis.setBackground(Ui.rounded(Ui.WEISS, Ui.GRUEN, Ui.dp(this, 12), Ui.dp(this, 2)));
            inhalt.addView(unterschriftHinweis, zeile(10));
        }

        inhalt.addView(zeitenKarte(), zeile(10));
        inhalt.addView(materialKarte(), zeile(10));
        inhalt.addView(dokuKarte(), zeile(10));
        inhalt.addView(fotoKarte(), zeile(10));

        LinearLayout bem = box();
        bem.addView(titelZeile("💬  Bemerkung / Hinweis", Ui.GRAU));
        EditText bemerkung = eingabe(r.bemerkung, "z. B. Hinweis zur Abrechnung (Vorlage im Menü ⋮)", true);
        beiText(bemerkung, s -> r.bemerkung = s);
        bem.addView(bemerkung);
        inhalt.addView(bem, zeile(16));

        Button vorschau = knopf("👁  Vorschau", Ui.WEISS, Ui.PRIMAER, true);
        vorschau.setOnClickListener(v -> vorschau());
        inhalt.addView(vorschau, hoch(52, 8));
        Button unter = knopf(r.unterschrieben() ? "✍  Neu unterschreiben" : "✍  Kunde unterschreibt", Ui.AKZENT, Ui.WEISS, false);
        unter.setOnClickListener(v -> unterschreiben());
        inhalt.addView(unter, hoch(56, 8));
        Button senden = knopf("📤  PDF senden", Ui.PRIMAER, Ui.WEISS, false);
        senden.setOnClickListener(v -> senden());
        inhalt.addView(senden, hoch(56, 0));
    }

    // ---------------------------------------------------------------- Zeiten

    private View zeitenKarte() {
        LinearLayout k = box();
        k.addView(titelZeile("⏱  Zeiten" + (rapport.zeiten.isEmpty() ? "" : "  ·  " + RapportPdf.dauer(rapport.summeMin()) + " h"), Ui.PRIMAER));
        if (rapport.zeiten.isEmpty()) {
            k.addView(hinweis("Keine gestempelten Zeiten für diesen Tag auf dieser Baustelle. Zeilen für dich und die Kollegen hier eintragen."));
        }
        for (int i = 0; i < rapport.zeiten.size(); i++) {
            Rapport.ZeitZeile z = rapport.zeiten.get(i);
            String a = (z.mitarbeiter.isEmpty() ? "(ohne Namen)" : z.mitarbeiter) + (z.leistung.isEmpty() ? "" : "  ·  " + z.leistung);
            String b = RapportPdf.uhr(z.vonMin) + " – " + RapportPdf.uhr(z.bisMin)
                    + (z.pauseMin > 0 ? "   Pause " + RapportPdf.dauer(z.pauseMin) : "") + "   =  " + RapportPdf.dauer(z.nettoMin()) + " h";
            int idx = i;
            k.addView(listenZeile(a, b, v -> zeitBearbeiten(idx)));
        }
        LinearLayout r = new LinearLayout(this);
        Button neu = knopf("＋  Mitarbeiter", Ui.WEISS, Ui.PRIMAER, true);
        neu.setTextSize(15);
        neu.setOnClickListener(v -> zeitBearbeiten(-1));
        Button holen = knopf("⟳  Aus Stunden", Ui.WEISS, Ui.PRIMAER, true);
        holen.setTextSize(15);
        holen.setOnClickListener(v -> {
            Runnable tun = () -> {
                rapport.zeitenUebernehmen(baustelle, Zeiten.alle(this), ZeitActivity.jetzt());
                geaendert();
                speichern(true);
                if (rapport.zeiten.isEmpty()) Toast.makeText(this, "An diesem Tag wurde auf dieser Baustelle nichts gestempelt", Toast.LENGTH_LONG).show();
            };
            if (rapport.zeiten.isEmpty()) tun.run();
            else new AlertDialog.Builder(this).setTitle("Zeiten neu übernehmen?")
                    .setMessage("Die Tabelle wird durch die gestempelten Zeiten aus „⏱ Stunden“ ersetzt.")
                    .setPositiveButton("Ersetzen", (d, w) -> tun.run()).setNegativeButton("Abbrechen", null).show();
        });
        r.addView(neu, halb(true));
        r.addView(holen, halb(false));
        k.addView(r, zeile(0));
        return k;
    }

    private void zeitBearbeiten(int index) {
        boolean neu = index < 0;
        Rapport.ZeitZeile z = new Rapport.ZeitZeile();
        if (!neu) {
            Rapport.ZeitZeile o = rapport.zeiten.get(index);
            z.mitarbeiter = o.mitarbeiter; z.leistung = o.leistung; z.vonMin = o.vonMin; z.bisMin = o.bisMin; z.pauseMin = o.pauseMin;
        } else if (!rapport.zeiten.isEmpty()) {
            // Kollege war meist genauso lange da: Zeiten und Leistung der letzten Zeile vorschlagen
            Rapport.ZeitZeile o = rapport.zeiten.get(rapport.zeiten.size() - 1);
            z.leistung = o.leistung; z.vonMin = o.vonMin; z.bisMin = o.bisMin; z.pauseMin = o.pauseMin;
        } else {
            z.mitarbeiter = Zeiten.mitarbeiter(this);
            z.vonMin = 7 * 60; z.bisMin = 16 * 60; z.pauseMin = 30;
        }
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.addView(beschriftung("Mitarbeiter"));
        EditText name = eingabe(z.mitarbeiter, "Name", false);
        b.addView(mitAuswahl(name, namensVorschlaege()));
        b.addView(beschriftung("Leistung / Tätigkeit"));
        EditText leistung = eingabe(z.leistung, "z. B. Abbund & Montage", false);
        b.addView(mitAuswahl(leistung, leistungsVorschlaege()));
        LinearLayout zeit = new LinearLayout(this);
        Button von = feldKnopf("Von  " + RapportPdf.uhr(z.vonMin));
        Button bis = feldKnopf("Bis  " + RapportPdf.uhr(z.bisMin));
        von.setOnClickListener(v -> new TimePickerDialog(this, (tp, h, m) -> { z.vonMin = h * 60 + m; von.setText("Von  " + RapportPdf.uhr(z.vonMin)); },
                z.vonMin / 60, z.vonMin % 60, true).show());
        bis.setOnClickListener(v -> new TimePickerDialog(this, (tp, h, m) -> { z.bisMin = h * 60 + m; bis.setText("Bis  " + RapportPdf.uhr(z.bisMin)); },
                z.bisMin / 60, z.bisMin % 60, true).show());
        zeit.addView(von, halb(true));
        zeit.addView(bis, halb(false));
        b.addView(zeit, zeile(0));
        b.addView(beschriftung("Pause in Minuten"));
        EditText pause = eingabe(String.valueOf(z.pauseMin), "0", false);
        pause.setInputType(InputType.TYPE_CLASS_NUMBER);
        b.addView(pause);

        AlertDialog.Builder db = new AlertDialog.Builder(this).setTitle(neu ? "Zeile hinzufügen" : "Zeile ändern")
                .setView(scroll(b))
                .setPositiveButton("Übernehmen", (d, w) -> {
                    z.mitarbeiter = name.getText().toString().trim();
                    z.leistung = leistung.getText().toString().trim();
                    try { z.pauseMin = Math.max(0, Integer.parseInt(pause.getText().toString().trim())); } catch (NumberFormatException e) { z.pauseMin = 0; }
                    if (z.bisMin < z.vonMin) Toast.makeText(this, "„Bis“ liegt vor „Von“ – bitte prüfen", Toast.LENGTH_LONG).show();
                    RapportDateien.merkeName(this, "mitarbeiter", z.mitarbeiter);
                    if (neu) rapport.zeiten.add(z); else rapport.zeiten.set(index, z);
                    geaendert();
                    speichern(true);
                })
                .setNegativeButton("Abbrechen", null);
        if (!neu) db.setNeutralButton("Löschen", (d, w) -> { rapport.zeiten.remove(index); geaendert(); speichern(true); });
        db.show();
    }

    private String[] namensVorschlaege() {
        Set<String> s = new LinkedHashSet<>();
        if (!Zeiten.mitarbeiter(this).isEmpty()) s.add(Zeiten.mitarbeiter(this));
        for (String n : RapportDateien.namen(this, "mitarbeiter")) s.add(n);
        for (Zeiteintrag z : Zeiten.alle(this)) if (!z.mitarbeiter.trim().isEmpty()) s.add(z.mitarbeiter.trim());
        return s.toArray(new String[0]);
    }

    private String[] leistungsVorschlaege() {
        Set<String> s = new LinkedHashSet<>(Zeiten.taetigkeiten(Zeiten.alle(this)));
        for (Rapport.ZeitZeile z : rapport.zeiten) if (!z.leistung.isEmpty()) s.add(z.leistung);
        String[] std = {"Abbund", "Montage", "Abbund & Montage", "Dachdeckung", "Dämmung", "Trockenbau", "Schalung", "Rückbau", "Anlieferung", "Aufräumen"};
        Collections.addAll(s, std);
        return s.toArray(new String[0]);
    }

    // ---------------------------------------------------------------- Material

    private View materialKarte() {
        LinearLayout k = box();
        k.addView(titelZeile("📦  Material", Ui.HOLZ));
        if (rapport.material.isEmpty()) k.addView(hinweis("Was heute verbaut oder geliefert wurde – direkt aus der Materialliste übernehmen."));
        for (int i = 0; i < rapport.material.size(); i++) {
            Rapport.MatZeile m = rapport.material.get(i);
            String a = (m.mengeText() + " " + m.einheit).trim();
            int idx = i;
            k.addView(listenZeile((a.isEmpty() ? "" : a + "   ") + m.bezeichnung, m.details, v -> materialBearbeiten(idx)));
        }
        LinearLayout r = new LinearLayout(this);
        Button aus = knopf("📋  Aus Liste", Ui.WEISS, Ui.HOLZ, true);
        aus.setTextSize(15);
        aus.setOnClickListener(v -> materialAusListe());
        Button frei = knopf("＋  Frei", Ui.WEISS, Ui.HOLZ, true);
        frei.setTextSize(15);
        frei.setOnClickListener(v -> materialBearbeiten(-1));
        r.addView(aus, halb(true));
        r.addView(frei, halb(false));
        k.addView(r, zeile(0));
        return k;
    }

    private void materialAusListe() {
        List<Position> pos = new ArrayList<>();
        for (Position p : baustelle.positionen) if (!p.isTitel()) pos.add(p);
        if (pos.isEmpty()) {
            Toast.makeText(this, "Die Materialliste dieser Baustelle ist noch leer – „＋ Frei“ eingeben", Toast.LENGTH_LONG).show();
            return;
        }
        String[] namen = new String[pos.size()];
        boolean[] an = new boolean[pos.size()];
        for (int i = 0; i < pos.size(); i++) {
            Rapport.MatZeile m = Rapport.ausPosition(pos.get(i));
            namen[i] = (m.mengeText() + " " + m.einheit).trim() + "  –  " + m.bezeichnung + (m.details.isEmpty() ? "" : " (" + m.details + ")");
        }
        new AlertDialog.Builder(this).setTitle("Was wurde verbaut?")
                .setMultiChoiceItems(namen, an, (d, w, c) -> an[w] = c)
                .setPositiveButton("Übernehmen", (d, w) -> {
                    int n = 0;
                    for (int i = 0; i < pos.size(); i++) if (an[i]) { rapport.material.add(Rapport.ausPosition(pos.get(i))); n++; }
                    if (n == 0) return;
                    geaendert();
                    speichern(true);
                    Toast.makeText(this, n + " übernommen – Menge antippen, wenn nur ein Teil verbaut wurde", Toast.LENGTH_LONG).show();
                })
                .setNegativeButton("Abbrechen", null).show();
    }

    private void materialBearbeiten(int index) {
        boolean neu = index < 0;
        Rapport.MatZeile m = neu ? new Rapport.MatZeile() : rapport.material.get(index);
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.addView(beschriftung("Bezeichnung"));
        EditText bez = eingabe(m.bezeichnung, "z. B. OSB/3 22 mm", false);
        b.addView(bez);
        b.addView(beschriftung("Zusatz (Maße, Qualität …) – optional"));
        EditText det = eingabe(m.details, "", false);
        b.addView(det);
        LinearLayout r = new LinearLayout(this);
        LinearLayout l1 = new LinearLayout(this);
        l1.setOrientation(LinearLayout.VERTICAL);
        l1.addView(beschriftung("Menge"));
        EditText menge = eingabe(m.menge == null ? "" : Fmt.num(m.menge), "", false);
        Ui.decimalInput(menge);
        l1.addView(menge);
        LinearLayout l2 = new LinearLayout(this);
        l2.setOrientation(LinearLayout.VERTICAL);
        l2.addView(beschriftung("Einheit"));
        EditText einh = eingabe(m.einheit, "Stk", false);
        l2.addView(mitAuswahl(einh, Einheit.ALLE));
        r.addView(l1, halb(true, -1));
        r.addView(l2, halb(false, -1));
        b.addView(r);
        AlertDialog.Builder db = new AlertDialog.Builder(this).setTitle(neu ? "Material hinzufügen" : "Material ändern")
                .setView(scroll(b))
                .setPositiveButton("Übernehmen", (d, w) -> {
                    m.bezeichnung = bez.getText().toString().trim();
                    m.details = det.getText().toString().trim();
                    m.menge = Fmt.parse(menge.getText().toString());
                    m.einheit = Einheit.norm(einh.getText().toString());
                    if (m.bezeichnung.isEmpty() && m.menge == null) return;
                    if (neu) rapport.material.add(m);
                    geaendert();
                    speichern(true);
                })
                .setNegativeButton("Abbrechen", null);
        if (!neu) db.setNeutralButton("Löschen", (d, w) -> { rapport.material.remove(index); geaendert(); speichern(true); });
        db.show();
    }

    // ---------------------------------------------------------------- Dokumentation

    private View dokuKarte() {
        LinearLayout k = box();
        k.addView(titelZeile("📝  Dokumentation", Ui.LILA));
        k.addView(beschriftung("Wetter"));
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = new LinearLayout(this);
        List<TextView> alle = new ArrayList<>();
        for (String w : WETTER) {
            TextView c = Ui.text(this, w, 15, Ui.TEXT, false);
            int p = Ui.dp(this, 12);
            c.setPadding(p, Ui.dp(this, 8), p, Ui.dp(this, 8));
            alle.add(c);
            c.setOnClickListener(v -> {
                rapport.wetter = w.equals(rapport.wetter) ? "" : w;
                for (TextView x : alle) chip(x, x.getText().toString().equals(rapport.wetter));
                geaendert();
            });
            chip(c, w.equals(rapport.wetter));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(Ui.dp(this, 6));
            chips.addView(c, lp);
        }
        hs.addView(chips);
        k.addView(hs);
        k.addView(beschriftung("Temperatur in °C"));
        EditText temp = eingabe(rapport.temperatur, "z. B. 8", false);
        temp.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
        beiText(temp, s -> rapport.temperatur = s);
        k.addView(temp);

        k.addView(beschriftung("Ausgeführte Arbeiten"));
        eintragFeld = eingabe(rapport.eintrag, "z. B. Schüttung eingebracht, OSB-Platten auf Deckenbalken montiert", true);
        beiText(eintragFeld, s -> rapport.eintrag = s);
        k.addView(mitMikrofon(eintragFeld, REQ_SPRACHE_EINTRAG));
        k.addView(beschriftung("Behinderungen / Erschwernisse"));
        behFeld = eingabe(rapport.behinderungen, "z. B. Material zu spät geliefert, Regen ab 14 Uhr", true);
        beiText(behFeld, s -> rapport.behinderungen = s);
        k.addView(mitMikrofon(behFeld, REQ_SPRACHE_BEH));
        String anw = rapport.anwesende();
        if (!anw.isEmpty()) k.addView(hinweis("Anwesend (aus den Zeiten): " + anw));
        return k;
    }

    private void chip(TextView c, boolean an) {
        c.setTextColor(an ? Ui.WEISS : Ui.TEXT);
        c.setBackground(Ui.rounded(an ? Ui.LILA : Ui.ZEBRA, an ? Ui.LILA : Ui.LINIE, Ui.dp(this, 18), Ui.dp(this, 1)));
    }

    // ---------------------------------------------------------------- Fotos

    private View fotoKarte() {
        LinearLayout k = box();
        k.addView(titelZeile("📷  Fotos" + (rapport.fotos.isEmpty() ? "" : "  ·  " + rapport.fotos.size()), Ui.BLAU));
        if (rapport.fotos.isEmpty()) k.addView(hinweis("Fotos kommen ans Ende der PDF, groß auf je einer halben Seite."));
        LinearLayout reihe = null;
        int groesse = Ui.dp(this, 96);
        for (int i = 0; i < rapport.fotos.size(); i++) {
            if (i % 3 == 0) {
                reihe = new LinearLayout(this);
                k.addView(reihe, zeile(6));
            }
            String f = rapport.fotos.get(i);
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            Bitmap bm = RapportDateien.bild(RapportDateien.datei(this, f), 300);
            if (bm != null) iv.setImageBitmap(bm);
            iv.setBackgroundColor(Ui.LINIE);
            iv.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("Foto entfernen?")
                    .setPositiveButton("Entfernen", (d, w) -> {
                        RapportDateien.datei(this, f).delete();
                        rapport.fotos.remove(f);
                        geaendert();
                        speichern(true);
                    }).setNegativeButton("Behalten", null).show());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, groesse, 1);
            lp.setMarginEnd(Ui.dp(this, 6));
            reihe.addView(iv, lp);
        }
        if (reihe != null) for (int i = rapport.fotos.size() % 3; i != 0 && i < 3; i++) {
            reihe.addView(new View(this), new LinearLayout.LayoutParams(0, groesse, 1)); // Lücke auffüllen
        }
        LinearLayout r = new LinearLayout(this);
        Button kam = knopf("📷  Kamera", Ui.WEISS, Ui.BLAU, true);
        kam.setTextSize(15);
        kam.setOnClickListener(v -> kamera());
        Button gal = knopf("🖼  Galerie", Ui.WEISS, Ui.BLAU, true);
        gal.setTextSize(15);
        gal.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("image/*");
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            starte(Intent.createChooser(i, "Fotos auswählen"), REQ_GALERIE);
        });
        r.addView(kam, halb(true));
        r.addView(gal, halb(false));
        k.addView(r, zeile(0));
        return k;
    }

    private void kamera() {
        File dir = new File(getCacheDir(), "export");
        if (!dir.exists()) dir.mkdirs();
        kameraDatei = "kamera_" + System.currentTimeMillis() + ".jpg";
        Uri uri = ExportProvider.uriFor(kameraDatei);
        Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        i.putExtra(MediaStore.EXTRA_OUTPUT, uri);
        i.setClipData(ClipData.newRawUri("", uri));
        i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        starte(i, REQ_KAMERA);
    }

    private void fotoUebernehmen(Uri u) {
        try {
            rapport.fotos.add(RapportDateien.fotoSpeichern(this, u));
        } catch (Exception e) {
            Toast.makeText(this, "Foto konnte nicht übernommen werden: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // ================================================================ Vorschau, Unterschrift, Senden

    private void vorschau() {
        speichern(false);
        Dialog d = new Dialog(this, android.R.style.Theme_Material_Light_NoActionBar);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.HELL);
        ScrollView sv = new ScrollView(this);
        LinearLayout seiten = new LinearLayout(this);
        seiten.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 8);
        seiten.setPadding(p, p, p, p);
        for (Bitmap b : RapportPdf.bilder(this, baustelle, rapport, 2f)) {
            ImageView iv = new ImageView(this);
            iv.setImageBitmap(b);
            iv.setAdjustViewBounds(true);
            iv.setBackgroundColor(Ui.WEISS);
            seiten.addView(iv, zeile(8));
        }
        sv.addView(seiten);
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        LinearLayout leiste = new LinearLayout(this);
        leiste.setBackgroundColor(Ui.WEISS);
        leiste.setPadding(p, p, p, p);
        Button zu = knopf("✕", Ui.WEISS, Ui.GRAU, true);
        zu.setOnClickListener(v -> d.dismiss());
        Button unter = knopf("✍ Unterschreiben", Ui.AKZENT, Ui.WEISS, false);
        unter.setOnClickListener(v -> { d.dismiss(); unterschreiben(); });
        Button send = knopf("📤 Senden", Ui.PRIMAER, Ui.WEISS, false);
        send.setOnClickListener(v -> { d.dismiss(); senden(); });
        LinearLayout.LayoutParams l0 = new LinearLayout.LayoutParams(Ui.dp(this, 56), Ui.dp(this, 52));
        l0.setMarginEnd(Ui.dp(this, 8));
        leiste.addView(zu, l0);
        leiste.addView(unter, halb(true, 52));
        leiste.addView(send, halb(false, 52));
        root.addView(leiste);
        d.setContentView(root);
        d.show();
    }

    private void unterschreiben() {
        speichern(false);
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.addView(beschriftung("Name des Unterzeichners"));
        String vorschlag = rapport.unterzeichner.isEmpty() ? (baustelle.kunde == null ? "" : baustelle.kunde.trim()) : rapport.unterzeichner;
        EditText name = eingabe(vorschlag, "Vor- und Nachname", false);
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        Set<String> namen = new LinkedHashSet<>();
        if (baustelle.kunde != null && !baustelle.kunde.trim().isEmpty()) namen.add(baustelle.kunde.trim());
        Collections.addAll(namen, RapportDateien.namen(this, "unterzeichner"));
        b.addView(mitAuswahl(name, namen.toArray(new String[0])));
        b.addView(hinweis("Mit der Unterschrift bestätigt der Auftraggeber die Angaben im Rapport (Zeiten "
                + RapportPdf.dauer(rapport.summeMin()) + " h" + (rapport.material.isEmpty() ? "" : ", " + rapport.material.size() + " Material") + ")."));
        UnterschriftView feld = new UnterschriftView(this);
        feld.setBackground(Ui.rounded(Ui.WEISS, Ui.LINIE, Ui.dp(this, 8), Ui.dp(this, 2)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 220));
        lp.topMargin = Ui.dp(this, 8);
        b.addView(feld, lp);
        LinearLayout rahmen = new LinearLayout(this);
        int p = Ui.dp(this, 20);
        rahmen.setPadding(p, Ui.dp(this, 8), p, 0);
        rahmen.addView(b, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        AlertDialog d = new AlertDialog.Builder(this).setTitle("✍ Unterschrift")
                .setView(rahmen)
                .setPositiveButton("Übernehmen", null)
                .setNeutralButton("Löschen", null)
                .setNegativeButton("Abbrechen", null).create();
        d.setOnShowListener(x -> {
            d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> feld.loeschen());
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                Bitmap sig = feld.bild();
                if (sig == null) {
                    Toast.makeText(this, "Bitte zuerst im weißen Feld unterschreiben", Toast.LENGTH_SHORT).show();
                    return;
                }
                try {
                    if (rapport.unterschrieben()) RapportDateien.datei(this, rapport.unterschrift).delete();
                    rapport.unterschrift = RapportDateien.unterschriftSpeichern(this, sig);
                    rapport.unterzeichner = name.getText().toString().trim();
                    rapport.unterschriebenAm = System.currentTimeMillis();
                    RapportDateien.merkeName(this, "unterzeichner", rapport.unterzeichner);
                    speichern(true);
                    d.dismiss();
                    new AlertDialog.Builder(this).setTitle("✓ Unterschrieben")
                            .setMessage("PDF jetzt senden (WhatsApp, E-Mail …)?")
                            .setPositiveButton("📤 Senden", (dd, w) -> senden())
                            .setNegativeButton("Später", null).show();
                } catch (Exception e) {
                    Toast.makeText(this, "Unterschrift konnte nicht gespeichert werden: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            });
        });
        d.show();
    }

    private void senden() {
        speichern(false);
        try {
            File dir = new File(getCacheDir(), "export");
            if (!dir.exists()) dir.mkdirs();
            File[] alt = dir.listFiles();
            if (alt != null) for (File f : alt) if (f.getName().endsWith(".pdf")) f.delete();
            File f = new File(dir, RapportPdf.dateiname(baustelle, rapport));
            try (FileOutputStream out = new FileOutputStream(f)) {
                RapportPdf.pdf(this, baustelle, rapport, out);
            }
            Uri uri = ExportProvider.uriFor(f.getName());
            Intent s = new Intent(Intent.ACTION_SEND);
            s.setType("application/pdf");
            s.putExtra(Intent.EXTRA_STREAM, uri);
            String betreff = rapport.titel + " " + Zeiten.datum(rapport.tag) + " – " + baustelle.titel();
            s.putExtra(Intent.EXTRA_SUBJECT, betreff);
            s.putExtra(Intent.EXTRA_TEXT, betreff + (rapport.unterschrieben() ? "" : "\n(noch nicht unterschrieben)"));
            s.setClipData(ClipData.newRawUri("", uri));
            s.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(s, "Rapport senden an …"));
        } catch (Exception e) {
            Toast.makeText(this, "PDF konnte nicht erstellt werden: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // ================================================================ Einstellungen: Briefkopf, Hinweistext

    private void firmaDialog() {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.addView(beschriftung("Firmenname"));
        EditText name = eingabe(RapportDateien.firmaName(this), "z. B. Zimmerei Muster GmbH", false);
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        b.addView(name);
        b.addView(beschriftung("Anschrift, Telefon, E-Mail (eine Angabe pro Zeile)"));
        EditText adr = eingabe(RapportDateien.firmaAdresse(this), "Straße 1\n12345 Ort\nTel. 0123 456789", true);
        b.addView(adr);
        b.addView(beschriftung("Logo (oben rechts auf dem Rapport)"));
        logoVorschau = new ImageView(this);
        logoVorschau.setAdjustViewBounds(true);
        logoVorschau.setMaxHeight(Ui.dp(this, 80));
        Bitmap lg = RapportDateien.bild(RapportDateien.logo(this), 600);
        if (lg != null) logoVorschau.setImageBitmap(lg);
        b.addView(logoVorschau, zeile(4));
        LinearLayout r = new LinearLayout(this);
        Button waehlen = feldKnopf("🖼  Logo wählen …");
        waehlen.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("image/*");
            i.addCategory(Intent.CATEGORY_OPENABLE);
            starte(Intent.createChooser(i, "Logo auswählen"), REQ_LOGO);
        });
        Button weg = feldKnopf("✕  Entfernen");
        weg.setOnClickListener(v -> { RapportDateien.logo(this).delete(); logoVorschau.setImageDrawable(null); });
        r.addView(waehlen, halb(true));
        r.addView(weg, halb(false));
        b.addView(r);
        new AlertDialog.Builder(this).setTitle("🏢 Briefkopf").setView(scroll(b))
                .setPositiveButton("Speichern", (d, w) -> {
                    RapportDateien.setFirma(this, name.getText().toString().trim(), adr.getText().toString().trim());
                    render();
                })
                .setNegativeButton("Abbrechen", null)
                .setOnDismissListener(d -> logoVorschau = null).show();
    }

    private void vorlageDialog() {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.addView(hinweis("Dieser Text steht bei jedem neuen Rapport unter „Bemerkung“ (z. B. Hinweis zur Abrechnung). "
                + "Den Vorschlag bitte selbst prüfen – keine Rechtsberatung."));
        EditText t = eingabe(RapportDateien.bemerkungVorlage(this), "Hinweistext", true);
        t.setMinLines(5);
        b.addView(t);
        AlertDialog d = new AlertDialog.Builder(this).setTitle("💬 Hinweistext-Vorlage").setView(scroll(b))
                .setPositiveButton("Speichern", (x, w) -> {
                    RapportDateien.setBemerkungVorlage(this, t.getText().toString().trim());
                    Toast.makeText(this, "Gilt für neue Rapporte", Toast.LENGTH_SHORT).show();
                })
                .setNeutralButton("Vorschlag VOB", null)
                .setNegativeButton("Abbrechen", null).create();
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> t.setText(RapportDateien.VOB_VORSCHLAG)));
        d.show();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, "🏢 Briefkopf (Firma & Logo) …");
        menu.add(0, 2, 1, "💬 Hinweistext-Vorlage …");
        if (rapport != null) {
            menu.add(0, 3, 2, "💬 Vorlage in diesen Rapport übernehmen");
            menu.add(0, 4, 3, "🗑 Rapport löschen");
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home: finish(); return true;
            case 1: firmaDialog(); return true;
            case 2: vorlageDialog(); return true;
            case 3:
                rapport.bemerkung = RapportDateien.bemerkungVorlage(this);
                geaendert();
                speichern(true);
                return true;
            case 4: loeschenFragen(rapport); return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ================================================================ Ergebnisse anderer Apps

    private void starte(Intent i, int req) {
        try {
            startActivityForResult(i, req);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, req == REQ_SPRACHE_EINTRAG || req == REQ_SPRACHE_BEH
                    ? "Keine Spracherkennung auf dem Handy gefunden (Google-App installieren) – das Mikrofon der Tastatur geht auch"
                    : "Keine passende App gefunden", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_LOGO && res == RESULT_OK && data != null && data.getData() != null) {
            try {
                RapportDateien.logoSpeichern(this, data.getData());
                if (logoVorschau != null) logoVorschau.setImageBitmap(RapportDateien.bild(RapportDateien.logo(this), 600));
                Toast.makeText(this, "Logo übernommen", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "Logo konnte nicht gelesen werden: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
            return;
        }
        if (rapport == null) return;
        if (req == REQ_KAMERA) {
            File f = kameraDatei == null ? null : new File(new File(getCacheDir(), "export"), kameraDatei);
            if (res == RESULT_OK && f != null && f.length() > 0) {
                fotoUebernehmen(Uri.fromFile(f));
                geaendert();
                speichern(true);
            }
            if (f != null) f.delete();
            kameraDatei = null;
        } else if (req == REQ_GALERIE && res == RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++) fotoUebernehmen(data.getClipData().getItemAt(i).getUri());
            } else if (data.getData() != null) {
                fotoUebernehmen(data.getData());
            }
            geaendert();
            speichern(true);
        } else if ((req == REQ_SPRACHE_EINTRAG || req == REQ_SPRACHE_BEH) && res == RESULT_OK && data != null) {
            ArrayList<String> t = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (t == null || t.isEmpty()) return;
            EditText e = req == REQ_SPRACHE_EINTRAG ? eintragFeld : behFeld;
            if (e == null) return;
            String alt = e.getText().toString().trim();
            String neu = t.get(0).trim();
            if (!neu.isEmpty()) neu = Character.toUpperCase(neu.charAt(0)) + neu.substring(1);
            e.setText(alt.isEmpty() ? neu : alt + (alt.endsWith(".") ? " " : ". ") + neu);
            e.setSelection(e.getText().length());
        }
    }

    // ================================================================ Bausteine der Oberfläche

    private interface Text { void gesetzt(String s); }

    /** Übernimmt Eingaben sofort in den Rapport (gespeichert wird beim Verlassen und vor Vorschau/Senden). */
    private void beiText(EditText e, Text t) {
        e.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence c, int a, int b, int d) {}
            @Override public void onTextChanged(CharSequence c, int a, int b, int d) {}
            @Override public void afterTextChanged(Editable s) {
                t.gesetzt(s.toString().trim());
                geaendert();
            }
        });
    }

    private void datumWaehlen(long vorgabe, java.util.function.LongConsumer ok) {
        Calendar c = Calendar.getInstance(DE);
        c.setTimeInMillis(vorgabe);
        new DatePickerDialog(this, (dp, y, m, d) -> {
            Calendar x = Calendar.getInstance(DE);
            x.clear();
            x.set(y, m, d);
            ok.accept(x.getTimeInMillis());
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show();
    }

    private LinearLayout box() {
        LinearLayout k = new LinearLayout(this);
        k.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 14);
        k.setPadding(p, Ui.dp(this, 12), p, p);
        k.setBackground(Ui.rounded(Ui.WEISS, Ui.LINIE, Ui.dp(this, 14), Ui.dp(this, 1)));
        return k;
    }

    private View karte(String titel, String text, int farbe) {
        LinearLayout k = box();
        k.setBackground(Ui.rounded(Ui.WEISS, farbe, Ui.dp(this, 14), Ui.dp(this, 2)));
        TextView t = Ui.text(this, titel, 17, farbe, true);
        k.addView(t);
        TextView s = Ui.text(this, text, 14, Ui.GRAU, false);
        s.setPadding(0, Ui.dp(this, 2), 0, 0);
        k.addView(s);
        return k;
    }

    private TextView titelZeile(String s, int farbe) {
        TextView t = Ui.text(this, s, 18, farbe, true);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, 0, 0, Ui.dp(this, 6));
        return t;
    }

    private TextView hinweis(String s) {
        TextView t = Ui.text(this, s, 14, Ui.GRAU, false);
        t.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 8));
        return t;
    }

    private TextView beschriftung(String s) {
        TextView t = Ui.text(this, s, 13, Ui.GRAU, false);
        t.setPadding(Ui.dp(this, 4), Ui.dp(this, 10), 0, 0);
        return t;
    }

    private View listenZeile(String oben, String unten, View.OnClickListener klick) {
        LinearLayout z = new LinearLayout(this);
        z.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 10);
        z.setPadding(p, p, p, p);
        z.setBackground(Ui.touchBg(Ui.ZEBRA));
        z.addView(Ui.text(this, oben, 16, Ui.TEXT, true));
        if (unten != null && !unten.isEmpty()) z.addView(Ui.text(this, unten, 14, Ui.GRAU, false));
        z.setOnClickListener(klick);
        LinearLayout.LayoutParams lp = zeile(6);
        z.setLayoutParams(lp);
        return z;
    }

    private EditText eingabe(String wert, String hint, boolean mehrzeilig) {
        EditText e = new EditText(this);
        e.setText(wert);
        e.setHint(hint);
        e.setTextSize(16);
        if (mehrzeilig) {
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            e.setMinLines(2);
            e.setGravity(Gravity.TOP | Gravity.START);
        } else {
            e.setSingleLine(true);
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        }
        return e;
    }

    /** Eingabefeld mit „▾“-Knopf für schnelle Auswahl aus Vorschlägen. */
    private View mitAuswahl(EditText e, String[] vorschlaege) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.addView(e, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (vorschlaege.length == 0) return r;
        Button b = feldKnopf("▾");
        b.setGravity(Gravity.CENTER);
        b.setOnClickListener(v -> new AlertDialog.Builder(this).setItems(vorschlaege, (d, w) -> {
            e.setText(vorschlaege[w]);
            e.setSelection(e.getText().length());
        }).show());
        r.addView(b, new LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 48)));
        return r;
    }

    /** Eingabefeld mit Mikrofon-Knopf (Spracheingabe). */
    private View mitMikrofon(EditText e, int req) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.TOP);
        r.addView(e, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button b = knopf("🎤", Ui.WEISS, Ui.LILA, true);
        b.setOnClickListener(v -> {
            Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "de-DE");
            i.putExtra(RecognizerIntent.EXTRA_PROMPT, "Was wurde gemacht?");
            starte(i, req);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 52));
        lp.setMarginStart(Ui.dp(this, 6));
        r.addView(b, lp);
        return r;
    }

    private Button knopf(String text, int bg, int fg, boolean rand) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(17);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(fg);
        b.setBackground(Ui.rounded(bg, rand ? fg : 0, Ui.dp(this, 12), rand ? Ui.dp(this, 2) : 0));
        return b;
    }

    private Button feldKnopf(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        return b;
    }

    private View scroll(View v) {
        ScrollView sv = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        int p = Ui.dp(this, 20);
        box.setPadding(p, Ui.dp(this, 8), p, 0);
        box.addView(v, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        sv.addView(box);
        return sv;
    }

    private LinearLayout.LayoutParams zeile(int abstandDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(this, abstandDp);
        return lp;
    }

    private LinearLayout.LayoutParams hoch(int hoeheDp, int abstandDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, hoeheDp));
        lp.bottomMargin = Ui.dp(this, abstandDp);
        return lp;
    }

    private LinearLayout.LayoutParams halb(boolean links) {
        return halb(links, 48);
    }

    private LinearLayout.LayoutParams halb(boolean links, int hoeheDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, hoeheDp < 0 ? ViewGroup.LayoutParams.WRAP_CONTENT : Ui.dp(this, hoeheDp), 1);
        if (links) lp.setMarginEnd(Ui.dp(this, 8));
        return lp;
    }
}
