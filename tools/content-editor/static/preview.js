// 3D previews on deepslate (vendor/deepslate-*.umd.js, window.deepslate): a block or item
// model on one block or a 3×3 wall, and box-model mobs wearing a host-overlay composite.
// Drag to orbit, scroll to zoom. Textures are drawn into our own atlas at full size,
// because deepslate's TextureAtlas.fromBlobs crops every texture to 16×16.

const ds = () => window.deepslate;

// --- tiny column-major mat4 helpers (deepslate bundles gl-matrix but does not export it) ---
function identity() { const m = new Float32Array(16); m[0] = m[5] = m[10] = m[15] = 1; return m; }
function multiply(a, b) {
  const o = new Float32Array(16);
  for (let c = 0; c < 4; c++) for (let r = 0; r < 4; r++) {
    let s = 0;
    for (let k = 0; k < 4; k++) s += a[k * 4 + r] * b[c * 4 + k];
    o[c * 4 + r] = s;
  }
  return o;
}
function translate(x, y, z) { const m = identity(); m[12] = x; m[13] = y; m[14] = z; return m; }
function rotateX(a) { const m = identity(), c = Math.cos(a), s = Math.sin(a); m[5] = c; m[6] = s; m[9] = -s; m[10] = c; return m; }
function rotateY(a) { const m = identity(), c = Math.cos(a), s = Math.sin(a); m[0] = c; m[2] = -s; m[8] = s; m[10] = c; return m; }

async function blobOf(url) {
  const res = await fetch(url);
  if (!res.ok) throw new Error(`${res.status} loading ${url}`);
  return res.blob();
}

// An atlas provider holding every texture at its own resolution, scaled into equal power-of-two cells.
async function buildAtlas(blobs) {
  const ids = Object.keys(blobs);
  const bitmaps = await Promise.all(ids.map((id) => createImageBitmap(blobs[id])));
  let cell = 16;
  for (const b of bitmaps) while (cell < Math.max(b.width, b.height)) cell *= 2;
  let grid = 1;
  while (grid * grid < ids.length + 1) grid *= 2;
  const size = grid * cell;
  const canvas = document.createElement('canvas');
  canvas.width = canvas.height = size;
  const ctx = canvas.getContext('2d');
  ctx.imageSmoothingEnabled = false;
  ctx.fillStyle = '#ff00ff';                    // slot 0: missing texture
  ctx.fillRect(0, 0, cell / 2, cell / 2);
  ctx.fillRect(cell / 2, cell / 2, cell / 2, cell / 2);
  const part = cell / size, uv = {};
  bitmaps.forEach((bitmap, i) => {
    const slot = i + 1, u = slot % grid, v = Math.floor(slot / grid);
    ctx.drawImage(bitmap, u * cell, v * cell, cell, cell);
    uv[ids[i]] = [u * part, v * part, u * part + part, v * part + part];
  });
  const image = ctx.getImageData(0, 0, size, size);
  return {
    getTextureAtlas: () => image,
    getTextureUV: (id) => uv[id.toString()] ?? [0, 0, part, part],
    getPixelSize: () => 1 / size,
  };
}

function resources(atlas, models, rootKey) {
  const definition = ds().BlockDefinition.fromJson({ variants: { '': { model: rootKey } } });
  return {
    getBlockDefinition: (id) => (id.toString() === 'preview:block' ? definition : null),
    getBlockModel: (id) => models.get(id.toString()) ?? null,
    getTextureAtlas: () => atlas.getTextureAtlas(),
    getTextureUV: (id) => atlas.getTextureUV(id),
    getPixelSize: () => atlas.getPixelSize(),
    getBlockFlags: () => ({ opaque: false }),
    getBlockProperties: () => null,
    getDefaultBlockProperties: () => ({}),
  };
}

// Owns one <canvas>: a structure, the camera and pointer controls.
class Viewport {
  constructor(canvas, structure, res, center, distance) {
    this.canvas = canvas;
    this.gl = canvas.getContext('webgl', { antialias: false, alpha: true, premultipliedAlpha: false });
    if (!this.gl) throw new Error('WebGL is not available in this browser');
    this.renderer = new (ds().StructureRenderer)(this.gl, structure, res, { chunkSize: 8 });
    this.center = center;
    this.home = { yaw: -0.62, pitch: 0.48, distance };
    Object.assign(this, this.home);
    this.bind();
    this.draw();
  }

  reset() { Object.assign(this, this.home); this.draw(); }

