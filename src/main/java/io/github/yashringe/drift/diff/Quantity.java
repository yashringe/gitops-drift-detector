package io.github.yashringe.drift.diff;

import java.math.BigDecimal;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses Kubernetes resource quantities ("500m", "0.5", "1Gi", "1024Mi") into a plain number,
 * so that equal amounts written differently are not reported as drift. The API server
 * sometimes rewrites them, e.g. {@code cpu: 0.5} comes back as {@code 500m}.
 */
public final class Quantity {

    private static final Pattern FORMAT = Pattern.compile("^([+-]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][+-]?\\d+)?)([a-zA-Z]{0,2})$");

    private static final Map<String, BigDecimal> SUFFIXES = Map.ofEntries(
            Map.entry("", BigDecimal.ONE),
            Map.entry("n", new BigDecimal("1e-9")),
            Map.entry("u", new BigDecimal("1e-6")),
            Map.entry("m", new BigDecimal("1e-3")),
            Map.entry("k", new BigDecimal("1e3")),
            Map.entry("M", new BigDecimal("1e6")),
            Map.entry("G", new BigDecimal("1e9")),
            Map.entry("T", new BigDecimal("1e12")),
            Map.entry("P", new BigDecimal("1e15")),
            Map.entry("E", new BigDecimal("1e18")),
            Map.entry("Ki", BigDecimal.valueOf(1L << 10)),
            Map.entry("Mi", BigDecimal.valueOf(1L << 20)),
            Map.entry("Gi", BigDecimal.valueOf(1L << 30)),
            Map.entry("Ti", BigDecimal.valueOf(1L << 40)),
            Map.entry("Pi", BigDecimal.valueOf(1L << 50)),
            Map.entry("Ei", BigDecimal.valueOf(1L << 60)));

    private Quantity() {
    }

    /** Returns the amount in base units, or {@code null} if the text is not a quantity. */
    public static BigDecimal parse(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = FORMAT.matcher(text.strip());
        if (!m.matches()) {
            return null;
        }
        BigDecimal factor = SUFFIXES.get(m.group(2));
        if (factor == null) {
            return null;
        }
        return new BigDecimal(m.group(1)).multiply(factor);
    }

    public static boolean sameAmount(Object a, Object b) {
        BigDecimal x = parse(a == null ? null : a.toString());
        BigDecimal y = parse(b == null ? null : b.toString());
        return x != null && y != null && x.compareTo(y) == 0;
    }
}
