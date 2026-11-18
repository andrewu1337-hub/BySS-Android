package app.lev.browser;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * Начальная страница: картинка-фон, два поиска выше середины и сетка любимых сайтов.
 * Долгое нажатие на ярлык — изменить или удалить, «+» — добавить.
 */
@SuppressLint("ViewConstructor")
final class HomeView extends FrameLayout {

    interface Listener {
        void onOpen(String url);
        void onSearch(String engineId, String query);
        void onPickEngine(int slot);
    }

    static final int[] WALLPAPERS = {R.drawable.wp_mountains, R.drawable.wp_dunes, R.drawable.wp_waves, R.drawable.wp_rings, 0};
    static final String[] WALLPAPER_NAMES = {"Горы", "Дюны", "Волны", "Круги", "Просто чёрный"};

    private final Listener listener;
    private final ImageView wall;
    private final View shade;
    private final LinearLayout grid;
    private final TextView badge1, badge2;
    private final EditText search1, search2;
    private List<Shortcuts.Item> items;

    HomeView(Context c, Listener l) {
        super(c);
        listener = l;
        wall = new ImageView(c);
        wall.setScaleType(ImageView.ScaleType.CENTER_CROP);
        addView(wall, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        shade = new View(c);
        addView(shade, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        int side = Ui.dp(c, 22);
        col.setPadding(side, 0, side, 0);
        addView(col, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        col.addView(new View(c), new LinearLayout.LayoutParams(1, 0, 0.85f));

        LinearLayout row1 = searchRow(c);
        badge1 = (TextView) row1.getChildAt(0);
        search1 = (EditText) row1.getChildAt(1);
        col.addView(row1);
        View gap = new View(c);
        col.addView(gap, new LinearLayout.LayoutParams(1, Ui.dp(c, 10)));
        LinearLayout row2 = searchRow(c);
        badge2 = (TextView) row2.getChildAt(0);
        search2 = (EditText) row2.getChildAt(1);
        col.addView(row2);

        col.addView(new View(c), new LinearLayout.LayoutParams(1, Ui.dp(c, 34)));
        grid = new LinearLayout(c);
        grid.setOrientation(LinearLayout.VERTICAL);
        col.addView(grid, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        col.addView(new View(c), new LinearLayout.LayoutParams(1, 0, 1.5f));

        wire(badge1, search1, 1);
        wire(badge2, search2, 2);
        refresh();
    }

    private LinearLayout searchRow(Context c) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int h = Ui.dp(c, 52);
        row.setMinimumHeight(h);
        row.setPadding(Ui.dp(c, 8), 0, Ui.dp(c, 14), 0);
        TextView badge = new TextView(c);
        badge.setGravity(Gravity.CENTER);
        badge.setTextSize(15);
        badge.setIncludeFontPadding(false);
        row.addView(badge, new LinearLayout.LayoutParams(Ui.dp(c, 36), Ui.dp(c, 36)));
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

    private void wire(TextView badge, EditText field, int slot) {
        badge.setOnClickListener(v -> {
            Haptics.click(v);
            listener.onPickEngine(slot);
        });
        field.setOnEditorActionListener((v, actionId, event) -> {
            boolean go = actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_GO
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN);
            if (!go) return false;
            String q = field.getText().toString().trim();
            if (q.isEmpty()) return true;
            hideKeyboard(field);
            field.setText("");
            listener.onSearch(slot == 1 ? Prefs.home1() : Prefs.home2(), q);
            return true;
        });
    }

    private static void hideKeyboard(View v) {
        InputMethodManager imm = v.getContext().getSystemService(InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
        v.clearFocus();
    }

    /** Перерисовать: тема, фон, поисковики и ярлыки. */
    void refresh() {
        Context c = getContext();
        setBackgroundColor(Ui.bg);
        int w = Prefs.wallpaper();
        int res = (w >= 0 && w < WALLPAPERS.length) ? WALLPAPERS[w] : 0;
        if (res != 0) {
            wall.setImageResource(res);
            wall.setVisibility(VISIBLE);
            wall.setAlpha(Ui.dark ? 1f : 0.18f);
        } else {
            wall.setImageDrawable(null);
            wall.setVisibility(GONE);
        }
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00000000, Ui.dark ? 0x66000000 : 0x00FFFFFF});
        shade.setBackground(g);

        styleSearch(badge1, search1, SearchEngines.get(Prefs.home1()));
        styleSearch(badge2, search2, SearchEngines.get(Prefs.home2()));
        buildGrid(c);
    }

    private void styleSearch(TextView badge, EditText field, SearchEngines.Engine e) {
        Context c = getContext();
        ViewGroup row = (ViewGroup) badge.getParent();
        row.setBackground(Ui.round(Ui.dark ? 0xD91C1C1E : 0xF2EBEBEF, Ui.dp(c, 26)));
        row.setElevation(Ui.dp(c, 3));
        badge.setText(e.glyph);
        badge.setTextColor(Ui.tileGlyph);
        badge.setTextSize(e.glyph.length() > 1 ? 12 : 15);
        badge.setBackground(Ui.ripple(Ui.round(Ui.dark ? 0xFF2C2C2E : 0xFFDCDCE1, Ui.dp(c, 18)), null));
        field.setHint("Поиск: " + e.name);
        field.setHintTextColor(Ui.text2);
        field.setTextColor(Ui.text);
    }

    private void buildGrid(Context c) {
        grid.removeAllViews();
        items = Shortcuts.load();
        int cols = 4;
        int total = items.size() + 1; // + кнопка «добавить»
        LinearLayout row = null;
        for (int i = 0; i < total; i++) {
            if (i % cols == 0) {
                row = new LinearLayout(c);
                row.setOrientation(LinearLayout.HORIZONTAL);
                grid.addView(row, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
            }
            View cell = i < items.size() ? tile(c, items.get(i), i) : addTile(c);
            row.addView(cell, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
        if (row != null) {
            for (int k = total % cols; k != 0 && k < cols; k++) {
                row.addView(new View(c), new LinearLayout.LayoutParams(0, 1, 1f));
            }
        }
    }

    private View cellShell(Context c, View icon, String label) {
        LinearLayout cell = new LinearLayout(c);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setPadding(0, Ui.dp(c, 8), 0, Ui.dp(c, 8));
        cell.addView(icon, new LinearLayout.LayoutParams(Ui.dp(c, 58), Ui.dp(c, 58)));
        TextView t = Ui.text(c, label, 12, Ui.dark ? 0xFFD1D1D6 : Ui.text2, false);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setPadding(Ui.dp(c, 2), Ui.dp(c, 6), Ui.dp(c, 2), 0);
        cell.addView(t, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return cell;
    }

    private View tile(Context c, Shortcuts.Item it, int index) {
        TextView icon = Ui.glyphTile(c, it.glyph == null || it.glyph.isEmpty() ? Shortcuts.glyphFor(it.title) : it.glyph, 58, 22);
        View cell = cellShell(c, icon, it.title);
        icon.setForeground(Ui.ripple(null, Ui.round(0xFFFFFFFF, Ui.dp(c, 16))));
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

    private View addTile(Context c) {
        TextView icon = Ui.glyphTile(c, "+", 58, 26);
        icon.setTextColor(Ui.text2);
        icon.setBackground(Ui.outline(Ui.dark ? 0x66FFFFFF : 0x44000000, Ui.dp(c, 1), Ui.dp(c, 16)));
        icon.setElevation(0);
        icon.setOnClickListener(v -> {
            Haptics.click(v);
            edit(-1);
        });
        return cellShell(c, icon, "Добавить");
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
        EditText glyph = field(c, "Значок (1–2 символа, необязательно)", it.glyph, InputType.TYPE_CLASS_TEXT);
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
