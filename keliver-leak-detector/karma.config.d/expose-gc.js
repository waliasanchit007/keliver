// The wasmJs tests run in headless Chrome. Like the js tests in Node (build.gradle), they need the
// engine's gc() (LeakDetectorTest checks the detector found one) and up to 10s of wall clock per
// leak test, so: V8's --expose-gc, and a mocha timeout with room for a slow CI machine. Karma's
// browserNoActivityTimeout (30 s by default) must outlast that timeout, or a hung test reads as a
// disconnected browser instead of mocha's timeout naming the test.
config.set({
  browserNoActivityTimeout: 60000,
  browsers: ['ChromeHeadlessExposeGc'],
  customLaunchers: {
    ChromeHeadlessExposeGc: {
      base: 'ChromeHeadless',
      flags: ['--js-flags=--expose-gc'],
    },
  },
});
config.client.mocha = Object.assign({}, config.client.mocha, { timeout: 30000 });
