package app.lev.browser;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Ярлыки сайтов на начальной странице (список можно менять). */
final class Shortcuts {

    private Shortcuts() { }

    static final class Item {
        String title;
        String url;
        String glyph;

        Item(String title, String url, String glyph) {
            this.title = title;
            this.url = url;
            this.glyph = glyph;
        }
    }

    static List<Item> defaults() {
        List<Item> l = new ArrayList<>();
        l.add(new Item("Google", "https://www.google.com", "G"));
        l.add(new Item("Яндекс", "https://ya.ru", "Я"));
        l.add(new Item("DuckDuckGo", "https://duckduckgo.com", "D"));
        l.add(new Item("Переводчик", "https://translate.google.com", "AЯ"));
        l.add(new Item("YouTube", "https://m.youtube.com", "▶"));
        l.add(new Item("Sauto", "https://www.sauto.cz", "S"));
        l.add(new Item("mobile.de", "https://www.mobile.de", "m"));
        l.add(new Item("AutoScout24", "https://www.autoscout24.com", "A"));
        return l;
    }

    static List<Item> load() {
        String json = Prefs.sp().getString("shortcuts", null);
        if (json == null) return defaults();
        List<Item> l = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                l.add(new Item(o.optString("t"), o.optString("u"), o.optString("g")));
            }
        } catch (Exception e) {
            return defaults();
        }
        return l;
    }

    static void save(List<Item> items) {
        JSONArray a = new JSONArray();
        try {
            for (Item it : items) {
                JSONObject o = new JSONObject();
                o.put("t", it.title);
                o.put("u", it.url);
                o.put("g", it.glyph);
                a.put(o);
            }
        } catch (Exception ignored) {
            return;
        }
        Prefs.put("shortcuts", a.toString());
    }

    static String glyphFor(String title) {
        String t = title == null ? "" : title.trim();
        if (t.isEmpty()) return "•";
        int cp = t.codePointAt(0);
        return new String(Character.toChars(cp)).toUpperCase(Locale.ROOT);
    }
}
