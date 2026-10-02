package com.orbit.assistant;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * Where the browser's "Return to Orbit" button lands after a ChatGPT sign-in, and since
 * 0.8.3.0-beta.6 after Sign in with OpenRouter too (AI Providers sets {@link #returnTo}).
 *
 * <p>Exported and browsable only because a web page cannot otherwise hand the user back to an app.
 * It is a doorway with nothing behind it: it reads nothing from the link, carries no data, signs
 * nothing in, and only brings forward the screen that started the sign-in (Settings unless
 * onboarding did). The sign-in itself was already finished by the loopback receiver before the
 * page showing this button was served, so opening this link from anywhere else changes nothing.
 */
public final class ChatGptSignInReturnActivity extends Activity {
    /** The screen to return to, set by whichever screen started the sign-in. */
    static volatile Class<? extends Activity> returnTo = SettingsActivity.class;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Class<? extends Activity> target = returnTo == null ? SettingsActivity.class : returnTo;
        Intent back = new Intent(this, target)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try { startActivity(back); } catch (Exception ignored) {}
        finish();
        UiKit.suppressPageTransition(this);
    }
}
