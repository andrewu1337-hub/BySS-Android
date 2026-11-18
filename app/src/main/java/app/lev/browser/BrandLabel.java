package app.lev.browser;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;

import java.util.HashMap;
import java.util.Map;

/**
 * Надпись с названием браузера (по умолчанию «BySS») вверху справа на начальном экране.
 * 13 стилей: обычные шрифты и «GTA» — жирные буквы с чёрной обводкой и тенью.
 * Шрифты (свободные, OFL/GUST) скачиваются при сборке в assets/fonts.
 */
final class BrandLabel extends View {

    static final String[] NAMES = {
            "Минимал", "Чёткий", "Тонкий", "Округлый", "Элегант", "Классика", "Гео", "Каллиграфия",
            "Плакат", "Техно", "GTA белый", "GTA золото", "GTA с наклоном"};

    private static final Map<String, Typeface> cache = new HashMap<>();

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private String text = "BySS";
    private int style;
    private float sizeScale = 1f;

    BrandLabel(Context c) {
        super(c);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        setStyle(Prefs.brandFont());
    }

    /** Множитель размера (для крупного образца в настройках). */
    void setSizeScale(float s) {
        sizeScale = s;
        setStyle(style);
    }

    void setText(String t) {
        text = t == null || t.isEmpty() ? "BySS" : t;
        requestLayout();
        invalidate();
    }

    static boolean isGta(int style) {
        return style >= 10;
    }

    void setStyle(int s) {
        style = Math.max(0, Math.min(NAMES.length - 1, s));
        Context c = getContext();
        fill.setShader(null);
        fill.clearShadowLayer();
        fill.setTextSkewX(0);
        stroke.setTextSkewX(0);
        fill.setLetterSpacing(0);
        stroke.setLetterSpacing(0);
        float sp = 15;
        switch (style) {
            case 0: fill.setTypeface(font(c, "montserrat.ttf", 300, Typeface.DEFAULT)); fill.setLetterSpacing(0.16f); break;
            case 1: fill.setTypeface(font(c, "montserrat.ttf", 650, Typeface.DEFAULT_BOLD)); fill.setLetterSpacing(0.02f); break;
            case 2: fill.setTypeface(font(c, "firasans_thin.ttf", 0, Typeface.create("sans-serif-thin", Typeface.NORMAL)));
                fill.setLetterSpacing(0.2f); sp = 16; break;
            case 3: fill.setTypeface(font(c, "poppins_medium.ttf", 0, Typeface.DEFAULT_BOLD)); break;
            case 4: fill.setTypeface(font(c, "lora_italic.ttf", 520, Typeface.create(Typeface.SERIF, Typeface.ITALIC))); sp = 17; break;
            case 5: fill.setTypeface(font(c, "cardo.ttf", 0, Typeface.SERIF)); fill.setLetterSpacing(0.12f); sp = 17; break;
            case 6: fill.setTypeface(font(c, "adventor_bold.otf", 0, Typeface.DEFAULT_BOLD)); fill.setLetterSpacing(0.04f); break;
            case 7: fill.setTypeface(font(c, "chorus.otf", 0, Typeface.create("cursive", Typeface.NORMAL))); sp = 21; break;
            case 8: fill.setTypeface(font(c, "anton.ttf", 0, Typeface.create("sans-serif-condensed", Typeface.BOLD)));
                fill.setLetterSpacing(0.06f); sp = 17; break;
            case 9: fill.setTypeface(font(c, "anonymous.ttf", 0, Typeface.MONOSPACE)); fill.setLetterSpacing(0.08f); break;
            case 10:
            case 11: fill.setTypeface(font(c, "anton.ttf", 0, Typeface.create("sans-serif-condensed", Typeface.BOLD)));
                fill.setLetterSpacing(0.03f); sp = 21; break;
            default: fill.setTypeface(font(c, "archivo_black.ttf", 0, Typeface.DEFAULT_BOLD));
                fill.setTextSkewX(-0.22f); sp = 19; break;
        }
        fill.setTextSize(Ui.dp(c, sp * sizeScale));
        stroke.setTypeface(fill.getTypeface());
        stroke.setTextSize(fill.getTextSize());
        stroke.setLetterSpacing(fill.getLetterSpacing());
        stroke.setTextSkewX(fill.getTextSkewX());
        if (isGta(style)) {
            stroke.setColor(0xFF000000);
            stroke.setStrokeWidth(fill.getTextSize() * 0.13f);
            stroke.setShadowLayer(fill.getTextSize() * 0.12f, fill.getTextSize() * 0.06f, fill.getTextSize() * 0.08f, 0xC0000000);
        } else {
            fill.setShadowLayer(Ui.dp(c, 3), 0, Ui.dp(c, 1), 0x66000000);
        }
        requestLayout();
        invalidate();
    }

    private static Typeface font(Context c, String file, int weight, Typeface fallback) {
        String key = file + "#" + weight;
        Typeface t = cache.get(key);
        if (t != null) return t;
        try {
            Typeface.Builder b = new Typeface.Builder(c.getAssets(), "fonts/" + file);
            if (weight > 0) b.setFontVariationSettings("'wght' " + weight);
            t = b.build();
        } catch (Exception e) {
            t = null;
        }
        if (t == null) t = fallback;
        cache.put(key, t);
        return t;
    }

    private float pad() {
        return isGta(style) ? fill.getTextSize() * 0.28f : Ui.dp(getContext(), 2);
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        Paint.FontMetrics fm = fill.getFontMetrics();
        float w = fill.measureText(text) + pad() * 2 + Math.abs(fill.getTextSkewX()) * fill.getTextSize();
        float h = (fm.descent - fm.ascent) + pad() * 2;
        setMeasuredDimension(resolveSize((int) Math.ceil(w), wSpec), resolveSize((int) Math.ceil(h), hSpec));
    }

    @Override
    protected void onDraw(Canvas c) {
        Paint.FontMetrics fm = fill.getFontMetrics();
        float x = pad() + Math.max(0, -fill.getTextSkewX()) * fill.getTextSize() * 0.15f;
        float base = pad() - fm.ascent;
        if (isGta(style)) {
            int top, bottom;
            if (style == 11) {
                top = 0xFFFFE27A;
                bottom = 0xFFD68C1E;
            } else {
                top = 0xFFFFFFFF;
                bottom = 0xFFCFCFD4;
            }
            fill.setShader(new LinearGradient(0, base + fm.ascent, 0, base, top, bottom, Shader.TileMode.CLAMP));
            c.drawText(text, x, base, stroke);
            c.drawText(text, x, base, fill);
        } else {
            fill.setShader(null);
            fill.setColor(Ui.dark ? 0xFFF2F2F7 : 0xFF111113);
            c.drawText(text, x, base, fill);
        }
    }
}
