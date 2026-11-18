package dev.hermit.crab.engine;

/**
 * 当前线程正在执行的规则包和能力。空闲截断的日志只读这两个名字，不读路径或正文。
 * 测试登录没有能力名时，能力留空。
 */
final class CallSite {
    private static final ThreadLocal<CallSite> CURRENT = new ThreadLocal<>();

    private final String conch;
    private final String capability;

    private CallSite(String conch, String capability) {
        this.conch = text(conch);
        this.capability = text(capability);
    }

    static void begin(ExecutionRequest request) {
        if (request == null) {
            CURRENT.remove();
            return;
        }
        CURRENT.set(new CallSite(request.conch(), request.action()));
    }

    static void end() {
        CURRENT.remove();
    }

    static CallSite current() {
        return CURRENT.get();
    }

    String conch() {
        return conch;
    }

    String capability() {
        return capability;
    }

    private static String text(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }
}
