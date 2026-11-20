package app.lev.browser;

import android.graphics.Bitmap;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Одна вкладка. url == null — начальная страница. */
final class Tab {

    private static long nextId = System.currentTimeMillis();

    final long id;
    String url;
    String title;
    long lastUsed;
    ByssWebView web;
    /** Спящая вкладка: сохранённая история «назад» (только в памяти). */
    android.os.Bundle state;
    /** Не используется: картинки вкладок живут в кэше BrowserActivity (экономия памяти). */
    Bitmap thumb;
    /** Режим BySS, к которому относится вкладка (null — обычный режим). */
    String space;
    /** Профиль WebView, в котором сейчас открыта вкладка (null — обычный). */
    String profile;
    volatile String pageHost;
    final AtomicInteger blocked = new AtomicInteger();
    boolean translated;
    /** На какой язык переведена страница (ru/en). */
    String trTo;
    /** Сайт, для которого уже определяли язык в этой вкладке. */
    String langChecked;
    boolean mediaPlaying;
    /** Вкладка открыта в отдельном хранилище YouTube (не стирается кнопкой «Удалить всё»). */
    boolean ytProfile;
    Tab parent;

    Tab() {
        this(nextId++);
    }

    Tab(long id) {
        this.id = id;
        if (id >= nextId) nextId = id + 1;
        lastUsed = System.currentTimeMillis();
    }

    boolean isHome() {
        return url == null;
    }

    String displayTitle() {
        if (isHome()) return "Начальная страница";
        if (title != null && !title.trim().isEmpty()) return title;
        return url;
    }

    // ---------------------------------------------------------------- хранение

    static String toJson(List<Tab> tabs, Tab current) {
        JSONObject root = new JSONObject();
        try {
            JSONArray a = new JSONArray();
            for (Tab t : tabs) {
                JSONObject o = new JSONObject();
                o.put("id", t.id);
                if (t.url != null) o.put("url", t.url);
                if (t.title != null) o.put("title", t.title);
                o.put("used", t.lastUsed);
                if (t.space != null) o.put("space", t.space);
                a.put(o);
            }
            root.put("tabs", a);
            root.put("current", current == null ? -1 : current.id);
        } catch (Exception ignored) {
            // пусто
        }
        return root.toString();
    }

    /** Возвращает список вкладок; id текущей кладёт в currentOut[0]. */
    static List<Tab> fromJson(String json, long[] currentOut) {
        List<Tab> list = new ArrayList<>();
        if (json == null) return list;
        try {
            JSONObject root = new JSONObject(json);
            JSONArray a = root.getJSONArray("tabs");
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Tab t = new Tab(o.getLong("id"));
                t.url = o.has("url") ? o.getString("url") : null;
                t.title = o.has("title") ? o.getString("title") : null;
                t.lastUsed = o.optLong("used", System.currentTimeMillis());
                t.space = o.has("space") ? o.getString("space") : null;
                list.add(t);
            }
            currentOut[0] = root.optLong("current", -1);
        } catch (Exception ignored) {
            // испорчено — начнём с чистого листа
        }
        return list;
    }
}
