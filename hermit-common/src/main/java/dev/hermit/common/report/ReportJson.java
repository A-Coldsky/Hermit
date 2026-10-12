package dev.hermit.common.report;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

public final class ReportJson {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private ReportJson() {
    }

    public static String write(ReportBatch batch) {
        try {
            return MAPPER.writeValueAsString(batch);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("上报报文无法序列化", ex);
        }
    }

    public static ReportBatch readBatch(String json) {
        try {
            return MAPPER.readValue(json, ReportBatch.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("上报报文无法解析", ex);
        }
    }
}
