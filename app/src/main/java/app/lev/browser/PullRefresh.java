package app.lev.browser;

import android.content.Context;
import android.view.MotionEvent;

import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

/**
 * «Потяни вниз, чтобы обновить» — как в Chrome.
 * Страница обновляется, только если она сама упёрлась в верх (WebView сообщил о прокрутке за край)
 * и жест сделан одним пальцем. Карты, слайдеры и всё, что забирает жест себе, страницу не обновляют,
 * а щипок двумя пальцами (приблизить) — никогда.
 */
class PullRefresh extends SwipeRefreshLayout {

    interface Source {
        /** Текущая страница или null (главная, нет вкладки). */
        ByssWebView web();
    }

    private final Source source;
    private boolean inDown;
    private boolean multi;

    PullRefresh(Context c, Source source) {
        super(c);
        this.source = source;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        int a = ev.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN) {
            multi = false;
            ByssWebView w = source.web();
            if (w != null) w.resetPull();
            inDown = true;
            try {
                return super.onInterceptTouchEvent(ev);
            } finally {
                inDown = false;
            }
        }
        if (a == MotionEvent.ACTION_POINTER_DOWN || ev.getPointerCount() > 1) multi = true;
        return super.onInterceptTouchEvent(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        int a = ev.getActionMasked();
        if ((a == MotionEvent.ACTION_POINTER_DOWN || ev.getPointerCount() > 1) && !multi) {
            multi = true;
            // второй палец уже во время потягивания — убираем начатый кружок, ничего не обновляем
            if (!isRefreshing() && isEnabled()) {
                setEnabled(false);
                setEnabled(true);
            }
        }
        return super.onTouchEvent(ev);
    }

    /** true — жест сейчас нельзя превращать в обновление страницы. */
    boolean holdGesture() {
        if (inDown) return false;
        if (multi) return true;
        ByssWebView w = source.web();
        return w == null || !w.pulledAtTop();
    }
}
