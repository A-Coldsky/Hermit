package dev.hermit.reef.tide;

import dev.hermit.common.report.ReportBatch;
import dev.hermit.common.report.ReportJson;
import dev.hermit.reef.security.AgentAccessFilter;
import dev.hermit.reef.web.ApiResponse;
import dev.hermit.reef.web.TraceIds;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ReportController {

    private final ReportIngestService ingestService;

    public ReportController(ReportIngestService ingestService) {
        this.ingestService = ingestService;
    }

    @PostMapping("/agent/reports")
    public ApiResponse<ReportAccepted> reports(HttpServletRequest request) throws IOException {
        byte[] body = readBody(request);
        ReportBatch batch;
        try {
            batch = ReportJson.readBatch(new String(body, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException ex) {
            throw TideErrors.unprocessable();
        }
        ReportRules.check(batch, request.getHeader(AgentAccessFilter.INSTANCE_HEADER));
        return ApiResponse.ok(ingestService.ingest(batch), TraceIds.current(request));
    }

    private static byte[] readBody(HttpServletRequest request) throws IOException {
        long declared = request.getContentLengthLong();
        if (declared > ReportRules.MAX_BODY_BYTES) {
            throw TideErrors.tooLarge();
        }
        InputStream input = request.getInputStream();
        ByteArrayOutputStream output = new ByteArrayOutputStream(declared > 0 ? (int) declared : 256);
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > ReportRules.MAX_BODY_BYTES) {
                throw TideErrors.tooLarge();
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }
}
