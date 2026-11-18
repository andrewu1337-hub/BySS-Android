package app.lev.browser;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Ответ установщика Android на обновление браузера (нужно подтверждение, готово или ошибка). */
public final class UpdateReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        Updater.onInstallerResult(c, i);
    }
}
