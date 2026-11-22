package dev.hermit.crab.engine;

import java.security.SecureRandom;
import java.time.Clock;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.LambdaFunction;
import org.mozilla.javascript.ScriptRuntime;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;

/**
 * Frozen {@code hermit} global. Codec helpers are plain JavaScript. {@code randomBytes}
 * and {@code now} are Rhino functions that return a Uint8Array or a number, not a Java object.
 */
final class HermitPrelude {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int RANDOM_MIN = 1;
    private static final int RANDOM_MAX = 1024;
    private static final String PRELUDE = """
            (function () {
              function utf8(text) {
                var s = String(text);
                var bytes = [];
                for (var i = 0; i < s.length; i++) {
                  var c = s.charCodeAt(i);
                  if (c >= 0xD800 && c <= 0xDBFF && i + 1 < s.length) {
                    var next = s.charCodeAt(i + 1);
                    if (next >= 0xDC00 && next <= 0xDFFF) {
                      c = 0x10000 + ((c - 0xD800) << 10) + (next - 0xDC00);
                      i++;
                    }
                  }
                  if (c < 0x80) {
                    bytes.push(c);
                  } else if (c < 0x800) {
                    bytes.push(0xC0 | (c >> 6), 0x80 | (c & 0x3F));
                  } else if (c < 0x10000) {
                    bytes.push(0xE0 | (c >> 12), 0x80 | ((c >> 6) & 0x3F), 0x80 | (c & 0x3F));
                  } else {
                    bytes.push(
                      0xF0 | (c >> 18),
                      0x80 | ((c >> 12) & 0x3F),
                      0x80 | ((c >> 6) & 0x3F),
                      0x80 | (c & 0x3F));
                  }
                }
                return new Uint8Array(bytes);
              }
              function fromUtf8(bytes) {
                var out = "";
                var i = 0;
                while (i < bytes.length) {
                  var b = bytes[i] & 255;
                  var cp;
                  var need;
                  if (b < 0x80) {
                    cp = b;
                    need = 1;
                  } else if ((b & 0xE0) === 0xC0) {
                    cp = b & 0x1F;
                    need = 2;
                  } else if ((b & 0xF0) === 0xE0) {
                    cp = b & 0x0F;
                    need = 3;
                  } else if ((b & 0xF8) === 0xF0) {
                    cp = b & 0x07;
                    need = 4;
                  } else {
                    throw new Error("utf8");
                  }
                  if (i + need > bytes.length) {
                    throw new Error("utf8");
                  }
                  for (var j = 1; j < need; j++) {
                    var n = bytes[i + j] & 255;
                    if ((n & 0xC0) !== 0x80) {
                      throw new Error("utf8");
                    }
                    cp = (cp << 6) | (n & 0x3F);
                  }
                  i += need;
                  if (cp > 0xFFFF) {
                    cp -= 0x10000;
                    out += String.fromCharCode(0xD800 + (cp >> 10), 0xDC00 + (cp & 0x3FF));
                  } else {
                    out += String.fromCharCode(cp);
                  }
                }
                return out;
              }
              var HEX = "0123456789abcdef";
              function hex(bytes) {
                var out = "";
                for (var i = 0; i < bytes.length; i++) {
                  var v = bytes[i] & 255;
                  out += HEX.charAt(v >> 4) + HEX.charAt(v & 15);
                }
                return out;
              }
              function unhex(text) {
                var s = String(text);
                if (s.length % 2 !== 0) {
                  throw new Error("hex");
                }
                var bytes = [];
                for (var i = 0; i < s.length; i += 2) {
                  var v = parseInt(s.substr(i, 2), 16);
                  if (v !== v) {
                    throw new Error("hex");
                  }
                  bytes.push(v);
                }
                return new Uint8Array(bytes);
              }
              var B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
              var B64URL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
              function encode(bytes, alphabet, pad) {
                var out = "";
                var i = 0;
                for (; i + 2 < bytes.length; i += 3) {
                  var n = ((bytes[i] & 255) << 16) | ((bytes[i + 1] & 255) << 8) | (bytes[i + 2] & 255);
                  out += alphabet.charAt(n >> 18) + alphabet.charAt((n >> 12) & 63)
                      + alphabet.charAt((n >> 6) & 63) + alphabet.charAt(n & 63);
                }
                if (i < bytes.length) {
                  var rest = (bytes[i] & 255) << 16;
                  if (i + 1 < bytes.length) {
                    rest |= (bytes[i + 1] & 255) << 8;
                  }
                  out += alphabet.charAt(rest >> 18) + alphabet.charAt((rest >> 12) & 63);
                  if (i + 1 < bytes.length) {
                    out += alphabet.charAt((rest >> 6) & 63);
                    if (pad) {
                      out += "=";
                    }
                  } else if (pad) {
                    out += "==";
                  }
                }
                return out;
              }
              function decode(text, alphabet) {
                var s = String(text).replace(/=+$/, "");
                if (s.length % 4 === 1) {
                  throw new Error("base64");
                }
                var out = [];
                var buf = 0;
                var bits = 0;
                for (var i = 0; i < s.length; i++) {
                  var v = alphabet.indexOf(s.charAt(i));
                  if (v < 0) {
                    throw new Error("base64");
                  }
                  buf = (buf << 6) | v;
                  bits += 6;
                  if (bits >= 8) {
                    bits -= 8;
                    out.push((buf >> bits) & 255);
                    buf &= (1 << bits) - 1;
                  }
                }
                return new Uint8Array(out);
              }
              return {
                utf8: utf8,
                fromUtf8: fromUtf8,
                hex: hex,
                unhex: unhex,
                base64: function (bytes) { return encode(bytes, B64, true); },
                unbase64: function (text) { return decode(text, B64); },
                base64url: function (bytes) { return encode(bytes, B64URL, false); },
                unbase64url: function (text) { return decode(text, B64URL); }
              };
            })()
            """;
    private static final String FREEZE = """
            (function () {
              var names = ["utf8", "fromUtf8", "hex", "unhex", "base64", "unbase64",
                "base64url", "unbase64url", "randomBytes", "now"];
              for (var i = 0; i < names.length; i++) {
                Object.freeze(hermit[names[i]]);
              }
              Object.freeze(hermit);
            })()
            """;

