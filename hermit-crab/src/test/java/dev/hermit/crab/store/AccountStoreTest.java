package dev.hermit.crab.store;

import dev.hermit.crab.testing.SecretChecks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

class AccountStoreTest {
    @TempDir
    Path temp;

    private StoreFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = StoreFixture.open(temp);
    }

    @AfterEach
    void tearDown() {
        fixture.close();
    }

    @Test
    void firstAccountIsTheDefaultAndSetDefaultMovesIt() {
        Account alice = fixture.accounts.create(Origin.OFFICIAL, "oa", "alice", "cli");
        Account bob = fixture.accounts.create(Origin.OFFICIAL, "oa", "bob", "cli");
        assertThat(alice.defaultAccount()).isTrue();
        assertThat(bob.defaultAccount()).isFalse();
        assertThat(fixture.accounts.resolve(Origin.OFFICIAL, "oa", Optional.empty()).account()).isEqualTo("alice");

        fixture.accounts.setDefault(Origin.OFFICIAL, "oa", "bob", "cli");

        assertThat(fixture.accounts.resolve(Origin.OFFICIAL, "oa", Optional.empty()).account()).isEqualTo("bob");
        assertThat(fixture.accounts.list(Origin.OFFICIAL, "oa"))
                .filteredOn(Account::defaultAccount)
                .extracting(Account::account)
                .containsExactly("bob");
        assertThat(auditText()).contains("DEFAULT_CHANGED").contains("bob");
    }

    @Test
    void cannotDeleteTheDefaultWhileAnotherAccountExists() {
        fixture.accounts.create(Origin.OFFICIAL, "oa", "alice", "cli");
        fixture.accounts.create(Origin.OFFICIAL, "oa", "bob", "cli");
        fixture.vault.write(Origin.OFFICIAL, "oa", "alice", java.util.Map.of("password", "alice-secret-value"), "cli");

        Throwable thrown = catchThrowable(() -> fixture.accounts.delete(Origin.OFFICIAL, "oa", "alice", "cli"));

        assertThat(thrown).isInstanceOf(IllegalStateException.class);
        assertThat(thrown.getMessage()).contains("default");
        SecretChecks.absent(thrown, "alice-secret-value");
        SecretChecks.contains(
                fixture.vault.allSecretValues(Origin.OFFICIAL, "oa"),
                "alice-secret-value");
        assertThat(fixture.accounts.list(Origin.OFFICIAL, "oa")).hasSize(2);
    }

    @Test
    void deleteRemovesTheAccountAndItsCredentials() {
        fixture.accounts.create(Origin.OFFICIAL, "oa", "alice", "cli");
        fixture.accounts.create(Origin.OFFICIAL, "oa", "bob", "cli");
        fixture.vault.write(Origin.OFFICIAL, "oa", "bob", java.util.Map.of("password", "bob-secret-value"), "cli");
        fixture.vault.write(Origin.OFFICIAL, "oa", "alice", java.util.Map.of("password", "alice-secret-value"), "cli");

        fixture.accounts.delete(Origin.OFFICIAL, "oa", "bob", "cli");

        SecretChecks.missing(fixture.vault.allSecretValues(Origin.OFFICIAL, "oa"), "bob-secret-value");
        SecretChecks.contains(fixture.vault.allSecretValues(Origin.OFFICIAL, "oa"), "alice-secret-value");
        assertThatThrownBy(() -> fixture.accounts.resolve(Origin.OFFICIAL, "oa", Optional.of("bob")))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void deletingTheOnlyAccountIsAllowedAndTheNextCreateBecomesDefault() {
        fixture.accounts.create(Origin.OFFICIAL, "oa", "alice", "cli");
        fixture.vault.write(Origin.OFFICIAL, "oa", "alice", java.util.Map.of("password", "only-secret-value"), "cli");

        fixture.accounts.delete(Origin.OFFICIAL, "oa", "alice", "cli");

        assertThat(fixture.vault.allSecretValues(Origin.OFFICIAL, "oa")).isEmpty();
        assertThat(credentialCount()).isZero();
        assertThatThrownBy(() -> fixture.accounts.resolve(Origin.OFFICIAL, "oa", Optional.empty()))
                .isInstanceOf(AccountNotFoundException.class);

        Account carol = fixture.accounts.create(Origin.OFFICIAL, "oa", "carol", "cli");
        assertThat(carol.defaultAccount()).isTrue();
    }

    @Test
    void resolveFindsANamedAccountTheDefaultOrFails() {
        fixture.accounts.create(Origin.OFFICIAL, "oa", "alice", "cli");
        fixture.accounts.create(Origin.OFFICIAL, "oa", "bob", "cli");

        assertThat(fixture.accounts.resolve(Origin.OFFICIAL, "oa", Optional.of("bob")).account()).isEqualTo("bob");
        assertThat(fixture.accounts.resolve(Origin.OFFICIAL, "oa", Optional.empty()).account()).isEqualTo("alice");
        assertThatThrownBy(() -> fixture.accounts.resolve(Origin.OFFICIAL, "oa", Optional.of("missing")))
                .isInstanceOf(AccountNotFoundException.class);

        fixture.accounts.delete(Origin.OFFICIAL, "oa", "bob", "cli");
        fixture.accounts.delete(Origin.OFFICIAL, "oa", "alice", "cli");
        assertThatThrownBy(() -> fixture.accounts.resolve(Origin.OFFICIAL, "oa", Optional.empty()))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void originsDoNotSeeEachOthersAccountsOrDefaults() {
        fixture.accounts.create(Origin.OFFICIAL, "oa", "alice", "cli");
        fixture.accounts.create(Origin.LOCAL, "oa", "alice", "cli");
        fixture.accounts.create(Origin.OFFICIAL, "oa", "bob", "cli");

        assertThat(fixture.accounts.resolve(Origin.LOCAL, "oa", Optional.empty()).account()).isEqualTo("alice");
        assertThat(fixture.accounts.list(Origin.LOCAL, "oa")).extracting(Account::account).containsExactly("alice");
        assertThatThrownBy(() -> fixture.accounts.delete(Origin.OFFICIAL, "oa", "alice", "cli"))
                .isInstanceOf(IllegalStateException.class);

        fixture.accounts.delete(Origin.LOCAL, "oa", "alice", "cli");

        assertThatThrownBy(() -> fixture.accounts.resolve(Origin.LOCAL, "oa", Optional.empty()))
                .isInstanceOf(AccountNotFoundException.class);
        assertThat(fixture.accounts.resolve(Origin.OFFICIAL, "oa", Optional.empty()).account()).isEqualTo("alice");
        assertThat(storedOrigin("alice")).containsExactly("OFFICIAL");
    }

    @Test
    void actorIsOnlyCliOrConsole() {
        assertThat(fixture.accounts.create(Origin.OFFICIAL, "oa", "alice", Actors.CONSOLE).account()).isEqualTo("alice");
        String supplied = "HermitRequestActor-not-allowed";
        Throwable thrown = catchThrowable(() -> fixture.accounts.create(Origin.OFFICIAL, "erp", "bob", supplied));
        assertThat(thrown).isInstanceOf(IllegalArgumentException.class);
        assertThat(thrown.getMessage()).isEqualTo("actor must be cli, console, or sync");
        SecretChecks.absent(thrown, supplied);
        assertThat(auditText()).doesNotContain(supplied);
    }

    @Test
    void accountNamesMustMatchTheContractAndAreNotEchoed() {
        assertThat(fixture.accounts.create(Origin.OFFICIAL, "oa", "A_b-1", "cli").account()).isEqualTo("A_b-1");
        assertThat(fixture.accounts.create(Origin.OFFICIAL, "oa", "a".repeat(64), "cli").account()).hasSize(64);
        Throwable thrown = catchThrowable(() -> fixture.accounts.create(Origin.OFFICIAL, "erp", "bad name", "cli"));
        assertThat(thrown).isInstanceOf(IllegalArgumentException.class);
        SecretChecks.absent(thrown, "bad name");
        assertThatThrownBy(() -> fixture.accounts.create(Origin.OFFICIAL, "erp", "a".repeat(65), "cli"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fixture.accounts.create(Origin.OFFICIAL, "erp", "名字", "cli"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fixture.accounts.create(Origin.OFFICIAL, "oa", "A_b-1", "cli"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("account already exists");
        assertThat(auditText()).doesNotContain("bad name");
    }

    @Test
    void aShellWithTwoDefaultsFailsClosed() throws Exception {
        fixture.accounts.create(Origin.OFFICIAL, "oa", "alice", "cli");
        fixture.accounts.create(Origin.OFFICIAL, "oa", "bob", "cli");
        try (Connection connection = fixture.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE accounts SET is_default = TRUE WHERE origin = ? AND shell = ? AND account = ?
                     """)) {
            statement.setString(1, "OFFICIAL");
            statement.setString(2, "oa");
            statement.setString(3, "bob");
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }

        assertThatThrownBy(() -> fixture.accounts.resolve(Origin.OFFICIAL, "oa", Optional.empty()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("multiple default accounts");
    }

    @Test
    void concurrentCreatesLeaveExactlyOneDefault() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 20; i++) {
                String shell = "s" + i;
                CountDownLatch start = new CountDownLatch(1);
                Future<?> alice = pool.submit(createAfter(start, shell, "alice"));
                Future<?> bob = pool.submit(createAfter(start, shell, "bob"));
                start.countDown();
                alice.get(10, TimeUnit.SECONDS);
                bob.get(10, TimeUnit.SECONDS);
                assertThat(fixture.accounts.list(Origin.OFFICIAL, shell))
                        .filteredOn(Account::defaultAccount)
                        .hasSize(1);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private Runnable createAfter(CountDownLatch start, String shell, String account) {
        return () -> {
            try {
                start.await();
                fixture.accounts.create(Origin.OFFICIAL, shell, account, "cli");
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted");
            }
        };
    }

    private String auditText() {
        try (Connection connection = fixture.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT actor, kind, target, detail FROM config_audit");
             ResultSet resultSet = statement.executeQuery()) {
            StringBuilder text = new StringBuilder();
            while (resultSet.next()) {
                text.append(resultSet.getString(1)).append('\n');
                text.append(resultSet.getString(2)).append('\n');
                text.append(resultSet.getString(3)).append('\n');
                text.append(resultSet.getString(4)).append('\n');
            }
            return text.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("cannot read audit", ex);
        }
    }

    private int credentialCount() {
        try (Connection connection = fixture.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM credentials");
             ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getInt(1);
        } catch (Exception ex) {
            throw new IllegalStateException("cannot count credentials", ex);
        }
    }

    private List<String> storedOrigin(String account) {
        try (Connection connection = fixture.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT origin FROM accounts WHERE account = ?")) {
            statement.setString(1, account);
            try (ResultSet resultSet = statement.executeQuery()) {
                java.util.ArrayList<String> origins = new java.util.ArrayList<>();
                while (resultSet.next()) {
                    origins.add(resultSet.getString(1));
                }
                return origins;
            }
        } catch (Exception ex) {
            throw new IllegalStateException("cannot read origin", ex);
        }
    }
}
