// Copyright (C) 2026 Keliver contributors. Licensed under the Apache License, Version 2.0.
//
// NF2: the fork's own 32-bit size guards (BACKPORTS.md, "Keliver-only guards"), run by
// `apply.sh check-bigint` on the Mac's qjs builds and by `check-bigint-device` on every ABI a device
// runs, each under a 64 MiB memory limit (qjs --memory-limit, run-js --memory-limit). Each line
// asks for an array of 2^29 + 1 elements, whose table is 8 bytes an element on every host:
//   - a 32-bit host (armeabi-v7a, x86): the size wraps size_t, so the fork's guard in
//     expand_fast_array throws "out of memory" before allocating; the unpatched release allocates
//     8 bytes and writes past them (a crash: the control);
//   - a 64-bit host: nothing wraps, and the 4 GiB table is over the memory limit, so the allocation
//     fails with the same "out of memory", patched or not.
// So every build of the fork prints size-guards.expected on every host; only the outcome is
// compared, and the guard is what makes the 32-bit line match. Node is no reference here (V8
// allocates the array). Typed arrays (Object.keys and sort with a comparator) need a 512 MiB or
// 1 GiB buffer first, over the limit on every host, so they are not here.
function outcome(f) {
  try { f(); return 'no exception'; } catch (e) { return e.name + ': ' + e.message; }
}
var big = { length: 2 ** 29 + 1 };
print('toReversed: ' + outcome(function () { Array.prototype.toReversed.call(big); }));
print('toSorted: ' + outcome(function () { Array.prototype.toSorted.call(big); }));
print('toSpliced: ' + outcome(function () { Array.prototype.toSpliced.call(big, 0, 0); }));
print('with: ' + outcome(function () { Array.prototype.with.call(big, 0, 0); }));
// The context still works after the failed allocations.
print('after: ' + [3, 2, 1].toReversed().join());
