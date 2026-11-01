package tv.dashboard.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Sceglie lo sfondo successivo. Se nella cartella wallpapers dell'app ci sono immagini tue le usa,
 * altrimenti usa quelle incluse nell'app. L'ordine e' casuale senza ripetere lo stesso sfondo di fila.
 */
final class Wallpapers {
    private static final String ASSET_DIR = "wallpapers";

    private final Context ctx;
    private final Random random = new Random();
    private final List<Object> bag = new ArrayList<>();
    private Object last;

    Wallpapers(Context context) {
        this.ctx = context.getApplicationContext();
    }

    /** Cartella dove mettere i propri sfondi (viene creata se manca). */
    File userDir() {
        return ctx.getExternalFilesDir(ASSET_DIR);
    }

    private List<Object> sources() {
        List<Object> all = new ArrayList<>();
        File dir = userDir();
        File[] files = dir == null ? null : dir.listFiles();
        if (files != null) {
            Arrays.sort(files);
            for (File f : files) {
                String n = f.getName().toLowerCase(Locale.ROOT);
                if (f.isFile() && f.length() > 0 && f.length() <= 15L * 1024 * 1024
                        && (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp"))) {
                    all.add(f);
                }
            }
        }
        if (all.isEmpty()) {
            try {
                String[] names = ctx.getAssets().list(ASSET_DIR);
                if (names != null) {
                    Arrays.sort(names);
                    all.addAll(Arrays.asList(names));
                }
            } catch (IOException ignored) {
            }
        }
        return all;
    }

    private Object pickNext(List<Object> all) {
        bag.retainAll(all);
        if (bag.isEmpty()) {
            bag.addAll(all);
            Collections.shuffle(bag, random);
            if (bag.size() > 1 && bag.get(0).equals(last)) {
                Collections.swap(bag, 0, bag.size() - 1);
            }
        }
        Object next = bag.remove(0);
        last = next;
        return next;
    }

    /** Carica lo sfondo successivo gia' ritagliato a w x h. Da chiamare fuori dal thread principale. */
    synchronized Bitmap loadNext(int w, int h) {
        if (w <= 0 || h <= 0) return null;
        List<Object> all = sources();
        // Una immagine illeggibile non deve bloccare la rotazione: provo le successive.
        for (int attempt = 0; attempt < all.size(); attempt++) {
            Object src = pickNext(all);
            try {
                Bitmap b = decode(src, w, h);
                if (b != null) return b;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private InputStream open(Object src) throws IOException {
        if (src instanceof File) return new FileInputStream((File) src);
        return ctx.getAssets().open(ASSET_DIR + "/" + src);
    }

    private Bitmap decode(Object src, int w, int h) throws IOException {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        InputStream in = open(src);
        try {
            BitmapFactory.decodeStream(in, null, o);
        } finally {
            in.close();
        }
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        int sample = 1;
        while (o.outWidth / (sample * 2) >= w && o.outHeight / (sample * 2) >= h) sample *= 2;
        o.inJustDecodeBounds = false;
        o.inSampleSize = sample;
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap raw;
        in = open(src);
        try {
            raw = BitmapFactory.decodeStream(in, null, o);
        } finally {
            in.close();
        }
        if (raw == null) return null;

        // Riempie lo schermo ritagliando al centro, senza deformare.
        float scale = Math.max((float) w / raw.getWidth(), (float) h / raw.getHeight());
        int srcW = Math.min(raw.getWidth(), Math.round(w / scale));
        int srcH = Math.min(raw.getHeight(), Math.round(h / scale));
        int left = (raw.getWidth() - srcW) / 2;
        int top = (raw.getHeight() - srcH) / 2;
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        cv.drawBitmap(raw, new Rect(left, top, left + srcW, top + srcH), new Rect(0, 0, w, h), p);
        raw.recycle();
        return out;
    }
}
