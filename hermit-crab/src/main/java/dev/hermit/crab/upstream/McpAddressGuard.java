package dev.hermit.crab.upstream;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;

import dev.hermit.common.mcp.McpAddresses;

/** 连接前的主机和地址检查。字面量不查 DNS；名字留给解析之后的 {@link #rejected}。 */
public final class McpAddressGuard {

    private static final byte[] EC2_METADATA = new byte[] {
            (byte) 0xfd, 0x00, 0x0e, (byte) 0xc2,
            0, 0, 0, 0,
            0, 0, 0, 0,
            0, 0, 0x02, 0x54
    };

    private McpAddressGuard() {
    }

    /** 包里的 URL 本身是回环时，才允许连到回环地址。 */
    public static boolean packageAllowsLoopback(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        String normalized = McpAddresses.normalizeHost(host);
        if ("localhost".equals(normalized)) {
            return true;
        }
        if (normalized.indexOf(':') < 0 && !normalized.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) {
            return false;
        }
        try {
            return loopback(InetAddress.getByName(normalized));
        } catch (UnknownHostException ex) {
            return false;
        }
    }

    /**
     * 链路本地、云元数据，以及包本身不是回环时的回环地址。
     * IPv4 映射的 IPv6 按里面的 IPv4 判断。
     */
    public static boolean rejected(InetAddress address, boolean allowLoopback) {
        if (address == null) {
            return true;
        }
        if (loopback(address)) {
            return !allowLoopback;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            return ipv4LinkLocal(bytes);
        }
        if (address instanceof Inet6Address) {
            if (mapped(bytes)) {
                byte[] v4 = Arrays.copyOfRange(bytes, 12, 16);
                return ipv4LinkLocal(v4) || (!allowLoopback && (v4[0] & 0xff) == 127);
            }
            if (Arrays.equals(bytes, EC2_METADATA)) {
                return true;
            }
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            return first == 0xfe && (second & 0xc0) == 0x80;
        }
        return false;
    }

    private static boolean loopback(InetAddress address) {
        if (address.isLoopbackAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        return bytes.length == 16 && mapped(bytes) && (bytes[12] & 0xff) == 127;
    }

    private static boolean ipv4LinkLocal(byte[] bytes) {
        return bytes.length == 4 && (bytes[0] & 0xff) == 169 && (bytes[1] & 0xff) == 254;
    }

    private static boolean mapped(byte[] bytes) {
        if (bytes.length != 16) {
            return false;
        }
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        return (bytes[10] & 0xff) == 0xff && (bytes[11] & 0xff) == 0xff;
    }
}
