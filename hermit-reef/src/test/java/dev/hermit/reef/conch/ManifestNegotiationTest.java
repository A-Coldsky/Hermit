package dev.hermit.reef.conch;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ManifestNegotiationTest {

    @Test
    void ifNoneMatchUsesTheQuotedTagAWeakTagOrStar() {
        String etag = "\"abc\"";
        assertThat(ConchDistributionService.noneMatch(null, etag)).isFalse();
        assertThat(ConchDistributionService.noneMatch("  ", etag)).isFalse();
        assertThat(ConchDistributionService.noneMatch(etag, etag)).isTrue();
        assertThat(ConchDistributionService.noneMatch("W/" + etag, etag)).isTrue();
        assertThat(ConchDistributionService.noneMatch("*", etag)).isTrue();
        assertThat(ConchDistributionService.noneMatch("\"other\", " + etag, etag)).isTrue();
        assertThat(ConchDistributionService.noneMatch("\"other\"", etag)).isFalse();
    }
}
