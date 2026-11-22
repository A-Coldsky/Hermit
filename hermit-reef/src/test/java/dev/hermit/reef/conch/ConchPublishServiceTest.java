package dev.hermit.reef.conch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.hermit.reef.testsupport.ReefTestConfig;
import dev.hermit.reef.web.ApiException;
import dev.hermit.reef.web.ReefErrorCode;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

class ConchPublishServiceTest {

    @Test
    void nonCanonicalNamesDoNotTouchSql() {
        List<String> queried = new ArrayList<>();
        ConchRepository repository = new ConchRepository(null) {
            @Override
            List<VersionRow> lockConch(String conch) {
                queried.add(conch);
                return List.of();
            }
        };
        ConchPublishService service = new ConchPublishService(
                repository,
                null,
                null,
                null,
                null,
                null,
                Clock.fixed(ReefTestConfig.START, ZoneOffset.UTC));

        for (String name : List.of("Legacy", "LEGACY_OA", "legacy ", "legacy  ", " legacy")) {
            assertNotFound(() -> service.publish("reef-admin", name, "1.0.0"));
        }
        assertThat(queried).isEmpty();
    }

    @Test
    void mismatchedRowCaseFromCollationIsRejectedAsNotFound() {
        List<String> queried = new ArrayList<>();
        VersionRow mixedCaseRow = new VersionRow(
                1L,
                "Legacy",
                "1.0.0",
                "a".repeat(64),
                "1.0.0",
                "UPLOADED",
                "reef-admin",
                ReefTestConfig.START,
                null,
                null,
                false,
                null,
                null,
                0L);
        ConchRepository repository = new ConchRepository(null) {
            @Override
            List<VersionRow> lockConch(String conch) {
                queried.add(conch);
                return List.of(mixedCaseRow);
            }
        };
        ConchPublishService service = new ConchPublishService(
                repository,
                null,
                null,
                null,
                null,
                null,
                Clock.fixed(ReefTestConfig.START, ZoneOffset.UTC));

        assertNotFound(() -> service.publish("reef-admin", "legacy", "1.0.0"));
        assertThat(queried).containsExactly("legacy");
    }

    private static void assertNotFound(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, ex -> {
            assertThat(ex.status()).isEqualTo(404);
            assertThat(ex.code()).isEqualTo(ReefErrorCode.NOT_FOUND);
            assertThat(ex.getMessage()).isEqualTo(ReefErrorCode.NOT_FOUND.message());
        });
    }
}
