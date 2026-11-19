package app.lev.browser;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Date;
import java.util.List;
import java.util.function.Supplier;

/**
 * «Доп. настройки» по разделам: сначала список разделов, в каждом — свои настройки.
 */
public class SettingsActivity extends Activity {

    private static final int REQ_WALL = 41;
    private static final int REQ_MYICON = 42;

    private static final String[][] PAGES = {
            {"look", "Внешний вид", "Название, надпись, тема, шторка, значок"},
            {"home", "Начальный экран", "Фон, три поиска, погода, ярлыки сайтов"},
            {"tabs", "Вкладки и анимации", "Стопка или сетка, скорость, когда закрывать"},
            {"search", "Поиск и перевод", "Поисковики, перевод страниц"},
            {"privacy", "Защита и приватность", "Реклама, трекеры, cookie, режимы BySS"},
            {"media", "Видео и отклик", "YouTube в фоне, вибрация, звук"},
            {"about", "О браузере", "Сборка и обновление"},
    };
    private static final int[] PAGE_ICONS = {R.drawable.ic_theme, R.drawable.ic_home, R.drawable.ic_tabs,
            R.drawable.ic_search, R.drawable.ic_shield, R.drawable.ic_play, R.drawable.ic_info};

    private LinearLayout list;
    private String page;

    @Override
    protected void onCreate(Bundle state) {
        Prefs.init(this);
        SitePrefs.init(this);
        boolean dark = Prefs.dark();
        setTheme(dark ? R.style.Theme_Lev_Dark : R.style.Theme_Lev_Light);
        Ui.apply(dark);
        super.onCreate(state);
        page = getIntent().getStringExtra("page");
        build();
    }

    private String title() {
        if (page == null) return "Настройки";
        for (String[] p : PAGES) if (p[0].equals(page)) return p[1];
        return "Настройки";
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
        bar.addView(Ui.text(c, title(), 20, Ui.text, true), new LinearLayout.LayoutParams(-2, -2));
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
        if (page == null) {
            for (int i = 0; i < PAGES.length; i++) category(PAGE_ICONS[i], PAGES[i]);
            return;
        }
        switch (page) {
            case "look": look(); break;
            case "home": home(); break;
            case "tabs": tabs(); break;
            case "search": search(); break;
            case "privacy": privacy(); break;
            case "media": media(); break;
            default: about(); break;
        }
    }

    // ----------------------------------------------------------- разделы

    private void look() {
        section("Название");
        row("Название браузера", Prefs::browserName, this::editName);
        row("Шрифт надписи на главной", () -> BrandLabel.NAMES[Math.min(Prefs.brandFont(), BrandLabel.NAMES.length - 1)],
                this::pickBrandFont);
        toggle("Показывать надпись", "Вверху справа на начальном экране", "brand_show", true);

        section("Оформление");
        row("Тема", () -> Prefs.dark() ? "Тёмная" : "Светлая", () -> choose("Тема", new String[]{"Тёмная", "Светлая"},
                Prefs.dark() ? 0 : 1, w -> {
                    Prefs.put("dark", w == 0);
                    recreate();
                }));
        row("Шторка сверху (время, сеть, батарея)", () -> BAR_NAMES[Math.min(Prefs.statusBar(), 2)],
                () -> choose("Шторка сверху", BAR_NAMES, Prefs.statusBar(), w -> Prefs.put("status_bar", w)));

        section("Значок");
        row("Значок приложения", () -> Icons.currentName(this), () ->
                choose("Значок приложения", Icons.BUILT_IN, Icons.currentBuiltIn(this), w -> {
                    Icons.setBuiltIn(this, w);
                    Toast.makeText(this, "Значок поменяется через пару секунд. Если пропал с экрана — "
                            + "добавьте его снова из списка приложений", Toast.LENGTH_LONG).show();
                }));
        row("Мои значки", () -> {
            int n = Icons.mine(this).size();
            return n == 0 ? "Своя картинка из галереи — на чёрном фоне, белым или золотом"
                    : "Сохранено: " + n + " · нажмите, чтобы выбрать или добавить";
        }, this::myIcons);
    }

