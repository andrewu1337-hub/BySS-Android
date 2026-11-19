package app.lev.browser;

import android.app.AlertDialog;
import android.content.Context;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.method.DigitsKeyListener;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Окно конвертера валют с главной: сумма и основная валюта сверху (по умолчанию евро),
 * ниже — пересчёт в выбранные валюты. Нажать на валюту — сделать её основной,
 * долгое нажатие — убрать из списка, «+ Добавить валюту» — выбрать из всех.
 */
final class FxDialog {

    private final Context c;
    private final Consumer<String> openUrl;
    private final Runnable changed;
    private AlertDialog dialog;
    private EditText amount;
    private TextView baseBtn, baseName, status;
    private LinearLayout list;
    private final List<TextView> values = new ArrayList<>();
    private boolean updatingText;

    private FxDialog(Context c, Consumer<String> openUrl, Runnable changed) {
        this.c = c;
        this.openUrl = openUrl;
        this.changed = changed;
    }

    static void show(Context c, Consumer<String> openUrl, Runnable changed) {
        new FxDialog(c, openUrl, changed).open();
    }

    private void open() {
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(c, 20);
        root.setPadding(p, Ui.dp(c, 4), p, 0);

        LinearLayout top = new LinearLayout(c);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        amount = new EditText(c);
        amount.setTextSize(28);
        amount.setSingleLine(true);
        amount.setKeyListener(DigitsKeyListener.getInstance("0123456789.,"));
        amount.setRawInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        amount.setTextColor(Ui.text);
        amount.setBackground(null);
        amount.setPadding(0, Ui.dp(c, 6), Ui.dp(c, 8), Ui.dp(c, 6));
        amount.setSelectAllOnFocus(true);
        amount.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        top.addView(amount, new LinearLayout.LayoutParams(0, -2, 1f));
        baseBtn = Ui.text(c, "", 17, Ui.text, true);
        baseBtn.setGravity(Gravity.CENTER);
        baseBtn.setPadding(Ui.dp(c, 14), 0, Ui.dp(c, 14), 0);
        baseBtn.setMinHeight(Ui.dp(c, 44));
        baseBtn.setBackground(Ui.ripple(Ui.round(Ui.pill, Ui.dp(c, 22)), null));
        baseBtn.setOnClickListener(v -> {
            Haptics.click(v);
            pick("Основная валюта", code -> setBase(code, true));
        });
        top.addView(baseBtn, new LinearLayout.LayoutParams(-2, -2));
        root.addView(top, new LinearLayout.LayoutParams(-1, -2));
        baseName = Ui.text(c, "", 13, Ui.text2, false);
        root.addView(baseName);

        View line = new View(c);
        line.setBackgroundColor(Ui.line);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-1, Ui.dp(c, 1));
        llp.topMargin = Ui.dp(c, 12);
        root.addView(line, llp);

