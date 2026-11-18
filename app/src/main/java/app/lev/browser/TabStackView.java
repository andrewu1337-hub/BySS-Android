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
 * Вкладки стопкой, как в старом Android: карточки лежат одна на другой снизу вверх.
 * Листание с мягким «тиком» вибрации, свайп в сторону — закрыть, нажатие — открыть.
 */
@SuppressLint("ViewConstructor")
final class TabStackView extends FrameLayout {

    interface Listener {
        void onSelect(Tab t);
        void onClose(Tab t);
        void onNewTab();
        void onCloseAll();
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

        ImageButton plus = Ui.iconButton(c, R.drawable.ic_plus);
        plus.setBackground(Ui.ripple(Ui.round(Ui.pill, Ui.dp(c, 28)), null));
        plus.setElevation(Ui.dp(c, 6));
        plus.setOnClickListener(v -> {
            Haptics.click(v);
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
                v.setTranslationY(y + Ui.dp(c, 90));
                v.setAlpha(0f);
                v.animate().translationY(y).alpha(1f).setStartDelay(Math.min(i, 8) * 18L)
                        .setDuration(340).setInterpolator(ease).start();
            }
        });
    }

    void hide(Runnable after) {
        animate().alpha(0f).setDuration(160).withEndAction(() -> {
            setVisibility(GONE);
            setAlpha(1f);
            if (after != null) after.run();
        }).start();
    }

    private void updateCount() {
        int n = tabs.size();
        countText.setText("Вкладки · " + n);
    }

    private void measureGeometry() {
        int h = getHeight();
        topPad = Ui.dp(getContext(), 66);
        bottomPad = Ui.dp(getContext(), 92);
        cardH = Math.round(h * 0.56f);
        gap = Math.round(cardH * 0.30f);
        maxScroll = Math.max(0, topPad + (tabs.size() - 1) * gap + cardH - (h - bottomPad));
    }

    private float clamp(float s) {
        return Math.max(0, Math.min(maxScroll, s));
    }

    private void layoutCards() {
        int h = Math.max(1, getHeight());
        for (int i = 0; i < cards.size(); i++) {
            View v = cards.get(i);
            float raw = topPad + i * gap - scroll;
            float y;
            float s;
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
        card.setElevation(Ui.dp(c, 8));

        TopCropImageView img = new TopCropImageView(c);
        Bitmap thumb = t.thumb;
        if (thumb != null) {
            img.setImageBitmap(thumb);
        } else if (t.isHome()) {
            int w = Prefs.wallpaper();
            if (w >= 0 && w < HomeView.WALLPAPERS.length && HomeView.WALLPAPERS[w] != 0) {
                img.setScaleType(ImageView.ScaleType.CENTER_CROP);
                img.setImageResource(HomeView.WALLPAPERS[w]);
            }
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

        int side = Ui.dp(c, 14);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0);
        lp.leftMargin = side;
        lp.rightMargin = side;
        card.setLayoutParams(lp);
        post(() -> {
            ViewGroupHelper.setHeight(card, cardH > 0 ? cardH : Math.round(getHeight() * 0.56f));
        });
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

    private int cardAt(float y) {
        for (int i = cards.size() - 1; i >= 0; i--) {
            View v = cards.get(i);
            float top = v.getTranslationY();
            float bottom = top + v.getHeight() * v.getScaleY();
            if (y >= top && y <= bottom) return i;
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
        touched = cardAt(e.getY());
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
                        v.animate().translationX(0).alpha(1f).setDuration(220).setInterpolator(ease).start();
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
        v.animate().translationX(dir * getWidth() * 1.1f).alpha(0f).setDuration(200)
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
                        cv.animate().translationY(to).setDuration(260).setInterpolator(ease).start();
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
        v.animate().translationY(0).scaleX(1.06f).scaleY(1.06f).setDuration(230).setInterpolator(ease)
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
