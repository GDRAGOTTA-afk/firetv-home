package tv.dashboard.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.LruCache;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Prepara le immagini dei riquadri e delle anteprime: banner dell'app, oppure l'immagine scaricata
 * da un link (bitly o diretto), oppure l'icona su sfondo scuro. Il lavoro pesante gira in secondo piano
 * e i risultati restano in cache, cosi' il disegno della schermata non rallenta mai.
 */
final class ImageStore {
    interface Listener {
        void onImagesChanged();
    }

    interface Result {
        void done(boolean ok, String message);
    }

    static final String KIND_ICON = "icon";
    static final String KIND_PREVIEW = "preview";
    private static final int MAX_DOWNLOAD_BYTES = 12 * 1024 * 1024;

    private final Context ctx;
    private final PackageManager pm;
    private final SharedPreferences sp;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService render = Executors.newFixedThreadPool(2);
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private final Set<String> pending = new HashSet<>(); // solo thread principale
    private final Map<String, Integer> colors = new ConcurrentHashMap<>();

    private final LruCache<String, Bitmap> tiles = new LruCache<String, Bitmap>(24 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount() / 1024;
        }
    };
    private final LruCache<String, Bitmap> heroes = new LruCache<String, Bitmap>(16 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount() / 1024;
        }
    };

    private int tileW, tileH, heroW, heroH;

    ImageStore(Context context, Listener listener) {
        this.ctx = context.getApplicationContext();
        this.pm = ctx.getPackageManager();
        this.sp = ctx.getSharedPreferences(Apps.PREFS, Context.MODE_PRIVATE);
        this.listener = listener;
    }

    void configure(int tw, int th, int hw, int hh) {
        if (tw == tileW && th == tileH && hw == heroW && hh == heroH) return;
        tileW = tw;
        tileH = th;
        heroW = hw;
        heroH = hh;
        tiles.evictAll();
        heroes.evictAll();
        pending.clear();
    }

    /** Immagine del riquadro, o null finche' non e' pronta (in quel caso la prepara in secondo piano). */
    Bitmap tile(AppEntry e) {
        Bitmap b = tiles.get(e.pkg);
        if (b == null) schedule(e, false);
        return b;
    }

    /** Immagine grande dell'anteprima, o null finche' non e' pronta. */
    Bitmap hero(AppEntry e) {
        Bitmap b = heroes.get(e.pkg);
        if (b == null) schedule(e, true);
        return b;
    }

    /** Colore dominante dell'app, per l'alone dietro l'anteprima. */
    int color(AppEntry e) {
        Integer c = colors.get(e.pkg);
        return c == null ? 0xFF3A4A6B : c;
    }

    String getLink(String pkg, String kind) {
        return sp.getString("url_" + kind + "_" + pkg, null);
    }

    // ------------------------------------------------------------------ disegno

    private void schedule(final AppEntry e, final boolean hero) {
        final int w = hero ? heroW : tileW;
        final int h = hero ? heroH : tileH;
        if (w <= 0 || h <= 0) return;
        final String key = (hero ? "h:" : "t:") + e.pkg;
        if (!pending.add(key)) return;
        render.execute(new Runnable() {
            @Override
            public void run() {
                Bitmap made;
                try {
                    made = render(e, w, h, hero);
                } catch (Throwable t) {
                    made = placeholder(w, h, hero);
                }
                final Bitmap result = made;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        pending.remove(key);
                        if (w == (hero ? heroW : tileW) && h == (hero ? heroH : tileH)) {
                            (hero ? heroes : tiles).put(e.pkg, result);
                        }
                        listener.onImagesChanged();
                    }
                });
            }
        });
    }

    private Bitmap render(AppEntry e, int w, int h, boolean hero) {
        float radius = hero ? h * 0.045f : h * 0.08f;
        Bitmap source = null;
        if (hero) source = decodeCustom(KIND_PREVIEW, e.pkg, w, h);
        if (source == null) source = decodeCustom(KIND_ICON, e.pkg, w, h);
        if (source == null) {
            Drawable banner = e.info.loadBanner(pm);
            if (banner == null && e.info.applicationInfo != null) {
                banner = e.info.applicationInfo.loadBanner(pm);
            }
            if (banner != null) source = toBitmap(banner, w, h);
        }

        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        if (source != null) {
            drawCover(cv, source, w, h, radius);
        } else {
            drawIconTile(cv, e.info.loadIcon(pm), e.label, w, h, radius, hero);
        }
        colors.put(e.pkg, averageColor(out));
        return out;
    }

    private static Bitmap placeholder(int w, int h, boolean hero) {
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFF2A2F3B);
        float r = hero ? h * 0.045f : h * 0.08f;
        cv.drawRoundRect(new RectF(0, 0, w, h), r, r, p);
        return out;
    }

    private static void drawCover(Canvas cv, Bitmap src, int w, int h, float radius) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        BitmapShader shader = new BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        float scale = Math.max((float) w / src.getWidth(), (float) h / src.getHeight());
        Matrix m = new Matrix();
        m.setScale(scale, scale);
        m.postTranslate((w - src.getWidth() * scale) / 2f, (h - src.getHeight() * scale) / 2f);
        shader.setLocalMatrix(m);
        p.setShader(shader);
        cv.drawRoundRect(new RectF(0, 0, w, h), radius, radius, p);
    }

    private static void drawIconTile(Canvas cv, Drawable icon, String label, int w, int h, float radius, boolean hero) {
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setShader(new LinearGradient(0, 0, w, h, 0xFF3B4357, 0xFF171A22, Shader.TileMode.CLAMP));
        cv.drawRoundRect(new RectF(0, 0, w, h), radius, radius, bg);

        float size = h * (hero ? 0.5f : 0.46f);
        float cx = w / 2f;
        float cy = hero ? h / 2f : h * 0.44f;
        if (icon != null) {
            icon.setBounds(Math.round(cx - size / 2f), Math.round(cy - size / 2f),
                    Math.round(cx + size / 2f), Math.round(cy + size / 2f));
            icon.draw(cv);
        }
        if (!hero) {
            TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            tp.setColor(0xFFFFFFFF);
            tp.setTextSize(h * 0.115f);
            tp.setTextAlign(Paint.Align.CENTER);
            tp.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            String text = TextUtils.ellipsize(label, tp, w * 0.9f, TextUtils.TruncateAt.END).toString();
            cv.drawText(text, cx, h * 0.9f, tp);
        }
    }

    private static Bitmap toBitmap(Drawable d, int fallbackW, int fallbackH) {
        if (d instanceof BitmapDrawable && ((BitmapDrawable) d).getBitmap() != null) {
            return ((BitmapDrawable) d).getBitmap();
        }
        int iw = d.getIntrinsicWidth() > 0 ? Math.min(d.getIntrinsicWidth(), 1024) : fallbackW;
        int ih = d.getIntrinsicHeight() > 0 ? Math.min(d.getIntrinsicHeight(), 1024) : fallbackH;
        Bitmap b = Bitmap.createBitmap(iw, ih, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        d.setBounds(0, 0, iw, ih);
        d.draw(cv);
        return b;
    }

    private static int averageColor(Bitmap b) {
        Bitmap small = Bitmap.createScaledBitmap(b, 16, 9, true);
        int[] px = new int[16 * 9];
        small.getPixels(px, 0, 16, 0, 0, 16, 9);
        long r = 0, g = 0, bl = 0;
        for (int c : px) {
            r += Color.red(c);
            g += Color.green(c);
            bl += Color.blue(c);
        }
        int n = px.length;
        int avg = Color.rgb((int) (r / n), (int) (g / n), (int) (bl / n));
        if (small != b) small.recycle();
        float[] hsv = new float[3];
        Color.colorToHSV(avg, hsv);
        hsv[1] = Math.min(1f, hsv[1] * 1.15f);
        hsv[2] = Math.max(hsv[2], 0.55f);
        return Color.HSVToColor(255, hsv);
    }

    // ------------------------------------------------------------------ immagini personalizzate

    private File imageFile(String kind, String pkg) {
        return new File(ctx.getFilesDir(), kind + "_" + pkg.replaceAll("[^A-Za-z0-9._-]", "_"));
    }

    private Bitmap decodeCustom(String kind, String pkg, int w, int h) {
        File f = imageFile(kind, pkg);
        if (!f.isFile()) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        int sample = 1;
        while (o.outWidth / (sample * 2) >= w && o.outHeight / (sample * 2) >= h) sample *= 2;
        o.inJustDecodeBounds = false;
        o.inSampleSize = sample;
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeFile(f.getAbsolutePath(), o);
    }

    /** Scarica l'immagine del link e la associa all'app. Il risultato arriva sul thread principale. */
    void setLink(final String pkg, final String kind, final String rawUrl, final Result cb) {
        net.execute(new Runnable() {
            @Override
            public void run() {
                String message;
                boolean ok;
                try {
                    fetch(pkg, kind, rawUrl);
                    ok = true;
                    message = "Immagine aggiornata";
                } catch (Exception ex) {
                    ok = false;
                    message = "Non riesco a usare il link: " + ex.getMessage();
                }
                final boolean fok = ok;
                final String fmsg = message;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        cb.done(fok, fmsg);
                    }
                });
            }
        });
    }

    void clearLinks(String pkg) {
        imageFile(KIND_ICON, pkg).delete();
        imageFile(KIND_PREVIEW, pkg).delete();
        sp.edit().remove("url_" + KIND_ICON + "_" + pkg).remove("url_" + KIND_PREVIEW + "_" + pkg).apply();
        invalidate(pkg);
    }

    /**
     * Legge links.txt dalla cartella dell'app (una riga per immagine: pacchetto|icon|link oppure
     * pacchetto|preview|link) e scarica quelle nuove. Si rilegge solo se il file e' cambiato.
     */
    void importLinksFile(final Result cb) {
        net.execute(new Runnable() {
            @Override
            public void run() {
                File dir = ctx.getExternalFilesDir(null);
                if (dir == null) return;
                File f = new File(dir, "links.txt");
                if (!f.isFile()) return;
                long mod = f.lastModified();
                if (mod == sp.getLong("links_mod", 0L)) return;
                int ok = 0, fail = 0;
                BufferedReader br = null;
                try {
                    br = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
                    String line;
                    while ((line = br.readLine()) != null) {
                        line = line.trim();
                        if (line.isEmpty() || line.startsWith("#")) continue;
                        String[] p = line.split("\\|", 3);
                        if (p.length < 3) {
                            fail++;
                            continue;
                        }
                        String kind = p[1].trim().toLowerCase(Locale.ROOT);
                        if (!KIND_ICON.equals(kind) && !KIND_PREVIEW.equals(kind)) {
                            fail++;
                            continue;
                        }
                        try {
                            fetch(p[0].trim(), kind, p[2].trim());
                            ok++;
                        } catch (Exception ex) {
                            fail++;
                        }
                    }
                } catch (IOException ex) {
                    fail++;
                } finally {
                    if (br != null) {
                        try {
                            br.close();
                        } catch (IOException ignored) {
                        }
                    }
                }
                sp.edit().putLong("links_mod", mod).apply();
                final boolean allOk = fail == 0;
                final String msg = "links.txt: " + ok + " immagini caricate" + (fail > 0 ? ", " + fail + " non riuscite" : "");
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        cb.done(allOk, msg);
                    }
                });
            }
        });
    }

    private void fetch(String pkg, String kind, String rawUrl) throws IOException {
        File dest = imageFile(kind, pkg);
        File tmp = new File(dest.getParentFile(), dest.getName() + ".tmp");
        try {
            download(rawUrl, tmp);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(tmp.getAbsolutePath(), o);
            if (o.outWidth <= 0 || o.outHeight <= 0) {
                throw new IOException("il link non porta a un'immagine (usa un file JPG o PNG)");
            }
            if (dest.exists() && !dest.delete()) throw new IOException("impossibile sostituire l'immagine");
            if (!tmp.renameTo(dest)) throw new IOException("impossibile salvare l'immagine");
        } finally {
            if (tmp.exists()) tmp.delete();
        }
        sp.edit().putString("url_" + kind + "_" + pkg, rawUrl).apply();
        invalidate(pkg);
    }

    private static void download(String rawUrl, File dest) throws IOException {
        String url = rawUrl.trim();
        if (!url.toLowerCase(Locale.ROOT).startsWith("http")) url = "https://" + url;
        for (int hop = 0; hop < 6; hop++) {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(20000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 9) HomeTV");
            try {
                int code = conn.getResponseCode();
                if (code >= 300 && code < 400) {
                    String location = conn.getHeaderField("Location");
                    if (location == null) throw new IOException("reindirizzamento senza destinazione");
                    url = new URL(new URL(url), location).toString();
                    continue;
                }
                if (code != 200) throw new IOException("risposta HTTP " + code);
                InputStream in = conn.getInputStream();
                OutputStream out = new FileOutputStream(dest);
                try {
                    byte[] buf = new byte[16 * 1024];
                    int total = 0;
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        total += n;
                        if (total > MAX_DOWNLOAD_BYTES) throw new IOException("immagine troppo grande (oltre 12 MB)");
                        out.write(buf, 0, n);
                    }
                } finally {
                    out.close();
                    in.close();
                }
                return;
            } finally {
                conn.disconnect();
            }
        }
        throw new IOException("troppi reindirizzamenti");
    }

    private void invalidate(final String pkg) {
        main.post(new Runnable() {
            @Override
            public void run() {
                tiles.remove(pkg);
                heroes.remove(pkg);
                listener.onImagesChanged();
            }
        });
    }
}
