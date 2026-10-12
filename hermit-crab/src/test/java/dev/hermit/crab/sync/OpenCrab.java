package dev.hermit.crab.sync;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;

import javax.sql.DataSource;

import dev.hermit.crab.secure.CredentialCipher;
import dev.hermit.crab.secure.MasterKeyProvider;
import dev.hermit.crab.store.AccountStore;
import dev.hermit.crab.store.CrabDatabase;
import dev.hermit.crab.store.CrabPaths;
import dev.hermit.crab.store.CredentialVault;

final class OpenCrab implements AutoCloseable {

    final CrabPaths paths;
    final CrabDatabase database;
    final DataSource dataSource;
    final CredentialVault vault;
    final AccountStore accounts;

    private OpenCrab(
            CrabPaths paths,
            CrabDatabase database,
            CredentialVault vault,
            AccountStore accounts
    ) {
        this.paths = paths;
        this.database = database;
        this.dataSource = database.dataSource();
        this.vault = vault;
        this.accounts = accounts;
    }

    static OpenCrab open(Path root) {
        CrabPaths paths = new CrabPaths(root);
        CrabDatabase database = new CrabDatabase(paths);
        byte[] key = MasterKeyProvider.load(paths, Optional.empty(), name -> null);
        try {
            return new OpenCrab(
                    paths,
                    database,
                    new CredentialVault(database.dataSource(), new CredentialCipher(key)),
                    new AccountStore(database.dataSource()));
        } catch (RuntimeException ex) {
            database.close();
            throw ex;
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    @Override
    public void close() {
        database.close();
    }
}
