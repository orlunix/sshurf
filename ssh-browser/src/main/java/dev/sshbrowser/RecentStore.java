package dev.sshbrowser;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Recent page visits (title + url), newest first, capped at 20, deduped by URL. */
public final class RecentStore {

    public static final class Entry {
        public String title;
        public String url;

        Entry(String title, String url) {
            this.title = title;
            this.url = url;
        }
    }

    private static final String FILE = "recents";
    private static final String K_LIST = "list";
    private static final int MAX = 20;

    private RecentStore() {}

    public static List<Entry> list(Context ctx) {
        List<Entry> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs(ctx).getString(K_LIST, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new Entry(o.optString("title"), o.optString("url")));
            }
        } catch (JSONException ignored) {
        }
        return out;
    }

    public static void add(Context ctx, String title, String url) {
        List<Entry> all = list(ctx);
        all.removeIf(e -> e.url.equals(url)); // dedupe; re-add at front
        all.add(0, new Entry(title, url));
        while (all.size() > MAX) all.remove(all.size() - 1);
        persist(ctx, all);
    }

    public static void removeAt(Context ctx, int index) {
        List<Entry> all = list(ctx);
        if (index >= 0 && index < all.size()) {
            all.remove(index);
            persist(ctx, all);
        }
    }

    private static void persist(Context ctx, List<Entry> all) {
        JSONArray arr = new JSONArray();
        for (Entry e : all) {
            JSONObject o = new JSONObject();
            try {
                o.put("title", e.title).put("url", e.url);
            } catch (JSONException ignored) {
            }
            arr.put(o);
        }
        prefs(ctx).edit().putString(K_LIST, arr.toString()).apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }
}
