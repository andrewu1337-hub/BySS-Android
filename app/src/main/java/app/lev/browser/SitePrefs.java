package app.lev.browser;

import android.content.Context;
import android.content.SharedPreferences;

/** Решения по сайтам: блокировать трекеры или разрешить. Хранится по основному домену. */
final class SitePrefs {

    private SitePrefs() { }

    private static SharedPreferences sp;

    static void init(Context c) {
        if (sp == null) sp = c.getApplicationContext().getSharedPreferences("lev_sites", Context.MODE_PRIVATE);
    }

    static boolean hasDecision(String host) {
        return host != null && sp.contains(AdBlocker.baseDomain(host));
    }

    static boolean isAllowed(String host) {
        return host != null && "allow".equals(sp.getString(AdBlocker.baseDomain(host), null));
    }

    static void set(String host, boolean allowTrackers) {
        if (host == null) return;
        sp.edit().putString(AdBlocker.baseDomain(host), allowTrackers ? "allow" : "block").apply();
    }

    static int count() {
        return sp.getAll().size();
    }

    static void clear() {
        sp.edit().clear().apply();
    }
}
