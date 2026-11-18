package app.lev.browser;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.Map;

/**
 * Решения по сайтам: блокировать трекеры или разрешить. Хранится по основному домену.
 * У каждого режима BySS свои решения — при удалении режима они стираются вместе с ним.
 */
final class SitePrefs {

    private SitePrefs() { }

    private static SharedPreferences sp;
    private static Context app;
    private static final Map<String, SharedPreferences> bySpace = new HashMap<>();
    private static final Map<String, SharedPreferences> trBySpace = new HashMap<>();

    static void init(Context c) {
        if (sp == null) {
            app = c.getApplicationContext();
            sp = app.getSharedPreferences("lev_sites", Context.MODE_PRIVATE);
        }
    }

    private static String file(String space) {
        return "lev_sites_" + space;
    }

    private static SharedPreferences of(String space) {
        if (space == null) return sp;
        SharedPreferences p = bySpace.get(space);
        if (p == null) {
            p = app.getSharedPreferences(file(space), Context.MODE_PRIVATE);
            bySpace.put(space, p);
        }
        return p;
    }

    static boolean hasDecision(String host) {
        return hasDecision(null, host);
    }

    static boolean hasDecision(String space, String host) {
        return host != null && of(space).contains(AdBlocker.baseDomain(host));
    }

    static boolean isAllowed(String host) {
        return isAllowed(null, host);
    }

    static boolean isAllowed(String space, String host) {
        return host != null && "allow".equals(of(space).getString(AdBlocker.baseDomain(host), null));
    }

    static void set(String host, boolean allowTrackers) {
        set(null, host, allowTrackers);
    }

    static void set(String space, String host, boolean allowTrackers) {
        if (host == null) return;
        of(space).edit().putString(AdBlocker.baseDomain(host), allowTrackers ? "allow" : "block").apply();
    }

    // ------------------------------------------------------------ перевод: «да» / «нет» по сайту

    private static SharedPreferences tr(String space) {
        String name = space == null ? "lev_tr" : "lev_tr_" + space;
        SharedPreferences p = trBySpace.get(name);
        if (p == null) {
            p = app.getSharedPreferences(name, Context.MODE_PRIVATE);
            trBySpace.put(name, p);
        }
        return p;
    }

    /** 1 — всегда переводить, -1 — не предлагать никогда, 0 — ещё не решали. */
    static int translate(String space, String host) {
        if (host == null || app == null) return 0;
        String v = tr(space).getString(AdBlocker.baseDomain(host), null);
        return "yes".equals(v) ? 1 : "no".equals(v) ? -1 : 0;
    }

    static void setTranslate(String space, String host, boolean yes) {
        if (host == null || app == null) return;
        tr(space).edit().putString(AdBlocker.baseDomain(host), yes ? "yes" : "no").apply();
    }

    static int count() {
        return sp.getAll().size();
    }

    static void clear() {
        sp.edit().clear().apply();
        if (app != null) tr(null).edit().clear().apply();
    }

    /** Режим удалён — его решения по сайтам тоже. */
    static void forgetSpace(String space) {
        if (space == null || app == null) return;
        of(space).edit().clear().commit();
        bySpace.remove(space);
        app.deleteSharedPreferences(file(space));
        tr(space).edit().clear().commit();
        trBySpace.remove("lev_tr_" + space);
        app.deleteSharedPreferences("lev_tr_" + space);
    }
}
