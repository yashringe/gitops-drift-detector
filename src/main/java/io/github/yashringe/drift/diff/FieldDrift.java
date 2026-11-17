package io.github.yashringe.drift.diff;

/**
 * One field whose live value differs from Git.
 *
 * @param path    e.g. {@code spec.template.spec.containers[name=web].image}
 * @param desired value in Git, rendered as text
 * @param live    value in the cluster, or {@code null} if the field is missing there
 */
public record FieldDrift(String path, String desired, String live) {

    public FieldDrift redacted() {
        return new FieldDrift(path, desired == null ? null : "<redacted>", live == null ? null : "<redacted>");
    }
}
