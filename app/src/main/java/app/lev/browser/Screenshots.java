package app.lev.browser;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Скриншоты страницы: «Экран» (что видно сейчас), «Вся страница» (сама листает и склеивает,
 * пока страница закрыта неподвижной картинкой — без прыжков) и «Выбрать участок»
 * (человек сам листает до нужного места и нажимает «Готово»). После сохранения внизу
 * появляется карточка с миниатюрой: поделиться, открыть или просто подождать — она уйдёт сама.
 */
final class Screenshots {

    private final BrowserActivity act;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private LongShot shot;
    private View sheet;      // выбор: экран / вся страница / участок
    private View area;       // режим «выбрать участок»
    private View card;       // карточка «сохранено»
    private ViewTreeObserver.OnScrollChangedListener areaScroll;

    Screenshots(BrowserActivity act) {
        this.act = act;
    }

    private ViewGroup decor() {
        return (ViewGroup) act.getWindow().getDecorView();
    }

    private ByssWebView web() {
        Tab t = act.currentTab();
        return t == null || t.isHome() ? null : t.web;
    }

    /** Назад: закрыть выбор / выйти из режима участка / отменить съёмку. */
    boolean onBack() {
        if (sheet != null) {
            closeSheet();
            return true;
        }
        if (area != null) {
            closeArea();
            return true;
        }
        if (shot != null) {
            shot.cancel();
            return true;
        }
        return false;
    }

    void onPause() {
        if (shot != null) shot.cancel();
        closeArea();
    }

    // ================================================================= выбор

    void chooser() {
        if (shot != null || area != null || web() == null) return;
        Context c = act;
        FrameLayout scrim = new FrameLayout(c);
        scrim.setBackgroundColor(Ui.scrim);
        scrim.setOnClickListener(v -> closeSheet());
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(Ui.round(Ui.card, Ui.dp(c, 22)));
        box.setElevation(Ui.dp(c, 14));
        box.setPadding(0, Ui.dp(c, 14), 0, Ui.dp(c, 10));
        box.setClickable(true);
        TextView title = Ui.text(c, "Скриншот", 17, Ui.text, true);
        title.setPadding(Ui.dp(c, 20), 0, Ui.dp(c, 20), Ui.dp(c, 6));
        box.addView(title);
        box.addView(option(c, R.drawable.ic_shot_screen, "Экран", "То, что сейчас видно", () -> {
            closeSheet();
            ui.postDelayed(this::visible, 180);
        }));
        box.addView(option(c, R.drawable.ic_shot_page, "Вся страница", "Сверху донизу — листаю и склеиваю сам", () -> {
            closeSheet();
            ui.postDelayed(() -> longShot(-1, -1), 180);
        }));
        box.addView(option(c, R.drawable.ic_shot_area, "Выбрать участок",
                "Листаете сами до нужного места и нажимаете «Готово»", () -> {
                    closeSheet();
                    ui.postDelayed(this::area, 180);
                }));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        lp.setMargins(Ui.dp(c, 10), 0, Ui.dp(c, 10), act.bottomBarHeight() + Ui.dp(c, 10));
        scrim.addView(box, lp);
        decor().addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        sheet = scrim;
        scrim.setAlpha(0f);
        scrim.animate().alpha(1f).setDuration(Ui.anim(160)).start();
        box.setTranslationY(Ui.dp(c, 40));
        box.animate().translationY(0).setDuration(Ui.anim(240)).setInterpolator(new PathInterpolator(0.2f, 0f, 0f, 1f)).start();
    }

