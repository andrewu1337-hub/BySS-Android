package app.lev.browser;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.OverScroller;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Вкладки: стопкой, как в старом Android (карточки одна на другой снизу вверх), или сеткой 2×N.
 * Листание с мягким «тиком» вибрации, свайп в сторону — закрыть, нажатие — открыть.
 * Картинки вкладок грузятся только для видимых карточек — 50 вкладок не тормозят.
 */
@SuppressLint("ViewConstructor")
final class TabStackView extends FrameLayout {

    interface Listener {
        void onSelect(Tab t);
        void onClose(Tab t);
        void onNewTab();
        void onCloseAll();
        /** Картинка вкладки: сразу из памяти или позже через done (может не прийти). */
        void loadThumb(Tab t, java.util.function.Consumer<Bitmap> done);
    }

    private final Listener listener;
    private final List<Tab> tabs = new ArrayList<>();
    private final List<View> cards = new ArrayList<>();
    private final FrameLayout deck;
    private final TextView countText;
    private final OverScroller scroller;
    private final int slop;
    private final PathInterpolator ease = new PathInterpolator(0.2f, 0f, 0f, 1f);

    private Tab current;
    private float scroll;
    private float maxScroll;
    private int cardH, gap, topPad, bottomPad;
    private VelocityTracker vt;
    private float downX, downY, lastY;
    private int mode; // 0 — ещё не ясно, 1 — листаем, 2 — смахиваем карточку
    private int touched = -1;
    private int lastTick = Integer.MIN_VALUE;
    private boolean busy;
    private boolean grid;
    private int cardW, side, cols = 1;

    TabStackView(Context c, Listener l) {
        super(c);
        listener = l;
        scroller = new OverScroller(c, new DecelerateInterpolator(1.6f));
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        setClickable(true);

        deck = new FrameLayout(c);
        deck.setClipChildren(false);
        addView(deck, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        LinearLayout top = new LinearLayout(c);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        int p = Ui.dp(c, 18);
        top.setPadding(p, Ui.dp(c, 10), Ui.dp(c, 8), Ui.dp(c, 6));
        countText = Ui.text(c, "", 15, Ui.text2, true);
        top.addView(countText, new LinearLayout.LayoutParams(0, Ui.dp(c, 40), 1f));
        TextView closeAll = Ui.text(c, "Закрыть все", 15, Ui.text2, false);
        closeAll.setPadding(Ui.dp(c, 12), 0, Ui.dp(c, 12), 0);
        closeAll.setBackground(Ui.borderlessRipple());
        closeAll.setOnClickListener(v -> {
            Haptics.click(v);
            listener.onCloseAll();
        });
        top.addView(closeAll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, Ui.dp(c, 40)));
        LayoutParams tp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP);
        addView(top, tp);

        // «+» по центру снизу, без рамки и тени
        ImageButton plus = Ui.iconButton(c, R.drawable.ic_plus);
        Ui.heartButton(plus);
        plus.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int ip = Ui.dp(c, 14);
        plus.setPadding(ip, ip, ip, ip);
        plus.setOnClickListener(v -> {
            Haptics.click(v);
            Ui.heart(v);
            listener.onNewTab();
        });
        LayoutParams pp = new LayoutParams(Ui.dp(c, 56), Ui.dp(c, 56), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        pp.bottomMargin = Ui.dp(c, 18);
        addView(plus, pp);
    }

    void show(List<Tab> list, Tab cur) {
        Context c = getContext();
        setBackgroundColor(Ui.dark ? 0xFF000000 : 0xFFEDEDF0);
        tabs.clear();
        tabs.addAll(list);
        current = cur;
        grid = Prefs.tabStyle() == 1;
        deck.removeAllViews();
        cards.clear();
        for (Tab t : tabs) {
            View card = card(c, t);
            cards.add(card);
            deck.addView(card);
        }
        updateCount();
        setVisibility(VISIBLE);
        setAlpha(1f);
        post(() -> {
            measureGeometry();
            int idx = Math.max(0, tabs.indexOf(current));
            scroll = clamp(topPad + idx * gap + cardH - (getHeight() - bottomPad));
            layoutCards();
            for (int i = 0; i < cards.size(); i++) {
                View v = cards.get(i);
                float y = v.getTranslationY();
                if (v.getVisibility() != VISIBLE) continue;
                v.setTranslationY(y + Ui.dp(c, 90));
                v.setAlpha(0f);
                v.animate().translationY(y).alpha(1f).setStartDelay(Ui.anim(Math.min(i, 8) * 18L))
                        .setDuration(Ui.anim(340)).setInterpolator(ease).start();
            }
        });
    }

