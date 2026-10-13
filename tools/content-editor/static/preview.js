// 3D previews on deepslate (vendor/deepslate-*.umd.js, window.deepslate): a block or item
// model on one block or a 3×3 wall, box-model mobs wearing a host-overlay composite, and an
// item held in the first- and third-person right hand and drawn in the inventory.
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
function rotateZ(a) { const m = identity(), c = Math.cos(a), s = Math.sin(a); m[0] = c; m[1] = s; m[4] = -s; m[5] = c; return m; }
function scale(x, y, z) { const m = identity(); m[0] = x; m[5] = y; m[10] = z; return m; }
function chain(...ms) { return ms.reduce((a, b) => multiply(a, b)); }
function perspective(fovy, aspect, near, far) {
  const m = new Float32Array(16), f = 1 / Math.tan(fovy / 2);
  m[0] = f / aspect; m[5] = f; m[10] = (far + near) / (near - far); m[11] = -1; m[14] = 2 * far * near / (near - far);
  return m;
}
function ortho(l, r, b, t, n, f) {
  const m = identity();
  m[0] = 2 / (r - l); m[5] = 2 / (t - b); m[10] = -2 / (f - n);
  m[12] = -(r + l) / (r - l); m[13] = -(t + b) / (t - b); m[14] = -(f + n) / (f - n);
  return m;
}

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
  const [x, y, z, w, h, d] = box;
  const from = [offset[0] + x * scale, offset[1] + (layout.height - y - h) * scale, offset[2] + z * scale];
  const to = [from[0] + w * scale, from[1] + h * scale, from[2] + d * scale];
  return { from, to, faces: boxFaces(box, layout.sheet) };
}

// The six faces of a box on a vanilla UV layout, for an element whose front faces +z.
function boxFaces(box, [W, H]) {
  const [, , , w, h, d, u, v, mode] = box;
  const su = 16 / W, sv = 16 / H;
  const uv = (u0, v0, u1, v1) => [u0 * su, v0 * sv, u1 * su, v1 * sv];
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
  return faces;
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

// --- items in hand -------------------------------------------------------------
// Placement follows vanilla 1.21.1 with the item at rest (no swing, equip or bob): ItemInHandRenderer (first
// person: the item alone at (0.56, -0.52, -0.72) in view space under a 70° projection), ItemInHandLayer and
// PlayerModel (third person: the right arm at its ITEM pose, -18° pitch and the idle sway at tick 0, then -90° X,
// 180° Y and (1/16, 2/16, -10/16)), LivingEntityRenderer and PlayerRenderer (the 180° turn, the flip, 0.9375 and
// -1.501), ItemTransform (each model's `display` entry, with the clamps its JSON reader applies) and
// ItemModelGenerator (layer textures extruded 1/16 thick at z 7.5–8.5, with edge faces along each opaque span).

const DEG = Math.PI / 180;
const ITEM_CENTRE = chain(translate(-0.5, -0.5, -0.5), scale(1 / 16, 1 / 16, 1 / 16));
const GENERATED_LAYERS = ['layer0', 'layer1', 'layer2', 'layer3', 'layer4'];
const FLIP = scale(1, -1, -1);  // vanilla model space (y down, front -z) to element space (y up, front +z)
// Wide player model parts: [cube x, y, z, w, h, d, u, v], pivot, rotation (x, y, z) in radians.
const PLAYER_PARTS = [
  { cube: [-4, -8, -4, 8, 8, 8, 0, 0], pivot: [0, 0, 0] },
  { cube: [-4, 0, -2, 8, 12, 4, 16, 16], pivot: [0, 0, 0] },
  { cube: [-3, -2, -2, 4, 12, 4, 40, 16], pivot: [-5, 2, 0], rot: [-Math.PI / 10, 0, 0.1], hand: true },
  { cube: [-1, -2, -2, 4, 12, 4, 32, 48], pivot: [5, 2, 0], rot: [0, 0, -0.1] },
  { cube: [-2, 0, -2, 4, 12, 4, 0, 16], pivot: [-1.9, 12, 0] },
  { cube: [-2, 0, -2, 4, 12, 4, 16, 48], pivot: [1.9, 12, 0] },
];
// Model space to world: PlayerRenderer at body yaw 0 (facing +z).
const ENTITY = chain(rotateY(Math.PI), scale(-1, -1, 1), scale(0.9375, 0.9375, 0.9375), translate(0, -1.501, 0));

function partPose(part) {
  const [x, y, z] = part.rot || [0, 0, 0];
  return chain(translate(part.pivot[0] / 16, part.pivot[1] / 16, part.pivot[2] / 16), rotateZ(z), rotateY(y), rotateX(x));
}

// The `display` entry for a context: the nearest model in the parent chain that defines it wins outright.
function displayMatrix(raw, rootId, context) {
  let t = null;
  for (const json of raw.values()) { if (json.display?.[context]) { t = json.display[context]; break; } }
  if (!t) return identity();
  const clamp = (v, lim) => Math.max(-lim, Math.min(lim, v));
  const [tx, ty, tz] = (t.translation || [0, 0, 0]).map((v) => clamp(v * 0.0625, 5));
  const [rx, ry, rz] = (t.rotation || [0, 0, 0]).map((v) => v * DEG);
  const [sx, sy, sz] = (t.scale || [1, 1, 1]).map((v) => clamp(v, 4));
  return chain(translate(tx, ty, tz), rotateX(rx), rotateY(ry), rotateZ(rz), scale(sx, sy, sz));
}

// ItemModelGenerator: the front and back of each layer and one edge face per span of opaque texels.
function generatedElements(layer, key, image) {
  const { width: W, height: H, data } = image;
  const clear = (x, y) => x < 0 || y < 0 || x >= W || y >= H || data[(y * W + x) * 4 + 3] === 0;
  const facings = [['up', 0, -1, true], ['down', 0, 1, true], ['east', -1, 0, false], ['west', 1, 0, false]];
  const spans = [];
  for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
    if (clear(x, y)) continue;
    for (const [face, dx, dy, horizontal] of facings) {
      if (!clear(x + dx, y + dy)) continue;
      const anchor = horizontal ? y : x, pos = horizontal ? x : y;
      const span = spans.find((sp) => sp.face === face && sp.anchor === anchor);
      if (span) { span.min = Math.min(span.min, pos); span.max = Math.max(span.max, pos); }
      else spans.push({ face, anchor, min: pos, max: pos });
    }
  }
  const tex = `#${key}`;
  const out = [{ from: [0, 0, 7.5], to: [16, 16, 8.5], faces: {
    south: { texture: tex, uv: [0, 0, 16, 16], tintindex: layer }, north: { texture: tex, uv: [16, 0, 0, 16], tintindex: layer } } }];
  const su = 16 / W, sv = 16 / H;
  for (const { face, anchor, min, max } of spans) {
    let x0, x1, y0, y1, u0, u1, v0, v1;
    if (face === 'up' || face === 'down') {
      x0 = min; x1 = max + 1; u0 = min; u1 = max + 1; v0 = anchor; v1 = anchor + 1;
      y0 = y1 = face === 'up' ? anchor : anchor + 1;
    } else {
      x0 = x1 = face === 'east' ? anchor : anchor + 1;
      u0 = anchor; u1 = anchor + 1; v0 = max + 1; v1 = min; y0 = min; y1 = max + 1;
    }
    const ya = 16 - y0 * sv, yb = 16 - y1 * sv;
    out.push({ from: [x0 * su, Math.min(ya, yb), 7.5], to: [x1 * su, Math.max(ya, yb), 8.5],
      faces: { [face]: { texture: tex, uv: [u0 * su, v0 * sv, u1 * su, v1 * sv], tintindex: layer } } });
  }
  return out;
}

