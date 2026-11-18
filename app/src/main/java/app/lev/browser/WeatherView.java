package app.lev.browser;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Locale;

/**
 * Погода вверху начального экрана. Данные — Open-Meteo (бесплатно, без ключа и регистрации).
 * Город выбирается в «Доп. настройки → Начальный экран» или нажатием на саму погоду.
 */
final class WeatherView extends LinearLayout {

    static final String K_ON = "weather_on";
    private static final String K_CITY = "weather_city";
    private static final String K_LAT = "weather_lat";
    private static final String K_LON = "weather_lon";
    private static final String K_DATA = "weather_data";
    private static final String K_TIME = "weather_time";
    private static final long FRESH_MS = 30 * 60 * 1000L;

    private static volatile boolean loading;

    private final Icon icon;
    private final TextView temp;
    private final TextView line;

    WeatherView(Context c) {
        super(c);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setPadding(0, Ui.dp(c, 4), Ui.dp(c, 8), Ui.dp(c, 4));
        icon = new Icon(c);
        addView(icon, new LayoutParams(Ui.dp(c, 38), Ui.dp(c, 38)));
        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(VERTICAL);
        texts.setPadding(Ui.dp(c, 12), 0, 0, 0);
        temp = Ui.text(c, "", 30, Ui.text, false);
        temp.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        temp.setIncludeFontPadding(false);
        line = Ui.text(c, "", 13, Ui.text2, false);
        texts.addView(temp);
        texts.addView(line);
        addView(texts, new LayoutParams(-2, -2));
        setOnClickListener(v -> {
            Haptics.click(v);
            if (city().isEmpty() && getContext() instanceof Activity) {
                pickCity((Activity) getContext(), this::refresh);
            } else {
                load(true);
            }
        });
        setOnLongClickListener(v -> {
            if (getContext() instanceof Activity) pickCity((Activity) getContext(), this::refresh);
            return true;
        });
    }

    static boolean enabled() {
        return Prefs.sp().getBoolean(K_ON, true);
    }

    static String city() {
        return Prefs.sp().getString(K_CITY, "");
    }

    /** Для настроек: «Прага, Чехия» или «не выбран». */
    static String summary() {
        String c = city();
        return c.isEmpty() ? "Не выбран — нажмите, чтобы найти" : c;
    }

    void refresh() {
        if (!enabled()) {
            setVisibility(GONE);
            return;
        }
        setVisibility(VISIBLE);
        temp.setTextColor(Ui.text);
        line.setTextColor(Ui.text2);
        icon.invalidate();
        if (city().isEmpty()) {
            icon.kind = Icon.PARTLY;
            temp.setVisibility(GONE);
            line.setText("Погода — нажмите, чтобы выбрать город");
            return;
        }
        show();
        long age = System.currentTimeMillis() - Prefs.sp().getLong(K_TIME, 0L);
        if (age > FRESH_MS) load(false);
    }

    private void show() {
        String name = city();
        int comma = name.indexOf(',');
        String shortName = comma > 0 ? name.substring(0, comma) : name;
        try {
            JSONObject d = new JSONObject(Prefs.sp().getString(K_DATA, "{}"));
            if (!d.has("t")) {
                temp.setVisibility(GONE);
                line.setText(shortName + " · загружаю погоду…");
                return;
            }
            int code = d.optInt("code");
            icon.kind = Icon.kindFor(code, d.optBoolean("day", true));
            icon.invalidate();
            temp.setVisibility(VISIBLE);
            temp.setText(deg(d.optDouble("t")));
            String s = shortName + " · " + describe(code);
            if (d.has("max")) s += " · " + deg(d.optDouble("max")) + " / " + deg(d.optDouble("min"));
            line.setText(s);
        } catch (Exception e) {
            temp.setVisibility(GONE);
            line.setText(shortName);
        }
    }

    private static String deg(double v) {
        long r = Math.round(v);
        return (r == 0 ? "0" : String.valueOf(r)).replace("-", "−") + "°";
    }

    private void load(boolean byUser) {
        if (loading || city().isEmpty()) return;
        loading = true;
        String lat = Prefs.sp().getString(K_LAT, "");
        String lon = Prefs.sp().getString(K_LON, "");
        new Thread(() -> {
            boolean ok = false;
            try {
                JSONObject j = new JSONObject(get("https://api.open-meteo.com/v1/forecast?latitude=" + lat
                        + "&longitude=" + lon
                        + "&current=temperature_2m,weather_code,is_day"
                        + "&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1"));
                JSONObject cur = j.getJSONObject("current");
                JSONObject d = new JSONObject();
                d.put("t", cur.getDouble("temperature_2m"));
                d.put("code", cur.optInt("weather_code"));
                d.put("day", cur.optInt("is_day", 1) == 1);
                JSONObject daily = j.optJSONObject("daily");
                if (daily != null) {
                    d.put("max", daily.getJSONArray("temperature_2m_max").getDouble(0));
                    d.put("min", daily.getJSONArray("temperature_2m_min").getDouble(0));
                }
                Prefs.sp().edit().putString(K_DATA, d.toString()).putLong(K_TIME, System.currentTimeMillis()).apply();
                ok = true;
            } catch (Exception ignored) {
                // нет сети — покажем последнюю известную погоду
            }
            loading = false;
            boolean done = ok;
            post(() -> {
                show();
                if (!done && byUser) Toast.makeText(getContext(), "Погода не обновилась — нет сети?", Toast.LENGTH_SHORT).show();
            });
        }).start();
    }

