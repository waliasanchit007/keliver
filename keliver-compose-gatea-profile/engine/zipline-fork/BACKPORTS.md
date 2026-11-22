# Upstream QuickJS fixes in the fork's engine patch

`patches/quickjs-2026-06-04-backports.patch` is the QuickJS 2026-06-04 release plus fixes taken
from Bellard's repository (github.com/bellard/quickjs). This file records the triage of every
upstream commit from `04be246` to `e585640` (master on 2026-11-10) that touches the engine files
Zipline vendors (`quickjs.c`, `quickjs.h`, `libregexp`, `libunicode`, `cutils`, `dtoa`, the atom
and opcode tables). Each change in the patch is marked in the source with
`Keliver fork (NF2): backport of bellard/quickjs <sha>`.

The rules applied:

- **Taken:** fixes Kotlin/JS output or Zipline can reach (BigInt, which `Long` compiles to; the
  bytecode reader, which loads every guest; string conversion through the bridges), and every hunk
  that fixes a double free, a use after free, or a leak on an error or out-of-memory path, in code
  the 2026-06-04 release has. A host can set Zipline's `memoryLimit`, so allocation failures are
  reachable.
- **Partly:** only the hunks that meet that bar; the rest of the commit is listed with a reason.
- **Not needed:** a feature, a spec-conformance change, code the release does not have, or a
  path Zipline does not reach, with no memory-safety effect.
- **Out of scope:** changes the engine's shape, the bytecode format, the opcodes or the built-in
  atoms (`zipline-bytecode` reads the atom numbering), or needs a later feature (Intl, Temporal,
  NaN-boxing, `using`).

No taken hunk changes `BC_VERSION`, the opcodes, the built-in atoms or what the bytecode writer
emits; the reader changes only validate input, fix leaks, or set property flags after reading.

## The commits, newest first

