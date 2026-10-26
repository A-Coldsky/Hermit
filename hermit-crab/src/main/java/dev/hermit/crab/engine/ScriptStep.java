package dev.hermit.crab.engine;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.mozilla.javascript.ClassShutter;
import org.mozilla.javascript.ConsString;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.EcmaError;
import org.mozilla.javascript.EvaluatorException;
import org.mozilla.javascript.JavaScriptException;
import org.mozilla.javascript.LambdaFunction;
import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.NativeObject;
import org.mozilla.javascript.RhinoException;
import org.mozilla.javascript.ScriptRuntime;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;
import org.mozilla.javascript.UniqueTag;
import org.mozilla.javascript.WrapFactory;
import org.mozilla.javascript.WrappedException;
import org.mozilla.javascript.lc.type.TypeInfo;

import dev.hermit.common.error.ErrorCode;

/**
 * Rhino 只在解释模式下计数。setOptimizationLevel(-1) 在 1.9 里等价于 setInterpretedMode(true)。
 * Rhino 异常的 details 会带上源码行，对外消息只保留错误类型和行号。
 */
final class ScriptStep {

    static final int DEFAULT_TIMEOUT_MS = 1000;
    static final int MAX_TIMEOUT_MS = 5000;
    static final int MAX_SOURCE_BYTES = 64 * 1024;
    static final int MAX_RESULT_BYTES = 1024 * 1024;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> FIELDS = Set.of("code", "into", "timeout_ms");
    private static final Set<String> SAFE_NAMES = Set.of(
            "Error", "EvalError", "InternalError", "RangeError", "ReferenceError",
            "SyntaxError", "TypeError", "URIError", "EvaluatorException",
            "JavaScriptException", "EcmaError", "WrappedException");
    private static final String[] DENIED = {
            "eval", "Function", "Script", "load", "print", "printErr", "quit",
            "readFile", "readUrl", "read", "write", "writeln", "uneval", "Continuation",
            "importPackage", "importClass", "JavaAdapter", "JavaImporter", "Packages",
            "java", "javax", "org", "com", "edu", "net", "getClass"
    };
    private static final ClassShutter SHUTTER = name -> false;
    private static final WrapFactory WRAP = new SandboxWrap();
    private static final String PREFIX = "(function(){\n";
    private static final String SUFFIX = "\n})()";

    private final SandboxFactory factory = new SandboxFactory();
    private final ThreadLocal<Window> windows = new ThreadLocal<>();

    Outcome run(Map<String, Object> spec, Map<String, Object> vars, long capabilityDeadlineNanos) {
        try {
            return execute(spec, vars, capabilityDeadlineNanos);
        } catch (RuleProblem | DeadlineExceeded ex) {
            throw ex;
        } catch (ScriptTimeExceeded ex) {
            throw timeout(ex);
        } catch (StackOverflowError ex) {
            throw mismatch("脚本调用栈过深");
        } catch (RuntimeException ex) {
            throw described(ex);
        }
    }

    private Outcome execute(Map<String, Object> spec, Map<String, Object> vars, long capabilityDeadlineNanos) {
        rejectUnknown(spec);
        String code = code(spec.get("code"));
        String into = into(spec.get("into"));
        int timeoutMs = timeoutMs(spec.get("timeout_ms"));
        long now = System.nanoTime();
        if (now >= capabilityDeadlineNanos) {
            throw new DeadlineExceeded(-1, "script", List.of());
        }
        long scriptDeadline = deadline(now, timeoutMs);
        windows.set(new Window(scriptDeadline, capabilityDeadlineNanos));
        try {
            Object raw = factory.call(cx -> evaluate(cx, code, vars));
            Object converted = fromJs(raw, new IdentityHashMap<>());
            enforceSize(converted);
            apply(into, converted, vars);
            return new Outcome(null, null, null, null, false);
        } catch (ScriptTimeExceeded ex) {
            throw timeout(ex);
        } finally {
            windows.remove();
        }
    }

    private Object evaluate(Context cx, String code, Map<String, Object> vars) {
        Scriptable scope = newScope(cx);
        ScriptableObject.putProperty(scope, "vars", toJs(cx, scope, copyWithoutCredential(vars), new IdentityHashMap<>()));
        return cx.evaluateString(scope, PREFIX + code + SUFFIX, "script", 1, null);
    }

    private Scriptable newScope(Context cx) {
        // 顶层 sealObject 封不住内置原型，标准对象不能跨调用复用。
        ScriptableObject shared = cx.initSafeStandardObjects(null, false);
        deny(shared);
        shared.sealObject();
        ScriptableObject scope = (ScriptableObject) cx.newObject(shared);
        scope.setPrototype(shared);
        scope.setParentScope(null);
        return scope;
    }

    private static void deny(ScriptableObject scope) {
        Scriptable denied = new LambdaFunction(scope, "denied", 0, (context, callScope, thisObj, args) -> {
            throw ScriptRuntime.typeError("denied");
        });
        for (String name : DENIED) {
            scope.delete(name);
            ScriptableObject.putProperty(scope, name, denied);
        }
    }