    private static final String[] BAR_NAMES = {"Как фон браузера", "Мягкая: цвет сайта, плавно",
            "Прозрачная: сливается с сайтом, сразу"};

    private void home() {
        section("Фон");
        row("Картинка", () -> HomeView.WALLPAPER_NAMES[Math.min(Math.max(0, Prefs.wallpaper()), HomeView.WALLPAPER_NAMES.length - 1)],
                this::pickWallpaper);

        section("Поиск на главной");
        row("Первый поиск", () -> SearchEngines.get(Prefs.home1()).name, () -> pickEngine("home1"));
        row("Второй поиск", () -> SearchEngines.get(Prefs.home2()).name, () -> pickEngine("home2"));
        row("Третья строка", () -> HomeView.FX.equals(Prefs.home3()) ? "Конвертер валют"
                : SearchEngines.get(Prefs.home3()).name, () -> pickEngine("home3"));

        section("Погода");
        toggle("Погода вверху", "Обновляется раз в 10 минут · данные Open-Meteo", WeatherView.K_ON, true);
        row("Первый город", () -> WeatherView.summary(1), () -> WeatherView.pickCity(this, 1, this::fill));
        row("Второй город", () -> WeatherView.summary(2), () -> WeatherView.pickCity(this, 2, this::fill));

        section("Ярлыки сайтов");
        row("Вернуть стандартные", () -> "До трёх рядов. Долгое нажатие на ярлык — изменить или удалить",
                () -> new AlertDialog.Builder(this).setTitle("Вернуть стандартные ярлыки?")
                        .setPositiveButton("Вернуть", (d, w) -> Shortcuts.save(Shortcuts.defaults()))
                        .setNegativeButton("Отмена", null).show());
    }

    private static final String[] TAB_STYLES = {"Стопка (как старый Android)", "Сетка 2 × N"};
    private static final String[] SPEEDS = {"Без анимаций", "Быстро", "Обычно", "Плавно"};

    private void tabs() {
        section("Вид");
        row("Как показывать вкладки", () -> TAB_STYLES[Math.min(Prefs.tabStyle(), 1)],
                () -> choose("Вкладки", TAB_STYLES, Prefs.tabStyle(), w -> Prefs.put("tab_style", w)));
        row("Скорость анимаций", () -> SPEEDS[Math.min(Prefs.animSpeed(), 3)],
                () -> choose("Анимации", SPEEDS, Prefs.animSpeed(), w -> Prefs.put("anim_speed", w)));

        section("Когда закрывать");
        row("Начальная страница после перерыва", () -> minutesName(Prefs.homeAfterMin()), this::pickHomeAfter);
        row("Закрывать неактивные вкладки", () -> daysName(Prefs.tabExpireDays()), this::pickExpire);
        row("Сколько вкладок", () -> "До " + Prefs.MAX_TABS + " в каждом режиме. Загружены 4 последние, "
                + "остальные «спят» и открываются заново. YouTube не засыпает.", null);
    }

    private void search() {
        section("Поиск");
        row("Поиск в адресной строке", () -> SearchEngines.get(Prefs.engine()).name, () -> pickEngine("engine"));
        row("Свой поисковик", () -> Prefs.customEngine().isEmpty() ? "не задан" : Prefs.customEngine(), this::editCustomEngine);

        section("Перевод страниц");
        row("Способ перевода", () -> translateModeName(Prefs.translateMode()), this::pickTranslateMode);
        row("Переводить на", () -> PageTranslator.targetName(Prefs.translateTarget()), this::pickTarget);
        toggle("Предлагать перевод", "Для сайтов не на русском и не на английском", "tr_offer", true);
    }

