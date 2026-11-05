package dev.hermit.crab.sync;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Locks the short Chinese sentences. Error codes stay on the callers that throw these constants. */
class SubscriptionCopyTest {

    @Test
    void rejectionMessagesAreChinese() {
        assertThat(SubscriptionException.NOT_IN_CATALOG).isEqualTo("Reef 上当前不能订阅这个规则包。");
        assertThat(SubscriptionException.NOT_SUBSCRIBED).isEqualTo("还没有订阅这个规则包。");
        assertThat(SubscriptionException.NAME_INVALID).isEqualTo("规则包名称无效。");
        assertThat(SubscriptionException.NOT_CONFIGURED).isEqualTo("尚未连接 Reef，无法同步。");
        assertThat(SyncException.TOKEN_REJECTED).isEqualTo("令牌已失效，请重新导入引导文件。");
    }
}
