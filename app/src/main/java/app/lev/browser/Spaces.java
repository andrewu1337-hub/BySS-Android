package app.lev.browser;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Приватные режимы «BySS»: каждый — отдельное хранилище (свой профиль WebView: cookie, входы,
 * данные сайтов, кэш). Их может быть несколько одновременно. Удаляются только вручную,
 * и после удаления от режима не остаётся ничего.
 */
final class Spaces {

    private Spaces() { }

    static final class Space {
        final String id;
        String name;

        Space(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    static List<Space> all() {
        List<Space> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(Prefs.sp().getString("spaces", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new Space(o.getString("id"), o.optString("name", Prefs.incogName())));
            }
        } catch (Exception ignored) {
            // испорчено — пусто
        }
        return out;
    }

    private static void save(List<Space> list) {
        JSONArray a = new JSONArray();
        try {
            for (Space s : list) a.put(new JSONObject().put("id", s.id).put("name", s.name));
        } catch (Exception ignored) {
            // не сохранили
        }
        Prefs.put("spaces", a.toString());
    }

    static Space get(String id) {
        if (id == null) return null;
        for (Space s : all()) if (s.id.equals(id)) return s;
        return null;
    }

    static String name(String id) {
        Space s = get(id);
        return s == null ? Prefs.incogName() : s.name;
    }

    /** Новый режим: «BySS 1», «BySS 2»… (первое свободное число). */
    static Space create() {
        List<Space> list = all();
        String base = Prefs.incogName();
        int n = 1;
        while (true) {
            String candidate = base + " " + n;
            boolean used = false;
            for (Space s : list) if (s.name.equals(candidate)) used = true;
            if (!used) break;
            n++;
        }
        Space s = new Space("s" + System.currentTimeMillis(), base + " " + n);
        list.add(s);
        save(list);
        return s;
    }

    static void rename(String id, String name) {
        List<Space> list = all();
        for (Space s : list) if (s.id.equals(id)) s.name = name;
        save(list);
    }

    static void remove(String id) {
        List<Space> list = all();
        for (int i = list.size() - 1; i >= 0; i--) if (list.get(i).id.equals(id)) list.remove(i);
        save(list);
        SitePrefs.forgetSpace(id);
    }

    /** Имя профиля WebView для режима. */
    static String profile(String id) {
        return "byss_" + id;
    }
}
