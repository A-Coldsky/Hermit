package dev.hermit.common.version;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SemVerTest {

    @Test
    void parsesCoreAndPrerelease() {
        SemVer version = SemVer.parse("  1.2.3-alpha.1  ");
        assertThat(version.major()).isEqualTo(1);
        assertThat(version.minor()).isEqualTo(2);
        assertThat(version.patch()).isEqualTo(3);
        assertThat(version.prerelease()).containsExactly("alpha", "1");
        assertThat(version.toString()).isEqualTo("1.2.3-alpha.1");
        assertThat(SemVer.parse("1.0.0").toString()).isEqualTo("1.0.0");
    }

    @Test
    void rejectsBuildMetadataAndInvalidForms() {
        assertThatThrownBy(() -> SemVer.parse("1.0.0+build")).hasMessageContaining("build metadata");
        assertThatThrownBy(() -> SemVer.parse("1.0.0-rc.1+001")).hasMessageContaining("build metadata");
        assertThatThrownBy(() -> SemVer.parse("1.0")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SemVer.parse("01.0.0")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SemVer.parse("1.0.0-01")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SemVer.parse("v1.0.0")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SemVer.parse("")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void comparesBySemverPrecedence() {
        assertPrecedes("1.0.0-alpha", "1.0.0-alpha.1");
        assertPrecedes("1.0.0-alpha.1", "1.0.0-alpha.beta");
        assertPrecedes("1.0.0-alpha.beta", "1.0.0-beta");
        assertPrecedes("1.0.0-beta", "1.0.0-beta.2");
        assertPrecedes("1.0.0-beta.2", "1.0.0-beta.11");
        assertPrecedes("1.0.0-beta.11", "1.0.0-rc.1");
        assertPrecedes("1.0.0-rc.1", "1.0.0");
        assertPrecedes("1.0.0", "1.0.1");
        assertPrecedes("1.0.1", "1.1.0");
        assertPrecedes("1.1.0", "2.0.0");
        assertThat(SemVer.parse("1.0.0")).isEqualByComparingTo(SemVer.parse("1.0.0"));
        assertThat(SemVer.parse("1.0.0")).isEqualTo(SemVer.parse("1.0.0"));
    }

    private static void assertPrecedes(String lower, String higher) {
        assertThat(SemVer.parse(lower)).isLessThan(SemVer.parse(higher));
        assertThat(SemVer.parse(higher)).isGreaterThan(SemVer.parse(lower));
    }
}
