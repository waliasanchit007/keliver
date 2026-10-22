// A minimal PNG decoder (8-bit RGB/RGBA, non-interlaced: what Chrome's
// Page.captureScreenshot produces) and colour-region search, so tests can find
// where the canvas drew something without any npm dependency.
import zlib from 'node:zlib';

/** Decodes [buf] into { width, height, data } with data as RGBA bytes. */
export function decodePng(buf) {
  const sig = '89504e470d0a1a0a';
  if (buf.subarray(0, 8).toString('hex') !== sig) throw new Error('not a PNG');
  let off = 8, width = 0, height = 0, depth = 0, colorType = 0, interlace = 0;
  const idat = [];
  while (off < buf.length) {
    const len = buf.readUInt32BE(off);
    const type = buf.toString('latin1', off + 4, off + 8);
    const body = buf.subarray(off + 8, off + 8 + len);
    if (type === 'IHDR') {
      width = body.readUInt32BE(0); height = body.readUInt32BE(4);
      depth = body[8]; colorType = body[9]; interlace = body[12];
    } else if (type === 'IDAT') idat.push(body);
    else if (type === 'IEND') break;
    off += 12 + len;
  }
  if (depth !== 8 || (colorType !== 2 && colorType !== 6) || interlace !== 0) {
    throw new Error(`unsupported PNG: depth ${depth}, colour type ${colorType}, interlace ${interlace}`);
  }
  const bpp = colorType === 6 ? 4 : 3;
  const raw = zlib.inflateSync(Buffer.concat(idat));
  const stride = width * bpp;
  const out = Buffer.alloc(width * height * 4);
  let prev = Buffer.alloc(stride);
  for (let y = 0; y < height; y++) {
    const filter = raw[y * (stride + 1)];
    const line = Buffer.from(raw.subarray(y * (stride + 1) + 1, (y + 1) * (stride + 1)));
    for (let i = 0; i < stride; i++) {
      const a = i >= bpp ? line[i - bpp] : 0, b = prev[i], c = i >= bpp ? prev[i - bpp] : 0;
      let v = line[i];
      switch (filter) {
        case 0: break;
        case 1: v += a; break;
        case 2: v += b; break;
        case 3: v += (a + b) >> 1; break;
        case 4: { const p = a + b - c, pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
          v += pa <= pb && pa <= pc ? a : pb <= pc ? b : c; break; }
        default: throw new Error(`bad PNG filter ${filter}`);
      }
      line[i] = v & 0xff;
    }
    for (let x = 0; x < width; x++) {
      const o = (y * width + x) * 4;
      out[o] = line[x * bpp]; out[o + 1] = line[x * bpp + 1]; out[o + 2] = line[x * bpp + 2];
      out[o + 3] = bpp === 4 ? line[x * bpp + 3] : 255;
    }
    prev = line;
  }
  return { width, height, data: out };
}

/** An ARGB int (as written in Kotlin source, e.g. -13730453) as [r, g, b]. */
export function argbToRgb(argb) {
  const u = argb >>> 0;
  return [(u >>> 16) & 0xff, (u >>> 8) & 0xff, u & 0xff];
}

/**
 * The bounding box of the pixels within [tol] of [rgb] in [img], or null if
 * fewer than [minPixels] match. Coordinates are image pixels; [origin] (the
 * screenshot's clip origin) is added so the result is in page coordinates.
 */
export function findColor(img, rgb, { tol = 3, minPixels = 40, origin = { x: 0, y: 0 } } = {}) {
  let x0 = Infinity, y0 = Infinity, x1 = -Infinity, y1 = -Infinity, count = 0;
  const { width, height, data } = img;
  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      const o = (y * width + x) * 4;
      if (Math.abs(data[o] - rgb[0]) <= tol && Math.abs(data[o + 1] - rgb[1]) <= tol && Math.abs(data[o + 2] - rgb[2]) <= tol) {
        count++;
        if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y;
      }
    }
  }
  if (count < minPixels) return null;
  const left = x0 + origin.x, top = y0 + origin.y, right = x1 + 1 + origin.x, bottom = y1 + 1 + origin.y;
  return { left, top, right, bottom, width: right - left, height: bottom - top,
    cx: (left + right) / 2, cy: (top + bottom) / 2, count };
}

/** The fraction of pixels that differ (any channel by more than [tol]) between two same-size images. */
export function diffFraction(a, b, tol = 8) {
  if (a.width !== b.width || a.height !== b.height) return 1;
  let diff = 0;
  for (let i = 0; i < a.data.length; i += 4) {
    if (Math.abs(a.data[i] - b.data[i]) > tol || Math.abs(a.data[i + 1] - b.data[i + 1]) > tol ||
      Math.abs(a.data[i + 2] - b.data[i + 2]) > tol) diff++;
  }
  return diff / (a.width * a.height);
}
