package dev.hermit.reef.shell;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ManifestNegotiationTest {

    @Test
    void ifNoneMatchUsesTheQuotedTagAWeakTagOrStar() {
        String etag = "\"abc\"";
        assertThat(ShellDistributionService.noneMatch(null, etag)).isFalse();
        assertThat(ShellDistributionService.noneMatch("  ", etag)).isFalse();
        assertThat(ShellDistributionService.noneMatch(etag, etag)).isTrue();
        assertThat(ShellDistributionService.noneMatch("W/" + etag, etag)).isTrue();
        assertThat(ShellDistributionService.noneMatch("*", etag)).isTrue();
        assertThat(ShellDistributionService.noneMatch("\"other\", " + etag, etag)).isTrue();
        assertThat(ShellDistributionService.noneMatch("\"other\"", etag)).isFalse();
    }
}
