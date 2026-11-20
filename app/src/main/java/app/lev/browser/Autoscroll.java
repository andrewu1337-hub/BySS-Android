package app.lev.browser;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * Автоскролл страницы: настройки и окно с ними (меню → «Автоскролл»).
 * Плавно — скорость 1…10, шагами — интервал в секундах и шаг в процентах экрана.
 * Сама прокрутка — js/autoscroll.js в странице, тот же код, что в BySS для ПК.
 */
final class Autoscroll {

    private Autoscroll() { }

    /** Пикселей страницы в секунду для скоростей 1…10. */
    static final int[] SPEEDS = {15, 25, 40, 60, 85, 120, 170, 240, 340, 480};
    static final int[] INTERVALS = {1, 2, 3, 4, 5, 7, 10, 15, 20, 30, 45, 60};
    static final int[] STEPS = {10, 25, 50, 75, 90, 100};

    static final String STOP_JS = "window.__levAS&&__levAS.stop()";

    static boolean stepMode() {
        return "step".equals(Prefs.sp().getString("as_mode", "smooth"));
    }

    static int level() {
        return Math.max(1, Math.min(10, Prefs.sp().getInt("as_level", 4)));
    }

    static int interval() {
        int v = Prefs.sp().getInt("as_interval", 5);
        return indexOf(INTERVALS, v) >= 0 ? v : 5;
    }

    static int step() {
        int v = Prefs.sp().getInt("as_step", 75);
        return indexOf(STEPS, v) >= 0 ? v : 75;
    }

    private static int indexOf(int[] a, int v) {
        for (int i = 0; i < a.length; i++) if (a[i] == v) return i;
        return -1;
    }

    /** Код запуска (или обновления настроек) для страницы; core — js/autoscroll.js. */
    static String startJs(String core) {
        String cfg = "{mode:'" + (stepMode() ? "step" : "smooth") + "',speed:" + SPEEDS[level() - 1]
                + ",interval:" + interval() + ",step:" + step() + "}";
        return core + "\n;window.__levAS&&__levAS.start(" + cfg + ");";
    }

    /** Короткая подпись текущих настроек: «плавно · 4 из 10» / «шагами · 5 с · 75%». */
    static String summary() {
        return stepMode() ? "шагами · " + interval() + " с · " + step() + "%" : "плавно · " + level() + " из 10";
    }

    interface Host {
        boolean running();

        /** Запустить или остановить на текущей вкладке. */
        void toggle();

        /** Настройки поменялись — если идёт, применить сразу. */
        void changed();
    }

