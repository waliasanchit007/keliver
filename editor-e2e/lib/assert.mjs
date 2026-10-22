// Assertions that say what was expected and what happened.
import { isDeepStrictEqual, inspect } from 'node:util';

export class AssertionFailure extends Error {
  constructor(message) { super(message); this.name = 'AssertionFailure'; }
}

const show = (v) => inspect(v, { depth: 4, breakLength: 120 });

export function ok(cond, message) {
  if (!cond) throw new AssertionFailure(message);
}

export function equal(actual, expected, message) {
  if (!isDeepStrictEqual(actual, expected)) {
    throw new AssertionFailure(`${message}\n    expected: ${show(expected)}\n    actual:   ${show(actual)}`);
  }
}

export function near(actual, expected, tolerance, message) {
  if (typeof actual !== 'number' || Math.abs(actual - expected) > tolerance) {
    throw new AssertionFailure(`${message}\n    expected: ${expected} ± ${tolerance}\n    actual:   ${show(actual)}`);
  }
}

/** Collects failures instead of stopping at the first, for sweeps (every instance, every grid point). */
export class SoftAsserts {
  constructor() { this.failures = []; }
  ok(cond, message) { if (!cond) this.failures.push(message); return cond; }
  equal(actual, expected, message) {
    const same = isDeepStrictEqual(actual, expected);
    if (!same) this.failures.push(`${message}: expected ${show(expected)}, got ${show(actual)}`);
    return same;
  }
  /** Throws one failure listing everything collected, if anything was. */
  check(summary) {
    if (this.failures.length) {
      throw new AssertionFailure(`${summary} (${this.failures.length} failure(s)):\n    - ${this.failures.join('\n    - ')}`);
    }
  }
}
