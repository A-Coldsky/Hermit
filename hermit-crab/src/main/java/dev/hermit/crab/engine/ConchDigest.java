package dev.hermit.crab.engine;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

import dev.hermit.common.model.ConchDoc;

/**
 * 规则包内容身份。与 {@code Invoker.snapshotToken} 是同一份规范 JSON 的 SHA-256。
 * 本地覆盖可以不改版本号，所以暂停登录不能只比版本。
 */
public final class ConchDigest {

    /**
     * 键按字典序的紧凑 JSON。同一份文档两次相同；不用对象标识 {@code hashCode}。
     * 记录组件和嵌套映射都参与，所以步骤、confirm、read_only、上游 URL 变了摘要就变。
     */
    private static final JsonMapper CANONICAL = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    private ConchDigest() {
    }

    public static String sha256(ConchDoc doc) {
        try {
            byte[] body = CANONICAL.writeValueAsBytes(doc);
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(body == null ? new byte[0] : body);
            return HexFormat.of().formatHex(hash);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("snapshot token", ex);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }
}
