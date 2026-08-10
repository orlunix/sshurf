package dev.sshbrowser;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.KeyPair;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Generates device key pairs. The private key never leaves encrypted storage. */
public final class KeyManager {

    /** A generated pair: PEM private key + OpenSSH public key line. */
    public static final class Pair {
        public final String privPem;
        public final String pubLine;

        Pair(String privPem, String pubLine) {
            this.privPem = privPem;
            this.pubLine = pubLine;
        }
    }

    private KeyManager() {}

    /**
     * Generates an RSA-4096 pair (plain JCE, no extra deps).
     * Must be called off the main thread (key generation is slow).
     */
    public static Pair generate() throws Exception {
        JSch jsch = new JSch();
        KeyPair kp = KeyPair.genKeyPair(jsch, KeyPair.RSA, 4096);
        try {
            ByteArrayOutputStream priv = new ByteArrayOutputStream();
            kp.writePrivateKey(priv);
            ByteArrayOutputStream pub = new ByteArrayOutputStream();
            kp.writePublicKey(pub, "sshurf@android");
            return new Pair(
                    new String(priv.toByteArray(), StandardCharsets.US_ASCII),
                    new String(pub.toByteArray(), StandardCharsets.US_ASCII).trim());
        } finally {
            kp.dispose();
        }
    }
}
