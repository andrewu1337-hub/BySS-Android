package app.lev.browser;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Цветные значки сайтов для ярлыков на главной — как на iPhone: берём «apple-touch-icon»
 * или самый крупный значок, который сайт сам о себе указывает. Сохраняем на телефоне,
 * обновляем раз в 2 недели. Сторонние сервисы не используются — запрос идёт только на сам сайт.
 */
final class SiteIcons {

    private SiteIcons() { }

    private static final long MAX_AGE = 14L * 24 * 3600 * 1000;
    private static final ExecutorService exec = Executors.newFixedThreadPool(3);
    private static final Handler ui = new Handler(Looper.getMainLooper());
    private static final LruCache<String, Bitmap> mem = new LruCache<>(24);
    private static final Set<String> loading = new HashSet<>();
    private static final Set<String> failed = new HashSet<>();

    static String host(String url) {
        try {
            String h = Uri.parse(url).getHost();
            if (h == null) return null;
            h = h.toLowerCase(Locale.ROOT);
            return h;
        } catch (Exception e) {
            return null;
        }
    }

    private static File file(Context c, String host) {
        File d = new File(c.getFilesDir(), "site_icons");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return new File(d, host.replaceAll("[^a-z0-9.\\-]", "_") + ".png");
    }

    /** Сразу из памяти/диска или null; если значка нет — скачаем и вызовем done. */
    static Bitmap get(Context c, String url, Consumer<Bitmap> done) {
        String host = host(url);
        if (host == null) return null;
        Bitmap b = mem.get(host);
        if (b != null) return b;
        File f = file(c, host);
        if (f.exists()) {
            b = BitmapFactory.decodeFile(f.getAbsolutePath());
            if (b != null) {
                mem.put(host, b);
                if (System.currentTimeMillis() - f.lastModified() > MAX_AGE) fetch(c, url, host, null);
                return b;
            }
        }
        fetch(c, url, host, done);
        return null;
    }

    private static void fetch(Context c, String url, String host, Consumer<Bitmap> done) {
        synchronized (loading) {
            if (loading.contains(host) || failed.contains(host)) return;
            loading.add(host);
        }
        Context app = c.getApplicationContext();
        exec.execute(() -> {
            Bitmap best = null;
            try {
                String origin = "https://" + host;
                List<String> candidates = new ArrayList<>();
                try {
                    String html = text(origin + "/", 96 * 1024);
                    candidates.addAll(linksFrom(html, origin));
                } catch (Exception ignored) {
                    // страница не открылась — попробуем стандартные адреса
                }
                candidates.add(origin + "/apple-touch-icon.png");
                candidates.add(origin + "/apple-touch-icon-precomposed.png");
                candidates.add(origin + "/favicon.ico");
                Set<String> seen = new HashSet<>();
                for (String u : candidates) {
                    if (!seen.add(u)) continue;
                    Bitmap b = image(u);
                    if (b == null) continue;
                    if (best == null || b.getWidth() > best.getWidth()) best = b;
                    if (best.getWidth() >= 120) break;
                }
                if (best != null) {
                    try (FileOutputStream out = new FileOutputStream(file(app, host))) {
                        best.compress(Bitmap.CompressFormat.PNG, 100, out);
                    }
                    mem.put(host, best);
                }
            } catch (Exception ignored) {
                best = null;
            }
            Bitmap result = best;
            synchronized (loading) {
                loading.remove(host);
                if (result == null) failed.add(host);
            }
            if (done != null && result != null) ui.post(() -> done.accept(result));
        });
    }

