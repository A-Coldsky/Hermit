package dev.hermit.crab.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import dev.hermit.common.model.CredentialField;

/**
 * Chinese explanation when a call cannot run because required credentials are absent.
 * Names the account, the field, and the label the console shows. Never includes a secret value.
 */
public final class CredentialMessages {
    /** Ordinary calls with no account. Trial runs replace this exact sentence with one that names the package. */
    public static final String NO_ACCOUNT = "还没有账号。请到控制台为这个规则包新建账号并填写凭证。";

    private CredentialMessages() {
    }

    public static String missing(String account, List<String> names, Map<String, CredentialField> fields) {
        String who = account == null || account.isBlank() ? "默认账号" : account;
        ArrayList<String> shown = new ArrayList<>();
        if (names != null) {
            for (String name : names) {
                shown.add(display(name, fields));
            }
        }
        return "账号 " + who + " 还没填写凭证：" + String.join("、", shown)
                + "。请点账号 " + who + " 的「填写凭证」补上。不要把口令、Cookie 或令牌发给 AI。";
    }

    /** True when the failure never stored a trace, so the id is only a request number. */
    public static boolean untraced(String message) {
        return message != null && (message.contains("还没填写凭证") || "账号名不合法。".equals(message));
    }

    /** Shown as {@code 显示名（键名）}. A blank label, or a label equal to the key, is just the key. */
    static String display(String name, Map<String, CredentialField> fields) {
        CredentialField field = fields == null || name == null ? null : fields.get(name);
        String label = field == null ? null : field.label();
        if (name == null) {
            return "";
        }
        if (label == null || label.isBlank() || label.equals(name)) {
            return name;
        }
        return label + "（" + name + "）";
    }
}