    private View option(Context c, int icon, String name, String sub, Runnable run) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(Ui.dp(c, 20), Ui.dp(c, 12), Ui.dp(c, 18), Ui.dp(c, 12));
        r.setBackground(Ui.ripple(null, Ui.round(0xFFFFFFFF, 0)));
        ImageView ic = new ImageView(c);
        ic.setImageResource(icon);
        ic.setImageTintList(ColorStateList.valueOf(Ui.text));
        r.addView(ic, new LinearLayout.LayoutParams(Ui.dp(c, 26), Ui.dp(c, 26)));
        LinearLayout t = new LinearLayout(c);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setPadding(Ui.dp(c, 18), 0, 0, 0);
        t.addView(Ui.text(c, name, 16, Ui.text, true));
        t.addView(Ui.text(c, sub, 12.5f, Ui.text2, false));
        r.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        r.setOnClickListener(v -> {
            Haptics.click(v);
            run.run();
        });
        return r;
    }

    private void closeSheet() {
        View s = sheet;
        sheet = null;
        if (s == null) return;
        s.animate().alpha(0f).setDuration(Ui.anim(140)).withEndAction(() -> decor().removeView(s)).start();
    }

    // ================================================================= экран

    void visible() {
        ByssWebView w = web();
        if (w == null || shot != null) return;
        Bitmap b;
        try {
            b = LongShot.visible(w);
        } catch (Throwable e) {
            b = null;
        }
        if (b == null) {
            Toast.makeText(act, "Страница ещё не готова", Toast.LENGTH_SHORT).show();
            return;
        }
        flash(w);
        Haptics.click(w);
        Bitmap shotBmp = b;
        new Thread(() -> {
            Uri uri = null;
            String err = null;
            try {
                uri = LongShot.save(act, shotBmp);
            } catch (Throwable e) {
                err = "Не получилось сохранить: " + e.getMessage();
            } finally {
                shotBmp.recycle();
            }
            Uri u = uri;
            String er = err;
            ui.post(() -> {
                if (u != null) saved(u, false);
                else Toast.makeText(act, er, Toast.LENGTH_LONG).show();
            });
        }).start();
    }

    /** Белая вспышка поверх страницы — как у камеры. */
    private void flash(View over) {
        View f = new View(act);
        f.setBackgroundColor(0xFFFFFFFF);
        addOver(over, f, false);
        f.setAlpha(0.8f);
        f.animate().alpha(0f).setDuration(Ui.anim(320)).withEndAction(() -> decor().removeView(f)).start();
    }

    /** Положить view ровно поверх страницы. */
    private void addOver(View over, View v, boolean blockTouches) {
        int[] loc = new int[2];
        over.getLocationInWindow(loc);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(over.getWidth(), over.getHeight());
        lp.leftMargin = loc[0];
        lp.topMargin = loc[1];
        lp.gravity = Gravity.TOP | Gravity.START;
        v.setClickable(blockTouches);
        decor().addView(v, lp);
    }

    // ================================================================= длинный

    /** from/to — пиксели прокрутки; -1 — вся страница. */
    private void longShot(int from, int to) {
        ByssWebView w = web();
        if (w == null || shot != null) return;
        Context c = act;
        FrameLayout cover = new FrameLayout(c);
        cover.setClickable(true); // пока снимаем — страницу не трогаем
        // неподвижная картинка текущего экрана — страница под ней листается незаметно
        ImageView frozen = new ImageView(c);
        frozen.setScaleType(ImageView.ScaleType.FIT_XY);
        try {
            Bitmap still = LongShot.visible(w);
            if (still != null) frozen.setImageBitmap(still);
        } catch (Throwable ignored) {
            // без «заморозки» — просто затемнение
        }
        int[] loc = new int[2];
        w.getLocationInWindow(loc);
        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(w.getWidth(), w.getHeight());
        flp.leftMargin = loc[0];
        flp.topMargin = loc[1];
        flp.gravity = Gravity.TOP | Gravity.START;
        cover.addView(frozen, flp);
        View dim = new View(c);
        dim.setBackgroundColor(0x66000000);
        cover.addView(dim, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(c, 18), Ui.dp(c, 14), Ui.dp(c, 12), Ui.dp(c, 12));
        box.setBackground(Ui.round(Ui.card, Ui.dp(c, 20)));
        box.setElevation(Ui.dp(c, 12));
        TextView msg = Ui.text(c, "Снимаю страницу…", 15, Ui.text, true);
        box.addView(msg);
        ProgressBar bar = new ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setProgress(0);
        bar.setProgressTintList(ColorStateList.valueOf(Ui.text));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(Ui.line));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, Ui.dp(c, 8));
        blp.topMargin = Ui.dp(c, 10);
        blp.rightMargin = Ui.dp(c, 6);
        box.addView(bar, blp);
        LinearLayout btns = new LinearLayout(c);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        TextView hint = Ui.text(c, "«Стоп» — сохранить то, что уже снято", 12, Ui.text2, false);
        btns.addView(hint, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView cancel = pill(c, "Отмена", false);
        btns.addView(cancel);
        View gap = new View(c);
        btns.addView(gap, new LinearLayout.LayoutParams(Ui.dp(c, 6), 1));
        TextView stop = pill(c, "Стоп", true);
        btns.addView(stop);
        LinearLayout.LayoutParams brp = new LinearLayout.LayoutParams(-1, -2);
        brp.topMargin = Ui.dp(c, 12);
        box.addView(btns, brp);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        lp.setMargins(Ui.dp(c, 12), 0, Ui.dp(c, 12), act.bottomBarHeight() + Ui.dp(c, 12));
        cover.addView(box, lp);
        decor().addView(cover, new FrameLayout.LayoutParams(-1, -1));
        cover.setAlpha(0f);
        cover.animate().alpha(1f).setDuration(Ui.anim(140)).start();

        shot = new LongShot(act, w, from, to, new LongShot.Listener() {
            @Override
            public void progress(int frame, int frames) {
                msg.setText(frames > 1 ? "Снимаю страницу… " + frame + " из " + frames : "Снимаю…");
                int target = Math.round(1000f * (frame - 1) / Math.max(1, frames));
                android.animation.ObjectAnimator.ofInt(bar, "progress", bar.getProgress(), target)
                        .setDuration(Ui.anim(260)).start();
            }

            @Override
            public void done(Uri uri, String error, boolean cropped) {
                shot = null;
                bar.setProgress(1000);
                cover.animate().alpha(0f).setDuration(Ui.anim(160)).withEndAction(() -> decor().removeView(cover)).start();
                if (uri != null) {
                    Haptics.click(w);
                    saved(uri, cropped);
                } else if (error != null) {
                    Toast.makeText(act, error, Toast.LENGTH_LONG).show();
                }
            }
        });
        cancel.setOnClickListener(v -> {
            msg.setText("Отменяю…");
            if (shot != null) shot.cancel();
        });
        stop.setOnClickListener(v -> {
            Haptics.click(v);
            msg.setText("Сохраняю…");
            if (shot != null) shot.stop();
        });
        shot.start();
    }

    private TextView pill(Context c, String label, boolean primary) {
        TextView b = Ui.text(c, label, 14, primary ? Ui.bg : Ui.text, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(Ui.dp(c, 16), 0, Ui.dp(c, 16), 0);
        b.setMinHeight(Ui.dp(c, 38));
        b.setBackground(Ui.ripple(Ui.round(primary ? Ui.text : Ui.pill, Ui.dp(c, 19)), null));
        return b;
    }

    // ================================================================= участок

    /** Подсветка выбранного: выше начала — затемнено, по линии начала — полоска с подписью. */
    private static final class AreaView extends View {
        int startOnScreen; // где на экране начало снимка (может быть выше экрана — < 0)
        private final Paint dim = new Paint();
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint frame = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint chip = new Paint(Paint.ANTI_ALIAS_FLAG);

        AreaView(Context c) {
            super(c);
            dim.setColor(0x99000000);
            line.setColor(0xFFFFFFFF);
            line.setStrokeWidth(Ui.dp(c, 3));
            label.setColor(0xFF000000);
            label.setTextSize(Ui.dp(c, 12));
            label.setFakeBoldText(true);
            frame.setStyle(Paint.Style.STROKE);
            frame.setColor(0xCCFFFFFF);
            frame.setStrokeWidth(Ui.dp(c, 2));
            chip.setColor(0xFFFFFFFF);
        }

        @Override
        protected void onDraw(Canvas cv) {
            int w = getWidth(), h = getHeight();
            int y = Math.max(0, startOnScreen);
            if (y > 0) cv.drawRect(0, 0, w, y, dim);
            float half = frame.getStrokeWidth() / 2;
            cv.drawRect(half, y + half, w - half, h - half, frame);
            if (startOnScreen >= 0 && startOnScreen < h) {
                cv.drawLine(0, y, w, y, line);
                String s = "начало снимка";
                float tw = label.measureText(s), pad = Ui.dp(getContext(), 8);
                float bh = Ui.dp(getContext(), 22);
                float top = Math.min(h - bh, y);
                cv.drawRoundRect(pad, top, pad + tw + pad * 2, top + bh, bh / 2, bh / 2, chip);
                cv.drawText(s, pad * 2, top + bh * 0.7f, label);
            }
        }
    }

    private int areaTop;

    void area() {
        ByssWebView w = web();
        if (w == null || shot != null || area != null) return;
        Context c = act;
        areaTop = w.getScrollY();
        FrameLayout box = new FrameLayout(c);
        AreaView av = new AreaView(c);
        av.startOnScreen = 0;
        int[] loc = new int[2];
        w.getLocationInWindow(loc);
        FrameLayout.LayoutParams alp = new FrameLayout.LayoutParams(w.getWidth(), w.getHeight());
        alp.leftMargin = loc[0];
        alp.topMargin = loc[1];
        alp.gravity = Gravity.TOP | Gravity.START;
        box.addView(av, alp);

        LinearLayout bar = new LinearLayout(c);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(c, 18), Ui.dp(c, 10), Ui.dp(c, 10), Ui.dp(c, 10));
        bar.setBackground(Ui.round(Ui.card, Ui.dp(c, 20)));
        bar.setElevation(Ui.dp(c, 12));
        bar.setClickable(true);
        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t1 = Ui.text(c, "Листайте вниз до конца нужного места", 14, Ui.text, true);
        t1.setMaxLines(2);
        TextView t2 = Ui.text(c, "Снимок — от линии до низа экрана", 12, Ui.text2, false);
        t2.setSingleLine(true);
        t2.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(t1);
        texts.addView(t2);
        bar.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView cancel = pill(c, "Отмена", false);
        bar.addView(cancel);
        View gap = new View(c);
        bar.addView(gap, new LinearLayout.LayoutParams(Ui.dp(c, 6), 1));
        TextView ok = pill(c, "Готово", true);
        bar.addView(ok);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        lp.setMargins(Ui.dp(c, 12), 0, Ui.dp(c, 12), act.bottomBarHeight() + Ui.dp(c, 12));
        box.addView(bar, lp);
        // сам слой касания не ловит — страница листается как обычно
        box.setClickable(false);
        av.setClickable(false);
        decor().addView(box, new FrameLayout.LayoutParams(-1, -1));
        area = box;
        bar.setTranslationY(Ui.dp(c, 30));
        bar.setAlpha(0f);
        bar.animate().translationY(0).alpha(1f).setDuration(Ui.anim(220)).start();

        Runnable update = () -> {
            int sy = w.getScrollY(), h = w.getHeight();
            if (sy < areaTop) areaTop = sy; // долистали выше начала — начало поднимается
            av.startOnScreen = areaTop - sy;
            av.invalidate();
            float screens = (sy + h - areaTop) / (float) Math.max(1, h);
            t2.setText(screens < 1.05f ? "Снимок — от линии до низа экрана"
                    : "Выбрано примерно " + String.format(java.util.Locale.ROOT, "%.1f", screens).replace('.', ',')
                    + " экрана · можно листать дальше");
        };
        areaScroll = update::run;
        w.getViewTreeObserver().addOnScrollChangedListener(areaScroll);
        update.run();
        cancel.setOnClickListener(v -> closeArea());
        ok.setOnClickListener(v -> {
            Haptics.click(v);
            int from = areaTop, to = w.getScrollY() + w.getHeight();
            closeArea();
            ui.postDelayed(() -> longShot(from, to), 60);
        });
    }

    private void closeArea() {
        View a = area;
        area = null;
        ByssWebView w = web();
        if (areaScroll != null && w != null) {
            try {
                w.getViewTreeObserver().removeOnScrollChangedListener(areaScroll);
            } catch (Throwable ignored) {
                // окно уже закрыто
            }
        }
        areaScroll = null;
        if (a != null) decor().removeView(a);
    }

    // ================================================================= сохранено

    private void saved(Uri uri, boolean cropped) {
        Context c = act;
        if (card != null) {
            decor().removeView(card);
            card = null;
        }
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(Ui.dp(c, 10), Ui.dp(c, 10), Ui.dp(c, 8), Ui.dp(c, 10));
        box.setBackground(Ui.round(Ui.card, Ui.dp(c, 20)));
        box.setElevation(Ui.dp(c, 14));
        box.setClickable(true);
        ImageView thumb = new ImageView(c);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumb.setBackground(Ui.round(Ui.pill, Ui.dp(c, 10)));
        thumb.setClipToOutline(true);
        box.addView(thumb, new LinearLayout.LayoutParams(Ui.dp(c, 54), Ui.dp(c, 78)));
        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(Ui.dp(c, 14), 0, Ui.dp(c, 4), 0);
        texts.addView(Ui.text(c, "Скриншот сохранён", 15, Ui.text, true));
        TextView sub = Ui.text(c, "Галерея → Pictures/" + LongShot.folder() + (cropped ? " · снята часть" : ""),
                12, Ui.text2, false);
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(sub);
        LinearLayout btns = new LinearLayout(c);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setPadding(0, Ui.dp(c, 8), 0, 0);
        TextView share = pill(c, "Поделиться", true);
        btns.addView(share);
        View gap = new View(c);
        btns.addView(gap, new LinearLayout.LayoutParams(Ui.dp(c, 6), 1));
        TextView open = pill(c, "Открыть", false);
        btns.addView(open);
        texts.addView(btns);
        box.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
        ImageView close = new ImageView(c);
        close.setImageResource(R.drawable.ic_close);
        close.setImageTintList(ColorStateList.valueOf(Ui.text2));
        int cp = Ui.dp(c, 8);
        close.setPadding(cp, cp, cp, cp);
        close.setBackground(Ui.borderlessRipple());
        box.addView(close, new LinearLayout.LayoutParams(Ui.dp(c, 36), Ui.dp(c, 36)));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        lp.setMargins(Ui.dp(c, 10), 0, Ui.dp(c, 10), act.bottomBarHeight() + Ui.dp(c, 10));
        decor().addView(box, lp);
        card = box;
        box.setTranslationY(Ui.dp(c, 60));
        box.setAlpha(0f);
        box.animate().translationY(0).alpha(1f).setDuration(Ui.anim(260))
                .setInterpolator(new PathInterpolator(0.2f, 0f, 0f, 1f)).start();

        Runnable hide = () -> hideCard(box);
        ui.postDelayed(hide, 8000);
        Runnable openIt = () -> {
            hideCard(box);
            try {
                act.startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/jpeg")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
            } catch (Exception e) {
                Toast.makeText(act, "Откройте Галерею → Pictures/" + LongShot.folder(), Toast.LENGTH_SHORT).show();
            }
        };
        thumb.setOnClickListener(v -> openIt.run());
        open.setOnClickListener(v -> openIt.run());
        share.setOnClickListener(v -> {
            hideCard(box);
            Intent send = new Intent(Intent.ACTION_SEND).setType("image/jpeg")
                    .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            act.startActivity(Intent.createChooser(send, "Поделиться скриншотом"));
        });
        close.setOnClickListener(v -> hideCard(box));

        new Thread(() -> {
            Bitmap tb = null;
            try {
                tb = act.getContentResolver().loadThumbnail(uri, new Size(Ui.dp(c, 108), Ui.dp(c, 156)), null);
            } catch (Throwable ignored) {
                // без миниатюры
            }
            Bitmap t = tb;
            if (t != null) ui.post(() -> thumb.setImageBitmap(t));
        }).start();
    }

    private void hideCard(View box) {
        if (card != box) return;
        card = null;
        box.animate().translationY(Ui.dp(act, 60)).alpha(0f).setDuration(Ui.anim(200))
                .withEndAction(() -> decor().removeView(box)).start();
    }
}
