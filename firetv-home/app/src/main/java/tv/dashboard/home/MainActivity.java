package tv.dashboard.home;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements ImageStore.Listener {
    /** Ogni quanto cambia lo sfondo. */
    private static final long ROTATE_MS = 60_000L;

    private HomeView home;
    private ImageStore store;
    private Wallpapers wallpapers;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService appsWorker = Executors.newSingleThreadExecutor();
    private final ExecutorService wallWorker = Executors.newSingleThreadExecutor();
    private boolean resumed;
    private boolean wallLoading;
    private boolean backConsumed;
    private long lastRotate;

    private final Runnable rotate = new Runnable() {
        @Override
        public void run() {
            handler.removeCallbacks(this);
            if (!resumed) return;
            loadWallpaper();
            handler.postDelayed(this, ROTATE_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        home = new HomeView(this);
        store = new ImageStore(this, this);
        wallpapers = new Wallpapers(this);
        home.setStore(store);
        setContentView(home);
        hideSystemUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        hideSystemUi();
        reloadApps();

        store.importLinksFile(new ImageStore.Result() {
            @Override
            public void done(boolean ok, String message) {
                toast(message);
            }
        });

        // Se manca lo sfondo lo carico subito; altrimenti rispetto la pausa di un minuto.
        handler.removeCallbacks(rotate);
        long wait = home.hasWallpaper() ? Math.max(0L, lastRotate + ROTATE_MS - SystemClock.uptimeMillis()) : 0L;
        handler.postDelayed(rotate, wait);
    }

    @Override
    protected void onPause() {
        resumed = false;
        handler.removeCallbacks(rotate);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        appsWorker.shutdown();
        wallWorker.shutdown();
        super.onDestroy();
    }

    @Override
    public void onImagesChanged() {
        home.postInvalidateOnAnimation();
    }

    private void hideSystemUi() {
        home.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    // ------------------------------------------------------------------ dati

    private void reloadApps() {
        appsWorker.execute(new Runnable() {
            @Override
            public void run() {
                final List<AppEntry> list = Apps.load(MainActivity.this);
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!isDestroyed()) home.setApps(list);
                    }
                });
            }
        });
    }

    private void loadWallpaper() {
        final int w = home.wallpaperWidth();
        final int h = home.wallpaperHeight();
        if (w <= 0 || h <= 0) {
            handler.postDelayed(rotate, 400L);
            return;
        }
        if (wallLoading) return;
        wallLoading = true;
        lastRotate = SystemClock.uptimeMillis();
        wallWorker.execute(new Runnable() {
            @Override
            public void run() {
                Bitmap b = null;
                try {
                    b = wallpapers.loadNext(w, h);
                } catch (Throwable ignored) {
                }
                final Bitmap result = b;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        wallLoading = false;
                        if (result != null && !isDestroyed()) home.setWallpaper(result);
                    }
                });
            }
        });
    }

    // ------------------------------------------------------------------ telecomando

    private static boolean isSelectKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                home.moveSelection(keyCode);
                return true;
            case KeyEvent.KEYCODE_MENU:
                if (event.getRepeatCount() == 0) openMenu();
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (home.isMoving()) {
                    backConsumed = true;
                    stopMoving();
                    return true;
                }
                break;
            default:
                if (isSelectKey(keyCode)) {
                    if (event.getRepeatCount() == 0) event.startTracking();
                    return true;
                }
                break;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyLongPress(int keyCode, KeyEvent event) {
        if (isSelectKey(keyCode)) {
            openMenu();
            return true;
        }
        return super.onKeyLongPress(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (isSelectKey(keyCode)) {
            if (event.isTracking() && !event.isCanceled()) {
                if (home.isMoving()) stopMoving();
                else openSelected();
            }
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_BACK && backConsumed) {
            backConsumed = false;
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    private void openSelected() {
        AppEntry e = home.selected();
        if (e == null) return;
        try {
            startActivity(new android.content.Intent(e.intent));
        } catch (Exception ex) {
            toast("Non riesco ad aprire " + e.label);
        }
    }

    private void stopMoving() {
        home.setMoving(false);
        Apps.saveOrder(this, home.getApps());
    }

    // ------------------------------------------------------------------ menu

    private void openMenu() {
        if (home.isMoving()) {
            stopMoving();
            return;
        }
        final AppEntry e = home.selected();
        if (e == null) return;

        final List<String> items = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();

        items.add("Sposta");
        actions.add(new Runnable() {
            @Override
            public void run() {
                home.setMoving(true);
            }
        });
        items.add("Cambia icona (link bitly)");
        actions.add(new Runnable() {
            @Override
            public void run() {
                askLink(e, ImageStore.KIND_ICON, "Icona di " + e.label);
            }
        });
        items.add("Cambia anteprima (link bitly)");
        actions.add(new Runnable() {
            @Override
            public void run() {
                askLink(e, ImageStore.KIND_PREVIEW, "Anteprima di " + e.label);
            }
        });
        items.add("Ripristina le immagini originali");
        actions.add(new Runnable() {
            @Override
            public void run() {
                store.clearLinks(e.pkg);
                toast("Immagini originali ripristinate");
            }
        });
        items.add("Nascondi questa app");
        actions.add(new Runnable() {
            @Override
            public void run() {
                Apps.hide(MainActivity.this, e.pkg);
                reloadApps();
            }
        });
        if (Apps.hiddenCount(this) > 0) {
            items.add("Mostra di nuovo le app nascoste");
            actions.add(new Runnable() {
                @Override
                public void run() {
                    Apps.unhideAll(MainActivity.this);
                    reloadApps();
                }
            });
        }

        new AlertDialog.Builder(this)
                .setTitle(e.label)
                .setItems(items.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        actions.get(which).run();
                    }
                })
                .show();
    }

    private void askLink(final AppEntry e, final String kind, String title) {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("https://bit.ly/...");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        String current = store.getLink(e.pkg, kind);
        if (current != null) input.setText(current);

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage("Incolla il link bitly che porta a un'immagine JPG o PNG.")
                .setView(input)
                .setPositiveButton("Salva", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String url = input.getText().toString().trim();
                        if (url.isEmpty()) return;
                        toast("Scarico l'immagine...");
                        store.setLink(e.pkg, kind, url, new ImageStore.Result() {
                            @Override
                            public void done(boolean ok, String message) {
                                toast(message);
                            }
                        });
                    }
                })
                .setNegativeButton("Annulla", null)
                .show();
    }
}
