package io.github.yashringe.drift.report;

import io.github.yashringe.drift.diff.FieldDrift;
import io.github.yashringe.drift.manifest.ResourceKey;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public record DriftReport(String cluster, String manifestPath, Instant checkedAt, List<ResourceResult> resources) {

    public enum Status {
        IN_SYNC("In sync"),
        DRIFTED("Drifted"),
        MISSING("Missing in cluster"),
        NOT_IN_GIT("Not in Git"),
        ERROR("Could not check");

        private final String label;

        Status(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * @param source manifest file, {@code null} for objects that are only in the cluster
     * @param error  reason for status ERROR (for example "forbidden" when RBAC is missing)
     */
    public record ResourceResult(ResourceKey resource, Status status, String source, List<FieldDrift> drifts,
                                 String error) {
        public ResourceResult {
            drifts = drifts == null ? List.of() : List.copyOf(drifts);
        }
    }

    public DriftReport {
        resources = List.copyOf(resources);
    }

    public Map<Status, Long> counts() {
        Map<Status, Long> counts = new EnumMap<>(Status.class);
        for (Status status : Status.values()) {
            counts.put(status, 0L);
        }
        resources.forEach(r -> counts.merge(r.status(), 1L, Long::sum));
        return counts;
    }

    public List<ResourceResult> withStatus(Status status) {
        return resources.stream().filter(r -> r.status() == status).toList();
    }

    public boolean inSync() {
        return resources.stream().allMatch(r -> r.status() == Status.IN_SYNC);
    }

    /** 0 = in sync, 1 = drift found, 2 = some objects could not be checked. Used as the CLI exit code. */
    public int exitCode() {
        if (resources.stream().anyMatch(r -> r.status() == Status.ERROR)) {
            return 2;
        }
        return inSync() ? 0 : 1;
    }
}
