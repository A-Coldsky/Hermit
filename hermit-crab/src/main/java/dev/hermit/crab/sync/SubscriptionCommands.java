package dev.hermit.crab.sync;

import java.util.concurrent.locks.Lock;

import javax.sql.DataSource;

import dev.hermit.common.error.ErrorCode;
import dev.hermit.common.validate.ConchValidator;
import dev.hermit.crab.engine.ConchRegistry;
import dev.hermit.crab.store.Actors;
import dev.hermit.crab.store.AuditKind;
import dev.hermit.crab.store.AuditLog;
import dev.hermit.crab.store.CrabPaths;
import dev.hermit.crab.store.Origin;

/**
 * Unsubscribe shared by sync and by a Crab that has no {@code reef.url}.
 * The authorized ConchDoc already handed to the engine is not replaced (audit A01).
 */
public final class SubscriptionCommands {
    private SubscriptionCommands() {
    }

    public static void unsubscribe(
            Lock syncLock,
            CrabPaths paths,
            ConchRegistry registry,
            DisabledConches disabledConches,
            Subscriptions subscriptions,
            DataSource dataSource,
            String conch,
            String actor
    ) {
        if (!Actors.CLI.equals(actor) && !Actors.CONSOLE.equals(actor)) {
            throw new IllegalArgumentException("actor must be cli or console");
        }
        if (syncLock == null) {
            unsubscribeLocked(paths, registry, disabledConches, subscriptions, dataSource, conch, actor);
            return;
        }
        syncLock.lock();
        try {
            unsubscribeLocked(paths, registry, disabledConches, subscriptions, dataSource, conch, actor);
        } finally {
            syncLock.unlock();
        }
    }

    private static void unsubscribeLocked(
            CrabPaths paths,
            ConchRegistry registry,
            DisabledConches disabledConches,
            Subscriptions subscriptions,
            DataSource dataSource,
            String conch,
            String actor
    ) {
        if (!ConchValidator.isConchName(conch)) {
            throw new SubscriptionException(400, ErrorCode.INVALID_INPUT, SubscriptionException.NAME_INVALID);
        }
        if (!subscriptions.contains(conch)) {
            throw new SubscriptionException(404, ErrorCode.NOT_FOUND, SubscriptionException.NOT_SUBSCRIBED);
        }
        // A local package that shares the name is left on disk. Subscribe already rejects that name.
        if (registry.origin(conch) != Origin.LOCAL) {
            synchronized (ConchFileLocks.of(conch)) {
                registry.remove(conch);
                ConchFiles.clearCurrent(paths, conch);
                // Drop the install record so a later subscribe installs the manifest again.
                // A lost pointer while still subscribed leaves the record in place.
                new SyncStore(dataSource).clearInstallHistory(conch);
            }
        }
        disabledConches.clear(conch);
        if (!subscriptions.delete(conch)) {
            return;
        }
        AuditLog.record(dataSource, actor, AuditKind.CONCH_UNSUBSCRIBED, "OFFICIAL/" + conch, conch);
    }
}