  bind() {
    let drag = null;
    this.canvas.addEventListener('pointerdown', (e) => { drag = { x: e.clientX, y: e.clientY }; this.canvas.setPointerCapture(e.pointerId); });
    this.canvas.addEventListener('pointermove', (e) => {
      if (!drag) return;
      this.yaw += (e.clientX - drag.x) * 0.01;
      this.pitch = Math.max(-1.5, Math.min(1.5, this.pitch + (e.clientY - drag.y) * 0.01));
      drag = { x: e.clientX, y: e.clientY };
      this.draw();
    });
    this.canvas.addEventListener('pointerup', () => { drag = null; });
    this.canvas.addEventListener('wheel', (e) => {
      e.preventDefault();
      this.distance = Math.max(1.2, Math.min(20, this.distance * (e.deltaY > 0 ? 1.1 : 0.9)));
      this.draw();
    }, { passive: false });
    this.canvas.addEventListener('dblclick', () => this.reset());
  }

  draw() {
    const { gl, canvas } = this;
    const scale = window.devicePixelRatio || 1;
    const w = Math.round(canvas.clientWidth * scale), h = Math.round(canvas.clientHeight * scale);
    if (canvas.width !== w || canvas.height !== h) { canvas.width = w; canvas.height = h; }
    this.renderer.setViewport(0, 0, w, h);
    gl.clearColor(0, 0, 0, 0);
    gl.clear(gl.COLOR_BUFFER_BIT | gl.DEPTH_BUFFER_BIT);
    const [cx, cy, cz] = this.center;
    let view = translate(0, 0, -this.distance);
    view = multiply(view, rotateX(this.pitch));
    view = multiply(view, rotateY(this.yaw));
    view = multiply(view, translate(-cx, -cy, -cz));
    this.renderer.drawStructure(view);
  }
}

// --- block and item models ---------------------------------------------------

function norm(id) { return ds().Identifier.parse(id).toString(); }
export { norm as slugId };

async function loadModelChain(rootId, fetchModel) {
  const raw = new Map();
  let id = norm(rootId);
  while (id && !raw.has(id)) {
    const json = await fetchModel(id);
    if (!json) throw new Error(`model ${id} not found`);
    raw.set(id, json);
    const parent = json.parent;
    id = parent && !/^(minecraft:)?builtin\//.test(parent) ? norm(parent) : null;
  }
  return raw;
}

function textureIds(raw) {
  const ids = new Set();
  for (const json of raw.values()) {
    for (const value of Object.values(json.textures ?? {})) {
      const ref = typeof value === 'string' ? value : value?.sprite;
      if (ref && !ref.startsWith('#')) ids.add(norm(ref));
    }
  }
  return [...ids];
}

// opts: { modelId, layout: 'block' | 'wall', fetchModel(id) -> json, textureUrl(id) -> url }
export async function modelPreview(canvas, opts) {
  const raw = await loadModelChain(opts.modelId, opts.fetchModel);
  const models = new Map([...raw].map(([id, json]) => [id, ds().BlockModel.fromJson(json)]));
  const blobs = {};
  await Promise.all(textureIds(raw).map(async (id) => {
    try { blobs[id] = await blobOf(opts.textureUrl(id)); } catch { /* drawn as missing */ }
  }));
  const atlas = await buildAtlas(blobs);
  const rootKey = norm(opts.modelId);
  const res = resources(atlas, models, rootKey);
  models.get(rootKey).flatten(res);
  const wall = opts.layout === 'wall';
  const structure = new (ds().Structure)(wall ? [3, 3, 1] : [1, 1, 1]);
  if (wall) for (let x = 0; x < 3; x++) for (let y = 0; y < 3; y++) structure.addBlock([x, y, 0], 'preview:block');
  else structure.addBlock([0, 0, 0], 'preview:block');
  return new Viewport(canvas, structure, res, wall ? [1.5, 1.5, 0.5] : [0.5, 0.5, 0.5], wall ? 5.2 : 2.1);
}

// --- mobs wearing the host overlay ---------------------------------------------
// Boxes in texels on the vanilla UV layouts: [x, y, z, w, h, d, u, v, mode]; y counts down from the top and the
// mob faces +z, so +x is its left. 'mirror' reuses the right limb's texels flipped, as vanilla's HumanoidModel
// (zombies, husks, drowned) and QuadrupedModel do; only PlayerModel has separate left limbs at (32,48) and (16,48).
const HUMANOID = { sheet: [64, 64], height: 32, boxes: [
  [4, 0, 0, 8, 8, 8, 0, 0], [4, 8, 2, 8, 12, 4, 16, 16], [0, 8, 2, 4, 12, 4, 40, 16],
  [12, 8, 2, 4, 12, 4, 40, 16, 'mirror'], [4, 20, 2, 4, 12, 4, 0, 16], [8, 20, 2, 4, 12, 4, 0, 16, 'mirror'],
] };
const PLAYER = { sheet: [64, 64], height: 32, boxes: [
  [4, 0, 0, 8, 8, 8, 0, 0], [4, 8, 2, 8, 12, 4, 16, 16], [0, 8, 2, 4, 12, 4, 40, 16],
  [12, 8, 2, 4, 12, 4, 32, 48], [4, 20, 2, 4, 12, 4, 0, 16], [8, 20, 2, 4, 12, 4, 16, 48],
] };
const QUADRUPED = { sheet: [64, 32], height: 20, boxes: [
  [1, 2, 14, 8, 8, 8, 0, 0], [0, 6, 0, 10, 8, 16, 28, 8, 'lying'],
  [0, 14, 0, 4, 6, 4, 0, 16], [6, 14, 0, 4, 6, 4, 0, 16, 'mirror'], [0, 14, 12, 4, 6, 4, 0, 16], [6, 14, 12, 4, 6, 4, 0, 16, 'mirror'],
] };
const LAYOUTS = { humanoid: HUMANOID, player: PLAYER, quadruped: QUADRUPED };

