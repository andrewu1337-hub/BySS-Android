package app.lev.browser;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.List;

/**
 * Начальная страница: фон, вверху две погоды и надпись «BySS» справа,
 * три поиска выше середины и до трёх рядов любимых сайтов с цветными значками.
 * Долгое нажатие на ярлык — изменить или удалить, «+» — добавить.
 */
@SuppressLint("ViewConstructor")
final class HomeView extends FrameLayout {

    interface Listener {
        void onOpen(String url);
        void onSearch(String engineId, String query);
        void onPickEngine(int slot);
    }

    static final int[] WALLPAPERS = {
            R.drawable.wp_mountains, R.drawable.wp_dunes, R.drawable.wp_waves, R.drawable.wp_rings,
            R.drawable.wp_moon, R.drawable.wp_fog, R.drawable.wp_lines, R.drawable.wp_dots,
            R.drawable.wp_horizon, R.drawable.wp_stars, R.drawable.wp_prism, 0, -1};
    static final String[] WALLPAPER_NAMES = {
            "Горы", "Дюны", "Волны", "Круги", "Луна", "Туман", "Линии", "Точки", "Горизонт", "Звёзды", "Грани",
            "Просто чёрный", "Своя картинка…"};
    /** Номер пункта «Своя картинка». */
    static final int CUSTOM = WALLPAPERS.length - 1;

    private static final int COLS = 4;
    private static final int MAX_CELLS = COLS * 3; // не больше трёх рядов

    private final Listener listener;
    private final ImageView wall;
    private final View shade;
    private final LinearLayout col;
    private final LinearLayout grid;
    private final android.widget.ImageButton plus;
    private final TextView[] badges = new TextView[3];
    private final EditText[] fields = new EditText[3];
    private final WeatherView weather1, weather2;
    private final BrandLabel brand;
    private final ImageView brandShield;
    private List<Shortcuts.Item> items;
    private String spaceName;
    private int topInset;
    private int brandStyle = -1;

