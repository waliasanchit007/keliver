// Where does a bundle stop making progress? Prints after each phase.
// Usage: qjs --std engine/probe.js <workload dir>
import * as std from 'std';
const dir = scriptArgs[1];
const cache = {};
function req(name) {
  name = name.replace(/^\.\//, ''); if (!name.endsWith('.js')) name += '.js';
  if (cache[name]) return cache[name].exports;
  const module = { exports: {} }; cache[name] = module;
  print('load ' + name); std.out.flush();
  const fn = std.evalScript('(function (module, exports, require) {' + std.loadFile(dir + '/' + name) + '\n})');
  fn(module, module.exports, req);
  return module.exports;
}
req('guest-profile.js');
print('loaded; init'); std.out.flush();
globalThis.guestProfile('init:mode=kotlinx,items=20');
print('init done; first'); std.out.flush();
const f = globalThis.guestProfile('first');
print('first done: ' + f.slice(0, 120)); std.out.flush();
