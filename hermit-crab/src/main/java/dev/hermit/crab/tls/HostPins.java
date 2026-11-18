package dev.hermit.crab.tls;

import java.time.Instant;
import java.util.Set;

/**
 * 按 {@code host:port} 登记的 leaf 指纹。库表实现见 {@code TlsHostStore}。
 * {@link #find} 收到的 host 已转成小写，port 是握手会话里的端口。
 * 命中后跳过证书链和主机名校验，但证书本身过期或尚未生效仍然拒绝。
 */
public interface HostPins {

    HostPins NONE = (host, port) -> null;

    /**
     * @param host 小写主机名或 IP
     * @param port 握手端口，未知时不会来查询
     * @return 这台主机登记的指纹；没有登记时返回 null
     */
    Pin find(String host, int port);

    /**
     * 当前登记的 {@code host:port}。快照会抄下这一刻的集合，用来判断这次更换有没有删掉指纹。
     * 删掉指纹后要清掉 TLS 会话和空闲连接。默认空集，调用方不报告键，因此看不出删除。
     * 会删行的实现必须覆盖这个方法。
     */
    default Set<String> keys() {
        return Set.of();
    }

    /**
     * @param sha256 leaf 证书编码的 SHA-256，十六进制，比较时忽略大小写
     * @param notAfter 登记时看到的到期时间，校验以对端证书自己的有效期为准
     */
    record Pin(String sha256, Instant notAfter) {
        public Pin {
            if (sha256 == null || sha256.isBlank()) {
                throw new IllegalArgumentException("sha256 is required");
            }
            sha256 = sha256.strip();
        }
    }
}
