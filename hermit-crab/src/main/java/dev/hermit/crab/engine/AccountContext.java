package dev.hermit.crab.engine;

import java.util.Map;

public interface AccountContext {

    /** 明文凭证，只在执行期间使用。 */
    Map<String, String> credentials();

    /** 调用方已合并默认值后的配置。 */
    Map<String, String> config();
}
