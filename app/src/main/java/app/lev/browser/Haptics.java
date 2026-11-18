package app.lev.browser;

import android.content.Context;
import android.media.AudioManager;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.view.HapticFeedbackConstants;
import android.view.View;

/**
 * Мягкий отклик: тихий «тик», как у колёсика будильника на iPhone.
 * На Pixel используются примитивы вибромотора (LOW_TICK), если они есть.
 */
final class Haptics {

    private Haptics() { }

    private static Vibrator vibrator(Context c) {
        if (Build.VERSION.SDK_INT >= 31) {
            VibratorManager vm = c.getSystemService(VibratorManager.class);
            return vm == null ? null : vm.getDefaultVibrator();
        }
        return c.getSystemService(Vibrator.class);
    }

    /** Тик при прокрутке стопки вкладок. */
    static void tick(View v) {
        if (Prefs.haptics()) {
            Vibrator vib = vibrator(v.getContext());
            boolean done = false;
            if (Build.VERSION.SDK_INT >= 30 && vib != null && vib.hasVibrator()
                    && vib.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_LOW_TICK)) {
                vib.vibrate(VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.55f)
                        .compose());
                done = true;
            }
            if (!done) {
                int c = Build.VERSION.SDK_INT >= 34
                        ? HapticFeedbackConstants.SEGMENT_FREQUENT_TICK : HapticFeedbackConstants.CLOCK_TICK;
                v.performHapticFeedback(c);
            }
        }
        if (Prefs.sound()) {
            AudioManager am = v.getContext().getSystemService(AudioManager.class);
            if (am != null) am.playSoundEffect(AudioManager.FX_KEY_CLICK, 0.25f);
        }
    }

    /** Лёгкий «клик» по кнопкам. */
    static void click(View v) {
        if (!Prefs.haptics()) return;
        Vibrator vib = vibrator(v.getContext());
        if (Build.VERSION.SDK_INT >= 30 && vib != null && vib.hasVibrator()
                && vib.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK)) {
            vib.vibrate(VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.35f)
                    .compose());
        } else {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        }
    }

    /** Чуть заметнее — при закрытии вкладки свайпом. */
    static void thud(View v) {
        if (!Prefs.haptics()) return;
        Vibrator vib = vibrator(v.getContext());
        if (Build.VERSION.SDK_INT >= 30 && vib != null && vib.hasVibrator()
                && vib.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_THUD)) {
            vib.vibrate(VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, 0.4f)
                    .compose());
        } else {
            v.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
        }
    }
}
