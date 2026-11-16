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