    void hide(Runnable after) {
        animate().alpha(0f).setDuration(Ui.anim(160)).withEndAction(() -> {
            setVisibility(GONE);
            setAlpha(1f);
            if (after != null) after.run();
            if (getContext() instanceof BrowserActivity) ((BrowserActivity) getContext()).applyStatusBar();
        }).start();
    }

    private void updateCount() {
        int n = tabs.size();
        countText.setText("Вкладки · " + n);
    }

    private void measureGeometry() {
        int h = getHeight();
        int w = getWidth();
        topPad = Ui.dp(getContext(), 66);
        bottomPad = Ui.dp(getContext(), 92);
        side = Ui.dp(getContext(), 14);
        if (grid) {
            cols = 2;
            gap = Ui.dp(getContext(), 12);
            cardW = (w - 2 * side - gap) / 2;
            cardH = Math.round(cardW * 1.45f);
            int rows = (tabs.size() + 1) / 2;
            maxScroll = Math.max(0, topPad + rows * (cardH + gap) - gap - (h - bottomPad));
        } else {
            cols = 1;
            cardW = w - 2 * side;
            cardH = Math.round(h * 0.56f);
            gap = Math.round(cardH * 0.30f);
            maxScroll = Math.max(0, topPad + (tabs.size() - 1) * gap + cardH - (h - bottomPad));
        }
        for (int i = 0; i < cards.size(); i++) placeX(cards.get(i), i);
    }

