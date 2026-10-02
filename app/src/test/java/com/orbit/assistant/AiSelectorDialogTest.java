package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.view.View;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** The shared provider/model/strength picker shows only what is legal and updates in place. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class AiSelectorDialogTest {
    private Activity activity;

    @Before public void setUp() {
        Prefs.get(RuntimeEnvironment.getApplication()).edit().clear().commit();
        activity = Robolectric.buildActivity(Activity.class).setup().get();
    }

    @Test public void lunaOffersEveryStrengthAndSolNeverOffersNone() {
        AiSelectorDialog picker = AiSelectorDialog.forTest(activity,
                AiSelection.of(Prefs.PROVIDER_CHATGPT, OrbitModelCatalog.LUNA, AiStrength.NONE));
        assertEquals(Arrays.asList(AiStrength.values()), picker.offeredStrengths());
        picker.chooseModel(OrbitModelCatalog.SOL, null);
        assertEquals("None is not shown disabled; it is simply absent",
                Arrays.asList(AiStrength.LOW, AiStrength.MEDIUM, AiStrength.HIGH, AiStrength.XHIGH,
                        AiStrength.MAX), picker.offeredStrengths());
        assertEquals("and the choice lands visibly on Low", AiStrength.LOW, picker.current().strength);
    }

    @Test public void theModelListIsTheCatalogsAndRowsAreNeverRebuiltByAChoice() {
        AiSelectorDialog picker = AiSelectorDialog.forTest(activity, AiSelections.FALLBACK);
        assertEquals(Arrays.asList(OrbitModelCatalog.LUNA, OrbitModelCatalog.SOL, OrbitModelCatalog.ASTRA),
                picker.offeredModels());
        List<View> before = new ArrayList<>(picker.modelRowsForTest());
        picker.chooseModel(OrbitModelCatalog.ASTRA, null);
        picker.chooseStrength(AiStrength.MAX, null);
        for (int i = 0; i < before.size(); i++) assertSame(before.get(i), picker.modelRowsForTest().get(i));
        assertTrue(picker.modelRowsForTest().get(2).isSelected());
        assertTrue(picker.modelRowsForTest().get(2).getContentDescription().toString().endsWith("Selected"));
        assertEquals(AiSelection.of(Prefs.PROVIDER_CHATGPT, OrbitModelCatalog.ASTRA, AiStrength.MAX),
                picker.current());
    }

    @Test public void aKnownUnavailableModelIsLabelledButStillSelectable() {
        ModelAvailability.markUnavailable(activity, Prefs.PROVIDER_CHATGPT, OrbitModelCatalog.ASTRA);
        AiSelectorDialog picker = AiSelectorDialog.forTest(activity, AiSelections.FALLBACK);
        String description = picker.modelRowsForTest().get(2).getContentDescription().toString();
        assertTrue(description.startsWith("GPT-6 Astra"));
        picker.chooseModel(OrbitModelCatalog.ASTRA, null);
        assertEquals(OrbitModelCatalog.ASTRA, picker.current().model);
        ModelAvailability.markAvailable(activity, Prefs.PROVIDER_CHATGPT, OrbitModelCatalog.ASTRA);
    }

    @Test public void orbitLocalShowsNoStrengthControls() {
        AiSelectorDialog picker = AiSelectorDialog.forTest(activity,
                AiSelection.of(Prefs.PROVIDER_LOCAL, OrbitModelCatalog.ORBIT_LOCAL, null));
        assertTrue(picker.offeredStrengths().isEmpty());
    }
}
