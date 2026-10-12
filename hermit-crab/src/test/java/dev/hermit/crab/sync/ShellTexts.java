package dev.hermit.crab.sync;

final class ShellTexts {

    private ShellTexts() {
    }

    static String standard(String shell, String version) {
        return of(shell, version, "1.0.0", """
                username: { type: text, required: true }
                password: { type: secret, required: true }
                """);
    }

    static String withoutPassword(String shell, String version) {
        return of(shell, version, "1.0.0", """
                username: { type: text, required: true }
                otp: { type: secret, required: true }
                """);
    }

    static String of(String shell, String version, String minCrab, String credentials) {
        String indented = credentials.stripIndent().indent(2).stripTrailing();
        return """
                shell: %s
                version: %s
                driver: http
                min_crab_version: %s
                title: 标题
                description: 说明
                credentials:
                %s
                actions:
                  list_todo:
                    title: 能力
                    description: 说明
                    output:
                      value: { type: string, description: 结果, value: ok }
                    examples:
                      - input: {}
                        output: { value: ok }
                    notes: 注意
                    steps:
                      - set:
                          marker: ok
                """.formatted(shell, version, minCrab, indented);
    }
}
