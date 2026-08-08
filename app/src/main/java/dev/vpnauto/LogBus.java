package dev.vpnauto;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Tiny log bus: writes to Logcat and forwards to a UI listener (MainActivity).
 * Never log secrets (passwords, TOTP secrets) here.
 */
public final class LogBus {

    public interface Listener {
        void onLog(String line);
    }

    private static final String TAG = "VpnAuto";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final SimpleDateFormat TIME =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private static volatile Listener listener;

    private LogBus() {}

    public static void setListener(Listener l) {
        listener = l;
    }

    public static void log(String msg) {
        Log.i(TAG, msg);
        String line = TIME.format(new Date()) + "  " + msg;
        Listener l = listener;
        if (l != null) {
            MAIN.post(() -> {
                Listener cur = listener;
                if (cur != null) cur.onLog(line);
            });
        }
    }
}
