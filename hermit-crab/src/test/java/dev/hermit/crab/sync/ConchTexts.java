package dev.hermit.crab.sync;

final class ConchTexts {

    private ConchTexts() {
    }

    static String standard(String conch, String version) {
        return of(conch, version, "1.0.0", """
                username: { type: text, required: true }
                password: { type: secret, required: true }
                """);
    }

    static String withoutPassword(String conch, String version) {
        return of(conch, version, "1.0.0", """
                username: { type: text, required: true }
                otp: { type: secret, required: true }
                """);
    }

    static String of(String conch, String version, String minCrab, String credentials) {
        String indented = credentials.stripIndent().indent(2).stripTrailing();
        return """
                conch: %s
                version: %s
                driver: http
                min_crab_version: %s
                base_url: http://127.0.0.1:9/
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
                """.formatted(conch, version, minCrab, indented);
    }
}
