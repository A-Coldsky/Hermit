package dev.hermit.crab.mcp;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Developer-mode authoring guide. The field reference is the current
 * {@code docs/conch-reference.md}, copied into the jar at build time.
 * Markdown links are removed so the text does not point at the repository or the web.
 */
final class AuthoringGuide {
    static final String URI = "hermit://authoring/guide";
    private static final String RESOURCE = "authoring/conch-reference.md";
    private static final Pattern MARKDOWN_LINK = Pattern.compile("!?\\[([^\\]\\n]*)\\]\\(([^)\\n]*)\\)");
    private static final Pattern AUTOLINK = Pattern.compile("<https?://[^\\s>]+>");

    private static final byte[] REFERENCE = readReference();
    private static final String TEXT = stripDocLinks(new String(REFERENCE, StandardCharsets.UTF_8));

    private AuthoringGuide() {
    }

    static String text() {
        return TEXT;
    }

    static byte[] referenceBytes() {
        return REFERENCE.clone();
    }

    static String stripDocLinks(String markdown) {
        String withoutLinks = MARKDOWN_LINK.matcher(markdown == null ? "" : markdown).replaceAll("$1");
        return AUTOLINK.matcher(withoutLinks).replaceAll("");
    }

    private static byte[] readReference() {
        ClassLoader loader = AuthoringGuide.class.getClassLoader();
        try (InputStream in = loader == null ? null : loader.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(
                        "构建期没有把 docs/conch-reference.md 收进 classpath：" + RESOURCE);
            }
            return in.readAllBytes();
        } catch (IOException ex) {
            throw new IllegalStateException("构建期没有把 docs/conch-reference.md 收进 classpath：" + RESOURCE);
        }
    }
}
