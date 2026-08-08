package dev.sshbrowser;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

/**
 * Credential and key storage. Everything lives in EncryptedSharedPreferences
 * (AES-256-GCM, master key in Android Keystore). Nothing is hardcoded.
 */
public final class SshConfig {

    private static final String FILE = "ssh_config";
    private static final String K_HOST = "host";
    private static final String K_PORT = "port";
    private static final String K_USER = "user";
    private static final String K_PASSWORD = "password";
    private static final String K_PRIVATE_KEY = "private_key_pem";
    private static final String K_PUBLIC_KEY = "public_key";
    private static final String K_KEY_PASSPHRASE = "key_passphrase";

    public final String host;
    public final int port;
    public final String user;
    public final String password;
    public final String privateKeyPem;
    public final String publicKey;
    public final String keyPassphrase;

    private SshConfig(SharedPreferences sp) {
        host = sp.getString(K_HOST, "");
        port = sp.getInt(K_PORT, 22);
        user = sp.getString(K_USER, "");
        password = sp.getString(K_PASSWORD, "");
        privateKeyPem = sp.getString(K_PRIVATE_KEY, "");
        publicKey = sp.getString(K_PUBLIC_KEY, "");
        keyPassphrase = sp.getString(K_KEY_PASSPHRASE, "");
    }

    private static SharedPreferences prefs(Context ctx) {
        try {
            MasterKey key = new MasterKey.Builder(ctx)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
            return EncryptedSharedPreferences.create(ctx, FILE, key,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
        } catch (Exception e) {
            throw new IllegalStateException("Keystore-backed storage unavailable", e);
        }
    }

    public static SshConfig load(Context ctx) {
        return new SshConfig(prefs(ctx));
    }

    public static void saveConnection(Context ctx, String host, int port, String user, String password) {
        prefs(ctx).edit()
                .putString(K_HOST, host)
                .putInt(K_PORT, port)
                .putString(K_USER, user)
                .putString(K_PASSWORD, password)
                .apply();
    }

    public static void saveKeyPair(Context ctx, String privateKeyPem, String publicKey, String keyPassphrase) {
        prefs(ctx).edit()
                .putString(K_PRIVATE_KEY, privateKeyPem)
                .putString(K_PUBLIC_KEY, publicKey)
                .putString(K_KEY_PASSPHRASE, keyPassphrase)
                .apply();
    }

    public static void saveKeyPassphrase(Context ctx, String keyPassphrase) {
        prefs(ctx).edit().putString(K_KEY_PASSPHRASE, keyPassphrase).apply();
    }

    public boolean hasKey() {
        return privateKeyPem != null && !privateKeyPem.isEmpty();
    }

    public boolean isComplete() {
        return !host.isEmpty() && !user.isEmpty() && (hasKey() || !password.isEmpty());
    }
}
