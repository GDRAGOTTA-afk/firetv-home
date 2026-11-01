package tv.dashboard.home;

import android.content.Intent;
import android.content.pm.ActivityInfo;

/** Un'app installata che compare nella griglia. */
final class AppEntry {
    final String pkg;
    final String label;
    final Intent intent;
    final ActivityInfo info;

    /** Posizione animata sulla griglia (colonna, riga), in unita' di riquadro. */
    float ax, ay;

    /** Etichette gia' accorciate per la larghezza disponibile (si azzerano al cambio di dimensione). */
    String heroLabel;
    String tileLabel;

    AppEntry(String pkg, String label, Intent intent, ActivityInfo info) {
        this.pkg = pkg;
        this.label = label;
        this.intent = intent;
        this.info = info;
    }
}
