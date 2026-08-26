package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * What Orbit answers itself, and — far more importantly — what it refuses to answer itself.
 *
 * <p>Kitchen arithmetic has exactly one right answer, so Orbit gives it locally: instantly,
 * offline, and the same whichever AI provider is active. Everything else about cooking does not.
 * Technique, substitutions, what to make tonight, and above all food safety need judgement,
 * context, and current knowledge, and this router stealing one of those questions would replace a
 * good answer with a useless one.
 *
 * <p>So the deferral half of this file is the half that matters. It follows the rule the rest of
 * Orbit's local routing already follows: uncertain means do not intercept.
 */
public final class KitchenRoutingTest {

    private static void answers(String prompt, String expected) {
        assertEquals("Orbit should answer this itself: " + prompt,
                expected, KitchenMathRouter.answer(prompt));
        assertTrue("recognition and answering must agree: " + prompt,
                KitchenMathRouter.canHandle(prompt));
    }

    private static void defersToProvider(String prompt) {
        assertNull("this needs the AI provider, not arithmetic: " + prompt,
                KitchenMathRouter.answer(prompt));
        assertTrue(!KitchenMathRouter.canHandle(prompt));
    }

    // ---- temperature --------------------------------------------------------------------------

    @Test public void ovenTemperaturesConvertLocally() {
        answers("425 F in C", "425°F ≈ 218°C");
        answers("425°F to Celsius", "425°F ≈ 218°C");
        answers("What is 180 Celsius in Fahrenheit?", "180°C = 356°F");
        answers("Convert 350 degrees Fahrenheit to Celsius", "350°F ≈ 177°C");
        answers("What oven temp is 200 C in Fahrenheit?", "200°C = 392°F");
        answers("212f in c", "212°F = 100°C");
        answers("-40 f in c", "-40°F = -40°C");
        answers("98.6 F in C", "98.6°F = 37°C");
    }

    /** A number and the word "degrees" is not a request to convert anything. */
    @Test public void aTemperatureWithNoTargetIsLeftAlone() {
        defersToProvider("350 degrees");
        defersToProvider("Preheat to 200 C");
        defersToProvider("It is 30 degrees outside");
        defersToProvider("Set the oven to 425");
        defersToProvider("180 C in Celsius");
    }

    // ---- volume -------------------------------------------------------------------------------

    @Test public void cookingVolumesConvertLocally() {
        answers("How many tablespoons are in 1/3 cup?", "1/3 cup = 5 tbsp + 1 tsp");
        answers("Convert 2 cups to ml", "2 cups ≈ 473 ml");
        answers("How many teaspoons is 1.5 tablespoons?", "1 1/2 tbsp = 4 1/2 tsp");
        answers("6 fl oz in cups", "6 fl oz = 3/4 cup");
        answers("750 ml in cups", "750 ml ≈ 3.17 cups");
        answers("How many tbsp in half a cup?", "1/2 cup = 8 tbsp");
        answers("How many tablespoons are in a cup?", "1 cup = 16 tbsp");
        answers("2 pints to quarts", "2 pints = 1 quart");
        answers("1 quart in cups", "1 quart = 4 cups");
    }

    @Test public void unicodeAndWrittenFractionsAreUnderstood() {
        answers("½ cup in ml", "1/2 cup ≈ 118 ml");
        answers("1½ cups to ml", "1 1/2 cups ≈ 355 ml");
        answers("¾ cup in tbsp", "3/4 cup = 12 tbsp");
        answers("⅔ cup in tablespoons", "2/3 cup = 10 tbsp + 2 tsp");
        answers("two cups in ml", "2 cups ≈ 473 ml");
        answers("three quarters of a cup in tbsp", "3/4 cup = 12 tbsp");
    }

    // ---- mass ---------------------------------------------------------------------------------

    @Test public void cookingWeightsConvertLocally() {
        answers("12 oz in grams", "12 oz ≈ 340 g");
        answers("1.5 pounds to grams", "1 1/2 lb ≈ 680 g");
        answers("500g in pounds", "500 g ≈ 1.1 lb");
        answers("Convert 1 kg to lbs", "1 kg ≈ 2.2 lb");
    }

