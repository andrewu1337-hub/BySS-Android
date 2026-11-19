package app.lev.browser;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Загрузки браузера: что скачивается сейчас и что уже скачано (как в Chrome). */
final class Downloads {

    private Downloads() { }

    private static final String KEY = "dl_ids";
    private static final int KEEP = 40;

    static final class Item {
        long id;
        String name;
        int status;
        long got;
        long total;
        int reason;

        boolean active() {
            return status == DownloadManager.STATUS_RUNNING || status == DownloadManager.STATUS_PENDING
                    || status == DownloadManager.STATUS_PAUSED;
        }

        boolean done() {
            return status == DownloadManager.STATUS_SUCCESSFUL;
        }

        /** Доля скачанного 0…1, или -1 — размер неизвестен. */
        float fraction() {
            return total > 0 ? Math.min(1f, got / (float) total) : -1f;
        }

        String line() {
            switch (status) {
                case DownloadManager.STATUS_SUCCESSFUL:
                    return "Скачано" + (total > 0 ? " · " + size(total) : "");
                case DownloadManager.STATUS_FAILED:
                    return "Не удалось скачать";
                case DownloadManager.STATUS_PAUSED:
                    return reason == DownloadManager.PAUSED_WAITING_FOR_NETWORK || reason == DownloadManager.PAUSED_QUEUED_FOR_WIFI
                            ? "Ждёт интернет" : "Пауза" + progressText();
                case DownloadManager.STATUS_PENDING:
                    return "В очереди…";
                default:
                    return "Скачивается" + progressText();
            }
        }

        private String progressText() {
            if (total > 0) {
                return " · " + size(got) + " из " + size(total) + " · " + Math.round(fraction() * 100) + "%";
            }
            return got > 0 ? " · " + size(got) : "…";
        }
    }

    static String size(long b) {
        if (b < 1024 * 1024) return Math.max(1, Math.round(b / 1024f)) + " КБ";
        return String.format(Locale.ROOT, "%.1f", b / 1048576f).replace('.', ',') + " МБ";
    }

    /** Запомнить загрузку, начатую в браузере. */
    static void remember(long id) {
        List<Long> ids = ids();
        ids.remove(id);
        ids.add(0, id);
        while (ids.size() > KEEP) ids.remove(ids.size() - 1);
        save(ids);
    }

    private static List<Long> ids() {
        List<Long> out = new ArrayList<>();
        String s = Prefs.sp().getString(KEY, "");
        for (String p : s.split(",")) {
            try {
                if (!p.isEmpty()) out.add(Long.parseLong(p));
            } catch (NumberFormatException ignored) {
                // мусор в настройках — пропускаем
            }
        }
        return out;
    }

    private static void save(List<Long> ids) {
        StringBuilder b = new StringBuilder();
        for (Long id : ids) {
            if (b.length() > 0) b.append(',');
            b.append(id);
        }
        Prefs.put(KEY, b.toString());
    }

    /** Загрузки браузера, новые сверху. Удалённые из системы — забываем. */
    static List<Item> list(Context c) {
        List<Long> ids = ids();
        List<Item> out = new ArrayList<>();
        if (ids.isEmpty()) return out;
        DownloadManager dm = c.getSystemService(DownloadManager.class);
        long[] arr = new long[ids.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = ids.get(i);
        List<Item> found = new ArrayList<>();
        try (Cursor cur = dm.query(new DownloadManager.Query().setFilterById(arr))) {
            if (cur != null) {
                int cId = cur.getColumnIndex(DownloadManager.COLUMN_ID);
                int cTitle = cur.getColumnIndex(DownloadManager.COLUMN_TITLE);
                int cStatus = cur.getColumnIndex(DownloadManager.COLUMN_STATUS);
                int cGot = cur.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR);
                int cTotal = cur.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES);
                int cReason = cur.getColumnIndex(DownloadManager.COLUMN_REASON);
                while (cur.moveToNext()) {
                    Item it = new Item();
                    it.id = cur.getLong(cId);
                    it.name = cTitle >= 0 ? cur.getString(cTitle) : null;
                    if (it.name == null || it.name.isEmpty()) it.name = "Файл";
                    it.status = cur.getInt(cStatus);
                    it.got = cGot >= 0 ? cur.getLong(cGot) : 0;
                    it.total = cTotal >= 0 ? cur.getLong(cTotal) : -1;
                    it.reason = cReason >= 0 ? cur.getInt(cReason) : 0;
                    found.add(it);
                }
            }
        } catch (Exception e) {
            return out;
        }
        for (Long id : ids) {
            for (Item it : found) {
                if (it.id == id) {
                    out.add(it);
                    break;
                }
            }
        }
        if (out.size() != ids.size()) {
            List<Long> keep = new ArrayList<>();
            for (Item it : out) keep.add(it.id);
            save(keep);
        }
        return out;
    }

    /** Сколько файлов скачивается прямо сейчас. */
    static int active(Context c) {
        int n = 0;
        for (Item it : list(c)) if (it.active()) n++;
        return n;
    }

    /** Отменить загрузку (недокачанный файл удаляется). */
    static void cancel(Context c, long id) {
        try {
            c.getSystemService(DownloadManager.class).remove(id);
        } catch (Exception ignored) {
            // уже нет
        }
        List<Long> ids = ids();
        ids.remove(id);
        save(ids);
    }

    /** Убрать из списка, файл остаётся в «Загрузках». */
    static void forget(long id) {
        List<Long> ids = ids();
        ids.remove(id);
        save(ids);
    }

    static void open(Activity a, Item it) {
        try {
            DownloadManager dm = a.getSystemService(DownloadManager.class);
            Uri uri = dm.getUriForDownloadedFile(it.id);
            if (uri == null) {
                Toast.makeText(a, "Файл не найден — возможно, его удалили", Toast.LENGTH_SHORT).show();
                return;
            }
            String mime = dm.getMimeTypeForDownloadedFile(it.id);
            Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime != null ? mime : "*/*")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            a.startActivity(Intent.createChooser(i, it.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            Toast.makeText(a, "Нет приложения, чтобы открыть этот файл", Toast.LENGTH_SHORT).show();
        }
    }

    /** Системный список всех загрузок телефона. */
    static void openSystem(Activity a) {
        try {
            a.startActivity(new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            Toast.makeText(a, "Откройте приложение «Файлы» → Загрузки", Toast.LENGTH_SHORT).show();
        }
    }
}
