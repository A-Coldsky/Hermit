package dev.hermit.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;

import org.junit.jupiter.api.Test;

import dev.hermit.common.model.Action;
import dev.hermit.common.model.HttpMethod;
import dev.hermit.common.model.ConchDoc;
import dev.hermit.common.parse.ConchParser;
import dev.hermit.common.version.SemVer;

class LegacyOaFixtureTest {

    @Test
    void sampleResourceMatchesTheFixtureAndPassesValidation() throws Exception {
        byte[] fixture = Fixtures.yaml("legacy_oa_valid.yaml").getBytes(StandardCharsets.UTF_8);
        try (InputStream sample = getClass().getClassLoader().getResourceAsStream("samples/legacy_oa.yaml")) {
            assertThat(sample).isNotNull();
            assertThat(sample.readAllBytes()).containsExactly(fixture);
        }
        ConchDoc doc = ConchParser.parse(new String(fixture, StandardCharsets.UTF_8));
        assertThat(doc.conch()).isEqualTo("legacy_oa");
        assertThat(doc.version()).isEqualTo(SemVer.parse("1.0.0"));
        assertThat(doc.driver()).isEqualTo("http");
        assertThat(doc.minCrabVersion()).isEqualTo(SemVer.parse("1.0.0"));
        assertThat(doc.baseUrl()).isEqualTo("http://oa.intranet.local");
        assertThat(doc.title()).isEqualTo("协同办公 OA");
        assertThat(Fixtures.validate("legacy_oa_valid.yaml").errors()).isEmpty();
        assertThat(doc.credentials().get("password").description())
                .isEqualTo("填原始密码")
                .doesNotContainIgnoringCase("md5");
        assertThat(new String(fixture, StandardCharsets.UTF_8)).contains("{{credential.password | md5}}");

        Action action = doc.actions().get("list_todo");
        assertThat(action.effectiveMethod()).isEqualTo(HttpMethod.GET);
        assertThat(action.outputTemplate()).isEqualTo(Map.of("items", "{{items}}"));
        assertThat(action.related()).isNull();
        assertThat(new String(fixture, StandardCharsets.UTF_8))
                .doesNotContain("approve")
                .doesNotContain("list_done");
        assertThat(action.examples()).hasSize(1);

        ObjectMapper mapper = new ObjectMapper();
        JsonSchemaFactory schemas = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        JsonSchema input = schemas.getSchema(mapper.valueToTree(action.inputJsonSchema()));
        assertThat(input.validate(mapper.valueToTree(Map.of("page", 1)))).isEmpty();
        assertThat(input.validate(mapper.valueToTree(Map.of("page", "1")))).isNotEmpty();

        JsonSchema output = schemas.getSchema(mapper.valueToTree(action.outputJsonSchema()));
        assertThat(output.validate(mapper.valueToTree(action.examples().get(0).output()))).isEmpty();
        assertThat(action.outputJsonSchema()).isEqualTo(expectedOutputSchema());
    }

    @Test
    void outputFormsAndEffectiveMethod() {
        Action described = ConchParser.parse("""
                conch: legacy_oa
                version: 1.0.0
                min_crab_version: 1.0.0
                actions:
                  list_todo:
                    output:
                      items:
                        type: array
                        description: 列表
                        value: "{{rows}}"
                        items:
                          id: { type: string, description: 编号 }
                """).actions().get("list_todo");
        assertThat(described.outputTemplate()).isEqualTo(Map.of("items", "{{rows}}"));
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) described.outputJsonSchema().get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> items = (Map<String, Object>) properties.get("items");
        assertThat(items).doesNotContainKey("value");
        assertThat(items).containsEntry("type", "array");

        Action mapped = ConchParser.parse("""
                conch: legacy_oa
                version: 1.0.0
                min_crab_version: 1.0.0
                actions:
                  list_todo:
                    output:
                      items: "{{items}}"
                      meta:
                        total: "{{total}}"
                """).actions().get("list_todo");
        assertThat(mapped.outputTemplate()).isEqualTo(Map.of(
                "items", "{{items}}",
                "meta", Map.of("total", "{{total}}")));
        assertThat(mapped.outputJsonSchema()).isEqualTo(Map.of(
                "type", "object",
                "properties", Map.of(
                        "items", Map.of(),
                        "meta", Map.of(
                                "type", "object",
                                "properties", Map.of("total", Map.of())))));

        assertThat(method(null, false, false)).isEqualTo(HttpMethod.POST);
        assertThat(method(null, true, false)).isEqualTo(HttpMethod.GET);
        assertThat(method(null, false, true)).isEqualTo(HttpMethod.GET);
        assertThat(method("POST", true, true)).isEqualTo(HttpMethod.POST);
        assertThat(method("GET", false, false)).isEqualTo(HttpMethod.GET);
    }

    private static HttpMethod method(String explicit, boolean readOnly, boolean idempotent) {
        String yaml = "conch: legacy_oa\n"
                + "version: 1.0.0\n"
                + "min_crab_version: 1.0.0\n"
                + "actions:\n"
                + "  list_todo:\n"
                + "    idempotent: " + idempotent + "\n"
                + (explicit == null ? "" : "    method: " + explicit + "\n")
                + (readOnly ? "    mcp: { read_only: true }\n" : "");
        return ConchParser.parse(yaml).actions().get("list_todo").effectiveMethod();
    }

    private static Map<String, Object> expectedOutputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "items", Map.of(
                                "type", "array",
                                "description", "待办列表",
                                "items", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "id", Map.of("type", "string", "description", "审批单号"),
                                                "title", Map.of("type", "string", "description", "审批标题"),
                                                "applicant", Map.of("type", "string", "description", "申请人姓名"),
                                                "date", Map.of("type", "string", "description", "提交日期，yyyy-MM-dd"))))));
    }
}
