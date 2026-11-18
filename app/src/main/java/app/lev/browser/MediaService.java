package app.lev.browser;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Плеер YouTube в шторке: назад / пауза / вперёд. Держит браузер живым,
 * пока играет видео, чтобы музыка не останавливалась при выключенном экране.
 */
public class MediaService extends Service {

    static final String ACTION_UPDATE = "app.lev.browser.MEDIA_UPDATE";
    static final String ACTION_CMD = "app.lev.browser.MEDIA_CMD";
    private static final String CHANNEL = "player";
    private static final int ID = 7;
    private static final long IDLE_STOP_MS = 15 * 60 * 1000L;

    private MediaSession session;
    private PowerManager.WakeLock wakeLock;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable idleStop = this::stopSelf;
    private boolean playing;
    private String title = "";
    private String artist = "";
    private String videoId;
    private Bitmap art;

    static void update(Context c, boolean playing, String title, String artist, String videoId) {
        Intent i = new Intent(c, MediaService.class).setAction(ACTION_UPDATE)
                .putExtra("playing", playing).putExtra("title", title).putExtra("artist", artist)
                .putExtra("video", videoId);
        try {
            if (playing) c.startForegroundService(i); else c.startService(i);
        } catch (Exception ignored) {
            // система не дала запустить — просто без плеера в шторке
        }
    }

    static void stop(Context c) {
        c.stopService(new Intent(c, MediaService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Плеер YouTube", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);

        session = new MediaSession(this, "Lev");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { BrowserActivity.mediaCommand("play"); }
            @Override public void onPause() { BrowserActivity.mediaCommand("pause"); }
            @Override public void onSkipToNext() { BrowserActivity.mediaCommand("next"); }
            @Override public void onSkipToPrevious() { BrowserActivity.mediaCommand("prev"); }
            @Override public void onStop() {
                BrowserActivity.mediaCommand("pause");
                stopSelf();
            }
        });
        session.setActive(true);

        PowerManager pm = getSystemService(PowerManager.class);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Lev:player");
        wakeLock.setReferenceCounted(false);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_UPDATE.equals(intent.getAction())) {
            playing = intent.getBooleanExtra("playing", false);
            String t = intent.getStringExtra("title");
            if (t != null && !t.isEmpty()) title = t;
            String a = intent.getStringExtra("artist");
            artist = a == null ? "" : a;
            String v = intent.getStringExtra("video");
            if (v != null && !v.equals(videoId)) {
                videoId = v;
                art = null;
                loadArt(v);
            }
        } else if (intent != null && ACTION_CMD.equals(intent.getAction())) {
            BrowserActivity.mediaCommand(intent.getStringExtra("cmd"));
        }
        publish();
        return START_NOT_STICKY;
    }

    private void publish() {
        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_STOP;
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(actions)
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        PlaybackState.PLAYBACK_POSITION_UNKNOWN, playing ? 1f : 0f)
                .build());
        MediaMetadata.Builder md = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, artist.isEmpty() ? "YouTube" : artist);
        if (art != null) md.putBitmap(MediaMetadata.METADATA_KEY_ART, art);
        session.setMetadata(md.build());

        Intent open = new Intent(this, BrowserActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_n_play)
                .setContentTitle(title)
                .setContentText(artist.isEmpty() ? "YouTube" : artist)
                .setContentIntent(content)
                .setOngoing(playing)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(action(R.drawable.ic_n_prev, "Назад", "prev", 1))
                .addAction(playing ? action(R.drawable.ic_n_pause, "Пауза", "pause", 2)
                        : action(R.drawable.ic_n_play, "Играть", "play", 2))
                .addAction(action(R.drawable.ic_n_next, "Дальше", "next", 3))
                .setStyle(new Notification.MediaStyle()
                        .setMediaSession(session.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2));
        if (art != null) b.setLargeIcon(art);
        Notification n = b.build();
        startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);

        handler.removeCallbacks(idleStop);
        if (playing) {
            wakeLock.acquire(4 * 60 * 60 * 1000L);
        } else {
            if (wakeLock.isHeld()) wakeLock.release();
            handler.postDelayed(idleStop, IDLE_STOP_MS);
        }
    }

    private Notification.Action action(int icon, String label, String cmd, int code) {
        Intent i = new Intent(this, MediaService.class).setAction(ACTION_CMD).putExtra("cmd", cmd);
        PendingIntent pi = PendingIntent.getService(this, code, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, icon), label, pi)
                .build();
    }

    private void loadArt(String id) {
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL("https://i.ytimg.com/vi/" + id + "/hqdefault.jpg")
                        .openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                try (InputStream in = c.getInputStream()) {
                    Bitmap b = BitmapFactory.decodeStream(in);
                    if (b != null) {
                        handler.post(() -> {
                            if (id.equals(videoId)) {
                                art = b;
                                publish();
                            }
                        });
                    }
                }
            } catch (Exception ignored) {
                // без обложки
            }
        }).start();
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(idleStop);
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (session != null) session.release();
        stopForeground(STOP_FOREGROUND_REMOVE);
        BrowserActivity.onMediaServiceStopped();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