    // ---- scaling ------------------------------------------------------------------------------

    @Test public void quantitiesScaleLocally() {
        answers("Double 3/4 cup", "1 1/2 cups");
        answers("Half 2 tablespoons", "1 tbsp");
        answers("Scale 1 1/2 cups by 1.5", "2 1/4 cups");
        answers("3/4 cup times 1.5", "1 1/8 cups");
        answers("Triple 1/4 cup", "3/4 cup");
    }

    @Test public void servingCountsScaleLocally() {
        answers("This recipe serves 6 but I need 4. Scale 3 cups", "2 cups");
        answers("A recipe uses 2 tbsp for 4 people, how much for 6?", "3 tbsp");
    }

    // ---- the thing the deterministic layer knows it cannot do -----------------------------------

    /**
     * A cup of flour, a cup of sugar and a cup of honey weigh three different amounts. With no
     * ingredient named there is no right answer, and assuming water would be wrong nearly every
     * time, so Orbit says what is missing instead of inventing a density.
     */
    @Test public void volumeIntoWeightWithNoIngredientAsksForTheIngredient() {
        answers("How many grams is one cup?", KitchenMathRouter.INGREDIENT_NEEDED);
        answers("2 cups to grams", KitchenMathRouter.INGREDIENT_NEEDED);
        answers("Convert 250 ml to grams", KitchenMathRouter.INGREDIENT_NEEDED);
        answers("How many cups is 500 g?", KitchenMathRouter.INGREDIENT_NEEDED);
    }

    /** Once the ingredient is named the question is real, and it belongs to the AI provider. */
    @Test public void volumeIntoWeightWithAnIngredientGoesToTheProvider() {
        defersToProvider("How many grams is a cup of flour?");
        defersToProvider("Convert 2 cups of sugar to grams");
        defersToProvider("2 cups of water in grams");
        defersToProvider("How many grams is a cup of brown sugar?");
    }

    // ---- restraint ------------------------------------------------------------------------------

    /** Cooking questions that need reasoning, taste, or knowledge Orbit does not compute. */
    @Test public void realCookingQuestionsReachTheProvider() {
        defersToProvider("Why does my steak taste metallic?");
        defersToProvider("Should I use butter or olive oil?");
        defersToProvider("How do I make risotto?");
        defersToProvider("Can I substitute yogurt for sour cream?");
        defersToProvider("What should I cook tonight?");
        defersToProvider("How long should I cook this chicken?");
        defersToProvider("Is this meat safe to eat?");
        defersToProvider("What temperature should chicken be cooked to?");
        defersToProvider("How many cups of flour does a loaf need?");
    }

    /** Numbers and units turn up constantly outside a kitchen. None of these are conversions. */
    @Test public void ordinaryNumbersAndUnitsDoNotTriggerTheRouter() {
        defersToProvider("I ran 5 miles today");
        defersToProvider("The download is 500 MB");
        defersToProvider("Add 2 cups to the bowl");
        defersToProvider("I have a cup of coffee");
        defersToProvider("Send 2 g of data");
        defersToProvider("Book a table for 4 people");
        defersToProvider("The recipe makes 12 cookies");
    }

    /** Anything that belongs to Orbit's device commands stays a device command. */
    @Test public void deviceCommandsAreNeverAnsweredAsArithmetic() {
        defersToProvider("Set a timer for 10 minutes");
        defersToProvider("Set a steak timer for 4 minutes");
        defersToProvider("Turn the volume up to 50");
        defersToProvider("Set brightness to 40");
        defersToProvider("Remind me in 2 hours");
        defersToProvider("Set a timer for 2 cups");
    }

    /** A long message is a conversation that mentions a measurement, not a request to convert one. */
    @Test public void aLongMessageIsNotIntercepted() {
        defersToProvider("I am halfway through a bread recipe and it says to use 2 cups to ml "
                + "of water but my scale is broken and I am not sure what to do about any of it");
    }

    @Test public void emptyAndMeaninglessInputIsIgnored() {
        defersToProvider("");
        defersToProvider("   ");
        assertNull(KitchenMathRouter.answer(null));
        defersToProvider("cups");
        defersToProvider("convert");
    }
}