    HomeView(Context c, Listener l) {
        super(c);
        listener = l;
        wall = new ImageView(c);
        wall.setScaleType(ImageView.ScaleType.CENTER_CROP);
        addView(wall, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        shade = new View(c);
        addView(shade, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        int side = Ui.dp(c, 22);
        col.setPadding(side, 0, side, 0);
        addView(col, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        // Верх: погода слева, надпись справа
        LinearLayout top = new LinearLayout(c);
        top.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout weathers = new LinearLayout(c);
        weathers.setOrientation(LinearLayout.VERTICAL);
        weather1 = new WeatherView(c, 1);
        weather2 = new WeatherView(c, 2);
        weathers.addView(weather1, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams w2 = new LinearLayout.LayoutParams(-2, -2);
        w2.topMargin = Ui.dp(c, 16); // чуть больше воздуха между двумя городами
        weathers.addView(weather2, w2);
        top.addView(weathers, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout brandBox = new LinearLayout(c);
        brandBox.setOrientation(LinearLayout.HORIZONTAL);
        brandBox.setGravity(Gravity.CENTER_VERTICAL);
        brandShield = new ImageView(c);
        brandShield.setImageResource(R.drawable.ic_shield);
        brandShield.setVisibility(GONE);
        brandBox.addView(brandShield, new LinearLayout.LayoutParams(Ui.dp(c, 18), Ui.dp(c, 18)));
        brand = new BrandLabel(c);
        brandBox.addView(brand, new LinearLayout.LayoutParams(-2, -2));
        top.addView(brandBox, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
        tlp.topMargin = Ui.dp(c, 18);
        col.addView(top, tlp);

        col.addView(new View(c), new LinearLayout.LayoutParams(1, 0, 0.2f));

        for (int i = 0; i < 3; i++) {
            LinearLayout row = searchRow(c);
            badges[i] = (TextView) row.getChildAt(0);
            fields[i] = (EditText) row.getChildAt(1);
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
            if (i > 0) rp.topMargin = Ui.dp(c, i == 2 ? 12 : 10);
            col.addView(row, rp);
            wire(badges[i], fields[i], i + 1);
        }

        // Под поиском: сайты и «+» под ними — одним блоком ровно посередине между поиском
        // и нижней строкой. Сколько бы ни было рядов сайтов (1–3), блок всегда по центру.
        plus = Ui.iconButton(c, R.drawable.ic_plus);
        Ui.heartButton(plus);
        plus.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int ip = Ui.dp(c, 14);
        plus.setPadding(ip, ip, ip, ip);
        plus.setContentDescription("Добавить сайт");
        plus.setOnClickListener(v -> {
            Haptics.click(v);
            Ui.heart(v);
            edit(-1);
        });
        LinearLayout block = new LinearLayout(c);
        block.setOrientation(LinearLayout.VERTICAL);
        grid = new LinearLayout(c);
        grid.setOrientation(LinearLayout.VERTICAL);
        block.addView(grid, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(Ui.dp(c, 54), Ui.dp(c, 54));
        plp.gravity = Gravity.CENTER_HORIZONTAL;
        plp.topMargin = Ui.dp(c, 4);
        block.addView(plus, plp);
        FrameLayout lower = new Lower(c);
        lower.addView(block, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        col.addView(lower, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        refresh();
    }

    /** Место под поиском: блок «сайты + плюс» — ровно по центру по высоте. */
    private final class Lower extends FrameLayout {
        Lower(Context c) {
            super(c);
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            if (getChildCount() == 0) return;
            View v = getChildAt(0);
            int h = b - t, bh = v.getMeasuredHeight(), bw = v.getMeasuredWidth();
            int top = Math.max(0, (h - bh) / 2);
            int left = (r - l - bw) / 2;
            v.layout(left, top, left + bw, top + bh);
        }
    }

    // Погода обновляется раз в 10 минут, пока начальный экран на виду
    private final Runnable weatherTick = new Runnable() {
        @Override
        public void run() {
            weather1.refreshIfStale();
            weather2.refreshIfStale();
            postDelayed(this, 60_000);
        }
    };

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        removeCallbacks(weatherTick);
        postDelayed(weatherTick, 60_000);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(weatherTick);
        super.onDetachedFromWindow();
    }

    /** Отступ сверху под прозрачную шторку. */
    void setTopInset(int px) {
        if (px == topInset) return;
        topInset = px;
        col.setPadding(col.getPaddingLeft(), px, col.getPaddingRight(), 0);
    }

    /** В режиме BySS вместо названия браузера — название режима и щит. */
    void setSpace(String name) {
        spaceName = name;
        refreshBrand();
    }

    private void refreshBrand() {
        brand.setVisibility(Prefs.brandShow() || spaceName != null ? VISIBLE : GONE);
        int style = Prefs.brandFont();
        if (style != brandStyle) {
            brand.setStyle(style);
            brandStyle = style;
        }
        brand.setText(spaceName != null ? spaceName : Prefs.browserName());
        brandShield.setVisibility(spaceName != null ? VISIBLE : GONE);
        brandShield.setImageTintList(android.content.res.ColorStateList.valueOf(Ui.text));
    }

    private LinearLayout searchRow(Context c) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int h = Ui.dp(c, 50);
        row.setMinimumHeight(h);
        row.setPadding(Ui.dp(c, 8), 0, Ui.dp(c, 14), 0);
        TextView badge = new TextView(c);
        badge.setGravity(Gravity.CENTER);
        badge.setTextSize(15);
        badge.setIncludeFontPadding(false);
        row.addView(badge, new LinearLayout.LayoutParams(Ui.dp(c, 34), Ui.dp(c, 34)));
        EditText e = new EditText(c);
        e.setSingleLine(true);
        e.setBackground(null);
        e.setTextSize(16);
        e.setPadding(Ui.dp(c, 12), 0, 0, 0);
        e.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        e.setInputType(InputType.TYPE_CLASS_TEXT);
        e.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        row.addView(e, new LinearLayout.LayoutParams(0, h, 1f));
        return row;
    }

    private static String engineFor(int slot) {
        return slot == 1 ? Prefs.home1() : slot == 2 ? Prefs.home2() : Prefs.home3();
    }

    /** Третья строка — конвертер валют (вместо третьего поиска). */
    static final String FX = "currency";

    private static boolean isFx(int slot) {
        return slot == 3 && FX.equals(Prefs.home3());
    }

    private void openFx() {
        FxDialog.show(getContext(), listener::onOpen, () -> {
            if (isFx(3)) styleFx(badges[2], fields[2]);
        });
    }

    private void wire(TextView badge, EditText field, int slot) {
        badge.setOnClickListener(v -> {
            Haptics.click(v);
            if (isFx(slot)) openFx();
            else listener.onPickEngine(slot);
        });
        badge.setOnLongClickListener(v -> {
            Haptics.thud(v);
            listener.onPickEngine(slot);
            return true;
        });
        field.setOnClickListener(v -> {
            if (isFx(slot)) {
                Haptics.click(v);
                openFx();
            }
        });
        field.setOnEditorActionListener((v, actionId, event) -> {
            boolean go = actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_GO
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN);
            if (!go) return false;
            String q = field.getText().toString().trim();
            if (q.isEmpty()) return true;
            hideKeyboard(field);
            field.setText("");
            listener.onSearch(engineFor(slot), q);
            return true;
        });
    }

    private static void hideKeyboard(View v) {
        InputMethodManager imm = v.getContext().getSystemService(InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
        v.clearFocus();
    }

    /** Файл своей картинки для фона. */
    static File customWallpaper(Context c) {
        return new File(c.getFilesDir(), "wallpaper.jpg");
    }

    /** Перерисовать: тема, фон, погода, поисковики и ярлыки. */
    void refresh() {
        Context c = getContext();
        setBackgroundColor(Ui.bg);
        weather1.refresh();
        weather2.refresh();
        weather2.setVisibility(WeatherView.enabled() ? VISIBLE : GONE);
        refreshBrand();
        int w = Prefs.wallpaper();
        if (w == CUSTOM && customWallpaper(c).exists()) {
            Bitmap b = BitmapFactory.decodeFile(customWallpaper(c).getAbsolutePath());
            wall.setImageBitmap(b);
            wall.setVisibility(b != null ? VISIBLE : GONE);
            wall.setAlpha(1f);
        } else {
            int res = (w >= 0 && w < WALLPAPERS.length) ? WALLPAPERS[w] : 0;
            if (res > 0) {
                wall.setImageResource(res);
                wall.setVisibility(VISIBLE);
                wall.setAlpha(Ui.dark ? 1f : 0.18f);
            } else {
                wall.setImageDrawable(null);
                wall.setVisibility(GONE);
            }
        }
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Ui.dark ? 0x33000000 : 0x00FFFFFF, Ui.dark ? 0x66000000 : 0x00FFFFFF});
        shade.setBackground(g);

        for (int i = 0; i < 3; i++) {
            if (isFx(i + 1)) styleFx(badges[i], fields[i]);
            else styleSearch(badges[i], fields[i], SearchEngines.get(engineFor(i + 1)));
        }
        if (isFx(3)) Fx.refresh(false, ok -> {
            if (isFx(3)) styleFx(badges[2], fields[2]);
        });
        buildGrid(c);
    }

    /** Строка конвертера: «1 € = 19,62 MDL · 1,05 USD», нажатие — окно конвертера. */
    private void styleFx(TextView badge, EditText field) {
        Context c = getContext();
        ViewGroup row = (ViewGroup) badge.getParent();
        row.setBackground(Ui.round(Ui.dark ? 0xD91C1C1E : 0xF2EBEBEF, Ui.dp(c, 25)));
        row.setElevation(Ui.dp(c, 3));
        String b = Fx.base();
        badge.setText(Fx.symbol(b));
        badge.setTextColor(Ui.tileGlyph);
        badge.setTextSize(Fx.symbol(b).length() > 1 ? 12 : 16);
        badge.setBackground(Ui.ripple(Ui.round(Ui.dark ? 0xFF2C2C2E : 0xFFDCDCE1, Ui.dp(c, 17)), null));
        field.setFocusable(false);
        field.setFocusableInTouchMode(false);
        field.setCursorVisible(false);
        field.setLongClickable(false);
        field.setHint("Конвертер валют");
        field.setHintTextColor(Ui.text2);
        field.setTextColor(Ui.text);
        field.setText(Fx.ready() ? Fx.summary() : "");
        field.setContentDescription("Конвертер валют");
    }

    private void styleSearch(TextView badge, EditText field, SearchEngines.Engine e) {
        Context c = getContext();
        if (!field.isFocusableInTouchMode()) { // строка снова стала поиском
            field.setText("");
            field.setFocusable(true);
            field.setFocusableInTouchMode(true);
            field.setCursorVisible(true);
            field.setLongClickable(true);
            field.setContentDescription(null);
        }
        ViewGroup row = (ViewGroup) badge.getParent();
        row.setBackground(Ui.round(Ui.dark ? 0xD91C1C1E : 0xF2EBEBEF, Ui.dp(c, 25)));
        row.setElevation(Ui.dp(c, 3));
        badge.setText(e.glyph);
        badge.setTextColor(Ui.tileGlyph);
        badge.setTextSize(e.glyph.length() > 1 ? 12 : 15);
        badge.setBackground(Ui.ripple(Ui.round(Ui.dark ? 0xFF2C2C2E : 0xFFDCDCE1, Ui.dp(c, 17)), null));
        field.setHint("Поиск: " + e.name);
        field.setHintTextColor(Ui.text2);
        field.setTextColor(Ui.text);
    }

    private void buildGrid(Context c) {
        grid.removeAllViews();
        items = Shortcuts.load();
        int shown = Math.min(items.size(), MAX_CELLS);
        plus.setVisibility(shown < MAX_CELLS ? VISIBLE : GONE);
        plus.setColorFilter(Ui.text);
        int total = shown;
        LinearLayout row = null;
        for (int i = 0; i < total; i++) {
            if (i % COLS == 0) {
                row = new LinearLayout(c);
                row.setOrientation(LinearLayout.HORIZONTAL);
                grid.addView(row, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
            }
            View cell = tile(c, items.get(i), i);
            row.addView(cell, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
        if (row != null) {
            for (int k = total % COLS; k != 0 && k < COLS; k++) {
                row.addView(new View(c), new LinearLayout.LayoutParams(0, 1, 1f));
            }
        }
    }

    private static final int TILE = 50;

    private View cellShell(Context c, View icon, String label) {
        LinearLayout cell = new LinearLayout(c);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setPadding(0, Ui.dp(c, 7), 0, Ui.dp(c, 7));
        cell.addView(icon, new LinearLayout.LayoutParams(Ui.dp(c, TILE), Ui.dp(c, TILE)));
        TextView t = Ui.text(c, label, 11.5f, Ui.dark ? 0xFFD1D1D6 : Ui.text2, false);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setPadding(Ui.dp(c, 2), Ui.dp(c, 5), Ui.dp(c, 2), 0);
        cell.addView(t, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return cell;
    }

    /** Скруглённый квадрат, как значки на iPhone. */
    private static void squircle(View v, float radius) {
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        v.setClipToOutline(true);
    }

    private View tile(Context c, Shortcuts.Item it, int index) {
        float r = Ui.dp(c, TILE * 0.24f);
        FrameLayout icon = new FrameLayout(c);
        squircle(icon, r);
        icon.setElevation(Ui.dark ? 0 : Ui.dp(c, 2));
        String glyph = it.glyph == null || it.glyph.isEmpty() ? Shortcuts.glyphFor(it.title) : it.glyph;
        TextView letter = Ui.glyphTile(c, glyph, TILE, 20);
        letter.setElevation(0);
        int tint = SiteIcons.colorFor(SiteIcons.host(it.url));
        if (Ui.dark) { // тёмная тема: чёрная плитка, буква цветная
            letter.setTextColor(tint);
            letter.setBackgroundColor(0xFF000000);
        } else {
            letter.setTextColor(0xFFFFFFFF);
            letter.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{tint, darker(tint)}));
        }
        icon.addView(letter, new LayoutParams(-1, -1));
        ImageView img = new ImageView(c);
        img.setVisibility(GONE);
        icon.addView(img, new LayoutParams(-1, -1));
        Bitmap b = SiteIcons.get(c, it.url, bm -> showIcon(img, letter, bm));
        if (b != null) showIcon(img, letter, b);

        View cell = cellShell(c, icon, it.title);
        icon.setForeground(Ui.ripple(null, Ui.round(0xFFFFFFFF, r)));
        icon.setOnClickListener(v -> {
            Haptics.click(v);
            listener.onOpen(it.url);
        });
        icon.setOnLongClickListener(v -> {
            Haptics.thud(v);
            edit(index);
            return true;
        });
        return cell;
    }

    private static int darker(int c) {
        int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
        return 0xFF000000 | ((r * 3 / 4) << 16) | ((g * 3 / 4) << 8) | (b * 3 / 4);
    }

    /**
     * Крупный значок сайта — на всю плитку; маленький — по центру, как веб-ярлыки iOS.
     * В тёмной теме плитка чёрная: белый фон значка убираем, цветной — делаем чёрным.
     */
    private static void showIcon(ImageView img, TextView letter, Bitmap b) {
        if (b == null) return;
        boolean dk = Ui.dark;
        int bg = dk ? 0xFF000000 : 0xFFFFFFFF;
        img.setImageBitmap(dk ? SiteIcons.onBlack(b) : b);
        img.setVisibility(VISIBLE);
        if (b.getWidth() >= 96) {
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            img.setPadding(0, 0, 0, 0);
        } else {
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            int p = Ui.dp(img.getContext(), 11);
            img.setPadding(p, p, p, p);
        }
        img.setBackgroundColor(bg);
        letter.setVisibility(GONE);
    }

    /** Добавить (index == -1) или изменить ярлык. */
    private void edit(int index) {
        Context c = getContext();
        boolean isNew = index < 0;
        Shortcuts.Item it = isNew ? new Shortcuts.Item("", "https://", "") : items.get(index);
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(c, 20);
        box.setPadding(p, Ui.dp(c, 8), p, 0);
        EditText title = field(c, "Название", it.title, InputType.TYPE_CLASS_TEXT);
        EditText url = field(c, "Адрес сайта", it.url, InputType.TYPE_TEXT_VARIATION_URI | InputType.TYPE_CLASS_TEXT);
        EditText glyph = field(c, "Буква, пока нет значка (необязательно)", it.glyph, InputType.TYPE_CLASS_TEXT);
        box.addView(title);
        box.addView(url);
        box.addView(glyph);

        AlertDialog.Builder b = new AlertDialog.Builder(c)
                .setTitle(isNew ? "Новый ярлык" : "Ярлык")
                .setView(box)
                .setPositiveButton("Сохранить", (d, w) -> {
                    String t = title.getText().toString().trim();
                    String u = url.getText().toString().trim();
                    if (u.isEmpty() || u.equals("https://")) return;
                    if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://" + u;
                    if (t.isEmpty()) t = android.net.Uri.parse(u).getHost();
                    String g = glyph.getText().toString().trim();
                    if (g.length() > 2) g = g.substring(0, 2);
                    Shortcuts.Item n = new Shortcuts.Item(t, u, g.isEmpty() ? Shortcuts.glyphFor(t) : g);
                    if (!isNew && !u.equals(it.url)) SiteIcons.forget(c, it.url);
                    if (isNew) items.add(n); else items.set(index, n);
                    Shortcuts.save(items);
                    refresh();
                })
                .setNegativeButton("Отмена", null);
        if (!isNew) {
            b.setNeutralButton("Удалить", (d, w) -> {
                items.remove(index);
                Shortcuts.save(items);
                refresh();
            });
        }
        b.show();
    }

    private static EditText field(Context c, String hint, String value, int type) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setText(value);
        e.setSingleLine(true);
        e.setInputType(type);
        e.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        return e;
    }
}
