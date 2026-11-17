package io.github.yashringe.drift.report;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.yashringe.drift.config.DriftProperties.Format;
import io.github.yashringe.drift.report.DriftReport.Status;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Turns a report into the configured output format. */
@Component
public class ReportRenderer {

    /** JSON shape: the summary first so scripts can read it with a single jq expression. */
    public record JsonReport(Map<Status, Long> summary, int exitCode, DriftReport report) {
    }

    private final MarkdownReportWriter markdown;
    private final ObjectMapper json;

    public ReportRenderer(MarkdownReportWriter markdown, ObjectMapper objectMapper) {
        this.markdown = markdown;
        this.json = objectMapper.copy().enable(SerializationFeature.INDENT_OUTPUT);
    }

    public String render(DriftReport report, Format format) {
        if (format == Format.JSON) {
            try {
                return json.writeValueAsString(new JsonReport(report.counts(), report.exitCode(), report));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Cannot write JSON report", e);
            }
        }
        return markdown.write(report);
    }
}
