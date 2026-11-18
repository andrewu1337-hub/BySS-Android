package app.lev.browser;

import android.net.Uri;
import android.util.Patterns;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Поисковые системы и превращение введённого текста в адрес. */
final class SearchEngines {

    private SearchEngines() { }

    static final class Engine {
        final String id;
        final String name;
        final String glyph;
        final String template;
        final String home;

        Engine(String id, String name, String glyph, String template, String home) {
            this.id = id;
            this.name = name;
            this.glyph = glyph;
            this.template = template;
            this.home = home;
        }
    }

    static final List<Engine> ALL = Arrays.asList(
            new Engine("google", "Google", "G", "https://www.google.com/search?q=%s", "https://www.google.com"),
            new Engine("yandex", "Яндекс", "Я", "https://ya.ru/search/?text=%s", "https://ya.ru"),
            new Engine("ddg", "DuckDuckGo", "D", "https://duckduckgo.com/?q=%s", "https://duckduckgo.com"),
            new Engine("bing", "Bing", "B", "https://www.bing.com/search?q=%s", "https://www.bing.com"),
            new Engine("brave", "Brave Search", "Br", "https://search.brave.com/search?q=%s", "https://search.brave.com"),
            new Engine("startpage", "Startpage", "Sp", "https://www.startpage.com/do/search?q=%s", "https://www.startpage.com"),
            new Engine("ecosia", "Ecosia", "E", "https://www.ecosia.org/search?q=%s", "https://www.ecosia.org"),
            new Engine("qwant", "Qwant", "Q", "https://www.qwant.com/?q=%s", "https://www.qwant.com"),
            new Engine("seznam", "Seznam", "Sz", "https://search.seznam.cz/?q=%s", "https://www.seznam.cz"),
            new Engine("mojeek", "Mojeek", "Mj", "https://www.mojeek.com/search?q=%s", "https://www.mojeek.com"),
            new Engine("yahoo", "Yahoo", "Y", "https://search.yahoo.com/search?p=%s", "https://search.yahoo.com"),
            new Engine("youtube", "YouTube", "▶", "https://m.youtube.com/results?search_query=%s", "https://m.youtube.com"),
            new Engine("wiki", "Википедия", "W", "https://ru.wikipedia.org/w/index.php?search=%s", "https://ru.wikipedia.org"));

    static List<Engine> all() {
        List<Engine> list = new ArrayList<>(ALL);
        Engine custom = custom();
        if (custom != null) list.add(custom);
        return list;
    }

    static Engine custom() {
        String t = Prefs.customEngine();
        if (t == null || !t.contains("%s")) return null;
        Uri u = Uri.parse(t.replace("%s", "x"));
        String host = u.getHost() == null ? "Свой поиск" : u.getHost();
        return new Engine("custom", "Свой: " + host, "★", t, u.getScheme() + "://" + host);
    }

    static Engine get(String id) {
        for (Engine e : all()) if (e.id.equals(id)) return e;
        return ALL.get(0);
    }

    static String searchUrl(Engine e, String query) {
        return e.template.replace("%s", Uri.encode(query));
    }

    /** Адрес сайта → https://адрес, всё остальное → поиск. */
    static String toUrl(String input, Engine e) {
        String s = input == null ? "" : input.trim();
        if (s.isEmpty()) return null;
        String lower = s.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")) return s;
        if (lower.startsWith("about:")) return s;
        if (!s.contains(" ")) {
            if (lower.startsWith("localhost") || lower.matches("^\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?(/.*)?$")) {
                return "http://" + s;
            }
            if (s.contains(".") && Patterns.WEB_URL.matcher(s).matches()) return "https://" + s;
        }
        return searchUrl(e, s);
    }

    /** Если адрес — это поиск в одной из систем, вернуть сам запрос (для красивого показа). */
    static String queryOf(String url) {
        if (url == null) return null;
        try {
            Uri u = Uri.parse(url);
            String host = u.getHost();
            if (host == null) return null;
            for (Engine e : all()) {
                Uri t = Uri.parse(e.template.replace("%s", "QQQ"));
                if (t.getHost() != null && host.equalsIgnoreCase(t.getHost()) && t.getPath() != null
                        && t.getPath().equals(u.getPath())) {
                    for (String name : t.getQueryParameterNames()) {
                        if ("QQQ".equals(t.getQueryParameter(name))) return u.getQueryParameter(name);
                    }
                }
            }
        } catch (Exception ignored) {
            // не поиск
        }
        return null;
    }
}