| Commit | Verdict | Reason |
|---|---|---|
| e585640 intl DateTimeFormat OOM leaks | out of scope | Intl |
| 3cbe3f2 OOB in Intl.NumberFormat | out of scope | Intl |
| 681f105 intl buffer overflow | out of scope | Intl |
| 8120a9e temporal timezone validation | out of scope | Temporal |
| b19b2ec object spread with strings (#611) | not needed | `{..."ab"}`; Kotlin/JS emits no object spread |
| e5ac135 test_worker.js | not needed | test only |
| f2e4461 temporal date bound checks | out of scope | Temporal |
| 014389e quickjs-libc JSWaker | not needed | quickjs-libc, not in Zipline |
| 56d38b8 JS_GetTypedArrayBuffer with resizable buffers (#608) | not needed | API Zipline does not call |
| 813636e ArrayBuffer release callback called twice (#606) | **taken** | a double free: `JS_DetachArrayBuffer` and `transfer` released the data but kept the free function, so finalizing the detached buffer called it again. With the release's own buffers that call gets `NULL` and is harmless, and Zipline registers no external buffers; taken because it is a double free in code the release has. Both hunks (the second one's neighbour, immutable buffers, is not in the release and not needed) |
| 3cddd4c waitAsync polling in quickjs-libc | not needed | quickjs-libc |
| e36f864 malloc error handling in JS_DefineProperty (#604) | not needed | fixes the out-of-line getter/setter allocation, which the release does not have (it stores them inline) |
| 092277a iterator read twice in js_append_enumerate | not needed | observable only with a getter on `[Symbol.iterator]` during spread/for-of setup; spec order |
| 66fdddf ToIndex on maxByteLength | not needed | resizable ArrayBuffer option, spec |
| 3134fbd tests for Array iterator length | not needed | tests |
| e25861c invalid js_get_length32 (#597) | not needed | array-likes over 2^32 elements and proxy `ownKeys` over 2^31 give a truncated length; every read is a property get, so nothing reads out of bounds (but see "Known and not fixed upstream" below) |
| 673c622 regexp after template/private field (#596) | not needed | parser; Kotlin/JS emits neither before a `/` |
| 88130c0 seal template objects when deserializing (#598) | **taken** | bytecode reader |
| af2e282 extensibility test for generic arrays (#593) | not needed | spec, non-extensible sparse arrays |
| b25da59 add_fast_array_element OOM (#576) | not needed | after an allocation failure `length` is one too large (reads are checked against the element count, so no overflow); the 2^31-element guard is unreachable in a phone's memory |
| f68db0e NULL object references in deserializer | **taken** | bytecode reader, a NULL dereference |
| 0943061 JS_DefinePropertyValueC + error handling | **partly** (2 of 25 hunks, and the helper) | a double free in `Promise.withResolvers` and a leak in `JS_AddIntrinsicTypedArrays`; every hunk below |
| 712e8c0 import-bytes | out of scope | feature (modules) |
| 5e741b8 exported ArrayBuffer helpers | not needed | API |
| 1d3ae04 immutable ArrayBuffer | out of scope | feature |
| 28e801a import-text | out of scope | feature |
| 1cc7446 sort same-value optimisation | not needed | spec (comparator call count) |
| e8b6919 var refs fit on 16 bits (#589) | not needed | a function capturing more than 65,535 variables, which Kotlin output does not produce; the truncated index lands on the wrong variable, not out of bounds; about 110 lines in the compiler (maintainer's decision, 2026-11-23) |
| c530305 symbol leak in JS_ValueToAtom (#590) | **partly** (one hunk) | the `JS_ValueToAtom` leak; the rest is a signature cleanup |
| 809cf73 arm64 NaN-boxing only on macOS | out of scope | NaN-boxing (e621135) not taken |
| 2833510 test262 update | not needed | tests |
| af0af7f DataView byteLength out of bounds | not needed | spec: the getters return 0 instead of throwing |
| de4c9e0, c3469d6, aac9bc5 Iterator.prototype.reduce/flatMap/concat | not needed | iterator helpers; Kotlin does not use them |
| 3c4c6ed slow Array truncation (#585) | not needed | performance; the release's loop re-reads the shape after each delete |
| ca2c752 removed warnings | not needed | no behaviour change |
| e621135 NaN-boxing on 64-bit targets | out of scope | changes `JSValue` |
| da278b8 error stack accessor | out of scope | feature, changes Error objects |
| 5febaaa JS_AutoInitProperty OOM (#580) | not needed | in the release a failed lazy initialisation leaves `undefined` and leaks the value being set (`JS_SetPropertyInternal`) or the initial value (`js_create_var_ref` failing): leaks after an allocation failure, no double free or use after free; the main change is retry behaviour |
| 552d5eb leak in JS_ToCStringLen2 (#587) | **taken** | the JNI and iOS bridges convert strings through it |
| 7852f3a negative BigInt vs negative float (#583) | **taken** | `Long` as BigInt |
| 0fa1e6a allow JS_SHORT_BIG_INT_BITS < JS_LIMB_BITS | not needed | short BigInts are limb-sized in the release (only NaN-boxing changes that) |
| ef5c6e3 BigInt decrement with 64-bit limbs | **taken** | `Long` as BigInt |
| 19d6bd8 module namespace names | not needed | ES modules; Zipline loads its own module format |
| 0808cbc nonextensible-applies-to-private | out of scope | proposal |
| 1ec58aa await return() in for-await | not needed | Kotlin emits no for-await |
| f8199dc template literal substitution | out of scope | removes the `concat` atom (renumbers the built-in atoms) and changes the compiled code |
| 888481d faster String.prototype.concat | not needed | performance |
| 1134912 Proxy ownKeys primitive result | not needed | spec |
| 44e85b1 os.chdir/std.setenv arity | not needed | quickjs-libc |
| 9a0197f sort writes back every element | not needed | spec (frozen arrays) |
| cd97c2b use after free in build_backtrace (#529) | **taken** | every thrown Error builds a backtrace. Adapted: the release stores the stack with `JS_DefinePropertyValue`, not upstream's later `object_data` (da278b8). The new early exits still leak the backtrace buffer after an allocation failure, as upstream's do |
| 79f58c1 leak in JS_ReadBigInt | **taken** | bytecode reader |
| 817509a infinite loop in unicode_script | **taken** | libunicode, `\p{Script=…}` after an allocation failure |
| 4490e58 intl use after free | out of scope | Intl (not in the release) |
| f2eb204 Atomics.waitAsync | out of scope | feature |
| cac7287 pretty printing of symbol names | not needed | `JS_PrintValue`, unused |
| 7d1c13c, b763a91 run-test262 / CI | not needed | not engine |
| e2dc3eb, adc9db3, c1b906c Intl.Locale / Temporal | out of scope | Intl/Temporal |
| c4f63e1, 0709d6b explicit resource management (`using`) | out of scope | removes `OP_dispose_scope` (opcodes) |
| 84e723f compilation fixes | not needed | for later features |
| 3ad6280, 40dd0a9, 7bb3e9e, 3e7f88c, ec87e1a, ba41893, 0684981 Promise.allKeyed, iterator chunking/includes/join/zip, Array.fromAsync | out of scope | features |
| 4d56487 removed memory leak | not needed | temporal.c only |
| c2eaa5f CI | not needed | not engine |
| a37f62a, ea3deb1, bcb9ad1 duration rounding, int128 | out of scope | Temporal |
| 9d01a96, 7377176, 9c4f6af warnings, Makefile, missing file | not needed | build/Temporal |
| 97f64ef Temporal and Intl | out of scope | about 86k lines |
| 7b1b755, 7cf7315, c2a11b8, c28c623, d81806f, bf81c10, a98785d, 52c9b6e, 7693ca9, 4b0addb API exports | not needed | API for Intl/Temporal |
| 55c5f0b class elements after a line terminator (#567) | not needed | parser edge case (`get`/`set`/`static` then a newline); Kotlin/JS output does not produce it |
| 535a7c2, ce0dbb7, efc48a3, 13876a2, fce112d, 1aa7429, 8e68fcf, df8de73, cfc9464, 4984249 spec conformance (defineProperties order, Proxy invariants, bind prototype, name inference, super assignment, module exports, Promise.try, Annex B, yield/await parsing, empty catch value) | not needed | test262 conformance in paths Kotlin output does not exercise |
| 27e2503, dbbb3dc test exclusions | not needed | tests |
| 8ac9d9b TypedArray.prototype.at | not needed | spec (which length a negative index is checked against after a resize); the read goes through `JS_GetPropertyInt64` either way, so nothing reads out of bounds |
| abf571a iterator helpers return/next | not needed | iterator helpers |
| 22f8302 JS_PrintValue comment | not needed | comment |
| 5b6356a ArrayBuffer.prototype.resize ToIndex | not needed | spec |
| 90ab6e2 JS_ToBigInt128 | not needed | API |
| 0d9e585 32-bit JS_ToBigInt64 | **taken** | for parity: unreachable on the release, where a short BigInt is as wide as a limb and every one-limb value is made short, so the one-limb branch never runs, on any host |
| a38171d run-test262 | not needed | not engine |
| 21cbd86 JS_ToInt64SatF, String.prototype.repeat | not needed | `repeat` with ±Infinity/NaN counts throws the right error; the helper hunk needs iterator helpers |
| c8ef889 leak in Atomics.store (#537) | not needed | a leak of the converted value when the array is detached or the index is out of bounds; no double free or use after free; Kotlin output does not use Atomics |
| d88b1ed iterator take/drop limits | not needed | iterator helpers |
| c17f7fc BigInt.asUintN (#557) | **taken** (replaces the fork's own fix) | `Long` as BigInt; upstream's code verbatim (maintainer's decision, 2026-11-23) |
| a479eff regexp unicode-set parsing (#520) | not needed | `v`-flag regexps; Kotlin's Regex does not emit `v` |
| 87dc7cb JS_NewSymbol | not needed | API |
| f835c1d test262 update | not needed | tests |

Between the 2026-06-04 tarball and `04be246` (outside the range): 445624b, 8f0c037, 088cf5e and
762e6fc (inlined equality and relational operators, and a typo fix to them), 42d08be (faster
slice/splice), 04be246 (run-test262). All performance work, not taken; the BigInt fixes were
checked against the tarball's code.

## The hunks of 0943061

`0943061` adds `JS_DefinePropertyValueC`, which defines a property without taking ownership of the
value, and moves most callers to it. Most hunks replace `JS_DefinePropertyValue(…,
JS_DupValue(ctx, v), …)` with `JS_DefinePropertyValueC(…, v, …)`: the same reference counts on
every path, so no fix. Others start checking a result that was ignored; there the value was
already freed by the failed call, so ignoring the error leaves an exception pending but frees
nothing twice and leaks nothing.

| Hunk (function) | In the release? | Taken? | Why |
|---|---|---|---|
| `JS_DefinePropertyValueC` added; `JS_DefinePropertyValue` calls it | yes | partly: the helper | `js_promise_withResolvers` needs it; `JS_DefinePropertyValue` is left as it was (the rewrite is equivalent) |
| `js_instantiate_prototype` | yes | no | equivalent rewrite |
| `js_op_define_class` (two calls) | yes | no | equivalent rewrite |
| `js_parse_with_clause` | yes | no | equivalent rewrite |
| `JS_SetConstructor2` (two calls) | yes | no | equivalent rewrite |
| `js_object_getOwnPropertyDescriptor` | yes | no | equivalent rewrite |
| `js_function_bind` (`length`, `name`) | yes | no | result now checked; the failed call had freed the value, no memory error |
| `js_error_constructor` (`message`, `cause`, `errors`) | yes | no | result now checked; no memory error |
| `js_error_constructor` (`SuppressedError`) | no | no | SuppressedError comes with `using` (out of scope) |
| `js_error_set_stack` | no | no | the stack accessor (da278b8, out of scope) |
| `js_aggregate_error_constructor` | yes | no | result now checked; no memory error |
| `js_iterator_constructor_getset` | yes | no | equivalent rewrite |
| `js_iterator_proto_set_toStringTag` | yes | no | equivalent rewrite |
| `js_string_constructor` (`length`) | yes | no | result now checked; no memory error |
| `js_regexp_exec` (two group hunks) | yes | no | equivalent rewrite: the release's error path frees its own reference and the failed call freed the duplicate |
| `JS_JSONStringify` | yes | no | equivalent rewrite |
| `js_create_desc` | yes | no | results now checked; no memory error |
| `js_proxy_revocable` | yes | no | results now checked; the release frees nothing twice (`obj` is never freed on its `fail` path) |
| `js_object_groupBy` | yes | no | equivalent rewrite |
| `js_new_suppressed_error` | no | no | `using` (out of scope) |
| `js_promise_withResolvers` | yes | **yes** | a **double free**: a failed `JS_DefinePropertyValue` had already freed the promise or resolving function, and the `exception:` label freed it again. Upstream's function verbatim |
| `js_promise_all_resolve_element` | yes | no | equivalent rewrite |
| `js_promise_allKeyed_resolve_element` | no | no | `Promise.allKeyed` (out of scope) |
| `JS_AddIntrinsicBaseObjects` (`globalThis`) | yes | no | equivalent rewrite |
| `js_atomics_wait_result` | no | no | a double free, but in `Atomics.waitAsync` (f2eb204, out of scope); the release's `Atomics.wait` has no such path |
| `JS_AddIntrinsicTypedArrays` | yes | **yes** | a **leak**: `Array.prototype.toString` was not freed when reading `%TypedArray%.prototype` failed during context creation |

## Known and not fixed upstream

`js_proxy_get_own_property_names` sizes its table as `sizeof(JSPropertyEnum) * len`, with `len`
from the `ownKeys` trap's result. On a 32-bit host (armeabi-v7a, x86) a length just over 2^29
makes that product wrap to a few bytes, and the loop then writes past the table: for example
`Reflect.ownKeys(new Proxy({}, { ownKeys: () => ({ length: 2 ** 29 + 1, 0: 'a', 1: 'b' }) }))`.
On 64-bit hosts the allocation fails cleanly. Upstream's e25861c limits the length to 2^31 - 1,
which does not prevent the wrap, so master has the same code. Not changed in the fork, which
takes upstream fixes only; Kotlin/JS output does not use Proxy. Found by reading the code; not
reproduced on a 32-bit device.
