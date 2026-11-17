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
