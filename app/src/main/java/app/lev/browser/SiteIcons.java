package app.lev.browser;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
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
