// The 64-bit operations Kotlin's Long-as-BigInt output relies on; prints one line per result.
const p = (typeof print === 'function') ? print : console.log;
const a = BigInt.asIntN(64, 0x8080808080808080n), b = -1n, c = BigInt.asIntN(64, 0x0101010101010101n);
const cases = {
  asIntN_wrap: BigInt.asIntN(64, 0xFFFFFFFFFFFFFFFFn),
  asUintN_neg: BigInt.asUintN(64, -1n),
  mul_overflow: BigInt.asIntN(64, c * 0x7Fn * 0x1000000000n),
  shr_neg: a >> 7n,
  ushr_emul: BigInt.asIntN(64, BigInt.asUintN(64, a) >> 7n),
  shl_overflow: BigInt.asIntN(64, a << 1n),
  and_neg: a & 0xFFn,
  xor_neg: a ^ b,
  not: ~a,
  div_neg: (-7n) / 2n,
  rem_neg: (-7n) % 2n,
  toNumber_big: Number(a),
  fromNumber: BigInt(Math.trunc(-1.5e15)),
  toString16: a.toString(16),
  cmp_mixed: (a < 0) + ',' + (1n == 1) + ',' + (2n > 1.5),
  intN_small: BigInt.asIntN(32, 0x80000000n),
  clz_like: Math.clz32(Number(BigInt.asUintN(32, a))),
  shr_large: (a >> 63n) + ',' + (a >> 64n),
  ushr_63: BigInt.asUintN(64, a) >> 63n,
};
for (const k in cases) p(k + ' = ' + String(cases[k]));

// BigInt.asUintN and asIntN at every width from 1 to 128 (and a few wider), over values that are
// short and multi-limb on both 64-bit and 32-bit limb builds, positive and negative, at and around
// the limb boundaries. One line per function and width (NF2, docs/GUEST_PROFILE.md).
const P = (e) => 1n << BigInt(e);
const values = [0n, 1n, -1n, 2n, -2n, 127n, -128n, 255n, -255n,
  P(31) - 1n, -P(31), P(31), -P(31) - 1n, P(32) - 1n, P(32), -P(32), -P(32) + 1n,
  P(53) + 1n, -P(53) - 1n, P(63) - 1n, -P(63), P(63), -P(63) - 1n, P(64) - 1n, P(64), -P(64),
  P(64) + P(63), -(P(64) + P(63)), P(95) + 12345n, -P(96) + 7n, P(127) - 1n, -P(127), P(127),
  P(128) + 1n, -P(128) - 1n, -P(130) - 3n, 0x8080808080808080n, -0x8080808080808080n,
  0x0123456789abcdef0123456789abcdefn, -0x0123456789abcdef0123456789abcdefn];
const widths = [];
for (let w = 1; w <= 128; w++) widths.push(w);
widths.push(129, 160, 192, 255, 256, 257, 1000);
for (const fn of ['asUintN', 'asIntN']) {
  for (const w of widths) p(fn + '(' + w + ') = ' + values.map((v) => BigInt[fn](w, v).toString(16)).join(','));
}

// At QuickJS's size limit (2^20 bits; Node's is 2^30): the widest negative asUintN that fits, and
// widths at and past the limit where the value is returned as it is. A negative value at a width
// of 2^20 or more differs from Node (bellard/quickjs c17f7fc1db returns asUintN(2^20, -1n) one
// limb over the limit, so most operations on it are a RangeError; wider is a RangeError at once),
// so the fork's QuickJs2026Test checks those.
const widest = BigInt.asUintN(2 ** 20 - 1, -1n);
p('asUintN(2^20-1, -1n) = ' + widest.toString(2).length + ' bits, low ' + (widest & 0xffffn).toString(16)
  + ', asIntN back ' + BigInt.asIntN(2 ** 20 - 1, widest));
for (const w of [2 ** 20, 2 ** 30]) {
  p('asUintN(' + w + ') = ' + [0n, 1n, P(64) + 1n, P(200)].map((v) => BigInt.asUintN(w, v).toString(16)).join(',')
    + '; asIntN = ' + [-1n, -P(64), -P(200)].map((v) => BigInt.asIntN(w, v).toString(16)).join(','));
}

