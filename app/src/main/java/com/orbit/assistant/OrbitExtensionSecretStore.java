package com.orbit.assistant;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Keystore-only encrypted storage for extension credentials. Never falls back to plaintext. */
final class OrbitExtensionSecretStore {
    private static final String STORE = "AndroidKeyStore";
    private static final String ALIAS = "orbit_extension_secrets_v1";
    private static final String PREF_FILE = "orbit_extension_secrets";
    private static final String PREF_KEY = "encrypted_entries_v1";
    private static final int MAX_ENCRYPTED_STORE_BYTES = 256 * 1024;

    private OrbitExtensionSecretStore() {}

    static synchronized boolean save(Context context, String extensionId,
                                     Map<String, String> replacements,
                                     Set<String> clears) {
        if (context == null || !safeId(extensionId)) return false;
        Map<String, String> safeReplacements = replacements == null
                ? Collections.emptyMap() : replacements;
        Set<String> safeClears = clears == null ? Collections.emptySet() : clears;
        try {
            JSONObject root = readRoot(context);
            JSONObject extension = root.optJSONObject(extensionId);
            if (extension == null) extension = new JSONObject();
            for (String fieldId : safeClears) {
                if (!safeId(fieldId)) return false;
                extension.remove(fieldId);
            }
            for (Map.Entry<String, String> entry : safeReplacements.entrySet()) {
                if (!safeId(entry.getKey()) || entry.getValue() == null || entry.getValue().isEmpty())
                    return false;
                extension.put(entry.getKey(), encrypt(entry.getValue()));
            }
            if (extension.length() == 0) root.remove(extensionId);
            else root.put(extensionId, extension);
            byte[] bytes = root.toString().getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_ENCRYPTED_STORE_BYTES) return false;
            return prefs(context).edit().putString(PREF_KEY, root.toString()).commit();
        } catch (Exception ignored) {
            return false;
        }
    }

    static synchronized String load(Context context, String extensionId, String fieldId) {
        if (context == null || !safeId(extensionId) || !safeId(fieldId)) return null;
        try {
            JSONObject extension = readRoot(context).optJSONObject(extensionId);
            JSONObject entry = extension == null ? null : extension.optJSONObject(fieldId);
            if (entry == null) return null;
            String value = decrypt(entry);
            return value.isEmpty() ? null : value;
        } catch (Exception ignored) {
            return null;
        }
    }

    static synchronized boolean clearExtension(Context context, String extensionId) {
        if (context == null || !safeId(extensionId)) return false;
        try {
            JSONObject root = readRoot(context);
            root.remove(extensionId);
            return prefs(context).edit().putString(PREF_KEY, root.toString()).commit();
        } catch (Exception ignored) {
            return false;
        }
    }

    static synchronized boolean clearAll(Context context) {
        if (context == null) return false;
        boolean keyInvalidated = false;
        try {
            KeyStore keyStore = KeyStore.getInstance(STORE);
            keyStore.load(null);
            if (keyStore.containsAlias(ALIAS)) keyStore.deleteEntry(ALIAS);
            keyInvalidated = true;
        } catch (Exception ignored) {}
        boolean preferencesCleared = prefs(context).edit().remove(PREF_KEY).commit();
        return keyInvalidated && preferencesCleared;
    }

    private static JSONObject encrypt(String value) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        return new JSONObject()
                .put("ciphertext", Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .put("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP));
    }

    private static String decrypt(JSONObject entry) throws Exception {
        String ciphertext = entry.optString("ciphertext", "");
        String iv = entry.optString("iv", "");
        if (ciphertext.isEmpty() || iv.isEmpty()) return "";
        KeyStore keyStore = KeyStore.getInstance(STORE);
        keyStore.load(null);
        SecretKey key = (SecretKey) keyStore.getKey(ALIAS, null);
        if (key == null) return "";
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key,
                new GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP)),
                StandardCharsets.UTF_8);
    }

    private static synchronized SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(STORE);
        keyStore.load(null);
        if (keyStore.containsAlias(ALIAS)) return (SecretKey) keyStore.getKey(ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE);
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }

    private static JSONObject readRoot(Context context) {
        try {
            String raw = prefs(context).getString(PREF_KEY, "{}");
            if (raw == null || raw.getBytes(StandardCharsets.UTF_8).length > MAX_ENCRYPTED_STORE_BYTES)
                return new JSONObject();
            return new JSONObject(raw);
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
    }

    private static boolean safeId(String value) {
        return value != null && value.matches("[a-z0-9][a-z0-9._-]{1,79}");
    }
}
