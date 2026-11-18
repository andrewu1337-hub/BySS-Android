package app.lev.browser;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.util.Date;
import java.util.List;
import java.util.function.Supplier;

/** «Доп. настройки». */
public class SettingsActivity extends Activity {

    private LinearLayout list;

    @Override
    protected void onCreate(Bundle state) {
        Prefs.init(this);
        SitePrefs.init(this);
        boolean dark = Prefs.dark();
        setTheme(dark ? R.style.Theme_Lev_Dark : R.style.Theme_Lev_Light);
        Ui.apply(dark);
        super.onCreate(state);
        build();
    }

    private void build() {
        Context c = this;
        LinearLayout rootCol = new LinearLayout(c);
        rootCol.setOrientation(LinearLayout.VERTICAL);
        rootCol.setBackgroundColor(Ui.bg);

        LinearLayout bar = new LinearLayout(c);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(c, 4), Ui.dp(c, 6), Ui.dp(c, 16), Ui.dp(c, 6));
        ImageButton back = Ui.iconButton(c, R.drawable.ic_back);
        back.setOnClickListener(v -> finish());
        bar.addView(back, new LinearLayout.LayoutParams(Ui.dp(c, 48), Ui.dp(c, 48)));
        bar.addView(Ui.text(c, "Настройки", 20, Ui.text, true), new LinearLayout.LayoutParams(-2, -2));
        rootCol.addView(bar);

