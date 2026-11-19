package dev.hermit.crab.engine;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次脚本抛出的说明，以及它落在哪个脚本库或步骤自己的 {@code code} 上。
 * {@code column} 为 null 时 Rhino 没有给出列号。说明尚未打码。
 */
final class ScriptFault {

    static final String CODE = "code";

    final String source;
    final int line;
    final Integer column;
    final String message;

    ScriptFault(String source, int line, Integer column, String message) {
        this.source = source == null || source.isBlank() ? CODE : source;
        this.line = Math.max(0, line);
        this.column = column != null && column > 0 ? column : null;
        this.message = message == null ? "" : message;
    }

    String summary() {
        String where = CODE.equals(source) ? "脚本 code" : "脚本库 " + source;
        StringBuilder out = new StringBuilder(where);
        if (line > 0) {
            out.append(" 第 ").append(line).append(" 行");
            if (column != null) {
                out.append("第 ").append(column).append(" 列");
            }
        }
        if (!message.isEmpty()) {
            out.append('：').append(message);
        }
        return out.toString();
    }

    Map<String, Object> view() {
        LinkedHashMap<String, Object> view = new LinkedHashMap<>();
        view.put("source", source);
        view.put("line", line);
        view.put("column", column);
        view.put("message", message);
        return view;
    }
}
