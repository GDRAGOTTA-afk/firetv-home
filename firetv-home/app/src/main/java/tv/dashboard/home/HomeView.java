package tv.dashboard.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Schermata principale in stile Apple TV, disegnata tutta in una sola vista: sfondo che cambia,
 * anteprima grande dell'app selezionata, griglia di riquadri che si sollevano quando li selezioni.
 * Ogni movimento e' un'interpolazione continua (nessuno scatto) e si ridisegna solo mentre
 * qualcosa si muove.
 */
final class HomeView extends View {
    static final int COLS = 6;
    private static final int VISIBLE_ROWS = 2;
    private static final int ACCENT = 0xFFFFD60A;

    private ImageStore store;
    private List<AppEntry> apps = new ArrayList<>();
    private int sel;
    private int topRow;
    private boolean moving;

    // Valori animati
    private float fx, fy;
    private float scrollRow;
    private float heroT = 1f;
    private float wallT;
    private long lastFrame;
    private long selChangedAt;
    private AppEntry heroCur, heroPrev;
    private Bitmap wallCur, wallNext;

    // Geometria (calcolata da onSizeChanged)
    private int viewW, viewH;
    private float s = 1f;
    private float tileW, tileH, gapX, rowPitch, marginL, rowsTop, cardX, cardY, cardW, cardH, radius;
    private float[] proxBuf = new float[0];

    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dimPaint = new Paint();
    private final Paint bottomPaint = new Paint();
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint namePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint hintPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint timePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint datePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint labelPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("EEEE d MMMM", Locale.getDefault());
    private long clockMinute = -1;
    private String clockText = "";
    private String dateText = "";

    private final Runnable minuteTick = new Runnable() {
        @Override
        public void run() {
            invalidate();
            postDelayed(this, 60000L - (System.currentTimeMillis() % 60000L) + 50L);
        }
    };

    HomeView(Context context) {
        super(context);
        setFocusable(false);

        dimPaint.setColor(0x66000000);
        sheenPaint.setStyle(Paint.Style.FILL);
        strokePaint.setStyle(Paint.Style.STROKE);
        shadowPaint.setColor(0xFF000000);

        namePaint.setColor(0xFFFFFFFF);
        namePaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        hintPaint.setColor(0xB3FFFFFF);
        hintPaint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        timePaint.setColor(0xFFFFFFFF);
        timePaint.setTextAlign(Paint.Align.RIGHT);
        timePaint.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        datePaint.setColor(0xB3FFFFFF);
        datePaint.setTextAlign(Paint.Align.RIGHT);
        datePaint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        labelPaint.setColor(0xFFFFFFFF);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    }

    void setStore(ImageStore store) {
        this.store = store;
    }

    // ------------------------------------------------------------------ dati

    void setApps(List<AppEntry> list) {
        String keep = (!apps.isEmpty() && sel < apps.size()) ? apps.get(sel).pkg : null;
        int previousIndex = sel;
        apps = list;
        int n = list.size();
        int index = Math.min(previousIndex, Math.max(0, n - 1));
        if (keep != null) {
            for (int i = 0; i < n; i++) {
                if (list.get(i).pkg.equals(keep)) {
                    index = i;
                    break;
                }
            }
        }
        sel = n == 0 ? 0 : index;
        moving = false;
        for (int i = 0; i < n; i++) {
            AppEntry e = list.get(i);
            e.ax = i % COLS;
            e.ay = i / COLS;
        }
        fx = sel % COLS;
        fy = sel / COLS;
        topRow = Math.max(0, sel / COLS - (VISIBLE_ROWS - 1));
        scrollRow = topRow;
        heroCur = n > 0 ? list.get(sel) : null;
        heroPrev = null;
        heroT = 1f;
        invalidate();
    }

    List<AppEntry> getApps() {
        return apps;
    }

    AppEntry selected() {
        return (sel >= 0 && sel < apps.size()) ? apps.get(sel) : null;
    }

    boolean isMoving() {
        return moving;
    }

    void setMoving(boolean value) {
        if (value && apps.isEmpty()) return;
        moving = value;
        invalidate();
    }

    boolean hasWallpaper() {
        return wallCur != null || wallNext != null;
    }

    int wallpaperWidth() {
        return viewW;
    }

    int wallpaperHeight() {
        return viewH;
    }