    private void privacy() {
        section("Реклама и слежка");
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

        section("Приватные режимы");
        row("Название режимов", Prefs::incogName, () -> editText("Название приватных режимов", Prefs.incogName(),
                "BySS", v -> Prefs.put("incog_name", v)));
        row("Сейчас режимов", () -> {
            int n = Spaces.all().size();
            return n == 0 ? "Нет. Создаются в меню ⋮ → «" + Prefs.incogName() + "»"
                    : n + " · удаляются вручную в меню ⋮ → «" + Prefs.incogName() + "»";
        }, null);
    }

    private void media() {
        section("Видео");
        toggle("YouTube в фоне", "Музыка играет при свёрнутом браузере, плеер в шторке", "bg_play", true);
        toggle("Убирать рекламу на YouTube", "Иногда YouTube это ломает — тогда нужно обновление", "yt_ads", true);

        section("Отклик");
        toggle("Вибрация", "Мягкий «тик» при листании вкладок и нажатиях", "haptics", true);
        toggle("Звук при листании вкладок", null, "sound", false);
    }

    private void about() {
        section(Prefs.browserName());
        row("Сборка " + Updater.currentBuild(this), () -> "Без истории, без рекламы, без лишнего. Перевод на телефоне — Google ML Kit.", null);
        row("Обновить браузер", () -> "Проверить новую сборку и установить", () -> Updater.check(this));
    }

    // ------------------------------------------------------------- строки

    private void category(int icon, String[] p) {
        Context c = this;
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(Ui.dp(c, 20), Ui.dp(c, 14), Ui.dp(c, 12), Ui.dp(c, 14));
        r.setBackground(Ui.ripple(null, Ui.round(0xFFFFFFFF, 0)));
        FrameLayout badge = new FrameLayout(c);
        badge.setBackground(Ui.round(Ui.pill, Ui.dp(c, 12)));
        ImageView ic = new ImageView(c);
        ic.setImageResource(icon);
        ic.setImageTintList(ColorStateList.valueOf(Ui.text));
        badge.addView(ic, new FrameLayout.LayoutParams(Ui.dp(c, 22), Ui.dp(c, 22), Gravity.CENTER));
        r.addView(badge, new LinearLayout.LayoutParams(Ui.dp(c, 40), Ui.dp(c, 40)));
        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(Ui.dp(c, 16), 0, Ui.dp(c, 8), 0);
        texts.addView(Ui.text(c, p[1], 16, Ui.text, false));
        TextView st = Ui.text(c, p[2], 13, Ui.text2, false);
        st.setPadding(0, Ui.dp(c, 2), 0, 0);
        texts.addView(st);
        r.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
        ImageView chev = new ImageView(c);
        chev.setImageResource(R.drawable.ic_chevron);
        chev.setImageTintList(ColorStateList.valueOf(Ui.text2));
        r.addView(chev, new LinearLayout.LayoutParams(Ui.dp(c, 20), Ui.dp(c, 20)));
        r.setOnClickListener(v -> {
            Haptics.click(v);
            startActivity(new Intent(this, SettingsActivity.class).putExtra("page", p[0]));
        });
        list.addView(r, new LinearLayout.LayoutParams(-1, -2));
    }

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

    private void editText(String title, String value, String hint, java.util.function.Consumer<String> save) {
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setHint(hint);
        e.setText(value);
        e.setSelectAllOnFocus(true);
        int p = Ui.dp(this, 22);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(p, Ui.dp(this, 8), p, 0);
        box.addView(e);
        new AlertDialog.Builder(this).setTitle(title).setView(box)
                .setPositiveButton("Сохранить", (d, w) -> {
                    save.accept(e.getText().toString().trim());
                    fill();
                })
                .setNegativeButton("Отмена", null).show();
    }

    private void editName() {
        editText("Название браузера", Prefs.browserName(), "BySS", v -> {
            Prefs.put("name", v);
            Toast.makeText(this, "Готово. Подпись под значком в списке приложений Android менять не даёт — "
                    + "а у ярлыков из «Мои значки» будет новое название", Toast.LENGTH_LONG).show();
        });
    }

