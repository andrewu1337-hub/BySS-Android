package app.lev.browser;

import android.content.Context;
import android.content.SharedPreferences;

/** Все настройки браузера. */
final class Prefs {

    private Prefs() { }

    private static SharedPreferences sp;

    static void init(Context c) {
        if (sp == null) sp = c.getApplicationContext().getSharedPreferences("lev", Context.MODE_PRIVATE);
    }

    static SharedPreferences sp() {
        return sp;
    }

    private static boolean b(String k, boolean d) { return sp.getBoolean(k, d); }
    private static int i(String k, int d) { return sp.getInt(k, d); }
    private static String s(String k, String d) { return sp.getString(k, d); }

    static void put(String k, boolean v) { sp.edit().putBoolean(k, v).apply(); }
    static void put(String k, int v) { sp.edit().putInt(k, v).apply(); }
    static void put(String k, long v) { sp.edit().putLong(k, v).apply(); }
    static void put(String k, String v) { sp.edit().putString(k, v).apply(); }

    // Внешний вид
    static boolean dark() { return b("dark", true); }
    static int wallpaper() { return i("wallpaper", 0); }
    static int appIcon() { return i("app_icon", 0); }

    // Поиск
    static String engine() { return s("engine", "google"); }
    static String home1() { return s("home1", "google"); }
    static String home2() { return s("home2", "yandex"); }
    static String customEngine() { return s("custom_engine", ""); }

    // Перевод: device = на телефоне, google / yandex = через их сайт
    static String translateMode() { return s("tr_mode", "device"); }
    /** Только русский или английский. */
    static String translateTarget() { return "en".equals(s("tr_target", "ru")) ? "en" : "ru"; }
    /** Предлагать перевод сайтов не на русском и не на английском. */
    static boolean offerTranslate() { return b("tr_offer", true); }

    // Защита
    static boolean adblock() { return b("adblock", true); }
    static boolean askSites() { return b("ask_sites", true); }
    static boolean thirdPartyCookies() { return b("cookies_3p", false); }
    static long filtersUpdated() { return sp.getLong("filters_updated", 0L); }

    // Вкладки
    static int homeAfterMin() { return i("home_after_min", 15); }
    static int tabExpireDays() { return i("tab_expire_days", 7); }
    static long lastBackground() { return sp.getLong("last_background", 0L); }

    // Видео
    static boolean bgPlay() { return b("bg_play", true); }
    static boolean ytAds() { return b("yt_ads", true); }

    // Отклик
    static boolean haptics() { return b("haptics", true); }
    static boolean sound() { return b("sound", false); }
}
