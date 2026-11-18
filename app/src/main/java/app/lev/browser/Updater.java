package app.lev.browser;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * Обновление браузера вручную из меню: смотрим последнюю сборку на GitHub, спрашиваем «Точно обновить?»,
 * сами скачиваем файл (с понятным прогрессом: сколько скачано, скорость, что происходит)
 * и передаём его установщику Android. Вкладки, настройки и входы на сайтах сохраняются.
 */
final class Updater {

    private static final String API = "https://api.github.com/repos/andrewu1337-hub/Lev/releases/latest";
    private static final String APK = "https://github.com/andrewu1337-hub/Lev/releases/latest/download/Lev.apk";
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

    // ------------------------------------------------------------ окно прогресса

    private static final Handler ui = new Handler(Looper.getMainLooper());
    private static volatile boolean cancelled;
    private static volatile boolean running;
    private static AlertDialog dialog;
    private static TextView stepText, detailText;
    private static ProgressBar bar;
    /** Окно подтверждения установки, если его не удалось показать сразу (приложение было свёрнуто). */
    static Intent pendingConfirm;
    private static WeakReference<Activity> host = new WeakReference<>(null);
    private static boolean hooked;
    private static int resumed;

    /** Следим, открыт ли браузер на экране: окно установки показываем только поверх него. */
    private static void hook(Activity a) {
        host = new WeakReference<>(a);
        if (hooked) return;
        hooked = true;
        resumed = 1;
        a.getApplication().registerActivityLifecycleCallbacks(new android.app.Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityResumed(Activity act) {
                resumed++;
                host = new WeakReference<>(act);
                if (pendingConfirm != null) {
                    Intent i = pendingConfirm;
                    pendingConfirm = null;
                    try {
                        act.startActivity(i);
                    } catch (Exception ignored) {
                        // окно установки больше недоступно
                    }
                }
            }

            @Override
            public void onActivityPaused(Activity act) {
                resumed = Math.max(0, resumed - 1);
            }

            @Override
            public void onActivityCreated(Activity act, android.os.Bundle b) { }

            @Override
            public void onActivityStarted(Activity act) { }

            @Override
            public void onActivityStopped(Activity act) { }

            @Override
            public void onActivitySaveInstanceState(Activity act, android.os.Bundle b) { }

            @Override
            public void onActivityDestroyed(Activity act) { }
        });
    }

    private static void showDialog(Activity a, int build) {
        Context c = a;
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(c, 24);
        box.setPadding(p, Ui.dp(c, 8), p, Ui.dp(c, 4));
        stepText = Ui.text(c, "Подключаюсь к GitHub…", 15, Ui.text, true);
        box.addView(stepText);
        bar = new ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal);
        bar.setIndeterminate(true);
        bar.setMax(1000);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, Ui.dp(c, 18));
        bp.topMargin = Ui.dp(c, 14);
        box.addView(bar, bp);
        detailText = Ui.text(c, "Сборка " + build, 13, Ui.text2, false);
        detailText.setPadding(0, Ui.dp(c, 8), 0, 0);
        detailText.setGravity(Gravity.START);
        box.addView(detailText);
        TextView hint = Ui.text(c, "Можно свернуть браузер — окно установки появится, когда вы вернётесь.",
                12, Ui.text2, false);
        hint.setPadding(0, Ui.dp(c, 10), 0, 0);
        box.addView(hint);
        dialog = new AlertDialog.Builder(a)
                .setTitle("Обновление " + Prefs.browserName())
                .setView(box)
                .setCancelable(false)
                .setNegativeButton("Отмена", (d, w) -> {
                    cancelled = true;
                    Toast.makeText(a, "Обновление отменено", Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private static void step(String step, String detail, int permille) {
        ui.post(() -> {
            if (stepText != null) stepText.setText(step);
            if (detailText != null && detail != null) detailText.setText(detail);
            if (bar != null) {
                if (permille < 0) {
                    bar.setIndeterminate(true);
                } else {
                    bar.setIndeterminate(false);
                    bar.setProgress(permille);
                }
            }
        });
    }

    private static void closeDialog() {
        ui.post(() -> {
            try {
                if (dialog != null && dialog.isShowing()) dialog.dismiss();
            } catch (Exception ignored) {
                // окно уже закрыто
            }
            dialog = null;
            stepText = null;
            detailText = null;
            bar = null;
        });
    }

    /** Ошибка: закрыть прогресс и объяснить, что случилось, с кнопкой «Повторить». */
    private static void fail(String title, String why, String url, int build) {
        running = false;
        closeDialog();
        ui.post(() -> {
            Activity a = host.get();
            if (a == null || a.isFinishing()) {
                return;
            }
            new AlertDialog.Builder(a)
                    .setTitle(title)
                    .setMessage(why)
                    .setPositiveButton("Повторить", (d, w) -> start(a, url, build))
                    .setNegativeButton("Закрыть", null)
                    .show();
        });
    }

    // ------------------------------------------------------------ проверка

    static void check(Activity a) {
        if (running && dialog != null && dialog.isShowing()) {
            Toast.makeText(a, "Обновление уже идёт", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(a, "Проверяю обновления…", Toast.LENGTH_SHORT).show();
        int current = currentBuild(a);
        new Thread(() -> {
            int latest = -1;
            String url = APK;
            String err = null;
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
            } catch (java.net.UnknownHostException e) {
                err = "Нет интернета — проверьте сеть и попробуйте ещё раз.";
            } catch (java.net.SocketTimeoutException e) {
                err = "GitHub долго не отвечает — попробуйте чуть позже.";
            } catch (Exception e) {
                err = "Не получилось узнать последнюю сборку (" + e.getClass().getSimpleName() + ").";
            }
            int found = latest;
            String apk = url;
            String error = err;
            a.runOnUiThread(() -> {
                if (a.isFinishing()) return;
                if (found < 0) {
                    new AlertDialog.Builder(a).setTitle("Не получилось проверить обновления")
                            .setMessage(error == null ? "Попробуйте ещё раз." : error)
                            .setPositiveButton("Хорошо", null).show();
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
                            .setPositiveButton("Обновить", (d, w) -> start(a, apk, found))
                            .setNegativeButton("Не сейчас", null)
                            .show();
                }
            });
        }).start();
    }

    // ------------------------------------------------------------ скачивание

    private static void start(Activity a, String url, int build) {
        if (!a.getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(a)
                    .setTitle("Нужно разрешение")
                    .setMessage("Разрешите «" + Prefs.browserName() + "» устанавливать обновления: в настройках включите "
                            + "«Разрешить установку из этого источника», вернитесь и нажмите «Обновить браузер» ещё раз.")
                    .setPositiveButton("Открыть настройки", (d, w) -> {
                        try {
                            a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + a.getPackageName())));
                        } catch (Exception e) {
                            Toast.makeText(a, "Откройте настройки телефона → Приложения → " + Prefs.browserName(), Toast.LENGTH_LONG).show();
                        }
                    })
                    .setNegativeButton("Отмена", null)
                    .show();
            return;
        }
        hook(a);
        cancelled = false;
        running = true;
        showDialog(a, build);
        Context app = a.getApplicationContext();
        new Thread(() -> download(app, url, build), "BySS-update").start();
    }

    private static void download(Context app, String url, int build) {
        File file = new File(app.getCacheDir(), "update.apk");
        //noinspection ResultOfMethodCallIgnored
        file.delete();
        HttpURLConnection h = null;
        try {
            step("Подключаюсь к GitHub…", "Сборка " + build, -1);
            h = (HttpURLConnection) new URL(url).openConnection();
            h.setConnectTimeout(15000);
            h.setReadTimeout(20000);
            h.setInstanceFollowRedirects(true);
            h.setRequestProperty("User-Agent", "Lev-Browser");
            int code = h.getResponseCode();
            if (code != 200) {
                fail("Обновление не скачалось", "GitHub ответил ошибкой " + code + ". Попробуйте чуть позже.", url, build);
                return;
            }
            long total = h.getContentLengthLong();
            long done = 0, lastUi = 0, lastBytes = 0, lastT = System.currentTimeMillis();
            double speed = 0;
            byte[] buf = new byte[64 * 1024];
            try (InputStream in = h.getInputStream(); OutputStream out = new FileOutputStream(file)) {
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (cancelled) {
                        running = false;
                        //noinspection ResultOfMethodCallIgnored
                        file.delete();
                        return;
                    }
                    out.write(buf, 0, n);
                    done += n;
                    long now = System.currentTimeMillis();
                    if (now - lastUi > 250) {
                        double inst = (done - lastBytes) / Math.max(0.001, (now - lastT) / 1000.0);
                        speed = speed == 0 ? inst : speed * 0.7 + inst * 0.3;
                        lastBytes = done;
                        lastT = now;
                        lastUi = now;
                        String d = total > 0
                                ? mb(done) + " из " + mb(total) + " · " + mb((long) speed) + "/с"
                                + (speed > 0 ? " · осталось " + left((total - done) / speed) : "")
                                : mb(done) + " · " + mb((long) speed) + "/с";
                        int pm = total > 0 ? (int) (done * 1000 / total) : -1;
                        step("Скачиваю сборку " + build + (total > 0 ? " — " + (pm / 10) + "%" : "…"), d, pm);
                    }
                }
            }
            if (total > 0 && done < total) {
                fail("Обновление скачалось не до конца", "Связь прервалась на " + mb(done) + " из " + mb(total)
                        + ". Нажмите «Повторить».", url, build);
                return;
            }
        } catch (java.net.UnknownHostException e) {
            fail("Нет интернета", "Не получается подключиться к GitHub. Проверьте сеть и нажмите «Повторить».", url, build);
            return;
        } catch (java.net.SocketTimeoutException e) {
            fail("Связь слишком медленная", "Скачивание остановилось — сервер долго не отвечал. Нажмите «Повторить».", url, build);
            return;
        } catch (Exception e) {
            if (cancelled) {
                running = false;
                return;
            }
            fail("Обновление не скачалось", "Ошибка: " + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : " — " + e.getMessage()), url, build);
            return;
        } finally {
            if (h != null) h.disconnect();
        }
        if (cancelled) {
            running = false;
            return;
        }

        // проверяем файл: это точно наш браузер и он новее
        step("Проверяю файл…", mb(file.length()), 1000);
        PackageInfo pi = app.getPackageManager().getPackageArchiveInfo(file.getAbsolutePath(), 0);
        if (pi == null || !app.getPackageName().equals(pi.packageName)) {
            fail("Файл обновления повреждён", "Скачанный файл не похож на браузер. Нажмите «Повторить».", url, build);
            return;
        }
        install(app, file, url, build);
    }

    // ------------------------------------------------------------ установка

    private static void install(Context app, File file, String url, int build) {
        step("Готовлю установку…", "Сейчас Android спросит, обновить ли приложение", -1);
        PackageInstaller.Session session = null;
        try {
            PackageInstaller pi = app.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params =
                    new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(app.getPackageName());
            params.setSize(file.length());
            int id = pi.createSession(params);
            session = pi.openSession(id);
            try (InputStream in = new FileInputStream(file);
                 OutputStream out = session.openWrite("base.apk", 0, file.length())) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                session.fsync(out);
            }
            Intent cb = new Intent(app, UpdateReceiver.class).putExtra("build", build).putExtra("url", url);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE;
            PendingIntent pend = PendingIntent.getBroadcast(app, id, cb, flags);
            session.commit(pend.getIntentSender());
            session.close();
            step("Подтвердите обновление", "В окне Android нажмите «Обновить». Браузер перезапустится сам.", 1000);
        } catch (Exception e) {
            if (session != null) session.abandon();
            fail("Не получилось начать установку", "Ошибка: " + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : " — " + e.getMessage()), url, build);
        }
    }

    /** Ответ установщика Android (из UpdateReceiver). */
    static void onInstallerResult(Context c, Intent i) {
        int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        int build = i.getIntExtra("build", 0);
        String url = i.getStringExtra("url");
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @SuppressWarnings("deprecation")
            Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm == null) return;
            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Activity a = host.get();
            if (resumed > 0 && a != null && !a.isFinishing()) {
                try {
                    a.startActivity(confirm);
                    return;
                } catch (Exception ignored) {
                    // покажем позже
                }
            }
            pendingConfirm = confirm; // браузер свёрнут — покажем, когда его снова откроют
            return;
        }
        running = false;
        closeDialog();
        if (status == PackageInstaller.STATUS_SUCCESS) return; // браузер сейчас перезапустится
        String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        String why;
        switch (status) {
            case PackageInstaller.STATUS_FAILURE_ABORTED:
                why = "Установку отменили. Можно повторить в любой момент: ⋮ → «Обновить браузер».";
                break;
            case PackageInstaller.STATUS_FAILURE_STORAGE:
                why = "На телефоне мало места. Освободите около 100 МБ и повторите.";
                break;
            case PackageInstaller.STATUS_FAILURE_CONFLICT:
            case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE:
                why = "Android не принял обновление поверх текущей версии (подпись не совпадает). "
                        + "Скачайте Lev.apk со страницы проекта и установите заново.";
                break;
            case PackageInstaller.STATUS_FAILURE_BLOCKED:
                why = "Установку заблокировал телефон. Проверьте разрешение «Установка из этого источника».";
                break;
            default:
                why = "Android не установил обновление" + (msg == null ? "." : ": " + msg);
        }
        ui.post(() -> {
            Activity a = host.get();
            if (a != null && !a.isFinishing()) {
                new AlertDialog.Builder(a).setTitle("Обновление не установлено").setMessage(why)
                        .setPositiveButton("Повторить", (d, w) -> start(a, url == null ? APK : url, build))
                        .setNegativeButton("Закрыть", null).show();
            } else {
                Toast.makeText(c, why, Toast.LENGTH_LONG).show();
            }
        });
    }

    /** Браузер снова открыт: если окно установки не показалось — показать сейчас; после обновления — сказать об этом. */
    static void onResume(Activity a) {
        int now = currentBuild(a);
        int last = Prefs.sp().getInt("last_build", 0);
        if (last != now) {
            Prefs.put("last_build", now);
            if (last > 0 && now > last) {
                Toast.makeText(a, Prefs.browserName() + " обновлён — сборка " + now, Toast.LENGTH_LONG).show();
            }
        }
    }

    private static String mb(long bytes) {
        return String.format(Locale.ROOT, "%.1f МБ", bytes / 1048576.0).replace('.', ',');
    }

    private static String left(double sec) {
        if (sec < 1) return "секунда";
        if (sec < 60) return Math.round(sec) + " с";
        return Math.round(sec / 60) + " мин";
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
