// Spliced into bos-mail.yaml as scripts.bos_rsa. Test-only RSA-2048
// (e = 65537, PKCS#1 v1.5 type 2) on 26-bit limbs. Montgomery REDC.
// Not a copy of a website script. Padding uses hermit.randomBytes.
var DB = 26;
var DV = 67108864;

function trim(a) {
  var i = a.length;
  while (i > 1 && !a[i - 1]) i--;
  if (i <= 0) return [0];
  if (i === 1 && !a[0]) return [0];
  return i === a.length ? a : a.slice(0, i);
}

function cmp(a, b) {
  var aa = trim(a);
  var bb = trim(b);
  if (aa.length !== bb.length) return aa.length > bb.length ? 1 : -1;
  for (var i = aa.length - 1; i >= 0; i--) {
    if (aa[i] !== bb[i]) return aa[i] > bb[i] ? 1 : -1;
  }
  return 0;
}

function addSmall(a, n) {
  var out = a.slice();
  var carry = n;
  var i = 0;
  while (carry) {
    var t = (out[i] || 0) + carry;
    out[i] = t % DV;
    carry = Math.floor(t / DV);
    i++;
  }
  return out;
}

function shl(a, bits) {
  var out = [];
  var carry = 0;
  var mul = Math.pow(2, bits);
  for (var i = 0; i < a.length; i++) {
    var t = a[i] * mul + carry;
    out[i] = t % DV;
    carry = Math.floor(t / DV);
  }
  if (carry) out.push(carry);
  return out;
}

function shl1(a) {
  return shl(a, 1);
}

function sub(a, b) {
  var out = a.slice();
  var borrow = 0;
  var i = 0;
  for (; i < b.length; i++) {
    var t = (out[i] || 0) - b[i] - borrow;
    if (t < 0) {
      t += DV;
      borrow = 1;
    } else {
      borrow = 0;
    }
    out[i] = t;
  }
  while (borrow) {
    var u = (out[i] || 0) - borrow;
    if (u < 0) {
      u += DV;
      borrow = 1;
    } else {
      borrow = 0;
    }
    out[i] = u;
    i++;
  }
  return trim(out);
}

function mulRaw(a, b) {
  var out = [];
  var n = a.length + b.length;
  for (var i = 0; i < n; i++) out[i] = 0;
  for (var i = 0; i < a.length; i++) {
    var carry = 0;
    for (var j = 0; j < b.length; j++) {
      var t = out[i + j] + a[i] * b[j] + carry;
      out[i + j] = t % DV;
      carry = Math.floor(t / DV);
    }
    out[i + b.length] += carry;
  }
  return out;
}

function modInverse(a) {
  var t = 0;
  var newt = 1;
  var r = DV;
  var newr = a % DV;
  while (newr !== 0) {
    var q = Math.floor(r / newr);
    var nt = t - q * newt;
    t = newt;
    newt = nt;
    var nr = r - q * newr;
    r = newr;
    newr = nr;
  }
  if (r !== 1) throw new Error("inv");
  if (t < 0) t += DV;
  return t;
}

function n0invOf(n0) {
  return (DV - modInverse(n0)) % DV;
}

function redc(t, n, n0inv) {
  var k = n.length;
  var a = t.slice();
  while (a.length < k * 2 + 2) a.push(0);
  for (var i = 0; i < k; i++) {
    var u = (a[i] * n0inv) % DV;
    var carry = 0;
    for (var j = 0; j < k; j++) {
      var prod = a[i + j] + u * n[j] + carry;
      a[i + j] = prod % DV;
      carry = Math.floor(prod / DV);
    }
    var p = i + k;
    while (carry) {
      if (p >= a.length) a.push(0);
      var extra = a[p] + carry;
      a[p] = extra % DV;
      carry = Math.floor(extra / DV);
      p++;
    }
  }
  var r = trim(a.slice(k));
  if (cmp(r, n) >= 0) r = sub(r, n);
  if (cmp(r, n) >= 0) r = sub(r, n);
  return r;
}

function rSquared(n) {
  var bits = 2 * n.length * DB;
  var r = [1];
  for (var i = 0; i < bits; i++) {
    r = shl1(r);
    if (cmp(r, n) >= 0) r = sub(r, n);
  }
  return r;
}

function bitLength(a) {
  a = trim(a);
  if (cmp(a, [0]) === 0) return 0;
  var top = a[a.length - 1];
  var bits = (a.length - 1) * DB;
  while (top > 0) {
    top = Math.floor(top / 2);
    bits++;
  }
  return bits;
}

function testBit(a, index) {
  var limb = Math.floor(index / DB);
  if (limb >= a.length) return false;
  var bit = index % DB;
  return Math.floor(a[limb] / Math.pow(2, bit)) % 2 === 1;
}

function modPow(base, exp, n) {
  var n0inv = n0invOf(n[0]);
  var r2 = rSquared(n);
  var baseM = redc(mulRaw(base, r2), n, n0inv);
  var result = redc(mulRaw([1], r2), n, n0inv);
  var bits = bitLength(exp);
  for (var i = bits - 1; i >= 0; i--) {
    result = redc(mulRaw(result, result), n, n0inv);
    if (testBit(exp, i)) result = redc(mulRaw(result, baseM), n, n0inv);
  }
  return redc(result, n, n0inv);
}

function fromHex(hex) {
  var limbs = [0];
  var s = String(hex);
  for (var i = 0; i < s.length; i++) {
    var v = parseInt(s.charAt(i), 16);
    if (v !== v) throw new Error("hex");
    limbs = shl(limbs, 4);
    limbs = addSmall(limbs, v);
  }
  return trim(limbs);
}

function divmodSmall(a, m) {
  var quot = [];
  var rem = 0;
  for (var i = a.length - 1; i >= 0; i--) {
    var cur = rem * DV + a[i];
    quot[i] = Math.floor(cur / m);
    rem = cur % m;
  }
  return { quot: trim(quot), rem: rem };
}

function toHex(a) {
  if (cmp(a, [0]) === 0) return "0";
  var hex = "";
  var x = trim(a);
  while (cmp(x, [0]) > 0) {
    var div = divmodSmall(x, 16);
    hex = "0123456789abcdef".charAt(div.rem) + hex;
    x = div.quot;
  }
  return hex;
}

function modPowHex(baseHex, expHex, modHex) {
  return toHex(modPow(fromHex(baseHex), fromHex(expHex), fromHex(modHex)));
}

function padHex(hex, chars) {
  while (hex.length < chars) hex = "0" + hex;
  return hex;
}

function rsaEncrypt(modHex, text) {
  var n = fromHex(modHex);
  var k = (String(modHex).length + 1) >> 1;
  var msg = hermit.utf8(String(text));
  if (msg.length > k - 11) throw new Error("long");
  var em = new Uint8Array(k);
  em[0] = 0;
  em[1] = 2;
  var psLen = k - msg.length - 3;
  var filled = 0;
  while (filled < psLen) {
    var need = psLen - filled;
    var block = hermit.randomBytes(need > 1024 ? 1024 : need);
    for (var i = 0; i < block.length && filled < psLen; i++) {
      if (block[i] !== 0) {
        em[2 + filled] = block[i];
        filled++;
      }
    }
  }
  em[2 + psLen] = 0;
  for (var j = 0; j < msg.length; j++) em[3 + psLen + j] = msg[j];
  var c = modPow(fromHex(hermit.hex(em)), [65537], n);
  return padHex(toHex(c), k * 2);
}
