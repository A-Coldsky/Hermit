package dev.hermit.reef.conch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.hermit.reef.web.ApiException;
import dev.hermit.reef.web.ReefErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

class ConchAvailabilityServiceTest {

    @Test
    void nonCanonicalNamesDoNotTouchSql() {
        List<String> versions = new ArrayList<>();
        List<String> availability = new ArrayList<>();
        ConchAvailabilityService service = service(versions, availability);

        for (String name : List.of("Legacy", "legacy ", "legacy  ", " legacy")) {
            assertNotFound(() -> service.disable("reef-admin", name, "停用"));
            assertNotFound(() -> service.enable("reef-admin", name, "启用"));
        }
        assertThat(versions).isEmpty();
        assertThat(availability).isEmpty();

        assertNotFound(() -> service.disable("reef-admin", "legacy", "停用"));
        assertThat(versions).containsExactly("lockConch legacy");
        assertThat(availability).isEmpty();
    }

    private static ConchAvailabilityService service(List<String> versions, List<String> availability) {
        ConchRepository conches = new ConchRepository(null) {
            @Override
            List<VersionRow> lockConch(String conch) {
                versions.add("lockConch " + conch);
                return List.of();
            }
        };
        ConchAvailabilityRepository flags = new ConchAvailabilityRepository(null) {
            @Override
            AvailabilityRow lock(String conch) {
                availability.add("lock " + conch);
                return null;
            }

            @Override
            void insertDisabled(String conch, Instant at, String actor, String reason) {
                availability.add("insert " + conch);
            }

            @Override
            int updateDisabled(String conch, Instant at, String actor, String reason) {
                availability.add("update " + conch);
                return 0;
            }

            @Override
            int clearDisabled(String conch) {
                availability.add("clear " + conch);
                return 0;
            }
        };
        return new ConchAvailabilityService(conches, flags, null, new ObjectMapper(), Clock.systemUTC(), null);
    }

    private static void assertNotFound(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, ex -> {
            assertThat(ex.status()).isEqualTo(404);
            assertThat(ex.code()).isEqualTo(ReefErrorCode.NOT_FOUND);
            assertThat(ex.getMessage()).isEqualTo(ReefErrorCode.NOT_FOUND.message());
        });
    }
}
