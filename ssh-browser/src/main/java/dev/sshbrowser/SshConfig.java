package dev.sshbrowser;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Keystore-backed encrypted prefs provider. Only remains as the storage
 * backend; connection/auth data lives in Profiles, per profile.
 */
final class SshConfig {

    private static final String FILE = "ssh_config";

    private SshConfig() {}

    static SharedPreferences prefs(Context ctx) {
        try {
            androidx.security.crypto.MasterKey key = new androidx.security.crypto.MasterKey.Builder(ctx)
                    .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM)
                    .build();
            return androidx.security.crypto.EncryptedSharedPreferences.create(ctx, FILE, key,
                    androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
        } catch (Exception e) {
            throw new IllegalStateException("Keystore-backed storage unavailable", e);
        }
    }
}
