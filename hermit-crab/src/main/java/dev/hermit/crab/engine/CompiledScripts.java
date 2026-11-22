package dev.hermit.crab.engine;

import java.util.LinkedHashMap;
import java.util.Map;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.Script;
import org.mozilla.javascript.Scriptable;

/**
 * 按源码缓存解释模式的编译结果，省掉每次调用重新解析脚本库和 code。
 * 编译结果不带作用域，每次仍在新建的标准对象上执行。编译失败不缓存，下次照样报同一个错。
 */
final class CompiledScripts {
    static final int MAX_ENTRIES = 64;
    /** 缓存里源码总字符数的上限。单份脚本库最大 256 KB，一条条目可以由多份拼成。 */
    static final long MAX_CHARS = 16L * 1024 * 1024;

    private static final Map<Key, Script> CACHE = new LinkedHashMap<>(16, 0.75f, true);
    private static long chars;

    private CompiledScripts() {
    }

    /** 与 {@code cx.evaluateString(scope, source, name, 1, null)} 等价。 */
    static Object evaluate(Context cx, Scriptable scope, String source, String name) {
        // 带标签的模板字面量把 strings 数组记在编译结果上，连同第一次调用的全局对象；这类源码每次重新编译。
        if (source.indexOf('`') >= 0) {
            return cx.evaluateString(scope, source, name, 1, null);
        }
        Script script = compiled(cx, source, name);
        return script == null ? null : script.exec(cx, scope);
    }

    static int size() {
        synchronized (CACHE) {
            return CACHE.size();
        }
    }

    static void clear() {
        synchronized (CACHE) {
            CACHE.clear();
            chars = 0;
        }
    }

    private static Script compiled(Context cx, String source, String name) {
        Key key = new Key(name, source);
        synchronized (CACHE) {
            Script hit = CACHE.get(key);
            if (hit != null) {
                return hit;
            }
        }
        Script script = cx.compileString(source, name, 1, null);
        if (script == null || source.length() > MAX_CHARS) {
            return script;
        }
        synchronized (CACHE) {
            if (CACHE.putIfAbsent(key, script) == null) {
                chars += source.length();
            }
            var oldest = CACHE.entrySet().iterator();
            while ((CACHE.size() > MAX_ENTRIES || chars > MAX_CHARS) && oldest.hasNext()) {
                chars -= oldest.next().getKey().source.length();
                oldest.remove();
            }
        }
        return script;
    }

    private record Key(String name, String source) {
    }
}
