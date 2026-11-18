package app.lev.browser;

import android.net.Uri;
import android.util.LruCache;
import android.widget.Toast;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.languageid.LanguageIdentification;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Перевод страниц.
 *  device — прямо на телефоне (Google ML Kit, офлайн-модели), текст никуда не уходит;
 *  google / yandex — открыть страницу через сайт Google Переводчика или Яндекс Переводчика.
 */
final class PageTranslator {

    private final BrowserActivity act;
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final Map<String, Translator> clients = new HashMap<>();
    private final Map<Long, Translator> active = new HashMap<>();
    private final LruCache<String, String> cache = new LruCache<>(3000);
    private final String script;

    PageTranslator(BrowserActivity act, String script) {
        this.act = act;
        this.script = script;
    }

    /** Переводим только на русский или английский — с любого языка. */
    static final String[][] TARGETS = {{"ru", "Русский"}, {"en", "Английский"}};

    /** Язык из настроек; если страница уже на нём — второй из пары (рус ↔ англ). */
    static String targetFor(String src) {
        String t = Prefs.translateTarget();
        if (t.equals(src)) return "ru".equals(t) ? "en" : "ru";
        return t;
    }

    /** Языки, которые пользователь знает: на них перевод не предлагаем. */
    static boolean known(String lang) {
        return "ru".equals(lang) || "en".equals(lang);
    }

    private static final Locale RU = Locale.forLanguageTag("ru");

    /** «немецкий», «чешский»… */
    static String langName(String code) {
        String n = Locale.forLanguageTag(code).getDisplayLanguage(RU);
        return n == null || n.isEmpty() ? code : n.toLowerCase(RU);
    }

    static String targetName(String code) {
        for (String[] t : TARGETS) if (t[0].equals(code)) return t[1];
        return code;
    }

    /** Перевести вкладку выбранным в настройках способом (или явно указанным). */
    void translate(Tab tab, String mode) {
        if (tab == null || tab.isHome() || tab.web == null) return;
        if ("google".equals(mode) || "yandex".equals(mode)) {
            viaSite(tab, mode);
        } else {
            onDevice(tab);
        }
    }

    void restore(Tab tab) {
        if (tab == null || tab.web == null) return;
        tab.web.evaluateJavascript("window.__levTr&&__levTr.restore()", null);
        tab.translated = false;
        synchronized (active) {
            active.remove(tab.id);
        }
        act.onTranslateState(tab);
    }

    // ------------------------------------------------------------- через сайт

    private void viaSite(Tab tab, String which) {
        String url = tab.url;
        if (url == null) return;
        String target = Prefs.translateTarget();
        String host = Uri.parse(url).getHost();
        if (host != null && (host.endsWith("translate.goog") || host.contains("translate.yandex"))) return;
        tab.web.evaluateJavascript("(document.documentElement.lang||'').slice(0,2).toLowerCase()", value -> {
            String src = decode(value);
            if (src == null || src.length() != 2) src = null;
            String tgt = src == null ? target : targetFor(src);
            if ("yandex".equals(which)) {
                act.loadUrl(tab, "https://translate.yandex.ru/translate?url=" + Uri.encode(url)
                        + "&lang=" + (src == null ? "en" : src) + "-" + tgt);
            } else {
                act.loadUrl(tab, "https://translate.google.com/translate?sl=auto&tl=" + tgt
                        + "&hl=ru&u=" + Uri.encode(url));
            }
        });
    }

    // ---------------------------------------------------------- на телефоне

    private void onDevice(Tab tab) {
        tab.web.evaluateJavascript(script, ignored ->
                tab.web.evaluateJavascript("window.__levTr?__levTr.sample():''", value -> {
                    String sample = decode(value);
                    exec.execute(() -> prepare(tab, sample));
                }));
    }

    private com.google.mlkit.nl.languageid.LanguageIdentifier langId;

    /** Язык страницы по образцу текста (ML Kit на телефоне), затем по html lang. Код ML Kit или null. */
    private String detectLang(String sampleJson) {
        try {
            JSONObject s = new JSONObject(sampleJson == null || sampleJson.isEmpty() ? "{}" : sampleJson);
            String text = s.optString("text");
            String htmlLang = s.optString("lang");
            String src = null;
            if (text.length() > 40) {
                if (langId == null) langId = LanguageIdentification.getClient();
                String id = Tasks.await(langId.identifyLanguage(text), 20, TimeUnit.SECONDS);
                if (id != null && !"und".equals(id)) src = TranslateLanguage.fromLanguageTag(id);
            }
            if (src == null && !htmlLang.isEmpty()) {
                src = TranslateLanguage.fromLanguageTag(htmlLang.toLowerCase(Locale.ROOT).split("[-_]")[0]);
            }
            return src;
        } catch (Exception e) {
            return null;
        }
    }

