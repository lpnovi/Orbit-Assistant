package com.orbit.assistant;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The everyday measures Orbit converts outside the kitchen, with exact factors.
 *
 * <p>Deliberately a sibling of {@link KitchenUnit} rather than a replacement for it. The kitchen
 * table owns cooking volume and mass, where the presentation rules are genuinely different — a cup
 * reads as {@code 3/4}, never as {@code 0.75} — and it keeps owning them. This table owns the
 * dimensions cooking has no opinion about: length, speed, area, and digital storage. There is no
 * overlapping spelling between the two, which is asserted by a test rather than left to memory, so
 * a request only ever reaches one of them.
 *
 * <p>Both share {@link KitchenQuantity}, so there is one exact rational in Orbit and not two
 * numeric worlds that round differently. Every factor here is the legal definition rather than an
 * approximation: an inch is exactly 2.54 cm, a mile exactly 1609.344 m, an acre exactly
 * 4046.8564224 m squared.
 *
 * <p>Digital storage carries the distinction most software pretends does not exist. {@code KB},
 * {@code MB}, {@code GB} and {@code TB} are SI decimal powers of a thousand; {@code KiB},
 * {@code MiB}, {@code GiB} and {@code TiB} are binary powers of 1024. They are different units and
 * Orbit converts between them rather than treating them as the same thing.
 */
public enum MeasureUnit {

    // ---- length, base: metre ------------------------------------------------------------------

    MILLIMETER(Dimension.LENGTH, 1L, 1000L, "mm", "mm",
            "mm", "millimeter", "millimeters", "millimetre", "millimetres"),
    CENTIMETER(Dimension.LENGTH, 1L, 100L, "cm", "cm",
            "cm", "centimeter", "centimeters", "centimetre", "centimetres"),
    METER(Dimension.LENGTH, 1L, 1L, "m", "m",
            "m", "meter", "meters", "metre", "metres"),
    KILOMETER(Dimension.LENGTH, 1000L, 1L, "km", "km",
            "km", "kilometer", "kilometers", "kilometre", "kilometres"),
    INCH(Dimension.LENGTH, 254L, 10000L, "in", "in",
            "in", "inch", "inches", "ins"),
    FOOT(Dimension.LENGTH, 3048L, 10000L, "ft", "ft",
            "ft", "foot", "feet", "fts"),
    YARD(Dimension.LENGTH, 9144L, 10000L, "yd", "yd",
            "yd", "yard", "yards", "yds"),
    MILE(Dimension.LENGTH, 1609344L, 1000L, "mi", "mi",
            "mi", "mile", "miles"),

    // ---- speed, base: metre per second ---------------------------------------------------------

    METERS_PER_SECOND(Dimension.SPEED, 1L, 1L, "m/s", "m/s",
            "m/s", "mps", "meters per second", "meter per second",
            "metres per second", "metre per second"),
    KILOMETERS_PER_HOUR(Dimension.SPEED, 5L, 18L, "km/h", "km/h",
            "km/h", "kmh", "kph", "kilometers per hour", "kilometer per hour",
            "kilometres per hour", "kilometre per hour"),
    MILES_PER_HOUR(Dimension.SPEED, 44704L, 100000L, "mph", "mph",
            "mph", "miles per hour", "mile per hour"),

    // ---- area, base: square metre ----------------------------------------------------------------

    SQUARE_MILLIMETER(Dimension.AREA, 1L, 1000000L, "mm2", "mm2",
            "mm2", "sq mm", "square millimeter", "square millimeters",
            "square millimetre", "square millimetres"),
    SQUARE_CENTIMETER(Dimension.AREA, 1L, 10000L, "cm2", "cm2",
            "cm2", "sq cm", "square centimeter", "square centimeters",
            "square centimetre", "square centimetres"),
    SQUARE_METER(Dimension.AREA, 1L, 1L, "m2", "m2",
            "m2", "sq m", "sqm", "square meter", "square meters",
            "square metre", "square metres"),
    SQUARE_KILOMETER(Dimension.AREA, 1000000L, 1L, "km2", "km2",
            "km2", "sq km", "square kilometer", "square kilometers",
            "square kilometre", "square kilometres"),
    SQUARE_INCH(Dimension.AREA, 64516L, 100000000L, "sq in", "sq in",
            "sq in", "in2", "square inch", "square inches"),
    SQUARE_FOOT(Dimension.AREA, 9290304L, 100000000L, "sq ft", "sq ft",
            "sq ft", "ft2", "square foot", "square feet"),
    SQUARE_YARD(Dimension.AREA, 83612736L, 100000000L, "sq yd", "sq yd",
            "sq yd", "yd2", "square yard", "square yards"),
    ACRE(Dimension.AREA, 40468564224L, 10000000L, "acre", "acres",
            "acre", "acres"),
    HECTARE(Dimension.AREA, 10000L, 1L, "ha", "ha",
            "ha", "hectare", "hectares"),

