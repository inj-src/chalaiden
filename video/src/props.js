// Procedural props: the phone, its stand, the shop's QR stand, a glass of tea, the counter and room.
import * as THREE from 'three';
import { RoundedBoxGeometry } from 'three/addons/geometries/RoundedBoxGeometry.js';
import { drawQr } from './screens.js';

export const PHONE = { w: 0.0715, h: 0.151, d: 0.0078, r: 0.0105, screenW: 0.0676, screenR: 0.0088 };
PHONE.screenH = PHONE.screenW * (1188 / 540);

function roundedRectShape(w, h, r) {
  const s = new THREE.Shape();
  const x = -w / 2, y = -h / 2;
  s.moveTo(x + r, y);
  s.lineTo(x + w - r, y); s.absarc(x + w - r, y + r, r, -Math.PI / 2, 0);
  s.lineTo(x + w, y + h - r); s.absarc(x + w - r, y + h - r, r, 0, Math.PI / 2);
  s.lineTo(x + r, y + h); s.absarc(x + r, y + h - r, r, Math.PI / 2, Math.PI);
  s.lineTo(x, y + r); s.absarc(x + r, y + r, r, Math.PI, Math.PI * 1.5);
  return s;
}

function planeFromShape(w, h, r, segs = 24) {
  const g = new THREE.ShapeGeometry(roundedRectShape(w, h, r), segs);
  const pos = g.attributes.position, uv = g.attributes.uv;
  for (let i = 0; i < pos.count; i++) uv.setXY(i, (pos.getX(i) + w / 2) / w, (pos.getY(i) + h / 2) / h);
  return g;
}

/**
 * The phone. Returns { group, uiMat, vfMat, screenGroup } — the screen has two layers:
 * a camera-feed plane (only shown while scanning) under the UI canvas plane.
 */
export function makePhone(uiTexture, envMap) {
  const group = new THREE.Group();
  const { w, h, d, r } = PHONE;
  const bevel = 0.0011;
  const bodyGeo = new THREE.ExtrudeGeometry(roundedRectShape(w - bevel * 2, h - bevel * 2, r - bevel), {
    depth: d - bevel * 2, bevelEnabled: true, bevelThickness: bevel, bevelSize: bevel, bevelSegments: 8, curveSegments: 40,
  });
  bodyGeo.translate(0, 0, -(d - bevel * 2) / 2);
  const frame = new THREE.MeshPhysicalMaterial({
    color: 0x2b2e35, metalness: 1, roughness: 0.26, clearcoat: 0.6, clearcoatRoughness: 0.15, envMapIntensity: 1.2,
  });
  const body = new THREE.Mesh(bodyGeo, frame);
  body.castShadow = true; body.receiveShadow = true;
  group.add(body);

  // back glass
  const back = new THREE.Mesh(planeFromShape(w - 0.0016, h - 0.0016, r - 0.0008), new THREE.MeshPhysicalMaterial({
    color: 0x1d2026, roughness: 0.38, metalness: 0.2, clearcoat: 1, clearcoatRoughness: 0.2,
  }));
  back.rotation.y = Math.PI; back.position.z = -d / 2 - 0.00002;
  group.add(back);
  // camera island
  const island = new THREE.Mesh(new RoundedBoxGeometry(0.03, 0.032, 0.0018, 4, 0.006), new THREE.MeshPhysicalMaterial({ color: 0x23262c, roughness: 0.25, metalness: 0.5, clearcoat: 1 }));
  island.position.set(0.0165, 0.0545, -d / 2 - 0.0008);
  group.add(island);
  const lensMat = new THREE.MeshPhysicalMaterial({ color: 0x050608, roughness: 0.05, metalness: 0.3, clearcoat: 1 });
  const ringMat = new THREE.MeshStandardMaterial({ color: 0x9aa0a8, roughness: 0.25, metalness: 1 });
  for (const [x, y] of [[0.0105, 0.061], [0.0105, 0.048], [0.0225, 0.0545]]) {
    const ring = new THREE.Mesh(new THREE.CylinderGeometry(0.0052, 0.0052, 0.0016, 40), ringMat);
    ring.rotation.x = Math.PI / 2; ring.position.set(x, y, -d / 2 - 0.0019); group.add(ring);
    const lens = new THREE.Mesh(new THREE.CylinderGeometry(0.0039, 0.0039, 0.0006, 40), lensMat);
    lens.rotation.x = Math.PI / 2; lens.position.set(x, y, -d / 2 - 0.0027); group.add(lens);
  }
  // side buttons
  const btnMat = frame.clone();
  for (const [y, len] of [[0.03, 0.022], [0.0035, 0.012]]) {
    const b = new THREE.Mesh(new RoundedBoxGeometry(0.0012, len, 0.0032, 2, 0.0005), btnMat);
    b.position.set(w / 2 + 0.0002, y, 0); group.add(b);
  }

  // front: black glass bezel
  const zf = d / 2 + 0.00003;
  const glassBlack = new THREE.Mesh(planeFromShape(w - 0.0012, h - 0.0012, r - 0.0006), new THREE.MeshPhysicalMaterial({
    color: 0x020203, roughness: 0.05, metalness: 0, clearcoat: 1, clearcoatRoughness: 0.03,
  }));
  glassBlack.position.z = zf;
  group.add(glassBlack);

  const screenGroup = new THREE.Group();
  screenGroup.position.z = zf + 0.00008;
  group.add(screenGroup);
  const screenGeo = planeFromShape(PHONE.screenW, PHONE.screenH, PHONE.screenR);
  const vfMat = new THREE.MeshBasicMaterial({ color: 0xffffff, toneMapped: false });
  const vf = new THREE.Mesh(screenGeo, vfMat);
  vf.visible = false;
  screenGroup.add(vf);
  const uiMat = new THREE.MeshBasicMaterial({ map: uiTexture, transparent: true, toneMapped: false, depthWrite: false });
  uiMat.color.setScalar(1.0);
  const ui = new THREE.Mesh(screenGeo, uiMat);
  ui.position.z = 0.00004;
  ui.renderOrder = 2;
  screenGroup.add(ui);
  // cover glass: reflections only (additive)
  const cover = new THREE.Mesh(planeFromShape(w - 0.0012, h - 0.0012, r - 0.0006), new THREE.MeshPhysicalMaterial({
    color: 0x000000, roughness: 0.04, metalness: 0, clearcoat: 1, clearcoatRoughness: 0.02,
    transparent: true, blending: THREE.AdditiveBlending, depthWrite: false, envMapIntensity: 0.7,
  }));
  cover.position.z = zf + 0.0003;
  cover.renderOrder = 3;
  group.add(cover);

  return { group, uiMat, vfMat, vf, ui, screenGroup, frameMat: frame };
}