    private static final Pattern LINK = Pattern.compile("<link\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern REL = Pattern.compile("rel\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern HREF = Pattern.compile("href\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern SIZES = Pattern.compile("sizes\\s*=\\s*[\"'](\\d+)x\\d+", Pattern.CASE_INSENSITIVE);

    /** Ссылки на значки из <head>: сначала apple-touch-icon, потом крупные icon. */
    private static List<String> linksFrom(String html, String origin) {
        List<String> touch = new ArrayList<>(), icons = new ArrayList<>();
        Matcher m = LINK.matcher(html);
        int bestSize = -1;
        while (m.find()) {
            String tag = m.group();
            Matcher r = REL.matcher(tag), h = HREF.matcher(tag);
            if (!r.find() || !h.find()) continue;
            String rel = r.group(1).toLowerCase(Locale.ROOT);
            String href = resolve(origin, h.group(1).replace("&amp;", "&"));
            if (href == null) continue;
            if (rel.contains("apple-touch-icon")) {
                touch.add(href);
            } else if (rel.contains("icon")) {
                Matcher s = SIZES.matcher(tag);
                int size = s.find() ? Integer.parseInt(s.group(1)) : 0;
                if (size > bestSize) {
                    icons.add(0, href);
                    bestSize = size;
                } else {
                    icons.add(href);
                }
            }
        }
        touch.addAll(icons);
        return touch;
    }

    private static String resolve(String origin, String href) {
        try {
            if (href.startsWith("data:")) return null;
            return new URL(new URL(origin + "/"), href).toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(7000);
        h.setReadTimeout(9000);
        h.setInstanceFollowRedirects(true);
        h.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36");
        return h;
    }

    private static String text(String url, int max) throws Exception {
        HttpURLConnection h = open(url);
        try (InputStream in = h.getInputStream()) {
            return new String(read(in, max), "UTF-8");
        } finally {
            h.disconnect();
        }
    }

    private static Bitmap image(String url) {
        try {
            HttpURLConnection h = open(url);
            try (InputStream in = h.getInputStream()) {
                if (h.getResponseCode() != 200) return null;
                byte[] b = read(in, 1024 * 1024);
                Bitmap bm = BitmapFactory.decodeByteArray(b, 0, b.length);
                if (bm == null || bm.getWidth() < 16) return null;
                return bm;
            } finally {
                h.disconnect();
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] read(InputStream in, int max) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0 && out.size() < max) out.write(buf, 0, n);
        return out.toByteArray();
    }

    // ------------------------------------------------------------ тёмная тема: значки на чёрном

    private static final LruCache<Bitmap, Bitmap> dark = new LruCache<>(32);

    /**
     * Значок для чёрной плитки. Белый фон значка убираем (рисунок остаётся как есть),
     * цветной фон (красный у Яндекса) становится чёрным, а белая буква — цвета этого фона.
     * Чёрный рисунок, который потерялся бы на чёрном, делаем светлым.
     */
    static Bitmap onBlack(Bitmap src) {
        if (src == null) return null;
        Bitmap hit = dark.get(src);
        if (hit != null) return hit;
        try {
            Bitmap b = src;
            if (Build.VERSION.SDK_INT >= 26 && b.getConfig() == Bitmap.Config.HARDWARE) {
                b = b.copy(Bitmap.Config.ARGB_8888, false);
            }
            int big = Math.max(b.getWidth(), b.getHeight());
            if (big > 256) {
                b = Bitmap.createScaledBitmap(b, Math.max(1, b.getWidth() * 256 / big),
                        Math.max(1, b.getHeight() * 256 / big), true);
            }
            Bitmap out = blackTile(b);
            dark.put(src, out);
            return out;
        } catch (Throwable e) {
            return src;
        }
    }

    private static float lum(float r, float g, float b) {
        return (r * 299 + g * 587 + b * 114) / 1000f;
    }

    private static float sat(float r, float g, float b) {
        float mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b));
        return mx <= 0 ? 0 : (mx - mn) / mx;
    }

    private static Bitmap blackTile(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight(), n = w * h;
        int[] px = new int[n];
        b.getPixels(px, 0, w, 0, 0, w, h);
        boolean[] clear = new boolean[n];
        for (int i = 0; i < n; i++) clear[i] = (px[i] >>> 24) < 128;

        int edge = 2 * (w + h);
        int[] idx = new int[edge];
        int e = 0;
        for (int x = 0; x < w; x++) {
            idx[e++] = x;
            idx[e++] = (h - 1) * w + x;
        }
        for (int y = 0; y < h; y++) {
            idx[e++] = y * w;
            idx[e++] = y * w + w - 1;
        }

        // 1) прозрачное поле вокруг рисунка (связанное с краем)
        boolean[] region = new boolean[n];
        int[] q = new int[n];
        int head = 0, tail = 0, clearEdge = 0;
        for (int i = 0; i < edge; i++) {
            int j = idx[i];
            if (clear[j]) {
                clearEdge++;
                if (!region[j]) {
                    region[j] = true;
                    q[tail++] = j;
                }
            }
        }
        while (head < tail) {
            int i = q[head++];
            int x = i % w, y = i / w;
            if (x > 0 && clear[i - 1] && !region[i - 1]) { region[i - 1] = true; q[tail++] = i - 1; }
            if (x < w - 1 && clear[i + 1] && !region[i + 1]) { region[i + 1] = true; q[tail++] = i + 1; }
            if (y > 0 && clear[i - w] && !region[i - w]) { region[i - w] = true; q[tail++] = i - w; }
            if (y < h - 1 && clear[i + w] && !region[i + w]) { region[i + w] = true; q[tail++] = i + w; }
        }
        boolean outside = tail > 0;

        // 2) цвет фона: края значка, а если вокруг прозрачно — «подложка» (белый круг или квадрат)
        int[] rr = new int[n], gg = new int[n], bb = new int[n];
        int m = 0;
        if (outside) {
            for (int i = 0; i < n; i++) {
                if (clear[i]) continue;
                int x = i % w, y = i / w;
                boolean rim = (x > 0 && region[i - 1]) || (x < w - 1 && region[i + 1])
                        || (y > 0 && region[i - w]) || (y < h - 1 && region[i + w]);
                if (!rim) continue;
                int c = px[i];
                rr[m] = (c >> 16) & 0xFF;
                gg[m] = (c >> 8) & 0xFF;
                bb[m] = c & 0xFF;
                m++;
            }
        } else {
            for (int k = 0; k < edge; k++) {
                int c = px[idx[k]];
                rr[m] = (c >> 16) & 0xFF;
                gg[m] = (c >> 8) & 0xFF;
                bb[m] = c & 0xFF;
                m++;
            }
        }
        char mode;
        float bgR = 0, bgG = 0, bgB = 0;
        if (m == 0) {
            mode = 'T';
        } else {
            java.util.Arrays.sort(rr, 0, m);
            java.util.Arrays.sort(gg, 0, m);
            java.util.Arrays.sort(bb, 0, m);
            bgR = rr[m / 2];
            bgG = gg[m / 2];
            bgB = bb[m / 2];
            float L = lum(bgR, bgG, bgB), S = sat(bgR, bgG, bgB);
            if (S < 0.18f && L > 170) mode = 'L';          // белый фон или белая подложка
            else if (L < 50) mode = 'D';                   // чёрный фон
            else if (clearEdge > edge * 0.4f) mode = 'T';  // цветной круг на прозрачном — оставляем
            else mode = 'C';                               // цветной фон (Яндекс)
        }

        final float T = mode == 'L' ? 88f : 64f, D0 = mode == 'L' ? 18f : 14f;
        float[] dist = new float[n];
        if (mode != 'T') {
            for (int i = 0; i < n; i++) {
                int c = px[i];
                float dr = ((c >> 16) & 0xFF) - bgR, dg = ((c >> 8) & 0xFF) - bgG, db = (c & 0xFF) - bgB;
                dist[i] = (float) Math.sqrt(dr * dr + dg * dg + db * db);
            }
            if (mode == 'C') {
                // цветной фон убираем везде — и внутри букв тоже
                for (int i = 0; i < n; i++) if (dist[i] < T) region[i] = true;
            } else {
                // белый/чёрный фон — только связанный с краем: белое внутри рисунка остаётся
                head = 0;
                tail = 0;
                for (int i = 0; i < n; i++) if (region[i]) q[tail++] = i;
                for (int k = 0; k < edge; k++) {
                    int j = idx[k];
                    if (!region[j] && dist[j] < T) {
                        region[j] = true;
                        q[tail++] = j;
                    }
                }
                while (head < tail) {
                    int i = q[head++];
                    int x = i % w, y = i / w;
                    if (x > 0) tail = grow(i - 1, region, clear, dist, T, q, tail);
                    if (x < w - 1) tail = grow(i + 1, region, clear, dist, T, q, tail);
                    if (y > 0) tail = grow(i - w, region, clear, dist, T, q, tail);
                    if (y < h - 1) tail = grow(i + w, region, clear, dist, T, q, tail);
                }
            }
        }

        // 3) убрать фон, мягко по краям (возвращаем исходный цвет рисунка)
        float[] fr = new float[n], fg = new float[n], fb = new float[n], fa = new float[n];
        int lightAch = 0;
        for (int i = 0; i < n; i++) {
            int c = px[i];
            float r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, bl = c & 0xFF, a = c >>> 24;
            if (mode != 'T' && region[i] && dist[i] < T) {
                float k = Math.max(0f, Math.min(1f, (dist[i] - D0) / (T - D0)));
                if (k < 0.02f) {
                    fa[i] = 0;
                    continue;
                }
                r = Math.max(0, Math.min(255, (r - (1 - k) * bgR) / k));
                g = Math.max(0, Math.min(255, (g - (1 - k) * bgG) / k));
                bl = Math.max(0, Math.min(255, (bl - (1 - k) * bgB) / k));
                a *= k;
            }
            fr[i] = r;
            fg[i] = g;
            fb[i] = bl;
            fa[i] = a;
            if (mode == 'C' && a > 20 && sat(r, g, bl) < 0.25f && lum(r, g, bl) >= 110) lightAch++;
        }
        boolean hasLight = lightAch > n / 100;

        // 4) перекрасить, чтобы на чёрном было видно
        int[] res = new int[n];
        for (int i = 0; i < n; i++) {
            float a = fa[i];
            if (a <= 0) continue;
            float r = fr[i], g = fg[i], bl = fb[i];
            float l = lum(r, g, bl), s = sat(r, g, bl);
            if (mode == 'C') {
                if (a > 20 && s < 0.25f) {
                    if (l >= 110 || !hasLight) {
                        r = bgR;
                        g = bgG;
                        bl = bgB;
                    } else {
                        r = 255;
                        g = 255;
                        bl = 255;
                    }
                }
            } else if (Math.max(r, Math.max(g, bl)) < 120) {
                r = g = bl = 245; // тёмный рисунок (чёрный, тёмно-фиолетовый) → белый; яркий красный не трогаем
            }
            res[i] = (Math.round(a) << 24) | (Math.round(r) << 16) | (Math.round(g) << 8) | Math.round(bl);
        }
        return Bitmap.createBitmap(res, w, h, Bitmap.Config.ARGB_8888);
    }

    private static int grow(int j, boolean[] region, boolean[] clear, float[] dist, float t, int[] q, int tail) {
        if (!region[j] && (clear[j] || dist[j] < t)) {
            region[j] = true;
            q[tail++] = j;
        }
        return tail;
    }

    /** Яркий цвет для плитки без значка — свой для каждого сайта (как на iOS). */
    static int colorFor(String key) {
        int[] palette = {0xFF0A84FF, 0xFF30D158, 0xFFFF9F0A, 0xFFFF453A, 0xFFBF5AF2, 0xFF64D2FF,
                0xFFFF375F, 0xFF5E5CE6, 0xFFFFD60A, 0xFF32D74B, 0xFFAC8E68, 0xFF66D4CF};
        int h = key == null ? 0 : Math.abs(key.hashCode());
        return palette[h % palette.length];
    }

    static void forget(Context c, String url) {
        String host = host(url);
        if (host == null) return;
        mem.remove(host);
        //noinspection ResultOfMethodCallIgnored
        file(c, host).delete();
        synchronized (loading) {
            failed.remove(host);
        }
    }
}