    /** Определить язык страницы для предложения перевода; ответ приходит в UI-потоке. */
    void detect(Tab tab, java.util.function.Consumer<String> done) {
        if (tab == null || tab.web == null) return;
        tab.web.evaluateJavascript(script, ignored -> {
            if (tab.web == null) return;
            tab.web.evaluateJavascript("window.__levTr?__levTr.sample():''", value -> {
                String sample = decode(value);
                exec.execute(() -> {
                    String lang = detectLang(sample);
                    act.runOnUiThread(() -> done.accept(lang));
                });
            });
        });
    }

    private void prepare(Tab tab, String sampleJson) {
        try {
            String src = detectLang(sampleJson);
            if (src == null) {
                toast("Не получилось определить язык страницы");
                return;
            }
            String tgt = TranslateLanguage.fromLanguageTag(targetFor(src));
            if (tgt == null) tgt = TranslateLanguage.RUSSIAN;
            String key = src + ">" + tgt;
            Translator client = clients.get(key);
            if (client == null) {
                client = Translation.getClient(new TranslatorOptions.Builder()
                        .setSourceLanguage(src).setTargetLanguage(tgt).build());
                clients.put(key, client);
                toast("Готовлю перевод: " + langName(src) + " → " + langName(tgt)
                        + ". В первый раз скачивается пакет языка (~30 МБ)");
            }
            Tasks.await(client.downloadModelIfNeeded(new DownloadConditions.Builder().build()), 5, TimeUnit.MINUTES);
            synchronized (active) {
                active.put(tab.id, client);
            }
            String to = tgt;
            act.runOnUiThread(() -> {
                if (tab.web == null) return;
                tab.translated = true;
                tab.trTo = to;
                act.onTranslateState(tab);
                tab.web.evaluateJavascript("__levTr.start()", null);
            });
        } catch (Exception e) {
            toast("Перевод не удался: " + e.getMessage());
        }
    }

    /** Вызывается из страницы: перевести пакет абзацев. */
    void onBatch(Tab tab, int id, String json) {
        if (json == null || json.length() > 200_000) return;
        exec.execute(() -> {
            Translator client;
            synchronized (active) {
                client = active.get(tab.id);
            }
            if (client == null) return;
            JSONArray out = new JSONArray();
            try {
                JSONArray items = new JSONArray(json);
                for (int i = 0; i < items.length(); i++) {
                    JSONObject it = items.getJSONObject(i);
                    if (it.optInt("w") == 1) {
                        out.put(tr(client, it.optString("t")));
                    } else {
                        JSONArray parts = it.getJSONArray("t");
                        JSONArray res = new JSONArray();
                        for (int k = 0; k < parts.length(); k++) res.put(tr(client, parts.optString(k)));
                        out.put(res);
                    }
                }
                String js = "window.__levTr&&__levTr.apply(" + id + "," + out + ")";
                act.runOnUiThread(() -> {
                    if (tab.web != null) tab.web.evaluateJavascript(js, null);
                });
            } catch (Exception e) {
                act.runOnUiThread(() -> {
                    if (tab.web != null) tab.web.evaluateJavascript("window.__levTr&&__levTr.fail(" + id + ")", null);
                });
            }
        });
    }

    private String tr(Translator client, String text) throws Exception {
        if (text == null || text.trim().isEmpty()) return text;
        String cached = cache.get(text);
        if (cached != null) return cached;
        String r = Tasks.await(client.translate(text), 60, TimeUnit.SECONDS);
        if (r != null) cache.put(text, r);
        return r;
    }

    void forget(Tab tab) {
        synchronized (active) {
            active.remove(tab.id);
        }
    }

    void close() {
        for (Translator t : clients.values()) t.close();
        if (langId != null) langId.close();
        clients.clear();
        exec.shutdownNow();
    }

    private void toast(String msg) {
        act.runOnUiThread(() -> Toast.makeText(act, msg, Toast.LENGTH_SHORT).show());
    }

    /** Результат evaluateJavascript приходит JSON-строкой в кавычках. */
    static String decode(String value) {
        if (value == null || "null".equals(value)) return null;
        try {
            Object o = new JSONTokener(value).nextValue();
            return o instanceof String ? (String) o : String.valueOf(o);
        } catch (Exception e) {
            return value;
        }
    }
}