async function imageDataOf(blob) {
  const bitmap = await createImageBitmap(blob);
  const canvas = document.createElement('canvas');
  canvas.width = bitmap.width;
  canvas.height = Math.min(bitmap.height, bitmap.width);  // an animation strip: its first frame
  const ctx = canvas.getContext('2d');
  ctx.drawImage(bitmap, 0, 0);
  return ctx.getImageData(0, 0, canvas.width, canvas.height);
}

function fallbackSkin() {
  const canvas = document.createElement('canvas');
  canvas.width = canvas.height = 64;
  const ctx = canvas.getContext('2d');
  ctx.fillStyle = '#9a9a9a'; ctx.fillRect(0, 0, 64, 64);
  ctx.fillStyle = '#7d7d7d'; ctx.fillRect(40, 16, 16, 16); ctx.fillRect(32, 48, 16, 16);
  return new Promise((done) => canvas.toBlob(done));
}

// Loads the item model and the player and returns meshes for each view.
async function heldMeshes(opts) {
  const raw = await loadModelChain(opts.modelId, opts.fetchModel);
  const rootKey = norm(opts.modelId);
  const models = new Map([...raw].map(([id, json]) => [id, ds().BlockModel.fromJson(json)]));
  const blobs = {};
  await Promise.all(textureIds(raw).map(async (id) => {
    try { blobs[id] = await blobOf(opts.textureUrl(id)); } catch { /* drawn as missing */ }
  }));
  try { blobs['preview:skin'] = await blobOf(opts.skinUrl); } catch { blobs['preview:skin'] = await fallbackSkin(); }
  const atlas = await buildAtlas(blobs);
  const partKeys = PLAYER_PARTS.map((part, i) => {
    const [x, y, z, w, h, d] = part.cube;
    const from = [x, -(y + h), -(z + d)], to = [x + w, -y, -z];  // FLIP of the cube, around its pivot
    const key = `preview:part${i}`;
    models.set(key, ds().BlockModel.fromJson({ textures: { skin: 'preview:skin' }, elements: [{ from, to, faces: boxFaces(part.cube, [64, 64]) }] }));
    return key;
  });
  const res = resources(atlas, models, rootKey);
  const root = models.get(rootKey);
  root.flatten(res);
  if (root.generationMarker) {
    const elements = [];
    for (let i = 0; i < GENERATED_LAYERS.length; i++) {
      const key = GENERATED_LAYERS[i];
      if (!Object.hasOwn(root.textures || {}, key)) break;
      const blob = blobs[root.getTexture(key).toString()];
      if (blob) elements.push(...generatedElements(i, key, await imageDataOf(blob)));
    }
    root.elements = elements;
  }
  const meshOf = (model, matrix) => {
    const mesh = model.getMesh(res, ds().Cull.none(), () => [1, 1, 1]);
    mesh.transform(matrix);
    return mesh;
  };
  const item = (context, before) => meshOf(root, chain(before, displayMatrix(raw, rootKey, context), ITEM_CENTRE));
  const player = new (ds().Mesh)();
  PLAYER_PARTS.forEach((part, i) => {
    const pose = partPose(part);
    player.merge(meshOf(models.get(partKeys[i]), chain(ENTITY, pose, FLIP, scale(1 / 16, 1 / 16, 1 / 16))));
    if (part.hand) {
      player.merge(item('thirdperson_righthand', chain(ENTITY, pose, rotateX(-90 * DEG), rotateY(180 * DEG), translate(1 / 16, 0.125, -0.625))));
    }
  });
  return {
    atlas,
    first: item('firstperson_righthand', translate(0.56, -0.52, -0.72)),
    third: player,
    gui: item('gui', identity()),
  };
}