    // ---- digital storage, base: byte -------------------------------------------------------------

    BYTE(Dimension.DATA, 1L, 1L, "B", "B",
            "b", "byte", "bytes"),
    KILOBYTE(Dimension.DATA, 1000L, 1L, "KB", "KB",
            "kb", "kilobyte", "kilobytes"),
    MEGABYTE(Dimension.DATA, 1000000L, 1L, "MB", "MB",
            "mb", "megabyte", "megabytes"),
    GIGABYTE(Dimension.DATA, 1000000000L, 1L, "GB", "GB",
            "gb", "gigabyte", "gigabytes"),
    TERABYTE(Dimension.DATA, 1000000000000L, 1L, "TB", "TB",
            "tb", "terabyte", "terabytes"),
    KIBIBYTE(Dimension.DATA, 1024L, 1L, "KiB", "KiB",
            "kib", "kibibyte", "kibibytes"),
    MEBIBYTE(Dimension.DATA, 1048576L, 1L, "MiB", "MiB",
            "mib", "mebibyte", "mebibytes"),
    GIBIBYTE(Dimension.DATA, 1073741824L, 1L, "GiB", "GiB",
            "gib", "gibibyte", "gibibytes"),
    TEBIBYTE(Dimension.DATA, 1099511627776L, 1L, "TiB", "TiB",
            "tib", "tebibyte", "tebibytes");

    /** What a unit measures. Conversion is only ever defined inside one dimension. */
    public enum Dimension { LENGTH, SPEED, AREA, DATA }

    private final Dimension dimension;
    private final KitchenQuantity toBase;
    private final String singular;
    private final String plural;
    private final String[] aliases;

    MeasureUnit(Dimension dimension, long baseNum, long baseDen,
                String singular, String plural, String... aliases) {
        this.dimension = dimension;
        this.toBase = KitchenQuantity.of(baseNum, baseDen);
        this.singular = singular;
        this.plural = plural;
        this.aliases = aliases;
    }

    public Dimension dimension() { return dimension; }

    /** How many base units (metres, m/s, square metres, or bytes) one of this unit is. */
    public KitchenQuantity toBase() { return toBase; }

    /** True for the SI decimal storage units, whose powers of a thousand are worth stating. */
    public boolean isDecimalData() {
        return this == KILOBYTE || this == MEGABYTE || this == GIGABYTE || this == TERABYTE;
    }

    /** True for the binary storage units, whose powers of 1024 are worth stating. */
    public boolean isBinaryData() {
        return this == KIBIBYTE || this == MEBIBYTE || this == GIBIBYTE || this == TEBIBYTE;
    }

    /** The unit as Orbit writes it, matched to the amount in front of it. */
    public String label(KitchenQuantity amount) {
        if (amount == null) return singular;
        return amount.compareTo(KitchenQuantity.ONE) > 0 ? plural : singular;
    }

    // ---- alias lookup ---------------------------------------------------------------------------

    private static final List<String> ALIASES_BY_LENGTH;
    private static final Map<String, MeasureUnit> BY_ALIAS;

    static {
        Map<String, MeasureUnit> byAlias = new HashMap<>();
        for (MeasureUnit unit : values()) {
            for (String alias : unit.aliases) {
                // The first unit to claim a spelling keeps it, exactly as KitchenUnit does.
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
    public static MeasureUnit fromAlias(String text) {
        if (text == null) return null;
        String key = text.trim().toLowerCase(Locale.US)
                .replace(".", "").replaceAll("\\s+", " ");
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
}
