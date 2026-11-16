package dev.hermit.reef.conch;

import dev.hermit.common.validate.ConchValidator;
import dev.hermit.common.version.SemVer;
import dev.hermit.reef.audit.AuditAction;
import dev.hermit.reef.audit.AuditService;
import dev.hermit.reef.web.ApiException;
import dev.hermit.reef.web.ReefErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ConchDiscardService {

    /** 已发布，或任何不是 UPLOADED 的状态。 */
    static final String NOT_UPLOADED = "只能丢弃已上传、尚未发布的版本";

    private final ConchVersionLock locks;
    private final ConchRepository repository;
    private final ConchStorage storage;
    private final AuditService auditService;
    private final TransactionTemplate transactions;

    public ConchDiscardService(ConchVersionLock locks, ConchRepository repository, ConchStorage storage,
            AuditService auditService, PlatformTransactionManager transactionManager) {
        this.locks = locks;
        this.repository = repository;
        this.storage = storage;
        this.auditService = auditService;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /**
     * 丢掉尚未发布的版本。已发布版本返回 409，不改签名包。
     * 与上传共用按包名的锁，事务里再锁版本行。文件在提交之后、放开这把锁之前删除，
     * 避免和同版本的再次上传交错，也避免发布还拿着行锁时去动 ZIP。
     */
    public DiscardResponse discard(String actor, String conch, String version) {
        if (actor == null || actor.isBlank()) {
            throw new ApiException(401, ReefErrorCode.UNAUTHORIZED, ReefErrorCode.UNAUTHORIZED.message());
        }
        String canonical = canonical(conch, version);
        synchronized (locks.monitor(conch)) {
            DiscardResponse discarded = transactions.execute(status -> discardLocked(actor, conch, canonical));
            if (discarded == null) {
                throw new ApiException(500, ReefErrorCode.INTERNAL, ReefErrorCode.INTERNAL.message());
            }
            return discarded;
        }
    }

    private DiscardResponse discardLocked(String actor, String conch, String version) {
        VersionRow target = requireUploaded(conch, version);
        if (repository.deleteUploaded(target.conch(), target.version()) != 1) {
            throw notFound();
        }
        String storedConch = target.conch();
        String storedVersion = target.version();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                storage.deleteUploadedFiles(storedConch, storedVersion);
            }
        });
        auditService.record(actor, AuditAction.DISCARD, storedConch, storedVersion, null);
        return new DiscardResponse(storedConch, storedVersion);
    }

    private VersionRow requireUploaded(String conch, String version) {
        VersionRow target = repository.lockConch(conch).stream()
                .filter(row -> row.version().equals(version))
                .findFirst()
                .orElseThrow(ConchDiscardService::notFound);
        if (!"UPLOADED".equals(target.status())) {
            throw conflict(NOT_UPLOADED);
        }
        return target;
    }

    private static String canonical(String conch, String version) {
        // 包名要先通过 ConchValidator，不通过则 404，不拿锁、不进 SQL。版本仍按 SemVer 规范化。
        if (!ConchValidator.isConchName(conch) || !ConchPaths.singleSegment(version)) {
            throw notFound();
        }
        try {
            return SemVer.parse(version).toString();
        } catch (IllegalArgumentException ex) {
            throw notFound();
        }
    }

    private static ApiException notFound() {
        return new ApiException(404, ReefErrorCode.NOT_FOUND, ReefErrorCode.NOT_FOUND.message());
    }

    private static ApiException conflict(String message) {
        return new ApiException(409, ReefErrorCode.CONFLICT, message);
    }
}
