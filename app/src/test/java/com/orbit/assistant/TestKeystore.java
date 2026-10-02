package com.orbit.assistant;

import java.security.Key;
import java.security.KeyStoreSpi;
import java.security.Provider;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.spec.AlgorithmParameterSpec;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

import javax.crypto.KeyGenerator;
import javax.crypto.KeyGeneratorSpi;
import javax.crypto.SecretKey;

/**
 * A working, in-memory stand-in for Android Keystore, so the real {@link SecureStore} code paths
 * (generate a key, encrypt with AES/GCM, decrypt) run under Robolectric, which ships none.
 *
 * <p>Keys are ordinary AES keys from the JVM held in a map by alias. Nothing here weakens what the
 * app does on a phone; it only lets tests prove that credentials are stored, read back, rotated
 * and cleared by the same code that does it on a device.
 */
final class TestKeystore {
    static final Map<String, SecretKey> KEYS = new HashMap<>();
    private static final String NAME = "AndroidKeyStore";

    private TestKeystore() {}

    static void install() {
        Security.removeProvider(NAME);
        KEYS.clear();
        Security.addProvider(new FakeProvider());
    }

    static void uninstall() {
        Security.removeProvider(NAME);
        KEYS.clear();
    }

    static final class FakeProvider extends Provider {
        FakeProvider() {
            super(NAME, 1.0, "Test-only in-memory AndroidKeyStore");
            put("KeyStore." + NAME, Store.class.getName());
            put("KeyGenerator.AES", Generator.class.getName());
        }
    }

    public static final class Store extends KeyStoreSpi {
        @Override public Key engineGetKey(String alias, char[] password) { return KEYS.get(alias); }
        @Override public Certificate[] engineGetCertificateChain(String alias) { return null; }
        @Override public Certificate engineGetCertificate(String alias) { return null; }
        @Override public Date engineGetCreationDate(String alias) { return new Date(); }
        @Override public void engineSetKeyEntry(String alias, Key key, char[] password, Certificate[] chain) {
            KEYS.put(alias, (SecretKey) key);
        }
        @Override public void engineSetKeyEntry(String alias, byte[] key, Certificate[] chain) {}
        @Override public void engineSetCertificateEntry(String alias, Certificate cert) {}
        @Override public void engineDeleteEntry(String alias) { KEYS.remove(alias); }
        @Override public Enumeration<String> engineAliases() { return Collections.enumeration(KEYS.keySet()); }
        @Override public boolean engineContainsAlias(String alias) { return KEYS.containsKey(alias); }
        @Override public int engineSize() { return KEYS.size(); }
        @Override public boolean engineIsKeyEntry(String alias) { return KEYS.containsKey(alias); }
        @Override public boolean engineIsCertificateEntry(String alias) { return false; }
        @Override public String engineGetCertificateAlias(Certificate cert) { return null; }
        @Override public void engineStore(java.io.OutputStream stream, char[] password) {}
        @Override public void engineLoad(java.io.InputStream stream, char[] password) {}
    }

    public static final class Generator extends KeyGeneratorSpi {
        private String alias = "default";

        @Override protected void engineInit(SecureRandom random) {}

        @Override protected void engineInit(AlgorithmParameterSpec params, SecureRandom random) {
            if (params instanceof android.security.keystore.KeyGenParameterSpec) {
                alias = ((android.security.keystore.KeyGenParameterSpec) params).getKeystoreAlias();
            }
        }

        @Override protected void engineInit(int keysize, SecureRandom random) {}

        @Override protected SecretKey engineGenerateKey() {
            try {
                KeyGenerator real = KeyGenerator.getInstance("AES", "SunJCE");
                real.init(256);
                SecretKey key = real.generateKey();
                KEYS.put(alias, key);
                return key;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
