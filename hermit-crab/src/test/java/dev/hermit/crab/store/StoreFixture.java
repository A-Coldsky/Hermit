package dev.hermit.crab.store;

import dev.hermit.crab.secure.CredentialCipher;
import dev.hermit.crab.secure.MasterKeyProvider;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;

final class StoreFixture implements AutoCloseable {
    final CrabPaths paths;
    final CrabDatabase database;
    final javax.sql.DataSource dataSource;
    final AccountStore accounts;
    final CredentialVault vault;
    final ConchConfigStore config;
    final ApiKeyService apiKeys;

    private StoreFixture(
            CrabPaths paths,
            CrabDatabase database,
            AccountStore accounts,
            CredentialVault vault,
            ConchConfigStore config,
            ApiKeyService apiKeys
    ) {
        this.paths = paths;
        this.database = database;
        this.dataSource = database.dataSource();
        this.accounts = accounts;
        this.vault = vault;
        this.config = config;
        this.apiKeys = apiKeys;
    }

    static StoreFixture open(Path root) {
        CrabPaths paths = new CrabPaths(root);
        CrabDatabase database = new CrabDatabase(paths);
        try {
            byte[] key = MasterKeyProvider.load(paths, Optional.empty(), name -> null);
            try {
                CredentialCipher cipher = new CredentialCipher(key);
                return new StoreFixture(
                        paths,
                        database,
                        new AccountStore(database.dataSource()),
                        new CredentialVault(database.dataSource(), cipher),
                        new ConchConfigStore(database.dataSource()),
                        new ApiKeyService(database.dataSource()));
            } finally {
                Arrays.fill(key, (byte) 0);
            }
        } catch (RuntimeException ex) {
            database.close();
            throw ex;
        }
    }

    @Override
    public void close() {
        database.close();
    }
}
