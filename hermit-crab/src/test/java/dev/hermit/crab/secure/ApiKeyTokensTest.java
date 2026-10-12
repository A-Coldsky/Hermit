package dev.hermit.crab.secure;

import dev.hermit.crab.testing.SecretChecks;
import org.junit.jupiter.api.Test;

import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyTokensTest {
    @Test
    void issuedKeyIsHkPrefixPlusBase62AndOnlyTheHashIsComparable() {
        ApiKeyTokens.Issued issued = ApiKeyTokens.issue();
        assertThat(issued.plaintext()).startsWith("hk_");
        assertThat(issued.plaintext()).matches("hk_[0-9A-Za-z]+");
        assertThat(issued.prefix()).hasSize(ApiKeyTokens.PREFIX_LENGTH);
        assertThat(issued.plaintext()).startsWith(issued.prefix());
        assertThat(issued.hashHex()).matches("[0-9a-f]{64}");
        assertThat(issued.hashHex()).isEqualTo(HexFormat.of().formatHex(ApiKeyTokens.sha256(issued.plaintext())));
        assertThat(Base62.decode(issued.plaintext().substring(3))).hasSize(32);
        assertThat(ApiKeyTokens.constantTimeEquals(ApiKeyTokens.sha256(issued.plaintext()), issued.hashHex())).isTrue();
        String wrong = issued.plaintext().substring(0, issued.plaintext().length() - 1) + "0";
        if (wrong.equals(issued.plaintext())) {
            wrong = issued.plaintext() + "0";
        }
        assertThat(ApiKeyTokens.constantTimeEquals(ApiKeyTokens.sha256(wrong), issued.hashHex())).isFalse();
        SecretChecks.absent(issued.toString(), issued.plaintext());
        SecretChecks.absent(issued.hashHex(), issued.plaintext());
    }

    @Test
    void twoIssuesDiffer() {
        ApiKeyTokens.Issued first = ApiKeyTokens.issue();
        ApiKeyTokens.Issued second = ApiKeyTokens.issue();
        assertThat(first.plaintext()).isNotEqualTo(second.plaintext());
        assertThat(first.hashHex()).isNotEqualTo(second.hashHex());
    }
}