// Upstream fixes after 2026-06-04, backported (the fork's README, "The patches"). Kotlin's Long
// increments and decrements (i++, i--) and compares with Doubles through these paths.
// ++ and -- around every limb boundary, on short and multi-limb values (bellard/quickjs ef5c6e356c:
// with 64-bit limbs, -- on a value that is not a short BigInt added 2^32 - 1, so
// -(2n**63n) - 1 gave -9223372032559808513n). One line per value: x++, x--, ++x, --x, x - 1n.
const steps = [];
for (const e of [31, 32, 33, 52, 53, 62, 63, 64, 65, 95, 96, 127, 128, 200]) {
  for (const d of [-1n, 0n, 1n]) steps.push(P(e) + d, -P(e) + d);
}
steps.push(0x1ffffffffffffffffffffff01n, -0x1ffffffffffffffffffffff01n);
for (const v of steps) {
  let w = v, x = v, y = v, z = v;
  w++; x--;
  p('incdec(' + v.toString(16) + ') = ' + [w, x, ++y, --z, v - 1n, v + 1n].map((r) => r.toString(16)).join(','));
}
// A BigInt against a Number of every sign and size, both ways round (bellard/quickjs 7852f3a80f, #583:
// a negative BigInt and a negative float of a different magnitude compared the wrong way round, so
// -10n < -1.5 was false). Per pair: <, <=, >, >=, == and the same with the Number on the left.
const cmpInts = [0n, 1n, -1n, 2n, -2n, 10n, -10n, P(31), -P(31), P(32), -P(32), P(53) + 1n, -P(53) - 1n,
  P(63), -P(63), P(64), -P(64), P(70), -P(70), P(100) + 1n, -P(100) - 1n];
const cmpNums = [0, -0, 0.5, -0.5, 1, -1, 1.5, -1.5, 2.5, -2.5, 10, -10, 2147483648, -2147483648,
  2 ** 53, -(2 ** 53), 1e20, -1e20, 2 ** 64, -(2 ** 64), 1e30, -1e30, Infinity, -Infinity, NaN];
for (const v of cmpInts) {
  p('cmp(' + v.toString(16) + ') = ' + cmpNums.map((n) =>
    [v < n, v <= n, v > n, v >= n, v == n, n < v, n <= v, n > v, n >= v, n == v].map(Number).join('')).join(','));
}
// 64-bit stores (JS_ToBigInt64; bellard/quickjs 0d9e58551a fixed its 32-bit-limb path for a
// one-limb value, which canonical BigInts never are: coverage, not a reproduction).
const ta = new BigInt64Array(1), tu = new BigUint64Array(1);
for (const v of [0n, -1n, P(31), -P(31) - 1n, P(32), -P(32), P(63) - 1n, -P(63), P(63), P(64) - 1n,
  P(64) + 5n, -P(64) - 5n, P(100) + 7n, -P(100) - 7n]) {
  ta[0] = v; tu[0] = v;
  p('store64(' + v.toString(16) + ') = ' + ta[0].toString(16) + ',' + tu[0].toString(16));
}
// The lines bellard/quickjs added to tests/test_bigint.js with these fixes (ef5c6e356c, 7852f3a80f
// and c17f7fc1db, whose asIntN/asUintN cases came from quickjs-ng), as expressions and results.
let dec = 0x1ffffffffffffffffffffff01n;
const upstream = [
  ['--0x1ffffffffffffffffffffff01n', (--dec).toString(16)],
  ['-0.5 < -1n', -0.5 < -1n], ['-1n > -0.5', -1n > -0.5],
  ['-2147483648 < -1n', -2147483648 < -1n], ['-1n < -2147483648', -1n < -2147483648],
  ['asIntN(0, -1n)', BigInt.asIntN(0, -1n)], ['asIntN(1, -1n)', BigInt.asIntN(1, -1n)],
  ['asIntN(8, 128n)', BigInt.asIntN(8, 128n)], ['asIntN(8, 127n)', BigInt.asIntN(8, 127n)],
  ['asIntN(64, -1n)', BigInt.asIntN(64, -1n)],
  ['asIntN(64, 0x8000000000000000n)', BigInt.asIntN(64, 0x8000000000000000n)],
  ['asIntN(128, -1n)', BigInt.asIntN(128, -1n)],
  ['asUintN(0, -1n)', BigInt.asUintN(0, -1n)], ['asUintN(1, -1n)', BigInt.asUintN(1, -1n)],
  ['asUintN(8, -1n)', BigInt.asUintN(8, -1n)], ['asUintN(64, -1n)', BigInt.asUintN(64, -1n)],
  ['asUintN(64, -2n)', BigInt.asUintN(64, -2n)],
  ['asUintN(64, 0x8000000000000000n)', BigInt.asUintN(64, 0x8000000000000000n)],
  ['asUintN(128, -1n)', BigInt.asUintN(128, -1n)], ['asUintN(192, -1n)', BigInt.asUintN(192, -1n)],
  ['asUintN(64, -(1n << 64n))', BigInt.asUintN(64, -(1n << 64n))],
  ['asUintN(64, 1n << 64n)', BigInt.asUintN(64, 1n << 64n)],
  ['asUintN(64, 123n)', BigInt.asUintN(64, 123n)], ['asIntN(64, 123n)', BigInt.asIntN(64, 123n)],
];
for (const [k, v] of upstream) p('test_bigint: ' + k + ' = ' + String(v));
