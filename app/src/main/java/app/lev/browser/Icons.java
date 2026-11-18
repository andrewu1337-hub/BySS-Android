package app.lev.browser;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Значки браузера.
 * 1) Встроенные львы (золотой, белый) — меняют сам значок приложения (через activity-alias).
 * 2) «Мои значки» — любая картинка из галереи: ставим на чисто чёрный фон, тёмный рисунок
 *    делаем белым или золотым. Хранятся только на телефоне, ставятся ярлыком на главный экран.
 */
final class Icons {

    private Icons() { }

    static final String[] BUILT_IN = {"Золотой лев", "Белый лев"};
    private static final String[] ALIASES = {"app.lev.browser.IconGold", "app.lev.browser.IconWhite"};

    static int currentBuiltIn(Context c) {
        PackageManager pm = c.getPackageManager();
        for (int i = 0; i < ALIASES.length; i++) {
            int st = pm.getComponentEnabledSetting(new ComponentName(c, ALIASES[i]));
            boolean on = st == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    || (st == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && i == 0);
            if (on) return i;
        }
        return 0;
    }

    /** Поменять значок приложения. Лаунчеру нужно пару секунд, чтобы обновиться. */
    static void setBuiltIn(Context c, int which) {
        PackageManager pm = c.getPackageManager();
        // сначала включаем новый, потом выключаем остальные — чтобы значок не пропал совсем
        pm.setComponentEnabledSetting(new ComponentName(c, ALIASES[which]),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
        for (int i = 0; i < ALIASES.length; i++) {
            if (i == which) continue;
            pm.setComponentEnabledSetting(new ComponentName(c, ALIASES[i]),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
        }
        Prefs.put("app_icon", which);
    }

    // ------------------------------------------------------------ мои значки

    static File dir(Context c) {
        File d = new File(c.getFilesDir(), "my_icons");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    static List<File> mine(Context c) {
        File[] f = dir(c).listFiles((d, n) -> n.endsWith(".png"));
        List<File> out = new ArrayList<>();
        if (f == null) return out;
        Arrays.sort(f, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        out.addAll(Arrays.asList(f));
        return out;
    }

    static Bitmap load(File f) {
        return BitmapFactory.decodeFile(f.getAbsolutePath());
    }

    static Bitmap decode(Context c, Uri uri) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = c.getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, o);
            }
            int sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / sample > 1400) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            o2.inPreferredConfig = Bitmap.Config.ARGB_8888;
            try (InputStream in = c.getContentResolver().openInputStream(uri)) {
                return BitmapFactory.decodeStream(in, null, o2);
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** tone: 0 — белый, 1 — золотой, 2 — как есть (только на чёрном фоне). */
    static Bitmap make(Bitmap src, int tone) {
        int size = 432; // 108dp × 4 — адаптивный значок
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        canvas.drawColor(Color.BLACK);
        int w = src.getWidth(), h = src.getHeight();
        int[] px = new int[w * h];
        src.getPixels(px, 0, w, 0, 0, w, h);

        if (tone == 2) {
            drawFit(canvas, src, size, 0, 0, w, h);
            return out;
        }

        // яркость фона — по краям картинки
        int[] border = new int[2 * (w + h)];
        int k = 0;
        for (int x = 0; x < w; x++) {
            border[k++] = lum(px[x]);
            border[k++] = lum(px[(h - 1) * w + x]);
        }
        for (int y = 0; y < h; y++) {
            border[k++] = lum(px[y * w]);
            border[k++] = lum(px[y * w + w - 1]);
        }
        Arrays.sort(border);
        int bg = border[border.length / 2];
        int[] hist = new int[256];
        for (int p : px) hist[lum(p)]++;
        int lo = percentile(hist, px.length, 0.02), hi = percentile(hist, px.length, 0.98);
        boolean lightBg = bg > (lo + hi) / 2;
        float range = Math.max(1, lightBg ? bg - lo : hi - bg);

        // альфа рисунка: чем дальше от цвета фона, тем непрозрачнее
        int minX = w, minY = h, maxX = 0, maxY = 0;
        float[] alpha = new float[w * h];
        for (int i = 0; i < px.length; i++) {
            int l = lum(px[i]);
            int a0 = Color.alpha(px[i]);
            float a = (lightBg ? (bg - l) : (l - bg)) / range;
            a = Math.max(0, Math.min(1, (a - 0.08f) / 0.84f)) * (a0 / 255f);
            alpha[i] = a;
            if (a > 0.16f) {
                int x = i % w, y = i / w;
                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;
            }
        }
        if (maxX <= minX || maxY <= minY) {
            minX = 0;
            minY = 0;
            maxX = w - 1;
            maxY = h - 1;
        }
        int cw = maxX - minX + 1, ch = maxY - minY + 1;
        int[] art = new int[cw * ch];
        for (int y = 0; y < ch; y++) {
            float t = ch <= 1 ? 0 : y / (float) (ch - 1);
            int r, g, b;
            if (tone == 1) { // золото: светлое сверху, тёплое снизу
                r = Math.round(255 + (176 - 255) * t);
                g = Math.round(228 + (118 - 228) * t);
                b = Math.round(160 + (40 - 160) * t);
            } else {
                r = 255;
                g = 255;
                b = 255;
            }
            for (int x = 0; x < cw; x++) {
                float a = alpha[(minY + y) * w + minX + x];
                art[y * cw + x] = Color.argb(Math.round(a * 255), r, g, b);
            }
        }
        Bitmap a = Bitmap.createBitmap(art, cw, ch, Bitmap.Config.ARGB_8888);
        drawFit(canvas, a, size, 0, 0, cw, ch);
        return out;
    }

    private static void drawFit(Canvas canvas, Bitmap b, int size, int sx, int sy, int sw, int sh) {
        float box = size * 0.6f; // безопасная зона адаптивного значка
        float s = Math.min(box / sw, box / sh);
        float dw = sw * s, dh = sh * s;
        android.graphics.RectF dst = new android.graphics.RectF((size - dw) / 2f, (size - dh) / 2f,
                (size + dw) / 2f, (size + dh) / 2f);
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG
                | android.graphics.Paint.FILTER_BITMAP_FLAG);
        canvas.drawBitmap(b, new android.graphics.Rect(sx, sy, sx + sw, sy + sh), dst, p);
    }

    private static int lum(int c) {
        return (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000;
    }

    private static int percentile(int[] hist, int total, double q) {
        long need = Math.round(total * q), acc = 0;
        for (int i = 0; i < 256; i++) {
            acc += hist[i];
            if (acc >= need) return i;
        }
        return 255;
    }

    static File save(Context c, Bitmap b) {
        File f = new File(dir(c), "icon_" + System.currentTimeMillis() + ".png");
        try (FileOutputStream out = new FileOutputStream(f)) {
            b.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (Exception e) {
            return null;
        }
        return f;
    }

    /** Поставить значок на главный экран (ярлык «BySS» с этой картинкой). */
    static void pin(Context c, Bitmap icon) {
        ShortcutManager sm = c.getSystemService(ShortcutManager.class);
        if (sm == null || !sm.isRequestPinShortcutSupported()) {
            Toast.makeText(c, "Ваш экран «Домой» не поддерживает значки приложений", Toast.LENGTH_LONG).show();
            return;
        }
        Intent launch = new Intent(c, BrowserActivity.class).setAction(Intent.ACTION_MAIN);
        ShortcutInfo si = new ShortcutInfo.Builder(c, "byss_icon_" + System.currentTimeMillis())
                .setShortLabel(Prefs.browserName())
                .setIcon(Icon.createWithAdaptiveBitmap(icon))
                .setIntent(launch)
                .build();
        sm.requestPinShortcut(si, null);
        Toast.makeText(c, "Подтвердите добавление значка. Старый значок можно убрать с экрана — "
                + "браузер останется в списке приложений.", Toast.LENGTH_LONG).show();
    }
}
