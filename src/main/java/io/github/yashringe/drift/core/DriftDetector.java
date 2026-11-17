package io.github.yashringe.drift.core;

import io.github.yashringe.drift.cluster.LiveStateFetcher;
import io.github.yashringe.drift.diff.DriftCalculator;
import io.github.yashringe.drift.diff.FieldDrift;
import io.github.yashringe.drift.manifest.DesiredResource;
import io.github.yashringe.drift.manifest.ManifestLoader;
import io.github.yashringe.drift.manifest.ResourceKey;
import io.github.yashringe.drift.report.DriftReport;
import io.github.yashringe.drift.report.DriftReport.ResourceResult;
import io.github.yashringe.drift.report.DriftReport.Status;

import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Loads the manifests, fetches each object from the cluster and builds the report.
 * One object that cannot be read (RBAC, unknown CRD) becomes an ERROR line instead of
 * aborting the whole check, so the rest of the report is still useful.
 */
public final class DriftDetector {

    public record Options(String labelKey, String labelValue) {

        /** Looking for unmanaged objects is optional and needs a label to recognise them. */
        public boolean findObjectsNotInGit() {
            return labelKey != null && !labelKey.isBlank() && labelValue != null && !labelValue.isBlank();
        }
    }

    private final ManifestLoader loader;
    private final LiveStateFetcher cluster;
    private final DriftCalculator calculator;
    private final Options options;
    private final Clock clock;

    public DriftDetector(ManifestLoader loader, LiveStateFetcher cluster, DriftCalculator calculator,
                         Options options, Clock clock) {
        this.loader = loader;
        this.cluster = cluster;
        this.calculator = calculator;
        this.options = options;
        this.clock = clock;
    }

    public DriftReport check(Path manifests) {
        List<DesiredResource> desired = loader.load(manifests);
        List<ResourceResult> results = new ArrayList<>();
        for (DesiredResource resource : desired) {
            results.add(checkOne(resource));
        }
        if (options.findObjectsNotInGit()) {
            results.addAll(findNotInGit(desired));
        }
        results.sort(Comparator.comparing(ResourceResult::resource));
        return new DriftReport(cluster.clusterName(), manifests.toString(), clock.instant(), results);
    }

    private ResourceResult checkOne(DesiredResource resource) {
        ResourceKey key = resource.key();
        String source = resource.source().toString();
        try {
            Optional<Map<String, Object>> live = cluster.fetch(key);
            if (live.isEmpty()) {
                return new ResourceResult(key, Status.MISSING, source, List.of(), null);
            }
            Map<String, Object> wanted = resource.body();
            boolean secret = "Secret".equals(key.kind()) && "v1".equals(key.apiVersion());
            if (secret) {
                wanted = DriftCalculator.normaliseSecret(wanted);
            }
            List<FieldDrift> drifts = calculator.compare(wanted, live.get());
            if (secret) {
                // Never print secret values into a PR comment or CI log.
                drifts = drifts.stream().map(FieldDrift::redacted).toList();
            }
            return new ResourceResult(key, drifts.isEmpty() ? Status.IN_SYNC : Status.DRIFTED, source, drifts, null);
        } catch (RuntimeException e) {
            return new ResourceResult(key, Status.ERROR, source, List.of(), describe(e));
        }
    }

    private List<ResourceResult> findNotInGit(List<DesiredResource> desired) {
        Set<ResourceKey> declared = desired.stream().map(DesiredResource::key).collect(Collectors.toSet());
        Set<ResourceKey> kinds = desired.stream()
                .map(d -> new ResourceKey(d.key().apiVersion(), d.key().kind(), null, null))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> namespaces = desired.stream()
                .map(d -> d.key().namespace())
                .filter(ns -> ns != null)
                .collect(Collectors.toCollection(TreeSet::new));
        try {
            return cluster.listLabelled(kinds, namespaces, options.labelKey(), options.labelValue()).stream()
                    .filter(key -> !declared.contains(key))
                    .map(key -> new ResourceResult(key, Status.NOT_IN_GIT, null, List.of(), null))
                    .toList();
        } catch (RuntimeException e) {
            ResourceKey placeholder = new ResourceKey("-", "label search", null,
                    options.labelKey() + "=" + options.labelValue());
            return List.of(new ResourceResult(placeholder, Status.ERROR, null, List.of(), describe(e)));
        }
    }

    private static String describe(RuntimeException e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
