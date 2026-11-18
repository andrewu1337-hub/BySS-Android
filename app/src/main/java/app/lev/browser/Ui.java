package app.lev.browser;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

/** Цвета тёмной/светлой темы и маленькие помощники для интерфейса. */
final class Ui {

    private Ui() { }

    static boolean dark = true;
    static int bg, bar, pill, text, text2, line, danger, card, scrim, tile, tileGlyph, ripple, accent;

    static void apply(boolean isDark) {
        dark = isDark;
        if (isDark) {
            bg = 0xFF000000;
            bar = 0xFF0D0D0F;
            pill = 0xFF1C1C1E;
            text = 0xFFF2F2F7;
            text2 = 0xFF8E8E93;
            line = 0xFF2C2C2E;
            danger = 0xFFFF453A;
            card = 0xFF1C1C1E;
            scrim = 0x99000000;
            tile = 0xE61C1C1E;
            tileGlyph = 0xFFE5E5EA;
            ripple = 0x33FFFFFF;
            accent = 0xFFFFFFFF;
        } else {
            bg = 0xFFFFFFFF;
            bar = 0xFFF6F6F8;
            pill = 0xFFEBEBEF;
            text = 0xFF111113;
            text2 = 0xFF6C6C70;
            line = 0xFFDADADF;
            danger = 0xFFD70015;
            card = 0xFFFFFFFF;
            scrim = 0x55000000;
            tile = 0xF2FFFFFF;
            tileGlyph = 0xFF1C1C1E;
            ripple = 0x22000000;
            accent = 0xFF111113;
        }
    }

    /** Длительность анимации с учётом настройки «Скорость анимаций». */
    static long anim(long ms) {
        switch (Prefs.animSpeed()) {
            case 0: return 0;
            case 1: return Math.round(ms * 0.6);
            case 3: return Math.round(ms * 1.5);
            default: return ms;
        }
    }

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics()));
    }

    static GradientDrawable round(int color, float radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    static GradientDrawable outline(int strokeColor, int strokePx, float radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.TRANSPARENT);
        g.setStroke(strokePx, strokeColor);
        g.setCornerRadius(radiusPx);
        return g;
    }

    static Drawable ripple(Drawable content, Drawable mask) {
        return new RippleDrawable(ColorStateList.valueOf(ripple), content, mask);
    }

    static Drawable borderlessRipple() {
        return new RippleDrawable(ColorStateList.valueOf(ripple), null, null);
    }

    static ImageButton iconButton(Context c, int icon) {
        ImageButton b = new ImageButton(c);
        b.setImageResource(icon);
        b.setImageTintList(ColorStateList.valueOf(text));
        b.setBackground(borderlessRipple());
        int p = dp(c, 10);
        b.setPadding(p, p, p, p);
        b.setScaleType(ImageView.ScaleType.FIT_CENTER);
        return b;
    }

    static void setEnabledLook(View v, boolean enabled) {
        v.setEnabled(enabled);
        v.setAlpha(enabled ? 1f : 0.32f);
    }

    static TextView text(Context c, CharSequence s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setGravity(Gravity.CENTER_VERTICAL);
        return t;
    }

    /** Значок-«плитка» в стиле тёмной темы iOS: скруглённый квадрат и светлый символ. */
    static TextView glyphTile(Context c, String glyph, int sizeDp, float textSp) {
        TextView t = new TextView(c);
        t.setText(glyph);
        t.setGravity(Gravity.CENTER);
        t.setTextColor(tileGlyph);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, glyph.length() > 1 ? textSp * 0.72f : textSp);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setIncludeFontPadding(false);
        t.setBackground(round(tile, dp(c, sizeDp * 0.27f)));
        t.setElevation(dp(c, 2));
        return t;
    }

    // ------------------------------------------------------------ сердечко на «+»

    /** Кнопка «+» без серого круга: при нажатии из неё вылетает сердечко. */
    static void heartButton(View v) {
        v.setBackground(null);
    }

    /** Сердечко из центра кнопки: появляется, чуть растёт, всплывает и тает. */
    static void heart(View anchor) {
        long dur = anim(560);
        if (dur == 0) return;
        View rootView = anchor.getRootView();
        if (!(rootView instanceof android.view.ViewGroup)) return;
        android.view.ViewGroup vg = (android.view.ViewGroup) rootView;
        int[] a = new int[2], r = new int[2];
        anchor.getLocationInWindow(a);
        vg.getLocationInWindow(r);
        final float cx = a[0] - r[0] + anchor.getWidth() / 2f;
        final float cy = a[1] - r[1] + anchor.getHeight() / 2f;
        final float base = dp(anchor.getContext(), 13);
        final float rise = dp(anchor.getContext(), 34);
        final Heart d = new Heart(0xFFFF375F);
        vg.getOverlay().add(d);
        android.animation.ValueAnimator va = android.animation.ValueAnimator.ofFloat(0f, 1f);
        va.setDuration(dur);
        va.setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f));
        va.addUpdateListener(an -> {
            float t = (Float) an.getAnimatedValue();
            float pop = t < 0.25f ? 0.55f + 0.75f * (t / 0.25f) : 1.3f - 0.15f * ((t - 0.25f) / 0.75f);
            float s = base * pop;
            float y = cy - rise * t;
            d.setBounds(Math.round(cx - s), Math.round(y - s), Math.round(cx + s), Math.round(y + s));
            d.setAlpha(Math.round(255 * (t < 0.35f ? 1f : 1f - (t - 0.35f) / 0.65f)));
            vg.invalidate();
        });
        va.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                vg.getOverlay().remove(d);
            }
        });
        va.start();
    }

    private static final class Heart extends Drawable {
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path unit = new android.graphics.Path();
        private final android.graphics.Path path = new android.graphics.Path();
        private final android.graphics.Matrix m = new android.graphics.Matrix();

        Heart(int color) {
            paint.setColor(color);
            unit.moveTo(0.5f, 0.92f);
            unit.cubicTo(0.5f, 0.92f, 0.04f, 0.62f, 0.04f, 0.33f);
            unit.cubicTo(0.04f, 0.15f, 0.17f, 0.05f, 0.31f, 0.05f);
            unit.cubicTo(0.41f, 0.05f, 0.47f, 0.11f, 0.5f, 0.18f);
            unit.cubicTo(0.53f, 0.11f, 0.59f, 0.05f, 0.69f, 0.05f);
            unit.cubicTo(0.83f, 0.05f, 0.96f, 0.15f, 0.96f, 0.33f);
            unit.cubicTo(0.96f, 0.62f, 0.5f, 0.92f, 0.5f, 0.92f);
            unit.close();
        }

        @Override
        public void draw(android.graphics.Canvas canvas) {
            android.graphics.Rect b = getBounds();
            m.setScale(b.width(), b.height());
            m.postTranslate(b.left, b.top);
            unit.transform(m, path);
            canvas.drawPath(path, paint);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter cf) {
            paint.setColorFilter(cf);
        }

        @SuppressWarnings("deprecation")
        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }
}
