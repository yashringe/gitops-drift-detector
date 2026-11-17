package io.github.yashringe.drift.report;

import io.github.yashringe.drift.diff.FieldDrift;
import io.github.yashringe.drift.report.DriftReport.ResourceResult;
import io.github.yashringe.drift.report.DriftReport.Status;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Renders the report as GitHub-flavoured Markdown, for a pull request comment or the job summary.
 * The hidden marker on the first line lets a workflow find and update its earlier comment
 * instead of adding a new one on every push.
 */
public final class MarkdownReportWriter {

    public static final String MARKER = "<!-- gitops-drift-report -->";
    private static final int MAX_VALUE_LENGTH = 120;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'")
            .withZone(ZoneOffset.UTC);

    public String write(DriftReport report) {
        StringBuilder md = new StringBuilder();
        md.append(MARKER).append('\n');
        md.append("## GitOps drift report\n\n");
        md.append("Cluster `").append(report.cluster()).append("` compared with `").append(report.manifestPath())
                .append("` at ").append(TIME.format(report.checkedAt())).append(".\n\n");

        if (report.inSync()) {
            md.append("**No drift.** All ").append(report.resources().size())
                    .append(" objects match their manifests.\n");
            return md.toString();
        }

        md.append("| Status | Objects |\n|---|---:|\n");
        for (Map.Entry<Status, Long> entry : report.counts().entrySet()) {
            if (entry.getValue() > 0) {
                md.append("| ").append(entry.getKey().label()).append(" | ").append(entry.getValue()).append(" |\n");
            }
        }
        md.append('\n');

        List<ResourceResult> drifted = report.withStatus(Status.DRIFTED);
        if (!drifted.isEmpty()) {
            md.append("### Drifted\n\n");
            for (ResourceResult result : drifted) {
                int fields = result.drifts().size();
                md.append("<details><summary><code>").append(html(result.resource().display())).append("</code>: ")
                        .append(fields).append(fields == 1 ? " field" : " fields").append("</summary>\n\n");
                md.append("Source: `").append(result.source()).append("`\n\n");
                md.append("| Field | Git | Cluster |\n|---|---|---|\n");
                for (FieldDrift drift : result.drifts()) {
                    md.append("| `").append(cell(drift.path())).append("` | ")
                            .append(value(drift.desired())).append(" | ")
                            .append(drift.live() == null ? "_missing_" : value(drift.live())).append(" |\n");
                }
                md.append("\n</details>\n\n");
            }
        }
        list(md, report, Status.MISSING, "Missing in cluster", true);
        list(md, report, Status.NOT_IN_GIT, "Not in Git (labelled as managed, but no manifest)", false);

        List<ResourceResult> errors = report.withStatus(Status.ERROR);
        if (!errors.isEmpty()) {
            md.append("### Could not check\n\n");
            errors.forEach(r -> md.append("- `").append(r.resource().display()).append("`: ")
                    .append(cell(r.error())).append('\n'));
            md.append('\n');
        }
        return md.toString();
    }

    private static void list(StringBuilder md, DriftReport report, Status status, String title, boolean withSource) {
        List<ResourceResult> results = report.withStatus(status);
        if (results.isEmpty()) {
            return;
        }
        md.append("### ").append(title).append("\n\n");
        for (ResourceResult r : results) {
            md.append("- `").append(r.resource().display()).append('`');
            if (withSource && r.source() != null) {
                md.append(" (from `").append(r.source()).append("`)");
            }
            md.append('\n');
        }
        md.append('\n');
    }

    private static String value(String text) {
        if (text == null) {
            return "_not set_";
        }
        String shortened = text.length() > MAX_VALUE_LENGTH ? text.substring(0, MAX_VALUE_LENGTH - 1) + "…" : text;
        return "`" + cell(shortened) + "`";
    }

    /** Pipes and line breaks would break the Markdown table; backticks would end the code span. */
    private static String cell(String text) {
        return text.replace("|", "\\|").replace("\r", "").replace("\n", " ").replace("`", "'");
    }

    private static String html(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