/** Wireless charging stand the phone leans on. Returns { group, mount } — mount is where the phone's bottom-centre sits. */
export function makeStand() {
  const group = new THREE.Group();
  const mat = new THREE.MeshPhysicalMaterial({ color: 0x121316, roughness: 0.55, metalness: 0.0, clearcoat: 0.3, clearcoatRoughness: 0.5 });
  const base = new THREE.Mesh(new THREE.CylinderGeometry(0.047, 0.05, 0.009, 64), mat);
  base.position.y = 0.0045; base.castShadow = base.receiveShadow = true;
  group.add(base);
  const ring = new THREE.Mesh(new THREE.TorusGeometry(0.0475, 0.0007, 8, 96), new THREE.MeshStandardMaterial({ color: 0x8b8f96, metalness: 1, roughness: 0.3 }));
  ring.rotation.x = Math.PI / 2; ring.position.y = 0.009; group.add(ring);
  const tilt = THREE.MathUtils.degToRad(15);
  const backPlate = new THREE.Mesh(new RoundedBoxGeometry(0.058, 0.105, 0.009, 4, 0.004), mat);
  backPlate.position.set(0, 0.06, -0.016);
  backPlate.rotation.x = -tilt;
  backPlate.castShadow = backPlate.receiveShadow = true;
  group.add(backPlate);
  const lip = new THREE.Mesh(new RoundedBoxGeometry(0.062, 0.008, 0.014, 3, 0.003), mat);
  lip.position.set(0, 0.012, 0.004); lip.castShadow = true; group.add(lip);
  const mount = new THREE.Object3D();
  mount.position.set(0, 0.0125, -0.0028);
  mount.rotation.x = -tilt;
  group.add(mount);
  return { group, mount };
}

