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
}
