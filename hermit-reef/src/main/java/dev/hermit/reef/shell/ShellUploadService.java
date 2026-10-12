package dev.hermit.reef.shell;

import dev.hermit.common.model.ShellDoc;
import dev.hermit.common.parse.ShellParseException;
import dev.hermit.common.parse.ShellParser;
import dev.hermit.common.validate.ShellValidator;
import dev.hermit.common.validate.ValidationIssue;
import dev.hermit.common.validate.ValidationResult;
import dev.hermit.reef.web.ReefErrorCode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.springframework.stereotype.Service;

@Service
public class ShellUploadService {

    static final String VERSION_EXISTS = "shell version already exists";
    static final String VERSION_NOT_GREATER = "version is not greater than the highest existing version";

    private static final String YAML_ENTRY = "shell.yaml";

    private final ShellVersionLock locks;
    private final ShellUploadTransaction transaction;

    public ShellUploadService(ShellVersionLock locks, ShellUploadTransaction transaction) {
        this.locks = locks;
        this.transaction = transaction;
    }

    public UploadResponse upload(String actor, byte[] body) {
        PreparedUpload prepared = prepare(body);
        synchronized (locks.monitor(prepared.doc().shell())) {
            return transaction.store(actor, prepared);
        }
    }

    private static PreparedUpload prepare(byte[] body) {
        if (body == null || body.length == 0 || body.length > ShellPaths.MAX_UPLOAD_BYTES) {
            throw ShellRequestException.invalid(null);
        }
        byte[] yamlBytes = yamlBytes(body);
        String yaml = new String(yamlBytes, StandardCharsets.UTF_8);
        ShellDoc doc;
        try {
            doc = ShellParser.parse(yaml);
        } catch (ShellParseException ex) {
            throw new ShellRequestException(
                    422,
                    ReefErrorCode.INVALID_INPUT,
                    ex.getMessage(),
                    new ParseFailure(ex.path(), ex.getMessage()));
        }
        ValidationResult result = ShellValidator.validate(doc, ShellValidator.Context.of(null));
        if (!result.errors().isEmpty()) {
            throw ShellRequestException.invalid(new ValidationFailure(result.errors(), result.warnings()));
        }
        return new PreparedUpload(yamlBytes, doc, result.warnings());
    }

    private static byte[] yamlBytes(byte[] body) {
        if (!looksLikeZip(body)) {
            return body;
        }
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(body), StandardCharsets.UTF_8)) {
            ZipEntry entry = zip.getNextEntry();
            if (entry == null || entry.isDirectory() || !YAML_ENTRY.equals(entry.getName())) {
                throw ShellRequestException.invalid(null);
            }
            byte[] yaml = readLimited(zip);
            if (zip.getNextEntry() != null) {
                throw ShellRequestException.invalid(null);
            }
            if (yaml.length == 0) {
                throw ShellRequestException.invalid(null);
            }
            return yaml;
        } catch (ShellRequestException ex) {
            throw ex;
        } catch (IOException ex) {
            throw ShellRequestException.invalid(null);
        }
    }

    private static byte[] readLimited(ZipInputStream zip) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = zip.read(buffer)) >= 0) {
            total += read;
            if (total > ShellPaths.MAX_UPLOAD_BYTES) {
                throw ShellRequestException.invalid(null);
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

    record PreparedUpload(byte[] yaml, ShellDoc doc, List<ValidationIssue> warnings) {
    }
}