    void setWallpaper(Bitmap b) {
        if (b == null) return;
        if (wallCur == null) {
            wallCur = b;
            wallNext = null;
        } else {
            if (wallNext != null) wallCur = wallNext;
            wallNext = b;
            wallT = 0f;
        }
        invalidate();
    }

    // ------------------------------------------------------------------ telecomando

    void moveSelection(int keyCode) {
        int n = apps.size();
        if (n == 0) return;
        int target = sel;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (sel % COLS > 0) target = sel - 1;
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (sel % COLS < COLS - 1 && sel + 1 < n) target = sel + 1;
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (sel >= COLS) target = sel - COLS;
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (sel + COLS < n) target = sel + COLS;
                else if (sel / COLS < (n - 1) / COLS) target = n - 1;
                break;
            default:
                return;
        }
        if (target == sel) return;
        if (moving) {
            AppEntry picked = apps.remove(sel);
            apps.add(target, picked);
        }
        sel = target;
        selChangedAt = SystemClock.uptimeMillis();
        int row = sel / COLS;
        if (row < topRow) topRow = row;
        else if (row > topRow + VISIBLE_ROWS - 1) topRow = row - (VISIBLE_ROWS - 1);
        invalidate();
    }

    // ------------------------------------------------------------------ ciclo di vita

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        postDelayed(minuteTick, 60000L - (System.currentTimeMillis() % 60000L) + 50L);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(minuteTick);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        viewW = w;
        viewH = h;
        s = Math.min(w / 1920f, h / 1080f);
        tileW = 264f * s;
        tileH = 148f * s;
        gapX = 24f * s;
        rowPitch = 214f * s;
        radius = tileH * 0.08f;
        float totalW = COLS * tileW + (COLS - 1) * gapX;
        marginL = (w - totalW) / 2f;
        rowsTop = 600f * s;
        cardW = 640f * s;
        cardH = 360f * s;
        cardX = w - marginL - cardW;
        cardY = 150f * s;

        bottomPaint.setShader(new LinearGradient(0, h * 0.5f, 0, h, 0x00000000, 0xB0000000, Shader.TileMode.CLAMP));
        sheenPaint.setShader(new LinearGradient(0, 0, tileW, tileH, 0x55FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        glowPaint.setShader(new RadialGradient(cardX + cardW / 2f, cardY + cardH / 2f, cardW * 1.1f,
                0xFFFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        strokePaint.setStrokeWidth(Math.max(2f, 3.5f * s));

        namePaint.setTextSize(72f * s);
        hintPaint.setTextSize(28f * s);
        timePaint.setTextSize(46f * s);
        datePaint.setTextSize(24f * s);
        labelPaint.setTextSize(26f * s);

        for (AppEntry e : apps) {
            e.heroLabel = null;
            e.tileLabel = null;
        }
        if (store != null) {
            store.configure(Math.round(tileW), Math.round(tileH), Math.round(cardW), Math.round(cardH));
        }
    }

    // ------------------------------------------------------------------ animazione

    /** Avanza tutti i valori animati; restituisce true se qualcosa si sta ancora muovendo. */
    private boolean step(float dt, long now) {
        boolean anim = false;
        int n = apps.size();

        float kTile = 1f - (float) Math.exp(-dt * 16f);
        for (int i = 0; i < n; i++) {
            AppEntry e = apps.get(i);
            float tx = i % COLS;
            float ty = i / COLS;
            if (Math.abs(tx - e.ax) > 0.003f || Math.abs(ty - e.ay) > 0.003f) {
                e.ax += (tx - e.ax) * kTile;
                e.ay += (ty - e.ay) * kTile;
                anim = true;
            } else {
                e.ax = tx;
                e.ay = ty;
            }
        }

        if (n > 0) {
            float kSel = 1f - (float) Math.exp(-dt * 14f);
            float tx = sel % COLS;
            float ty = sel / COLS;
            if (Math.abs(tx - fx) > 0.003f || Math.abs(ty - fy) > 0.003f) {
                fx += (tx - fx) * kSel;
                fy += (ty - fy) * kSel;
                anim = true;
            } else {
                fx = tx;
                fy = ty;
            }
        }

        float kScroll = 1f - (float) Math.exp(-dt * 12f);
        if (Math.abs(topRow - scrollRow) > 0.003f) {
            scrollRow += (topRow - scrollRow) * kScroll;
            anim = true;
        } else {
            scrollRow = topRow;
        }

        // L'anteprima cambia solo dopo una breve sosta, cosi' scorrendo veloce non sfarfalla.
        if (n > 0) {
            AppEntry target = apps.get(sel);
            if (target != heroCur) {
                if (now - selChangedAt >= 280L) {
                    heroPrev = heroCur;
                    heroCur = target;
                    heroT = 0f;
                }
                anim = true;
            }
        }
        if (heroT < 1f) {
            heroT = Math.min(1f, heroT + dt / 0.35f);
            anim = true;
        }

        if (wallNext != null) {
            wallT += dt / 0.9f;
            if (wallT >= 1f) {
                wallCur = wallNext;
                wallNext = null;
                wallT = 0f;
            }
            anim = true;
        }
        return anim;
    }

    @Override
    protected void onDraw(Canvas c) {
        long now = SystemClock.uptimeMillis();
        float dt = lastFrame == 0 ? 0.016f : Math.min(0.05f, (now - lastFrame) / 1000f);
        lastFrame = now;
        boolean anim = step(dt, now);

        drawWallpaper(c);
        drawHero(c);
        drawClock(c);
        drawTiles(c);

        if (anim) {
            postInvalidateOnAnimation();
        } else {
            lastFrame = 0;
        }
    }

    private void drawWallpaper(Canvas c) {
        if (wallCur == null) {
            fill.setShader(null);
            fill.setColor(0xFF10131B);
            c.drawRect(0, 0, viewW, viewH, fill);
        } else {
            bitmapPaint.setAlpha(255);
            c.drawBitmap(wallCur, 0, 0, bitmapPaint);
            if (wallNext != null) {
                float t = wallT * wallT * (3f - 2f * wallT);
                bitmapPaint.setAlpha(Math.round(255 * t));
                c.drawBitmap(wallNext, 0, 0, bitmapPaint);
                bitmapPaint.setAlpha(255);
            }
        }
        c.drawRect(0, 0, viewW, viewH, dimPaint);
        c.drawRect(0, viewH * 0.5f, viewW, viewH, bottomPaint);
    }

    private void drawClock(Canvas c) {
        long minute = System.currentTimeMillis() / 60000L;
        if (minute != clockMinute) {
            clockMinute = minute;
            Date d = new Date();
            clockText = timeFormat.format(d);
            String date = dateFormat.format(d);
            dateText = date.isEmpty() ? date : date.substring(0, 1).toUpperCase(Locale.getDefault()) + date.substring(1);
        }
        c.drawText(clockText, viewW - marginL, 88f * s, timePaint);
        c.drawText(dateText, viewW - marginL, 124f * s, datePaint);
    }

    // ------------------------------------------------------------------ anteprima

    private void drawHero(Canvas c) {
        if (apps.isEmpty()) {
            namePaint.setAlpha(255);
            c.drawText("Nessuna app trovata", marginL, 330f * s, namePaint);
            return;
        }
        if (heroPrev != null && heroT < 1f) drawHeroFor(c, heroPrev, 1f - heroT);
        if (heroCur != null) drawHeroFor(c, heroCur, heroT);
    }

    private void drawHeroFor(Canvas c, AppEntry e, float a) {
        int alpha = Math.max(0, Math.min(255, Math.round(255 * a)));
        if (alpha == 0) return;

        // Alone del colore dell'app dietro la scheda
        glowPaint.setColorFilter(new PorterDuffColorFilter(store.color(e), PorterDuff.Mode.SRC_IN));
        glowPaint.setAlpha(Math.round(120 * a));
        c.drawRect(0, 0, viewW, rowsTop, glowPaint);

        // Ombra e immagine della scheda
        float cardRadius = cardH * 0.045f;
        for (int i = 3; i >= 1; i--) {
            shadowPaint.setAlpha(Math.round(26 * a));
            float grow = i * 7f * s;
            rect.set(cardX - grow, cardY - grow + 10f * s, cardX + cardW + grow, cardY + cardH + grow + 10f * s);
            c.drawRoundRect(rect, cardRadius + grow, cardRadius + grow, shadowPaint);
        }
        Bitmap b = store.hero(e);
        if (b == null) b = store.tile(e);
        rect.set(cardX, cardY, cardX + cardW, cardY + cardH);
        if (b != null) {
            bitmapPaint.setAlpha(alpha);
            c.drawBitmap(b, null, rect, bitmapPaint);
            bitmapPaint.setAlpha(255);
        } else {
            fill.setShader(null);
            fill.setColor(0xFF2A2F3B);
            fill.setAlpha(alpha);
            c.drawRoundRect(rect, cardRadius, cardRadius, fill);
        }

        // Testo a sinistra
        float textW = cardX - marginL - 48f * s;
        if (e.heroLabel == null) {
            e.heroLabel = TextUtils.ellipsize(e.label, namePaint, textW, TextUtils.TruncateAt.END).toString();
        }
        namePaint.setAlpha(alpha);
        c.drawText(e.heroLabel, marginL, 350f * s, namePaint);
        hintPaint.setAlpha(Math.round(alpha * 0.75f));
        String hint = moving ? "Frecce: sposta   ·   OK: conferma" : "OK: apri   ·   Menu: opzioni";
        c.drawText(hint, marginL, 410f * s, hintPaint);
    }

    // ------------------------------------------------------------------ griglia

    private void drawTiles(Canvas c) {
        int n = apps.size();
        if (n == 0) return;
        if (proxBuf.length < n) proxBuf = new float[n];

        int best = -1;
        float bestProx = -1f;
        for (int i = 0; i < n; i++) {
            AppEntry e = apps.get(i);
            float dx = e.ax - fx;
            float dy = e.ay - fy;
            float d = (float) Math.sqrt(dx * dx + dy * dy);
            float prox = Math.max(0f, 1f - d);
            proxBuf[i] = prox;
            if (prox > bestProx) {
                bestProx = prox;
                best = i;
            }
        }
        if (moving) best = sel;

        for (int i = 0; i < n; i++) {
            if (i != best) drawTile(c, apps.get(i), proxBuf[i], false);
        }
        if (best >= 0) drawTile(c, apps.get(best), proxBuf[best], true);
    }

    private void drawTile(Canvas c, AppEntry e, float prox, boolean top) {
        float y = rowsTop + (e.ay - scrollRow) * rowPitch;
        if (y > viewH + 40f * s || y + tileH < rowsTop - rowPitch) return;
        float fade = Math.max(0f, Math.min(1f, 1f - (rowsTop - y) / (0.6f * rowPitch)));
        if (fade <= 0f) return;

        float x = marginL + e.ax * (tileW + gapX);
        boolean lifted = moving && top;
        float scale = 1f + 0.13f * prox + (lifted ? 0.06f : 0f);
        float cx = x + tileW / 2f;
        float cy = y + tileH / 2f - prox * 10f * s;

        c.save();
        c.translate(cx, cy);
        c.scale(scale, scale);
        c.translate(-tileW / 2f, -tileH / 2f);

        if (prox > 0.02f) {
            for (int i = 1; i <= 3; i++) {
                shadowPaint.setAlpha(Math.round(34f * prox * fade));
                float grow = i * 5f * s;
                rect.set(-grow, -grow + i * 7f * s, tileW + grow, tileH + grow + i * 7f * s);
                c.drawRoundRect(rect, radius + grow, radius + grow, shadowPaint);
            }
        }

        Bitmap b = store.tile(e);
        rect.set(0, 0, tileW, tileH);
        if (b != null) {
            bitmapPaint.setAlpha(Math.round(255 * fade));
            c.drawBitmap(b, 0, 0, bitmapPaint);
            bitmapPaint.setAlpha(255);
        } else {
            fill.setShader(null);
            fill.setColor(0xFF20242C);
            fill.setAlpha(Math.round(255 * fade));
            c.drawRoundRect(rect, radius, radius, fill);
        }

        if (prox > 0.02f) {
            sheenPaint.setAlpha(Math.round(255 * prox * fade));
            c.drawRoundRect(rect, radius, radius, sheenPaint);
            strokePaint.setColor(lifted ? ACCENT : 0xFFFFFFFF);
            strokePaint.setAlpha(Math.round((lifted ? 255 : 210) * prox * fade));
            c.drawRoundRect(rect, radius, radius, strokePaint);
        }
        c.restore();

        // Nome sotto il riquadro selezionato
        if (prox > 0.25f) {
            float maxW = tileW * 1.7f;
            if (e.tileLabel == null) {
                e.tileLabel = TextUtils.ellipsize(e.label, labelPaint, maxW, TextUtils.TruncateAt.END).toString();
            }
            labelPaint.setAlpha(Math.round(255 * Math.min(1f, (prox - 0.25f) / 0.75f) * fade));
            c.drawText(e.tileLabel, cx, y + tileH / 2f + tileH * scale / 2f + 36f * s - prox * 10f * s, labelPaint);
        }
    }
}
