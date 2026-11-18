package app.lev.browser;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Если браузер вылетел — сохраняем текст ошибки на телефоне (никуда не отправляем).
 * При следующем запуске предлагаем скопировать его, чтобы прислать и быстро найти причину.
 */
final class CrashLog {

    private CrashLog() { }

    private static boolean installed;

    private static File file(Context c) {
        return new File(c.getFilesDir(), "last_crash.txt");
    }

    static void install(Context c) {
        if (installed) return;
        installed = true;
        Context app = c.getApplicationContext();
        Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, err) -> {
            try {
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                pw.println("BySS " + version(app) + " · Android " + Build.VERSION.RELEASE + " (API "
                        + Build.VERSION.SDK_INT + ") · " + Build.MODEL);
                pw.println("Поток: " + thread.getName());
                err.printStackTrace(pw);
                pw.flush();
                try (FileOutputStream out = new FileOutputStream(file(app))) {
                    out.write(sw.toString().getBytes(StandardCharsets.UTF_8));
                }
            } catch (Throwable ignored) {
                // не удалось записать — не страшно
            }
            if (prev != null) prev.uncaughtException(thread, err);
        });
    }

    private static String version(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    /** Показать предложение скопировать отчёт, если в прошлый раз был вылет. */
    static void offer(Activity a) {
        File f = file(a);
        if (!f.exists()) return;
        String text;
        try {
            text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            text = "";
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
        if (text.isEmpty()) return;
        final String report = text.length() > 6000 ? text.substring(0, 6000) : text;
        new AlertDialog.Builder(a)
                .setTitle("В прошлый раз браузер закрылся с ошибкой")
                .setMessage("Скопируйте отчёт и пришлите его — так причину найти проще всего. "
                        + "В отчёте — только строки кода, где случилась ошибка.")
                .setPositiveButton("Скопировать отчёт", (d, w) -> {
                    ClipboardManager cm = a.getSystemService(ClipboardManager.class);
                    if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("BySS — отчёт об ошибке", report));
                    Toast.makeText(a, "Отчёт скопирован", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Не нужно", null)
                .show();
    }
}
