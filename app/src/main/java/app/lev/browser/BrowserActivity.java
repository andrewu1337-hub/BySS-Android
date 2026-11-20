package app.lev.browser;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebViewDatabase;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class BrowserActivity extends Activity {

    private static WeakReference<BrowserActivity> instance = new WeakReference<>(null);
    private static final int REQ_FILE = 71;
    private static final int REQ_NOTIFY = 72;
    static final int REQ_ICON = 73;
    private static final Set<String> YT_ORIGINS = new HashSet<>(Arrays.asList(
            "https://m.youtube.com", "https://www.youtube.com", "https://youtube.com", "https://music.youtube.com"));

    private final List<Tab> tabs = new ArrayList<>();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Tab cur;
    private Tab mediaTab;

    private FrameLayout root;
    private PullRefresh swipe;
    private FrameLayout holder;
    private FrameLayout overlay;
    private FrameLayout fsLayer;
    private HomeView home;
    private TabStackView stack;
    private LinearLayout bottom;
    private LinearLayout prompt;
    private View progress;
    private EditText address;
    private TextView engineGlyph;
    private ImageView shield;
    private ImageButton btnTranslate, btnReload, btnBack, btnFwd;
    private TextView tabsBtn;

    private PageTranslator translator;
    private String pageJs, ytJs, asJs;
    /** Автоскролл: на какой вкладке идёт, на паузе ли, плашка внизу с кнопками. */
    private Tab asTab;
    private boolean asPaused;
    private LinearLayout asPill;
    private ImageButton asPlayBtn;
    private View fsView;
    private WebChromeClient.CustomViewCallback fsCallback;
    private ValueCallback<Uri[]> fileCallback;
    private String promptBase;
    private String promptDismissed;
    private boolean menuOpen;
    private boolean skipHomeOnce;
    private BroadcastReceiver downloadsDone;
    /** Кнопка «⋮»: пока что-то скачивается, она мигает зелёной стрелкой. */
    private ImageButton menuBtn;
    private boolean dlArrow;
    private boolean dlOpen;
    private final Runnable dlTick = this::downloadTick;
    private final java.util.Map<Long, View[]> dlRows = new java.util.HashMap<>();
    private LinearLayout dlList;

    private final Runnable refreshShield = this::updateShield;

    private boolean builtDark;
    /** Текущий режим BySS (null — обычный). */
    private String space;
    /** Картинки вкладок — в памяти только последние (≈24 МБ), остальные на диске. */
    private final android.util.LruCache<Long, Bitmap> thumbs = new android.util.LruCache<Long, Bitmap>(24 * 1024 * 1024) {
        @Override
        protected int sizeOf(Long key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private final java.util.concurrent.ExecutorService thumbExec = java.util.concurrent.Executors.newSingleThreadExecutor();

    // =================================================================== жизнь

    @Override
    protected void onCreate(Bundle state) {
        Prefs.init(this);
        SitePrefs.init(this);
        CrashLog.install(this);
        boolean dark = Prefs.dark();
        builtDark = dark;
        setTheme(dark ? R.style.Theme_BySS_Dark : R.style.Theme_BySS_Light);
        Ui.apply(dark);
        super.onCreate(state);
        instance = new WeakReference<>(this);

        AdBlocker.start(this);
        pageJs = asset("js/page.js");
        ytJs = asset("js/youtube.js");
        asJs = asset("js/autoscroll.js");
        translator = new PageTranslator(this, asset("js/translate.js"));

        buildUi();
        shots = new Screenshots(this);
        setupEdgeToEdge();
        applyStatusBar();
        root.post(() -> CrashLog.offer(this));
        restoreTabs();
        boolean opened = handleIntent(getIntent());
        if (!opened) {
            if (cur == null) newHomeTab(); else showTab(cur);
        }

        downloadsDone = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                Toast.makeText(c, "Файл скачан — Меню → Скачанные файлы", Toast.LENGTH_SHORT).show();
                watchDownloads();
            }
        };
        IntentFilter f = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(downloadsDone, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(downloadsDone, f);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Prefs.dark() != builtDark) { // тему поменяли в настройках — перестроить окно
            saveTabs();
            recreate();
            return;
        }
        Ui.apply(Prefs.dark());
        removeExpiredTabs();
        long lb = Prefs.lastBackground();
        int min = Prefs.homeAfterMin();
        boolean playing = mediaTab != null && mediaTab.mediaPlaying;
        if (!skipHomeOnce && lb > 0 && min > 0 && System.currentTimeMillis() - lb > min * 60_000L && !playing) {
            openHome();
        }
        skipHomeOnce = false;
        Prefs.put("last_background", 0L);
        if (cur != null && cur.web != null) cur.web.onResume();
        resumeWebTimers();
        for (Tab t : tabs) {
            if (t.web != null) Profiles.cookies(t.profile).setAcceptThirdPartyCookies(t.web, Prefs.thirdPartyCookies());
        }
        home.refresh();
        applyStatusBar();
        updateBars();
        Icons.applyTask(this);
        Updater.onResume(this);
        Updater.autoCheck(this);
        watchDownloads();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (shots != null) shots.onPause();
        saveTabs();
        CookieManager.getInstance().flush();
        if (Profiles.supported()) {
            Profiles.cookies(true).flush();
            for (Spaces.Space s : Spaces.all()) Profiles.cookies(Spaces.profile(s.id)).flush();
        }
        boolean keepMedia = mediaTab != null && mediaTab.mediaPlaying && Prefs.bgPlay();
        for (Tab t : tabs) {
            if (t.web == null) continue;
            if (keepMedia && t == mediaTab) continue;
            t.web.onPause();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        Prefs.put("last_background", System.currentTimeMillis());
        boolean keepMedia = mediaTab != null && mediaTab.mediaPlaying && Prefs.bgPlay();
        if (!keepMedia) {
            WebView w = anyWeb();
            if (w != null) {
                w.pauseTimers();
                timersPaused = true;
            }
        }
    }

    /*
     * pauseTimers/resumeTimers действуют сразу на все страницы приложения.
     * Раньше таймеры возобновлялись только через текущую вкладку: если после возврата
     * открывалась главная (у неё нет WebView), они так и оставались на паузе, и любой сайт
     * «грузился» бесконечно, пока браузер не закроешь. Теперь возобновляем через любую
     * страницу, а если страниц ещё нет — как только первая появится.
     */
    private boolean timersPaused;

    private WebView anyWeb() {
        if (cur != null && cur.web != null) return cur.web;
        for (Tab t : tabs) {
            if (t.web != null) return t.web;
        }
        return null;
    }

    private void resumeWebTimers() {
        if (!timersPaused) return;
        WebView w = anyWeb();
        if (w == null) return;
        try {
            w.resumeTimers();
            timersPaused = false;
        } catch (Exception ignored) {
            // нет WebView
        }
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(dlTick);
        if (downloadsDone != null) {
            try {
                unregisterReceiver(downloadsDone);
            } catch (Exception ignored) {
                // уже снят
            }
        }
        MediaService.stop(this);
        for (Tab t : tabs) destroyWeb(t);
        translator.close();
        super.onDestroy();
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        // Сворачивание браузера (UI_HIDDEN/BACKGROUND) вкладки не трогает — только настоящая нехватка памяти.
        if (level == TRIM_MEMORY_RUNNING_CRITICAL || level >= TRIM_MEMORY_COMPLETE) {
            for (Tab t : tabs) if (!keepsAwake(t)) sleepTab(t);
        }
    }

    private String asset(String name) {
        try (InputStream in = getAssets().open(name)) {
            byte[] b = new byte[in.available()];
            int off = 0;
            while (off < b.length) {
                int n = in.read(b, off, b.length - off);
                if (n < 0) break;
                off += n;
            }
            return new String(b, 0, off, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    // ================================================================ интерфейс

    private void buildUi() {
        Context c = this;
        root = new FrameLayout(c);
        root.setBackgroundColor(Ui.bg);
        root.setFocusable(true);
        root.setFocusableInTouchMode(true);

        column = new LinearLayout(c);
        column.setOrientation(LinearLayout.VERTICAL);
        root.addView(column, new FrameLayout.LayoutParams(-1, -1));
        // своя полоска под шторкой: её цвет плавно меняем сами (обычная / цвет сайта / прозрачная)
        sbar = new View(c);
        root.addView(sbar, new FrameLayout.LayoutParams(-1, 0, Gravity.TOP));

        swipe = new PullRefresh(c, () -> cur == null || cur.isHome() ? null : cur.web);
        swipe.setColorSchemeColors(Ui.text);
        swipe.setProgressBackgroundColorSchemeColor(Ui.pill);
        holder = new FrameLayout(c);
        holder.setBackgroundColor(Ui.bg);
        swipe.addView(holder, new ViewGroup.LayoutParams(-1, -1));
        swipe.setOnChildScrollUpCallback((parent, child) ->
                cur == null || cur.isHome() || cur.web == null || cur.web.canScrollVertically(-1)
                        || cur.web.getScrollY() > 0 || swipe.holdGesture());
        swipe.setOnRefreshListener(() -> {
            Haptics.click(swipe);
            if (cur != null && cur.web != null) cur.web.reload();
            ui.postDelayed(() -> swipe.setRefreshing(false), 900);
        });
        column.addView(swipe, new LinearLayout.LayoutParams(-1, 0, 1f));

        home = new HomeView(c, new HomeView.Listener() {
            @Override public void onOpen(String url) { go(url); }
            @Override public void onSearch(String engineId, String q) {
                go(SearchEngines.searchUrl(SearchEngines.get(engineId), q));
            }
            @Override public void onPickEngine(int slot) { pickEngine("home" + slot); }
        });

        bottom = new LinearLayout(c);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setBackgroundColor(Ui.bar);
        column.addView(bottom, new LinearLayout.LayoutParams(-1, -2));

        prompt = new LinearLayout(c);
        prompt.setOrientation(LinearLayout.VERTICAL);
        prompt.setVisibility(View.GONE);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, -2);
        plp.setMargins(Ui.dp(c, 10), Ui.dp(c, 8), Ui.dp(c, 10), Ui.dp(c, 4));
        bottom.addView(prompt, plp);

        progress = new View(c);
        progress.setBackgroundColor(Ui.text);
        progress.setPivotX(0);
        progress.setScaleX(0);
        progress.setAlpha(0);
        bottom.addView(progress, new LinearLayout.LayoutParams(-1, Ui.dp(c, 2)));

        // Ряд 1: [поисковик/щит] [адрес] [перевод] [обновить]
        LinearLayout row1 = new LinearLayout(c);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER_VERTICAL);
        row1.setPadding(Ui.dp(c, 8), Ui.dp(c, 6), Ui.dp(c, 4), 0);
        bottom.addView(row1, new LinearLayout.LayoutParams(-1, Ui.dp(c, 52)));

        LinearLayout pill = new LinearLayout(c);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setBackground(Ui.round(Ui.pill, Ui.dp(c, 22)));
        row1.addView(pill, new LinearLayout.LayoutParams(0, Ui.dp(c, 44), 1f));

        FrameLayout site = new FrameLayout(c);
        site.setBackground(Ui.borderlessRipple());
        engineGlyph = new TextView(c);
        engineGlyph.setGravity(Gravity.CENTER);
        engineGlyph.setTextColor(Ui.tileGlyph);
        engineGlyph.setTextSize(13);
        engineGlyph.setIncludeFontPadding(false);
        engineGlyph.setBackground(Ui.round(Ui.dark ? 0xFF2C2C2E : 0xFFDCDCE1, Ui.dp(c, 14)));
        FrameLayout.LayoutParams gp = new FrameLayout.LayoutParams(Ui.dp(c, 28), Ui.dp(c, 28), Gravity.CENTER);
        site.addView(engineGlyph, gp);
        shield = new ImageView(c);
        shield.setImageResource(R.drawable.ic_shield);
        shield.setImageTintList(ColorStateList.valueOf(Ui.text));
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(Ui.dp(c, 22), Ui.dp(c, 22), Gravity.CENTER);
        site.addView(shield, sp);
        site.setOnClickListener(v -> {
            Haptics.click(v);
            onSiteButton();
        });
        pill.addView(site, new LinearLayout.LayoutParams(Ui.dp(c, 44), Ui.dp(c, 44)));

        address = new EditText(c);
        address.setSingleLine(true);
        address.setBackground(null);
        address.setTextSize(15);
        address.setTextColor(Ui.text);
        address.setHintTextColor(Ui.text2);
        address.setHint("Поиск или адрес");
        address.setPadding(Ui.dp(c, 4), 0, Ui.dp(c, 12), 0);
        address.setSelectAllOnFocus(true);
        address.setImeOptions(EditorInfo.IME_ACTION_GO | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        address.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        address.setOnFocusChangeListener((v, focus) -> {
            if (focus) {
                address.setText(cur == null || cur.isHome() ? "" : cur.url);
                address.selectAll();
            } else {
                address.setText(displayAddress(cur));
            }
            btnTranslate.setVisibility(focus ? View.GONE : View.VISIBLE);
            btnReload.setVisibility(focus ? View.GONE : View.VISIBLE);
            updateShield();
        });
        address.setOnEditorActionListener((v, id, ev) -> {
            boolean go = id == EditorInfo.IME_ACTION_GO
                    || (ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER && ev.getAction() == KeyEvent.ACTION_DOWN);
            if (!go) return false;
            String url = SearchEngines.toUrl(address.getText().toString(), SearchEngines.get(Prefs.engine()));
            hideKeyboard();
            if (url != null) go(url);
            return true;
        });
        address.setOnLongClickListener(v -> {
            if (address.hasFocus() || cur == null || cur.isHome() || cur.url == null) return false;
            Haptics.thud(v);
            String url = cur.url;
            new AlertDialog.Builder(this)
                    .setTitle(url)
                    .setItems(new String[]{"Скопировать ссылку", "Поделиться ссылкой", "Изменить адрес"}, (d, w) -> {
                        if (w == 0) copy(url);
                        else if (w == 1) share(url);
                        else {
                            address.requestFocus();
                            InputMethodManager imm = getSystemService(InputMethodManager.class);
                            if (imm != null) imm.showSoftInput(address, 0);
                        }
                    })
                    .show();
            return true;
        });
        pill.addView(address, new LinearLayout.LayoutParams(0, -1, 1f));

        btnTranslate = Ui.iconButton(c, R.drawable.ic_translate);
        btnTranslate.setOnClickListener(v -> {
            Haptics.click(v);
            toggleTranslate(Prefs.translateMode());
        });
        btnTranslate.setOnLongClickListener(v -> {
            Haptics.thud(v);
            chooseTranslateOnce();
            return true;
        });
        row1.addView(btnTranslate, new LinearLayout.LayoutParams(Ui.dp(c, 44), Ui.dp(c, 44)));
        btnReload = Ui.iconButton(c, R.drawable.ic_reload);
        btnReload.setOnClickListener(v -> {
            Haptics.click(v);
            if (cur != null && cur.web != null && !cur.isHome()) cur.web.reload();
        });
        row1.addView(btnReload, new LinearLayout.LayoutParams(Ui.dp(c, 44), Ui.dp(c, 44)));

        // Ряд 2: назад, вперёд, +, вкладки, меню
        LinearLayout row2 = new LinearLayout(c);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setGravity(Gravity.CENTER_VERTICAL);
        bottom.addView(row2, new LinearLayout.LayoutParams(-1, Ui.dp(c, 50)));
        btnBack = Ui.iconButton(c, R.drawable.ic_back);
        btnBack.setOnClickListener(v -> {
            Haptics.click(v);
            goBack();
        });
        btnFwd = Ui.iconButton(c, R.drawable.ic_forward);
        btnFwd.setOnClickListener(v -> {
            Haptics.click(v);
            if (cur != null && cur.web != null && cur.web.canGoForward()) cur.web.goForward();
        });
        ImageButton plus = Ui.iconButton(c, R.drawable.ic_plus);
        Ui.heartButton(plus);
        plus.setOnClickListener(v -> {
            Haptics.click(v);
            Ui.heart(v);
            plusTab();
        });
        tabsBtn = new TextView(c);
        tabsBtn.setGravity(Gravity.CENTER);
        tabsBtn.setTextSize(12);
        tabsBtn.setTextColor(Ui.text);
        tabsBtn.setIncludeFontPadding(false);
        tabsBtn.setOnClickListener(v -> {
            Haptics.click(v);
            openStack();
        });
        FrameLayout tabsWrap = new FrameLayout(c);
        tabsWrap.setBackground(Ui.borderlessRipple());
        tabsWrap.setOnClickListener(v -> {
            Haptics.click(v);
            openStack();
        });
        tabsBtn.setBackground(Ui.outline(Ui.text, Ui.dp(c, 1.6f), Ui.dp(c, 6)));
        tabsWrap.addView(tabsBtn, new FrameLayout.LayoutParams(Ui.dp(c, 22), Ui.dp(c, 22), Gravity.CENTER));
        ImageButton menu = Ui.iconButton(c, R.drawable.ic_menu);
        menuBtn = menu;
        menu.setOnClickListener(v -> {
            Haptics.click(v);
            if (menuOpen) closeMenu(); else openMenu();
        });
        View[] tools = {btnBack, btnFwd, plus, tabsWrap, menu};
        for (View t : tools) row2.addView(t, new LinearLayout.LayoutParams(0, -1, 1f));

        overlay = new FrameLayout(c);
        overlay.setVisibility(View.GONE);
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));

        stack = new TabStackView(c, new TabStackView.Listener() {
            @Override public void onSelect(Tab t) {
                showTab(t);
                stack.hide(null);
            }
            @Override public void onClose(Tab t) { closeTab(t, false); }
            @Override public void onNewTab() {
                stack.hide(null);
                plusTab();
            }
            @Override public void onCloseAll() {
                new AlertDialog.Builder(BrowserActivity.this)
                        .setTitle("Закрыть все вкладки?")
                        .setMessage(space == null ? null : "Только в режиме «" + Spaces.name(space) + "». Сам режим останется.")
                        .setPositiveButton("Закрыть", (d, w) -> {
                            for (Tab t : spaceTabs()) closeTab(t, false);
                            stack.hide(null);
                            if (spaceTabs().isEmpty()) newHomeTab();
                        })
                        .setNegativeButton("Отмена", null).show();
            }
            @Override public void loadThumb(Tab t, java.util.function.Consumer<Bitmap> done) {
                BrowserActivity.this.loadThumb(t, done);
            }
        });
        stack.setVisibility(View.GONE);
        root.addView(stack, new FrameLayout.LayoutParams(-1, -1));

        fsLayer = new FrameLayout(c);
        fsLayer.setBackgroundColor(0xFF000000);
        fsLayer.setVisibility(View.GONE);
        root.addView(fsLayer, new FrameLayout.LayoutParams(-1, -1));

        setContentView(root);
    }

    private void hideKeyboard() {
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        View f = getCurrentFocus();
        if (imm != null) imm.hideSoftInputFromWindow((f != null ? f : address).getWindowToken(), 0);
        root.requestFocus();
        if (address.hasFocus()) address.clearFocus();
        if (f != null && f != address && f.hasFocus()) f.clearFocus();
    }

    private static String host(String url) {
        if (url == null) return null;
        try {
            return Uri.parse(url).getHost();
        } catch (Exception e) {
            return null;
        }
    }

    private String displayAddress(Tab t) {
        if (t == null || t.isHome()) return "";
        String q = SearchEngines.queryOf(t.url);
        if (q != null) return q;
        String h = host(t.url);
        if (h == null) return t.url;
        return h.startsWith("www.") ? h.substring(4) : h;
    }

    void updateBars() {
        if (cur == null || address == null) return;
        if (!address.hasFocus()) address.setText(displayAddress(cur));
        boolean web = !cur.isHome() && cur.web != null;
        Ui.setEnabledLook(btnBack, (web && cur.web.canGoBack()) || (cur.parent != null && tabs.contains(cur.parent)));
        Ui.setEnabledLook(btnFwd, web && cur.web.canGoForward());
        Ui.setEnabledLook(btnReload, web);
        Ui.setEnabledLook(btnTranslate, web);
        btnTranslate.setImageTintList(ColorStateList.valueOf(cur.translated ? Ui.text : Ui.text));
        btnTranslate.setBackground(cur.translated ? Ui.round(Ui.pill, Ui.dp(this, 22)) : Ui.borderlessRipple());
        int n = spaceTabs().size();
        tabsBtn.setText(n > 99 ? ":)" : String.valueOf(n));
        boolean inSpace = space != null;
        ((View) address.getParent()).setBackground(Ui.round(inSpace ? (Ui.dark ? 0xFF2A2236 : 0xFFEDE7F6) : Ui.pill,
                Ui.dp(this, 22)));
        address.setHint(inSpace ? Spaces.name(space) + " · поиск или адрес" : "Поиск или адрес");
        swipe.setEnabled(web);
        updateShield();
    }

    private void updateShield() {
        if (cur == null) return;
        boolean showEngine = cur.isHome() || address.hasFocus();
        engineGlyph.setVisibility(showEngine ? View.VISIBLE : View.GONE);
        shield.setVisibility(showEngine ? View.GONE : View.VISIBLE);
        if (showEngine) {
            SearchEngines.Engine e = SearchEngines.get(Prefs.engine());
            engineGlyph.setText(e.glyph);
            engineGlyph.setTextSize(e.glyph.length() > 1 ? 10 : 13);
        } else {
            boolean allowed = !Prefs.adblock() || SitePrefs.isAllowed(cur.space, cur.pageHost);
            shield.setImageResource(allowed ? R.drawable.ic_shield_off : R.drawable.ic_shield);
            shield.setAlpha(allowed ? 0.55f : 1f);
        }
        if (prompt.getVisibility() == View.VISIBLE && promptBase != null) updatePromptCount();
    }

    private void setPageProgress(int p) {
        if (p >= 100) {
            progress.animate().scaleX(1f).setDuration(Ui.anim(120)).withEndAction(() ->
                    progress.animate().alpha(0f).setDuration(Ui.anim(220)).start()).start();
        } else {
            progress.setAlpha(1f);
            progress.animate().scaleX(Math.max(0.06f, p / 100f)).setDuration(Ui.anim(160)).start();
        }
    }

    // ================================================================= вкладки

    private void restoreTabs() {
        long[] curId = {-1};
        List<Tab> saved = Tab.fromJson(Prefs.sp().getString("tabs", null), curId);
        tabs.clear();
        tabs.addAll(saved);
        removeExpiredTabs();
        cur = null;
        // вкладки удалённых режимов — выбросить
        java.util.Set<String> alive = new java.util.HashSet<>();
        for (Spaces.Space s : Spaces.all()) alive.add(s.id);
        for (Tab t : new ArrayList<>(tabs)) if (t.space != null && !alive.contains(t.space)) tabs.remove(t);
        for (Tab t : tabs) if (t.id == curId[0]) cur = t;
        if (cur == null && !tabs.isEmpty()) cur = tabs.get(tabs.size() - 1);
        space = cur == null ? null : cur.space;
    }

    /** Вкладки текущего режима (обычного или BySS). */
    private List<Tab> spaceTabs() {
        List<Tab> out = new ArrayList<>();
        for (Tab t : tabs) if (java.util.Objects.equals(t.space, space)) out.add(t);
        return out;
    }

    /** Можно ли открыть ещё вкладку; если нет — предложить закрыть самую старую. */
    private boolean roomForTab(Runnable thenOpen) {
        List<Tab> mine = spaceTabs();
        if (mine.size() < Prefs.MAX_TABS) return true;
        Tab oldest = null;
        for (Tab t : mine) {
            if (t == cur || t == mediaTab) continue;
            if (oldest == null || t.lastUsed < oldest.lastUsed) oldest = t;
        }
        Tab victim = oldest;
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("Открыто " + Prefs.MAX_TABS + " вкладок")
                .setMessage("Это максимум, чтобы телефон не тормозил. Закрыть самую старую вкладку и открыть новую?")
                .setNegativeButton("Отмена", null);
        if (victim != null) {
            b.setPositiveButton("Закрыть старую", (d, w) -> {
                closeTab(victim, false);
                if (thenOpen != null) thenOpen.run();
            });
        }
        b.show();
        return false;
    }

    void saveTabs() {
        Prefs.put("tabs", Tab.toJson(tabs, cur));
    }

    private void removeExpiredTabs() {
        int days = Prefs.tabExpireDays();
        if (days <= 0) return;
        long cutoff = System.currentTimeMillis() - days * 24L * 3600 * 1000;
        boolean removedCurrent = false;
        for (Tab t : new ArrayList<>(tabs)) {
            if (t.lastUsed < cutoff && t != mediaTab) {
                if (t == cur) removedCurrent = true;
                destroyWeb(t);
                deleteThumb(t);
                tabs.remove(t);
            }
        }
        if (removedCurrent) {
            cur = null;
            if (holder != null) newHomeTab();
        }
    }

    private void newHomeTab() {
        Tab t = new Tab();
        t.space = space;
        tabs.add(t);
        showTab(t);
    }

    /** Новая вкладка с кнопки «+» — с проверкой предела. */
    private void plusTab() {
        if (!roomForTab(this::newHomeTab)) return;
        newHomeTab();
    }

    /** Через N минут без браузера — сразу начальная страница. */
    private void openHome() {
        if (cur != null && cur.isHome()) return;
        for (Tab t : spaceTabs()) {
            if (t.isHome()) {
                tabs.remove(t);
                tabs.add(t);
                showTab(t);
                return;
            }
        }
        newHomeTab();
    }

    void showTab(Tab t) {
        if (cur != null && cur != t) {
            if (asTab != null && asTab != t) stopAutoscroll();
            captureThumb(cur);
            if (cur.web != null && cur != mediaTab) cur.web.onPause();
            if (address.hasFocus()) hideKeyboard();
        }
        cur = t;
        space = t.space;
        t.lastUsed = System.currentTimeMillis();
        holder.removeAllViews();
        if (t.isHome()) {
            detach(home);
            home.setSpace(space == null ? null : Spaces.name(space));
            home.refresh();
            holder.addView(home, new FrameLayout.LayoutParams(-1, -1));
            home.setAlpha(0f);
            home.animate().alpha(1f).setDuration(Ui.anim(180)).start();
        } else {
            if (t.web == null) {
                createWeb(t, profileFor(t, t.url));
                if (t.state == null || t.web.restoreState(t.state) == null) t.web.loadUrl(t.url);
                t.state = null;
            }
            detach(t.web);
            holder.addView(t.web, new FrameLayout.LayoutParams(-1, -1));
            t.web.onResume();
            resumeWebTimers();
        }
        hidePrompt();
        Integer known = tabColor.get(t);
        pageColor = known != null ? known : 0;
        applyStatusBar();
        if (!t.isHome() && Prefs.statusBar() != 0) {
            scheduleSample(60);
            barHandler.postDelayed(() -> scheduleSample(0), 450);
        }
        updateBars();
        saveTabs();
    }

    private static void detach(View v) {
        if (v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
    }

    private boolean wantsYt(String url) {
        return Profiles.supported() && Profiles.isYouTube(host(url));
    }

    /** Профиль для вкладки: режим BySS — свой; YouTube — свой; остальное — обычный. */
    private String profileFor(Tab t, String url) {
        if (t.space != null) return Profiles.supported() ? Spaces.profile(t.space) : null;
        return wantsYt(url) ? Profiles.YT : null;
    }

    /** Открыть адрес в текущей вкладке (с начальной страницы — в ней же). */
    void go(String url) {
        View f = getCurrentFocus();
        if (f instanceof EditText) hideKeyboard();
        if (cur == null) newHomeTab();
        loadUrl(cur, url);
    }

    void loadUrl(Tab t, String url) {
        if (url == null) return;
        String h = host(url);
        String profile = profileFor(t, url);
        if (t.space == null && t.ytProfile && Profiles.belongsToYouTube(h)) profile = Profiles.YT;
        if (t.web != null && !java.util.Objects.equals(t.profile, profile)) destroyWeb(t);
        t.url = url;
        t.translated = false;
        t.state = null;
        if (t.web == null) {
            createWeb(t, profile);
            if (t == cur) {
                holder.removeAllViews();
                holder.addView(t.web, new FrameLayout.LayoutParams(-1, -1));
            }
        }
        t.web.loadUrl(url);
        if (t == cur) updateBars();
        saveTabs();
    }

    private void closeTab(Tab t, boolean showNext) {
        if (!tabs.contains(t)) return;
        List<Tab> same = new ArrayList<>();
        for (Tab o : tabs) if (java.util.Objects.equals(o.space, t.space)) same.add(o);
        int idx = same.indexOf(t);
        if (t == mediaTab) stopMedia();
        if (t == asTab) stopAutoscroll();
        translator.forget(t);
        destroyWeb(t);
        deleteThumb(t);
        tabs.remove(t);
        same.remove(t);
        thumbs.remove(t.id);
        for (Tab o : tabs) if (o.parent == t) o.parent = null;
        if (t == cur) {
            cur = null;
            if (same.isEmpty()) {
                newHomeTab();
            } else {
                showTab(same.get(Math.max(0, Math.min(idx, same.size()) - 1)));
            }
        } else {
            updateBars();
        }
        saveTabs();
    }

    private void openStack() {
        if (address.hasFocus()) hideKeyboard();
        closeMenu();
        if (cur != null) captureThumb(cur);
        stack.show(spaceTabs(), cur);
        lightIcons(!Ui.dark); // над вкладками — значки шторки под цвет темы
    }

    private File thumbFile(Tab t) {
        File dir = new File(getCacheDir(), "thumbs");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return new File(dir, t.id + ".jpg");
    }

    private void deleteThumb(Tab t) {
        //noinspection ResultOfMethodCallIgnored
        thumbFile(t).delete();
        thumbs.remove(t.id);
    }

    private void captureThumb(Tab t) {
        if (t == null || t.isHome() || t.web == null || t.web.getWidth() == 0 || t.web.getHeight() == 0) return;
        try {
            float s = 0.3f;
            Bitmap b = Bitmap.createBitmap(Math.round(t.web.getWidth() * s), Math.round(t.web.getHeight() * s),
                    Bitmap.Config.RGB_565);
            Canvas c = new Canvas(b);
            c.scale(s, s);
            c.translate(-t.web.getScrollX(), -t.web.getScrollY());
            t.web.draw(c);
            thumbs.put(t.id, b);
            File f = thumbFile(t);
            new Thread(() -> {
                try (FileOutputStream out = new FileOutputStream(f)) {
                    b.compress(Bitmap.CompressFormat.JPEG, 70, out);
                } catch (Exception ignored) {
                    // без картинки
                }
            }).start();
        } catch (Throwable ignored) {
            // нехватка памяти — без картинки
        }
    }

    /** Картинка вкладки для списка вкладок: из памяти, иначе с диска в фоне. */
    private void loadThumb(Tab t, java.util.function.Consumer<Bitmap> done) {
        Bitmap b = thumbs.get(t.id);
        if (b != null) {
            done.accept(b);
            return;
        }
        File f = thumbFile(t);
        if (!f.exists()) return;
        thumbExec.execute(() -> {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap d = BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            if (d == null) return;
            ui.post(() -> {
                thumbs.put(t.id, d);
                done.accept(d);
            });
        });
    }

    private void destroyWeb(Tab t) {
        if (t.web == null) return;
        ByssWebView w = t.web;
        t.web = null;
        detach(w);
        w.setKeepAlive(false);
        w.stopLoading();
        w.destroy();
    }

    private void goBack() {
        if (cur == null) return;
        if (cur.web != null && !cur.isHome() && cur.web.canGoBack()) {
            cur.web.goBack();
        } else if (cur.parent != null && tabs.contains(cur.parent)) {
            Tab p = cur.parent;
            closeTab(cur, false);
            showTab(p);
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (shots != null && shots.onBack()) return;
        if (fsView != null) {
            exitFullscreen(true);
            return;
        }
        if (menuOpen) {
            closeMenu();
            return;
        }
        if (stack.getVisibility() == View.VISIBLE) {
            stack.hide(null);
            return;
        }
        if (address.hasFocus()) {
            hideKeyboard();
            return;
        }
        if (cur != null && cur.web != null && !cur.isHome() && cur.web.canGoBack()) {
            cur.web.goBack();
            return;
        }
        if (cur != null && cur.parent != null && tabs.contains(cur.parent)) {
            goBack();
            return;
        }
        moveTaskToBack(true);
    }

    private boolean handleIntent(Intent i) {
        if (i == null) return false;
        String url = null;
        if (Intent.ACTION_VIEW.equals(i.getAction()) && i.getData() != null) {
            url = i.getDataString();
        } else if (Intent.ACTION_WEB_SEARCH.equals(i.getAction())) {
            String q = i.getStringExtra("query");
            if (q != null) url = SearchEngines.toUrl(q, SearchEngines.get(Prefs.engine()));
        }
        if (url == null) return false;
        skipHomeOnce = true;
        Tab t = new Tab();
        space = null;
        tabs.add(t);
        cur = null;
        showTab(t);
        loadUrl(t, url);
        return true;
    }

    // ================================================================ WebView

    private void createWeb(Tab t, String profile) {
        ByssWebView w = new ByssWebView(this);
        if (!Profiles.supported()) profile = null;
        Profiles.attach(w, profile);
        t.profile = profile;
        t.ytProfile = Profiles.YT.equals(profile);
        w.setBackgroundColor(Ui.bg);
        w.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        w.setOnScrollChangeListener((v, x, y, ox, oy) -> {
            int mode = Prefs.statusBar();
            if (t == cur && mode != 0) sampleAfterScroll(mode == 2 ? 120 : 220); // шапка меняет цвет при прокрутке
        });

        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setGeolocationEnabled(false);
        s.setSaveFormData(false);
        s.setUserAgentString(s.getUserAgentString().replace("; wv", "").replace(" Version/4.0", ""));
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(s, Ui.dark);
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(s, Collections.emptySet());
        }
        CookieManager cm = Profiles.cookies(t.profile);
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(w, Prefs.thirdPartyCookies());

        w.addJavascriptInterface(new Native(t), "ByssNative");
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(w, pageJs, Collections.singleton("*"));
            WebViewCompat.addDocumentStartJavaScript(w, ytJs, YT_ORIGINS);
        }
        w.setWebViewClient(new Client(t));
        w.setWebChromeClient(new Chrome(t));
        w.setDownloadListener((url, ua, disposition, mime, length) -> download(url, ua, disposition, mime));
        w.setOnLongClickListener(v -> onLongPress(t));
        t.web = w;
        trimLiveTabs(t);
    }

    /** Сколько обычных вкладок держим загруженными; с пятой самая давняя засыпает. */
    private static final int LIVE_TABS = 4;

    /** Не усыплять: текущая, играющая и YouTube. */
    private boolean keepsAwake(Tab t) {
        return t == cur || t == mediaTab || t.ytProfile || Profiles.isYouTube(t.pageHost) || Profiles.isYouTube(host(t.url));
    }

    /** Усыпить вкладку: память освобождается, адрес, заголовок, картинка и история «назад» остаются. */
    private void sleepTab(Tab t) {
        if (t.web == null) return;
        try {
            android.os.Bundle b = new android.os.Bundle();
            t.state = t.web.saveState(b) != null ? b : null;
        } catch (Throwable e) {
            t.state = null;
        }
        if (t == mediaTab) stopMedia();
        if (t == asTab) stopAutoscroll();
        translator.forget(t);
        t.translated = false;
        destroyWeb(t);
    }

    private void trimLiveTabs(Tab keep) {
        List<Tab> live = new ArrayList<>();
        for (Tab o : tabs) {
            if (o.web == null || o == mediaTab || o.ytProfile || Profiles.isYouTube(o.pageHost)) continue;
            live.add(o);
        }
        if (keep != null && !tabs.contains(keep) && keep.web != null) live.add(keep);
        while (live.size() > LIVE_TABS) {
            Tab oldest = null;
            for (Tab o : live) {
                if (o == keep || o == cur) continue;
                if (oldest == null || o.lastUsed < oldest.lastUsed) oldest = o;
            }
            if (oldest == null) break;
            live.remove(oldest);
            sleepTab(oldest);
        }
    }

    private static final byte[] EMPTY = new byte[0];

    private static WebResourceResponse blocked() {
        return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(EMPTY));
    }

    private final class Client extends WebViewClient {
        private final Tab t;

        Client(Tab t) {
            this.t = t;
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
            Uri u = req.getUrl();
            String sc = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
            if (sc.equals("http") || sc.equals("https")) {
                if (req.isForMainFrame() && Profiles.supported() && t.space == null) {
                    String h = u.getHost();
                    boolean toYt = Profiles.isYouTube(h);
                    if ((t.ytProfile && !Profiles.belongsToYouTube(h)) || (!t.ytProfile && toYt)) {
                        String url = u.toString();
                        ui.post(() -> loadUrl(t, url));
                        return true;
                    }
                }
                return false;
            }
            if (sc.equals("about") || sc.equals("data") || sc.equals("blob") || sc.equals("javascript")) return false;
            return openExternal(u.toString());
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
            Uri u = req.getUrl();
            if (req.isForMainFrame()) {
                t.pageHost = u.getHost();
                t.blocked.set(0);
                return null;
            }
            if (!Prefs.adblock()) return null;
            String sc = u.getScheme();
            if (!"http".equals(sc) && !"https".equals(sc)) return null;
            String ph = t.pageHost;
            if (ph != null && SitePrefs.isAllowed(t.space, ph)) return null;
            if (AdBlocker.shouldBlock(u, ph)) {
                t.blocked.incrementAndGet();
                if (t == cur) {
                    ui.removeCallbacks(refreshShield);
                    ui.postDelayed(refreshShield, 300);
                }
                return blocked();
            }
            return null;
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            String old = t.pageHost;
            t.url = url;
            t.pageHost = host(url);
            t.translated = false;
            if (t == asTab) stopAutoscroll(); // новая страница — автоскролл заново только по команде
            if (t == mediaTab && !Profiles.isYouTube(t.pageHost)) stopMedia();
            if (t == cur) {
                if (old != null && !AdBlocker.baseDomain(old).equals(AdBlocker.baseDomain(t.pageHost))) hidePrompt();
                updateBars();
            }
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            t.url = url;
            t.title = view.getTitle();
            swipe.setRefreshing(false);
            if (t == cur && Prefs.statusBar() != 0) {
                scheduleSample(30);
                barHandler.postDelayed(() -> scheduleSample(0), 700); // шапка сайта иногда дорисовывается позже
            }
            if (t == cur) {
                updateBars();
                maybePrompt(t);
                maybeOfferTranslate(t);
            }
        }

        @Override
        public void onPageCommitVisible(WebView view, String url) {
            if (t == cur && Prefs.statusBar() != 0) scheduleSample(20); // цвет — как только страница видна
        }

        @Override
        public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
            t.url = url;
            if (t == cur) updateBars();
        }

        @Override
        public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
            String url = t.url;
            boolean wasCur = t == cur;
            if (t.web == view) {
                t.web = null;
                detach(view);
                view.destroy();
            }
            if (wasCur && url != null) {
                createWeb(t, profileFor(t, url));
                holder.removeAllViews();
                holder.addView(t.web, new FrameLayout.LayoutParams(-1, -1));
                t.web.loadUrl(url);
            }
            return true;
        }
    }

    private final class Chrome extends WebChromeClient {
        private final Tab t;

        Chrome(Tab t) {
            this.t = t;
        }

        @Override
        public void onProgressChanged(WebView view, int p) {
            if (t == cur) setPageProgress(p);
            if (t == cur && p >= 35 && Prefs.statusBar() != 0) scheduleSample(120);
        }

        @Override
        public void onReceivedTitle(WebView view, String title) {
            t.title = title;
        }

        @Override
        public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
            if (!isUserGesture) return false; // всплывающие окна без нажатия — блокируем
            if (spaceTabs().size() >= Prefs.MAX_TABS) {
                Toast.makeText(BrowserActivity.this, "Открыто " + Prefs.MAX_TABS + " вкладок — закройте ненужные",
                        Toast.LENGTH_SHORT).show();
                return false;
            }
            Tab nt = new Tab();
            nt.url = "about:blank";
            nt.parent = t;
            nt.space = t.space;
            tabs.add(tabs.indexOf(t) + 1, nt);
            createWeb(nt, t.space != null ? t.profile : null);
            WebView.WebViewTransport tr = (WebView.WebViewTransport) resultMsg.obj;
            tr.setWebView(nt.web);
            resultMsg.sendToTarget();
            showTab(nt);
            return true;
        }

        @Override
        public void onCloseWindow(WebView window) {
            closeTab(t, true);
        }

        @Override
        public void onShowCustomView(View view, CustomViewCallback callback) {
            enterFullscreen(view, callback);
        }

        @Override
        public void onHideCustomView() {
            exitFullscreen(false);
        }

        @Override
        public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> cb, FileChooserParams params) {
            if (fileCallback != null) fileCallback.onReceiveValue(null);
            fileCallback = cb;
            try {
                startActivityForResult(params.createIntent(), REQ_FILE);
            } catch (ActivityNotFoundException e) {
                fileCallback = null;
                return false;
            }
            return true;
        }

        @Override
        public void onPermissionRequest(PermissionRequest request) {
            request.deny(); // камера и микрофон сайтам не выдаются
        }

        @Override
        public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
            callback.invoke(origin, false, false);
        }

        @Override
        public Bitmap getDefaultVideoPoster() {
            return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        }
    }

    /** Мост для скриптов страницы. Вызывается не из главного потока. */
    private final class Native {
        private final Tab t;

        Native(Tab t) {
            this.t = t;
        }

        @JavascriptInterface
        public String css(String host) {
            if (!Prefs.adblock() || host == null || SitePrefs.isAllowed(t.space, host)) return "";
            return AdBlocker.cssFor(host);
        }

        @JavascriptInterface
        public String cfg() {
            try {
                JSONObject o = new JSONObject();
                o.put("bg", Prefs.bgPlay());
                o.put("ads", Prefs.ytAds() && Prefs.adblock() && !SitePrefs.isAllowed(t.space, "youtube.com"));
                return o.toString();
            } catch (Exception e) {
                return "{}";
            }
        }

        @JavascriptInterface
        public void autoscrollEnd() {
            ui.post(() -> onAutoscrollEnd(t));
        }

        @JavascriptInterface
        public void media(boolean playing, String title, String artist) {
            ui.post(() -> onMedia(t, playing, title, artist));
        }

        @JavascriptInterface
        public void trBatch(int id, String json) {
            translator.onBatch(t, id, json);
        }
    }

    /** Ссылки на другие приложения: всегда спрашиваем, ничего не открываем сами. */
    private boolean openExternal(String url) {
        Intent intent;
        try {
            intent = url.startsWith("intent:") ? Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                    : new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        } catch (Exception e) {
            return true;
        }
        intent.addCategory(Intent.CATEGORY_BROWSABLE);
        intent.setComponent(null);
        intent.setSelector(null);
        String fallback = intent.getStringExtra("browser_fallback_url");
        ui.post(() -> askOpenApp(intent, fallback));
        return true;
    }

    /** «Нет» для приложения на сайте: 30 секунд этот сайт больше не спрашивает про это приложение. */
    private final java.util.Map<String, Long> appDeclined = new java.util.HashMap<>();
    private boolean appDialogShown;

    private void askOpenApp(Intent intent, String fallback) {
        PackageManager pm = getPackageManager();
        android.content.pm.ResolveInfo ri = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
        String app = ri == null ? null : String.valueOf(ri.loadLabel(pm));
        Tab t = cur;
        if (app == null) {
            if (fallback != null && t != null) loadUrl(t, fallback);
            else Toast.makeText(this, "Нет приложения для этой ссылки", Toast.LENGTH_SHORT).show();
            return;
        }
        String site = t == null ? "" : AdBlocker.baseDomain(t.pageHost);
        String key = site + "|" + (ri.activityInfo != null ? ri.activityInfo.packageName : app);
        Long declined = appDeclined.get(key);
        if (appDialogShown || (declined != null && System.currentTimeMillis() - declined < 30_000)) return;
        appDialogShown = true;
        new AlertDialog.Builder(this)
                .setTitle("Открыть приложение?")
                .setMessage("Сайт хочет открыть «" + app + "».")
                .setPositiveButton("Открыть", (d, w) -> {
                    try {
                        startActivity(intent);
                    } catch (Exception e) {
                        Toast.makeText(this, "Не получилось открыть", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Нет", (d, w) -> {
                    appDeclined.put(key, System.currentTimeMillis());
                    if (fallback != null && t != null) loadUrl(t, fallback);
                })
                .setOnCancelListener(d -> appDeclined.put(key, System.currentTimeMillis()))
                .setOnDismissListener(d -> appDialogShown = false)
                .show();
    }

    // ============================================================ полноэкранное

    private void enterFullscreen(View v, WebChromeClient.CustomViewCallback cb) {
        if (fsView != null) {
            cb.onCustomViewHidden();
            return;
        }
        fsView = v;
        fsCallback = cb;
        fsLayer.addView(v, new FrameLayout.LayoutParams(-1, -1));
        fsLayer.setVisibility(View.VISIBLE);
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            //noinspection deprecation
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    private void exitFullscreen(boolean notifyPage) {
        if (fsView == null) return;
        fsLayer.removeAllViews();
        fsLayer.setVisibility(View.GONE);
        fsView = null;
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) c.show(WindowInsets.Type.systemBars());
        } else {
            //noinspection deprecation
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
        WebChromeClient.CustomViewCallback cb = fsCallback;
        fsCallback = null;
        if (notifyPage && cb != null) cb.onCustomViewHidden();
        applyStatusBar();
    }

    // ================================================================ загрузки

    private void download(String url, String ua, String disposition, String mime) {
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
            Toast.makeText(this, "Этот файл нельзя скачать", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            String name = URLUtil.guessFileName(url, disposition, mime);
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
            if (mime != null) r.setMimeType(mime);
            String cookie = Profiles.cookies(cur == null ? null : cur.profile).getCookie(url);
            if (cookie != null) r.addRequestHeader("Cookie", cookie);
            if (ua != null) r.addRequestHeader("User-Agent", ua);
            r.setTitle(name);
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE);
            r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
            DownloadManager dm = getSystemService(DownloadManager.class);
            Downloads.remember(dm.enqueue(r));
            Toast.makeText(this, "Скачивается: " + name + " — смотрите Меню → Скачанные файлы", Toast.LENGTH_SHORT).show();
            watchDownloads();
        } catch (Exception e) {
            Toast.makeText(this, "Не удалось скачать: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private boolean onLongPress(Tab t) {
        if (t.web == null) return false;
        WebView.HitTestResult r = t.web.getHitTestResult();
        int type = r.getType();
        String extra = r.getExtra();
        if (type == WebView.HitTestResult.SRC_ANCHOR_TYPE || type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
            Message m = ui.obtainMessage();
            t.web.requestFocusNodeHref(m);
            String href = m.getData().getString("url");
            if (href == null || href.isEmpty()) href = extra;
            linkMenu(t, href, type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE ? extra : null);
            return true;
        }
        if (type == WebView.HitTestResult.IMAGE_TYPE && extra != null) {
            linkMenu(t, null, extra);
            return true;
        }
        return false;
    }

    private void linkMenu(Tab t, String link, String image) {
        Haptics.thud(root);
        List<String> labels = new ArrayList<>();
        List<Runnable> acts = new ArrayList<>();
        if (link != null) {
            labels.add("Открыть в новой вкладке");
            acts.add(() -> {
                Runnable open = () -> {
                    Tab nt = new Tab();
                    nt.parent = t;
                    nt.space = t.space;
                    tabs.add(tabs.indexOf(t) + 1, nt);
                    showTab(nt);
                    loadUrl(nt, link);
                };
                if (roomForTab(open)) open.run();
            });
            labels.add("Открыть в фоне");
            acts.add(() -> {
                if (!roomForTab(null)) return;
                Tab nt = new Tab();
                nt.url = link;
                nt.space = t.space;
                tabs.add(tabs.indexOf(t) + 1, nt);
                updateBars();
                saveTabs();
                Toast.makeText(this, "Открыто в фоне", Toast.LENGTH_SHORT).show();
            });
            labels.add("Копировать ссылку");
            acts.add(() -> copy(link));
            labels.add("Поделиться ссылкой");
            acts.add(() -> share(link));
        }
        if (image != null && (image.startsWith("http://") || image.startsWith("https://"))) {
            labels.add("Скачать картинку");
            acts.add(() -> download(image, null, null, null));
            labels.add("Открыть картинку");
            acts.add(() -> loadUrl(t, image));
        }
        if (labels.isEmpty()) return;
        new AlertDialog.Builder(this)
                .setTitle(link != null ? link : image)
                .setItems(labels.toArray(new String[0]), (d, w) -> acts.get(w).run())
                .show();
    }

    private void copy(String text) {
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("link", text));
        Toast.makeText(this, "Скопировано", Toast.LENGTH_SHORT).show();
    }

    private void share(String text) {
        Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text);
        startActivity(Intent.createChooser(i, "Поделиться"));
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_FILE) {
            if (fileCallback != null) fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data));
            fileCallback = null;
        }
    }

    // ============================================================ трекеры: вопрос

    private void maybePrompt(Tab t) {
        if (t != cur || !Prefs.adblock() || !Prefs.askSites() || t.pageHost == null) return;
        String base = AdBlocker.baseDomain(t.pageHost);
        if (base.equals(promptBase) && prompt.getVisibility() == View.VISIBLE) {
            updatePromptCount();
            return;
        }
        if (SitePrefs.hasDecision(t.space, t.pageHost) || base.equals(promptDismissed)) return;
        showPrompt(t, base);
    }

    private TextView promptText;

    private void showPrompt(Tab t, String base) {
        Context c = this;
        promptBase = base;
        // спрашиваем про сайт один раз: пока не ответили — трекеры блокируются, и больше не спросим
        SitePrefs.set(t.space, t.pageHost, false);
        prompt.removeAllViews();
        prompt.setPadding(Ui.dp(c, 16), Ui.dp(c, 12), Ui.dp(c, 8), Ui.dp(c, 10));
        prompt.setBackground(Ui.round(Ui.card, Ui.dp(c, 22)));
        prompt.setElevation(Ui.dp(c, 6));

        LinearLayout top = new LinearLayout(c);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        ImageView ic = new ImageView(c);
        ic.setImageResource(R.drawable.ic_shield);
        ic.setImageTintList(ColorStateList.valueOf(Ui.text));
        top.addView(ic, new LinearLayout.LayoutParams(Ui.dp(c, 20), Ui.dp(c, 20)));
        promptText = Ui.text(c, "", 14, Ui.text, false);
        promptText.setPadding(Ui.dp(c, 12), 0, Ui.dp(c, 4), 0);
        top.addView(promptText, new LinearLayout.LayoutParams(0, -2, 1f));
        ImageButton x = Ui.iconButton(c, R.drawable.ic_close);
        x.setImageTintList(ColorStateList.valueOf(Ui.text2));
        x.setOnClickListener(v -> {
            promptDismissed = base;
            hidePrompt();
        });
        top.addView(x, new LinearLayout.LayoutParams(Ui.dp(c, 40), Ui.dp(c, 40)));
        prompt.addView(top);

        LinearLayout btns = new LinearLayout(c);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.END);
        btns.setPadding(0, Ui.dp(c, 6), Ui.dp(c, 6), 0);
        TextView allow = pillButton(c, "Разрешить трекеры", false);
        allow.setOnClickListener(v -> {
            Haptics.click(v);
            SitePrefs.set(t.space, t.pageHost, true);
            hidePrompt();
            if (t.web != null) t.web.reload();
            updateShield();
        });
        TextView block = pillButton(c, "Блокировать", true);
        block.setOnClickListener(v -> {
            Haptics.click(v);
            SitePrefs.set(t.space, t.pageHost, false);
            hidePrompt();
            updateShield();
        });
        btns.addView(allow);
        View sp = new View(c);
        btns.addView(sp, new LinearLayout.LayoutParams(Ui.dp(c, 8), 1));
        btns.addView(block);
        prompt.addView(btns);
        updatePromptCount();

        prompt.setVisibility(View.VISIBLE);
        prompt.setAlpha(0f);
        prompt.setTranslationY(Ui.dp(c, 24));
        prompt.animate().alpha(1f).translationY(0).setDuration(Ui.anim(220)).start();
    }

    private TextView pillButton(Context c, String label, boolean primary) {
        TextView b = Ui.text(c, label, 14, primary ? Ui.bg : Ui.text, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(Ui.dp(c, 16), 0, Ui.dp(c, 16), 0);
        b.setMinHeight(Ui.dp(c, 38));
        b.setBackground(Ui.ripple(Ui.round(primary ? Ui.text : Ui.pill, Ui.dp(c, 19)), null));
        return b;
    }

    private void updatePromptCount() {
        if (promptText == null || cur == null) return;
        SpannableStringBuilder sb = new SpannableStringBuilder();
        sb.append(promptBase);
        sb.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), 0, promptBase.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.append(" — новый сайт. Трекеры и реклама сейчас заблокированы");
        int n = cur.blocked.get();
        if (n > 0) sb.append(" (").append(String.valueOf(n)).append(")");
        sb.append(". Оставить так?");
        promptText.setText(sb);
    }

    /** Что показать, когда уберётся текущая плашка (предложение перевода ждёт вопроса о трекерах). */
    private Runnable afterPrompt;

    private void hidePrompt() {
        if (prompt.getVisibility() != View.VISIBLE) return;
        promptBase = null;
        prompt.animate().alpha(0f).setDuration(Ui.anim(150)).withEndAction(() -> {
            prompt.setVisibility(View.GONE);
            Runnable r = afterPrompt;
            afterPrompt = null;
            if (r != null) r.run();
        }).start();
    }

    // ===================================================== перевод: предложение

    /** Сайты, где в этот запуск нажали «Нет» / «Перевести» (тогда дальше переводим сами). */

    private static boolean isTranslatorHost(String h) {
        return h.endsWith("translate.goog") || h.contains("translate.yandex") || h.startsWith("translate.google.");
    }

    /** Страница не на русском и не на английском — предложить перевод. */
    // ============================================================ текст страницы

    /**
     * Весь текст страницы в окне, где его можно выделить и скопировать как есть — с русскими буквами
     * (в отличие от «Выбрать» в недавних приложениях: там Android распознаёт картинку и путает буквы).
     */
    private void pageText() {
        if (cur == null || cur.web == null || cur.isHome()) return;
        Tab t = cur;
        t.web.evaluateJavascript("(function(){var s=(document.body&&document.body.innerText)||'';"
                + "return s.length>60000?s.slice(0,60000):s})()", v -> {
            String text;
            try {
                text = new org.json.JSONArray("[" + v + "]").getString(0);
            } catch (Exception e) {
                text = "";
            }
            text = text.replaceAll("[ \\t]+\\n", "\n").replaceAll("\n{3,}", "\n\n").trim();
            if (text.isEmpty()) {
                Toast.makeText(this, "На странице нет текста", Toast.LENGTH_SHORT).show();
                return;
            }
            final String all = text;
            TextView tv = Ui.text(this, all, 15, Ui.text, false);
            tv.setTextIsSelectable(true);
            tv.setLineSpacing(0, 1.15f);
            int p = Ui.dp(this, 20);
            tv.setPadding(p, Ui.dp(this, 4), p, p);
            android.widget.ScrollView sv = new android.widget.ScrollView(this);
            sv.addView(tv);
            new AlertDialog.Builder(this)
                    .setTitle("Выделите нужный текст")
                    .setView(sv)
                    .setPositiveButton("Скопировать всё", (d, w) -> {
                        android.content.ClipboardManager cm = getSystemService(android.content.ClipboardManager.class);
                        if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("Текст страницы", all));
                        Toast.makeText(this, "Текст скопирован", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("Закрыть", null)
                    .show();
        });
    }

    private void maybeOfferTranslate(Tab t) {
        if (!Prefs.offerTranslate() || t.isHome() || t.translated || t.web == null || t.pageHost == null) return;
        String h = t.pageHost.toLowerCase(Locale.ROOT);
        if (isTranslatorHost(h) || Profiles.isYouTube(h)) return;
        String base = AdBlocker.baseDomain(h);
        int choice = SitePrefs.translate(t.space, base);
        if (choice < 0) return; // сказали «нет» — для этого сайта больше не предлагаем
        boolean auto = choice > 0 && "device".equals(Prefs.translateMode());
        if (!auto && base.equals(t.langChecked)) return;
        t.langChecked = base;
        String page = t.url;
        translator.detect(t, lang -> {
            if (lang == null || PageTranslator.known(lang)) return;
            if (t != cur || t.translated || page == null || !page.equals(t.url)) return;
            if (auto) {
                translator.translate(t, "device");
                return;
            }
            if (prompt.getVisibility() == View.VISIBLE) {
                afterPrompt = () -> showTranslateOffer(t, lang, base, page);
            } else {
                showTranslateOffer(t, lang, base, page);
            }
        });
    }

    private void showTranslateOffer(Tab t, String lang, String base, String page) {
        if (t != cur || t.translated || !page.equals(t.url) || SitePrefs.translate(t.space, base) < 0) return;
        Context c = this;
        promptBase = null;
        prompt.removeAllViews();
        prompt.setPadding(Ui.dp(c, 16), Ui.dp(c, 12), Ui.dp(c, 8), Ui.dp(c, 10));
        prompt.setBackground(Ui.round(Ui.card, Ui.dp(c, 22)));
        prompt.setElevation(Ui.dp(c, 6));

        LinearLayout top = new LinearLayout(c);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        ImageView ic = new ImageView(c);
        ic.setImageResource(R.drawable.ic_translate);
        ic.setImageTintList(ColorStateList.valueOf(Ui.text));
        top.addView(ic, new LinearLayout.LayoutParams(Ui.dp(c, 20), Ui.dp(c, 20)));
        String to = PageTranslator.targetName(PageTranslator.targetFor(lang)).toLowerCase(Locale.ROOT);
        TextView msg = Ui.text(c, "Язык страницы: " + PageTranslator.langName(lang) + ". Перевести на " + to + "?",
                14, Ui.text, false);
        msg.setPadding(Ui.dp(c, 12), 0, Ui.dp(c, 4), 0);
        top.addView(msg, new LinearLayout.LayoutParams(0, -2, 1f));
        ImageButton x = Ui.iconButton(c, R.drawable.ic_close);
        x.setImageTintList(ColorStateList.valueOf(Ui.text2));
        x.setOnClickListener(v -> {
            SitePrefs.setTranslate(t.space, base, false);
            hidePrompt();
        });
        top.addView(x, new LinearLayout.LayoutParams(Ui.dp(c, 40), Ui.dp(c, 40)));
        prompt.addView(top);

        LinearLayout btns = new LinearLayout(c);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.END);
        btns.setPadding(0, Ui.dp(c, 6), Ui.dp(c, 6), 0);
        TextView no = pillButton(c, "Нет", false);
        no.setOnClickListener(v -> {
            Haptics.click(v);
            SitePrefs.setTranslate(t.space, base, false);
            hidePrompt();
        });
        TextView yes = pillButton(c, "Перевести", true);
        yes.setOnClickListener(v -> {
            Haptics.click(v);
            SitePrefs.setTranslate(t.space, base, true);
            hidePrompt();
            translator.translate(t, Prefs.translateMode());
        });
        btns.addView(no);
        View sp = new View(c);
        btns.addView(sp, new LinearLayout.LayoutParams(Ui.dp(c, 8), 1));
        btns.addView(yes);
        prompt.addView(btns);

        prompt.setVisibility(View.VISIBLE);
        prompt.setAlpha(0f);
        prompt.setTranslationY(Ui.dp(c, 24));
        prompt.animate().alpha(1f).translationY(0).setDuration(Ui.anim(220)).start();
    }

    private void onSiteButton() {
        if (cur == null || cur.isHome() || address.hasFocus()) {
            pickEngine("engine");
            return;
        }
        String h = cur.pageHost;
        if (h == null) return;
        boolean allowed = SitePrefs.isAllowed(cur.space, h);
        String base = AdBlocker.baseDomain(h);
        String msg = (Prefs.adblock()
                ? (allowed ? "Трекеры и реклама на этом сайте разрешены." : "Трекеры и реклама блокируются.")
                : "Блокировка выключена в настройках.")
                + "\nЗаблокировано на этой странице: " + cur.blocked.get()
                + "\nПравил блокировки: " + AdBlocker.ruleCount();
        AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle(base).setMessage(msg)
                .setNegativeButton("Закрыть", null);
        if (Prefs.adblock()) {
            b.setPositiveButton(allowed ? "Блокировать" : "Разрешить трекеры", (d, w) -> {
                SitePrefs.set(cur.space, h, !allowed);
                if (cur.web != null) cur.web.reload();
                updateShield();
            });
        }
        b.show();
    }

    void pickEngine(String key) {
        List<SearchEngines.Engine> engines = SearchEngines.all();
        String curId = Prefs.sp().getString(key, key.equals("home2") ? "yandex" : key.equals("home3") ? HomeView.FX : "google");
        boolean fx = key.equals("home3");
        List<String> ids = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        if (fx) {
            ids.add(HomeView.FX);
            labels.add("Конвертер валют");
        }
        for (SearchEngines.Engine e : engines) {
            ids.add(e.id);
            labels.add(e.name);
        }
        String[] names = labels.toArray(new String[0]);
        int checked = Math.max(0, ids.indexOf(curId));
        String title = key.equals("engine") ? "Поиск в адресной строке"
                : key.equals("home1") ? "Первый поиск на главной"
                : key.equals("home2") ? "Второй поиск на главной" : "Третья строка на главной";
        new AlertDialog.Builder(this).setTitle(title)
                .setSingleChoiceItems(names, checked, (d, w) -> {
                    Prefs.put(key, ids.get(w));
                    d.dismiss();
                    home.refresh();
                    updateShield();
                })
                .setNegativeButton("Отмена", null).show();
    }

    // ================================================================= перевод

    private void toggleTranslate(String mode) {
        if (cur == null || cur.isHome()) return;
        if (cur.translated) translator.restore(cur); else translator.translate(cur, mode);
    }

    private void chooseTranslateOnce() {
        if (cur == null || cur.isHome()) return;
        String[] names = {"На телефоне (Google ML Kit)", "Через Google Переводчик", "Через Яндекс Переводчик"};
        String[] modes = {"device", "google", "yandex"};
        new AlertDialog.Builder(this).setTitle("Перевести страницу")
                .setItems(names, (d, w) -> translator.translate(cur, modes[w])).show();
    }

    void onTranslateState(Tab t) {
        if (t == cur) {
            updateBars();
            if (t.translated) {
                String to = t.trTo != null ? t.trTo : Prefs.translateTarget();
                Toast.makeText(this, "Перевод на " + PageTranslator.targetName(to).toLowerCase(Locale.ROOT)
                        + " · Google ML Kit, на телефоне", Toast.LENGTH_SHORT).show();
            }
        }
    }

    // ==================================================================== автоскролл

    private void openAutoscroll() {
        Autoscroll.show(this, new Autoscroll.Host() {
            @Override public boolean running() {
                return asTab != null && asTab == cur;
            }

            @Override public void toggle() {
                if (running()) stopAutoscroll();
                else startAutoscroll();
            }

            @Override public void changed() {
                if (asTab != null && !asPaused) runAutoscroll();
            }
        });
    }

    private void startAutoscroll() {
        if (cur == null || cur.isHome() || cur.web == null) return;
        if (asTab != null && asTab != cur) stopAutoscroll();
        asTab = cur;
        asPaused = false;
        runAutoscroll();
        showAsPill();
    }

    /** Запустить (или перезапустить с новыми настройками): страница сама или свайпы по таймеру. */
    private void runAutoscroll() {
        ui.removeCallbacks(asSwipeTick);
        if (asTab == null || asTab.web == null) return;
        if (Autoscroll.swipeMode()) {
            asTab.web.evaluateJavascript(Autoscroll.STOP_JS, null);
            ui.postDelayed(asSwipeTick, Autoscroll.interval() * 1000L);
        } else {
            asTab.web.evaluateJavascript(Autoscroll.startJs(asJs), null);
        }
    }

    /** Свайп вверх через интервал — для лент, которые листаются только пальцем (TikTok, Shorts). */
    private final Runnable asSwipeTick = new Runnable() {
        @Override public void run() {
            if (asTab == null || asPaused || !Autoscroll.swipeMode()) return;
            if (asTab == cur && asTab.web != null && !menuOpen) swipeUp(asTab.web);
            ui.postDelayed(this, Autoscroll.interval() * 1000L);
        }
    };

    private void swipeUp(WebView w) {
        int h = w.getHeight();
        int wd = w.getWidth();
        if (h <= 0 || wd <= 0) return;
        final long t0 = SystemClock.uptimeMillis();
        final float x = wd / 2f;
        final float y0 = h * 0.72f;
        final float y1 = h * 0.28f;
        final int steps = 10;
        final long dur = 220;
        MotionEvent down = MotionEvent.obtain(t0, t0, MotionEvent.ACTION_DOWN, x, y0, 0);
        down.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        w.dispatchTouchEvent(down);
        down.recycle();
        for (int i = 1; i <= steps; i++) {
            final int k = i;
            ui.postDelayed(() -> {
                MotionEvent e = MotionEvent.obtain(t0, SystemClock.uptimeMillis(),
                        k == steps ? MotionEvent.ACTION_UP : MotionEvent.ACTION_MOVE, x, y0 + (y1 - y0) * k / steps, 0);
                e.setSource(InputDevice.SOURCE_TOUCHSCREEN);
                w.dispatchTouchEvent(e);
                e.recycle();
            }, dur * k / steps);
        }
    }

    private void stopAutoscroll() {
        ui.removeCallbacks(asSwipeTick);
        if (asTab != null && asTab.web != null) asTab.web.evaluateJavascript(Autoscroll.STOP_JS, null);
        asTab = null;
        asPaused = false;
        hideAsPill();
    }

    private void toggleAutoscrollPause() {
        if (asTab == null || asTab.web == null) return;
        asPaused = !asPaused;
        if (asPaused) {
            ui.removeCallbacks(asSwipeTick);
            asTab.web.evaluateJavascript(Autoscroll.STOP_JS, null);
        } else {
            runAutoscroll();
        }
        if (asPlayBtn != null) asPlayBtn.setImageResource(asPaused ? R.drawable.ic_n_play : R.drawable.ic_n_pause);
    }

    private void onAutoscrollEnd(Tab t) {
        if (t != asTab) return;
        asTab = null;
        asPaused = false;
        hideAsPill();
        Toast.makeText(this, "Автоскролл остановлен — конец страницы", Toast.LENGTH_SHORT).show();
    }

    /** Плашка над нижней панелью: пауза · «Автоскролл» (настройки) · стоп. */
    private void showAsPill() {
        Context c = this;
        if (asPill == null) {
            asPill = new LinearLayout(c);
            asPill.setOrientation(LinearLayout.HORIZONTAL);
            asPill.setGravity(Gravity.CENTER_VERTICAL);
            asPill.setBackground(Ui.round(Ui.card, Ui.dp(c, 22)));
            asPill.setElevation(Ui.dp(c, 8));
            asPill.setPadding(Ui.dp(c, 4), 0, Ui.dp(c, 4), 0);
            asPlayBtn = Ui.iconButton(c, R.drawable.ic_n_pause);
            asPlayBtn.setContentDescription("Пауза");
            asPlayBtn.setOnClickListener(v -> {
                Haptics.click(v);
                toggleAutoscrollPause();
            });
            asPill.addView(asPlayBtn, new LinearLayout.LayoutParams(Ui.dp(c, 40), Ui.dp(c, 40)));
            TextView lbl = Ui.text(c, "Автоскролл", 14, Ui.text, false);
            lbl.setPadding(Ui.dp(c, 4), 0, Ui.dp(c, 8), 0);
            lbl.setOnClickListener(v -> {
                Haptics.click(v);
                openAutoscroll();
            });
            asPill.addView(lbl, new LinearLayout.LayoutParams(-2, Ui.dp(c, 40)));
            ImageButton close = Ui.iconButton(c, R.drawable.ic_close);
            close.setContentDescription("Остановить автоскролл");
            close.setOnClickListener(v -> {
                Haptics.click(v);
                stopAutoscroll();
            });
            asPill.addView(close, new LinearLayout.LayoutParams(Ui.dp(c, 40), Ui.dp(c, 40)));
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, Ui.dp(c, 44), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            int at = root.indexOfChild(overlay);
            if (at < 0) root.addView(asPill, lp);
            else root.addView(asPill, at, lp); // под меню, чтобы меню открывалось поверх
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) asPill.getLayoutParams();
        lp.bottomMargin = bottom.getHeight() + Ui.dp(c, 12);
        asPill.setLayoutParams(lp);
        asPlayBtn.setImageResource(R.drawable.ic_n_pause);
        asPill.animate().cancel();
        asPill.setVisibility(View.VISIBLE);
        asPill.setAlpha(0f);
        asPill.animate().alpha(1f).setDuration(Ui.anim(160)).start();
    }

    private void hideAsPill() {
        if (asPill == null || asPill.getVisibility() != View.VISIBLE) return;
        asPill.animate().alpha(0f).setDuration(Ui.anim(140)).withEndAction(() -> asPill.setVisibility(View.GONE)).start();
    }

    // ==================================================================== меню

    private void openMenu() {
        if (address.hasFocus()) hideKeyboard();
        Context c = this;
        menuOpen = true;
        overlay.removeAllViews();
        overlay.setBackgroundColor(Ui.scrim);
        overlay.setVisibility(View.VISIBLE);
        overlay.setAlpha(0f);
        overlay.animate().alpha(1f).setDuration(Ui.anim(160)).start();
        overlay.setOnClickListener(v -> closeMenu());

        LinearLayout panel = new LinearLayout(c);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(Ui.round(Ui.card, Ui.dp(c, 20)));
        panel.setElevation(Ui.dp(c, 14));
        panel.setPadding(0, Ui.dp(c, 8), 0, Ui.dp(c, 8));
        panel.setClickable(true);

        boolean translated = cur != null && cur.translated;
        boolean site = cur != null && !cur.isHome() && cur.url != null;
        if (site && cur.pageHost != null) siteSwitch(panel, cur);

        // Пункты меню: чем длиннее текст, тем выше; «Удалить все данные» — всегда последний.
        List<Object[]> items = new ArrayList<>();
        android.content.pm.ResolveInfo app = site ? appFor(cur.url) : null;
        if (app != null) {
            String appName = String.valueOf(app.loadLabel(getPackageManager()));
            items.add(new Object[]{R.drawable.ic_forward, "Открыть в приложении", appName, (Runnable) () -> openInApp(cur.url, app)});
        }
        if (site) {
            items.add(new Object[]{R.drawable.ic_translate, translated ? "Показать оригинал" : "Перевести страницу", null,
                    (Runnable) () -> toggleTranslate(Prefs.translateMode())});
            items.add(new Object[]{R.drawable.ic_screenshot, "Скриншот", null, (Runnable) () -> shots.chooser()});
            items.add(new Object[]{R.drawable.ic_autoscroll, "Автоскролл",
                    asTab == cur ? (asPaused ? "пауза" : "идёт") : null, (Runnable) this::openAutoscroll});
            items.add(new Object[]{R.drawable.ic_copy, "Выделить и скопировать текст", null, (Runnable) this::pageText});
        }
        items.add(new Object[]{R.drawable.ic_reload, "Обновить браузер", null, (Runnable) () -> Updater.check(this)});
        int dlNow = Downloads.active(this);
        items.add(new Object[]{R.drawable.ic_download, "Скачанные файлы", dlNow > 0 ? "↓ " + dlNow : null,
                (Runnable) this::openDownloads});
        items.add(new Object[]{R.drawable.ic_settings, "Доп. настройки", null,
                (Runnable) () -> startActivity(new Intent(this, SettingsActivity.class))});
        items.add(new Object[]{R.drawable.ic_theme, Ui.dark ? "Светлая тема" : "Тёмная тема", null, (Runnable) () -> {
            Prefs.put("dark", !Ui.dark);
            saveTabs();
            recreate();
        }});
        String incog = space != null ? Spaces.name(space) : Prefs.incogName();
        items.add(new Object[]{R.drawable.ic_shield, incog, null, (Runnable) this::openSpaces});
        java.util.Collections.sort(items, (x, y) -> Integer.compare(((String) y[1]).length(), ((String) x[1]).length()));
        for (Object[] it : items) {
            menuItem(panel, (Integer) it[0], (String) it[1], (String) it[2], Ui.text, (Runnable) it[3]);
        }
        View line = new View(c);
        line.setBackgroundColor(Ui.line);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Ui.dp(c, 1));
        lp.setMargins(Ui.dp(c, 16), Ui.dp(c, 6), Ui.dp(c, 16), Ui.dp(c, 6));
        panel.addView(line, lp);
        menuItem(panel, R.drawable.ic_trash, "Удалить все данные", null, Ui.danger, this::confirmWipe);

        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(Ui.dp(c, 270), -2, Gravity.BOTTOM | Gravity.END);
        pp.rightMargin = Ui.dp(c, 10);
        pp.bottomMargin = bottom.getHeight() + Ui.dp(c, 8);
        overlay.addView(panel, pp);
        panel.setPivotX(Ui.dp(c, 270));
        panel.post(() -> {
            panel.setPivotY(panel.getHeight());
            panel.setScaleX(0.9f);
            panel.setScaleY(0.9f);
            panel.setAlpha(0f);
            panel.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(Ui.anim(200))
                    .setInterpolator(new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)).start();
        });
    }

    // ====================================================== скриншоты

    private Screenshots shots;

    Tab currentTab() {
        return cur;
    }

    int bottomBarHeight() {
        return bottom != null ? bottom.getHeight() : 0;
    }

    /** Быстрый переключатель в меню: реклама и слежка для открытого сайта. */
    private void siteSwitch(LinearLayout panel, Tab t) {
        Context c = this;
        String host = t.pageHost;
        String base = AdBlocker.baseDomain(host);
        boolean global = Prefs.adblock();

        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(c, 18), Ui.dp(c, 4), Ui.dp(c, 12), Ui.dp(c, 4));
        ImageView ic = new ImageView(c);
        boolean on = global && !SitePrefs.isAllowed(t.space, host);
        ic.setImageResource(on ? R.drawable.ic_shield : R.drawable.ic_shield_off);
        ic.setImageTintList(ColorStateList.valueOf(Ui.text));
        row.addView(ic, new LinearLayout.LayoutParams(Ui.dp(c, 22), Ui.dp(c, 22)));

        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(Ui.dp(c, 16), 0, Ui.dp(c, 6), 0);
        texts.addView(Ui.text(c, "Блокировать рекламу и слежку", 15, Ui.text, false));
        int n = t.blocked.get();
        String sub = !global ? "выключено в доп. настройках"
                : base + (on && n > 0 ? " · заблокировано " + n : on ? "" : " · разрешено");
        TextView subView = Ui.text(c, sub, 12, Ui.text2, false);
        subView.setSingleLine(true);
        subView.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        texts.addView(subView);
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));

        android.widget.Switch sw = new android.widget.Switch(c);
        int[][] states = {{android.R.attr.state_checked}, {}};
        sw.setThumbTintList(new ColorStateList(states, new int[]{Ui.text, Ui.text2}));
        sw.setTrackTintList(new ColorStateList(states, new int[]{Ui.text2, Ui.line}));
        sw.setChecked(on);
        sw.setEnabled(global);
        sw.setOnCheckedChangeListener((b, checked) -> {
            Haptics.click(b);
            SitePrefs.set(t.space, host, !checked);
            ic.setImageResource(checked ? R.drawable.ic_shield : R.drawable.ic_shield_off);
            subView.setText(base + (checked ? "" : " · разрешено"));
            if (base.equals(promptBase)) hidePrompt();
            updateShield();
            ui.postDelayed(() -> {
                closeMenu();
                if (t.web != null) t.web.reload();
            }, 280);
        });
        row.addView(sw, new LinearLayout.LayoutParams(-2, -2));
        row.setOnClickListener(v -> {
            if (sw.isEnabled()) sw.toggle();
        });
        panel.addView(row, new LinearLayout.LayoutParams(-1, Ui.dp(c, 60)));

        View line = new View(c);
        line.setBackgroundColor(Ui.line);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Ui.dp(c, 1));
        lp.setMargins(Ui.dp(c, 16), Ui.dp(c, 4), Ui.dp(c, 16), Ui.dp(c, 4));
        panel.addView(line, lp);
    }

    private void menuItem(LinearLayout panel, int icon, String label, String hint, int color, Runnable action) {
        Context c = this;
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(c, 18), 0, Ui.dp(c, 18), 0);
        row.setBackground(Ui.ripple(null, Ui.round(0xFFFFFFFF, 0)));
        ImageView ic = new ImageView(c);
        ic.setImageResource(icon);
        ic.setImageTintList(ColorStateList.valueOf(color));
        row.addView(ic, new LinearLayout.LayoutParams(Ui.dp(c, 22), Ui.dp(c, 22)));
        TextView t = Ui.text(c, label, 15, color, false);
        t.setPadding(Ui.dp(c, 16), 0, 0, 0);
        row.addView(t, new LinearLayout.LayoutParams(0, -1, 1f));
        if (hint != null) {
            TextView h = Ui.text(c, hint, 12, Ui.text2, false);
            row.addView(h, new LinearLayout.LayoutParams(-2, -1));
        }
        row.setOnClickListener(v -> {
            Haptics.click(v);
            closeMenu();
            action.run();
        });
        panel.addView(row, new LinearLayout.LayoutParams(-1, Ui.dp(c, 50)));
    }

    private void closeMenu() {
        if (!menuOpen) return;
        menuOpen = false;
        dlOpen = false;
        dlList = null;
        dlRows.clear();
        overlay.animate().alpha(0f).setDuration(Ui.anim(140)).withEndAction(() -> {
            overlay.setVisibility(View.GONE);
            overlay.removeAllViews();
        }).start();
    }

    // ============================================================ скачанные файлы

    private static int dlGreen() {
        return Ui.dark ? 0xFF30D158 : 0xFF248A3D;
    }

    /** Проверять загрузки: пока что-то качается — «⋮» мигает зелёной стрелкой. */
    private void watchDownloads() {
        ui.removeCallbacks(dlTick);
        ui.post(dlTick);
    }

    private void downloadTick() {
        List<Downloads.Item> list = Downloads.list(this);
        int active = 0;
        for (Downloads.Item it : list) if (it.active()) active++;
        if (dlOpen) fillDownloads(list);
        if (active > 0) {
            dlArrow = !dlArrow;
            setMenuIcon(dlArrow);
            ui.postDelayed(dlTick, 750);
        } else if (dlArrow) {
            dlArrow = false;
            setMenuIcon(false);
        }
    }

    /** «⋮» ↔ зелёная стрелка «скачивается»: мягкая смена, стрелка чуть «опускается» вниз. */
    private void setMenuIcon(boolean arrow) {
        ImageButton b = menuBtn;
        if (b == null) return;
        b.animate().cancel();
        b.animate().alpha(0f).setDuration(Ui.anim(110)).withEndAction(() -> {
            b.setImageResource(arrow ? R.drawable.ic_download : R.drawable.ic_menu);
            b.setImageTintList(ColorStateList.valueOf(arrow ? dlGreen() : Ui.text));
            b.setContentDescription(arrow ? "Меню · идёт загрузка" : "Меню");
            b.setTranslationY(arrow ? -Ui.dp(this, 4) : 0);
            b.animate().alpha(1f).translationY(0).setDuration(Ui.anim(arrow ? 420 : 150))
                    .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
        }).start();
    }

    /** Список загрузок браузера, как в Chrome: что скачивается и что уже скачано. */
    private void openDownloads() {
        Context c = this;
        overlay.animate().cancel(); // меню только что закрылось — его анимация не должна убрать этот список
        menuOpen = true;
        dlOpen = true;
        dlRows.clear();
        overlay.removeAllViews();
        overlay.setBackgroundColor(Ui.scrim);
        overlay.setVisibility(View.VISIBLE);
        overlay.setAlpha(0f);
        overlay.animate().alpha(1f).setDuration(Ui.anim(160)).start();
        overlay.setOnClickListener(v -> closeMenu());

        LinearLayout panel = new LinearLayout(c);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(Ui.round(Ui.card, Ui.dp(c, 20)));
        panel.setElevation(Ui.dp(c, 14));
        panel.setPadding(0, Ui.dp(c, 8), 0, Ui.dp(c, 8));
        panel.setClickable(true);

        TextView title = Ui.text(c, "Скачанные файлы", 17, Ui.text, true);
        title.setPadding(Ui.dp(c, 18), Ui.dp(c, 6), Ui.dp(c, 18), Ui.dp(c, 6));
        panel.addView(title, new LinearLayout.LayoutParams(-1, Ui.dp(c, 44)));

        android.widget.ScrollView sv = new android.widget.ScrollView(c);
        dlList = new LinearLayout(c);
        dlList.setOrientation(LinearLayout.VERTICAL);
        sv.addView(dlList, new FrameLayout.LayoutParams(-1, -2));
        int maxH = Math.round(getResources().getDisplayMetrics().heightPixels * 0.55f);
        panel.addView(sv, new LinearLayout.LayoutParams(-1, -2));
        sv.post(() -> {
            if (sv.getHeight() > maxH) {
                sv.getLayoutParams().height = maxH;
                sv.requestLayout();
            }
        });

        View line = new View(c);
        line.setBackgroundColor(Ui.line);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Ui.dp(c, 1));
        lp.setMargins(Ui.dp(c, 16), Ui.dp(c, 6), Ui.dp(c, 16), Ui.dp(c, 2));
        panel.addView(line, lp);
        menuItem(panel, R.drawable.ic_forward, "Все загрузки телефона", null, Ui.text2, () -> Downloads.openSystem(this));

        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        pp.leftMargin = Ui.dp(c, 10);
        pp.rightMargin = Ui.dp(c, 10);
        pp.bottomMargin = bottom.getHeight() + Ui.dp(c, 8);
        overlay.addView(panel, pp);
        fillDownloads(Downloads.list(this));
        panel.setTranslationY(Ui.dp(c, 24));
        panel.setAlpha(0f);
        panel.animate().translationY(0).alpha(1f).setDuration(Ui.anim(200))
                .setInterpolator(new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)).start();
        watchDownloads();
    }

    /** Обновить строки на месте (не пересоздавая) — нажатия не теряются. */
    private void fillDownloads(List<Downloads.Item> list) {
        LinearLayout box = dlList;
        if (box == null) return;
        Context c = this;
        if (list.isEmpty()) {
            if (box.getChildCount() == 0) {
                TextView empty = Ui.text(c, "Пока ничего не скачано", 14, Ui.text2, false);
                empty.setGravity(Gravity.CENTER);
                box.addView(empty, new LinearLayout.LayoutParams(-1, Ui.dp(c, 72)));
            }
            return;
        }
        if (dlRows.isEmpty()) box.removeAllViews(); // убрать «Пока ничего не скачано»
        java.util.Set<Long> alive = new HashSet<>();
        for (int i = 0; i < list.size(); i++) {
            Downloads.Item it = list.get(i);
            alive.add(it.id);
            View[] r = dlRows.get(it.id);
            if (r == null) {
                r = downloadRow(it.id);
                dlRows.put(it.id, r);
                box.addView(r[0], Math.min(i, box.getChildCount()));
            }
            bindRow(r, it);
        }
        for (java.util.Iterator<java.util.Map.Entry<Long, View[]>> en = dlRows.entrySet().iterator(); en.hasNext(); ) {
            java.util.Map.Entry<Long, View[]> e = en.next();
            if (!alive.contains(e.getKey())) {
                box.removeView(e.getValue()[0]);
                en.remove();
            }
        }
    }

    /** [строка, значок, имя, статус, полоска, заполнение, кнопка ×] */
    private View[] downloadRow(long id) {
        Context c = this;
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(c, 18), Ui.dp(c, 8), Ui.dp(c, 8), Ui.dp(c, 8));
        row.setBackground(Ui.ripple(null, Ui.round(0xFFFFFFFF, 0)));

        ImageView ic = new ImageView(c);
        ic.setImageResource(R.drawable.ic_download);
        row.addView(ic, new LinearLayout.LayoutParams(Ui.dp(c, 22), Ui.dp(c, 22)));

        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(Ui.dp(c, 16), 0, Ui.dp(c, 8), 0);
        TextView name = Ui.text(c, "", 15, Ui.text, false);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        TextView status = Ui.text(c, "", 12, Ui.text2, false);
        status.setSingleLine(true);
        FrameLayout track = new FrameLayout(c);
        track.setBackground(Ui.round(Ui.line, Ui.dp(c, 2)));
        View fill = new View(c);
        fill.setBackground(Ui.round(dlGreen(), Ui.dp(c, 2)));
        track.addView(fill, new FrameLayout.LayoutParams(0, -1));
        texts.addView(name, new LinearLayout.LayoutParams(-1, -2));
        texts.addView(status, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, Ui.dp(c, 3));
        tp.topMargin = Ui.dp(c, 6);
        texts.addView(track, tp);
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));

        ImageButton x = Ui.iconButton(c, R.drawable.ic_close);
        x.setImageTintList(ColorStateList.valueOf(Ui.text2));
        row.addView(x, new LinearLayout.LayoutParams(Ui.dp(c, 44), Ui.dp(c, 44)));
        return new View[]{row, ic, name, status, track, fill, x};
    }

    private void bindRow(View[] r, Downloads.Item it) {
        ImageView ic = (ImageView) r[1];
        TextView name = (TextView) r[2];
        TextView status = (TextView) r[3];
        View track = r[4];
        View fill = r[5];
        ImageButton x = (ImageButton) r[6];
        if (!it.name.contentEquals(name.getText())) name.setText(it.name);
        String line = it.line();
        if (!line.contentEquals(status.getText())) status.setText(line);
        int tint = it.done() ? Ui.text : it.status == DownloadManager.STATUS_FAILED ? Ui.danger : dlGreen();
        ic.setImageTintList(ColorStateList.valueOf(tint));
        track.setVisibility(it.active() ? View.VISIBLE : View.GONE);
        if (it.active()) {
            float f = it.fraction();
            track.post(() -> {
                int w = track.getWidth();
                FrameLayout.LayoutParams fp = (FrameLayout.LayoutParams) fill.getLayoutParams();
                fp.width = f < 0 ? Math.round(w * 0.3f) : Math.round(w * f);
                fill.setLayoutParams(fp);
            });
        }
        x.setContentDescription(it.active() ? "Отменить загрузку" : "Убрать из списка");
        long id = it.id;
        boolean active = it.active();
        x.setOnClickListener(v -> {
            Haptics.click(v);
            if (active) {
                Downloads.cancel(this, id);
                Toast.makeText(this, "Загрузка отменена", Toast.LENGTH_SHORT).show();
            } else {
                Downloads.forget(id); // файл остаётся в «Загрузках»
            }
            watchDownloads();
        });
        boolean done = it.done();
        r[0].setOnClickListener(v -> {
            Haptics.click(v);
            if (done) {
                closeMenu();
                Downloads.open(this, it);
            }
        });
    }

    private void confirmWipe() {
        new AlertDialog.Builder(this)
                .setTitle("Удалить все данные?")
                .setMessage("Будут удалены входы на сайтах (cookie), кэш, данные сайтов, решения по трекерам "
                        + "и все вкладки.\n\nYouTube не тронется: вы останетесь в аккаунте.\nНастройки и ярлыки останутся."
                        + "\nРежимы «" + Prefs.incogName() + "» удаляются отдельно — в своём меню.")
                .setPositiveButton("Удалить", (d, w) -> wipe())
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void wipe() {
        ByssWebView any = null;
        for (Tab t : tabs) if (t.web != null && !t.ytProfile && t.space == null) any = t.web;
        if (any != null) {
            any.clearCache(true);
        } else {
            WebView tmp = new WebView(this);
            tmp.clearCache(true);
            tmp.destroy();
        }
        for (Tab t : new ArrayList<>(tabs)) {
            if (t.space != null) continue;
            if (t == mediaTab) stopMedia();
            destroyWeb(t);
            deleteThumb(t);
            tabs.remove(t);
        }
        cur = null;
        space = null;
        Profiles.wipeExceptYouTube();
        WebViewDatabase.getInstance(this).clearHttpAuthUsernamePassword();
        GeolocationPermissions.getInstance().clearAll();
        boolean ytAllowed = SitePrefs.isAllowed("youtube.com");
        boolean ytDecided = SitePrefs.hasDecision("youtube.com");
        SitePrefs.clear();
        if (ytDecided) SitePrefs.set("youtube.com", ytAllowed);
        promptDismissed = null;
        newHomeTab();
        Toast.makeText(this, "Данные удалены. YouTube вас помнит.", Toast.LENGTH_LONG).show();
    }

    // ======================================================= режимы BySS

    private void openSpaces() {
        if (!Profiles.supported()) {
            Toast.makeText(this, "Нужен свежий «Android System WebView» — обновите его в Play Маркете",
                    Toast.LENGTH_LONG).show();
            return;
        }
        Context c = this;
        LinearLayout list = new LinearLayout(c);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, Ui.dp(c, 4), 0, Ui.dp(c, 4));
        AlertDialog[] dlg = new AlertDialog[1];
        TextView about = Ui.text(c, "Отдельные приватные режимы: свои входы, cookie и данные сайтов. "
                + "Можно держать несколько сразу. Удаляются только вручную — и тогда от режима не остаётся ничего.",
                13, Ui.text2, false);
        about.setPadding(Ui.dp(c, 24), 0, Ui.dp(c, 24), Ui.dp(c, 8));
        list.addView(about);
        if (space != null) {
            list.addView(spaceRow(c, R.drawable.ic_back, "Обычный режим", countName(null), () -> {
                dlg[0].dismiss();
                switchSpace(null);
            }, null, null));
        }
        for (Spaces.Space s : Spaces.all()) {
            boolean now = s.id.equals(space);
            list.addView(spaceRow(c, R.drawable.ic_shield, s.name + (now ? " · сейчас" : ""), countName(s.id), () -> {
                dlg[0].dismiss();
                if (!now) switchSpace(s.id);
            }, () -> {
                dlg[0].dismiss();
                confirmDeleteSpace(s);
            }, () -> {
                dlg[0].dismiss();
                renameSpace(s);
            }));
        }
        list.addView(spaceRow(c, R.drawable.ic_plus, "Новый режим", null, () -> {
            dlg[0].dismiss();
            Spaces.Space s = Spaces.create();
            switchSpace(s.id);
        }, null, null));
        android.widget.ScrollView sv = new android.widget.ScrollView(c);
        sv.addView(list);
        dlg[0] = new AlertDialog.Builder(this).setTitle(Prefs.incogName()).setView(sv)
                .setNegativeButton("Закрыть", null).show();
    }

    private String countName(String sp) {
        int n = 0;
        for (Tab t : tabs) if (java.util.Objects.equals(t.space, sp)) n++;
        if (n == 0) return "нет вкладок";
        int m10 = n % 10, m100 = n % 100;
        String w = (m10 == 1 && m100 != 11) ? "вкладка" : (m10 >= 2 && m10 <= 4 && (m100 < 12 || m100 > 14)) ? "вкладки" : "вкладок";
        return n + " " + w;
    }

    private View spaceRow(Context c, int icon, String title, String sub, Runnable open, Runnable delete, Runnable rename) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(c, 24), Ui.dp(c, 8), Ui.dp(c, 12), Ui.dp(c, 8));
        row.setBackground(Ui.ripple(null, Ui.round(0xFFFFFFFF, Ui.dp(c, 14))));
        ImageView ic = new ImageView(c);
        ic.setImageResource(icon);
        ic.setImageTintList(ColorStateList.valueOf(Ui.text));
        row.addView(ic, new LinearLayout.LayoutParams(Ui.dp(c, 22), Ui.dp(c, 22)));
        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(Ui.dp(c, 16), 0, Ui.dp(c, 8), 0);
        texts.addView(Ui.text(c, title, 16, Ui.text, false));
        if (sub != null) texts.addView(Ui.text(c, sub, 12, Ui.text2, false));
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
        if (delete != null) {
            ImageButton del = Ui.iconButton(c, R.drawable.ic_trash);
            del.setImageTintList(ColorStateList.valueOf(Ui.danger));
            del.setOnClickListener(v -> {
                Haptics.click(v);
                delete.run();
            });
            row.addView(del, new LinearLayout.LayoutParams(Ui.dp(c, 44), Ui.dp(c, 44)));
        }
        row.setOnClickListener(v -> {
            Haptics.click(v);
            open.run();
        });
        if (rename != null) {
            row.setOnLongClickListener(v -> {
                Haptics.thud(v);
                rename.run();
                return true;
            });
        }
        row.setMinimumHeight(Ui.dp(c, 56));
        return row;
    }

    private void switchSpace(String id) {
        if (cur != null) captureThumb(cur);
        space = id;
        Tab target = null;
        for (Tab t : spaceTabs()) if (target == null || t.lastUsed > target.lastUsed) target = t;
        if (target == null) newHomeTab(); else showTab(target);
        Toast.makeText(this, id == null ? "Обычный режим"
                : "«" + Spaces.name(id) + "»: свои входы и cookie. Удаляется вручную", Toast.LENGTH_SHORT).show();
    }

    private void renameSpace(Spaces.Space s) {
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setText(s.name);
        e.setSelectAllOnFocus(true);
        FrameLayout box = new FrameLayout(this);
        int pd = Ui.dp(this, 22);
        box.setPadding(pd, Ui.dp(this, 8), pd, 0);
        box.addView(e);
        new AlertDialog.Builder(this).setTitle("Название режима").setView(box)
                .setPositiveButton("Сохранить", (d, w) -> {
                    String n = e.getText().toString().trim();
                    if (!n.isEmpty()) Spaces.rename(s.id, n);
                    if (s.id.equals(space)) {
                        home.setSpace(Spaces.name(space));
                        updateBars();
                    }
                })
                .setNegativeButton("Отмена", null).show();
    }

    private void confirmDeleteSpace(Spaces.Space s) {
        new AlertDialog.Builder(this)
                .setTitle("Удалить «" + s.name + "»?")
                .setMessage("Вы точно хотите удалить? Сотрётся всё, что было в этом режиме: вкладки, входы на сайтах, "
                        + "cookie, данные сайтов и кэш. Вернуть будет нельзя.")
                .setPositiveButton("Удалить", (d, w) -> deleteSpace(s.id))
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void deleteSpace(String id) {
        boolean wasCurrent = id.equals(space);
        for (Tab t : new ArrayList<>(tabs)) {
            if (!id.equals(t.space)) continue;
            if (t == mediaTab) stopMedia();
            translator.forget(t);
            destroyWeb(t);
            deleteThumb(t);
            tabs.remove(t);
        }
        Spaces.remove(id);
        if (wasCurrent) {
            space = null;
            cur = null;
            Tab target = null;
            for (Tab t : spaceTabs()) if (target == null || t.lastUsed > target.lastUsed) target = t;
            if (target == null) newHomeTab(); else showTab(target);
        }
        saveTabs();
        updateBars();
        ui.postDelayed(() -> Profiles.delete(Spaces.profile(id), ui, 0), 500);
        Toast.makeText(this, "Режим удалён — от него ничего не осталось", Toast.LENGTH_SHORT).show();
    }

    // ================================================== открыть в приложении

    /** Приложение (не браузер), которое умеет открыть этот адрес, — например Facebook. */
    private android.content.pm.ResolveInfo appFor(String url) {
        try {
            PackageManager pm = getPackageManager();
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE);
            List<android.content.pm.ResolveInfo> apps = pm.queryIntentActivities(i, PackageManager.MATCH_ALL);
            if (apps.isEmpty()) return null;
            Intent probe = new Intent(Intent.ACTION_VIEW, Uri.parse("https://byss-probe.invalid/x"))
                    .addCategory(Intent.CATEGORY_BROWSABLE);
            java.util.Set<String> browsers = new java.util.HashSet<>();
            for (android.content.pm.ResolveInfo r : pm.queryIntentActivities(probe, PackageManager.MATCH_ALL)) {
                browsers.add(r.activityInfo.packageName);
            }
            for (android.content.pm.ResolveInfo r : apps) {
                String pkg = r.activityInfo.packageName;
                if (pkg.equals(getPackageName()) || browsers.contains(pkg)) continue;
                return r;
            }
        } catch (Exception ignored) {
            // нет приложения
        }
        return null;
    }

    private void openInApp(String url, android.content.pm.ResolveInfo r) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
                    .setClassName(r.activityInfo.packageName, r.activityInfo.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "Не получилось открыть приложение", Toast.LENGTH_SHORT).show();
        }
    }

    // ============================================================ шторка сверху

    private int pageColor;
    private android.animation.ValueAnimator barAnim;
    private LinearLayout column;
    private View sbar;
    private int barColor;
    private int insetTop, insetBottom, insetLeft, insetRight;

    /**
     * Окно рисуется и под шторкой — так мы сами решаем, что там видно: цвет браузера,
     * цвет сайта или сама страница (как в Google Maps). Клавиатуру и нижнюю полоску учитываем сами.
     */
    @SuppressWarnings("deprecation")
    private void setupEdgeToEdge() {
        android.view.Window w = getWindow();
        w.setStatusBarColor(0x00000000);
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false);
        } else {
            w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
        root.setOnApplyWindowInsetsListener((v, ins) -> {
            int top, bottom, left, right;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets sb = ins.getInsets(WindowInsets.Type.statusBars());
                android.graphics.Insets bars = ins.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout());
                android.graphics.Insets ime = ins.getInsets(WindowInsets.Type.ime());
                top = sb.top;
                bottom = Math.max(bars.bottom, ime.bottom);
                left = bars.left;
                right = bars.right;
            } else {
                top = ins.getSystemWindowInsetTop();
                bottom = ins.getSystemWindowInsetBottom();
                left = ins.getSystemWindowInsetLeft();
                right = ins.getSystemWindowInsetRight();
            }
            if (top != insetTop || bottom != insetBottom || left != insetLeft || right != insetRight) {
                insetTop = top;
                insetBottom = bottom;
                insetLeft = left;
                insetRight = right;
                root.setPadding(left, 0, right, bottom);
                applyStatusBar();
            }
            return Build.VERSION.SDK_INT >= 30 ? WindowInsets.CONSUMED : ins.consumeSystemWindowInsets();
        });
        root.requestApplyInsets();
    }

    /** 0 — как фон браузера, 1 — мягкая: цвет сайта (на главной — без полосы), 2 — прозрачная. */
    void applyStatusBar() {
        if (column == null || sbar == null) return;
        int mode = Prefs.statusBar();
        boolean onHome = cur == null || cur.isHome();
        // под шторку уходит только главная (фон-картинка); сайт всегда ниже часов и значков —
        // иначе он залезает под время и батарею. Цвет полосы — точно как верх страницы.
        boolean under = mode != 0 && onHome;
        if (column.getPaddingTop() != (under ? 0 : insetTop)) column.setPadding(0, under ? 0 : insetTop, 0, 0);
        if (home != null) home.setTopInset(under ? insetTop : 0);
        if (stack != null && stack.getPaddingTop() != insetTop) stack.setPadding(0, insetTop, 0, 0);
        ViewGroup.LayoutParams lp = sbar.getLayoutParams();
        if (lp.height != insetTop) {
            lp.height = insetTop;
            sbar.setLayoutParams(lp);
        }
        int target = under ? 0 : (mode != 0 && !onHome && pageColor != 0 ? pageColor : Ui.bg);
        animateBar(target, mode == 2 ? 0 : (mode == 1 ? 200 : 300));
        lightIcons(under ? !Ui.dark : luminance(target) > 0.6);
    }

    private void setBar(int c) {
        barColor = c;
        sbar.setBackgroundColor(c);
    }

    private void animateBar(int target, long ms) {
        int from = barColor;
        if (barAnim != null) barAnim.cancel();
        if (from == target || ms == 0 || Ui.anim(ms) == 0) {
            setBar(target);
        } else {
            barAnim = android.animation.ValueAnimator.ofArgb(from, target);
            barAnim.setDuration(Ui.anim(ms));
            barAnim.addUpdateListener(a -> setBar((Integer) a.getAnimatedValue()));
            barAnim.start();
        }
    }

    @SuppressWarnings("deprecation")
    private void lightIcons(boolean lightBar) {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.setSystemBarsAppearance(lightBar ? WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS : 0,
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
            }
        } else {
            View d = getWindow().getDecorView();
            int f = d.getSystemUiVisibility();
            d.setSystemUiVisibility(lightBar ? f | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR : f & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
    }

    private static double luminance(int c) {
        return (0.299 * ((c >> 16) & 0xFF) + 0.587 * ((c >> 8) & 0xFF) + 0.114 * (c & 0xFF)) / 255.0;
    }

    private final Handler barHandler = new Handler(Looper.getMainLooper());
    private final java.util.WeakHashMap<Tab, Integer> tabColor = new java.util.WeakHashMap<>();
    private boolean samplePending;
    private final Runnable sampleRun = () -> {
        samplePending = false;
        sampleBar(cur);
    };

    private void scheduleSample(long delay) {
        if (samplePending) return;
        samplePending = true;
        barHandler.postDelayed(sampleRun, delay);
    }

    /** Во время прокрутки цвет не читаем (чтобы не лагало) — только когда палец/прокрутка остановились. */
    private void sampleAfterScroll(long delay) {
        barHandler.removeCallbacks(sampleRun);
        samplePending = true;
        barHandler.postDelayed(sampleRun, delay);
    }

    /**
     * Цвет верха страницы — прямо с экрана (полоска пикселей под шторкой). Так полоса совпадает
     * с сайтом один в один, даже когда сайт затемнён тёмной темой, и без ожидания загрузки.
     */
    private void sampleBar(Tab t) {
        if (t == null || t != cur || t.isHome() || t.web == null || Prefs.statusBar() == 0) return;
        if (stack != null && stack.getVisibility() == View.VISIBLE) return;
        if (overlay != null && overlay.getVisibility() == View.VISIBLE) return;
        WebView web = t.web;
        if (web.getWidth() < 8 || web.getHeight() < 8 || !web.isAttachedToWindow()) return;
        int[] loc = new int[2];
        web.getLocationInWindow(loc);
        android.graphics.Rect src = new android.graphics.Rect(loc[0], loc[1] + 1, loc[0] + web.getWidth(), loc[1] + 4);
        Bitmap bm = Bitmap.createBitmap(48, 3, Bitmap.Config.ARGB_8888);
        try {
            android.view.PixelCopy.request(getWindow(), src, bm, res -> {
                if (t != cur) return;
                if (res != android.view.PixelCopy.SUCCESS) {
                    readPageColor(t);
                    return;
                }
                int col = dominant(bm);
                if (col == 0) return;
                tabColor.put(t, col);
                if (col != pageColor) {
                    pageColor = col;
                    applyStatusBar();
                }
            }, barHandler);
        } catch (Exception e) {
            readPageColor(t);
        }
    }

    /** Самый частый цвет полоски (мелкие детали вроде логотипа не в счёт). */
    private static int dominant(Bitmap bm) {
        int n = bm.getWidth() * bm.getHeight();
        int[] px = new int[n];
        bm.getPixels(px, 0, bm.getWidth(), 0, 0, bm.getWidth(), bm.getHeight());
        java.util.HashMap<Integer, int[]> buckets = new java.util.HashMap<>();
        int[] best = null;
        for (int c : px) {
            int key = ((c >> 19) & 0x1F) << 10 | ((c >> 11) & 0x1F) << 5 | ((c >> 3) & 0x1F);
            int[] acc = buckets.get(key);
            if (acc == null) {
                acc = new int[4];
                buckets.put(key, acc);
            }
            acc[0]++;
            acc[1] += (c >> 16) & 0xFF;
            acc[2] += (c >> 8) & 0xFF;
            acc[3] += c & 0xFF;
            if (best == null || acc[0] > best[0]) best = acc;
        }
        if (best == null || best[0] == 0) return 0;
        return 0xFF000000 | ((best[1] / best[0]) << 16) | ((best[2] / best[0]) << 8) | (best[3] / best[0]);
    }

    /** Цвет сайта: theme-color или фон страницы. В тёмной теме светлые сайты затемняются — берём чёрный. */
    private void readPageColor(Tab t) {
        if (t.web == null) return;
        t.web.evaluateJavascript("(function(){var m=document.querySelector('meta[name=\"theme-color\"]');"
                + "var c=m&&m.content;if(!c){c=getComputedStyle(document.body||document.documentElement).backgroundColor}"
                + "return c||''})()", v -> {
            int col = parseCss(v == null ? "" : v.replace("\"", ""));
            if (col == 0 || (Ui.dark && luminance(col) > 0.45)) col = Ui.bg;
            if (t != cur) return;
            pageColor = col;
            if (Prefs.statusBar() != 0) applyStatusBar();
        });
    }

    private static int parseCss(String s) {
        try {
            s = s.trim().toLowerCase(Locale.ROOT);
            if (s.startsWith("#")) {
                if (s.length() == 4) {
                    s = "#" + s.charAt(1) + s.charAt(1) + s.charAt(2) + s.charAt(2) + s.charAt(3) + s.charAt(3);
                }
                return 0xFF000000 | (int) Long.parseLong(s.substring(1, 7), 16);
            }
            if (s.startsWith("rgb")) {
                String[] p = s.substring(s.indexOf('(') + 1, s.indexOf(')')).split(",");
                if (p.length >= 4 && Float.parseFloat(p[3].trim()) < 0.5f) return 0;
                return 0xFF000000 | (Integer.parseInt(p[0].trim()) << 16) | (Integer.parseInt(p[1].trim()) << 8)
                        | Integer.parseInt(p[2].trim());
            }
        } catch (Exception ignored) {
            // непонятный цвет
        }
        return 0;
    }

    // ============================================================ плеер YouTube

    private void onMedia(Tab t, boolean playing, String title, String artist) {
        if (!Profiles.isYouTube(t.pageHost) || t.web == null) return; // в шторке — только YouTube
        t.mediaPlaying = playing;
        if (playing) {
            if (mediaTab != null && mediaTab != t && mediaTab.web != null) {
                mediaTab.web.evaluateJavascript("window.__byssMedia&&__byssMedia('pause')", null);
                mediaTab.web.setKeepAlive(false);
                mediaTab.mediaPlaying = false;
            }
            mediaTab = t;
            if (Prefs.bgPlay()) t.web.setKeepAlive(true);
            askNotificationsOnce();
        }
        if (t != mediaTab) return;
        MediaService.update(this, playing, title, artist, videoId(t.url));
    }

    private static String videoId(String url) {
        if (url == null) return null;
        try {
            Uri u = Uri.parse(url);
            String v = u.getQueryParameter("v");
            if (v != null) return v;
            List<String> seg = u.getPathSegments();
            if (seg.size() >= 2 && ("shorts".equals(seg.get(0)) || "live".equals(seg.get(0)))) return seg.get(1);
            if ("youtu.be".equals(u.getHost()) && !seg.isEmpty()) return seg.get(0);
        } catch (Exception ignored) {
            // нет id
        }
        return null;
    }

    private void askNotificationsOnce() {
        if (Build.VERSION.SDK_INT < 33) return;
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return;
        if (Prefs.sp().getBoolean("asked_notify", false)) return;
        Prefs.put("asked_notify", true);
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY);
    }

    private void stopMedia() {
        MediaService.stop(this);
        if (mediaTab != null) {
            if (mediaTab.web != null) mediaTab.web.setKeepAlive(false);
            mediaTab.mediaPlaying = false;
            mediaTab = null;
        }
    }

    /** Команды из шторки: play / pause / next / prev. */
    static void mediaCommand(String cmd) {
        BrowserActivity a = instance.get();
        if (a == null || cmd == null) return;
        a.ui.post(() -> {
            Tab t = a.mediaTab;
            if (t == null || t.web == null) return;
            t.web.evaluateJavascript("window.__byssMedia&&__byssMedia('" + cmd.replaceAll("[^a-z]", "") + "')", null);
        });
    }

    static void onMediaServiceStopped() {
        BrowserActivity a = instance.get();
        if (a == null) return;
        a.ui.post(() -> {
            if (a.mediaTab != null && !a.mediaTab.mediaPlaying) {
                if (a.mediaTab.web != null) a.mediaTab.web.setKeepAlive(false);
                a.mediaTab = null;
            }
        });
    }
}
