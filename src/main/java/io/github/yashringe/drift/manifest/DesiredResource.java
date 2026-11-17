package io.github.yashringe.drift.manifest;

import java.nio.file.Path;
import java.util.Map;

/**
 * An object as declared in Git.
 *
 * @param body   the parsed YAML document exactly as written (no defaults added)
 * @param source file it came from, shown in the report
 */
public record DesiredResource(ResourceKey key, Map<String, Object> body, Path source) {
}
