package de.zimmerei.holzliste;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/** Feld zum Unterschreiben mit dem Finger. {@link #bild()} liefert die Unterschrift zugeschnitten mit transparentem Hintergrund. */
public class UnterschriftView extends View {

    private final Path pfad = new Path();
    private final Paint stift = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hilfe = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bereich = new RectF();
    private float lx, ly;
    private boolean leer = true;
    private Runnable geaendert;

    public UnterschriftView(Context c) {
        super(c);
        stift.setColor(Color.parseColor("#0D2A6B"));
        stift.setStyle(Paint.Style.STROKE);
        stift.setStrokeCap(Paint.Cap.ROUND);
        stift.setStrokeJoin(Paint.Join.ROUND);
        stift.setStrokeWidth(Ui.dp(c, 3));
        hilfe.setColor(Ui.HELL);
        hilfe.setStrokeWidth(Ui.dp(c, 1.5f));
        hilfe.setTextSize(Ui.dp(c, 14));
        setBackgroundColor(Color.WHITE);
    }

    public void beiAenderung(Runnable r) { geaendert = r; }

    public boolean istLeer() { return leer; }

    public void loeschen() {
        pfad.reset();
        leer = true;
        invalidate();
        if (geaendert != null) geaendert.run();
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        float y = getHeight() * 0.75f;
        c.drawLine(getWidth() * 0.06f, y, getWidth() * 0.94f, y, hilfe);
        if (leer) c.drawText("✍  Hier mit dem Finger unterschreiben", getWidth() * 0.06f, y - Ui.dp(getContext(), 10), hilfe);
        c.drawPath(pfad, stift);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getAction()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true); // nicht scrollen beim Unterschreiben
                pfad.moveTo(x, y);
                pfad.lineTo(x + 0.1f, y + 0.1f); // auch ein Punkt ist sichtbar
                lx = x; ly = y;
                break;
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < e.getHistorySize(); i++) glatt(e.getHistoricalX(i), e.getHistoricalY(i));
                glatt(x, y);
                break;
            case MotionEvent.ACTION_UP:
                pfad.lineTo(x, y);
                boolean warLeer = leer;
                leer = false;
                if (warLeer && geaendert != null) geaendert.run();
                break;
            default:
                return true;
        }
        invalidate();
        return true;
    }

    private void glatt(float x, float y) {
        pfad.quadTo(lx, ly, (x + lx) / 2, (y + ly) / 2);
        lx = x; ly = y;
    }

    /** Unterschrift als Bild (zugeschnitten, transparent), null wenn leer. */
    public Bitmap bild() {
        if (leer) return null;
        pfad.computeBounds(bereich, true);
        float r = stift.getStrokeWidth() * 2;
        bereich.inset(-r, -r);
        int w = Math.max(1, Math.round(bereich.width())), h = Math.max(1, Math.round(bereich.height()));
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.translate(-bereich.left, -bereich.top);
        c.drawPath(pfad, stift);
        return b;
    }
}
