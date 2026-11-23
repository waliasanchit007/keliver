/*
 * Copyright (C) 2026 Keliver contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
/*
 * NF2 step 2: a minimal script runner on the fork's engine, built with the NDK for each Android ABI
 * (`apply.sh qjs-android`) and run on a device or emulator over adb (`apply.sh check-bigint-device`).
 * It runs one script file with a global print(), and reports on stderr how the engine was built
 * for this ABI: NaN-boxed values (JS_NAN_BOXING, 32-bit ABIs) and the BigInt limb size.
 * --memory-limit <bytes> sets the runtime's memory limit (JS_SetMemoryLimit), as qjs's option does;
 * check-bigint-device runs ../zipline-fork/size-guards.js under one.
 * Exit status: 0 when the script ran, 1 when it threw, 2 when it could not start.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "quickjs.h"

static JSValue js_print(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
  for (int i = 0; i < argc; i++) {
    size_t len;
    const char *s = JS_ToCStringLen(ctx, &len, argv[i]);
    if (!s) return JS_EXCEPTION;
    if (i > 0) fputc(' ', stdout);
    fwrite(s, 1, len, stdout);
    JS_FreeCString(ctx, s);
  }
  fputc('\n', stdout);
  return JS_UNDEFINED;
}

static char *read_file(const char *path, size_t *len) {
  FILE *f = fopen(path, "rb");
  if (!f) return NULL;
  if (fseek(f, 0, SEEK_END) != 0) { fclose(f); return NULL; }
  long n = ftell(f);
  if (n < 0 || fseek(f, 0, SEEK_SET) != 0) { fclose(f); return NULL; }
  char *buf = malloc((size_t) n + 1);
  if (!buf || fread(buf, 1, (size_t) n, f) != (size_t) n) { fclose(f); free(buf); return NULL; }
  buf[n] = '\0';
  fclose(f);
  *len = (size_t) n;
  return buf;
}

int main(int argc, char **argv) {
  size_t memory_limit = 0;
  if (argc == 4 && strcmp(argv[1], "--memory-limit") == 0) {
    char *end;
    unsigned long long n = strtoull(argv[2], &end, 10);
    if (*argv[2] == '\0' || *end != '\0' || n == 0 || (unsigned long long) (size_t) n != n) {
      fprintf(stderr, "run-js: bad --memory-limit %s\n", argv[2]);
      return 2;
    }
    memory_limit = (size_t) n;
    argv += 2;
    argc -= 2;
  }
  if (argc != 2) { fprintf(stderr, "usage: run-js [--memory-limit <bytes>] <script.js>\n"); return 2; }
#ifdef JS_NAN_BOXING
  const char *boxing = "yes";
#else
  const char *boxing = "no";
#endif
  fprintf(stderr, "run-js: QuickJS %s, JS_NAN_BOXING %s, %d-bit BigInt limbs, %d-bit pointers\n",
          CONFIG_VERSION, boxing, JS_LIMB_BITS, (int) (sizeof(void *) * 8));

  size_t len;
  char *src = read_file(argv[1], &len);
  if (!src) { fprintf(stderr, "run-js: cannot read %s\n", argv[1]); return 2; }

  JSRuntime *rt = JS_NewRuntime();
  if (!rt) { fprintf(stderr, "run-js: JS_NewRuntime failed\n"); free(src); return 2; }
  if (memory_limit != 0) JS_SetMemoryLimit(rt, memory_limit);
  JSContext *ctx = JS_NewContext(rt);
  if (!ctx) { fprintf(stderr, "run-js: JS_NewContext failed\n"); JS_FreeRuntime(rt); free(src); return 2; }
  JSValue global = JS_GetGlobalObject(ctx);
  JS_SetPropertyStr(ctx, global, "print", JS_NewCFunction(ctx, js_print, "print", 1));
  JS_FreeValue(ctx, global);

  int rc = 0;
  JSValue v = JS_Eval(ctx, src, len, argv[1], JS_EVAL_TYPE_GLOBAL);
  if (JS_IsException(v)) {
    JSValue e = JS_GetException(ctx);
    const char *s = JS_ToCString(ctx, e);
    fprintf(stderr, "run-js: uncaught %s\n", s ? s : "(exception)");
    if (s) JS_FreeCString(ctx, s);
    JS_FreeValue(ctx, e);
    rc = 1;
  }
  JS_FreeValue(ctx, v);
  fflush(stdout);
  JS_FreeContext(ctx);
  JS_FreeRuntime(rt);
  free(src);
  return rc;
}