    static void show(Context c, Host host) {
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(c, 22);
        root.setPadding(p, Ui.dp(c, 6), p, 0);

        // режим: плавно / шагами
        LinearLayout seg = new LinearLayout(c);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        seg.setBackground(Ui.round(Ui.pill, Ui.dp(c, 12)));
        seg.setPadding(Ui.dp(c, 3), Ui.dp(c, 3), Ui.dp(c, 3), Ui.dp(c, 3));
        TextView smooth = segItem(c, "Плавно");
        TextView steps = segItem(c, "Шагами");
        seg.addView(smooth, new LinearLayout.LayoutParams(0, Ui.dp(c, 38), 1f));
        seg.addView(steps, new LinearLayout.LayoutParams(0, Ui.dp(c, 38), 1f));
        root.addView(seg, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout smoothBox = new LinearLayout(c);
        smoothBox.setOrientation(LinearLayout.VERTICAL);
        TextView speedLabel = label(c);
        SeekBar speed = bar(c, SPEEDS.length - 1, level() - 1);
        smoothBox.addView(speedLabel);
        smoothBox.addView(speed);
        root.addView(smoothBox);

        LinearLayout stepBox = new LinearLayout(c);
        stepBox.setOrientation(LinearLayout.VERTICAL);
        TextView intLabel = label(c);
        SeekBar inter = bar(c, INTERVALS.length - 1, indexOf(INTERVALS, interval()));
        TextView stepLabel = label(c);
        SeekBar stepBar = bar(c, STEPS.length - 1, indexOf(STEPS, step()));
        stepBox.addView(intLabel);
        stepBox.addView(inter);
        stepBox.addView(stepLabel);
        stepBox.addView(stepBar);
        root.addView(stepBox);

        TextView hint = Ui.text(c, "Коснитесь страницы — пауза. Остановить — ✕ на плашке внизу.", 12.5f, Ui.text2, false);
        hint.setPadding(0, Ui.dp(c, 12), 0, Ui.dp(c, 4));
        root.addView(hint);

        Runnable sync = () -> {
            boolean st = stepMode();
            mark(c, smooth, !st);
            mark(c, steps, st);
            smoothBox.setVisibility(st ? View.GONE : View.VISIBLE);
            stepBox.setVisibility(st ? View.VISIBLE : View.GONE);
            speedLabel.setText("Скорость:  " + level() + " из 10");
            intLabel.setText("Интервал:  " + interval() + " с");
            stepLabel.setText("Шаг:  " + step() + "% экрана");
        };
        sync.run();

        smooth.setOnClickListener(v -> {
            if (!stepMode()) return;
            Haptics.click(v);
            Prefs.put("as_mode", "smooth");
            sync.run();
            host.changed();
        });
        steps.setOnClickListener(v -> {
            if (stepMode()) return;
            Haptics.click(v);
            Prefs.put("as_mode", "step");
            sync.run();
            host.changed();
        });
        listen(speed, i -> Prefs.put("as_level", i + 1), sync, host);
        listen(inter, i -> Prefs.put("as_interval", INTERVALS[i]), sync, host);
        listen(stepBar, i -> Prefs.put("as_step", STEPS[i]), sync, host);

        AlertDialog d = new AlertDialog.Builder(c).setTitle("Автоскролл").setView(root)
                .setPositiveButton(host.running() ? "Остановить" : "Запустить", (x, w) -> host.toggle())
                .setNegativeButton("Закрыть", null)
                .create();
        d.show();
    }

    private interface IntSink {
        void put(int i);
    }

    private static void listen(SeekBar b, IntSink sink, Runnable sync, Host host) {
        b.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int v, boolean user) {
                if (!user) return;
                sink.put(v);
                sync.run();
                Haptics.click(s);
            }

            @Override public void onStartTrackingTouch(SeekBar s) { }

            @Override public void onStopTrackingTouch(SeekBar s) {
                host.changed();
            }
        });
    }

    private static TextView segItem(Context c, String text) {
        TextView t = Ui.text(c, text, 14.5f, Ui.text2, false);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private static void mark(Context c, TextView t, boolean on) {
        t.setTextColor(on ? Ui.text : Ui.text2);
        t.setTypeface(Typeface.create("sans-serif-medium", on ? Typeface.BOLD : Typeface.NORMAL));
        t.setBackground(on ? Ui.round(Ui.card, Ui.dp(c, 10)) : null);
    }

    private static TextView label(Context c) {
        TextView t = Ui.text(c, "", 14.5f, Ui.text, false);
        t.setPadding(0, Ui.dp(c, 16), 0, Ui.dp(c, 2));
        return t;
    }

    private static SeekBar bar(Context c, int max, int value) {
        SeekBar b = new SeekBar(c);
        b.setMax(max);
        b.setProgress(Math.max(0, value));
        b.setProgressTintList(ColorStateList.valueOf(Ui.text));
        b.setThumbTintList(ColorStateList.valueOf(Ui.text));
        b.setProgressBackgroundTintList(ColorStateList.valueOf(Ui.text2));
        b.setPadding(Ui.dp(c, 4), Ui.dp(c, 10), Ui.dp(c, 4), Ui.dp(c, 10));
        return b;
    }
}
