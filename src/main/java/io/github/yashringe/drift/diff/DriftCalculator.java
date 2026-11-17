package io.github.yashringe.drift.diff;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Compares what Git declares with what the cluster has, field by field.
 *
 * <h2>Rules</h2>
 * <ul>
 *   <li><b>Only fields written in Git are compared.</b> The API server fills in many defaults
 *       ({@code revisionHistoryLimit}, {@code terminationGracePeriodSeconds}, ...). Those are not
 *       drift, so live-only fields are ignored. This is the same idea as Server-Side Apply's
 *       "fields I own".</li>
 *   <li><b>Lists of named objects are matched by key</b> (containers by {@code name}, ports by
 *       {@code containerPort}, ...), not by position. Reordering containers is not a change.</li>
 *   <li><b>Lists of plain values</b> ({@code args}, {@code command}) must match exactly, order included.</li>
 *   <li><b>Equal values written differently are equal:</b> {@code 8080} vs {@code "8080"},
 *       {@code cpu: 0.5} vs {@code 500m}, {@code 1Gi} vs {@code 1024Mi}, empty map vs missing.</li>
 *   <li>Server-managed metadata and {@code status} are never compared.</li>
 * </ul>
 */
public final class DriftCalculator {

    /** Candidate keys for matching list elements, in order of preference. */
    private static final List<String> MERGE_KEYS = List.of("name", "containerPort", "port", "mountPath", "devicePath", "ip");

    private static final List<String> ALWAYS_IGNORED = List.of(
            "status",
            "metadata.uid",
            "metadata.resourceVersion",
            "metadata.generation",
            "metadata.creationTimestamp",
            "metadata.managedFields",
            "metadata.selfLink",
            "metadata.annotations[\"kubectl.kubernetes.io/last-applied-configuration\"]",
            "metadata.annotations[\"deployment.kubernetes.io/revision\"]");

    private static final Pattern SIMPLE_KEY = Pattern.compile("[A-Za-z0-9_-]+");
    private static final Pattern RESOURCE_PATH = Pattern.compile(".*resources\\.(requests|limits|claims)\\..*|.*\\.hard\\..*");

    private final List<String> ignoredPaths;

    /** @param extraIgnoredPaths path prefixes to skip, e.g. {@code spec.replicas} when an HPA scales the Deployment */
    public DriftCalculator(List<String> extraIgnoredPaths) {
        List<String> all = new ArrayList<>(ALWAYS_IGNORED);
        if (extraIgnoredPaths != null) {
            all.addAll(extraIgnoredPaths);
        }
        this.ignoredPaths = List.copyOf(all);
    }

    public List<FieldDrift> compare(Map<String, Object> desired, Map<String, Object> live) {
        List<FieldDrift> drifts = new ArrayList<>();
        compareValues("", desired, live, drifts);
        return drifts;
    }