// Defined on first use: deepslate's Renderer exists only once its bundle has run.
let MeshRendererClass = null;
function meshRenderer(...args) {
  MeshRendererClass ??= class extends ds().Renderer {
    constructor(gl, mesh, atlas, projection) {
      super(gl);
      this.projection = projection;
      this.projMatrix = this.getPerspective();
      this.gl.disable(this.gl.CULL_FACE);  // edge faces of generated items face one way only
      this.mesh = mesh;
      mesh.computeNormals();
      mesh.rebuild(gl, { pos: true, color: true, texture: true, normal: true });
      this.atlasTexture = this.createAtlasTexture(atlas.getTextureAtlas());
      this.pixel = atlas.getPixelSize();
    }

    getPerspective() {
      const canvas = this.gl.canvas;
      return this.projection ? this.projection(canvas.clientWidth / Math.max(1, canvas.clientHeight)) : super.getPerspective();
    }

    draw(view) {
      this.setShader(this.shaderProgram);
      this.setTexture(this.atlasTexture, this.pixel);
      this.prepareDraw(view);
      this.drawMesh(this.mesh, { pos: true, color: true, texture: true, normal: true });
    }
  };
  return new MeshRendererClass(...args);
}

// One canvas: a fixed camera (first person, inventory) or an orbit around the player (third person).
class HeldViewport {
  constructor(canvas, mesh, atlas, mode) {
    this.canvas = canvas;
    this.mode = mode;
    const gl = canvas.getContext('webgl', { antialias: false, alpha: true, premultipliedAlpha: false });
    if (!gl) throw new Error('WebGL is not available in this browser');
    this.gl = gl;
    const projection = mode === 'first' ? (aspect) => perspective(70 * DEG, aspect, 0.05, 100)
      : mode === 'gui' ? () => ortho(-9 / 16, 9 / 16, -9 / 16, 9 / 16, -10, 10)  // a 16-pixel item in an 18-pixel slot
        : (aspect) => perspective(50 * DEG, aspect, 0.05, 100);
    this.renderer = meshRenderer(gl, mesh, atlas, projection);
    this.home = { yaw: -0.45, pitch: 0.2, distance: 2.2 };
    Object.assign(this, this.home);
    if (mode === 'third') this.bind();
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
      this.distance = Math.max(0.6, Math.min(8, this.distance * (e.deltaY > 0 ? 1.1 : 0.9)));
      this.draw();
    }, { passive: false });
    this.canvas.addEventListener('dblclick', () => this.reset());
  }

  draw() {
    const { gl, canvas } = this;
    const ratio = window.devicePixelRatio || 1;
    const w = Math.round(canvas.clientWidth * ratio), h = Math.round(canvas.clientHeight * ratio);
    if (canvas.width !== w || canvas.height !== h) { canvas.width = w; canvas.height = h; }
    this.renderer.setViewport(0, 0, w, h);
    gl.clearColor(0, 0, 0, 0);
    gl.clear(gl.COLOR_BUFFER_BIT | gl.DEPTH_BUFFER_BIT);
    let view = identity();
    if (this.mode === 'third') {
      // Orbit around the chest, starting in front of the player (vanilla's third_person_front, closer).
      view = chain(translate(0, 0, -this.distance), rotateX(this.pitch), rotateY(this.yaw), translate(0, -1.05, 0));
    }
    this.renderer.draw(view);
  }
}

// opts: { modelId, fetchModel(id) -> json, textureUrl(id) -> url, skinUrl }; canvases: { first, third, gui }.
export async function heldPreview(canvases, opts) {
  const meshes = await heldMeshes(opts);
  return Object.entries(canvases).filter(([, c]) => c).map(([mode, canvas]) => new HeldViewport(canvas, meshes[mode], meshes.atlas, mode));
}
