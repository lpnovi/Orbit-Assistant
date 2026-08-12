package com.orbit.assistant;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class SecureStore {
    private static final String STORE = "AndroidKeyStore";
    private static final String RELAY_ALIAS = "orbit_relay_secret_v1";
    private static final String RELAY_ENC = "backend_token_enc";
    private static final String RELAY_IV = "backend_token_iv";

    private static final String CHATGPT_ALIAS = "orbit_chatgpt_oauth_v1";
    private static final String CHATGPT_ENC = "chatgpt_oauth_enc";
    private static final String CHATGPT_IV = "chatgpt_oauth_iv";

    private SecureStore() {}

    public static void saveRelayToken(Context c, String value) {
        try {
            if (value == null || value.isEmpty()) {
                Prefs.get(c).edit().remove(RELAY_ENC).remove(RELAY_IV).remove(Prefs.BACKEND_TOKEN).apply();
                return;
            }
            encrypt(c, RELAY_ALIAS, RELAY_ENC, RELAY_IV, value);
            Prefs.get(c).edit().remove(Prefs.BACKEND_TOKEN).apply();
        } catch (Exception e) {
            // Compatibility fallback only for the optional relay token. The OpenAI API key is never stored here.
            Prefs.get(c).edit().putString(Prefs.BACKEND_TOKEN, value == null ? "" : value).apply();
        }
    }

    public static String loadRelayToken(Context c) {
        try {
            String value = decrypt(c, RELAY_ALIAS, RELAY_ENC, RELAY_IV);
            if (!value.isEmpty()) return value;
        } catch (Exception ignored) {}
        return Prefs.get(c).getString(Prefs.BACKEND_TOKEN, "").trim();
    }

    public static boolean saveChatGptTokens(Context c, String idToken, String accessToken,
                                             String refreshToken, String accountId) {
        try {
            JSONObject o = new JSONObject();
            o.put("id_token", idToken == null ? "" : idToken);
            o.put("access_token", accessToken == null ? "" : accessToken);
            o.put("refresh_token", refreshToken == null ? "" : refreshToken);
            o.put("account_id", accountId == null ? "" : accountId);
            encrypt(c, CHATGPT_ALIAS, CHATGPT_ENC, CHATGPT_IV, o.toString());
            return true;
        } catch (Exception e) {
            clearChatGpt(c);
            return false;
        }
    }

    public static ChatGptTokens loadChatGptTokens(Context c) {
        try {
            String raw = decrypt(c, CHATGPT_ALIAS, CHATGPT_ENC, CHATGPT_IV);
            if (raw.isEmpty()) return null;
            JSONObject o = new JSONObject(raw);
            String access = o.optString("access_token", "");
            String refresh = o.optString("refresh_token", "");
            if (access.isEmpty() || refresh.isEmpty()) return null;
            return new ChatGptTokens(
                    o.optString("id_token", ""), access, refresh, o.optString("account_id", ""));
        } catch (Exception e) {
            return null;
        }
    }

    public static void clearChatGpt(Context c) {
        Prefs.get(c).edit().remove(CHATGPT_ENC).remove(CHATGPT_IV).apply();
    }

    private static void encrypt(Context c, String alias, String encKey, String ivKey, String value) throws Exception {
        SecretKey key = getOrCreateKey(alias);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        Prefs.get(c).edit()
                .putString(encKey, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .putString(ivKey, Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                .apply();
    }

    private static String decrypt(Context c, String alias, String encKey, String ivKey) throws Exception {
        String enc = Prefs.get(c).getString(encKey, "");
        String iv = Prefs.get(c).getString(ivKey, "");
        if (enc.isEmpty() || iv.isEmpty()) return "";
        KeyStore ks = KeyStore.getInstance(STORE);
        ks.load(null);
        SecretKey key = (SecretKey) ks.getKey(alias, null);
        if (key == null) return "";
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(enc, Base64.NO_WRAP)), StandardCharsets.UTF_8);
    }

    private static SecretKey getOrCreateKey(String alias) throws Exception {
        KeyStore ks = KeyStore.getInstance(STORE);
        ks.load(null);
        if (ks.containsAlias(alias)) return (SecretKey) ks.getKey(alias, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE);
        generator.init(new KeyGenParameterSpec.Builder(alias,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }

    public static final class ChatGptTokens {
        public final String idToken;
        public final String accessToken;
        public final String refreshToken;
        public final String accountId;
        ChatGptTokens(String id, String access, String refresh, String account) {
            idToken = id; accessToken = access; refreshToken = refresh; accountId = account;
        }
    }
}
