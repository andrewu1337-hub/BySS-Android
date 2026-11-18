package app.lev.browser;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.format.DateFormat;

import java.io.OutputStream;
import java.util.Date;

/**
 * Длинный скриншот всей страницы: прокручиваем экран за экраном и склеиваем кадры.
 * Шапки и кнопки, «прилипшие» к экрану, после первого кадра прячем, чтобы не повторялись.
 * Очень длинную страницу уменьшаем, чтобы влезла целиком; режем только бесконечные ленты.
 */
final class LongShot {

    interface Listener {
        void progress(int frame, int frames);

        /** uri == null — не получилось (error — почему). cropped — снята только часть страницы. */
        void done(Uri uri, String error, boolean cropped);
    }

    private static final int MAX_PX = 32000;    // высота готовой картинки
    private static final int MIN_WIDTH = 540;   // уже не уменьшаем — дальше режем
    private static final long FRAME_DELAY = 450;

    private static final String HIDE_FIXED = "(function(){var a=[];document.querySelectorAll('body *').forEach(function(e){"
            + "var p=getComputedStyle(e).position;if(p==='fixed'||p==='sticky'){a.push([e,e.style.getPropertyValue('visibility'),"
            + "e.style.getPropertyPriority('visibility')]);e.style.setProperty('visibility','hidden','important');}});"
            + "window.__levShot=a;return a.length})()";
    private static final String SHOW_FIXED = "(function(){(window.__levShot||[]).forEach(function(p){"
            + "if(p[1])p[0].style.setProperty('visibility',p[1],p[2]);else p[0].style.removeProperty('visibility');});"
            + "window.__levShot=null})()";

    private final Activity act;
    private final LevWebView web;
    private final Listener listener;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private Bitmap out;
    private Canvas canvas;
    private int total;
    private int frames;
    private int frame;
    private int lastY = -1;
    private int startX;
    private int startY;
    private boolean cropped;
    private boolean cancelled;
    private boolean stopped;
    private int captured; // сколько пикселей страницы уже снято
    private boolean finished;
    private boolean scrollbar;

    LongShot(Activity act, LevWebView web, Listener listener) {
        this.act = act;
        this.web = web;
        this.listener = listener;
    }

    void cancel() {
        cancelled = true;
    }

    /** «Стоп»: больше не листаем, сохраняем то, что уже снято. */
    void stop() {
        stopped = true;
    }

    void start() {
        int w = web.getWidth();
        int h = web.getHeight();
        if (w == 0 || h == 0) {
            listener.done(null, "Страница ещё не готова", false);
            return;
        }
        startX = web.getScrollX();
        startY = web.getScrollY();
        int range = Math.max(web.scrollRange(), h);
        float s = Math.min(1f, 1080f / w);
        if (range * s > MAX_PX) s = Math.max((float) MAX_PX / range, (float) MIN_WIDTH / w);
        total = Math.min(range, (int) (MAX_PX / s));
        cropped = total < range;
        frames = Math.max(1, (total + h - 1) / h);
        try {
            out = Bitmap.createBitmap(Math.max(1, Math.round(w * s)), Math.max(1, Math.round(total * s)),
                    Bitmap.Config.RGB_565);
        } catch (Throwable e) {
            listener.done(null, "Не хватает памяти для такого длинного скриншота", false);
            return;
        }
        canvas = new Canvas(out);
        canvas.drawColor(Ui.bg);
        canvas.scale(s, s);
        scrollbar = web.isVerticalScrollBarEnabled();
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        web.scrollTo(startX, 0);
        listener.progress(1, frames);
        ui.postDelayed(this::capture, FRAME_DELAY);
    }

    private void capture() {
        if (cancelled || !web.isAttachedToWindow()) {
            finish(false);
            return;
        }
        if (stopped && frame > 0) {
            finish(true);
            return;
        }
        int sx = web.getScrollX();
        int sy = web.getScrollY();
        int w = web.getWidth();
        int h = web.getHeight();
        if (sy <= lastY) { // страница дальше не листается
            finish(true);
            return;
        }
        lastY = sy;
        try {
            canvas.save();
            canvas.translate(-sx, 0);
            canvas.clipRect(sx, sy, sx + w, Math.min(sy + h, total));
            web.draw(canvas);
            canvas.restore();
        } catch (Throwable e) {
            finish(false);
            return;
        }
        frame++;
        captured = Math.min(total, sy + h);
        if (frame == 1) web.evaluateJavascript(HIDE_FIXED, null);
        int next = sy + h;
        if (next >= total) {
            finish(true);
            return;
        }
        listener.progress(Math.min(frame + 1, frames), frames);
        web.scrollTo(sx, next);
        ui.postDelayed(this::capture, FRAME_DELAY);
    }

    private void finish(boolean ok) {
        if (finished) return;
        finished = true;
        web.evaluateJavascript(SHOW_FIXED, null);
        web.setVerticalScrollBarEnabled(scrollbar);
        web.setHorizontalScrollBarEnabled(scrollbar);
        web.scrollTo(startX, startY);
        Bitmap b = out;
        out = null;
        canvas = null;
        if (!ok || frame == 0 || b == null) {
            if (b != null) b.recycle();
            listener.done(null, cancelled ? null : "Не получилось снять страницу", false);
            return;
        }
        boolean part = cropped || (stopped && captured < total);
        if (stopped && captured > 0 && captured < total) {
            int ch = Math.max(1, Math.min(b.getHeight(), Math.round(captured * (b.getHeight() / (float) total))));
            Bitmap cut = Bitmap.createBitmap(b, 0, 0, b.getWidth(), ch);
            if (cut != b) b.recycle();
            b = cut;
        }
        Bitmap shotBmp = b;
        new Thread(() -> {
            Uri uri = null;
            String err = null;
            try {
                uri = save(shotBmp);
            } catch (Throwable e) {
                err = "Не получилось сохранить: " + e.getMessage();
            } finally {
                shotBmp.recycle();
            }
            Uri u = uri;
            String er = err;
            ui.post(() -> listener.done(u, er, part));
        }).start();
    }

    /** Папка в Галерее — по названию браузера (без запрещённых символов). */
    static String folder() {
        String n = Prefs.browserName().replaceAll("[\\/:*?\"<>|]", "").trim();
        return n.isEmpty() ? "BySS" : n;
    }

    private Uri save(Bitmap b) throws Exception {
        ContentResolver cr = act.getContentResolver();
        ContentValues v = new ContentValues();
        v.put(MediaStore.Images.Media.DISPLAY_NAME,
                folder() + "_" + DateFormat.format("yyyyMMdd_HHmmss", new Date()) + ".jpg");
        v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + folder());
        v.put(MediaStore.Images.Media.IS_PENDING, 1);
        Uri uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
        if (uri == null) throw new Exception("нет доступа к Галерее");
        try (OutputStream os = cr.openOutputStream(uri)) {
            if (os == null || !b.compress(Bitmap.CompressFormat.JPEG, 92, os)) throw new Exception("ошибка записи");
        } catch (Exception e) {
            cr.delete(uri, null, null);
            throw e;
        }
        v.clear();
        v.put(MediaStore.Images.Media.IS_PENDING, 0);
        cr.update(uri, v, null, null);
        return uri;
    }
}
