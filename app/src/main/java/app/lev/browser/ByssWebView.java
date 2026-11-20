package app.lev.browser;

import android.content.Context;
import android.view.View;
import android.webkit.WebView;

/**
 * WebView, который умеет «не замечать», что приложение свернули, — пока играет видео.
 * Так YouTube продолжает играть в фоне и при выключенном экране.
 */
public class ByssWebView extends WebView {

    private boolean keepAlive;

    public ByssWebView(Context context) {
        super(context);
    }

    void setKeepAlive(boolean keep) {
        keepAlive = keep;
    }

    boolean isKeepAlive() {
        return keepAlive;
    }

    private boolean pulledAtTop;

    /** Начался новый жест: забываем, упиралась ли страница в верх. */
    void resetPull() {
        pulledAtTop = false;
    }

    /** Страница в этом жесте сама дошла до верха и тянется дальше — можно обновлять. */
    boolean pulledAtTop() {
        return pulledAtTop;
    }

    @Override
    protected void onOverScrolled(int scrollX, int scrollY, boolean clampedX, boolean clampedY) {
        super.onOverScrolled(scrollX, scrollY, clampedX, clampedY);
        if (clampedY && scrollY <= 0) pulledAtTop = true;
    }

    /** Полная высота страницы в пикселях экрана (для длинного скриншота). */
    int scrollRange() {
        return computeVerticalScrollRange();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        if (keepAlive && visibility != View.VISIBLE) {
            super.onWindowVisibilityChanged(View.VISIBLE);
        } else {
            super.onWindowVisibilityChanged(visibility);
        }
    }
}
