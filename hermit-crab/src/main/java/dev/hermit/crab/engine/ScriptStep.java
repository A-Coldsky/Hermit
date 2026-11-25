package dev.hermit.crab.engine;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.management.ThreadMXBean;

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
import dev.hermit.common.validate.ScriptLibraries;

/**
 * Rhino 1.9.1，语言级别 ES6，只在解释模式下计数。
 * setOptimizationLevel(-1) 在 1.9 里等价于 setInterpretedMode(true)。
 * 抛出的说明和行、列会映射回脚本库或 code，不附带 Java 堆栈。
 */
final class ScriptStep {

    static final int DEFAULT_TIMEOUT_MS = 1000;
    static final int MAX_TIMEOUT_MS = 5000;
    static final int MAX_SOURCE_BYTES = 64 * 1024;
    static final int MAX_RESULT_BYTES = 1024 * 1024;
    static final String MEMORY_EXCEEDED = "脚本内存超限";
    /** 脚本自己的时限到了。能力 deadline 仍是 {@code 执行超时}，不走这句。 */
    static final String SCRIPT_TIMEOUT = "脚本超时";
    /** 省略 {@code into} 时合并进变量表，必须是对象。句子不带返回值。 */
    static final String RETURN_OBJECT = "省略 into 时要返回对象";
    /** Uint8Array 不能当结果交出去。句子不带字节。 */
    static final String RETURN_BYTES = "不能直接返回字节数组，请先 hermit.hex 或 base64";
    /** Date 不是可保存的值。句子不带时间。 */
    static final String RETURN_DATE = "不能返回 Date";
    /**
     * 一次脚本（含把变量转成 JS 对象）在本线程上最多分配的字节数，按 JVM 的线程分配计数，包含已回收的垃圾。
     * 同一个 JVM 里没有真正的内存隔离；这里挡住循环里不断变大的脚本。单次巨大的分配在 {@link #run} 里接住。
     */
    static volatile long maxAllocatedBytes = 256L * 1024 * 1024;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> FIELDS = Set.of("code", "into", "timeout_ms", "use", "credential");
    /** 进入打码前保留的抛出说明上限。更长的部分不进入错误，避免把整段响应正文带出去。 */
    private static final int DETAIL_CHARS = 1_000_000;
    private static final String PREFIX = "(function(){\n";
    private static final String SUFFIX = "\n})()";
    private static final String[] DENIED = {
            "eval", "Function", "Script", "load", "print", "printErr", "quit",
            "readFile", "readUrl", "read", "write", "writeln", "uneval", "Continuation",
            "importPackage", "importClass", "JavaAdapter", "JavaImporter", "Packages",
            "java", "javax", "org", "com", "edu", "net", "getClass"
    };
    private static final ClassShutter SHUTTER = name -> false;
    private static final ThreadMXBean THREADS = threadBean();
    private static final WrapFactory WRAP = new SandboxWrap();

    private final SandboxFactory factory = new SandboxFactory();
    private final ThreadLocal<Window> windows = new ThreadLocal<>();
    private final Clock clock;

