package dev.hermit.crab.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.hermit.common.pkg.Ed25519Keys;
import dev.hermit.crab.engine.ConchRegistry;
import dev.hermit.crab.testing.FakeReef;

class InstalledConchesTest {

    @TempDir
    Path temp;

    @Test
    void loadAllRefusesATamperedPackageAndStillLoadsTheOthers() throws Exception {
        try (FakeReef reef = new FakeReef(); OpenCrab crab = OpenCrab.open(temp)) {
            reef.publish(ConchTexts.standard("legacy_oa", "1.0.0"));
            reef.publish(ConchTexts.standard("other_sys", "1.0.0"));
            TestSubscriptions.grantKnown(crab.dataSource);
            ConchRegistry installed = new ConchRegistry();
            new ConchSync(
                    new SyncSettings(
                            reef.base(),
                            Optional.empty(),
                            reef.keys().getPublic(),
                            Duration.ofMinutes(5),
                            UUID.fromString("123e4567-e89b-12d3-a456-426614174000")),
                    crab.paths,
                    installed,
                    crab.vault,
                    crab.dataSource,
                    Clock.systemUTC()).syncOnce();

            Path zip = crab.paths.conches().resolve("legacy_oa/1.0.0/package.zip");
            byte[] bytes = Files.readAllBytes(zip);
            bytes[bytes.length / 2] ^= 0x01;
            Files.write(zip, bytes);

            ConchRegistry registry = new ConchRegistry();
            LoadReport report = InstalledConches.loadAll(crab.paths, reef.keys().getPublic(), registry);

            assertThat(report.loaded()).containsExactly("other_sys");
            assertThat(report.failures()).singleElement().satisfies(failure -> {
                assertThat(failure.conch()).isEqualTo("legacy_oa");
                assertThat(failure.kind()).isEqualTo(OutcomeKind.REJECTED);
            });
            assertThat(registry.find("legacy_oa")).isEmpty();
            assertThat(registry.find("other_sys")).isPresent();

            ConchRegistry wrongKey = new ConchRegistry();
            LoadReport rejected = InstalledConches.loadAll(crab.paths, Ed25519Keys.generate().getPublic(), wrongKey);
            assertThat(rejected.loaded()).isEmpty();
            assertThat(wrongKey.snapshot()).isEmpty();

            Optional<InstalledConches.VerifiedYaml> ok =
                    InstalledConches.readVerifiedYaml(crab.paths, reef.keys().getPublic(), "other_sys");
            assertThat(ok).isPresent();
            assertThat(ok.get().conch()).isEqualTo("other_sys");
            assertThat(ok.get().version()).isEqualTo("1.0.0");
            assertThat(new String(ok.get().bytes(), java.nio.charset.StandardCharsets.UTF_8))
                    .isEqualTo(ConchTexts.standard("other_sys", "1.0.0"));

            // 篡改后的包、错误公钥和非法路径段一律返回 empty
            assertThat(InstalledConches.readVerifiedYaml(crab.paths, reef.keys().getPublic(), "legacy_oa")).isEmpty();
            assertThat(InstalledConches.readVerifiedYaml(crab.paths, Ed25519Keys.generate().getPublic(), "other_sys")).isEmpty();
            assertThat(InstalledConches.readVerifiedYaml(crab.paths, reef.keys().getPublic(), "../other_sys")).isEmpty();
        }
    }
}
