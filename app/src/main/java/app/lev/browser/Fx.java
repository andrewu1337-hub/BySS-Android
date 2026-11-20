package app.lev.browser;

import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Курсы валют для конвертера на главной. Источник — открытый бесплатный API ExchangeRate-API
 * (без ключа, курсы обновляются раз в сутки, ссылку на источник показываем в окне конвертера).
 * Берём одну таблицу «от евро» и считаем любые пары через неё.
 */
final class Fx {

    private Fx() { }

    static final String SOURCE_URL = "https://www.exchangerate-api.com";
    private static final String API = "https://open.er-api.com/v6/latest/EUR";
    private static final long FRESH = 6 * 3600_000L;
    private static final String DEFAULT_LIST = "USD,MDL,RON,UAH,CZK,GBP";
    /** Эти валюты — первыми в списке выбора. */
    static final List<String> POPULAR = Arrays.asList("EUR", "USD", "MDL", "RON", "UAH", "RUB", "GBP", "CZK", "PLN",
            "CHF", "TRY", "BGN", "HUF", "BYN", "KZT", "GEL", "AMD", "AZN", "SEK", "NOK", "DKK", "RSD", "ILS", "AED",
            "CNY", "JPY", "CAD", "AUD");

    private static final Map<String, Double> rates = new TreeMap<>();
    private static long updated;   // когда источник обновил курсы
    private static boolean loading;
    private static final List<Runnable> waiting = new ArrayList<>();
    private static final Handler ui = new Handler(Looper.getMainLooper());

    // ------------------------------------------------------------ настройки

    static String base() {
        String b = Prefs.sp().getString("fx_base", "EUR");
        return b == null || b.length() != 3 ? "EUR" : b;
    }

    static void setBase(String code) {
        Prefs.put("fx_base", code);
    }

    static List<String> targets() {
        String s = Prefs.sp().getString("fx_list", DEFAULT_LIST);
        List<String> out = new ArrayList<>();
        if (s != null) for (String p : s.split(",")) if (p.trim().length() == 3 && !out.contains(p.trim())) out.add(p.trim());
        return out;
    }

    static void setTargets(List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (String c : list) {
            if (sb.length() > 0) sb.append(',');
            sb.append(c);
        }
        Prefs.put("fx_list", sb.toString());
    }

    static double amount() {
        try {
            return Double.parseDouble(Prefs.sp().getString("fx_amount", "1"));
        } catch (Exception e) {
            return 1;
        }
    }

    static void setAmount(double v) {
        Prefs.put("fx_amount", String.valueOf(v));
    }

    // ------------------------------------------------------------ курсы

    static boolean ready() {
        load();
        return !rates.isEmpty();
    }

    static long updatedAt() {
        load();
        return updated;
    }

    static List<String> codes() {
        load();
        List<String> all = new ArrayList<>(rates.keySet());
        List<String> out = new ArrayList<>();
        for (String p : POPULAR) if (all.contains(p)) out.add(p);
        Collections.sort(all);
        for (String c : all) if (!out.contains(c)) out.add(c);
        return out;
    }

    /** amount в валюте from → в валюте to; NaN, если курса нет. */
    static double convert(double amount, String from, String to) {
        load();
        Double f = rates.get(from), t = rates.get(to);
        if (f == null || t == null || f == 0) return Double.NaN;
        return amount / f * t;
    }

    private static boolean loaded;

    private static synchronized void load() {
        if (loaded) return;
        loaded = true;
        String json = Prefs.sp().getString("fx_json", null);
        if (json != null) parse(json);
    }