    private static Map<String, Object> copyWithoutCredential(Map<String, Object> vars) {
        LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : vars.entrySet()) {
            if (!"credential".equals(entry.getKey())) {
                copy.put(entry.getKey(), entry.getValue());
            }
        }
        return copy;
    }

    private Object toJs(Context cx, Scriptable scope, Object value, IdentityHashMap<Object, Boolean> seen) {
        if (value == null) {
            return null;
        }
        if (value instanceof String || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof Map<?, ?> map) {
            if (seen.containsKey(map)) {
                throw mismatch("脚本变量类型无效");
            }
            seen.put(map, Boolean.TRUE);
            Scriptable object = cx.newObject(scope);
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                ScriptableObject.putProperty(
                        object, String.valueOf(entry.getKey()), toJs(cx, scope, entry.getValue(), seen));
            }
            seen.remove(map);
            return object;
        }
        if (value instanceof List<?> list) {
            if (seen.containsKey(list) || list.size() > MAX_RESULT_BYTES) {
                throw mismatch("脚本变量类型无效");
            }
            seen.put(list, Boolean.TRUE);
            Scriptable array = cx.newArray(scope, list.size());
            for (int i = 0; i < list.size(); i++) {
                array.put(i, array, toJs(cx, scope, list.get(i), seen));
            }
            seen.remove(list);
            return array;
        }
        throw mismatch("脚本变量类型无效");
    }

    private Object fromJs(Object value, IdentityHashMap<Object, Boolean> seen) {
        if (value == null || Undefined.isUndefined(value)) {
            return null;
        }
        if (value instanceof ConsString || (value instanceof CharSequence && !(value instanceof Scriptable))) {
            return value.toString();
        }
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value instanceof Number number) {
            return javaNumber(number.doubleValue());
        }
        if (value instanceof NativeArray array) {
            return fromArray(array, seen);
        }
        if (value instanceof NativeObject object) {
            return fromObject(object, seen);
        }
        throw mismatch("脚本返回值类型无效");
    }

    private Object fromArray(NativeArray array, IdentityHashMap<Object, Boolean> seen) {
        if (seen.containsKey(array)) {
            throw mismatch("脚本返回值类型无效");
        }
        long length = array.getLength();
        if (length < 0 || length > MAX_RESULT_BYTES) {
            throw mismatch("脚本结果超过 1MB");
        }
        seen.put(array, Boolean.TRUE);
        ArrayList<Object> list = new ArrayList<>((int) length);
        for (int i = 0; i < length; i++) {
            Object element = array.get(i, array);
            if (element == UniqueTag.NOT_FOUND) {
                element = null;
            }
            list.add(fromJs(element, seen));
        }
        seen.remove(array);
        return list;
    }

    private Object fromObject(NativeObject object, IdentityHashMap<Object, Boolean> seen) {
        if (seen.containsKey(object)) {
            throw mismatch("脚本返回值类型无效");
        }
        seen.put(object, Boolean.TRUE);
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        for (Object id : object.getIds()) {
            if (id instanceof String key) {
                map.put(key, fromJs(property(object, key), seen));
            } else if (id instanceof Number index) {
                int position = index.intValue();
                Object element = object.get(position, object);
                if (element == UniqueTag.NOT_FOUND) {
                    element = null;
                }
                map.put(Integer.toString(position), fromJs(element, seen));
            }
        }
        seen.remove(object);
        return map;
    }

    private static Object property(Scriptable object, String key) {
        Object value = object.get(key, object);
        return value == UniqueTag.NOT_FOUND ? null : value;
    }

    private static Object javaNumber(double value) {
        if (Double.isFinite(value)
                && value == Math.rint(value)
                && value >= Long.MIN_VALUE
                && value <= Long.MAX_VALUE) {
            return (long) value;
        }
        return value;
    }

    private static void enforceSize(Object converted) {
        byte[] encoded;
        try {
            encoded = JSON.writeValueAsBytes(converted);
        } catch (JsonProcessingException ex) {
            throw mismatch("脚本返回值类型无效");
        }
        if (encoded.length > MAX_RESULT_BYTES) {
            throw mismatch("脚本结果超过 1MB");
        }
    }

    private static void apply(String into, Object converted, Map<String, Object> vars) {
        if (into != null) {
            vars.put(into, converted);
            return;
        }
        Map<String, Object> map = Values.map(converted);
        if (map == null) {
            throw mismatch("脚本返回值类型无效");
        }
        for (String key : map.keySet()) {
            if (ConchRegistry.RESERVED.contains(key)) {
                throw mismatch("不能写入保留变量");
            }
        }
        vars.putAll(map);
    }

    private static void rejectUnknown(Map<String, Object> spec) {
        for (String key : spec.keySet()) {
            if (!FIELDS.contains(key)) {
                throw mismatch("未知的 script 字段");
            }
        }
    }

    private static String code(Object value) {
        if (!(value instanceof String text)) {
            throw mismatch("script 缺少 code");
        }
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_SOURCE_BYTES) {
            throw mismatch("脚本源码超过 64KB");
        }
        return text;
    }

    private static String into(Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text) || text.isBlank()) {
            throw mismatch("script into 无效");
        }
        if (ConchRegistry.RESERVED.contains(text)) {
            throw mismatch("不能写入保留变量");
        }
        return text;
    }

    private static int timeoutMs(Object value) {
        if (value == null) {
            return DEFAULT_TIMEOUT_MS;
        }
        if (!(value instanceof Number number)) {
            throw mismatch("script timeout_ms 无效");
        }
        double raw = number.doubleValue();
        if (!Double.isFinite(raw) || raw != Math.rint(raw) || raw < 1 || raw > MAX_TIMEOUT_MS) {
            throw mismatch("script timeout_ms 无效");
        }
        return (int) raw;
    }

    private static long deadline(long now, int timeoutMs) {
        long timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        if (Long.MAX_VALUE - now < timeoutNanos) {
            return Long.MAX_VALUE;
        }
        return now + timeoutNanos;
    }

    private static RuntimeException timeout(ScriptTimeExceeded ex) {
        if (ex.capability) {
            return new DeadlineExceeded(-1, "script", List.of());
        }
        return mismatch("script timeout");
    }

    private static RuleProblem described(Throwable ex) {
        String name = "Error";
        int line = 0;
        if (ex instanceof EcmaError ecma) {
            name = safeName(ecma.getName());
            line = ecma.lineNumber();
        } else if (ex instanceof WrappedException wrapped) {
            name = "WrappedException";
            line = wrapped.lineNumber();
        } else if (ex instanceof JavaScriptException javascript) {
            name = "JavaScriptException";
            line = javascript.lineNumber();
        } else if (ex instanceof EvaluatorException evaluator) {
            name = "SyntaxError";
            line = evaluator.lineNumber();
        } else if (ex instanceof RhinoException rhino) {
            name = safeName(rhino.getClass().getSimpleName());
            line = rhino.lineNumber();
        }
        if (line > 0) {
            line = Math.max(0, line - 1);
        }
        return mismatch("Rhino " + name + " line " + line);
    }

    private static String safeName(String name) {
        if (name != null && SAFE_NAMES.contains(name)) {
            return name;
        }
        return "Error";
    }

    private static RuleProblem mismatch(String summary) {
        return new RuleProblem(ErrorCode.RULE_MISMATCH, summary, null, null, null);
    }

    private final class SandboxFactory extends ContextFactory {

        @Override
        @SuppressWarnings("deprecation") // 契约要求的解释模式；1.9 中即 setInterpretedMode(true)
        protected Context makeContext() {
            Context cx = super.makeContext();
            cx.setOptimizationLevel(-1);
            cx.setInstructionObserverThreshold(10_000);
            cx.setMaximumInterpreterStackDepth(1000);
            cx.setClassShutter(SHUTTER);
            cx.setWrapFactory(WRAP);
            return cx;
        }

        @Override
        protected boolean hasFeature(Context cx, int featureIndex) {
            return switch (featureIndex) {
                case Context.FEATURE_E4X,
                        Context.FEATURE_ENHANCED_JAVA_ACCESS,
                        Context.FEATURE_PARENT_PROTO_PROPERTIES,
                        Context.FEATURE_V8_EXTENSIONS,
                        Context.FEATURE_ENABLE_JAVA_MAP_ACCESS,
                        Context.FEATURE_DYNAMIC_SCOPE -> false;
                case Context.FEATURE_LOCATION_INFORMATION_IN_ERROR -> true;
                default -> super.hasFeature(cx, featureIndex);
            };
        }

        @Override
        protected void observeInstructionCount(Context cx, int instructionCount) {
            Window window = windows.get();
            if (window == null) {
                return;
            }
            long now = System.nanoTime();
            if (now >= window.scriptDeadline && window.scriptDeadline <= window.capabilityDeadline) {
                throw new ScriptTimeExceeded(false);
            }
            if (now >= window.capabilityDeadline) {
                throw new ScriptTimeExceeded(true);
            }
        }
    }

    private static final class SandboxWrap extends WrapFactory {

        private SandboxWrap() {
            setJavaPrimitiveWrap(false);
        }

        @Override
        public Scriptable wrapAsJavaObject(Context cx, Scriptable scope, Object javaObject, Class<?> staticType) {
            throw ScriptRuntime.typeError("denied");
        }

        @Override
        public Scriptable wrapAsJavaObject(Context cx, Scriptable scope, Object javaObject, TypeInfo staticType) {
            throw ScriptRuntime.typeError("denied");
        }

        @Override
        public Scriptable wrapJavaClass(Context cx, Scriptable scope, Class<?> javaClass) {
            throw ScriptRuntime.typeError("denied");
        }
    }

    private record Window(long scriptDeadline, long capabilityDeadline) {
    }

    private static final class ScriptTimeExceeded extends Error {

        private final boolean capability;

        private ScriptTimeExceeded(boolean capability) {
            super(capability ? "capability" : "script", null, false, false);
            this.capability = capability;
        }
    }
}
