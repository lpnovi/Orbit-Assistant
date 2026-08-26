package com.orbit.assistant;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The measures Orbit can convert between in a kitchen, with exact factors.
 *
 * <p>Every US customary factor here is the legally exact definition rather than a rounded
 * approximation: an inch is 2.54 cm exactly, so a US gallon is exactly 3785.411784 ml and every
 * spoon and cup divides down from it. A pound is exactly 453.59237 g. Kept as
 * {@link KitchenQuantity} rationals so a chain of conversions never accumulates error.
 *
 * <p>Volume and mass are deliberately separate dimensions. Turning cups into grams needs the
 * density of a specific ingredient, which this class does not have and does not guess.
 */
public enum KitchenUnit {

    TSP(Dimension.VOLUME, false, 473176473L, 96000000L, "tsp", "tsp",
            "teaspoon", "teaspoons", "tsp", "tsps", "teaspoonful", "teaspoonfuls"),
    TBSP(Dimension.VOLUME, false, 473176473L, 32000000L, "tbsp", "tbsp",
            "tablespoon", "tablespoons", "tbsp", "tbsps", "tbs", "tblsp", "tablespoonful",
            "tablespoonfuls"),
    FLUID_OUNCE(Dimension.VOLUME, false, 473176473L, 16000000L, "fl oz", "fl oz",
            "fluid ounce", "fluid ounces", "fl oz", "fl ozs", "floz", "fluid oz"),
    CUP(Dimension.VOLUME, false, 473176473L, 2000000L, "cup", "cups",
            "cup", "cups", "cupful", "cupfuls"),
    PINT(Dimension.VOLUME, false, 473176473L, 1000000L, "pint", "pints",
            "pint", "pints", "pt", "pts"),
    QUART(Dimension.VOLUME, false, 473176473L, 500000L, "quart", "quarts",
            "quart", "quarts", "qt", "qts"),
    GALLON(Dimension.VOLUME, false, 473176473L, 125000L, "gallon", "gallons",
            "gallon", "gallons", "gal"),
    MILLILITER(Dimension.VOLUME, true, 1L, 1L, "ml", "ml",
            "ml", "mls", "milliliter", "milliliters", "millilitre", "millilitres"),
    LITER(Dimension.VOLUME, true, 1000L, 1L, "L", "L",
            "l", "liter", "liters", "litre", "litres"),

    GRAM(Dimension.MASS, true, 1L, 1L, "g", "g",
            "g", "gram", "grams", "gramme", "grammes"),
    KILOGRAM(Dimension.MASS, true, 1000L, 1L, "kg", "kg",
            "kg", "kgs", "kilogram", "kilograms", "kilo", "kilos"),
    OUNCE(Dimension.MASS, false, 45359237L, 1600000L, "oz", "oz",
            "ounce", "ounces", "oz", "ozs"),
    POUND(Dimension.MASS, false, 45359237L, 100000L, "lb", "lb",
            "pound", "pounds", "lb", "lbs");

    /** What a unit measures. Conversion is only ever defined inside one dimension. */
    public enum Dimension { VOLUME, MASS }

    private final Dimension dimension;
    private final boolean metric;
    private final KitchenQuantity toBase;
    private final String singular;
    private final String plural;
    private final String[] aliases;

    KitchenUnit(Dimension dimension, boolean metric, long baseNum, long baseDen,
                String singular, String plural, String... aliases) {
        this.dimension = dimension;
        this.metric = metric;
        this.toBase = KitchenQuantity.of(baseNum, baseDen);
        this.singular = singular;
        this.plural = plural;
        this.aliases = aliases;
    }

    public Dimension dimension() {
        return dimension;
    }

    /**
     * Whether the unit belongs to a decimal system.
     *
     * <p>Drives presentation, not arithmetic: 473 ml reads better than 473 4/25 ml, while three
     * quarters of a cup reads far better than 0.75 cups.
     */
    public boolean isMetric() {
        return metric;
    }

    /** How many base units (millilitres for volume, grams for mass) one of this unit is. */
    public KitchenQuantity toBase() {
        return toBase;
    }

    /** The unit as Orbit writes it, matched to the amount in front of it. */
    public String label(KitchenQuantity amount) {
        if (amount == null) return singular;
        return amount.compareTo(KitchenQuantity.ONE) > 0 ? plural : singular;
    }

    // ---- alias lookup -------------------------------------------------------------------------

    /** Every spelling Orbit recognises, longest first so "fl oz" wins over "oz". */
    private static final List<String> ALIASES_BY_LENGTH;
    private static final Map<String, KitchenUnit> BY_ALIAS;

    static {
        Map<String, KitchenUnit> byAlias = new HashMap<>();
        for (KitchenUnit unit : values()) {
            for (String alias : unit.aliases) {
                // The first unit to claim a spelling keeps it. "oz" is a weight in every kitchen;
                // "fl oz" is spelled out when a volume is meant.
                if (!byAlias.containsKey(alias)) byAlias.put(alias, unit);
            }
        }
        List<String> ordered = new ArrayList<>(byAlias.keySet());
        Collections.sort(ordered, (a, b) -> {
            if (a.length() != b.length()) return b.length() - a.length();
            return a.compareTo(b);
        });
        BY_ALIAS = Collections.unmodifiableMap(byAlias);
        ALIASES_BY_LENGTH = Collections.unmodifiableList(ordered);
    }

    /** The unit written as {@code text}, or null when it is not one Orbit measures with. */
    public static KitchenUnit fromAlias(String text) {
        if (text == null) return null;
        String key = text.trim().toLowerCase(Locale.US).replaceAll("\\.", "").replaceAll("\\s+", " ");
        return BY_ALIAS.get(key);
    }

    /** All recognised spellings, longest first, for building a matcher. */
    public static List<String> aliasesLongestFirst() {
        return ALIASES_BY_LENGTH;
    }

    /** A regular-expression alternation of every spelling, longest first. */
    public static String aliasAlternation() {
        StringBuilder out = new StringBuilder();
        for (String alias : ALIASES_BY_LENGTH) {
            if (out.length() > 0) out.append('|');
            out.append(java.util.regex.Pattern.quote(alias));
        }
        return out.toString();
    }

    /** Debug-friendly listing used by tests to prove the table has not silently shrunk. */
    static List<String> allAliases() {
        return Arrays.asList(ALIASES_BY_LENGTH.toArray(new String[0]));
    }
}
