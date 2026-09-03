package com.orbit.assistant;

import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.os.Looper;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Lifecycle safety that is deterministic under Robolectric's intentionally stubbed PdfRenderer. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
@LooperMode(LooperMode.Mode.PAUSED)
public final class PdfRenderEngineTest {
    @Test public void invalidPageBeforeOpenIsReportedAndCloseIsIdempotent() throws Exception {
        AtomicInteger renderErrors = new AtomicInteger();
        PdfRenderEngine engine = new PdfRenderEngine("missing.pdf",
                new PdfRenderEngine.Callback() {
                    @Override public void onOpened(int pageCount) {}
                    @Override public void onRendered(long generation, int page,
                                                     android.graphics.Bitmap bitmap) {}
                    @Override public void onOpenError(String message) {}
                    @Override public void onRenderError(long generation, int page, String message) {
                        renderErrors.incrementAndGet();
                    }
                });
        try {
            engine.render(2L, -1, 800);
            await(() -> renderErrors.get() == 1);
        } finally {
            engine.close();
            engine.close();
        }
    }

    private static void await(BooleanSupplier complete) throws Exception {
        long deadline = System.currentTimeMillis() + 5000L;
        while (!complete.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L);
            shadowOf(Looper.getMainLooper()).idle();
        }
        assertTrue("background renderer did not complete", complete.getAsBoolean());
    }
}
