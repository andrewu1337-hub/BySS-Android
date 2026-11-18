package app.lev.browser;

import android.content.Context;
import android.view.View;
import android.webkit.WebView;

/**
 * WebView, который умеет «не замечать», что приложение свернули, — пока играет видео.
 * Так YouTube продолжает играть в фоне и при выключенном экране.
 */
public class LevWebView extends WebView {

    private boolean keepAlive;

    public LevWebView(Context context) {
        super(context);
    }

    void setKeepAlive(boolean keep) {
        keepAlive = keep;
    }

    boolean isKeepAlive() {
        return keepAlive;
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
