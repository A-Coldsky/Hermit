package dev.hermit.reef.conch;

import dev.hermit.common.model.ConchDoc;
import dev.hermit.common.parse.ConchParseException;
import dev.hermit.common.parse.ConchParser;
import dev.hermit.common.validate.AuthenticatorChecks;
import dev.hermit.common.validate.ValidationCodes;
import dev.hermit.common.validate.ConchValidator;
import dev.hermit.common.validate.DuplicateActions;
import dev.hermit.common.validate.DuplicateGroups;
import dev.hermit.common.validate.DuplicateScripts;
import dev.hermit.common.validate.IssueLines;
import dev.hermit.common.validate.ValidationIssue;
import dev.hermit.common.validate.ValidationResult;
import dev.hermit.reef.web.ApiException;
import dev.hermit.reef.web.ReefErrorCode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.springframework.stereotype.Service;

@Service
public class ConchUploadService {

    static final String VERSION_EXISTS = "该规则包版本已存在";
    static final String VERSION_NOT_GREATER = "版本必须高于已有的最高版本";

    private static final String YAML_ENTRY = "conch.yaml";

    private final ConchVersionLock locks;
    private final ConchUploadTransaction transaction;

    public ConchUploadService(ConchVersionLock locks, ConchUploadTransaction transaction) {
        this.locks = locks;
        this.transaction = transaction;
    }

    public UploadResponse upload(String actor, byte[] body) {
        if (actor == null || actor.isBlank()) {
            throw new ApiException(401, ReefErrorCode.UNAUTHORIZED, ReefErrorCode.UNAUTHORIZED.message());
        }
        PreparedUpload prepared = prepare(body);
        List<ValidationIssue> sameSite = transaction.sameSiteWarnings(prepared.doc());
        synchronized (locks.monitor(prepared.doc().conch())) {
            return transaction.store(actor, prepared, sameSite);
        }
    }

    static PreparedUpload prepare(byte[] body) {
        if (body == null || body.length == 0 || body.length > ConchPaths.MAX_UPLOAD_BYTES) {
            throw ConchRequestException.invalid(null);
        }
        byte[] yamlBytes = yamlBytes(body);
        String yaml = new String(yamlBytes, StandardCharsets.UTF_8);
        ArrayList<ValidationIssue> duplicates = new ArrayList<>();
        duplicates.addAll(DuplicateActions.find(yaml));
        duplicates.addAll(DuplicateScripts.find(yaml));
        duplicates.addAll(DuplicateGroups.find(yaml));
        ConchDoc doc;
        try {
            doc = ConchParser.parse(yaml);
        } catch (ConchParseException ex) {
            if (!duplicates.isEmpty()) {
                throw validationFailed(yaml, duplicates, List.of());
            }
            throw new ConchRequestException(
                    422,
                    ReefErrorCode.INVALID_INPUT,
                    ex.getMessage(),
                    new ParseFailure(ex.path(), ex.getMessage()));
        }
        ValidationResult result = ConchValidator.validate(doc, ConchValidator.Context.of(null));
        ValidationIssue unsupported = authenticatorNotYetPublishable(doc);
        if (!result.errors().isEmpty() || !duplicates.isEmpty() || unsupported != null) {
            ArrayList<ValidationIssue> errors = new ArrayList<>(result.errors());
            errors.addAll(duplicates);
            if (unsupported != null) {
                errors.add(unsupported);
            }
            throw validationFailed(yaml, errors, result.warnings());
        }
        return new PreparedUpload(yamlBytes, doc, result.warnings());
    }

    /**
     * 授权器（{@code driver: auth}）和引用它的包（{@code auth.use}）的写法已经能解析和校验，
     * 但 Reef 还没有依赖校验和分发，Crab 也还不能运行，先一律拒绝上传。
     */
    private static ValidationIssue authenticatorNotYetPublishable(ConchDoc doc) {
        boolean authenticator = AuthenticatorChecks.isAuthenticator(doc);
        if (!authenticator && (doc.auth() == null || !doc.auth().sharedDeclared())) {
            return null;
        }
        return new ValidationIssue(
                ValidationCodes.AUTHENTICATOR,
                authenticator ? "driver" : "auth",
                "这个版本的 Reef 还不能发布授权器（driver: auth）和引用授权器的规则包");
    }

    private static ConchRequestException validationFailed(
            String yaml,
            List<ValidationIssue> errors,
            List<ValidationIssue> warnings
    ) {
        return new ConchRequestException(
                422,
                ReefErrorCode.INVALID_INPUT,
                "规则包校验失败",
                new ValidationFailure(
                        IssueLines.locate(yaml, errors),
                        IssueLines.locate(yaml, warnings)));
    }

    private static byte[] yamlBytes(byte[] body) {
        if (!looksLikeZip(body)) {
            return body;
        }
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(body), StandardCharsets.UTF_8)) {
            ZipEntry entry = zip.getNextEntry();
            if (entry == null || entry.isDirectory() || !YAML_ENTRY.equals(entry.getName())) {
                throw ConchRequestException.invalid(null);
            }
            byte[] yaml = readLimited(zip);
            if (zip.getNextEntry() != null) {
                throw ConchRequestException.invalid(null);
            }
            if (yaml.length == 0) {
                throw ConchRequestException.invalid(null);
            }
            return yaml;
        } catch (ConchRequestException ex) {
            throw ex;
        } catch (IOException ex) {
            throw ConchRequestException.invalid(null);
        }
    }

    private static byte[] readLimited(ZipInputStream zip) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = zip.read(buffer)) >= 0) {
            total += read;
            if (total > ConchPaths.MAX_UPLOAD_BYTES) {
                throw ConchRequestException.invalid(null);
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static boolean looksLikeZip(byte[] body) {
        return body.length >= 4
                && body[0] == 'P'
                && body[1] == 'K'
                && (body[2] == 3 || body[2] == 5 || body[2] == 7);
    }

    record PreparedUpload(byte[] yaml, ConchDoc doc, List<ValidationIssue> warnings) {
    }
}