        ScrollView sv = new ScrollView(c);
        list = new LinearLayout(c);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, 0, 0, Ui.dp(c, 32));
        sv.addView(list);
        rootCol.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(rootCol);
        fill();
    }

    private void fill() {
        list.removeAllViews();

        section("Поиск");
        row("Поиск в адресной строке", () -> SearchEngines.get(Prefs.engine()).name, () -> pickEngine("engine"));
        row("Первый поиск на главной", () -> SearchEngines.get(Prefs.home1()).name, () -> pickEngine("home1"));
        row("Второй поиск на главной", () -> SearchEngines.get(Prefs.home2()).name, () -> pickEngine("home2"));
        row("Свой поисковик", () -> Prefs.customEngine().isEmpty() ? "не задан" : Prefs.customEngine(), this::editCustomEngine);

        section("Перевод страниц");
        row("Способ перевода", () -> translateModeName(Prefs.translateMode()), this::pickTranslateMode);
        row("Переводить на", () -> PageTranslator.targetName(Prefs.translateTarget()), this::pickTarget);
        toggle("Предлагать перевод", "Для сайтов не на русском и не на английском", "tr_offer", true);

        section("Защита");
        toggle("Блокировать рекламу и трекеры", "Списки EasyList, EasyPrivacy, RuAdList и др.", "adblock", true);
        toggle("Спрашивать на новых сайтах", "Блокировать или разрешить трекеры на сайте", "ask_sites", true);
        toggle("Сторонние cookie", "Выключено — меньше слежки между сайтами", "cookies_3p", false);
        row("Решения по сайтам", () -> "Запомнено: " + SitePrefs.count() + " · нажмите, чтобы сбросить", () ->
                new AlertDialog.Builder(this).setTitle("Сбросить решения по сайтам?")
                        .setPositiveButton("Сбросить", (d, w) -> {
                            SitePrefs.clear();
                            fill();
                        })
                        .setNegativeButton("Отмена", null).show());
        row("Списки блокировки", this::filtersInfo, () -> {
            Toast.makeText(this, "Обновляю списки…", Toast.LENGTH_SHORT).show();
            AdBlocker.updateNow(this, () -> runOnUiThread(() -> {
                Toast.makeText(this, "Готово: правил " + AdBlocker.ruleCount(), Toast.LENGTH_SHORT).show();
                fill();
            }));
        });

        section("Вкладки");
        row("Начальная страница после перерыва", () -> minutesName(Prefs.homeAfterMin()), this::pickHomeAfter);
        row("Закрывать неактивные вкладки", () -> daysName(Prefs.tabExpireDays()), this::pickExpire);

        section("Начальный экран");
        toggle("Погода вверху", "Данные Open-Meteo, без регистрации", WeatherView.K_ON, true);
        row("Город для погоды", WeatherView::summary, () -> WeatherView.pickCity(this, this::recreate));
        row("Фон", () -> HomeView.WALLPAPER_NAMES[Math.min(Prefs.wallpaper(), HomeView.WALLPAPER_NAMES.length - 1)],
                this::pickWallpaper);
        row("Ярлыки сайтов", () -> "Долгое нажатие на ярлык — изменить или удалить. Нажмите, чтобы вернуть стандартные",
                () -> new AlertDialog.Builder(this).setTitle("Вернуть стандартные ярлыки?")
                        .setPositiveButton("Вернуть", (d, w) -> Shortcuts.save(Shortcuts.defaults()))
                        .setNegativeButton("Отмена", null).show());

        section("Иконка");
        row("Своя картинка на главный экран", () -> "Выберите любую картинку — появится значок «Лев» с ней",
                this::pickIconImage);

        section("Видео");
        toggle("YouTube в фоне", "Музыка играет при свёрнутом браузере, плеер в шторке", "bg_play", true);
        toggle("Убирать рекламу на YouTube", "Иногда YouTube это ломает — тогда нужно обновление", "yt_ads", true);

        section("Отклик");
        toggle("Вибрация", "Мягкий «тик» при листании вкладок и нажатиях", "haptics", true);
        toggle("Звук при листании вкладок", null, "sound", false);

        section("О браузере");
        row("Лев, сборка " + Updater.currentBuild(this), () -> "Без истории, без рекламы, без лишнего. Перевод на телефоне — Google ML Kit.", null);
    }

    // ------------------------------------------------------------- строки

    private void section(String title) {
        Context c = this;
        TextView t = Ui.text(c, title.toUpperCase(), 12, Ui.text2, true);
        t.setLetterSpacing(0.06f);
        t.setPadding(Ui.dp(c, 20), Ui.dp(c, 22), Ui.dp(c, 20), Ui.dp(c, 6));
        list.addView(t);
    }

    private void row(String title, Supplier<String> subtitle, Runnable click) {
        Context c = this;
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(Ui.dp(c, 20), Ui.dp(c, 12), Ui.dp(c, 20), Ui.dp(c, 12));
        r.setBackground(Ui.ripple(null, Ui.round(0xFFFFFFFF, 0)));
        r.addView(Ui.text(c, title, 16, Ui.text, false));
        if (subtitle != null) {
            String s = subtitle.get();
            if (s != null && !s.isEmpty()) {
                TextView st = Ui.text(c, s, 13, Ui.text2, false);
                st.setPadding(0, Ui.dp(c, 2), 0, 0);
                r.addView(st);
            }
        }
        if (click != null) {
            r.setOnClickListener(v -> {
                Haptics.click(v);
                click.run();
            });
        }
        list.addView(r, new LinearLayout.LayoutParams(-1, -2));
    }

    private void toggle(String title, String subtitle, String key, boolean def) {
        Context c = this;
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(Ui.dp(c, 20), Ui.dp(c, 10), Ui.dp(c, 16), Ui.dp(c, 10));
        r.setBackground(Ui.ripple(null, Ui.round(0xFFFFFFFF, 0)));
        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(Ui.text(c, title, 16, Ui.text, false));
        if (subtitle != null) {
            TextView st = Ui.text(c, subtitle, 13, Ui.text2, false);
            st.setPadding(0, Ui.dp(c, 2), 0, 0);
            texts.addView(st);
        }
        r.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
        Switch sw = new Switch(c);
        sw.setChecked(Prefs.sp().getBoolean(key, def));
        sw.setThumbTintList(ColorStateList.valueOf(Ui.dark ? 0xFFF2F2F7 : 0xFFFFFFFF));
        sw.setTrackTintList(new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{Ui.dark ? 0xFF8E8E93 : 0xFF111113, Ui.dark ? 0xFF3A3A3C : 0xFFCFCFD4}));
        sw.setOnCheckedChangeListener((b, on) -> {
            Haptics.click(b);
            Prefs.put(key, on);
        });
        r.addView(sw);
        r.setOnClickListener(v -> sw.toggle());
        list.addView(r, new LinearLayout.LayoutParams(-1, -2));
    }

    // -------------------------------------------------------------- выбор

    private void choose(String title, String[] names, int checked, java.util.function.IntConsumer onPick) {
        new AlertDialog.Builder(this).setTitle(title)
                .setSingleChoiceItems(names, checked, (d, w) -> {
                    onPick.accept(w);
                    d.dismiss();
                    fill();
                })
                .setNegativeButton("Отмена", null).show();
    }

    private void pickEngine(String key) {
        List<SearchEngines.Engine> all = SearchEngines.all();
        String cur = Prefs.sp().getString(key, key.equals("home2") ? "yandex" : "google");
        String[] names = new String[all.size()];
        int checked = 0;
        for (int i = 0; i < all.size(); i++) {
            names[i] = all.get(i).name;
            if (all.get(i).id.equals(cur)) checked = i;
        }
        choose("Поисковая система", names, checked, w -> Prefs.put(key, all.get(w).id));
    }

    private void editCustomEngine() {
        EditText e = new EditText(this);
        e.setHint("https://example.com/search?q=%s");
        e.setText(Prefs.customEngine());
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        int p = Ui.dp(this, 20);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(p, Ui.dp(this, 8), p, 0);
        box.addView(e, new LinearLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(this).setTitle("Свой поисковик")
                .setMessage("Адрес поиска, где вместо запроса стоит %s")
                .setView(box)
                .setPositiveButton("Сохранить", (d, w) -> {
                    String t = e.getText().toString().trim();
                    if (!t.isEmpty() && !t.contains("%s")) {
                        Toast.makeText(this, "Нужен %s в адресе", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Prefs.put("custom_engine", t);
                    fill();
                })
                .setNegativeButton("Отмена", null).show();
    }

    private static String translateModeName(String m) {
        if ("google".equals(m)) return "Через сайт Google Переводчика";
        if ("yandex".equals(m)) return "Через сайт Яндекс Переводчика";
        return "На телефоне (Google ML Kit, без интернета)";
    }

    private void pickTranslateMode() {
        String[] modes = {"device", "google", "yandex"};
        String[] names = {translateModeName("device"), translateModeName("google"), translateModeName("yandex")};
        int checked = 0;
        for (int i = 0; i < modes.length; i++) if (modes[i].equals(Prefs.translateMode())) checked = i;
        choose("Способ перевода", names, checked, w -> Prefs.put("tr_mode", modes[w]));
    }

    private void pickTarget() {
        String[][] t = PageTranslator.TARGETS;
        String[] names = new String[t.length];
        int checked = 0;
        for (int i = 0; i < t.length; i++) {
            names[i] = t[i][1];
            if (t[i][0].equals(Prefs.translateTarget())) checked = i;
        }
        choose("Переводить на", names, checked, w -> Prefs.put("tr_target", t[w][0]));
    }

    private String filtersInfo() {
        long t = Prefs.filtersUpdated();
        String when = t == 0 ? "списки из приложения" : "обновлено " + DateFormat.format("dd.MM.yyyy", new Date(t));
        return "Правил: " + AdBlocker.ruleCount() + " · " + when + " · нажмите, чтобы обновить";
    }

    private static final int[] MINUTES = {5, 10, 15, 30, 60, 0};

    private static String minutesName(int m) {
        return m <= 0 ? "Никогда" : "Через " + m + " мин";
    }

    private void pickHomeAfter() {
        String[] names = new String[MINUTES.length];
        int checked = 2;
        for (int i = 0; i < MINUTES.length; i++) {
            names[i] = minutesName(MINUTES[i]);
            if (MINUTES[i] == Prefs.homeAfterMin()) checked = i;
        }
        choose("Если не открывать браузер", names, checked, w -> Prefs.put("home_after_min", MINUTES[w]));
    }

    private static final int[] DAYS = {1, 3, 7, 14, 30, 0};

    private static String daysName(int d) {
        if (d <= 0) return "Никогда";
        if (d == 1) return "Через 1 день";
        if (d < 5) return "Через " + d + " дня";
        return "Через " + d + " дней";
    }

    private void pickExpire() {
        String[] names = new String[DAYS.length];
        int checked = 2;
        for (int i = 0; i < DAYS.length; i++) {
            names[i] = daysName(DAYS[i]);
            if (DAYS[i] == Prefs.tabExpireDays()) checked = i;
        }
        choose("Если не заходить во вкладку", names, checked, w -> Prefs.put("tab_expire_days", DAYS[w]));
    }

    private void pickWallpaper() {
        choose("Фон начальной страницы", HomeView.WALLPAPER_NAMES, Prefs.wallpaper(), w -> Prefs.put("wallpaper", w));
    }

    // ------------------------------------------------------- своя иконка

    private void pickIconImage() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(i, "Картинка для значка"), BrowserActivity.REQ_ICON);
        } catch (Exception e) {
            Toast.makeText(this, "Нет приложения для выбора картинки", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != BrowserActivity.REQ_ICON || res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        Bitmap src = decode(uri);
        if (src == null) {
            Toast.makeText(this, "Не получилось открыть картинку", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this).setTitle("Значок на главный экран")
                .setMessage("Картинка будет на чёрном фоне. Если она тёмная на белом — можно инвертировать, "
                        + "чтобы получилось светлое на чёрном.")
                .setPositiveButton("Как есть", (d, w) -> pin(src, false))
                .setNeutralButton("Инвертировать", (d, w) -> pin(src, true))
                .setNegativeButton("Отмена", null).show();
    }

    private Bitmap decode(Uri uri) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, o);
            }
            int sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / sample > 1024) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                return BitmapFactory.decodeStream(in, null, o2);
            }
        } catch (Exception e) {
            return null;
        }
    }

    private void pin(Bitmap src, boolean invert) {
        int size = 432; // 108dp при 4x — размер адаптивного значка
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        canvas.drawColor(0xFF000000);
        float box = size * 0.62f;
        float scale = Math.min(box / src.getWidth(), box / src.getHeight());
        float w = src.getWidth() * scale;
        float h = src.getHeight() * scale;
        RectF dst = new RectF((size - w) / 2f, (size - h) / 2f, (size + w) / 2f, (size + h) / 2f);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        if (invert) {
            p.setColorFilter(new ColorMatrixColorFilter(new ColorMatrix(new float[]{
                    -1, 0, 0, 0, 255,
                    0, -1, 0, 0, 255,
                    0, 0, -1, 0, 255,
                    0, 0, 0, 1, 0})));
        }
        canvas.drawBitmap(src, null, dst, p);

        ShortcutManager sm = getSystemService(ShortcutManager.class);
        if (sm == null || !sm.isRequestPinShortcutSupported()) {
            Toast.makeText(this, "Ваш экран «Домой» не поддерживает значки приложений", Toast.LENGTH_LONG).show();
            return;
        }
        Intent launch = new Intent(this, BrowserActivity.class).setAction(Intent.ACTION_MAIN);
        ShortcutInfo si = new ShortcutInfo.Builder(this, "lev_icon_" + System.currentTimeMillis())
                .setShortLabel(getString(R.string.app_name))
                .setIcon(Icon.createWithAdaptiveBitmap(out))
                .setIntent(launch)
                .build();
        sm.requestPinShortcut(si, null);
        Toast.makeText(this, "Подтвердите добавление значка. Старый значок можно убрать с экрана — "
                + "приложение останется в списке приложений.", Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (list != null) fill();
    }

    @SuppressWarnings("unused")
    private static void noop(View v) { }
}
