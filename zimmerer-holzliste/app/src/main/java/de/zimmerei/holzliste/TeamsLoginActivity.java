package de.zimmerei.holzliste;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.TextView;

/** Rückkehr aus dem Browser nach der Microsoft-Anmeldung (de.zimmerei.holzliste://auth?code=…). */
public class TeamsLoginActivity extends Activity {

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        TextView t = Ui.text(this, "⟳  Anmeldung wird abgeschlossen …", 17, Ui.PRIMAER, true);
        t.setGravity(Gravity.CENTER);
        t.setBackgroundColor(Ui.HINTERGRUND);
        setContentView(t);
        Uri daten = getIntent().getData();
        if (daten == null) { finish(); return; }
        TeamSync.imHintergrund(() -> { TeamsKonto.rueckkehr(this, daten, Graph.standard); return true; }, (ok, f) -> {
            if (isFinishing()) return;
            if (f != null) {
                new AlertDialog.Builder(this).setTitle("Anmeldung fehlgeschlagen")
                        .setMessage(f.getMessage() + "\n\nHäufige Ursache: Die App ist im Unternehmen noch nicht freigegeben "
                                + "(Admin muss „Administratorzustimmung erteilen“) oder die Umleitungs-URI fehlt in der App-Registrierung.")
                        .setPositiveButton("OK", (d, w) -> weiter())
                        .setOnCancelListener(d -> weiter()).show();
                return;
            }
            weiter();
        });
    }

    private void weiter() {
        Intent i = new Intent(this, TeamsActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(i);
        finish();
    }
}
