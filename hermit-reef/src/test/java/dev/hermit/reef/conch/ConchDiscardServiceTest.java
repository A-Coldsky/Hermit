package dev.hermit.reef.conch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.hermit.reef.web.ApiException;
import dev.hermit.reef.web.ReefErrorCode;
import java.util.ArrayList;
import java.util.List;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

class ConchDiscardServiceTest {

    @Test
    void nonCanonicalNamesDoNotLockOrQuery() {
        List<String> locked = new ArrayList<>();
        List<String> queried = new ArrayList<>();
        ConchDiscardService service = service(locked, queried);

        for (String name : List.of("Legacy", "legacy ", "legacy  ", " legacy")) {
            assertNotFound(() -> service.discard("reef-admin", name, "1.0.0"));
        }
        assertThat(locked).isEmpty();
        assertThat(queried).isEmpty();
    }

    @Test
    void canonicalNameStillLocksAfterSemVerNormalization() {
        List<String> locked = new ArrayList<>();
        List<String> queried = new ArrayList<>();
        ConchDiscardService service = service(locked, queried);

        assertNotFound(() -> service.discard("reef-admin", "legacy", "nope"));
        assertThat(locked).isEmpty();
        assertThat(queried).isEmpty();

        assertNotFound(() -> service.discard("reef-admin", "legacy", "1.0.0 "));
        assertThat(locked).containsExactly("legacy");
        assertThat(queried).containsExactly("legacy");
    }

    private static ConchDiscardService service(List<String> locked, List<String> queried) {
        ConchVersionLock locks = new ConchVersionLock() {
            @Override
            Object monitor(String conch) {
                locked.add(conch);
                return super.monitor(conch);
            }
        };
        ConchRepository repository = new ConchRepository(null) {
            @Override
            List<VersionRow> lockConch(String conch) {
                queried.add(conch);
                return List.of();
            }
        };
        return new ConchDiscardService(locks, repository, null, null, new RunImmediately());
    }

    private static void assertNotFound(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, ex -> {
            assertThat(ex.status()).isEqualTo(404);
            assertThat(ex.code()).isEqualTo(ReefErrorCode.NOT_FOUND);
            assertThat(ex.getMessage()).isEqualTo(ReefErrorCode.NOT_FOUND.message());
        });
    }

    private static final class RunImmediately implements PlatformTransactionManager {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    }
}
