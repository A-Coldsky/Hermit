package dev.hermit.reef.conch;

import dev.hermit.common.model.ConchDoc;
import dev.hermit.common.parse.ConchParser;
import dev.hermit.common.validate.ValidationIssue;
import dev.hermit.reef.web.ApiException;
import dev.hermit.reef.web.ReefErrorCode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ConchSubmissionService {

    public static final int MAX_NOTE_LENGTH = 500;
    public static final int MAX_COMMENT_LENGTH = 500;

    private final ConchVersionLock locks;
    private final ConchUploadTransaction uploadTransaction;
    private final ConchSubmissionTransaction transaction;
    private final SubmissionStorage submissionStorage;

    public ConchSubmissionService(
            ConchVersionLock locks,
            ConchUploadTransaction uploadTransaction,
            ConchSubmissionTransaction transaction,
            SubmissionStorage submissionStorage) {
        this.locks = locks;
        this.uploadTransaction = uploadTransaction;
        this.transaction = transaction;
        this.submissionStorage = submissionStorage;
    }

    public SubmissionDetail submit(String actor, byte[] body, String rawNote) {
        requireActor(actor);
        String note = validateOptionalText(rawNote, MAX_NOTE_LENGTH, "提交说明");
        ConchUploadService.PreparedUpload prepared = ConchUploadService.prepare(body);
        List<ValidationIssue> sameSite = uploadTransaction.sameSiteWarnings(prepared.doc());
        synchronized (locks.monitor(prepared.doc().conch())) {
            return transaction.create(actor, prepared, sameSite, note);
        }
    }

    public List<SubmissionSummary> list(String actor, boolean admin, String rawStatus) {
        requireActor(actor);
        SubmissionStatus status = SubmissionStatus.parseOptional(rawStatus);
        return transaction.list(actor, admin, status);
    }

    public long pendingCount(String actor, boolean admin) {
        requireActor(actor);
        return transaction.pendingCount(actor, admin);
    }

    public SubmissionDetail detail(long id, String actor, boolean admin) {
        requireActor(actor);
        return transaction.detail(id, actor, admin);
    }

    public byte[] yaml(long id, String actor, boolean admin) {
        requireActor(actor);
        return transaction.yaml(id, actor, admin);
    }

    public SubmissionDetail withdraw(long id, String actor) {
        requireActor(actor);
        return transaction.withdraw(id, actor);
    }

    public SubmissionDetail reject(long id, String actor, String rawComment) {
        requireActor(actor);
        String comment = validateRequiredText(rawComment, MAX_COMMENT_LENGTH, "驳回原因");
        return transaction.reject(id, actor, comment);
    }

    public SubmissionDetail approve(long id, String actor, String rawComment) {
        requireActor(actor);
        String comment = validateOptionalText(rawComment, MAX_COMMENT_LENGTH, "审批意见");
        SubmissionRow row = transaction.requireRowForApprovalPrep(id);
        List<ValidationIssue> sameSite = List.of();
        if (row.status() == SubmissionStatus.PENDING) {
            byte[] yamlBytes = submissionStorage.readYaml(row.id());
            ConchDoc doc = ConchParser.parse(new String(yamlBytes, StandardCharsets.UTF_8));
            sameSite = uploadTransaction.sameSiteWarnings(doc);
        }
        synchronized (locks.monitor(row.conch())) {
            return transaction.approve(id, actor, comment, sameSite);
        }
    }

    private static void requireActor(String actor) {
        if (actor == null || actor.isBlank()) {
            throw new ApiException(401, ReefErrorCode.UNAUTHORIZED, ReefErrorCode.UNAUTHORIZED.message());
        }
    }

    private static String validateOptionalText(String raw, int maxLen, String label) {
        if (raw == null) {
            return null;
        }
        String normalized = raw.replace("\r\n", "\n").replace('\r', '\n').trim();
        if (normalized.isEmpty()) {
            return null;
        }
        return checkText(normalized, maxLen, label);
    }

    private static String validateRequiredText(String raw, int maxLen, String label) {
        if (raw == null) {
            throw new ApiException(400, ReefErrorCode.INVALID_INPUT, label + "不能为空");
        }
        String normalized = raw.replace("\r\n", "\n").replace('\r', '\n').trim();
        if (normalized.isEmpty()) {
            throw new ApiException(400, ReefErrorCode.INVALID_INPUT, label + "不能为空");
        }
        return checkText(normalized, maxLen, label);
    }

    private static String checkText(String normalized, int maxLen, String label) {
        if (normalized.length() > maxLen) {
            throw new ApiException(400, ReefErrorCode.INVALID_INPUT, label + "不能超过 " + maxLen + " 个字符");
        }
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if ((c < 0x20 && c != '\n' && c != '\t') || c == 0x7f) {
                throw new ApiException(400, ReefErrorCode.INVALID_INPUT, label + "包含非法控制字符");
            }
        }
        return normalized;
    }
}
