package dev.sshbrowser;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * SSH server profiles, stored in Keystore-encrypted prefs (see SshConfig).
 * Exactly one profile is "enabled" — the tunnel uses it.
 *
 * Auth is per profile: an imported/generated private key (privKey/keyPass)
 * takes precedence over the password field.
 *
 * Migrations: legacy single-server fields (0.0.1-), and the legacy global
 * key pair, are folded into the first profile.
 */
public final class Profiles {

    public static final class Profile {
        public String id = "";
        public String name = "";
        public String host = "";
        public int port = 22;
        public String user = "";
        public String password = "";
        public String privKey = "";   // PEM private key, "" = no key auth
        public String keyPass = "";   // passphrase for privKey, "" = none
        public String pubKey = "";    // OpenSSH public key line, for display/copy

        public String summary() {
            return user + "@" + host + (port == 22 ? "" : ":" + port);
        }

        public boolean hasKey() {
            return !privKey.isEmpty();
        }
    }

    private static final String K_LIST = "profiles";
    private static final String K_ENABLED = "enabled_profile";
    private static final String K_KEYS_MIGRATED = "keys_migrated";

    // legacy SshConfig keys (global key pair, pre-0.0.3)
    private static final String L_PRIV = "private_key_pem";
    private static final String L_PUB = "public_key";
    private static final String L_PASS = "key_passphrase";

    private Profiles() {}

    public static List<Profile> list(Context ctx) {
        SharedPreferences sp = SshConfig.prefs(ctx);
        migrateLegacyServerIfNeeded(sp);
        migrateGlobalKeyIfNeeded(sp);
        List<Profile> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(sp.getString(K_LIST, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                out.add(fromJson(arr.getJSONObject(i)));
            }
        } catch (JSONException ignored) {
        }
        return out;
    }

    public static Profile enabled(Context ctx) {
        String id = SshConfig.prefs(ctx).getString(K_ENABLED, "");
        List<Profile> all = list(ctx);
        for (Profile p : all) {
            if (p.id.equals(id)) return p;
        }
        return all.isEmpty() ? null : all.get(0); // fall back to first
    }

    public static void setEnabled(Context ctx, String id) {
        SshConfig.prefs(ctx).edit().putString(K_ENABLED, id).apply();
    }

    /** Insert or update (matched by id). New profiles become enabled if none was. */
    public static void save(Context ctx, Profile p) {
        List<Profile> all = list(ctx);
        boolean found = false;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id.equals(p.id)) {
                all.set(i, p);
                found = true;
                break;
            }
        }
        if (!found) {
            if (p.id.isEmpty()) p.id = UUID.randomUUID().toString().substring(0, 8);
            all.add(p);
        }
        persist(ctx, all);
        if (SshConfig.prefs(ctx).getString(K_ENABLED, "").isEmpty()) {
            setEnabled(ctx, p.id);
        }
    }

    public static void delete(Context ctx, String id) {
        List<Profile> all = list(ctx);
        all.removeIf(p -> p.id.equals(id));
        persist(ctx, all);
    }

    private static void persist(Context ctx, List<Profile> all) {
        JSONArray arr = new JSONArray();
        for (Profile p : all) {
            arr.put(toJson(p));
        }
        SshConfig.prefs(ctx).edit().putString(K_LIST, arr.toString()).apply();
    }

    private static JSONObject toJson(Profile p) {
        JSONObject o = new JSONObject();
        try {
            o.put("id", p.id).put("name", p.name).put("host", p.host)
                    .put("port", p.port).put("user", p.user).put("password", p.password)
                    .put("privKey", p.privKey).put("keyPass", p.keyPass).put("pubKey", p.pubKey);
        } catch (JSONException ignored) {
        }
        return o;
    }

    private static Profile fromJson(JSONObject o) {
        Profile p = new Profile();
        p.id = o.optString("id");
        p.name = o.optString("name");
        p.host = o.optString("host");
        p.port = o.optInt("port", 22);
        p.user = o.optString("user");
        p.password = o.optString("password");
        p.privKey = o.optString("privKey");
        p.keyPass = o.optString("keyPass");
        p.pubKey = o.optString("pubKey");
        return p;
    }

    /** One-time migration of the legacy single-server fields (pre-profiles versions). */
    private static void migrateLegacyServerIfNeeded(SharedPreferences sp) {
        if (sp.contains(K_LIST)) return;
        String legacyHost = sp.getString("host", "");
        if (legacyHost == null || legacyHost.isEmpty()) return;
        Profile p = new Profile();
        p.id = "default";
        p.name = "默认服务器";
        p.host = legacyHost;
        p.port = sp.getInt("port", 22);
        p.user = sp.getString("user", "");
        p.password = sp.getString("password", "");
        JSONArray arr = new JSONArray();
        arr.put(toJson(p));
        sp.edit().putString(K_LIST, arr.toString()).putString(K_ENABLED, p.id).apply();
    }

    /** One-time migration of the legacy global key pair into profiles lacking a key. */
    private static void migrateGlobalKeyIfNeeded(SharedPreferences sp) {
        if (sp.getBoolean(K_KEYS_MIGRATED, false)) return;
        String priv = sp.getString(L_PRIV, "");
        String pub = sp.getString(L_PUB, "");
        String pass = sp.getString(L_PASS, "");
        sp.edit().putBoolean(K_KEYS_MIGRATED, true).apply();
        if (priv == null || priv.isEmpty()) return;
        if (!sp.contains(K_LIST)) return; // no profiles yet; nothing to attach to
        try {
            JSONArray arr = new JSONArray(sp.getString(K_LIST, "[]"));
            boolean changed = false;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                if (o.optString("privKey").isEmpty()) {
                    o.put("privKey", priv);
                    o.put("pubKey", pub == null ? "" : pub);
                    o.put("keyPass", pass == null ? "" : pass);
                    changed = true;
                }
            }
            if (changed) {
                sp.edit().putString(K_LIST, arr.toString())
                        .remove(L_PRIV).remove(L_PUB).remove(L_PASS)
                        .apply();
            }
        } catch (JSONException ignored) {
        }
    }
}
