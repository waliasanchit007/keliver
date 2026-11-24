// Research glue (eff-webview): timing marks for the Android host's KMark interface, and a frame
// recorder for the scrolling series. Epoch milliseconds throughout (the host's wall clock).
(function () {
  function now() { return performance.timeOrigin + performance.now(); }
  window.kpoint = function (name) {
    console.log('KMARK point ' + name + ' perf=' + performance.now().toFixed(1));
    if (window.KMark) KMark.point(name, now());
  };
  // A screen's composition was applied (DisposableEffect): report the frame after it has painted
  // (the next animation frame, then a task after it), as the three-way benchmark's web form does.
  window.kmark = function (name, seq) {
    console.log('KMARK composed ' + name + ' ' + seq + ' perf=' + performance.now().toFixed(1));
    if (window.KMark) KMark.composed(name, seq, now());
    requestAnimationFrame(function () {
      setTimeout(function () {
        console.log('KMARK painted ' + name + ' ' + seq + ' perf=' + performance.now().toFixed(1));
        if (window.KMark) KMark.mark(name, seq, now());
      }, 0);
    });
  };
  // Scrolling series: a continuous requestAnimationFrame loop on the page's main thread (where the
  // Compose frame runs), recording each frame's time; started by the host, read back by window.
  var frames = [];
  var recording = false;
  function loop(ts) {
    if (!recording) return;
    frames.push(performance.timeOrigin + ts);
    requestAnimationFrame(loop);
  }
  window.__kstart = function () { frames = []; recording = true; requestAnimationFrame(loop); return 'ok'; };
  window.__kstop = function () { recording = false; return JSON.stringify(frames); };
  window.addEventListener('error', function (e) { console.log('KERR ' + e.message + ' @' + e.filename + ':' + e.lineno); });
  window.addEventListener('unhandledrejection', function (e) { console.log('KERR rejection ' + e.reason); });
  window.kpoint('htmlStart');
})();
