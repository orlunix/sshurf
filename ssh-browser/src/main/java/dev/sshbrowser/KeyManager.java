package dev.sshbrowser;

import android.content.Context;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.KeyPair;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Generates the device key pair. The private key never leaves encrypted storage. */
public final class KeyManager {

    private KeyManager() {}

    /**
     * Generates an RSA-4096 pair (plain JCE, no extra deps) and persists it.
     * Returns the OpenSSH-format public key line for the user to install on the server.
     * Must be called off the main thread (key generation is slow).
     */
    public static String generate(Context ctx) throws Exception {
        JSch jsch = new JSch();
        KeyPair kp = KeyPair.genKeyPair(jsch, KeyPair.RSA, 4096);
        try {
            ByteArrayOutputStream priv = new ByteArrayOutputStream();
            kp.writePrivateKey(priv);
            ByteArrayOutputStream pub = new ByteArrayOutputStream();
            kp.writePublicKey(pub, "ssh-browser@android");
            String privatePem = new String(priv.toByteArray(), StandardCharsets.US_ASCII);
            String publicLine = new String(pub.toByteArray(), StandardCharsets.US_ASCII).trim();
            SshConfig.saveKeyPair(ctx, privatePem, publicLine, "");
            return publicLine;
        } finally {
            kp.dispose();
        }
    }
}
