package dev.sshbrowser;

import android.os.Handler;
import android.os.Looper;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * Tiny in-process event bus: connection state + log lines for the UI.
 * Keeps a bounded history so lines emitted while no UI is attached
 * (e.g. while browsing) are replayed when the listener returns.
 */
public final class Bus {

    public enum State { DISCONNECTED, CONNECTING, CONNECTED }

    public interface Listener {
        void onState(State state, String detail);
        void onLog(String line);
    }

    private static final int MAX_HISTORY = 300;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Deque<String> history = new ArrayDeque<>();
    private static volatile State state = State.DISCONNECTED;
    private static Listener listener;

    private Bus() {}

    public static void setListener(Listener l) {
        listener = l;
        if (l != null) {
            State s = state;
            List<String> snap;
            synchronized (history) {
                snap = new ArrayList<>(history);
            }
            MAIN.post(() -> {
                Listener cur = listener;
                if (cur != l) return;
                for (String line : snap) cur.onLog(line);
                cur.onState(s, null);
            });
        }
    }

    public static State getState() {
        return state;
    }

    public static void postState(State s, String detail) {
        state = s;
        MAIN.post(() -> {
            Listener l = listener;
            if (l != null) l.onState(s, detail);
        });
        if (detail != null) log(detail);
    }

    public static void log(String msg) {
        String ts = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        String line = ts + "  " + msg;
        synchronized (history) {
            history.addLast(line);
            while (history.size() > MAX_HISTORY) history.removeFirst();
        }
        MAIN.post(() -> {
            Listener l = listener;
            if (l != null) l.onLog(line);
        });
    }
}
