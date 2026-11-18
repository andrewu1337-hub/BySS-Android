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
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.KeyEvent;
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

import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
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
    private SwipeRefreshLayout swipe;
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
    private String pageJs, ytJs;
    private View fsView;
    private WebChromeClient.CustomViewCallback fsCallback;
    private ValueCallback<Uri[]> fileCallback;
    private String promptBase;
    private String promptDismissed;
    private boolean menuOpen;
    private boolean skipHomeOnce;
    private BroadcastReceiver downloadsDone;

    private final Runnable refreshShield = this::updateShield;

    // =================================================================== жизнь

    @Override
    protected void onCreate(Bundle state) {
        Prefs.init(this);
        SitePrefs.init(this);
        boolean dark = Prefs.dark();
        setTheme(dark ? R.style.Theme_Lev_Dark : R.style.Theme_Lev_Light);
        Ui.apply(dark);
        super.onCreate(state);
        instance = new WeakReference<>(this);

        AdBlocker.start(this);
        pageJs = asset("js/page.js");
        ytJs = asset("js/youtube.js");
        translator = new PageTranslator(this, asset("js/translate.js"));

        buildUi();
        restoreTabs();
        boolean opened = handleIntent(getIntent());
        if (!opened) {
            if (cur == null) newHomeTab(); else showTab(cur);
        }

        downloadsDone = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                Toast.makeText(c, "Файл скачан — Меню → Скачанные файлы", Toast.LENGTH_SHORT).show();
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
        try {
            if (cur != null && cur.web != null) cur.web.resumeTimers();
        } catch (Exception ignored) {
            // нет WebView
        }
        for (Tab t : tabs) {
            if (t.web != null) Profiles.cookies(t.ytProfile).setAcceptThirdPartyCookies(t.web, Prefs.thirdPartyCookies());
        }
        home.refresh();
        updateBars();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (shot != null) shot.cancel();
        saveTabs();
        CookieManager.getInstance().flush();
        if (Profiles.supported()) Profiles.cookies(true).flush();
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
        if (!keepMedia && cur != null && cur.web != null) cur.web.pauseTimers();
    }

    @Override
    protected void onDestroy() {
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

        LinearLayout column = new LinearLayout(c);
        column.setOrientation(LinearLayout.VERTICAL);
        root.addView(column, new FrameLayout.LayoutParams(-1, -1));

        swipe = new SwipeRefreshLayout(c);
        swipe.setColorSchemeColors(Ui.text);
        swipe.setProgressBackgroundColorSchemeColor(Ui.pill);
        holder = new FrameLayout(c);
        holder.setBackgroundColor(Ui.bg);
        swipe.addView(holder, new ViewGroup.LayoutParams(-1, -1));
        swipe.setOnChildScrollUpCallback((parent, child) ->
                cur == null || cur.isHome() || cur.web == null || cur.web.canScrollVertically(-1)
                        || cur.web.getScrollY() > 0);
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
            @Override public void onPickEngine(int slot) { pickEngine(slot == 1 ? "home1" : "home2"); }
        });

        bottom = new LinearLayout(c);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setBackgroundColor(Ui.bar);
        column.addView(bottom, new LinearLayout.LayoutParams(-1, -2));

        prompt = new LinearLayout(c);
        prompt.setOrientation(LinearLayout.VERTICAL);
        prompt.setVisibility(View.GONE);
        bottom.addView(prompt, new LinearLayout.LayoutParams(-1, -2));

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
        plus.setOnClickListener(v -> {
            Haptics.click(v);
            newHomeTab();
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
                newHomeTab();
            }
            @Override public void onCloseAll() {
                new AlertDialog.Builder(BrowserActivity.this)
                        .setTitle("Закрыть все вкладки?")
                        .setPositiveButton("Закрыть", (d, w) -> {
                            for (Tab t : new ArrayList<>(tabs)) closeTab(t, false);
                            stack.hide(null);
                            newHomeTab();
                        })
                        .setNegativeButton("Отмена", null).show();
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
        if (imm != null) imm.hideSoftInputFromWindow(address.getWindowToken(), 0);
        address.clearFocus();
        root.requestFocus();
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
        int n = tabs.size();
        tabsBtn.setText(n > 99 ? ":)" : String.valueOf(n));
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
            boolean allowed = !Prefs.adblock() || SitePrefs.isAllowed(cur.pageHost);
            shield.setImageResource(allowed ? R.drawable.ic_shield_off : R.drawable.ic_shield);
            shield.setAlpha(allowed ? 0.55f : 1f);
        }
        if (prompt.getVisibility() == View.VISIBLE && promptBase != null) updatePromptCount();
    }

    private void setProgress(int p) {
        if (p >= 100) {
            progress.animate().scaleX(1f).setDuration(120).withEndAction(() ->
                    progress.animate().alpha(0f).setDuration(220).start()).start();
        } else {
            progress.setAlpha(1f);
            progress.animate().scaleX(Math.max(0.06f, p / 100f)).setDuration(160).start();
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
        for (Tab t : tabs) if (t.id == curId[0]) cur = t;
        if (cur == null && !tabs.isEmpty()) cur = tabs.get(tabs.size() - 1);
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
        tabs.add(t);
        showTab(t);
    }

    /** Через N минут без браузера — сразу начальная страница. */
    private void openHome() {
        if (cur != null && cur.isHome()) return;
        for (Tab t : tabs) {
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
            captureThumb(cur);
            if (cur.web != null && cur != mediaTab) cur.web.onPause();
            if (address.hasFocus()) hideKeyboard();
        }
        cur = t;
        t.lastUsed = System.currentTimeMillis();
        holder.removeAllViews();
        if (t.isHome()) {
            detach(home);
            home.refresh();
            holder.addView(home, new FrameLayout.LayoutParams(-1, -1));
            home.setAlpha(0f);
            home.animate().alpha(1f).setDuration(180).start();
        } else {
            if (t.web == null) {
                createWeb(t, wantsYt(t.url));
                if (t.state == null || t.web.restoreState(t.state) == null) t.web.loadUrl(t.url);
                t.state = null;
            }
            detach(t.web);
            holder.addView(t.web, new FrameLayout.LayoutParams(-1, -1));
            t.web.onResume();
        }
        hidePrompt();
        updateBars();
        saveTabs();
    }

    private static void detach(View v) {
        if (v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
    }

    private boolean wantsYt(String url) {
        return Profiles.supported() && Profiles.isYouTube(host(url));
    }

    /** Открыть адрес в текущей вкладке (с начальной страницы — в ней же). */
    void go(String url) {
        if (cur == null) newHomeTab();
        loadUrl(cur, url);
    }

    void loadUrl(Tab t, String url) {
        if (url == null) return;
        String h = host(url);
        boolean yt = wantsYt(url);
        if (t.web != null && Profiles.supported() && t.ytProfile != yt
                && !(t.ytProfile && Profiles.belongsToYouTube(h))) {
            destroyWeb(t);
        }
        t.url = url;
        t.translated = false;
        t.state = null;
        if (t.web == null) {
            createWeb(t, yt);
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
        int idx = tabs.indexOf(t);
        if (idx < 0) return;
        if (t == mediaTab) stopMedia();
        translator.forget(t);
        destroyWeb(t);
        deleteThumb(t);
        tabs.remove(t);
        for (Tab o : tabs) if (o.parent == t) o.parent = null;
        if (t == cur) {
            cur = null;
            if (tabs.isEmpty()) {
                newHomeTab();
            } else if (showNext) {
                showTab(tabs.get(Math.max(0, Math.min(idx, tabs.size()) - 1)));
            } else {
                cur = tabs.get(Math.max(0, Math.min(idx, tabs.size()) - 1));
                showTab(cur);
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
        for (Tab t : tabs) {
            if (t.thumb == null) {
                File f = thumbFile(t);
                if (f.exists()) t.thumb = BitmapFactory.decodeFile(f.getAbsolutePath());
            }
        }
        stack.show(tabs, cur);
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
        t.thumb = null;
    }

    private void captureThumb(Tab t) {
        if (t == null || t.isHome() || t.web == null || t.web.getWidth() == 0 || t.web.getHeight() == 0) return;
        try {
            float s = 0.38f;
            Bitmap b = Bitmap.createBitmap(Math.round(t.web.getWidth() * s), Math.round(t.web.getHeight() * s),
                    Bitmap.Config.RGB_565);
            Canvas c = new Canvas(b);
            c.scale(s, s);
            c.translate(-t.web.getScrollX(), -t.web.getScrollY());
            t.web.draw(c);
            t.thumb = b;
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

    private void destroyWeb(Tab t) {
        if (t.web == null) return;
        LevWebView w = t.web;
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
        tabs.add(t);
        cur = null;
        showTab(t);
        loadUrl(t, url);
        return true;
    }

    // ================================================================ WebView

    private void createWeb(Tab t, boolean yt) {
        LevWebView w = new LevWebView(this);
        Profiles.attach(w, yt);
        t.ytProfile = yt && Profiles.supported();
        w.setBackgroundColor(Ui.bg);
        w.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);

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
        CookieManager cm = Profiles.cookies(t.ytProfile);
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(w, Prefs.thirdPartyCookies());

        w.addJavascriptInterface(new Native(t), "LevNative");
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
                if (req.isForMainFrame() && Profiles.supported()) {
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
            if (ph != null && SitePrefs.isAllowed(ph)) return null;
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
            if (t == cur) {
                updateBars();
                maybePrompt(t);
                maybeOfferTranslate(t);
            }
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
                createWeb(t, wantsYt(url));
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
            if (t == cur) setProgress(p);
        }

        @Override
        public void onReceivedTitle(WebView view, String title) {
            t.title = title;
        }

        @Override
        public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
            if (!isUserGesture) return false; // всплывающие окна без нажатия — блокируем
            Tab nt = new Tab();
            nt.url = "about:blank";
            nt.parent = t;
            tabs.add(tabs.indexOf(t) + 1, nt);
            createWeb(nt, false);
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
            if (!Prefs.adblock() || host == null || SitePrefs.isAllowed(host)) return "";
            return AdBlocker.cssFor(host);
        }

        @JavascriptInterface
        public String cfg() {
            try {
                JSONObject o = new JSONObject();
                o.put("bg", Prefs.bgPlay());
                o.put("ads", Prefs.ytAds() && Prefs.adblock() && !SitePrefs.isAllowed("youtube.com"));
                return o.toString();
            } catch (Exception e) {
                return "{}";
            }
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
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
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
            getWindow().getDecorView().setSystemUiVisibility(0);
        }
        WebChromeClient.CustomViewCallback cb = fsCallback;
        fsCallback = null;
        if (notifyPage && cb != null) cb.onCustomViewHidden();
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
            String cookie = Profiles.cookies(cur != null && cur.ytProfile).getCookie(url);
            if (cookie != null) r.addRequestHeader("Cookie", cookie);
            if (ua != null) r.addRequestHeader("User-Agent", ua);
            r.setTitle(name);
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE);
            r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
            DownloadManager dm = getSystemService(DownloadManager.class);
            dm.enqueue(r);
            Toast.makeText(this, "Скачивается: " + name, Toast.LENGTH_SHORT).show();
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
                Tab nt = new Tab();
                nt.parent = t;
                tabs.add(tabs.indexOf(t) + 1, nt);
                showTab(nt);
                loadUrl(nt, link);
            });
            labels.add("Открыть в фоне");
            acts.add(() -> {
                Tab nt = new Tab();
                nt.url = link;
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
        if (SitePrefs.hasDecision(t.pageHost) || base.equals(promptDismissed)) return;
        if (base.equals(promptBase) && prompt.getVisibility() == View.VISIBLE) {
            updatePromptCount();
            return;
        }
        showPrompt(t, base);
    }

    private TextView promptText;

    private void showPrompt(Tab t, String base) {
        Context c = this;
        promptBase = base;
        prompt.removeAllViews();
        prompt.setPadding(Ui.dp(c, 16), Ui.dp(c, 12), Ui.dp(c, 8), Ui.dp(c, 8));
        prompt.setBackgroundColor(Ui.bar);

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
            SitePrefs.set(t.pageHost, true);
            hidePrompt();
            if (t.web != null) t.web.reload();
            updateShield();
        });
        TextView block = pillButton(c, "Блокировать", true);
        block.setOnClickListener(v -> {
            Haptics.click(v);
            SitePrefs.set(t.pageHost, false);
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
        prompt.animate().alpha(1f).translationY(0).setDuration(220).start();
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
        prompt.animate().alpha(0f).setDuration(150).withEndAction(() -> {
            prompt.setVisibility(View.GONE);
            Runnable r = afterPrompt;
            afterPrompt = null;
            if (r != null) r.run();
        }).start();
    }

    // ===================================================== перевод: предложение

    /** Сайты, где в этот запуск нажали «Нет» / «Перевести» (тогда дальше переводим сами). */
    private final java.util.Set<String> trDeclined = new java.util.HashSet<>();
    private final java.util.Set<String> trAccepted = new java.util.HashSet<>();

    private static boolean isTranslatorHost(String h) {
        return h.endsWith("translate.goog") || h.contains("translate.yandex") || h.startsWith("translate.google.");
    }

    /** Страница не на русском и не на английском — предложить перевод. */
    private void maybeOfferTranslate(Tab t) {
        if (!Prefs.offerTranslate() || t.isHome() || t.translated || t.web == null || t.pageHost == null) return;
        String h = t.pageHost.toLowerCase(Locale.ROOT);
        if (isTranslatorHost(h) || Profiles.isYouTube(h)) return;
        String base = AdBlocker.baseDomain(h);
        if (trDeclined.contains(base)) return;
        boolean auto = trAccepted.contains(base) && "device".equals(Prefs.translateMode());
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
        if (t != cur || t.translated || !page.equals(t.url) || trDeclined.contains(base)) return;
        Context c = this;
        promptBase = null;
        prompt.removeAllViews();
        prompt.setPadding(Ui.dp(c, 16), Ui.dp(c, 12), Ui.dp(c, 8), Ui.dp(c, 8));
        prompt.setBackgroundColor(Ui.bar);

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
            trDeclined.add(base);
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
            trDeclined.add(base);
            hidePrompt();
        });
        TextView yes = pillButton(c, "Перевести", true);
        yes.setOnClickListener(v -> {
            Haptics.click(v);
            trAccepted.add(base);
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
        prompt.animate().alpha(1f).translationY(0).setDuration(220).start();
    }

    private void onSiteButton() {
        if (cur == null || cur.isHome() || address.hasFocus()) {
            pickEngine("engine");
            return;
        }
        String h = cur.pageHost;
        if (h == null) return;
        boolean allowed = SitePrefs.isAllowed(h);
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
                SitePrefs.set(h, !allowed);
                if (cur.web != null) cur.web.reload();
                updateShield();
            });
        }
        b.show();
    }

    void pickEngine(String key) {
        List<SearchEngines.Engine> all = SearchEngines.all();
        String curId = Prefs.sp().getString(key, key.equals("home2") ? "yandex" : "google");
        String[] names = new String[all.size()];
        int checked = 0;
        for (int i = 0; i < all.size(); i++) {
            names[i] = all.get(i).name;
            if (all.get(i).id.equals(curId)) checked = i;
        }
        String title = key.equals("engine") ? "Поиск в адресной строке"
                : key.equals("home1") ? "Первый поиск на главной" : "Второй поиск на главной";
        new AlertDialog.Builder(this).setTitle(title)
                .setSingleChoiceItems(names, checked, (d, w) -> {
                    Prefs.put(key, all.get(w).id);
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

    // ==================================================================== меню

    private void openMenu() {
        if (address.hasFocus()) hideKeyboard();
        Context c = this;
        menuOpen = true;
        overlay.removeAllViews();
        overlay.setBackgroundColor(Ui.scrim);
        overlay.setVisibility(View.VISIBLE);
        overlay.setAlpha(0f);
        overlay.animate().alpha(1f).setDuration(160).start();
        overlay.setOnClickListener(v -> closeMenu());

        LinearLayout panel = new LinearLayout(c);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(Ui.round(Ui.card, Ui.dp(c, 20)));
        panel.setElevation(Ui.dp(c, 14));
        panel.setPadding(0, Ui.dp(c, 8), 0, Ui.dp(c, 8));
        panel.setClickable(true);

        boolean translated = cur != null && cur.translated;
        boolean canTranslate = cur != null && !cur.isHome();
        if (canTranslate && cur.pageHost != null) siteSwitch(panel, cur);
        menuItem(panel, R.drawable.ic_incognito, "Инкогнито", "скоро", Ui.text, () ->
                new AlertDialog.Builder(this).setTitle("Режим инкогнито")
                        .setMessage("Сделаю, как только опишете, как он должен работать.")
                        .setPositiveButton("Хорошо", null).show());
        menuItem(panel, R.drawable.ic_translate, translated ? "Показать оригинал" : "Перевести страницу",
                canTranslate ? null : "откройте сайт", canTranslate ? Ui.text : Ui.text2,
                () -> toggleTranslate(Prefs.translateMode()));
        menuItem(panel, R.drawable.ic_screenshot, "Длинный скриншот",
                canTranslate ? null : "откройте сайт", canTranslate ? Ui.text : Ui.text2, this::longScreenshot);
        menuItem(panel, R.drawable.ic_download, "Скачанные файлы", null, Ui.text, () -> {
            try {
                startActivity(new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception e) {
                Toast.makeText(this, "Откройте приложение «Файлы» → Загрузки", Toast.LENGTH_SHORT).show();
            }
        });
        menuItem(panel, R.drawable.ic_theme, Ui.dark ? "Светлая тема" : "Тёмная тема", null, Ui.text, () -> {
            Prefs.put("dark", !Ui.dark);
            saveTabs();
            recreate();
        });
        menuItem(panel, R.drawable.ic_trash, "Удалить все данные", null, Ui.danger, this::confirmWipe);
        View line = new View(c);
        line.setBackgroundColor(Ui.line);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Ui.dp(c, 1));
        lp.setMargins(Ui.dp(c, 16), Ui.dp(c, 6), Ui.dp(c, 16), Ui.dp(c, 6));
        panel.addView(line, lp);
        menuItem(panel, R.drawable.ic_settings, "Доп. настройки", null, Ui.text,
                () -> startActivity(new Intent(this, SettingsActivity.class)));

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
            panel.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(200)
                    .setInterpolator(new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)).start();
        });
    }

    // ====================================================== длинный скриншот

    private LongShot shot;

    private void longScreenshot() {
        Tab t = cur;
        if (shot != null || t == null || t.isHome() || t.web == null) return;
        Context c = this;
        ViewGroup decor = (ViewGroup) getWindow().getDecorView();
        FrameLayout cover = new FrameLayout(c);
        cover.setClickable(true); // пока снимаем — страницу не трогаем
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(Ui.dp(c, 18), Ui.dp(c, 10), Ui.dp(c, 10), Ui.dp(c, 10));
        box.setBackground(Ui.round(Ui.card, Ui.dp(c, 18)));
        box.setElevation(Ui.dp(c, 12));
        TextView msg = Ui.text(c, "Снимаю всю страницу…", 14, Ui.text, false);
        box.addView(msg, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView cancel = pillButton(c, "Отмена", false);
        box.addView(cancel);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        lp.setMargins(Ui.dp(c, 12), 0, Ui.dp(c, 12), bottom.getHeight() + Ui.dp(c, 12));
        cover.addView(box, lp);
        decor.addView(cover, new FrameLayout.LayoutParams(-1, -1));

        shot = new LongShot(this, t.web, new LongShot.Listener() {
            @Override
            public void progress(int frame, int frames) {
                msg.setText(frames > 1 ? "Снимаю всю страницу… " + frame + " из " + frames : "Снимаю страницу…");
            }

            @Override
            public void done(Uri uri, String error, boolean cropped) {
                shot = null;
                decor.removeView(cover);
                if (uri != null) {
                    Haptics.click(holder);
                    shotSaved(uri, cropped);
                } else if (error != null) {
                    Toast.makeText(BrowserActivity.this, error, Toast.LENGTH_LONG).show();
                }
            }
        });
        cancel.setOnClickListener(v -> {
            msg.setText("Останавливаю…");
            shot.cancel();
        });
        shot.start();
    }

    private void shotSaved(Uri uri, boolean cropped) {
        String m = "Галерея → Pictures/Лев";
        if (cropped) m += "\n\nСтраница бесконечная — снята первая часть.";
        new AlertDialog.Builder(this)
                .setTitle("Скриншот сохранён")
                .setMessage(m)
                .setPositiveButton("Поделиться", (d, w) -> {
                    Intent send = new Intent(Intent.ACTION_SEND).setType("image/jpeg")
                            .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(send, "Поделиться скриншотом"));
                })
                .setNeutralButton("Открыть", (d, w) -> {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/jpeg")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
                    } catch (Exception e) {
                        Toast.makeText(this, "Откройте Галерею → Pictures/Лев", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Готово", null)
                .show();
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
        boolean on = global && !SitePrefs.isAllowed(host);
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
            SitePrefs.set(host, !checked);
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
        overlay.animate().alpha(0f).setDuration(140).withEndAction(() -> {
            overlay.setVisibility(View.GONE);
            overlay.removeAllViews();
        }).start();
    }

    private void confirmWipe() {
        new AlertDialog.Builder(this)
                .setTitle("Удалить все данные?")
                .setMessage("Будут удалены входы на сайтах (cookie), кэш, данные сайтов, решения по трекерам "
                        + "и все вкладки.\n\nYouTube не тронется: вы останетесь в аккаунте.\nНастройки и ярлыки останутся.")
                .setPositiveButton("Удалить", (d, w) -> wipe())
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void wipe() {
        LevWebView any = null;
        for (Tab t : tabs) if (t.web != null && !t.ytProfile) any = t.web;
        if (any != null) {
            any.clearCache(true);
        } else {
            WebView tmp = new WebView(this);
            tmp.clearCache(true);
            tmp.destroy();
        }
        for (Tab t : new ArrayList<>(tabs)) {
            if (t == mediaTab) stopMedia();
            destroyWeb(t);
            deleteThumb(t);
        }
        tabs.clear();
        cur = null;
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

    // ============================================================ плеер YouTube

    private void onMedia(Tab t, boolean playing, String title, String artist) {
        if (!Profiles.isYouTube(t.pageHost) || t.web == null) return; // в шторке — только YouTube
        t.mediaPlaying = playing;
        if (playing) {
            if (mediaTab != null && mediaTab != t && mediaTab.web != null) {
                mediaTab.web.evaluateJavascript("window.__levMedia&&__levMedia('pause')", null);
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
            t.web.evaluateJavascript("window.__levMedia&&__levMedia('" + cmd.replaceAll("[^a-z]", "") + "')", null);
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