    ScriptStep(Clock clock) {
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    Outcome run(
            Map<String, Object> spec,
            Map<String, Object> vars,
            long capabilityDeadlineNanos,
            Map<String, String> libraries
    ) {
        return run(spec, vars, capabilityDeadlineNanos, libraries, Set.of());
    }

    Outcome run(
            Map<String, Object> spec,
            Map<String, Object> vars,
            long capabilityDeadlineNanos,
            Map<String, String> libraries,
            Set<String> hiddenVars
    ) {
        try {
            return execute(spec, vars, capabilityDeadlineNanos, libraries, hiddenVars);
        } catch (RuleProblem | DeadlineExceeded ex) {
            throw ex;
        } catch (ScriptTimeExceeded ex) {
            throw timeout(ex);
        } catch (ScriptMemoryExceeded | OutOfMemoryError ex) {
            // OOM 多半来自脚本里一次巨大的分配（如超长 repeat），那次分配没有成功，放手后可以回收。
            throw faulted(MEMORY_EXCEEDED);
        } catch (StackOverflowError ex) {
            throw mismatch("脚本调用栈过深");
        } catch (RuntimeException ex) {
            throw described(ex, List.of(), "");
        }
    }

    private Outcome execute(
            Map<String, Object> spec,
            Map<String, Object> vars,
            long capabilityDeadlineNanos,
            Map<String, String> libraries,
            Set<String> hiddenVars
    ) {
        rejectUnknown(spec);
        boolean withCredential = credential(spec.get("credential"));
        Prelude prelude = libraries(spec.get("use"), libraries);
        String code = code(spec.get("code"));
        String into = into(spec.get("into"));
        int timeoutMs = timeoutMs(spec.get("timeout_ms"));
        long now = System.nanoTime();
        if (now >= capabilityDeadlineNanos) {
            throw new DeadlineExceeded(-1, "script", List.of());
        }
        long scriptDeadline = deadline(now, timeoutMs);
        windows.set(new Window(scriptDeadline, capabilityDeadlineNanos, allocated()));
        try {
            Object raw = factory.call(cx -> evaluate(cx, prelude.source + code, vars, withCredential, hiddenVars));
            Object converted = fromJs(raw, new IdentityHashMap<>());
            enforceSize(converted);
            apply(into, converted, vars);
            return new Outcome(null, null, null, null, false);
        } catch (RuleProblem | DeadlineExceeded ex) {
            throw ex;
        } catch (ScriptTimeExceeded ex) {
            throw timeout(ex);
        } catch (RuntimeException ex) {
            throw described(ex, prelude.spans, code);
        } finally {
            windows.remove();
        }
    }

    private Object evaluate(
            Context cx,
            String code,
            Map<String, Object> vars,
            boolean withCredential,
            Set<String> hiddenVars
    ) {
        Scriptable scope = newScope(cx);
        HermitPrelude.install(cx, scope, clock);
        Map<String, Object> exposed = withCredential
                ? copyWithCredential(vars)
                : copyWithoutCredential(vars, hiddenVars);
        ScriptableObject.putProperty(scope, "vars", toJs(cx, scope, exposed, new IdentityHashMap<>()));
        return CompiledScripts.evaluate(cx, scope, PREFIX + code + SUFFIX, "script");
    }

    private static Prelude libraries(Object use, Map<String, String> libraries) {
        if (use == null) {
            return new Prelude("", List.of());
        }
        if (!(use instanceof List<?> list)) {
            throw mismatch(ScriptLibraries.USE_TYPE);
        }
        Map<String, String> known = libraries == null ? Map.of() : libraries;
        StringBuilder body = new StringBuilder();
        ArrayList<SourceSpan> spans = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (Object item : list) {
            if (!(item instanceof String name) || !ScriptLibraries.name(name)) {
                throw mismatch(ScriptLibraries.NAME_INVALID);
            }
            if (!seen.add(name)) {
                throw mismatch(ScriptLibraries.duplicate(name));
            }
            if (!known.containsKey(name)) {
                throw mismatch(ScriptLibraries.missing(name));
            }
            String source = known.get(name);
            if (source == null) {
                throw mismatch(ScriptLibraries.missing(name));
            }
            if (source.getBytes(StandardCharsets.UTF_8).length > ScriptLibraries.MAX_BYTES) {
                throw mismatch(ScriptLibraries.TOO_LARGE);
            }
            if (!source.isEmpty()) {
                String appended = source.endsWith("\n") ? source : source + "\n";
                body.append(appended);
                spans.add(new SourceSpan(name, lineCount(appended)));
            }
        }
        return new Prelude(body.toString(), spans);
    }

    /** 以换行结尾的文本，行数等于换行个数；最后一行没有换行时再加一行。 */
    private static int lineCount(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int newlines = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                newlines++;
            }
        }
        return text.charAt(text.length() - 1) == '\n' ? newlines : newlines + 1;
    }

    private static boolean credential(Object value) {
        if (value == null) {
            return false;
        }
        if (!(value instanceof Boolean flag)) {
            throw mismatch(ScriptLibraries.CREDENTIAL_TYPE);
        }
        return flag;
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

    private static Map<String, Object> copyWithoutCredential(Map<String, Object> vars, Set<String> hiddenVars) {
        LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : vars.entrySet()) {
            String key = entry.getKey();
            if (!"credential".equals(key) && !"login".equals(key)
                    && !(entry.getValue() instanceof HttpCalls.SecretSource)
                    && (hiddenVars == null || !hiddenVars.contains(key))) {
                copy.put(key, entry.getValue());
            }
        }
        return copy;
    }

    /** A new map so a script cannot mutate the account credential fields used by later templates. */
    private static Map<String, Object> copyWithCredential(Map<String, Object> vars) {
        LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : vars.entrySet()) {
            if ("login".equals(entry.getKey()) || entry.getValue() instanceof HttpCalls.SecretSource) {
                continue;
            }
            if ("credential".equals(entry.getKey())) {
                Map<String, Object> fields = Values.map(entry.getValue());
                if (fields == null) {
                    throw mismatch("脚本变量类型无效");
                }
                copy.put("credential", new LinkedHashMap<>(fields));
            } else {
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
        if (isTypedArray(value)) {
            // Uint8Array 是 NativeArray 的子类。直接返回会变成数字列表，调用方无法区分字节。
            throw faulted(RETURN_BYTES);
        }
        if (value instanceof Scriptable scriptable && "Date".equals(scriptable.getClassName())) {
            throw faulted(RETURN_DATE);
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

    private static boolean isTypedArray(Object value) {
        return value.getClass().getName().startsWith("org.mozilla.javascript.typedarrays.");
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
            throw faulted(RETURN_OBJECT);
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
        // 脚本已经在跑，但指令计数拿不到抛出的行。记在 code 上，不编造列号。
        return faulted(SCRIPT_TIMEOUT);
    }

    /**
     * 脚本已经执行，只是没有可引用的行号。来源记在 {@code code}，{@code line} 为 0，列号为 null。
     * 调用错误仍是 {@code summary} 本身，不把返回值写进去。
     */
    private static RuleProblem faulted(String summary) {
        return mismatch(summary).withScript(new ScriptFault(ScriptFault.CODE, 0, null, summary));
    }

    private static RuleProblem described(Throwable ex, List<SourceSpan> libraries, String code) {
        int line = 0;
        int column = 0;
        String detail;
        if (ex instanceof EcmaError ecma) {
            line = ecma.lineNumber();
            column = ecma.columnNumber();
            detail = safeDetail(ecma.details());
        } else if (ex instanceof WrappedException wrapped) {
            line = wrapped.lineNumber();
            column = wrapped.columnNumber();
            detail = "脚本执行失败";
        } else if (ex instanceof JavaScriptException javascript) {
            line = javascript.lineNumber();
            column = javascript.columnNumber();
            detail = safeDetail(javascript.details());
        } else if (ex instanceof EvaluatorException evaluator) {
            line = evaluator.lineNumber();
            column = evaluator.columnNumber();
            detail = safeDetail(evaluator.details());
        } else if (ex instanceof RhinoException rhino) {
            line = rhino.lineNumber();
            column = rhino.columnNumber();
            detail = safeDetail(rhino.details());
        } else {
            detail = "脚本执行失败";
        }
        ScriptFault fault = locate(line, column, libraries, code, detail);
        return mismatch(fault.summary()).withScript(fault);
    }

    /**
     * Rhino 行号从包装函数的第一行算起。这里把它映射回 {@code use} 里的库，或步骤自己的 {@code code}。
     * 列号沿用 Rhino 的值；运行时 {@code throw} 经常是 0，这时列为 null。
     */
    private static ScriptFault locate(
            int rhinoLine,
            int rhinoColumn,
            List<SourceSpan> libraries,
            String code,
            String detail
    ) {
        Integer column = rhinoColumn > 0 ? rhinoColumn : null;
        int remaining = rhinoLine - 1;
        if (remaining > 0 && libraries != null) {
            for (SourceSpan span : libraries) {
                if (span.lines <= 0) {
                    continue;
                }
                if (remaining <= span.lines) {
                    return new ScriptFault(span.name, remaining, column, detail);
                }
                remaining -= span.lines;
            }
        }
        int codeLines = lineCount(code);
        if (remaining > 0 && remaining <= codeLines) {
            return new ScriptFault(ScriptFault.CODE, remaining, column, detail);
        }
        return new ScriptFault(ScriptFault.CODE, 0, column, detail);
    }

    /** 丢掉堆栈行。说明本身可以换行，但不把 Java 或 Rhino 的栈带出去。 */
    private static String safeDetail(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (String line : raw.split("\\R", -1)) {
            if (stackLine(line)) {
                continue;
            }
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(line);
            if (out.length() >= DETAIL_CHARS) {
                break;
            }
        }
        if (out.length() > DETAIL_CHARS) {
            out.setLength(DETAIL_CHARS);
        }
        return out.toString().strip();
    }

    private static boolean stackLine(String line) {
        String trimmed = line.trim();
        return trimmed.startsWith("at ")
                || trimmed.contains("org.mozilla.javascript.")
                || (trimmed.startsWith("java.lang.") && trimmed.contains("Exception"));
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
            if (window.allocationStart >= 0 && allocated() - window.allocationStart > maxAllocatedBytes) {
                throw new ScriptMemoryExceeded();
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

    /** {@code allocationStart} 为 -1 时这个 JVM 不提供线程分配计数，只靠超时。 */
    private record Window(long scriptDeadline, long capabilityDeadline, long allocationStart) {
    }

    private static long allocated() {
        return THREADS == null ? -1 : THREADS.getCurrentThreadAllocatedBytes();
    }

    private static ThreadMXBean threadBean() {
        if (ManagementFactory.getThreadMXBean() instanceof ThreadMXBean bean
                && bean.isThreadAllocatedMemorySupported()) {
            if (!bean.isThreadAllocatedMemoryEnabled()) {
                bean.setThreadAllocatedMemoryEnabled(true);
            }
            return bean;
        }
        return null;
    }

    private static final class ScriptMemoryExceeded extends Error {
        private ScriptMemoryExceeded() {
            super("memory", null, false, false);
        }
    }

    private static final class ScriptTimeExceeded extends Error {

        private final boolean capability;

        private ScriptTimeExceeded(boolean capability) {
            super(capability ? "capability" : "script", null, false, false);
            this.capability = capability;
        }
    }

    /** 一个脚本库接进包装函数之后占的行。 */
    private record SourceSpan(String name, int lines) {
    }

    private record Prelude(String source, List<SourceSpan> spans) {
    }
}
