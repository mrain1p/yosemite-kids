// The hub's app icons, drawn here rather than checked in as art nobody can
// edit: a teal rounded square, a white screen, a play triangle. Re-run this
// to change them; the PNGs it writes are the checked-in ones.
//
// No image library on this machine, so the PNGs are assembled by hand:
// RGBA scanlines, one zlib IDAT, hand-computed CRCs. Deterministic, so a
// re-run with no edit produces byte-identical files and no diff.
const fs = require("fs");
const path = require("path");
const zlib = require("zlib");

// --- PNG container ---------------------------------------------------------
let TABLE = null;
function crcTable() {
  if (TABLE) return TABLE;
  TABLE = new Int32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    TABLE[n] = c;
  }
  return TABLE;
}
function crc32(buf) {
  const t = crcTable();
  let c = 0xffffffff;
  for (let i = 0; i < buf.length; i++) c = t[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}
function chunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length, 0);
  const t = Buffer.from(type, "ascii");
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(Buffer.concat([t, data])), 0);
  return Buffer.concat([len, t, data, crc]);
}
function png(size, rgba) {
  const sig = Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]);
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(size, 0);
  ihdr.writeUInt32BE(size, 4);
  ihdr[8] = 8;   // bit depth
  ihdr[9] = 6;   // colour type: RGBA
  const stride = size * 4 + 1;
  const raw = Buffer.alloc(stride * size);
  for (let y = 0; y < size; y++) {
    raw[y * stride] = 0; // filter: none
    rgba.copy(raw, y * stride + 1, y * size * 4, (y + 1) * size * 4);
  }
  return Buffer.concat([
    sig,
    chunk("IHDR", ihdr),
    chunk("IDAT", zlib.deflateSync(raw, { level: 9 })),
    chunk("IEND", Buffer.alloc(0)),
  ]);
}

// --- the mark --------------------------------------------------------------
// Brand teal is ui/Theme.kt's, the same colour the GUI's --brand already uses.
const TEAL = [47, 143, 131];
const WHITE = [255, 255, 255];

function inRoundRect(x, y, x0, y0, x1, y1, r) {
  // Clamp to the inner band and measure: exact for the corners, and for a
  // radius of 0 it degenerates to a plain rectangle test.
  const cx = Math.min(Math.max(x, x0 + r), x1 - r);
  const cy = Math.min(Math.max(y, y0 + r), y1 - r);
  const dx = x - cx, dy = y - cy;
  return dx * dx + dy * dy <= r * r;
}
function inTriangle(px, py, ax, ay, bx, by, cx, cy) {
  const s = (bx - ax) * (py - ay) - (by - ay) * (px - ax);
  const t = (cx - bx) * (py - by) - (cy - by) * (px - bx);
  const u = (ax - cx) * (py - cy) - (ay - cy) * (px - cx);
  return (s >= 0 && t >= 0 && u >= 0) || (s <= 0 && t <= 0 && u <= 0);
}

/**
 * @param square  fill the whole canvas (maskable and Apple icons, which the
 *                platform rounds itself) rather than a rounded square.
 * @param scale   shrink the mark toward the centre. 0.8 keeps a maskable
 *                icon's content inside the safe circle whatever shape
 *                Android crops it to.
 */
function sample(x, y, square, scale) {
  const inside = square
    ? x >= 0 && x <= 1 && y >= 0 && y <= 1
    : inRoundRect(x, y, 0, 0, 1, 1, 0.22);
  if (!inside) return [0, 0, 0, 0];
  const qx = (x - 0.5) / scale + 0.5;
  const qy = (y - 0.5) / scale + 0.5;
  let col = TEAL;
  if (inRoundRect(qx, qy, 0.18, 0.24, 0.82, 0.68, 0.07)) col = WHITE;
  if (inTriangle(qx, qy, 0.43, 0.345, 0.43, 0.575, 0.62, 0.46)) col = TEAL;
  return [col[0], col[1], col[2], 255];
}

function render(size, square, scale) {
  const buf = Buffer.alloc(size * size * 4);
  const N = 4; // 4x4 supersampling: the only antialiasing there is
  for (let py = 0; py < size; py++) {
    for (let px = 0; px < size; px++) {
      let r = 0, g = 0, b = 0, a = 0;
      for (let sy = 0; sy < N; sy++) {
        for (let sx = 0; sx < N; sx++) {
          const x = (px + (sx + 0.5) / N) / size;
          const y = (py + (sy + 0.5) / N) / size;
          const c = sample(x, y, square, scale);
          // Premultiply, so a transparent sample cannot drag the colour of
          // an edge pixel toward black.
          r += c[0] * c[3]; g += c[1] * c[3]; b += c[2] * c[3]; a += c[3];
        }
      }
      const i = (py * size + px) * 4;
      if (a === 0) { buf[i] = buf[i + 1] = buf[i + 2] = buf[i + 3] = 0; continue; }
      buf[i] = Math.round(r / a);
      buf[i + 1] = Math.round(g / a);
      buf[i + 2] = Math.round(b / a);
      buf[i + 3] = Math.round(a / (N * N));
    }
  }
  return buf;
}

const out = process.argv[2] || __dirname;
const ICONS = [
  { name: "icon-192.png", size: 192, square: false, scale: 1 },
  { name: "icon-512.png", size: 512, square: false, scale: 1 },
  // Maskable: Android crops this to a circle, a squircle or a teardrop
  // depending on the launcher, so the mark shrinks into the safe zone and
  // the teal runs to every edge.
  { name: "icon-maskable-512.png", size: 512, square: true, scale: 0.8 },
  // iOS composites a transparent icon on black and rounds the corners
  // itself, so this one is opaque and square.
  { name: "apple-touch-icon.png", size: 180, square: true, scale: 1 },
];
for (const ic of ICONS) {
  const file = path.join(out, ic.name);
  fs.writeFileSync(file, png(ic.size, render(ic.size, ic.square, ic.scale)));
  console.log(ic.name, fs.statSync(file).size + " bytes");
}