/** The card a shop keeps at the counter: a QR and, under it, the number. */
export function makeCardCanvas() {
  const W = 1050, H = 1480;
  const c = document.createElement('canvas');
  c.width = W; c.height = H;
  const ctx = c.getContext('2d');
  ctx.fillStyle = '#f6f3ec'; ctx.fillRect(0, 0, W, H);
  // paper fibre noise
  const img = ctx.getImageData(0, 0, W, H);
  let s = 3;
  for (let i = 0; i < img.data.length; i += 4) {
    s = (s * 16807) % 2147483647;
    const n = ((s / 2147483647) - 0.5) * 10;
    img.data[i] += n; img.data[i + 1] += n; img.data[i + 2] += n;
  }
  ctx.putImageData(img, 0, 0);
  ctx.fillStyle = '#d9481c';
  ctx.fillRect(0, 0, W, 250);
  ctx.fillStyle = '#fff';
  ctx.font = '700 84px Geist'; ctx.textAlign = 'center';
  ctx.fillText('SCAN & PAY', W / 2, 128);
  ctx.font = '500 46px Geist'; ctx.globalAlpha = 0.85;
  ctx.fillText('Rahim Store · Mirpur 10', W / 2, 200);
  ctx.globalAlpha = 1;
  ctx.fillStyle = '#fff';
  ctx.beginPath(); ctx.roundRect(175, 300, 700, 700, 36); ctx.fill();
  ctx.strokeStyle = '#e4ddd0'; ctx.lineWidth = 4; ctx.stroke();
  drawQr(ctx, 215, 340, 620, '#111');
  ctx.fillStyle = '#7b756b'; ctx.font = '500 40px Geist';
  ctx.fillText('Merchant number', W / 2, 1090);
  ctx.fillStyle = '#121212';
  ctx.font = '700 100px GeistMono';
  if ('letterSpacing' in ctx) ctx.letterSpacing = '2px';
  const label = '01712 345 678';
  const tw = ctx.measureText(label).width;
  ctx.fillText(label, W / 2, 1218);
  if ('letterSpacing' in ctx) ctx.letterSpacing = '0px';
  ctx.fillStyle = '#9a9387'; ctx.font = '400 34px Geist';
  ctx.fillText('Pay from any mobile wallet', W / 2, 1390);
  ctx.fillStyle = '#d9481c'; ctx.fillRect(0, H - 22, W, 22);
  // number rect in UV (0..1, y down)
  const numRect = { x0: (W / 2 - tw / 2) / W, x1: (W / 2 + tw / 2) / W, y0: 1138 / H, y1: 1236 / H };
  const qrRect = { x0: 215 / W, x1: 835 / W, y0: 340 / H, y1: 960 / H };
  return { canvas: c, numRect, qrRect };
}

export const CARD = { w: 0.105, h: 0.148 };
/** Acrylic L-stand holding the card. Returns { group, card, uvToLocal(u,v) }. */
export function makeQrStand(cardTex) {
  const group = new THREE.Group();
  const tilt = THREE.MathUtils.degToRad(12);
  const acrylic = new THREE.MeshPhysicalMaterial({
    color: 0x000000, roughness: 0.08, clearcoat: 1, clearcoatRoughness: 0.06, transparent: true,
    blending: THREE.AdditiveBlending, depthWrite: false, envMapIntensity: 0.6,
  });
  const black = new THREE.MeshPhysicalMaterial({ color: 0x0d0d0f, roughness: 0.35, clearcoat: 1, clearcoatRoughness: 0.1 });
  const base = new THREE.Mesh(new RoundedBoxGeometry(0.118, 0.006, 0.06, 3, 0.002), black);
  base.position.set(0, 0.003, 0.004); base.castShadow = base.receiveShadow = true;
  group.add(base);
  const holder = new THREE.Group();
  holder.position.set(0, 0.006, -0.012);
  holder.rotation.x = -tilt;
  group.add(holder);
  const backPanel = new THREE.Mesh(new RoundedBoxGeometry(0.114, 0.158, 0.003, 3, 0.0012), black);
  backPanel.position.set(0, 0.079, -0.0022); backPanel.castShadow = true; backPanel.receiveShadow = true;
  holder.add(backPanel);
  const cardMat = new THREE.MeshStandardMaterial({ map: cardTex, roughness: 0.6 });
  const card = new THREE.Mesh(new THREE.PlaneGeometry(CARD.w, CARD.h), cardMat);
  card.position.set(0, 0.0785, 0.0002); card.receiveShadow = true;
  holder.add(card);
  const front = new THREE.Mesh(new RoundedBoxGeometry(0.112, 0.156, 0.002, 3, 0.0009), acrylic);
  front.position.set(0, 0.0785, 0.0016);
  holder.add(front);
  const uvToLocal = (u, v) => new THREE.Vector3((u - 0.5) * CARD.w, (0.5 - v) * CARD.h, 0.0003);
  return { group, card, uvToLocal };
}