    private static String get(String url) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(8000);
        h.setReadTimeout(10000);
        h.setRequestProperty("User-Agent", "Lev-Browser");
        try (InputStream in = h.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            h.disconnect();
        }
    }

    // ------------------------------------------------------------ выбор города

    /** Поиск города по названию (Open-Meteo Geocoding) и выбор из списка. */
    static void pickCity(Activity a, Runnable done) {
        EditText e = new EditText(a);
        e.setSingleLine(true);
        e.setHint("Например: Прага, Амстердам, Москва");
        e.setText(cityName());
        e.setSelectAllOnFocus(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        e.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        FrameLayout box = new FrameLayout(a);
        int p = Ui.dp(a, 22);
        box.setPadding(p, Ui.dp(a, 8), p, 0);
        box.addView(e);
        AlertDialog d = new AlertDialog.Builder(a)
                .setTitle("Город для погоды")
                .setView(box)
                .setPositiveButton("Найти", (x, w) -> search(a, e.getText().toString().trim(), done))
                .setNegativeButton("Отмена", null)
                .create();
        e.setOnEditorActionListener((v, id, ev) -> {
            if (id == EditorInfo.IME_ACTION_SEARCH) {
                d.dismiss();
                search(a, e.getText().toString().trim(), done);
                return true;
            }
            return false;
        });
        d.show();
    }

    private static String cityName() {
        String c = city();
        int comma = c.indexOf(',');
        return comma > 0 ? c.substring(0, comma) : c;
    }

    private static void search(Activity a, String q, Runnable done) {
        if (q.isEmpty()) return;
        Toast.makeText(a, "Ищу «" + q + "»…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            JSONArray res = null;
            try {
                JSONObject j = new JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?count=8&language=ru&format=json&name="
                        + URLEncoder.encode(q, "UTF-8")));
                res = j.optJSONArray("results");
            } catch (Exception ignored) {
                // покажем ошибку ниже
            }
            JSONArray r = res;
            a.runOnUiThread(() -> {
                if (a.isFinishing()) return;
                if (r == null || r.length() == 0) {
                    Toast.makeText(a, "Ничего не нашлось. Проверьте название или сеть", Toast.LENGTH_LONG).show();
                    return;
                }
                String[] names = new String[r.length()];
                for (int i = 0; i < r.length(); i++) {
                    JSONObject o = r.optJSONObject(i);
                    String n = o.optString("name");
                    String region = o.optString("admin1");
                    String country = o.optString("country");
                    StringBuilder sb = new StringBuilder(n);
                    if (!country.isEmpty()) sb.append(", ").append(country);
                    if (!region.isEmpty() && !region.equals(n)) sb.append(" · ").append(region);
                    names[i] = sb.toString();
                }
                new AlertDialog.Builder(a)
                        .setTitle("Выберите город")
                        .setItems(names, (x, w) -> {
                            JSONObject o = r.optJSONObject(w);
                            String country = o.optString("country");
                            String name = o.optString("name") + (country.isEmpty() ? "" : ", " + country);
                            Prefs.sp().edit()
                                    .putString(K_CITY, name)
                                    .putString(K_LAT, String.format(Locale.ROOT, "%.4f", o.optDouble("latitude")))
                                    .putString(K_LON, String.format(Locale.ROOT, "%.4f", o.optDouble("longitude")))
                                    .remove(K_DATA)
                                    .putLong(K_TIME, 0L)
                                    .putBoolean(K_ON, true)
                                    .apply();
                            if (done != null) done.run();
                        })
                        .setNegativeButton("Отмена", null)
                        .show();
            });
        }).start();
    }

    // ------------------------------------------------------------- описание

    static String describe(int code) {
        if (code == 0) return "ясно";
        if (code == 1) return "малооблачно";
        if (code == 2) return "переменная облачность";
        if (code == 3) return "пасмурно";
        if (code == 45 || code == 48) return "туман";
        if (code >= 51 && code <= 55) return "морось";
        if (code == 56 || code == 57) return "ледяная морось";
        if (code >= 61 && code <= 65) return code == 65 ? "сильный дождь" : "дождь";
        if (code == 66 || code == 67) return "ледяной дождь";
        if (code >= 71 && code <= 75) return code == 75 ? "сильный снег" : "снег";
        if (code == 77) return "снежная крупа";
        if (code >= 80 && code <= 82) return "ливень";
        if (code == 85 || code == 86) return "снегопад";
        if (code == 95) return "гроза";
        if (code == 96 || code == 99) return "гроза с градом";
        return "";
    }

    // ------------------------------------------------------------- значок

    /** Минималистичные значки погоды, нарисованные линиями (в стиле остальных иконок). */
    static final class Icon extends View {
        static final int SUN = 0, MOON = 1, PARTLY = 2, PARTLY_NIGHT = 3, CLOUD = 4, RAIN = 5, SNOW = 6, STORM = 7, FOG = 8;

        int kind = CLOUD;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Icon(Context c) {
            super(c);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
        }

        static int kindFor(int code, boolean day) {
            if (code == 0) return day ? SUN : MOON;
            if (code == 1 || code == 2) return day ? PARTLY : PARTLY_NIGHT;
            if (code == 45 || code == 48) return FOG;
            if ((code >= 71 && code <= 77) || code == 85 || code == 86) return SNOW;
            if (code >= 95) return STORM;
            if (code >= 51) return RAIN;
            return CLOUD;
        }

        private static Path cloud(float dx, float dy) {
            Path a = new Path();
            a.addCircle(8.5f + dx, 14.5f + dy, 3f, Path.Direction.CW);
            Path b = new Path();
            b.addCircle(13f + dx, 11.5f + dy, 4.5f, Path.Direction.CW);
            Path c = new Path();
            c.addCircle(17f + dx, 14.5f + dy, 3f, Path.Direction.CW);
            Path r = new Path();
            r.addRect(8.5f + dx, 14.5f + dy, 17f + dx, 17.5f + dy, Path.Direction.CW);
            a.op(b, Path.Op.UNION);
            a.op(c, Path.Op.UNION);
            a.op(r, Path.Op.UNION);
            return a;
        }

        private void sun(Canvas c, float cx, float cy, float r, float r1, float r2) {
            c.drawCircle(cx, cy, r, p);
            for (int i = 0; i < 8; i++) {
                double a = Math.PI * i / 4;
                c.drawLine(cx + (float) Math.cos(a) * r1, cy + (float) Math.sin(a) * r1,
                        cx + (float) Math.cos(a) * r2, cy + (float) Math.sin(a) * r2, p);
            }
        }

        private void moon(Canvas c, float cx, float cy, float r) {
            Path m = new Path();
            m.addCircle(cx, cy, r, Path.Direction.CW);
            Path cut = new Path();
            cut.addCircle(cx + r * 0.55f, cy - r * 0.45f, r * 0.85f, Path.Direction.CW);
            m.op(cut, Path.Op.DIFFERENCE);
            c.drawPath(m, p);
        }

        @Override
        protected void onDraw(Canvas c) {
            float s = Math.min(getWidth(), getHeight()) / 24f;
            c.save();
            c.translate((getWidth() - 24 * s) / 2f, (getHeight() - 24 * s) / 2f);
            c.scale(s, s);
            p.setColor(Ui.text);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.5f);
            switch (kind) {
                case SUN:
                    sun(c, 12, 12, 4f, 6.5f, 9f);
                    break;
                case MOON:
                    moon(c, 12, 12, 7f);
                    break;
                case PARTLY:
                case PARTLY_NIGHT: {
                    Path cl = cloud(1.5f, 1.5f);
                    c.save();
                    c.clipOutPath(cl);
                    if (kind == PARTLY) sun(c, 8.5f, 8.5f, 3f, 5f, 6.8f);
                    else moon(c, 8.5f, 8.5f, 4.5f);
                    c.restore();
                    c.drawPath(cl, p);
                    break;
                }
                case RAIN:
                    c.drawPath(cloud(0, -3), p);
                    for (int i = 0; i < 3; i++) {
                        float x = 10f + i * 3f;
                        c.drawLine(x, 16.8f, x - 1.2f, 20.3f, p);
                    }
                    break;
                case SNOW:
                    c.drawPath(cloud(0, -3), p);
                    p.setStyle(Paint.Style.FILL);
                    for (int i = 0; i < 3; i++) c.drawCircle(9.8f + i * 3f, 18f + (i % 2) * 1.8f, 0.9f, p);
                    p.setStyle(Paint.Style.STROKE);
                    break;
                case STORM: {
                    c.drawPath(cloud(0, -3), p);
                    Path z = new Path();
                    z.moveTo(13.6f, 15.2f);
                    z.lineTo(11.2f, 18.6f);
                    z.lineTo(13.4f, 18.6f);
                    z.lineTo(11.6f, 22f);
                    c.drawPath(z, p);
                    break;
                }
                case FOG:
                    for (int i = 0; i < 4; i++) {
                        float y = 7f + i * 3.5f;
                        c.drawLine(4f + (i % 2) * 2f, y, 20f - ((i + 1) % 2) * 2f, y, p);
                    }
                    break;
                default:
                    c.drawPath(cloud(0, 0), p);
            }
            c.restore();
        }
    }
}
