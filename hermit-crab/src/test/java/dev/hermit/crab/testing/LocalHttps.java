package dev.hermit.crab.testing;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.X509ExtendedKeyManager;

/** 只听 127.0.0.1 的 HTTPS。证书链可以在连接之间更换。 */
public final class LocalHttps implements AutoCloseable {

    private final SSLServerSocket server;
    private final ExecutorService workers;
    private final AtomicReference<X509Certificate[]> chain;
    private final AtomicReference<PrivateKey> key;
    private final List<String> requests = Collections.synchronizedList(new ArrayList<>());
    private volatile boolean keepAlive;

    private LocalHttps(
            SSLServerSocket server,
            ExecutorService workers,
            AtomicReference<X509Certificate[]> chain,
            AtomicReference<PrivateKey> key
    ) {
        this.server = server;
        this.workers = workers;
        this.chain = chain;
        this.key = key;
    }

    public static LocalHttps start(X509Certificate[] certificates, PrivateKey privateKey) throws Exception {
        AtomicReference<X509Certificate[]> chain = new AtomicReference<>(certificates);
        AtomicReference<PrivateKey> key = new AtomicReference<>(privateKey);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(new KeyManager[] {new SelectingKeyManager(chain, key)}, null, null);
        SSLServerSocket server = (SSLServerSocket) context.getServerSocketFactory()
                .createServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        ExecutorService workers = Executors.newCachedThreadPool(task -> {
            Thread thread = new Thread(task, "lab-https");
            thread.setDaemon(true);
            return thread;
        });
        LocalHttps https = new LocalHttps(server, workers, chain, key);
        workers.submit(https::acceptLoop);
        return https;
    }

    public void use(X509Certificate[] certificates, PrivateKey privateKey, boolean keepAlive) {
        chain.set(certificates);
        key.set(privateKey);
        this.keepAlive = keepAlive;
    }

    public int port() {
        return server.getLocalPort();
    }

    public String url() {
        return "https://127.0.0.1:" + port() + "/";
    }

    /** 收到的请求头，按 ISO-8859-1 记下。默认响应仍是 200 ok。 */
    public List<String> requests() {
        return List.copyOf(requests);
    }

    @Override
    public void close() {
        try {
            server.close();
        } catch (IOException ignored) {
            // 测试结束。
        }
        workers.shutdownNow();
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                Socket socket = server.accept();
                workers.submit(() -> respond(socket));
            } catch (IOException ex) {
                if (server.isClosed()) {
                    return;
                }
            }
        }
    }

    private void respond(Socket socket) {
        try (Socket client = socket) {
            client.setSoTimeout(5_000);
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();
            while (true) {
                byte[] header = readHeaders(in);
                if (header == null) {
                    return;
                }
                requests.add(new String(header, StandardCharsets.ISO_8859_1));
                byte[] body = "ok".getBytes(StandardCharsets.US_ASCII);
                String connection = keepAlive ? "keep-alive" : "close";
                byte[] response = ("HTTP/1.1 200 OK\r\nContent-Length: " + body.length
                        + "\r\nContent-Type: text/plain\r\nConnection: " + connection + "\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII);
                out.write(response);
                out.write(body);
                out.flush();
                if (!keepAlive) {
                    return;
                }
            }
        } catch (IOException ignored) {
            // 对端关掉连接。
        }
    }

    private static byte[] readHeaders(InputStream in) throws IOException {
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        int previous = 0;
        int breaks = 0;
        while (header.size() < 8192) {
            int value = in.read();
            if (value < 0) {
                return header.size() == 0 ? null : header.toByteArray();
            }
            header.write(value);
            if (previous == '\r' && value == '\n') {
                breaks++;
                if (breaks == 2) {
                    return header.toByteArray();
                }
            } else if (value != '\r') {
                breaks = 0;
            }
            previous = value;
        }
        return header.toByteArray();
    }

    private static final class SelectingKeyManager extends X509ExtendedKeyManager {
        private final AtomicReference<X509Certificate[]> chain;
        private final AtomicReference<PrivateKey> key;

        private SelectingKeyManager(AtomicReference<X509Certificate[]> chain, AtomicReference<PrivateKey> key) {
            this.chain = chain;
            this.key = key;
        }

        @Override
        public String[] getClientAliases(String keyType, Principal[] issuers) {
            return null;
        }

        @Override
        public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) {
            return null;
        }

        @Override
        public String[] getServerAliases(String keyType, Principal[] issuers) {
            return new String[] {"lab"};
        }

        @Override
        public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
            return "lab";
        }

        @Override
        public X509Certificate[] getCertificateChain(String alias) {
            return chain.get();
        }

        @Override
        public PrivateKey getPrivateKey(String alias) {
            return key.get();
        }
    }
}