    private static synchronized boolean parse(String json) {
        try {
            JSONObject o = new JSONObject(json);
            if (!"success".equals(o.optString("result"))) return false;
            JSONObject r = o.getJSONObject("rates");
            Map<String, Double> m = new TreeMap<>();
            for (Iterator<String> it = r.keys(); it.hasNext(); ) {
                String k = it.next();
                double v = r.optDouble(k, Double.NaN);
                if (k.length() == 3 && v > 0) m.put(k, v);
            }
            if (m.isEmpty()) return false;
            rates.clear();
            rates.putAll(m);
            updated = o.optLong("time_last_update_unix", 0) * 1000L;
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Обновить курсы, если они старше 6 часов (или force). done — в главном потоке, с результатом. */
    static void refresh(boolean force, java.util.function.Consumer<Boolean> done) {
        load();
        long fetched = Prefs.sp().getLong("fx_time", 0);
        if (!force && !rates.isEmpty() && System.currentTimeMillis() - fetched < FRESH) {
            if (done != null) done.accept(true);
            return;
        }
        synchronized (waiting) {
            if (done != null) waiting.add(() -> done.accept(!rates.isEmpty()));
            if (loading) return;
            loading = true;
        }
        new Thread(() -> {
            boolean ok = false;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(API).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                c.setRequestProperty("Accept", "application/json");
                try (InputStream in = c.getInputStream()) {
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                    String json = bo.toString("UTF-8");
                    if (parse(json)) {
                        Prefs.put("fx_json", json);
                        Prefs.put("fx_time", System.currentTimeMillis());
                        ok = true;
                    }
                } finally {
                    c.disconnect();
                }
            } catch (Exception ignored) {
                // нет связи — остаются прошлые курсы
            }
            boolean res = ok;
            ui.post(() -> {
                List<Runnable> run;
                synchronized (waiting) {
                    loading = false;
                    run = new ArrayList<>(waiting);
                    waiting.clear();
                }
                for (Runnable r : run) r.run();
            });
        }).start();
    }

    // ------------------------------------------------------------ вид

    static String name(String code) {
        try {
            String n = java.util.Currency.getInstance(code).getDisplayName(new Locale("ru"));
            if (n == null || n.isEmpty() || n.equals(code)) return code;
            return Character.toUpperCase(n.charAt(0)) + n.substring(1);
        } catch (Exception e) {
            return code;
        }
    }

    static String symbol(String code) {
        switch (code) {
            case "EUR": return "€";
            case "USD": return "$";
            case "GBP": return "£";
            case "RUB": return "₽";
            case "UAH": return "₴";
            case "JPY": case "CNY": return "¥";
            case "TRY": return "₺";
            case "PLN": return "zł";
            case "CZK": return "Kč";
            case "MDL": case "RON": return "L";
            case "KZT": return "₸";
            case "GEL": return "₾";
            case "ILS": return "₪";
            case "INR": return "₹";
            default: return code.substring(0, 1);
        }
    }

    /** Флаг страны валюты: EUR — флаг Евросоюза, у остальных — по первым двум буквам кода. */
    static String flag(String code) {
        if (code == null || code.length() != 3) return "";
        String cc = code.equals("EUR") ? "EU" : code.substring(0, 2);
        if (cc.charAt(0) == 'X') return "🌐";
        int a = 0x1F1E6 + (cc.charAt(0) - 'A'), b = 0x1F1E6 + (cc.charAt(1) - 'A');
        return new String(Character.toChars(a)) + new String(Character.toChars(b));
    }

    static String format(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "—";
        DecimalFormatSymbols sy = new DecimalFormatSymbols(new Locale("ru"));
        sy.setGroupingSeparator(' ');
        sy.setDecimalSeparator(',');
        double a = Math.abs(v);
        String pattern = a >= 1 ? "#,##0.00" : a >= 0.01 ? "0.0000" : "0.000000";
        return new DecimalFormat(pattern, sy).format(v);
    }

    /** Вторая валюта строки на главной — первая в списке (кроме основной); null — списка нет. */
    static String homeTarget() {
        String b = base();
        for (String c : targets()) if (!c.equals(b)) return c;
        return null;
    }

    /** Сделать валюту второй на главной: ставим её первой в списке. */
    static void setHomeTarget(String code) {
        List<String> t = targets();
        t.remove(code);
        t.add(0, code);
        setTargets(t);
    }

    /** Строка на главной — только две валюты: «1 EUR → 1,05 USD». */
    static CharSequence summary() {
        String b = base();
        String to = homeTarget();
        if (!ready() || to == null) return "Конвертер валют";
        SpannableStringBuilder sb = new SpannableStringBuilder();
        sb.append("1 ").append(b);
        sb.setSpan(new ForegroundColorSpan(Ui.text2), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        int a = sb.length();
        sb.append("   →   ");
        sb.setSpan(new ForegroundColorSpan(Ui.text2), a, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        int v = sb.length();
        sb.append(format(convert(1, b, to))).append(' ').append(to);
        sb.setSpan(new StyleSpan(Typeface.BOLD), v, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }
}
