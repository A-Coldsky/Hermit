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
                + "。请到控制台打开这个规则包，在账号 " + who + " 的凭证里补上。不要把口令、Cookie 或令牌发给 AI。";
    }

    /** Console rows show {@code label} when it is set, otherwise the field name. */
    static String display(String name, Map<String, CredentialField> fields) {
        CredentialField field = fields == null || name == null ? null : fields.get(name);
        String label = field == null ? null : field.label();
        if (label == null || label.isBlank() || label.equals(name)) {
            return name == null ? "" : name;
        }
        return name + "（" + label + "）";
    }
}