function boxElement(box, layout, scale, offset) {
  const [x, y, z, w, h, d, u, v, mode] = box;
  const [W, H] = layout.sheet;
  const su = 16 / W, sv = 16 / H;
  const uv = (u0, v0, u1, v1) => [u0 * su, v0 * sv, u1 * su, v1 * sv];
  const from = [offset[0] + x * scale, offset[1] + (layout.height - y - h) * scale, offset[2] + z * scale];
  const to = [from[0] + w * scale, from[1] + h * scale, from[2] + d * scale];
  let faces;
  if (mode === 'lying') {
    // A body modelled upright (w × l × h on the sheet) and rotated to lie along z, as vanilla quadrupeds do.
    const l = d, t = h;  // sheet box is w × l × t
    faces = {
      up: { uv: uv(u + t, v + t, u + t + w, v + t + l) },
      down: { uv: uv(u + 2 * t + w, v + t, u + 2 * t + 2 * w, v + t + l) },
      north: { uv: uv(u + t + w, v, u + t + 2 * w, v + t) },
      south: { uv: uv(u + t, v, u + t + w, v + t) },
      west: { uv: uv(u, v + t, u + t, v + t + l), rotation: 90 },
      east: { uv: uv(u + t + w, v + t, u + 2 * t + w, v + t + l), rotation: 270 },
    };
  } else if (mode === 'mirror') {
    // Flipped horizontally: each face's u runs backwards and the two sides swap.
    const m = (u0, v0, u1, v1) => uv(u1, v0, u0, v1);
    faces = {
      up: { uv: m(u + d, v, u + d + w, v + d) },
      down: { uv: m(u + d + w, v, u + d + 2 * w, v + d) },
      south: { uv: m(u + d, v + d, u + d + w, v + d + h) },
      north: { uv: m(u + 2 * d + w, v + d, u + 2 * d + 2 * w, v + d + h) },
      west: { uv: m(u + d + w, v + d, u + 2 * d + w, v + d + h) },
      east: { uv: m(u, v + d, u + d, v + d + h) },
    };
  } else {
    faces = {
      up: { uv: uv(u + d, v, u + d + w, v + d) },
      down: { uv: uv(u + d + w, v, u + d + 2 * w, v + d) },
      south: { uv: uv(u + d, v + d, u + d + w, v + d + h) },
      north: { uv: uv(u + 2 * d + w, v + d, u + 2 * d + 2 * w, v + d + h) },
      west: { uv: uv(u, v + d, u + d, v + d + h) },
      east: { uv: uv(u + d + w, v + d, u + 2 * d + w, v + d + h) },
    };
  }
  for (const face of Object.values(faces)) face.texture = '#skin';
  return { from, to, faces };
}

// opts: { kind: 'humanoid' | 'player' | 'quadruped', textureUrl }
export async function mobPreview(canvas, opts) {
  const layout = LAYOUTS[opts.kind] || HUMANOID;
  const scale = 0.5;
  const width = Math.max(...layout.boxes.map((b) => b[0] + b[3])) * scale;
  const depth = Math.max(...layout.boxes.map((b) => b[2] + b[5])) * scale;
  const offset = [8 - width / 2, 0, 8 - depth / 2];
  const json = { textures: { skin: 'preview:skin' }, elements: layout.boxes.map((b) => boxElement(b, layout, scale, offset)) };
  const models = new Map([['preview:model', ds().BlockModel.fromJson(json)]]);
  const atlas = await buildAtlas({ 'preview:skin': await blobOf(opts.textureUrl) });
  const res = resources(atlas, models, 'preview:model');
  models.get('preview:model').flatten(res);
  const structure = new (ds().Structure)([1, 1, 1]);
  structure.addBlock([0, 0, 0], 'preview:block');
  const top = layout.height * scale / 16;
  return new Viewport(canvas, structure, res, [0.5, top / 2, 0.5], opts.kind === 'quadruped' ? 1.5 : 1.55);
}
