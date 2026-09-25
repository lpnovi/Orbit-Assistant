package com.orbit.assistant;

import android.content.Context;
import android.graphics.Bitmap;

import com.google.android.gms.common.moduleinstall.ModuleInstall;
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.common.MlKitException;
import com.google.mlkit.common.sdkinternal.MlKitContext;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.concurrent.TimeUnit;

/**
 * Text recognition for pictures saved in the Vault, entirely on the device.
 *
 * <p>Google ML Kit's Latin text recogniser, in its Google Play services form: the recognition
 * model is delivered by Play services the first time it is needed instead of being bundled into
 * every Orbit APK, which keeps the download small for the many people who never turn this on. The
 * picture never leaves the phone. Google Play services may collect anonymous usage metrics for the
 * ML Kit API itself, which the Smart Vault settings say plainly next to the switch.
 *
 * <p>Everything ML Kit-specific sits behind {@link Engine}, so the rest of Smart Vault - and every
 * test - works with a device that has no Play services at all. Recognition that is unavailable is
 * reported as such and retried later; it never blocks a save or a search.
 */
final class SmartVaultOcr {

    /** The longest side a picture is scaled to before recognition. Screens stay legible. */
    static final int MAX_SIDE = 2560;
    /** The most recognised text one picture contributes. */
    static final int MAX_CHARS = 12000;

    /** Thrown when recognition cannot run yet - Play services missing, or the model downloading. */
    static final class Unavailable extends Exception {
        Unavailable(String message) { super(message); }
    }

    interface Engine {
        String recognize(Context c, Bitmap bitmap) throws Exception;
        /** Asks for the recognition model ahead of time. Never throws. */
        void prepare(Context c);
    }

    private static volatile Engine engine = new MlKitEngine();

    private SmartVaultOcr() {}

    static Engine installForTest(Engine replacement) {
        Engine previous = engine;
        engine = replacement;
        return previous;
    }

    static void prepare(Context c) {
        try { engine.prepare(c); } catch (Throwable ignored) { }
    }

    /** The text in a picture, cleaned and bounded. Empty when there is none. */
    static String recognize(Context c, Bitmap bitmap) throws Exception {
        if (bitmap == null) return "";
        Bitmap scaled = scale(bitmap);
        try {
            return clean(engine.recognize(c, scaled));
        } finally {
            if (scaled != bitmap && !scaled.isRecycled()) scaled.recycle();
        }
    }

    static String clean(String raw) {
        if (raw == null) return "";
        String text = raw.replace('\r', '\n').replaceAll("[ \\t\\x0B\\f]+", " ")
                .replaceAll("\\n{3,}", "\n\n").trim();
        return text.length() <= MAX_CHARS ? text : text.substring(0, MAX_CHARS).trim();
    }

    private static Bitmap scale(Bitmap bitmap) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        int longest = Math.max(w, h);
        if (longest <= MAX_SIDE || longest <= 0) return bitmap;
        float ratio = MAX_SIDE / (float) longest;
        return Bitmap.createScaledBitmap(bitmap, Math.max(1, Math.round(w * ratio)),
                Math.max(1, Math.round(h * ratio)), true);
    }

    /** The production engine: ML Kit through Google Play services. */
    static final class MlKitEngine implements Engine {
        private TextRecognizer recognizer;
        private android.content.Context appContext;

        private synchronized TextRecognizer recognizer() {
            if (recognizer == null) {
                // Orbit removes ML Kit's start-up provider from the manifest, so ML Kit is set up
                // here, the first time a picture is actually read, and never at app launch.
                MlKitContext.initializeIfNeeded(appContext);
                recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
            }
            return recognizer;
        }

        @Override public String recognize(Context c, Bitmap bitmap) throws Exception {
            appContext = c.getApplicationContext();
            try {
                Text result = Tasks.await(recognizer().process(InputImage.fromBitmap(bitmap, 0)),
                        45, TimeUnit.SECONDS);
                return result == null ? "" : result.getText();
            } catch (java.util.concurrent.ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof MlKitException
                        && ((MlKitException) cause).getErrorCode() == MlKitException.UNAVAILABLE) {
                    prepare(c);
                    throw new Unavailable("Text recognition is still being prepared by Google "
                            + "Play services.");
                }
                throw e;
            } catch (NoClassDefFoundError | SecurityException | IllegalStateException e) {
                throw new Unavailable("Text recognition needs Google Play services.");
            }
        }

        @Override public void prepare(Context c) {
            try {
                appContext = c.getApplicationContext();
                ModuleInstall.getClient(c.getApplicationContext()).installModules(
                        ModuleInstallRequest.newBuilder().addApi(recognizer()).build());
            } catch (Throwable ignored) {
                // No Play services, or an old one: recognition reports itself unavailable later.
            }
        }
    }
}