    private HermitPrelude() {
    }

    static void install(Context cx, Scriptable scope, Clock clock) {
        Object created = CompiledScripts.evaluate(cx, scope, PRELUDE, "hermit");
        if (!(created instanceof Scriptable hermit)) {
            throw ScriptRuntime.typeError("denied");
        }
        ScriptableObject.putProperty(hermit, "randomBytes", randomBytes(cx, scope));
        ScriptableObject.putProperty(hermit, "now", now(scope, clock));
        ScriptableObject.defineProperty(
                scope,
                "hermit",
                hermit,
                ScriptableObject.READONLY | ScriptableObject.PERMANENT);
        CompiledScripts.evaluate(cx, scope, FREEZE, "hermit");
    }

    private static Scriptable randomBytes(Context cx, Scriptable scope) {
        return new LambdaFunction(scope, "randomBytes", 1, (context, callScope, thisObj, args) -> {
            int length = randomLength(args);
            byte[] bytes = new byte[length];
            RANDOM.nextBytes(bytes);
            return uint8(context, scope, bytes);
        });
    }

    private static Scriptable now(Scriptable scope, Clock clock) {
        Clock source = clock == null ? Clock.systemUTC() : clock;
        return new LambdaFunction(scope, "now", 0, (context, callScope, thisObj, args) -> (double) source.millis());
    }

    private static int randomLength(Object[] args) {
        if (args == null || args.length == 0 || args[0] == null || Undefined.isUndefined(args[0])) {
            throw ScriptRuntime.typeError("range");
        }
        if (!(args[0] instanceof Number number)) {
            throw ScriptRuntime.typeError("range");
        }
        double raw = number.doubleValue();
        if (!Double.isFinite(raw) || raw != Math.rint(raw) || raw < RANDOM_MIN || raw > RANDOM_MAX) {
            throw ScriptRuntime.typeError("range");
        }
        return (int) raw;
    }

    private static Scriptable uint8(Context cx, Scriptable scope, byte[] data) {
        Scriptable array = cx.newArray(scope, data.length);
        for (int i = 0; i < data.length; i++) {
            array.put(i, array, data[i] & 0xff);
        }
        return cx.newObject(scope, "Uint8Array", new Object[] {array});
    }
}
