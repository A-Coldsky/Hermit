package dev.hermit.reef.conch;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.hermit.common.model.ConchDoc;
import dev.hermit.common.parse.ConchParser;
import dev.hermit.common.validate.ConchDiff;
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
public class ConchCatalogService {

    private final ConchRepository repository;
    private final ConchAvailabilityRepository availability;
    private final ConchStorage storage;
    private final ObjectMapper objectMapper;

    public ConchCatalogService(ConchRepository repository, ConchAvailabilityRepository availability,
            ConchStorage storage, ObjectMapper objectMapper) {
        this.repository = repository;
        this.availability = availability;
        this.storage = storage;
        this.objectMapper = objectMapper;
    }

    public List<ConchSummary> list() {
        Map<String, List<VersionRow>> grouped = group(repository.listAll());
        Map<String, AvailabilityRow> flags = availability.all();
        List<ConchSummary> summaries = new ArrayList<>();
        for (List<VersionRow> rows : grouped.values()) {
            VersionRow current = singleCurrent(rows);
            VersionRow source = current != null ? current : highest(rows);
            ConchDoc doc = readDoc(source);
            AvailabilityRow flag = flags.get(doc.conch());
            boolean disabled = flag != null && flag.disabled();
            summaries.add(new ConchSummary(
                    doc.conch(),
                    current == null ? null : current.version(),
                    doc.title(),
                    doc.description(),
                    doc.owner(),
                    doc.tags(),
                    doc.actions().size(),
                    latestPublishedAt(rows),
                    disabled,
                    disabled ? flag.disabledAt() : null,
                    disabled ? flag.disabledBy() : null,
                    disabled ? flag.disabledReason() : null,
                    pullTotal(rows)));
        }
        summaries.sort(Comparator.comparing(ConchSummary::conch));
        return summaries;
    }

    public ConchDetail detail(String conch) {
        List<VersionRow> rows = rowsOf(conch);
        VersionRow current = singleCurrent(rows);
        if (current == null) {
            throw notFound();
        }
        return ConchDetail.from(readDoc(current), availability.find(current.conch()), pullTotal(rows));
    }

    public List<ConchVersionItem> versions(String conch) {
        List<VersionRow> rows = new ArrayList<>(rowsOf(conch));
        if (rows.isEmpty()) {
            throw notFound();
        }
        rows.sort(Comparator.comparing((VersionRow row) -> SemVer.parse(row.version())).reversed());
        List<ConchVersionItem> items = new ArrayList<>();
        for (VersionRow row : rows) {
            List<ValidationIssue> warnings = warnings(row.validationJson());
            items.add(new ConchVersionItem(
                    row.version(),
                    row.status(),
                    row.uploadedBy(),
                    row.uploadedAt(),
                    row.publishedBy(),
                    row.publishedAt(),
                    row.current(),
                    warnings.size(),
                    warnings,
                    row.pullCount()));
        }
        return items;
    }

    public byte[] yaml(String conch, String version) {
        VersionRow row = requireVersion(conch, version);
        return storage.readYaml(row.conch(), row.version());
    }

    public ConchDiff diff(String conch, String from, String to) {
        if (from == null || to == null || from.isBlank() || to.isBlank()) {
            throw ApiException.invalidInput();
        }
        ConchDoc oldDoc = readDoc(requireVersion(conch, from));
        ConchDoc newDoc = readDoc(requireVersion(conch, to));
        return ConchDiff.between(oldDoc, newDoc);
    }

    private VersionRow requireVersion(String conch, String version) {
        if (version == null || version.isBlank()) {
            throw ApiException.invalidInput();
        }
        SemVer parsed;
        try {
            parsed = SemVer.parse(version);
        } catch (IllegalArgumentException ex) {
            throw notFound();
        }
        return rowsOf(conch).stream()
                .filter(row -> row.version().equals(parsed.toString()))
                .findFirst()
                .orElseThrow(ConchCatalogService::notFound);
    }

    private List<VersionRow> rowsOf(String conch) {
        if (conch == null || conch.isBlank() || conch.length() > 128 || !ConchPaths.singleSegment(conch)) {
            throw notFound();
        }
        return repository.listAll().stream().filter(row -> row.conch().equals(conch)).toList();
    }

    private ConchDoc readDoc(VersionRow row) {
        try {
            return ConchParser.parse(new String(storage.readYaml(row.conch(), row.version()), StandardCharsets.UTF_8));
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
            grouped.computeIfAbsent(row.conch(), ignored -> new ArrayList<>()).add(row);
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
                .orElseThrow(ConchCatalogService::notFound);
    }

    private static long pullTotal(List<VersionRow> rows) {
        long total = 0;
        for (VersionRow row : rows) {
            total += row.pullCount();
        }
        return total;
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