    /** Колонка карточки: в сетке — левая или правая, в стопке — по центру. */
    private void placeX(View card, int i) {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) card.getLayoutParams();
        int left = grid ? side + (i % 2) * (cardW + gap) : side;
        if (lp.leftMargin != left || lp.width != cardW || lp.height != cardH) {
            lp.leftMargin = left;
            lp.rightMargin = 0;
            lp.width = cardW;
            lp.height = cardH;
            card.setLayoutParams(lp);
        }
    }

    private float clamp(float s) {
        return Math.max(0, Math.min(maxScroll, s));
    }

    private void layoutCards() {
        int h = Math.max(1, getHeight());
        if (grid) {
            for (int i = 0; i < cards.size(); i++) {
                View v = cards.get(i);
                float y = topPad + (i / 2) * (cardH + gap) - scroll;
                v.setTranslationY(y);
                v.setScaleX(1f);
                v.setScaleY(1f);
                v.setTranslationZ(0);
                show(v, i, y + cardH > 0 && y < h);
            }
            int tick = (int) Math.floor(scroll / Math.max(1, cardH + gap));
            if (lastTick != Integer.MIN_VALUE && tick != lastTick) Haptics.tick(this);
            lastTick = tick;
            return;
        }
        // в «стопке» над экраном видно только 3 последние сжатые карточки
        int firstOnScreen = cards.size();
        for (int i = 0; i < cards.size(); i++) {
            if (topPad + i * gap - scroll >= topPad) {
                firstOnScreen = i;
                break;
            }
        }
        for (int i = 0; i < cards.size(); i++) {
            View v = cards.get(i);
            float raw = topPad + i * gap - scroll;
            float y;
            float s;
            show(v, i, i >= firstOnScreen - 3 && raw < h);
            if (raw < topPad) {
                float over = topPad - raw;
                y = topPad - Math.min(over * 0.10f, Ui.dp(getContext(), 18));
                s = Math.max(0.86f, 1f - over / (h * 2.6f));
            } else {
                y = raw;
                float depth = (raw - topPad) / h;
                s = 0.93f + 0.07f * Math.min(1f, depth * 1.8f);
            }
            v.setPivotX(v.getWidth() / 2f);
            v.setPivotY(0);
            v.setTranslationY(y);
            v.setScaleX(s);
            v.setScaleY(s);
            v.setTranslationZ(i * 0.6f);
        }
        int tick = gap > 0 ? (int) Math.floor(scroll / gap) : 0;
        if (lastTick != Integer.MIN_VALUE && tick != lastTick) Haptics.tick(this);
        lastTick = tick;
    }

    /** Показать/спрятать карточку; при первом показе — подгрузить картинку вкладки. */
    private void show(View v, int i, boolean visible) {
        int want = visible ? VISIBLE : INVISIBLE;
        if (v.getVisibility() != want) v.setVisibility(want);
        if (visible && v.getTag() instanceof ImageView && i < tabs.size()) {
            ImageView img = (ImageView) v.getTag();
            v.setTag(null);
            Tab t = tabs.get(i);
            if (t.isHome()) return;
            listener.loadThumb(t, b -> {
                if (b != null) img.setImageBitmap(b);
            });
        }
    }

    private View card(Context c, Tab t) {
        FrameLayout card = new FrameLayout(c);
        int r = Ui.dp(c, 18);
        card.setBackground(Ui.round(Ui.card, r));
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
            }
        });
        card.setClipToOutline(true);
        card.setElevation(Ui.dp(c, grid ? 3 : 8));

        TopCropImageView img = new TopCropImageView(c);
        if (t.isHome()) {
            int w = Prefs.wallpaper();
            if (w >= 0 && w < HomeView.WALLPAPERS.length && HomeView.WALLPAPERS[w] > 0) {
                img.setScaleType(ImageView.ScaleType.CENTER_CROP);
                img.setImageResource(HomeView.WALLPAPERS[w]);
            }
        } else {
            card.setTag(img); // картинку подгрузим, когда карточка станет видна
        }
        img.setBackgroundColor(Ui.dark ? 0xFF111113 : 0xFFF7F7F8);
        FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
        ip.topMargin = Ui.dp(c, 44);
        card.addView(img, ip);

        LinearLayout head = new LinearLayout(c);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(Ui.dp(c, 12), 0, Ui.dp(c, 2), 0);
        head.setBackgroundColor(t == current ? (Ui.dark ? 0xFF2C2C2E : 0xFFE5E5EA) : Ui.card);
        TextView glyph = Ui.glyphTile(c, glyphFor(t), 24, 11);
        glyph.setElevation(0);
        head.addView(glyph, new LinearLayout.LayoutParams(Ui.dp(c, 24), Ui.dp(c, 24)));
        TextView title = Ui.text(c, t.displayTitle(), 14, Ui.text, t == current);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setPadding(Ui.dp(c, 10), 0, Ui.dp(c, 6), 0);
        head.addView(title, new LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f));
        ImageButton close = Ui.iconButton(c, R.drawable.ic_close);
        close.setImageTintList(android.content.res.ColorStateList.valueOf(Ui.text2));
        close.setOnClickListener(v -> dismiss(cards.indexOf(card), 1));
        head.addView(close, new LinearLayout.LayoutParams(Ui.dp(c, 44), Ui.dp(c, 44)));
        card.addView(head, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, Ui.dp(c, 44)));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.max(1, cardW), Math.max(1, cardH));
        lp.gravity = Gravity.TOP | Gravity.START;
        card.setLayoutParams(lp);
        card.setVisibility(INVISIBLE);
        return card;
    }

    private static String glyphFor(Tab t) {
        if (t.isHome()) return "⌂";
        String host = t.url == null ? null : android.net.Uri.parse(t.url).getHost();
        if (host == null) return "•";
        if (host.startsWith("www.")) host = host.substring(4);
        if (host.startsWith("m.")) host = host.substring(2);
        return Shortcuts.glyphFor(host);
    }

    // ----------------------------------------------------------------- жесты

    private int cardAt(float x, float y) {
        for (int i = cards.size() - 1; i >= 0; i--) {
            View v = cards.get(i);
            if (v.getVisibility() != VISIBLE) continue;
            float top = v.getTranslationY();
            float bottom = top + v.getHeight() * v.getScaleY();
            float left = ((FrameLayout.LayoutParams) v.getLayoutParams()).leftMargin;
            float right = left + v.getWidth();
            if (y >= top && y <= bottom && (!grid || (x >= left && x <= right))) return i;
        }
        return -1;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        if (busy) return true;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                start(e);
                return false;
            case MotionEvent.ACTION_MOVE:
                return decide(e);
            default:
                return false;
        }
    }

    private void start(MotionEvent e) {
        scroller.forceFinished(true);
        downX = e.getX();
        downY = lastY = e.getY();
        mode = 0;
        touched = cardAt(e.getX(), e.getY());
        if (vt != null) vt.recycle();
        vt = VelocityTracker.obtain();
        vt.addMovement(e);
    }

    private boolean decide(MotionEvent e) {
        float dx = e.getX() - downX;
        float dy = e.getY() - downY;
        if (mode == 0) {
            if (Math.abs(dy) > slop && Math.abs(dy) > Math.abs(dx)) {
                mode = 1;
            } else if (Math.abs(dx) > slop && touched >= 0) {
                mode = 2;
            }
        }
        return mode != 0;
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (busy) return true;
        if (vt == null) vt = VelocityTracker.obtain();
        vt.addMovement(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                start(e);
                return true;
            case MotionEvent.ACTION_MOVE: {
                decide(e);
                if (mode == 1) {
                    float dy = e.getY() - lastY;
                    float next = scroll - dy;
                    if (next < 0 || next > maxScroll) next = scroll - dy * 0.35f; // упругий край
                    scroll = next;
                    layoutCards();
                } else if (mode == 2 && touched >= 0) {
                    View v = cards.get(touched);
                    float dx = e.getX() - downX;
                    v.setTranslationX(dx);
                    v.setAlpha(1f - Math.min(1f, Math.abs(dx) / getWidth()) * 0.8f);
                }
                lastY = e.getY();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                vt.computeCurrentVelocity(1000);
                float vx = vt.getXVelocity();
                float vy = vt.getYVelocity();
                boolean cancel = e.getActionMasked() == MotionEvent.ACTION_CANCEL;
                if (mode == 1) {
                    fling(vy);
                } else if (mode == 2 && touched >= 0) {
                    View v = cards.get(touched);
                    float dx = v.getTranslationX();
                    if (!cancel && (Math.abs(dx) > getWidth() * 0.33f || Math.abs(vx) > 1800)) {
                        dismiss(touched, dx == 0 ? 1 : (int) Math.signum(dx));
                    } else {
                        v.animate().translationX(0).alpha(1f).setDuration(Ui.anim(220)).setInterpolator(ease).start();
                    }
                } else if (!cancel && touched >= 0) {
                    open(touched);
                }
                vt.recycle();
                vt = null;
                mode = 0;
                return true;
            }
            default:
                return true;
        }
    }

    private void fling(float vy) {
        if (scroll < 0 || scroll > maxScroll) {
            scroller.springBack(0, (int) scroll, 0, 0, 0, (int) maxScroll);
        } else {
            scroller.fling(0, (int) scroll, 0, (int) -vy, 0, 0, 0, (int) maxScroll, 0, Ui.dp(getContext(), 40));
        }
        postOnAnimation(new Runnable() {
            @Override
            public void run() {
                if (scroller.computeScrollOffset()) {
                    scroll = scroller.getCurrY();
                    layoutCards();
                    postOnAnimation(this);
                }
            }
        });
    }

    private void dismiss(int index, int dir) {
        if (index < 0 || index >= cards.size()) return;
        busy = true;
        View v = cards.get(index);
        Tab t = tabs.get(index);
        Haptics.thud(this);
        v.animate().translationX(dir * getWidth() * 1.1f).alpha(0f).setDuration(Ui.anim(200))
                .setInterpolator(ease).withEndAction(() -> {
                    deck.removeView(v);
                    cards.remove(index);
                    tabs.remove(index);
                    listener.onClose(t);
                    updateCount();
                    float before = scroll;
                    measureGeometry();
                    scroll = clamp(before);
                    // плавно сдвинуть оставшиеся карточки
                    List<Float> from = new ArrayList<>();
                    for (View cv : cards) from.add(cv.getTranslationY());
                    layoutCards();
                    for (int i = 0; i < cards.size(); i++) {
                        View cv = cards.get(i);
                        float to = cv.getTranslationY();
                        cv.setTranslationY(from.get(i));
                        cv.animate().translationY(to).setDuration(Ui.anim(260)).setInterpolator(ease).start();
                    }
                    busy = false;
                    if (tabs.isEmpty()) hide(null);
                }).start();
    }

    private void open(int index) {
        if (index < 0 || index >= cards.size()) return;
        busy = true;
        Haptics.click(this);
        View v = cards.get(index);
        Tab t = tabs.get(index);
        v.setTranslationZ(1000);
        v.animate().translationY(grid ? v.getTranslationY() : 0).scaleX(1.06f).scaleY(1.06f)
                .alpha(grid ? 0.4f : 1f).setDuration(Ui.anim(230)).setInterpolator(ease)
                .withEndAction(() -> {
                    busy = false;
                    listener.onSelect(t);
                }).start();
    }

    /** Картинка вкладки: по ширине карточки, обрезана снизу. */
    static final class TopCropImageView extends ImageView {
        TopCropImageView(Context c) {
            super(c);
            setScaleType(ScaleType.MATRIX);
        }

        @Override
        protected boolean setFrame(int l, int t, int r, int b) {
            boolean changed = super.setFrame(l, t, r, b);
            if (getScaleType() == ScaleType.MATRIX && getDrawable() != null) {
                int dw = getDrawable().getIntrinsicWidth();
                if (dw > 0) {
                    float s = (r - l) / (float) dw;
                    Matrix m = new Matrix();
                    m.setScale(s, s);
                    setImageMatrix(m);
                }
            }
            return changed;
        }
    }

    /** Маленький помощник: выставить высоту карточки после измерения экрана. */
    static final class ViewGroupHelper {
        static void setHeight(View v, int h) {
            android.view.ViewGroup.LayoutParams lp = v.getLayoutParams();
            if (lp != null && lp.height != h) {
                lp.height = h;
                v.setLayoutParams(lp);
            }
        }
    }
}
