package dev.hermit.reef.conch;

import com.fasterxml.jackson.databind.JsonNode;
import dev.hermit.common.model.Action;
import dev.hermit.common.model.ConfigField;
import dev.hermit.common.model.CredentialField;
import dev.hermit.common.model.Example;
import dev.hermit.common.model.McpHints;
import dev.hermit.common.model.ConchDoc;
import dev.hermit.common.validate.ValidationIssue;
import java.time.Instant;
import java.util.List;
import java.util.Map;

record UploadResponse(String conch, String version, List<ValidationIssue> warnings, JsonNode diff) {
}

record PublishResponse(String conch, String version) {
}

record RollbackResponse(String conch, String from, String to) {
}

record ConchAvailabilityView(
        String conch,
        boolean disabled,
        Instant disabledAt,
        String disabledBy,
        String disabledReason) {
}

record SigningKeyResponse(String publicKey) {
}

record ParseFailure(String path, String message) {
}

record ValidationFailure(List<ValidationIssue> errors, List<ValidationIssue> warnings) {
}

record ConchSummary(
        String conch,
        String version,
        String title,
        String description,
        String owner,
        List<String> tags,
        int capabilityCount,
        Instant publishedAt,
        boolean disabled,
        Instant disabledAt,
        String disabledBy,
        String disabledReason) {
}

record ConchVersionItem(
        String version,
        String status,
        String uploadedBy,
        Instant uploadedAt,
        String publishedBy,
        Instant publishedAt,
        boolean current,
        int warningCount,
        List<ValidationIssue> warnings) {
}

record CredentialDeclaration(
        String type,
        String label,
        boolean required,
        String description,
        List<String> options) {

    static CredentialDeclaration from(CredentialField field) {
        return new CredentialDeclaration(field.type(), field.label(), field.required(), field.description(), field.options());
    }
}

record ConfigDeclaration(
        String type,
        String label,
        boolean required,
        String description,
        List<String> options,
        @com.fasterxml.jackson.annotation.JsonProperty("default") Object defaultValue) {

    static ConfigDeclaration from(ConfigField field) {
        return new ConfigDeclaration(
                field.type(), field.label(), field.required(), field.description(), field.options(), field.defaultValue());
    }
}

record ExampleView(Object input, Object output) {

    static ExampleView from(Example example) {
        return new ExampleView(example.input(), example.output());
    }
}

record McpView(boolean expose, boolean readOnly, boolean destructive) {

    static McpView from(McpHints hints) {
        return new McpView(hints.expose(), hints.readOnly(), hints.destructive());
    }
}

record CapabilityView(
        String name,
        String title,
        String description,
        String effectiveMethod,
        Map<String, Object> inputJsonSchema,
        Map<String, Object> outputJsonSchema,
        List<ExampleView> examples,
        String notes,
        List<String> related,
        McpView mcp) {

    static CapabilityView from(Action action) {
        return new CapabilityView(
                action.name(),
                action.title(),
                action.description(),
                action.effectiveMethod().name(),
                action.inputJsonSchema(),
                action.outputJsonSchema(),
                action.examples().stream().map(ExampleView::from).toList(),
                action.notes(),
                action.related(),
                McpView.from(action.mcp()));
    }
}

record ConchDetail(
        String conch,
        String version,
        String driver,
        String minCrabVersion,
        String baseUrl,
        String title,
        String description,
        String owner,
        List<String> tags,
        String changelog,
        Map<String, CredentialDeclaration> credentials,
        Map<String, ConfigDeclaration> config,
        Map<String, CapabilityView> actions,
        boolean disabled,
        Instant disabledAt,
        String disabledBy,
        String disabledReason) {

    static ConchDetail from(ConchDoc doc, AvailabilityRow availability) {
        Map<String, CredentialDeclaration> credentials = new java.util.LinkedHashMap<>();
        if (doc.credentials() != null) {
            doc.credentials().forEach((name, field) -> credentials.put(name, CredentialDeclaration.from(field)));
        }
        Map<String, ConfigDeclaration> config = new java.util.LinkedHashMap<>();
        doc.config().forEach((name, field) -> config.put(name, ConfigDeclaration.from(field)));
        Map<String, CapabilityView> actions = new java.util.LinkedHashMap<>();
        doc.actions().forEach((name, action) -> actions.put(name, CapabilityView.from(action)));
        return new ConchDetail(
                doc.conch(),
                doc.version().toString(),
                doc.driver(),
                doc.minCrabVersion().toString(),
                doc.baseUrl(),
                doc.title(),
                doc.description(),
                doc.owner(),
                doc.tags(),
                doc.changelog(),
                credentials,
                config,
                actions,
                availability != null && availability.disabled(),
                availability != null && availability.disabled() ? availability.disabledAt() : null,
                availability != null && availability.disabled() ? availability.disabledBy() : null,
                availability != null && availability.disabled() ? availability.disabledReason() : null);
    }
}

record ManifestEntry(String conch, String version, String sha256, String minCrabVersion, String title) {
}
