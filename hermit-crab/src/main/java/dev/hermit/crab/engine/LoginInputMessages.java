package dev.hermit.crab.engine;

/**
 * 登录验证码暂停时给人看的句子。验证码本身不出现在这些句子里。
 */
public final class LoginInputMessages {

    /** 方案里的那句，后面再加控制台账号页路径。 */
    public static final String SENTENCE = "这个账号登录需要输入验证码，请到 Crab 控制台的账号页输入后再试";

    public static final String UPDATED = "规则包已经更新，请重新登录";

    public static final String NONE = "没有待输入的登录。";

    /** 待输入还在，但这一步不是图片验证码。 */
    public static final String NO_IMAGE = "没有验证码图片。";

    public static final String TOO_FAST = "换得太快，请稍后再试。";

    public static final String QUOTA = "换验证码的次数已经用完。请放弃这次登录后重新登录。";

    public static final String NO_RESEND = "这一步不能重新发送。";

    public static final String BAD_VALUE = "验证码必须是 1 到 64 个字符，且不能含控制字符。";

    public static final String NOT_STRING = "验证码必须是字符串。";

    /** 步骤日志的路径。没有单独的说明字段，路径也不含验证码。 */
    public static final String WAITING = "等待用户输入";

    private LoginInputMessages() {
    }

    public static String page(String conch) {
        return "/console/conches/" + conch;
    }

    public static String required(String conch) {
        return SENTENCE + " " + page(conch);
    }

    /**
     * 不 trim。{@code null} 表示可以接着登录。长度按 {@link String#length()}，
     * 与打码「至少 4 个字符」用的是同一种计数。
     */
    public static String rejectValue(String value) {
        if (value == null || value.isEmpty() || value.length() > 64) {
            return BAD_VALUE;
        }
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) {
                return BAD_VALUE;
            }
        }
        return null;
    }
}
