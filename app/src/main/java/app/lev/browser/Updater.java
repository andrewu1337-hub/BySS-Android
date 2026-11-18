package app.lev.browser;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Обновление браузера вручную из меню: смотрим последнюю сборку на GitHub,
 * спрашиваем «Точно обновить?», скачиваем и открываем установщик Android.
 * Вкладки, настройки и входы на сайтах при обновлении сохраняются.
 */
final class Updater {

    private static final String API = "https://api.github.com/repos/andrewu1337-hub/Lev/releases/latest";
    private static final String APK = "https://github.com/andrewu1337-hub/Lev/releases/latest/download/Lev.apk";
    private static final String MIME = "application/vnd.android.package-archive";
    private static final String NL = System.lineSeparator();

    private Updater() {
    }

    static int currentBuild(Context c) {
        try {
            return (int) c.getPackageManager().getPackageInfo(c.getPackageName(), 0).getLongVersionCode();
        } catch (Exception e) {
            return 0;
        }
    }

    static void check(Activity a) {
        Toast.makeText(a, "Проверяю обновления…", Toast.LENGTH_SHORT).show();
        int current = currentBuild(a);
        new Thread(() -> {
            int latest = -1;
            String url = APK;
            try {
                JSONObject j = new JSONObject(get(API));
                String tag = j.optString("tag_name");
                latest = Integer.parseInt(tag.substring(tag.lastIndexOf('-') + 1).trim());
                JSONArray assets = j.optJSONArray("assets");
                if (assets != null) {
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject o = assets.optJSONObject(i);
                        if (o != null && "Lev.apk".equals(o.optString("name"))) url = o.optString("browser_download_url", APK);
                    }
                }
            } catch (Exception ignored) {
                latest = -1;
            }
            int found = latest;
            String apk = url;
            a.runOnUiThread(() -> {
                if (a.isFinishing()) return;
                if (found < 0) {
                    Toast.makeText(a, "Не получилось проверить обновления — нет сети?", Toast.LENGTH_LONG).show();
                } else if (found <= current) {
                    new AlertDialog.Builder(a)
                            .setTitle("Обновлений нет")
                            .setMessage("У вас последняя версия — сборка " + current + ".")
                            .setPositiveButton("Хорошо", null)
                            .show();
                } else {
                    new AlertDialog.Builder(a)
                            .setTitle("Обновить браузер?")
                            .setMessage("Есть новая сборка " + found + " (у вас — " + current + ")." + NL + NL
                                    + "Точно обновить? Вкладки, настройки и входы на сайтах сохранятся.")
                            .setPositiveButton("Обновить", (d, w) -> start(a, apk))
                            .setNegativeButton("Не сейчас", null)
                            .show();
                }
            });
        }).start();
    }

    private static void start(Activity a, String url) {
        if (!a.getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(a)
                    .setTitle("Нужно разрешение")
                    .setMessage("Разрешите «Лев» устанавливать обновления: в настройках включите "
                            + "«Разрешить установку из этого источника», вернитесь и нажмите «Обновить браузер» ещё раз.")
                    .setPositiveButton("Открыть настройки", (d, w) -> {
                        try {
                            a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + a.getPackageName())));
                        } catch (Exception e) {
                            Toast.makeText(a, "Откройте настройки телефона → Приложения → Лев", Toast.LENGTH_LONG).show();
                        }
                    })
                    .setNegativeButton("Отмена", null)
                    .show();
            return;
        }
        Context app = a.getApplicationContext();
        try {
            File dir = app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (dir != null) {
                //noinspection ResultOfMethodCallIgnored
                new File(dir, "Lev-update.apk").delete();
            }
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
            r.setTitle("Обновление браузера «Лев»");
            r.setMimeType(MIME);
            r.addRequestHeader("User-Agent", "Lev-Browser");
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE);
            r.setDestinationInExternalFilesDir(app, Environment.DIRECTORY_DOWNLOADS, "Lev-update.apk");
            DownloadManager dm = app.getSystemService(DownloadManager.class);
            long id = dm.enqueue(r);
            Toast.makeText(a, "Скачиваю обновление…", Toast.LENGTH_SHORT).show();
            Handler h = new Handler(Looper.getMainLooper());
            h.postDelayed(new Runnable() {
                @Override
                public void run() {
                    int status = -1;
                    try (Cursor c = dm.query(new DownloadManager.Query().setFilterById(id))) {
                        if (c != null && c.moveToFirst()) {
                            status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                        }
                    } catch (Exception ignored) {
                        status = DownloadManager.STATUS_FAILED;
                    }
                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        install(app, dm.getUriForDownloadedFile(id));
                    } else if (status == DownloadManager.STATUS_FAILED || status == -1) {
                        Toast.makeText(app, "Обновление не скачалось. Попробуйте ещё раз", Toast.LENGTH_LONG).show();
                    } else {
                        h.postDelayed(this, 1000);
                    }
                }
            }, 1000);
        } catch (Exception e) {
            Toast.makeText(a, "Не получилось начать обновление: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private static void install(Context c, Uri uri) {
        if (uri == null) {
            Toast.makeText(c, "Файл обновления не найден", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            c.startActivity(new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, MIME)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            Toast.makeText(c, "Откройте «Скачанные файлы» и установите Lev-update.apk", Toast.LENGTH_LONG).show();
        }
    }

    private static String get(String url) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(8000);
        h.setReadTimeout(10000);
        h.setRequestProperty("User-Agent", "Lev-Browser");
        h.setRequestProperty("Accept", "application/vnd.github+json");
        try (InputStream in = h.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            h.disconnect();
        }
    }
}
