package dev.hermit.common.validate;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import dev.hermit.common.parse.ConchParser;
import dev.hermit.common.version.SemVer;

class ScriptLibraryValidationTest {

    @Test
    void acceptsLibrariesInOrderAndLeavesBracesLiteral() {
        ValidationResult result = validate(pkg("1.2.0", """
                lib: |
                  function raw() { return "X{{credential.password}}Y"; }
                """, """
                - script:
                    use: [lib]
                    code: |
                      return raw();
                """), null);
        assertThat(result.errors()).isEmpty();
        assertThat(ConchParser.parse(pkg("1.2.0", """
                lib: |
                  function raw() { return "X{{credential.password}}Y"; }
                """, """
                - script:
                    use: [lib]
                    code: |
                      return raw();
                """)).scripts().get("lib")).contains("{{credential.password}}");
    }

    @Test
    void rejectsMissingDuplicateAndInvalidUse() {
        ValidationResult missing = validate(pkg("1.2.0", "", """
                - script:
                    use: [missing]
                    code: "return 1;"
                """), "1.2.0");
        assertThat(missing.errors()).anyMatch(issue ->
                issue.code().equals(ValidationCodes.SCRIPT_STEP)
                        && issue.message().equals(ScriptLibraries.missing("missing")));

        ValidationResult duplicate = validate(pkg("1.2.0", """
                lib: "function a() { return 1; }"
                """, """
                - script:
                    use: [lib, lib]
                    code: "return a();"
                """), "1.2.0");
        assertThat(duplicate.errors()).anyMatch(issue ->
                issue.message().equals(ScriptLibraries.duplicate("lib")));

        ValidationResult typed = validate(pkg("1.2.0", "", """
                - script:
                    use: "lib"
                    credential: "yes"
                    code: "return 1;"
                """), "1.2.0");
        assertThat(typed.errors()).anyMatch(issue -> issue.message().equals(ScriptLibraries.USE_TYPE));
        assertThat(typed.errors()).anyMatch(issue -> issue.message().equals(ScriptLibraries.CREDENTIAL_TYPE));

        String blob = "x".repeat(80) + "!";
        ValidationResult named = validate(pkg("1.2.0", "\"" + blob + "\": \"function a(){return 1;}\"\n", """
                - script:
                    code: "return 1;"
                """), "1.2.0");
        assertThat(named.errors()).anyMatch(issue ->
                issue.path().equals("scripts")
                        && issue.message().equals(ScriptLibraries.NAME_INVALID)
                        && !issue.message().contains(blob));
    }

    @Test
    void enforcesLibrarySizeAndVersionOnUploadAndCurrentCrab() {
        String exact = "a".repeat(ScriptLibraries.MAX_BYTES);
        ValidationResult fits = validate(pkg("1.2.0", "lib: \"" + exact + "\"\n", """
                - script:
                    use: [lib]
                    code: "return 1;"
                """), null);
        assertThat(fits.errors()).noneMatch(issue -> issue.message().equals(ScriptLibraries.TOO_LARGE));

        String over = "a".repeat(ScriptLibraries.MAX_BYTES + 1);
        ValidationResult huge = validate(pkg("1.2.0", "lib: \"" + over + "\"\n", """
                - script:
                    use: [lib]
                    code: "return 1;"
                """), null);
        assertThat(huge.errors()).anyMatch(issue ->
                issue.code().equals(ValidationCodes.SCRIPT_STEP)
                        && issue.message().equals(ScriptLibraries.TOO_LARGE)
                        && !issue.message().contains("aaaa"));

        ValidationResult low = validate(pkg("1.1.0", "lib: \"function a(){return 1;}\"\n", """
                - script:
                    use: [lib]
                    code: "return 1;"
                """), null);
        assertThat(low.errors()).anyMatch(issue ->
                issue.code().equals(ValidationCodes.MIN_CRAB_VERSION)
                        && issue.message().equals(ScriptLibraries.VERSION));

        ValidationResult prerelease = validate(pkg("1.2.0-rc.1", "lib: \"function a(){return 1;}\"\n", """
                - script:
                    code: "return 1;"
                """), null);
        assertThat(prerelease.errors()).anyMatch(issue -> issue.message().equals(ScriptLibraries.VERSION));

        ValidationResult emptyUse = validate(pkg("1.0.0", "", """
                - script:
                    use: []
                    code: "return 1;"
                """), null);
        assertThat(emptyUse.errors()).anyMatch(issue -> issue.message().equals(ScriptLibraries.VERSION));

        ValidationResult credentialOnly = validate(pkg("1.0.0", "", """
                - script:
                    credential: true
                    code: "return { ok: 1 };"
                """), null);
        assertThat(credentialOnly.errors()).isEmpty();

        ValidationResult current = validate(pkg("1.2.0", "lib: \"function a(){return 1;}\"\n", """
                - script:
                    use: [lib]
                    code: "return a();"
                """), "1.1.0");
        assertThat(current.errors()).anyMatch(issue -> issue.message().contains("高于当前 Crab"));
        assertThat(current.errors()).noneMatch(issue -> issue.message().equals(ScriptLibraries.VERSION));
    }

    private static ValidationResult validate(String yaml, String current) {
        return ConchValidator.validate(
                ConchParser.parse(yaml),
                ConchValidator.Context.of(current == null ? null : SemVer.parse(current)));
    }

    private static String pkg(String min, String scripts, String steps) {
        String library = scripts == null || scripts.isBlank()
                ? ""
                : "scripts:\n" + scripts.stripIndent().indent(2);
        String indented = steps.stripIndent().stripTrailing().replace("\n", "\n      ");
        return """
                conch: sample_conch
                version: 1.0.0
                driver: http
                min_crab_version: %s
                title: 标题
                description: 说明
                %scredentials: {}
                actions:
                  run:
                    title: 能力
                    description: 说明
                    output:
                      value: { type: string, description: 结果 }
                    examples:
                      - input: {}
                        output: { value: ok }
                    notes: 注意
                    related: [run]
                    steps:
                      %s
                """.formatted(min, library, indented);
    }
}