        int maxH = (int) (c.getResources().getDisplayMetrics().heightPixels * 0.42f);
        ScrollView sv = new ScrollView(c) {
            @Override
            protected void onMeasure(int w, int h) {
                super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST));
            }
        };
        list = new LinearLayout(c);
        list.setOrientation(LinearLayout.VERTICAL);
        sv.addView(list);
        root.addView(sv, new LinearLayout.LayoutParams(-1, -2));

        TextView add = Ui.text(c, "+  Добавить валюту", 15, Ui.text, true);
        add.setPadding(0, Ui.dp(c, 12), 0, Ui.dp(c, 12));
        add.setOnClickListener(v -> {
            Haptics.click(v);
            pick("Добавить валюту", code -> {
                List<String> t = Fx.targets();
                if (code.equals(Fx.base()) || t.contains(code)) {
                    Toast.makeText(c, code + " уже есть", Toast.LENGTH_SHORT).show();
                    return;
                }
                t.add(code);
                Fx.setTargets(t);
                rebuild();
            });
        });
        root.addView(add);

        status = Ui.text(c, "", 11.5f, Ui.text2, false);
        status.setPadding(0, 0, 0, Ui.dp(c, 6));
        status.setOnClickListener(v -> {
            if (dialog != null) dialog.dismiss();
            openUrl.accept(Fx.SOURCE_URL);
        });
        root.addView(status);

        amount.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int d) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int d) { }
            @Override public void afterTextChanged(Editable s) {
                if (updatingText) return;
                Fx.setAmount(parse());
                updateValues();
            }
        });

        dialog = new AlertDialog.Builder(c).setTitle("Конвертер валют").setView(root)
                .setPositiveButton("Готово", null).create();
        dialog.setOnDismissListener(d -> {
            if (changed != null) changed.run();
        });
        dialog.show();
        setAmountText(Fx.amount());
        rebuild();
        Fx.refresh(false, ok -> {
            if (dialog != null && dialog.isShowing()) rebuild();
        });
    }

    private double parse() {
        String s = amount.getText().toString().replace(" ", "").replace(',', '.');
        try {
            return s.isEmpty() ? 0 : Double.parseDouble(s);
        } catch (Exception e) {
            return 0;
        }
    }

    private void setAmountText(double v) {
        updatingText = true;
        String s = Math.abs(v - Math.rint(v)) < 1e-9 ? String.valueOf((long) Math.rint(v))
                : String.format(Locale.ROOT, "%.2f", v).replace('.', ',');
        amount.setText(s);
        amount.setSelection(amount.getText().length());
        updatingText = false;
    }

    private void setBase(String code, boolean keepAmount) {
        String old = Fx.base();
        if (code.equals(old)) return;
        List<String> t = Fx.targets();
        int i = t.indexOf(code);
        if (i >= 0) t.set(i, old); else if (!t.contains(old)) t.add(0, old);
        Fx.setTargets(t);
        if (!keepAmount) {
            double v = Fx.convert(parse(), old, code);
            if (!Double.isNaN(v)) {
                double r = Math.round(v * 100) / 100.0;
                Fx.setAmount(r);
                setAmountText(r);
            }
        }
        Fx.setBase(code);
        rebuild();
    }

    private void rebuild() {
        String b = Fx.base();
        baseBtn.setText(Fx.flag(b) + "  " + b + "  ▾");
        baseName.setText(Fx.name(b));
        list.removeAllViews();
        values.clear();
        if (!Fx.ready()) {
            TextView w = Ui.text(c, "Загружаю курсы…", 15, Ui.text2, false);
            w.setPadding(0, Ui.dp(c, 18), 0, Ui.dp(c, 18));
            list.addView(w);
        }
        for (String code : Fx.targets()) {
            if (code.equals(b)) continue;
            list.addView(row(code));
        }
        updateValues();
        long at = Fx.updatedAt();
        String when = at > 0 ? new SimpleDateFormat("d MMMM, HH:mm", new Locale("ru")).format(new Date(at)) : "";
        status.setText("Курсы: ExchangeRate-API" + (when.isEmpty() ? "" : " · обновлены " + when));
    }

    private View row(String code) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, Ui.dp(c, 10), 0, Ui.dp(c, 10));
        r.setBackground(Ui.ripple(null, Ui.round(0xFFFFFFFF, Ui.dp(c, 12))));
        TextView flag = Ui.text(c, Fx.flag(code), 22, Ui.text, false);
        r.addView(flag, new LinearLayout.LayoutParams(Ui.dp(c, 40), -2));
        LinearLayout names = new LinearLayout(c);
        names.setOrientation(LinearLayout.VERTICAL);
        names.addView(Ui.text(c, code, 15, Ui.text, true));
        TextView n = Ui.text(c, Fx.name(code), 12, Ui.text2, false);
        n.setSingleLine(true);
        n.setEllipsize(android.text.TextUtils.TruncateAt.END);
        names.addView(n);
        r.addView(names, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView v = Ui.text(c, "", 19, Ui.text, true);
        v.setGravity(Gravity.END);
        v.setTag(code);
        values.add(v);
        r.addView(v, new LinearLayout.LayoutParams(-2, -2));
        r.setOnClickListener(x -> {
            Haptics.click(x);
            setBase(code, false);
        });
        r.setOnLongClickListener(x -> {
            Haptics.thud(x);
            List<String> t = Fx.targets();
            t.remove(code);
            Fx.setTargets(t);
            Toast.makeText(c, code + " убрана из списка", Toast.LENGTH_SHORT).show();
            rebuild();
            return true;
        });
        return r;
    }

    private void updateValues() {
        double a = parse();
        String b = Fx.base();
        for (TextView v : values) v.setText(Fx.format(Fx.convert(a, b, (String) v.getTag())));
    }

    /** Список всех валют с поиском по коду и названию. */
    private void pick(String title, Consumer<String> onPick) {
        List<String> codes = Fx.codes();
        if (codes.isEmpty()) {
            Toast.makeText(c, "Курсы ещё не загрузились — нужен интернет", Toast.LENGTH_SHORT).show();
            return;
        }
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(c, 16);
        box.setPadding(p, Ui.dp(c, 4), p, 0);
        EditText q = new EditText(c);
        q.setHint("Код или название, например MDL");
        q.setSingleLine(true);
        q.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        box.addView(q, new LinearLayout.LayoutParams(-1, -2));
        ListView lv = new ListView(c);
        box.addView(lv, new LinearLayout.LayoutParams(-1, Ui.dp(c, 380)));
        List<String> shown = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        ArrayAdapter<String> ad = new ArrayAdapter<>(c, android.R.layout.simple_list_item_1, labels);
        lv.setAdapter(ad);
        Runnable filter = () -> {
            String f = q.getText().toString().trim().toLowerCase(new Locale("ru"));
            shown.clear();
            labels.clear();
            for (String code : codes) {
                String name = Fx.name(code);
                if (f.isEmpty() || code.toLowerCase(Locale.ROOT).startsWith(f)
                        || name.toLowerCase(new Locale("ru")).contains(f)) {
                    shown.add(code);
                    labels.add(Fx.flag(code) + "   " + code + " · " + name);
                }
            }
            ad.notifyDataSetChanged();
        };
        filter.run();
        AlertDialog d = new AlertDialog.Builder(c).setTitle(title).setView(box)
                .setNegativeButton("Отмена", null).create();
        lv.setOnItemClickListener((parent, view, pos, id) -> {
            d.dismiss();
            onPick.accept(shown.get(pos));
        });
        q.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int e) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int e) { filter.run(); }
            @Override public void afterTextChanged(Editable s) { }
        });
        d.show();
    }
}