    /**
     * A Secret may be written with {@code stringData} (plain text) but the API server only
     * returns {@code data} (base64). Convert so both sides use {@code data}.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> normaliseSecret(Map<String, Object> secret) {
        Object stringData = secret.get("stringData");
        if (!(stringData instanceof Map<?, ?> plain) || plain.isEmpty()) {
            return secret;
        }
        Map<String, Object> copy = new LinkedHashMap<>(secret);
        Map<String, Object> data = secret.get("data") instanceof Map<?, ?> d
                ? new LinkedHashMap<>((Map<String, Object>) d) : new LinkedHashMap<>();
        plain.forEach((k, v) -> data.put(String.valueOf(k),
                Base64.getEncoder().encodeToString(String.valueOf(v).getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        copy.remove("stringData");
        copy.put("data", data);
        return copy;
    }

    @SuppressWarnings("unchecked")
    private void compareValues(String path, Object desired, Object live, List<FieldDrift> out) {
        if (isIgnored(path)) {
            return;
        }
        if (isEmpty(desired)) {
            if (!isEmpty(live) && desired != null) {
                // Git explicitly says "empty" (e.g. labels: {}) but the cluster has values.
                out.add(new FieldDrift(path, render(desired), render(live)));
            }
            return;
        }
        if (desired instanceof Map<?, ?> desiredMap) {
            if (!(live instanceof Map<?, ?> liveMap)) {
                out.add(new FieldDrift(path, render(desired), render(live)));
                return;
            }
            for (Map.Entry<?, ?> entry : desiredMap.entrySet()) {
                String key = String.valueOf(entry.getKey());
                compareValues(child(path, key), entry.getValue(), liveMap.get(key), out);
            }
            return;
        }
        if (desired instanceof List<?> desiredList) {
            if (!(live instanceof List<?> liveList)) {
                out.add(new FieldDrift(path, render(desired), render(live)));
                return;
            }
            compareLists(path, (List<Object>) desiredList, (List<Object>) liveList, out);
            return;
        }
        if (!scalarEquals(path, desired, live)) {
            out.add(new FieldDrift(path, render(desired), render(live)));
        }
    }

    private void compareLists(String path, List<Object> desired, List<Object> live, List<FieldDrift> out) {
        String mergeKey = mergeKeyFor(desired);
        if (mergeKey != null) {
            for (Object element : desired) {
                Map<?, ?> desiredElement = (Map<?, ?>) element;
                String id = scalarText(desiredElement.get(mergeKey));
                Object match = live.stream()
                        .filter(l -> l instanceof Map<?, ?> m && id.equals(scalarText(m.get(mergeKey))))
                        .findFirst()
                        .orElse(null);
                String elementPath = path + "[" + mergeKey + "=" + id + "]";
                if (match == null) {
                    out.add(new FieldDrift(elementPath, render(element), null));
                } else {
                    compareValues(elementPath, element, match, out);
                }
            }
            return;
        }
        boolean scalars = desired.stream().noneMatch(e -> e instanceof Map<?, ?> || e instanceof List<?>);
        if (scalars) {
            List<String> d = desired.stream().map(DriftCalculator::scalarText).toList();
            List<String> l = live.stream().map(DriftCalculator::scalarText).toList();
            if (!d.equals(l)) {
                out.add(new FieldDrift(path, render(desired), render(live)));
            }
            return;
        }
        // Unkeyed objects (e.g. tolerations): compare by position.
        for (int i = 0; i < desired.size(); i++) {
            Object liveElement = i < live.size() ? live.get(i) : null;
            compareValues(path + "[" + i + "]", desired.get(i), liveElement, out);
        }
    }

    /** The first candidate key that every element of the list has, or {@code null}. */
    private static String mergeKeyFor(List<Object> list) {
        if (list.isEmpty() || !list.stream().allMatch(e -> e instanceof Map<?, ?>)) {
            return null;
        }
        for (String key : MERGE_KEYS) {
            if (list.stream().allMatch(e -> ((Map<?, ?>) e).get(key) != null)) {
                return key;
            }
        }
        return null;
    }

    private static boolean scalarEquals(String path, Object desired, Object live) {
        if (live == null) {
            return false;
        }
        if (Objects.equals(scalarText(desired), scalarText(live))) {
            return true;
        }
        return RESOURCE_PATH.matcher(path).matches() && Quantity.sameAmount(desired, live);
    }

    /**
     * Numbers are compared by value, so {@code 8080}, {@code 8080.0} and the string {@code "8080"}
     * are the same. Strings with a decimal point stay text: "1.10" and "1.1" are different
     * version labels, not the same number.
     */
    static String scalarText(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            try {
                return new BigDecimal(number.toString()).stripTrailingZeros().toPlainString();
            } catch (NumberFormatException e) {
                return number.toString(); // NaN or Infinity
            }
        }
        String text = value.toString();
        if (text.matches("[+-]?\\d{1,18}")) {
            return Long.toString(Long.parseLong(text));
        }
        return text;
    }

    private static boolean isEmpty(Object value) {
        return value == null
                || (value instanceof Map<?, ?> m && m.isEmpty())
                || (value instanceof List<?> l && l.isEmpty());
    }

    private boolean isIgnored(String path) {
        for (String ignored : ignoredPaths) {
            if (path.equals(ignored) || path.startsWith(ignored + ".") || path.startsWith(ignored + "[")) {
                return true;
            }
        }
        return false;
    }

    /** Keys with dots or slashes (annotation and label keys) are written in brackets. */
    static String child(String path, String key) {
        String segment = SIMPLE_KEY.matcher(key).matches() ? key : "[\"" + key + "\"]";
        if (path.isEmpty()) {
            return segment;
        }
        return segment.startsWith("[") ? path + segment : path + "." + segment;
    }

    static String render(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> map) {
            return map.entrySet().stream()
                    .map(e -> e.getKey() + ": " + render(e.getValue()))
                    .collect(Collectors.joining(", ", "{", "}"));
        }
        if (value instanceof List<?> list) {
            return list.stream().map(DriftCalculator::render).collect(Collectors.joining(", ", "[", "]"));
        }
        return scalarText(value);
    }
}
