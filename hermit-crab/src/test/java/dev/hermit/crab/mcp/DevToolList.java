package dev.hermit.crab.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** 测试用：开发者工具不平铺在 tools/list 里，把入口工具 hermit_dev 返回的清单并进去再断言。 */
public final class DevToolList {
    private DevToolList() {
    }

    public static JsonNode expand(JsonNode listed, JsonNode overviewCall) {
        ObjectNode copy = listed.deepCopy();
        ArrayNode tools = (ArrayNode) copy.path("result").path("tools");
        for (JsonNode tool : overviewCall.path("result").path("structuredContent").path("tools")) {
            tools.add(tool);
        }
        return copy;
    }
}
