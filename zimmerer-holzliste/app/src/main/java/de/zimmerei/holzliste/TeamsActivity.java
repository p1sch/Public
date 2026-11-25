package de.zimmerei.holzliste;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * „👥 Meine Teams“: alle Microsoft-Teams-Teams des Benutzers – ein Team ist eine Baustelle. Antippen öffnet die
 * Baustelle (bei Bedarf aus dem Team geladen oder neu angelegt). Mit {@link #EXTRA_VERKNUEPFEN} wählt man das
 * Team für eine vorhandene Baustelle. Ohne Einrichtung/Anmeldung zeigt die Seite die nötigen Schritte.
 */
public class TeamsActivity extends Activity {

    /** ID einer Baustelle, die mit dem gewählten Team verknüpft werden soll. */
    public static final String EXTRA_VERKNUEPFEN = "verknuepfen";

    private LinearLayout inhalt;
    private List<Graph.Team> teams;
    private String ladeFehler;
    private boolean laedt;
    /** Tests: Teams ohne Netz vorgeben. */
    static List<Graph.Team> testTeams;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
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
        setTitle(verknuepfen() == null ? "👥 Meine Teams" : "👥 Team für die Baustelle");
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (testTeams != null) teams = testTeams;
        render();
        if (teams == null && TeamsKonto.angemeldet(this)) laden();
        TeamSync.anstossen(this); // Postausgang und offene Änderungen mitnehmen
    }

    private String verknuepfen() {
        return getIntent().getStringExtra(EXTRA_VERKNUEPFEN);
    }

    private void laden() {
        laedt = true;
        ladeFehler = null;
        render();
        TeamSync.imHintergrund(() -> TeamSync.graph(this).meineTeams(), (l, f) -> {
            laedt = false;
            if (f != null) {
                ladeFehler = f.getMessage();
                if (f instanceof Graph.Fehler && ((Graph.Fehler) f).code == 401) TeamsKonto.abmelden(this);
            } else teams = l;
            if (!isFinishing()) render();
        });
    }

    // ================================================================ Darstellung

    private void render() {
        inhalt.removeAllViews();
        if (!TeamsKonto.eingerichtet(this)) { einrichtung(); return; }
        if (!TeamsKonto.angemeldet(this) && testTeams == null) { anmeldung(); return; }

        String name = TeamsKonto.name(this);
        TextView kopf = Ui.text(this, verknuepfen() != null ? "Welches Team gehört zu dieser Baustelle?"
                : "Ein Team = eine Baustelle. Antippen zum Arbeiten.", 15, Ui.GRAU, false);
        kopf.setPadding(Ui.dp(this, 4), 0, 0, Ui.dp(this, 4));
        inhalt.addView(kopf);
        if (!name.isEmpty()) {
            TextView n = Ui.text(this, "Angemeldet als " + name, 13, Ui.GRAU, false);
            n.setPadding(Ui.dp(this, 4), 0, 0, Ui.dp(this, 10));
            inhalt.addView(n);
        }

        if (laedt) {
            inhalt.addView(hinweis("⟳  Teams werden geladen …"));
            return;
        }
        if (ladeFehler != null) {
            inhalt.addView(karte("⚠  Teams konnten nicht geladen werden", ladeFehler + "\n\nAntippen zum Wiederholen", ZeitActivity.ROT,
                    v -> laden()), zeile(10));
            return;
        }
        if (teams == null) return;
        if (teams.isEmpty()) {
            inhalt.addView(hinweis("Du bist noch in keinem Team.\n\nLege in Microsoft Teams für jede Baustelle ein Team an "
                    + "(Teams → „Team beitreten oder erstellen“) und tippe dann oben rechts auf ⟳."));
        }
        for (Graph.Team t : teams) {
            Holzliste h = TeamSync.lokal(this, t.id);
            String text;
            int farbe;
            if (h != null) {
                text = "📱 auf diesem Handy · " + TeamSync.statusText(this, h);
                farbe = TeamSync.konflikt(this, h.id) ? Ui.AKZENT : Ui.GRUEN;
            } else {
                text = verknuepfen() != null ? "Mit diesem Team verknüpfen" : "Antippen: aus dem Team laden oder als Baustelle anlegen";
                farbe = Ui.PRIMAER;
            }
            if (!t.beschreibung.isEmpty()) text = t.beschreibung + "\n" + text;
            inhalt.addView(karte("👥  " + t.name, text, farbe, v -> gewaehlt(t)), zeile(8));
        }
    }

    private void einrichtung() {
        inhalt.addView(karte("👥  Microsoft Teams verbinden",
                "Einmalig: Die App muss bei Microsoft registriert werden (kostenlos, macht der Admin von Microsoft 365). "
                        + "Die Schritt-für-Schritt-Anleitung steht in der README unter „Microsoft Teams einrichten“.\n\n"
                        + "Danach hier die Anwendungs-ID (Client-ID) eintragen.", Ui.PRIMAER, null), zeile(12));
        LinearLayout b = box();
        b.addView(beschriftung("Anwendungs-ID (Client-ID)"));
        EditText id = feld(TeamsKonto.clientId(this), "z. B. 1a2b3c4d-…");
        b.addView(id);
        b.addView(beschriftung("Verzeichnis-ID (Mandant) – optional"));
        EditText mandant = feld("", "leer lassen = jedes Firmenkonto");
        b.addView(mandant);
        Button ok = knopf("Speichern und anmelden", Ui.PRIMAER, Ui.WEISS);
        ok.setOnClickListener(v -> {
            String c = id.getText().toString().trim();
            if (!c.matches("[0-9a-fA-F-]{36}")) {
                Toast.makeText(this, "Die Client-ID sieht so aus: 8-4-4-4-12 Zeichen (Ziffern und a–f)", Toast.LENGTH_LONG).show();
                return;
            }
            TeamsKonto.einrichten(this, c, mandant.getText().toString());
            anmelden();
        });
        LinearLayout.LayoutParams lp = hoch(52);
        lp.topMargin = Ui.dp(this, 12);
        b.addView(ok, lp);
        inhalt.addView(b, zeile(0));
    }

    private void anmeldung() {
        inhalt.addView(karte("👥  Mit Microsoft anmelden",
                "Melde dich einmal mit deinem Firmenkonto an (wie bei Outlook oder Teams). Danach siehst du hier alle deine Teams.",
                Ui.PRIMAER, null), zeile(12));
        Button an = knopf("Mit Microsoft anmelden", Ui.PRIMAER, Ui.WEISS);
        an.setOnClickListener(v -> anmelden());
        inhalt.addView(an, hoch(56));
    }

    private void anmelden() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, TeamsKonto.anmeldeAdresse(this)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Kein Browser gefunden", Toast.LENGTH_LONG).show();
        }
    }

    // ================================================================ Team gewählt

    private void gewaehlt(Graph.Team t) {
        String lid = verknuepfen();
        if (lid != null) { verknuepfenMit(lid, t); return; }
        Holzliste h = TeamSync.lokal(this, t.id);
        if (h != null) { oeffnen(h); return; }
        ProgressDialog pd = warten("Hole „" + t.name + "“ aus dem Team …");
        TeamSync.imHintergrund(() -> TeamSync.ausTeamLaden(this, t), (geladen, f) -> {
            pd.dismiss();
            if (f != null) { fehler(f); return; }
            if (geladen != null) { oeffnen(geladen); return; }
            // im Team gibt es noch keine Baustelle
            List<Holzliste> frei = new ArrayList<>();
            for (Holzliste x : Storage.alle(this)) if (!TeamSync.verknuepft(x)) frei.add(x);
            List<String> wahl = new ArrayList<>();
            wahl.add("＋  Neue Baustelle „" + t.name + "“");
            for (Holzliste x : frei) wahl.add("🔗  " + x.titel() + " verknüpfen");
            new AlertDialog.Builder(this).setTitle("Im Team gibt es noch keine Baustelle")
                    .setItems(wahl.toArray(new String[0]), (d, w) -> {
                        if (w == 0) {
                            Holzliste neu = new Holzliste();
                            neu.projekt = t.name;
                            neu.datum = Zeiten.datum(System.currentTimeMillis());
                            neu.bearbeiter = Zeiten.mitarbeiter(this);
                            Storage.schreiben(this, neu, true);
                            verknuepfenMit(neu.id, t);
                        } else verknuepfenMit(frei.get(w - 1).id, t);
                    })
                    .setNegativeButton("Abbrechen", null).show();
        });
    }

    private void verknuepfenMit(String listeId, Graph.Team t) {
        Holzliste h = Storage.laden(this, listeId);
        if (h == null) return;
        Holzliste schon = TeamSync.lokal(this, t.id);
        if (schon != null && !schon.id.equals(h.id)) {
            Toast.makeText(this, "„" + t.name + "“ gehört schon zur Baustelle „" + schon.titel() + "“", Toast.LENGTH_LONG).show();
            return;
        }
        ProgressDialog pd = warten("Verknüpfe mit „" + t.name + "“ …");
        TeamSync.imHintergrund(() -> TeamSync.verknuepfen(this, h, t), (e, f) -> {
            pd.dismiss();
            if (f != null) { fehler(f); return; }
            if (e == TeamSync.Ergebnis.KONFLIKT) {
                BaustelleActivity.konfliktFragen(this, h.id, "Im Team „" + t.name + "“ liegt schon eine Baustelle.",
                        () -> oeffnen(Storage.laden(this, h.id)));
                return;
            }
            Toast.makeText(this, "✓ Verknüpft – Änderungen gehen jetzt automatisch ins Team", Toast.LENGTH_LONG).show();
            if (verknuepfen() != null) finish(); else oeffnen(Storage.laden(this, h.id));
        });
    }

    private void oeffnen(Holzliste h) {
        if (h == null) return;
        Intent i = new Intent(this, BaustelleActivity.class);
        i.putExtra(BaustelleActivity.EXTRA_ID, h.id);
        startActivity(i);
        if (verknuepfen() != null) finish();
    }

    private void fehler(Exception f) {
        new AlertDialog.Builder(this).setTitle("Das hat nicht geklappt")
                .setMessage(f.getMessage() == null ? f.toString() : f.getMessage())
                .setPositiveButton("OK", null).show();
    }

    @SuppressWarnings("deprecation")
    private ProgressDialog warten(String text) {
        ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage(text);
        pd.setCancelable(false);
        pd.show();
        return pd;
    }

    // ================================================================ Menü

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        if (TeamsKonto.angemeldet(this)) {
            menu.add(0, 1, 0, "⟳").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
            menu.add(0, 2, 1, "Abmelden");
        }
        menu.add(0, 3, 2, "Client-ID ändern …");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home: finish(); return true;
            case 1: teams = null; laden(); return true;
            case 2:
                TeamsKonto.abmelden(this);
                teams = null;
                invalidateOptionsMenu();
                render();
                return true;
            case 3:
                TeamsKonto.einrichten(this, "", "");
                TeamsKonto.abmelden(this);
                teams = null;
                invalidateOptionsMenu();
                render();
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ================================================================ Bausteine

    private LinearLayout box() {
        LinearLayout k = new LinearLayout(this);
        k.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 14);
        k.setPadding(p, Ui.dp(this, 12), p, p);
        k.setBackground(Ui.rounded(Ui.WEISS, Ui.LINIE, Ui.dp(this, 14), Ui.dp(this, 1)));
        return k;
    }

    private View karte(String titel, String text, int farbe, View.OnClickListener klick) {
        LinearLayout k = box();
        k.setBackground(Ui.rounded(Ui.WEISS, farbe, Ui.dp(this, 14), Ui.dp(this, 2)));
        TextView t = Ui.text(this, titel, 18, farbe, true);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        k.addView(t);
        TextView s = Ui.text(this, text, 14, Ui.GRAU, false);
        s.setPadding(0, Ui.dp(this, 3), 0, 0);
        k.addView(s);
        if (klick != null) k.setOnClickListener(klick);
        return k;
    }

    private TextView hinweis(String s) {
        TextView t = Ui.text(this, s, 15, Ui.GRAU, false);
        t.setGravity(Gravity.CENTER);
        t.setPadding(Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16), 0);
        return t;
    }

    private TextView beschriftung(String s) {
        TextView t = Ui.text(this, s, 13, Ui.GRAU, false);
        t.setPadding(Ui.dp(this, 4), Ui.dp(this, 10), 0, 0);
        return t;
    }

    private EditText feld(String wert, String hint) {
        EditText e = new EditText(this);
        e.setText(wert);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        return e;
    }

    private Button knopf(String text, int bg, int fg) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(17);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(fg);
        b.setBackground(Ui.rounded(bg, 0, Ui.dp(this, 12), 0));
        return b;
    }

    private LinearLayout.LayoutParams zeile(int abstandDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(this, abstandDp);
        return lp;
    }

    private LinearLayout.LayoutParams hoch(int dp) {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, dp));
    }
}