    /** Выбор шрифта надписи — с живыми образцами. */
    private void pickBrandFont() {
        Context c = this;
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, Ui.dp(c, 4), 0, Ui.dp(c, 4));
        AlertDialog[] dlg = new AlertDialog[1];
        for (int i = 0; i < BrandLabel.NAMES.length; i++) {
            int idx = i;
            LinearLayout r = new LinearLayout(c);
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(Ui.dp(c, 22), Ui.dp(c, 8), Ui.dp(c, 16), Ui.dp(c, 8));
            r.setBackground(Ui.ripple(i == Prefs.brandFont() ? Ui.round(Ui.pill, Ui.dp(c, 14)) : null,
                    Ui.round(0xFFFFFFFF, Ui.dp(c, 14))));
            TextView name = Ui.text(c, (i + 1) + " · " + BrandLabel.NAMES[i], 13, Ui.text2, false);
            r.addView(name, new LinearLayout.LayoutParams(Ui.dp(c, 130), -2));
            BrandLabel l = new BrandLabel(c);
            l.setStyle(i);
            l.setSizeScale(1.25f);
            l.setText(Prefs.browserName());
            r.addView(l, new LinearLayout.LayoutParams(-2, -2));
            r.setOnClickListener(v -> {
                Haptics.click(v);
                Prefs.put("brand_font", idx);
                dlg[0].dismiss();
                fill();
            });
            box.addView(r, new LinearLayout.LayoutParams(-1, -2));
        }
        ScrollView sv = new ScrollView(c);
        sv.addView(box);
        dlg[0] = new AlertDialog.Builder(this).setTitle("Шрифт надписи").setView(sv)
                .setNegativeButton("Отмена", null).show();
    }

    private void pickEngine(String key) {
        List<SearchEngines.Engine> all = SearchEngines.all();
        String def = key.equals("home2") ? "yandex" : key.equals("home3") ? HomeView.FX : "google";
        String cur = Prefs.sp().getString(key, def);
        java.util.List<String> ids = new java.util.ArrayList<>();
        java.util.List<String> labels = new java.util.ArrayList<>();
        if (key.equals("home3")) {
            ids.add(HomeView.FX);
            labels.add("Конвертер валют");
        }
        for (SearchEngines.Engine e : all) {
            ids.add(e.id);
            labels.add(e.name);
        }
        int checked = Math.max(0, ids.indexOf(cur));
        choose(key.equals("home3") ? "Третья строка на главной" : "Поисковая система",
                labels.toArray(new String[0]), checked, w -> Prefs.put(key, ids.get(w)));
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
        int cur = Math.max(0, Math.min(Prefs.wallpaper(), HomeView.WALLPAPER_NAMES.length - 1));
        new AlertDialog.Builder(this).setTitle("Фон начальной страницы")
                .setSingleChoiceItems(HomeView.WALLPAPER_NAMES, cur, (d, w) -> {
                    d.dismiss();
                    if (w == HomeView.CUSTOM) {
                        pickImage(REQ_WALL, "Картинка для фона");
                    } else {
                        Prefs.put("wallpaper", w);
                        fill();
                    }
                })
                .setNegativeButton("Отмена", null).show();
    }

    private void pickImage(int req, String title) {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(i, title), req);
        } catch (Exception e) {
            Toast.makeText(this, "Нет приложения для выбора картинки", Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------- мои значки

    private void myIcons() {
        Context c = this;
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(c, 18), Ui.dp(c, 4), Ui.dp(c, 18), Ui.dp(c, 4));
        AlertDialog[] dlg = new AlertDialog[1];
        List<File> files = Icons.mine(this);
        LinearLayout row = null;
        for (int i = 0; i < files.size(); i++) {
            if (i % 3 == 0) {
                row = new LinearLayout(c);
                row.setOrientation(LinearLayout.HORIZONTAL);
                box.addView(row, new LinearLayout.LayoutParams(-1, -2));
            }
            File f = files.get(i);
            ImageView iv = new ImageView(c);
            Bitmap b = Icons.load(f);
            iv.setImageBitmap(b);
            iv.setBackground(Ui.round(0xFF000000, Ui.dp(c, 18)));
            iv.setClipToOutline(true);
            int pd = Ui.dp(c, 2);
            iv.setPadding(pd, pd, pd, pd);
            iv.setOnClickListener(v -> {
                dlg[0].dismiss();
                if (b != null) Icons.pin(this, b, f);
            });
            iv.setOnLongClickListener(v -> {
                dlg[0].dismiss();
                new AlertDialog.Builder(this).setTitle("Удалить этот значок?")
                        .setMessage("Из списка «Мои значки». Ярлык на главном экране, если он есть, останется.")
                        .setPositiveButton("Удалить", (d, w) -> {
                            //noinspection ResultOfMethodCallIgnored
                            f.delete();
                            fill();
                        })
                        .setNegativeButton("Отмена", null).show();
                return true;
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(c, 88), 1f);
            lp.setMargins(Ui.dp(c, 4), Ui.dp(c, 4), Ui.dp(c, 4), Ui.dp(c, 4));
            row.addView(iv, lp);
        }
        if (row != null) {
            for (int k = files.size() % 3; k != 0 && k < 3; k++) row.addView(new View(c), new LinearLayout.LayoutParams(0, 1, 1f));
        }
        TextView hint = Ui.text(c, files.isEmpty()
                ? "Выберите картинку из галереи — она встанет на чёрный фон, тёмный рисунок станет белым или золотым."
                : "Нажмите на значок — поставить на главный экран. Долгое нажатие — удалить.", 13, Ui.text2, false);
        hint.setPadding(Ui.dp(c, 4), Ui.dp(c, 8), Ui.dp(c, 4), 0);
        box.addView(hint);
        ScrollView sv = new ScrollView(c);
        sv.addView(box);
        dlg[0] = new AlertDialog.Builder(this).setTitle("Мои значки").setView(sv)
                .setPositiveButton("Добавить", (d, w) -> pickImage(REQ_MYICON, "Картинка для значка"))
                .setNegativeButton("Закрыть", null).show();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        Bitmap src = Icons.decode(this, uri);
        if (src == null) {
            Toast.makeText(this, "Не получилось открыть картинку", Toast.LENGTH_SHORT).show();
            return;
        }
        if (req == REQ_WALL) {
            saveWallpaper(src);
        } else if (req == REQ_MYICON || req == BrowserActivity.REQ_ICON) {
            new AlertDialog.Builder(this).setTitle("Каким сделать значок?")
                    .setItems(new String[]{"Белый на чёрном", "Золотой на чёрном", "Как есть, на чёрном"}, (d, w) -> {
                        Bitmap icon = Icons.make(src, w);
                        File f = Icons.save(this, icon);
                        fill();
                        if (f == null) {
                            Toast.makeText(this, "Не получилось сохранить значок", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        new AlertDialog.Builder(this).setTitle("Значок сохранён")
                                .setMessage("Поставить его на главный экран сейчас?")
                                .setPositiveButton("Поставить", (d2, w2) -> Icons.pin(this, icon, f))
                                .setNegativeButton("Позже", null).show();
                    })
                    .setNegativeButton("Отмена", null).show();
        }
    }

    private void saveWallpaper(Bitmap src) {
        try {
            int max = 1600;
            Bitmap b = src;
            if (Math.max(b.getWidth(), b.getHeight()) > max) {
                float s = max / (float) Math.max(b.getWidth(), b.getHeight());
                b = Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * s), Math.round(b.getHeight() * s), true);
            }
            try (FileOutputStream out = new FileOutputStream(HomeView.customWallpaper(this))) {
                b.compress(Bitmap.CompressFormat.JPEG, 90, out);
            }
            Prefs.put("wallpaper", HomeView.CUSTOM);
            Toast.makeText(this, "Фон поставлен", Toast.LENGTH_SHORT).show();
            fill();
        } catch (Exception e) {
            Toast.makeText(this, "Не получилось сохранить фон", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (list != null) fill();
    }
}
