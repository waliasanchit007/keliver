/*
 * NF2 step 2: a minimal script runner on the fork's engine, built with the NDK for each Android ABI
 * (`apply.sh qjs-android`) and run on a device or emulator over adb (`apply.sh check-bigint-device`).
 * It runs one script file with a global print(), and reports on stderr how the engine was built
 * for this ABI: NaN-boxed values (JS_NAN_BOXING, 32-bit ABIs) and the BigInt limb size.
 *
 * Copyright (C) 2026 Keliver contributors. Apache License 2.0, as the rest of the patch set.
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
  fseek(f, 0, SEEK_END);
  long n = ftell(f);
  fseek(f, 0, SEEK_SET);
  char *buf = malloc(n + 1);
  if (!buf || fread(buf, 1, n, f) != (size_t) n) { fclose(f); free(buf); return NULL; }
  buf[n] = '\0';
  fclose(f);
  *len = (size_t) n;
  return buf;
}

int main(int argc, char **argv) {
  if (argc != 2) { fprintf(stderr, "usage: run-js <script.js>\n"); return 2; }
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
  JSContext *ctx = JS_NewContext(rt);
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
