package dev.hermit.reef.shell;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.hermit.common.model.ShellDoc;
import dev.hermit.common.parse.ShellParser;
import dev.hermit.common.validate.ShellDiff;
import dev.hermit.common.validate.ValidationIssue;
import dev.hermit.common.version.SemVer;
import dev.hermit.reef.web.ApiException;
import dev.hermit.reef.web.ReefErrorCode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class ShellCatalogService {

    private final ShellRepository repository;
    private final ShellStorage storage;
    private final ObjectMapper objectMapper;

    public ShellCatalogService(ShellRepository repository, ShellStorage storage, ObjectMapper objectMapper) {
        this.repository = repository;
        this.storage = storage;
        this.objectMapper = objectMapper;
    }

    public List<ShellSummary> list() {
        Map<String, List<VersionRow>> grouped = group(repository.listAll());
        List<ShellSummary> summaries = new ArrayList<>();
        for (List<VersionRow> rows : grouped.values()) {
            VersionRow current = singleCurrent(rows);
            VersionRow source = current != null ? current : highest(rows);
            ShellDoc doc = readDoc(source);
            summaries.add(new ShellSummary(
                    doc.shell(),
                    current == null ? null : current.version(),
                    doc.title(),
                    doc.description(),
                    doc.owner(),
                    doc.tags(),
                    doc.actions().size(),
                    latestPublishedAt(rows)));
        }
        summaries.sort(Comparator.comparing(ShellSummary::shell));
        return summaries;
    }

    public ShellDetail detail(String shell) {
        List<VersionRow> rows = rowsOf(shell);
        VersionRow current = singleCurrent(rows);
        if (current == null) {
            throw notFound();
        }
        return ShellDetail.from(readDoc(current));
    }

    public List<ShellVersionItem> versions(String shell) {
        List<VersionRow> rows = new ArrayList<>(rowsOf(shell));
        if (rows.isEmpty()) {
            throw notFound();
        }
        rows.sort(Comparator.comparing((VersionRow row) -> SemVer.parse(row.version())).reversed());
        List<ShellVersionItem> items = new ArrayList<>();
        for (VersionRow row : rows) {
            List<ValidationIssue> warnings = warnings(row.validationJson());
            items.add(new ShellVersionItem(
                    row.version(),
                    row.status(),
                    row.uploadedBy(),
                    row.uploadedAt(),
                    row.publishedBy(),
                    row.publishedAt(),
                    row.current(),
                    warnings.size(),
                    warnings));
        }
        return items;
    }

    public byte[] yaml(String shell, String version) {
        VersionRow row = requireVersion(shell, version);
        return storage.readYaml(row.shell(), row.version());
    }

    public ShellDiff diff(String shell, String from, String to) {
        if (from == null || to == null || from.isBlank() || to.isBlank()) {
            throw ApiException.invalidInput();
        }
        ShellDoc oldDoc = readDoc(requireVersion(shell, from));
        ShellDoc newDoc = readDoc(requireVersion(shell, to));
        return ShellDiff.between(oldDoc, newDoc);
    }

    private VersionRow requireVersion(String shell, String version) {
        if (version == null || version.isBlank()) {
            throw ApiException.invalidInput();
        }
        SemVer parsed;
        try {
            parsed = SemVer.parse(version);
        } catch (IllegalArgumentException ex) {
            throw notFound();
        }
        return rowsOf(shell).stream()
                .filter(row -> row.version().equals(parsed.toString()))
                .findFirst()
                .orElseThrow(ShellCatalogService::notFound);
    }

    private List<VersionRow> rowsOf(String shell) {
        if (shell == null || shell.isBlank() || shell.length() > 128 || !ShellPaths.singleSegment(shell)) {
            throw notFound();
        }
        return repository.listAll().stream().filter(row -> row.shell().equals(shell)).toList();
    }

    private ShellDoc readDoc(VersionRow row) {
        try {
            return ShellParser.parse(new String(storage.readYaml(row.shell(), row.version()), StandardCharsets.UTF_8));
        } catch (RuntimeException ex) {
            throw new ApiException(500, ReefErrorCode.INTERNAL, ReefErrorCode.INTERNAL.message());
        }
    }

    private List<ValidationIssue> warnings(String validationJson) {
        if (validationJson == null || validationJson.isBlank()) {
            return List.of();
        }
        try {
            List<ValidationIssue> warnings = objectMapper.readValue(validationJson, new TypeReference<List<ValidationIssue>>() {
            });
            return warnings == null ? List.of() : List.copyOf(warnings);
        } catch (IOException ex) {
            throw new ApiException(500, ReefErrorCode.INTERNAL, ReefErrorCode.INTERNAL.message());
        }
    }

    private static Map<String, List<VersionRow>> group(List<VersionRow> rows) {
        Map<String, List<VersionRow>> grouped = new LinkedHashMap<>();
        for (VersionRow row : rows) {
            grouped.computeIfAbsent(row.shell(), ignored -> new ArrayList<>()).add(row);
        }
        return grouped;
    }

    private static VersionRow singleCurrent(List<VersionRow> rows) {
        List<VersionRow> currents = rows.stream().filter(VersionRow::current).toList();
        if (currents.size() > 1) {
            throw new ApiException(500, ReefErrorCode.INTERNAL, ReefErrorCode.INTERNAL.message());
        }
        return currents.isEmpty() ? null : currents.get(0);
    }

    private static VersionRow highest(List<VersionRow> rows) {
        return rows.stream()
                .max(Comparator.comparing(row -> SemVer.parse(row.version())))
                .orElseThrow(ShellCatalogService::notFound);
    }

    private static Instant latestPublishedAt(List<VersionRow> rows) {
        Instant latest = null;
        for (VersionRow row : rows) {
            if (row.publishedAt() != null && (latest == null || row.publishedAt().isAfter(latest))) {
                latest = row.publishedAt();
            }
        }
        return latest;
    }

    private static ApiException notFound() {
        return new ApiException(404, ReefErrorCode.NOT_FOUND, ReefErrorCode.NOT_FOUND.message());
    }
}
