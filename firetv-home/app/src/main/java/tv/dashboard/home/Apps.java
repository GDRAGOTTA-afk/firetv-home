package tv.dashboard.home;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Legge le app installate e ricorda ordine e app nascoste. */
final class Apps {
    static final String PREFS = "home";
    private static final String KEY_ORDER = "order";
    private static final String KEY_HIDDEN = "hidden";

    private Apps() {
    }

    static List<AppEntry> load(Context c) {
        PackageManager pm = c.getPackageManager();
        SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> hidden = sp.getStringSet(KEY_HIDDEN, new HashSet<String>());

        Map<String, AppEntry> map = new LinkedHashMap<>();
        String[] categories = {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER};
        for (String category : categories) {
            Intent query = new Intent(Intent.ACTION_MAIN).addCategory(category);
            List<ResolveInfo> found = pm.queryIntentActivities(query, 0);
            for (ResolveInfo ri : found) {
                ActivityInfo ai = ri.activityInfo;
                if (ai == null) continue;
                String pkg = ai.packageName;
                if (pkg.equals(c.getPackageName()) || map.containsKey(pkg) || hidden.contains(pkg)) continue;
                Intent launch = new Intent(Intent.ACTION_MAIN)
                        .addCategory(category)
                        .setClassName(pkg, ai.name)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                CharSequence label = ri.loadLabel(pm);
                map.put(pkg, new AppEntry(pkg, label == null ? pkg : label.toString(), launch, ai));
            }
        }

        List<AppEntry> alphabetical = new ArrayList<>(map.values());
        Collections.sort(alphabetical, new Comparator<AppEntry>() {
            @Override
            public int compare(AppEntry a, AppEntry b) {
                return a.label.toLowerCase(Locale.getDefault()).compareTo(b.label.toLowerCase(Locale.getDefault()));
            }
        });

        List<AppEntry> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String saved = sp.getString(KEY_ORDER, "");
        if (!saved.isEmpty()) {
            for (String pkg : saved.split("\n")) {
                AppEntry e = map.get(pkg);
                if (e != null && seen.add(pkg)) result.add(e);
            }
        }
        for (AppEntry e : alphabetical) {
            if (seen.add(e.pkg)) result.add(e);
        }
        return result;
    }

    static void saveOrder(Context c, List<AppEntry> list) {
        StringBuilder sb = new StringBuilder();
        for (AppEntry e : list) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(e.pkg);
        }
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_ORDER, sb.toString()).apply();
    }

    static void hide(Context c, String pkg) {
        SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> hidden = new HashSet<>(sp.getStringSet(KEY_HIDDEN, new HashSet<String>()));
        hidden.add(pkg);
        sp.edit().putStringSet(KEY_HIDDEN, hidden).apply();
    }

    static int hiddenCount(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getStringSet(KEY_HIDDEN, new HashSet<String>()).size();
    }

    static void unhideAll(Context c) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_HIDDEN).apply();
    }
}
