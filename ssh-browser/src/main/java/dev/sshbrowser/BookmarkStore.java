package dev.sshbrowser;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Bookmarked "web apps" shown on the Index page. Not sensitive — plain prefs. */
public final class BookmarkStore {

    public static final class Bookmark {
        public String name;
        public String url;

        Bookmark(String name, String url) {
            this.name = name;
            this.url = url;
        }
    }

    private static final String FILE = "bookmarks";
    private static final String K_LIST = "list";

    private BookmarkStore() {}

    public static List<Bookmark> list(Context ctx) {
        List<Bookmark> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs(ctx).getString(K_LIST, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new Bookmark(o.optString("name"), o.optString("url")));
            }
        } catch (JSONException ignored) {
        }
        if (out.isEmpty() && !prefs(ctx).contains(K_LIST)) {
            out.add(new Bookmark("出口IP", "https://ifconfig.me")); // first-run default
        }
        return out;
    }

    public static void add(Context ctx, String name, String url) {
        List<Bookmark> all = list(ctx);
        all.add(new Bookmark(name, url));
        persist(ctx, all);
    }

    public static void updateAt(Context ctx, int index, String name, String url) {
        List<Bookmark> all = list(ctx);
        if (index >= 0 && index < all.size()) {
            all.set(index, new Bookmark(name, url));
            persist(ctx, all);
        }
    }

    public static void removeAt(Context ctx, int index) {
        List<Bookmark> all = list(ctx);
        if (index >= 0 && index < all.size()) {
            all.remove(index);
            persist(ctx, all);
        }
    }

    private static void persist(Context ctx, List<Bookmark> all) {
        JSONArray arr = new JSONArray();
        for (Bookmark b : all) {
            JSONObject o = new JSONObject();
            try {
                o.put("name", b.name).put("url", b.url);
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