/** A small glass of milk tea on a saucer. */
export function makeTea() {
  const group = new THREE.Group();
  const prof = [];
  const H = 0.078, rb = 0.022, rt = 0.029, th = 0.0018;
  prof.push(new THREE.Vector2(0.0001, 0));
  prof.push(new THREE.Vector2(rb - 0.003, 0));
  prof.push(new THREE.Vector2(rb, 0.002));
  for (let i = 0; i <= 12; i++) { const t = i / 12; prof.push(new THREE.Vector2(rb + (rt - rb) * t + Math.sin(t * Math.PI) * 0.0012, 0.002 + t * (H - 0.002))); }
  prof.push(new THREE.Vector2(rt + 0.0004, H + 0.0006));
  prof.push(new THREE.Vector2(rt - th, H));
  for (let i = 12; i >= 0; i--) { const t = i / 12; prof.push(new THREE.Vector2(rb - th + (rt - rb) * t + Math.sin(t * Math.PI) * 0.0012, 0.006 + t * (H - 0.008))); }
  prof.push(new THREE.Vector2(0.0001, 0.006));
  const glass = new THREE.Mesh(new THREE.LatheGeometry(prof, 72), new THREE.MeshPhysicalMaterial({
    color: 0xffffff, roughness: 0.02, transmission: 1, thickness: 0.002, ior: 1.5, specularIntensity: 1, envMapIntensity: 1.4,
  }));
  glass.castShadow = true;
  group.add(glass);
  const liquidH = 0.056;
  const lprof = [new THREE.Vector2(0.0001, 0.0062)];
  for (let i = 0; i <= 8; i++) { const t = i / 8 * (liquidH / (H - 0.008)); lprof.push(new THREE.Vector2(rb - th - 0.0003 + (rt - rb) * t + Math.sin(t * Math.PI) * 0.0012, 0.0062 + t * (H - 0.008))); }
  lprof.push(new THREE.Vector2(0.0001, 0.0062 + liquidH));
  const tea = new THREE.Mesh(new THREE.LatheGeometry(lprof, 72), new THREE.MeshPhysicalMaterial({
    color: 0xc98a4b, roughness: 0.12, transmission: 0.35, thickness: 0.04, attenuationColor: new THREE.Color(0x7a3d12), attenuationDistance: 0.02, ior: 1.34,
  }));
  group.add(tea);
  // saucer
  const sp = [new THREE.Vector2(0.0001, 0), new THREE.Vector2(0.03, 0), new THREE.Vector2(0.036, 0.003), new THREE.Vector2(0.058, 0.0085), new THREE.Vector2(0.06, 0.0105), new THREE.Vector2(0.057, 0.0102), new THREE.Vector2(0.035, 0.0055), new THREE.Vector2(0.0001, 0.0045)];
  const saucer = new THREE.Mesh(new THREE.LatheGeometry(sp, 72), new THREE.MeshPhysicalMaterial({ color: 0xf2efe8, roughness: 0.18, clearcoat: 1, clearcoatRoughness: 0.08 }));
  saucer.castShadow = saucer.receiveShadow = true;
  group.add(saucer);
  glass.position.y = 0.0045; tea.position.y = 0.0045;
  // a spoon resting on the saucer
  const spoon = new THREE.Mesh(new RoundedBoxGeometry(0.075, 0.0012, 0.006, 2, 0.0005), new THREE.MeshStandardMaterial({ color: 0xc9cbd0, metalness: 1, roughness: 0.18 }));
  spoon.position.set(0.035, 0.009, 0.02); spoon.rotation.y = -0.5; spoon.rotation.z = 0.05; spoon.castShadow = true;
  group.add(spoon);
  return group;
}

/** Load a Poly Haven texture set with sensible tiling. */
export function pbr(loader, name, repeat = [1, 1], { colorTint } = {}) {
  const base = `assets/textures/${name}/`;
  const set = (t, srgb) => { t.wrapS = t.wrapT = THREE.RepeatWrapping; t.repeat.set(...repeat); t.anisotropy = 8; if (srgb) t.colorSpace = THREE.SRGBColorSpace; return t; };
  const map = set(loader.load(base + 'diff.jpg'), true);
  const normalMap = set(loader.load(base + 'nor.jpg'));
  const arm = set(loader.load(base + 'arm.jpg'));
  const m = new THREE.MeshStandardMaterial({ map, normalMap, roughnessMap: arm, metalnessMap: arm, aoMap: arm, roughness: 1, metalness: 1 });
  if (colorTint) m.color.set(colorTint);
  return m;
}

/** Colourful packet boxes for the shelves; they read as shop stock once out of focus. */
export function packet(w, h, d, hue, seed) {
  const c = document.createElement('canvas');
  c.width = 128; c.height = 192;
  const ctx = c.getContext('2d');
  const col = `hsl(${hue},${30 + (seed % 30)}%,${26 + (seed % 22)}%)`;
  ctx.fillStyle = col; ctx.fillRect(0, 0, 128, 192);
  ctx.fillStyle = `hsl(${(hue + 40) % 360},45%,${42 + (seed % 20)}%)`; ctx.fillRect(0, 60 + (seed % 40), 128, 30);
  ctx.fillStyle = 'rgba(255,255,255,0.85)'; ctx.fillRect(16, 20, 96, 14 + (seed % 10));
  const tex = new THREE.CanvasTexture(c);
  tex.colorSpace = THREE.SRGBColorSpace;
  const m = new THREE.Mesh(new RoundedBoxGeometry(w, h, d, 2, Math.min(w, d) * 0.08), new THREE.MeshStandardMaterial({ map: tex, roughness: 0.45 }));
  m.castShadow = m.receiveShadow = true;
  return m;
}
