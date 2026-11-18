package app.lev.browser;

import android.webkit.CookieManager;
import android.webkit.WebStorage;
import android.webkit.WebView;

import androidx.webkit.Profile;
import androidx.webkit.ProfileStore;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import java.util.Locale;

/**
 * Отдельное хранилище для YouTube: cookie, вход в аккаунт и настройки YouTube живут
 * в своём профиле и не стираются кнопкой «Удалить все данные».
 */
final class Profiles {

    private Profiles() { }

    static final String YT = "youtube";

    static boolean supported() {
        try {
            return WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE);
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean isYouTube(String host) {
        if (host == null) return false;
        String h = host.toLowerCase(Locale.ROOT);
        return h.equals("youtube.com") || h.endsWith(".youtube.com") || h.equals("youtu.be")
                || h.endsWith(".youtube-nocookie.com");
    }

    /** Какие сайты остаются внутри профиля YouTube (вход через аккаунт Google). */
    static boolean belongsToYouTube(String host) {
        if (isYouTube(host)) return true;
        if (host == null) return false;
        String h = host.toLowerCase(Locale.ROOT);
        return h.equals("accounts.google.com") || h.equals("consent.google.com") || h.equals("myaccount.google.com")
                || h.startsWith("accounts.google.") || h.equals("consent.youtube.com") || h.endsWith(".gstatic.com")
                || h.endsWith(".googleusercontent.com");
    }

    /** Подключить WebView к профилю YouTube — вызывать сразу после создания WebView. */
    static void attach(WebView w, boolean yt) {
        if (!yt || !supported()) return;
        ProfileStore.getInstance().getOrCreateProfile(YT);
        WebViewCompat.setProfile(w, YT);
    }

    static CookieManager cookies(boolean yt) {
        if (yt && supported()) {
            Profile p = ProfileStore.getInstance().getOrCreateProfile(YT);
            return p.getCookieManager();
        }
        return CookieManager.getInstance();
    }

    /** Стереть всё, кроме YouTube. */
    static void wipeExceptYouTube() {
        CookieManager cm = CookieManager.getInstance();
        if (supported()) {
            cm.removeAllCookies(null);
            cm.flush();
            WebStorage.getInstance().deleteAllData();
            return;
        }
        // Старый WebView без профилей: сохраняем cookie YouTube/Google и возвращаем их обратно.
        String[][] keep = {
                {"https://www.youtube.com", ".youtube.com"},
                {"https://m.youtube.com", ".youtube.com"},
                {"https://accounts.google.com", ".google.com"},
                {"https://www.google.com", ".google.com"},
        };
        String[] saved = new String[keep.length];
        for (int i = 0; i < keep.length; i++) saved[i] = cm.getCookie(keep[i][0]);
        cm.removeAllCookies(ok -> {
            for (int i = 0; i < keep.length; i++) {
                if (saved[i] == null) continue;
                for (String pair : saved[i].split(";")) {
                    String p = pair.trim();
                    if (p.isEmpty() || !p.contains("=")) continue;
                    String name = p.substring(0, p.indexOf('='));
                    String attrs = name.startsWith("__Host-") ? "; Path=/; Secure"
                            : "; Domain=" + keep[i][1] + "; Path=/; Secure";
                    cm.setCookie(keep[i][0], p + attrs + "; Max-Age=31536000; SameSite=Lax");
                }
            }
            cm.flush();
        });
        WebStorage ws = WebStorage.getInstance();
        ws.getOrigins(origins -> {
            if (origins == null) return;
            for (Object key : origins.keySet()) {
                String origin = String.valueOf(key);
                String host = android.net.Uri.parse(origin).getHost();
                if (!belongsToYouTube(host)) ws.deleteOrigin(origin);
            }
        });
    }
}
