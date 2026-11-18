package app.lev.browser;

import android.content.Context;
import android.net.Uri;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Блокировщик рекламы и трекеров по спискам EasyList / EasyPrivacy / RuAdList и др.
 * Понимает самые частые правила: ||домен^, ||домен/путь, исключения @@ и
 * скрытие элементов для конкретных сайтов (домен##селектор).
 */
final class AdBlocker {

    /** Списки: имя файла в assets/filters и адрес для еженедельного обновления. */
    static final String[][] LISTS = {
            {"easylist_ru.txt", "https://easylist-downloads.adblockplus.org/ruadlist+easylist.txt"},
            {"easyprivacy.txt", "https://easylist.to/easylist/easyprivacy.txt"},
            {"czech.txt", "https://raw.githubusercontent.com/tomasko126/easylistczechandslovak/master/filters.txt"},
            {"notifications.txt", "https://easylist-downloads.adblockplus.org/fanboy-notifications.txt"},
            {"peterlowe.txt", "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=adblockplus&showintro=0&mimetype=plaintext"},
    };

    /** Самое главное — работает сразу, пока большие списки ещё загружаются. */
    private static final String[] CORE = {
            "doubleclick.net", "googlesyndication.com", "googleadservices.com", "google-analytics.com",
            "googletagmanager.com", "googletagservices.com", "adservice.google.com", "analytics.google.com",
            "app-measurement.com", "scorecardresearch.com", "adnxs.com", "criteo.com", "criteo.net",
            "taboola.com", "outbrain.com", "mc.yandex.ru", "an.yandex.ru", "yandexadexchange.net", "adfox.ru",
            "hotjar.com", "mixpanel.com", "amplitude.com", "quantserve.com", "moatads.com", "amazon-adsystem.com",
            "adsrvr.org", "rubiconproject.com", "pubmatic.com", "openx.net", "casalemedia.com", "smartadserver.com",
            "bidswitch.net", "3lift.com", "sharethrough.com", "teads.tv", "bat.bing.com", "clarity.ms",
            "analytics.tiktok.com", "ads-twitter.com", "adform.net", "onesignal.com", "pushwoosh.com",
            "pushengage.com", "pushnami.com", "webpushr.com", "izooto.com", "push.express", "sendpulse.com",
    };

    /** Общие правила скрытия рекламы (на всех сайтах). */
    static final String GENERIC_CSS =
            ".adsbygoogle,ins.adsbygoogle,[id^=\"google_ads_\"],[id^=\"div-gpt-ad\"],"
            + "iframe[src*=\"doubleclick.net\"],iframe[src*=\"googlesyndication\"],"
            + ".ad-banner,.ad_banner,.banner-ad,.advertisement,#onesignal-slidedown-container,"
            + "#onesignal-bell-container,.onesignal-customlink-container"
            + "{display:none!important;}";

    private static final Set<String> TYPE_OPTIONS = new HashSet<>(Arrays.asList(
            "script", "image", "stylesheet", "object", "xmlhttprequest", "xhr", "subdocument", "frame",
            "ping", "media", "font", "websocket", "other", "important", "all", "match-case", "css",
            "object-subrequest", "beacon"));

    static final class Rules {
        final Set<String> block = new HashSet<>();
        final Set<String> block3p = new HashSet<>();
        final Set<String> allow = new HashSet<>();
        final Set<String> pageAllow = new HashSet<>();
        final Map<String, List<String>> blockPaths = new HashMap<>();
        final Map<String, List<String>> allowPaths = new HashMap<>();
        final Map<String, StringBuilder> css = new HashMap<>();
        int count;
    }

    private static volatile Rules rules;
    private static final Set<String> core = new HashSet<>(Arrays.asList(CORE));
    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    private static boolean started;

    private AdBlocker() { }

    static int ruleCount() {
        Rules r = rules;
        return r == null ? 0 : r.count;
    }

    /** Загрузить списки в фоне; раз в неделю — обновить их из интернета. */
    static synchronized void start(Context ctx) {
        if (started) return;
        started = true;
        Context app = ctx.getApplicationContext();
        worker.execute(() -> {
            rules = loadAll(app);
            if (System.currentTimeMillis() - Prefs.filtersUpdated() > 7L * 24 * 3600 * 1000) {
                if (update(app)) rules = loadAll(app);
            }
        });
    }

    /** Обновить списки сейчас (кнопка в настройках). */
    static void updateNow(Context ctx, Runnable done) {
        Context app = ctx.getApplicationContext();
        worker.execute(() -> {
            if (update(app)) rules = loadAll(app);
            if (done != null) done.run();
        });
    }

    private static boolean update(Context app) {
        File dir = new File(app.getFilesDir(), "filters");
        if (!dir.exists() && !dir.mkdirs()) return false;
        int ok = 0;
        for (String[] l : LISTS) {
            File tmp = new File(dir, l[0] + ".tmp");
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(l[1]).openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                if (c.getResponseCode() != 200) continue;
                try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                if (tmp.length() > 1000 && tmp.renameTo(new File(dir, l[0]))) ok++;
            } catch (Exception ignored) {
                // нет сети — останутся старые списки
            } finally {
                if (tmp.exists()) //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
            }
        }
        if (ok > 0) Prefs.put("filters_updated", System.currentTimeMillis());
        return ok > 0;
    }

    private static Rules loadAll(Context app) {
        Rules r = new Rules();
        File dir = new File(app.getFilesDir(), "filters");
        for (String[] l : LISTS) {
            File f = new File(dir, l[0]);
            try (InputStream in = f.exists() ? new java.io.FileInputStream(f)
                    : app.getAssets().open("filters/" + l[0])) {
                BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                String line;
                while ((line = br.readLine()) != null) parseLine(r, line);
            } catch (Exception ignored) {
                // списка нет — пропускаем
            }
        }
        return r;
    }

    // ------------------------------------------------------------------ разбор

    static void parseLine(Rules r, String raw) {
        String line = raw.trim();
        if (line.isEmpty() || line.charAt(0) == '!' || line.charAt(0) == '[') return;

        // Скрытие элементов: домен1,домен2##селектор
        int hide = line.indexOf("##");
        if (hide >= 0 || line.contains("#@#") || line.contains("#?#") || line.contains("#$#")
                || line.contains("#%#")) {
            if (hide <= 0) return; // общие правила не берём — они тяжёлые
            if (line.contains("#@#") || line.contains("#?#") || line.contains("#$#")) return;
            String selector = line.substring(hide + 2).trim();
            if (selector.isEmpty() || selector.startsWith("+js") || selector.contains(":-abp")
                    || selector.contains(":has-text") || selector.contains(":style")
                    || selector.contains(":remove") || selector.contains(":xpath")
                    || selector.contains(":upward") || selector.contains(":matches")) return;
            for (String d : line.substring(0, hide).split(",")) {
                d = d.trim().toLowerCase(Locale.ROOT);
                if (d.isEmpty() || d.startsWith("~") || d.contains("*") || !d.contains(".")) continue;
                StringBuilder sb = r.css.get(d);
                if (sb == null) {
                    sb = new StringBuilder();
                    r.css.put(d, sb);
                }
                if (sb.length() > 0) sb.append(',');
                sb.append(selector);
            }
            r.count++;
            return;
        }

        boolean exception = line.startsWith("@@");
        if (exception) line = line.substring(2);
        if (line.startsWith("/") && line.endsWith("/")) return; // регулярные выражения не поддерживаем

        boolean thirdParty = false;
        boolean document = false;
        int dollar = line.lastIndexOf('$');
        if (dollar >= 0) {
            String opts = line.substring(dollar + 1);
            line = line.substring(0, dollar);
            for (String o : opts.split(",")) {
                o = o.trim().toLowerCase(Locale.ROOT);
                if (o.isEmpty()) continue;
                if (o.equals("third-party") || o.equals("3p")) {
                    thirdParty = true;
                } else if (o.equals("document") && exception) {
                    document = true;
                } else if ((o.equals("elemhide") || o.equals("generichide") || o.equals("genericblock"))
                        && exception) {
                    return;
                } else if (!TYPE_OPTIONS.contains(o)) {
                    return; // domain=, popup, csp, redirect и прочее — пропускаем
                }
            }
        }
        if (!line.startsWith("||")) return;
        String rest = line.substring(2);
        int end = 0;
        while (end < rest.length() && "^/*:?|$".indexOf(rest.charAt(end)) < 0) end++;
        String host = rest.substring(0, end).toLowerCase(Locale.ROOT);
        if (!validHost(host)) return;
        String tail = rest.substring(end);

        if (tail.isEmpty() || tail.equals("^") || tail.equals("^|") || tail.equals("*")) {
            if (exception) {
                (document ? r.pageAllow : r.allow).add(host);
            } else {
                (thirdParty ? r.block3p : r.block).add(host);
            }
            r.count++;
            return;
        }
        if (thirdParty) return;
        String path = tail.startsWith("^") ? tail.substring(1) : tail;
        if (!path.startsWith("/")) return;
        int cut = path.length();
        for (int i = 0; i < path.length(); i++) {
            char ch = path.charAt(i);
            if (ch == '*' || ch == '^' || ch == '|') {
                cut = i;
                break;
            }
        }
        path = path.substring(0, cut);
        if (path.length() < 2) return;
        Map<String, List<String>> map = exception ? r.allowPaths : r.blockPaths;
        List<String> list = map.get(host);
        if (list == null) {
            list = new ArrayList<>(2);
            map.put(host, list);
        }
        list.add(path);
        r.count++;
    }

    private static boolean validHost(String h) {
        if (h.length() < 4 || h.startsWith(".") || h.endsWith(".") || !h.contains(".")) return false;
        for (int i = 0; i < h.length(); i++) {
            char c = h.charAt(i);
            if (!(c >= 'a' && c <= 'z') && !(c >= '0' && c <= '9') && c != '.' && c != '-') return false;
        }
        return true;
    }

    // --------------------------------------------------------------- проверка

    private static boolean hostIn(Set<String> set, String host) {
        String h = host;
        while (h != null) {
            if (set.contains(h)) return true;
            int dot = h.indexOf('.');
            if (dot < 0 || h.indexOf('.', dot + 1) < 0) return false; // дошли до домена 1-го уровня
            h = h.substring(dot + 1);
        }
        return false;
    }

    private static boolean pathIn(Map<String, List<String>> map, String host, String path) {
        if (map.isEmpty()) return false;
        String h = host;
        while (h != null) {
            List<String> l = map.get(h);
            if (l != null) {
                for (String p : l) if (path.startsWith(p)) return true;
            }
            int dot = h.indexOf('.');
            if (dot < 0 || h.indexOf('.', dot + 1) < 0) return false;
            h = h.substring(dot + 1);
        }
        return false;
    }

    /** Нужно ли заблокировать запрос url со страницы pageHost. */
    static boolean shouldBlock(Uri url, String pageHost) {
        String host = url.getHost();
        if (host == null) return false;
        host = host.toLowerCase(Locale.ROOT);
        Rules r = rules;
        boolean third = pageHost == null || !baseDomain(host).equals(baseDomain(pageHost));
        if (r == null) return third && hostIn(core, host);
        if (pageHost != null && hostIn(r.pageAllow, pageHost)) return false;
        String path = url.getEncodedPath() == null ? "/" : url.getEncodedPath();
        if (url.getEncodedQuery() != null) path = path + "?" + url.getEncodedQuery();
        if (hostIn(r.allow, host) || pathIn(r.allowPaths, host, path)) return false;
        if (hostIn(r.block, host)) return true;
        if (third && (hostIn(r.block3p, host) || hostIn(core, host))) return true;
        return pathIn(r.blockPaths, host, path);
    }

    /** CSS для скрытия рекламы на этом сайте (общие правила + правила сайта). */
    static String cssFor(String pageHost) {
        StringBuilder out = new StringBuilder(GENERIC_CSS);
        Rules r = rules;
        if (r == null || pageHost == null) return out.toString();
        String h = pageHost.toLowerCase(Locale.ROOT);
        while (h != null) {
            StringBuilder sb = r.css.get(h);
            if (sb != null && sb.length() > 0) {
                out.append(sb).append("{display:none!important;}");
            }
            int dot = h.indexOf('.');
            if (dot < 0 || h.indexOf('.', dot + 1) < 0) break;
            h = h.substring(dot + 1);
        }
        return out.toString();
    }

    private static final Set<String> SECOND_LEVEL = new HashSet<>(Arrays.asList(
            "co", "com", "net", "org", "gov", "edu", "ac", "or", "ne", "go", "msk", "spb"));

    /** example.co.uk → example.co.uk, a.b.example.com → example.com */
    static String baseDomain(String host) {
        if (host == null) return "";
        String h = host.toLowerCase(Locale.ROOT);
        String[] p = h.split("\\.");
        if (p.length <= 2) return h;
        String sld = p[p.length - 2];
        int take = (p[p.length - 1].length() == 2 && SECOND_LEVEL.contains(sld)) ? 3 : 2;
        StringBuilder sb = new StringBuilder();
        for (int i = p.length - take; i < p.length; i++) {
            if (sb.length() > 0) sb.append('.');
            sb.append(p[i]);
        }
        return sb.toString();
    }
}
