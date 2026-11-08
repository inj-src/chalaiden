// Chalaiden demo film. A deterministic three.js scene: renderAt(t) draws one frame,
// averaging several sub-frames for real motion blur and supersampled edges.
import * as THREE from 'three';
import { GLTFLoader } from 'three/addons/loaders/GLTFLoader.js';
import { RGBELoader } from 'three/addons/loaders/RGBELoader.js';
import { RectAreaLightUniformsLib } from 'three/addons/lights/RectAreaLightUniformsLib.js';
import {
  EffectComposer, RenderPass, EffectPass, DepthOfFieldEffect, BloomEffect, ToneMappingEffect, ToneMappingMode, VignetteEffect,
} from 'postprocessing';
import { N8AOPostPass } from 'n8ao';
import { PhoneUI, SCREEN_W, SCREEN_H, drawUssdCard } from './screens.js';
import { makePhone, makeStand, makeQrStand, makeCardCanvas, makeTea, pbr, packet, PHONE } from './props.js';

const W = 1920, H = 1080, FPS = 30;
export const DURATION = 34.5;
const params = new URLSearchParams(location.search);
const SUB = Number(params.get('sub') ?? 6);

const clamp = (x, a = 0, b = 1) => Math.min(b, Math.max(a, x));
const span = (t, a, b) => clamp((t - a) / (b - a));
const smooth = (x) => { x = clamp(x); return x * x * (3 - 2 * x); };
const inOut = (x) => { x = clamp(x); return x < 0.5 ? 4 * x * x * x : 1 - Math.pow(-2 * x + 2, 3) / 2; };
const sine = (x) => -(Math.cos(Math.PI * clamp(x)) - 1) / 2;
const outExpo = (x) => (x >= 1 ? 1 : 1 - Math.pow(2, -10 * clamp(x)));
const outCubic = (x) => 1 - Math.pow(1 - clamp(x), 3);
const V = (x, y, z) => new THREE.Vector3(x, y, z);

// ---------------------------------------------------------------- renderer
const canvas = document.getElementById('gl');
const renderer = new THREE.WebGLRenderer({ canvas, antialias: false, preserveDrawingBuffer: true, powerPreference: 'high-performance', stencil: false });
renderer.setPixelRatio(1);
renderer.setSize(W, H, false);
renderer.shadowMap.enabled = true;
renderer.shadowMap.type = THREE.PCFSoftShadowMap;
renderer.toneMapping = THREE.NoToneMapping;
renderer.outputColorSpace = THREE.SRGBColorSpace;
RectAreaLightUniformsLib.init();

const scene = new THREE.Scene();
scene.background = new THREE.Color(0x020203);
const camera = new THREE.PerspectiveCamera(30, W / H, 0.01, 60);

await document.fonts.load('400 20px Geist');
await Promise.all(['300', '400', '500', '600', '700'].map((w) => document.fonts.load(`${w} 20px Geist`)));
await Promise.all(['500', '700'].map((w) => document.fonts.load(`${w} 20px GeistMono`)));

// ---------------------------------------------------------------- environment
const pmrem = new THREE.PMREMGenerator(renderer);
const hdr = await new RGBELoader().loadAsync('assets/hdri/warm_restaurant_night.hdr');
const envMap = pmrem.fromEquirectangular(hdr).texture;
scene.environment = envMap;
scene.environmentIntensity = 0.32;

const tl = new THREE.TextureLoader();
const gltf = new GLTFLoader();
const models = {};
await Promise.all([
  'lightbulb_01', 'pull_chain_light_socket', 'potted_plant_04', 'plastic_crate_01', 'long_life_food', 'cardboard_box_01',
  'office_notepads', 'russian_food_cans_01', 'pot_enamel_01', 'vintage_electric_kettle', 'painted_wooden_shelves',
].map(async (id) => { models[id] = (await gltf.loadAsync(`assets/models/${id}/${id}.gltf`)).scene; }));
const shadowAll = (o, cast = true, recv = true) => o.traverse((m) => { if (m.isMesh) { m.castShadow = cast; m.receiveShadow = recv; } });
const model = (id, node) => {
  let src = models[id];
  if (node) src = src.getObjectByName(node);
  const c = src.clone(true);
  if (node) { c.position.set(0, 0, 0); c.rotation.set(0, 0, 0); }
  shadowAll(c);
  return c;
};

// ---------------------------------------------------------------- room
const wood = pbr(tl, 'dark_wood', [3.2, 0.8], { colorTint: 0xb08a6a });
const counterTop = new THREE.Mesh(new THREE.BoxGeometry(3.4, 0.045, 0.78), wood);
counterTop.position.set(0.2, -0.0225, 0); counterTop.receiveShadow = counterTop.castShadow = true;
scene.add(counterTop);
const woodFront = pbr(tl, 'dark_wood', [3.2, 1], { colorTint: 0x5a4433 });
const counterFront = new THREE.Mesh(new THREE.BoxGeometry(3.4, 0.95, 0.04), woodFront);
counterFront.position.set(0.2, -0.52, 0.37); counterFront.receiveShadow = true;
scene.add(counterFront);
const floor = new THREE.Mesh(new THREE.PlaneGeometry(12, 12), new THREE.MeshStandardMaterial({ color: 0x1a1714, roughness: 0.8 }));
floor.rotation.x = -Math.PI / 2; floor.position.y = -0.98; floor.receiveShadow = true;
scene.add(floor);

const plaster = pbr(tl, 'plastered_wall_04', [3, 1.6], { colorTint: 0x6f9a8e });
const WALL_Z = -1.15;
const wallPiece = (x0, x1, y0, y1) => {
  const g = new THREE.PlaneGeometry(x1 - x0, y1 - y0);
  const uv = g.attributes.uv;
  for (let i = 0; i < uv.count; i++) uv.setXY(i, (x0 + uv.getX(i) * (x1 - x0)) / 2, (y0 + uv.getY(i) * (y1 - y0)) / 2);
  const m = new THREE.Mesh(g, plaster);
  m.position.set((x0 + x1) / 2, (y0 + y1) / 2, WALL_Z); m.receiveShadow = true;
  scene.add(m);
};
// doorway to the lane at x ∈ [-2.3, -0.95]
wallPiece(-0.95, 3.5, -1, 2.6); wallPiece(-4, -2.3, -1, 2.6); wallPiece(-2.3, -0.95, 1.95, 2.6);
const ceiling = new THREE.Mesh(new THREE.PlaneGeometry(10, 6), new THREE.MeshStandardMaterial({ color: 0x15130f, roughness: 1 }));
ceiling.rotation.x = Math.PI / 2; ceiling.position.set(0, 2.6, 0); scene.add(ceiling);

// shelves of stock on the back wall
const shelfMat = pbr(tl, 'dark_wood', [2, 0.2], { colorTint: 0x8a6a4c });
let seed = 11;
const rnd = () => ((seed = (seed * 16807) % 2147483647) / 2147483647);
for (const y of [0.32, 0.72, 1.12, 1.52]) {
  const plank = new THREE.Mesh(new THREE.BoxGeometry(2.6, 0.025, 0.3), shelfMat);
  plank.position.set(0.55, y, WALL_Z + 0.16); plank.castShadow = plank.receiveShadow = true;
  scene.add(plank);
  let x = -0.68;
  while (x < 1.8) {
    const k = rnd();
    let o, wdt;
    if (k < 0.5) {
      const w = 0.06 + rnd() * 0.07, h = 0.1 + rnd() * 0.17, d = 0.04 + rnd() * 0.06;
      o = packet(w, h, d, Math.floor(rnd() * 360), Math.floor(rnd() * 97));
      o.position.set(x + w / 2, y + 0.0125 + h / 2, WALL_Z + 0.1 + rnd() * 0.08);
      o.rotation.y = (rnd() - 0.5) * 0.25; wdt = w;
    } else if (k < 0.7) {
      const names = ['long_life_food_milk', 'long_life_food_tomatoes', 'long_life_food_beans', 'long_life_food_sardines'];
      o = model('long_life_food', names[Math.floor(rnd() * 4)]);
      o.position.set(x + 0.05, y + 0.0125, WALL_Z + 0.15); o.rotation.y = (rnd() - 0.5) * 0.6; wdt = 0.1;
    } else if (k < 0.85) {
      const names = ['russian_food_cans_01_salt_box', 'russian_food_cans_01_can_cond', 'russian_food_cans_01_can_fish'];
      o = model('russian_food_cans_01', names[Math.floor(rnd() * 3)]);
      o.position.set(x + 0.05, y + 0.0125, WALL_Z + 0.15); o.rotation.y = rnd() * 6; wdt = 0.09;
    } else {
      o = model('pot_enamel_01'); o.scale.setScalar(0.6);
      o.position.set(x + 0.09, y + 0.0125, WALL_Z + 0.15); wdt = 0.18;
    }
    scene.add(o);
    x += wdt + 0.012 + rnd() * 0.03;
  }
}
{
  const box = model('cardboard_box_01'); box.position.set(1.6, 0, -0.75); box.rotation.y = 0.4; box.scale.setScalar(0.8); scene.add(box);
  const crate = model('plastic_crate_01'); crate.position.set(-0.45, -0.98, WALL_Z + 0.3); scene.add(crate);
}

// the lane outside the doorway: a far wall and city lights for bokeh
{
  const iron = pbr(tl, 'corrugated_iron_02', [4, 2], { colorTint: 0x50606a });
  const lane = new THREE.Mesh(new THREE.PlaneGeometry(10, 5), iron);
  lane.position.set(-2.5, 1, -5.5); lane.receiveShadow = true; scene.add(lane);
  const laneLight = new THREE.PointLight(0xff9a45, 2.5, 0, 2); laneLight.position.set(-2.2, 2.2, -4.2); scene.add(laneLight);
  const dot = new THREE.SphereGeometry(1, 12, 8);
  const cols = [0xffb066, 0xffd9a0, 0xfff0d8, 0x9ccaff, 0xff7a55, 0xffc27a];
  for (let i = 0; i < 44; i++) {
    const c = new THREE.Color(cols[i % cols.length]).multiplyScalar(4 + rnd() * 10);
    const m = new THREE.Mesh(dot, new THREE.MeshBasicMaterial({ color: c, toneMapped: false }));
    m.scale.setScalar(0.03 + rnd() * 0.06);
    m.position.set(-3.9 + rnd() * 3.4, -0.4 + rnd() * 2.6, -4.5 - rnd() * 1.0 + 0.0);
    if (i % 3 === 0) m.position.z = -5.3;
    scene.add(m);
  }
}

// ---------------------------------------------------------------- the bulb
const bulbRig = new THREE.Group();
bulbRig.position.set(-0.02, 0.6, -0.16);
scene.add(bulbRig);
{
  const socket = model('pull_chain_light_socket');
  socket.position.y = 0.05; bulbRig.add(socket);
  const bulb = model('lightbulb_01');
  bulb.rotation.x = Math.PI; bulb.position.y = 0.06;
  bulb.traverse((m) => {
    if (!m.isMesh) return;
    m.castShadow = false;
    const n = (m.material.name + m.name).toLowerCase();
    if (n.includes('glass') || n.includes('bulb')) {
      m.material = m.material.clone();
      m.material.emissive = new THREE.Color(0xffb46b);
      m.material.emissiveIntensity = 6;
      m.material.transparent = false;
    }
    if (n.includes('filament') || n.includes('wire')) {
      m.material = new THREE.MeshBasicMaterial({ color: new THREE.Color(0xffc080).multiplyScalar(40), toneMapped: false });
    }
  });
  bulbRig.add(bulb);
  socket.traverse((m) => { if (m.isMesh) m.castShadow = false; });
  const cord = new THREE.Mesh(new THREE.CylinderGeometry(0.0028, 0.0028, 2.2, 8), new THREE.MeshStandardMaterial({ color: 0x111111, roughness: 0.6 }));
  cord.position.y = 0.05 + 0.165 + 1.1; bulbRig.add(cord);
  // glow core for bloom
  const core = new THREE.Mesh(new THREE.SphereGeometry(0.018, 24, 16), new THREE.MeshBasicMaterial({ color: new THREE.Color(0xffc48a).multiplyScalar(30), toneMapped: false }));
  core.position.y = 0.0; bulbRig.add(core);
}
const bulbLight = new THREE.PointLight(0xffc088, 0.95, 0, 2);
bulbLight.position.set(0, -0.005, 0);
bulbLight.castShadow = true;
bulbLight.shadow.mapSize.set(2048, 2048);
bulbLight.shadow.bias = -0.0004;
bulbLight.shadow.normalBias = 0.006;
bulbLight.shadow.radius = 4;
bulbLight.shadow.camera.near = 0.03;
bulbRig.add(bulbLight);

// street light through the shutter slats: a spotlight with a striped cookie
const slats = document.createElement('canvas');
slats.width = 512; slats.height = 512;
{
  const g = slats.getContext('2d');
  g.fillStyle = '#000'; g.fillRect(0, 0, 512, 512);
  const grd = g.createRadialGradient(256, 256, 40, 256, 256, 256);
  grd.addColorStop(0, '#fff'); grd.addColorStop(1, '#000');
  for (let i = 0; i < 30; i++) {
    const y = 6 + i * 17;
    g.fillStyle = grd; g.fillRect(0, y, 512, 10);
  }
  const tmp = document.createElement('canvas'); tmp.width = tmp.height = 512;
  const tg = tmp.getContext('2d'); tg.filter = 'blur(1.5px)'; tg.drawImage(slats, 0, 0);
  g.clearRect(0, 0, 512, 512); g.drawImage(tmp, 0, 0);
}
const slatTex = new THREE.CanvasTexture(slats);
slatTex.colorSpace = THREE.SRGBColorSpace;
const street = new THREE.SpotLight(0x9db8ff, 190, 0, 0.36, 0.25, 2);
street.position.set(1.3, 2.35, 2.3);
street.target.position.set(-0.25, 0.0, -0.55);
street.map = slatTex;
street.castShadow = true;
street.shadow.mapSize.set(2048, 2048);
street.shadow.bias = -0.0002;
street.shadow.normalBias = 0.004;
scene.add(street, street.target);

const rim = new THREE.DirectionalLight(0x7f9bff, 0.25);
rim.position.set(-2, 1.4, -1.5);
scene.add(rim);

// ---------------------------------------------------------------- counter props
const cardInfo = makeCardCanvas();
const cardTex = new THREE.CanvasTexture(cardInfo.canvas);
cardTex.colorSpace = THREE.SRGBColorSpace; cardTex.anisotropy = 16;
const qr = makeQrStand(cardTex);
qr.group.position.set(-0.2, 0, -0.06);
qr.group.rotation.y = 0.26;
scene.add(qr.group);

const stand = makeStand();
stand.group.position.set(0.17, 0, 0.0);
stand.group.rotation.y = 0.1;
scene.add(stand.group);

const tea = makeTea();
tea.position.set(0.43, 0, 0.15);
scene.add(tea);

{
  const pad = model('office_notepads', 'office_notepads_yellow_pad');
  pad.position.set(-0.55, 0, 0.13); pad.rotation.y = 0.35; scene.add(pad);
  const notes = model('office_notepads', 'office_notepads_note_stack');
  notes.position.set(-0.62, 0, -0.08); notes.rotation.y = -0.2; scene.add(notes);
  const plant = model('potted_plant_04'); plant.position.set(-0.86, 0, -0.2); plant.scale.setScalar(1.1); scene.add(plant);
  const kettle = model('vintage_electric_kettle'); kettle.position.set(0.86, 0, -0.17); kettle.rotation.y = -0.9; scene.add(kettle);
  const cans = model('russian_food_cans_01', 'russian_food_cans_01_can_cond'); cans.position.set(0.64, 0, -0.24); scene.add(cans);
}

// reflections: capture the shop itself so glossy surfaces mirror the bulb and shelves, not a stock HDRI
{
  const cubeRT = new THREE.WebGLCubeRenderTarget(512, { type: THREE.HalfFloatType });
  const cubeCam = new THREE.CubeCamera(0.02, 30, cubeRT);
  cubeCam.position.set(0.1, 0.22, 0.45);
  scene.add(cubeCam);
  scene.updateMatrixWorld(true);
  cubeCam.update(renderer, scene);
  scene.environment = pmrem.fromCubemap(cubeRT.texture).texture;
  scene.environmentIntensity = 0.9;
  scene.remove(cubeCam);
}

// ---------------------------------------------------------------- the phone
const ui = new PhoneUI();
const uiTex = new THREE.CanvasTexture(ui.canvas);
uiTex.colorSpace = THREE.SRGBColorSpace; uiTex.anisotropy = 16;
const phone = makePhone(uiTex, envMap);
phone.group.matrixAutoUpdate = false;
scene.add(phone.group);
phone.uiMat.color.setScalar(1.1);

const screenLight = new THREE.RectAreaLight(0xbcd0ff, 0.0, PHONE.screenW, PHONE.screenH);
screenLight.position.z = PHONE.d / 2 + 0.001;
screenLight.rotation.y = 0; // RectAreaLight shines along its -z; flip to face out of the screen
screenLight.lookAt(0, 0, 1);
phone.group.add(screenLight);

const vfRT = new THREE.WebGLRenderTarget(720, 1584, { type: THREE.HalfFloatType, samples: 4 });
phone.vfMat.map = vfRT.texture;
phone.vfMat.color.setScalar(1.1);
const phoneCam = new THREE.PerspectiveCamera(56, SCREEN_W / SCREEN_H, 0.01, 30);
phoneCam.position.set(0.0165, 0.0545, -0.006);
phone.group.add(phoneCam);

// ---------------------------------------------------------------- USSD ghosts (the "under the hood" beat)
// Prompts as bKash sends them (menu from UssdEngineTest; payment wording mapped on a Grameenphone SIM).
const USSD = [
  { prompt: 'bKash\n1 Send Money\n2 Send Money to Non-bKash User\n3 Mobile Recharge\n4 Payment\n5 Cash Out\n6 Pay Bill', reply: '4' },
  { prompt: 'Enter Merchant bKash\nAccount No:', reply: '01712345678' },
  { prompt: 'Enter Amount:', reply: '250' },
  { prompt: 'Enter Reference:', reply: 'ZQX7K' },
  { prompt: 'Payment\nTo: 01712345678\nAmount: Tk 250\nFee: Tk 0.00\nReference: ZQX7K\nEnter Menu PIN to confirm:', reply: '•••••' },
];
const RUN_T0 = 22.1;
const USSD_AT = [0.1, 0.85, 1.4, 1.95, 2.45];
const ghosts = USSD.map((u, i) => {
  const tex = new THREE.CanvasTexture(drawUssdCard(u.prompt, ''));
  tex.colorSpace = THREE.SRGBColorSpace; tex.anisotropy = 8;
  const mat = new THREE.MeshBasicMaterial({ map: tex, transparent: true, toneMapped: false, depthWrite: true, side: THREE.DoubleSide });
  mat.color.setScalar(1.15);
  const mesh = new THREE.Mesh(new THREE.PlaneGeometry(0.115, 0.115 * 470 / 620), mat);
  mesh.matrixAutoUpdate = false;
  mesh.visible = false;
  mesh.renderOrder = 4;
  scene.add(mesh);
  return { mesh, tex, typed: -1, ...u };
});

// ---------------------------------------------------------------- poses
scene.updateMatrixWorld(true);
const standPhone = new THREE.Matrix4().copy(stand.mount.matrixWorld).multiply(new THREE.Matrix4().makeTranslation(0, PHONE.h / 2, PHONE.d / 2 + 0.0005));
const basis = (m) => {
  const p = new THREE.Vector3(), q = new THREE.Quaternion(), s = new THREE.Vector3();
  m.decompose(p, q, s);
  return { P: p, R: V(1, 0, 0).applyQuaternion(q), U: V(0, 1, 0).applyQuaternion(q), N: V(0, 0, 1).applyQuaternion(q), q };
};
const SP = basis(standPhone);
const cardWorld = (u, v) => qr.uvToLocal(u, v).applyMatrix4(qr.card.matrixWorld);
const NR = cardInfo.numRect, QR = cardInfo.qrRect;
const numberCenter = cardWorld((NR.x0 + NR.x1) / 2, (NR.y0 + NR.y1) / 2);
const qrCenter = cardWorld((QR.x0 + QR.x1) / 2, (QR.y0 + QR.y1) / 2);
const cardN = V(0, 0, 1).transformDirection(qr.card.matrixWorld);

function scanPose(t) {
  // Phone held ~19 cm off the card, its back camera aimed a little above the number.
  const n = cardN.clone();
  const aim = numberCenter.clone().add(V(0, 0.034, 0));
  const camPos = aim.clone().addScaledVector(n, 0.27 + 0.012 * Math.sin(t * 0.9));
  const zAxis = n.clone(); // screen faces back toward us
  const up0 = V(0, 1, 0);
  const xAxis = up0.clone().cross(zAxis).normalize();
  const yAxis = zAxis.clone().cross(xAxis).normalize();
  const m = new THREE.Matrix4().makeBasis(xAxis, yAxis, zAxis);
  // hand wobble
  const wob = new THREE.Euler(
    0.012 * Math.sin(t * 1.7) + 0.006 * Math.sin(t * 4.1),
    0.014 * Math.sin(t * 1.3 + 1) + 0.005 * Math.sin(t * 3.7),
    0.02 * Math.sin(t * 0.8) - 0.03,
  );
  m.multiply(new THREE.Matrix4().makeRotationFromEuler(wob));
  // place so the lens (not the phone centre) sits at camPos
  const lens = phoneCam.position.clone().applyMatrix4(new THREE.Matrix4().extractRotation(m));
  m.setPosition(camPos.clone().sub(lens));
  return m;
}

function phoneMatrix(t) {
  if (t >= 8.2 && t < 12.8) return scanPose(t);
  return standPhone;
}

// ---------------------------------------------------------------- screen schedule
const SCHED = [
  { s: 'home', at: 0 },
  { s: 'scan', at: 8.2, mix: 0.01, args: { shutterAt: 2.75 } },
  { s: 'number', at: 12.0, mix: 0.45, args: { continueAt: 3.0 } },
  { s: 'amount', at: 15.4, mix: 0.45, args: { keys: [{ key: '2', at: 0.55 }, { key: '5', at: 0.92 }, { key: '0', at: 1.28 }], payAt: 2.35 } },
  { s: 'pin', at: 18.2, mix: 0.45, args: { keys: [{ key: '3', at: 0.5 }, { key: '8', at: 0.76 }, { key: '1', at: 1.02 }, { key: '6', at: 1.28 }, { key: '0', at: 1.54 }], holdAt: 2.0, holdFor: 1.45 } },
  { s: 'run', at: RUN_T0, mix: 0.4, args: { steps: [0.35, 0.85, 1.4, 1.95, 2.45, 3.0, 3.5], confirmAt: 3.55 } },
  { s: 'result', at: 26.4, mix: 0.45 },
];

// a photo-like crop of the number, for the number screen
const crop = document.createElement('canvas');
crop.width = 960; crop.height = 164;
{
  const g = crop.getContext('2d');
  const cw = cardInfo.canvas.width, ch = cardInfo.canvas.height;
  const cx = (NR.x0 + NR.x1) / 2 * cw, cy = (NR.y0 + NR.y1) / 2 * ch;
  const sw = (NR.x1 - NR.x0) * cw * 1.12, sh = sw * 164 / 960;
  g.filter = 'blur(1.1px) contrast(0.92) brightness(0.96)';
  g.drawImage(cardInfo.canvas, cx - sw / 2, cy - sh / 2, sw, sh, 0, 0, 960, 164);
  g.filter = 'none';
  const warm = g.createLinearGradient(0, 0, 960, 164);
  warm.addColorStop(0, 'rgba(255,170,90,0.16)'); warm.addColorStop(1, 'rgba(70,90,160,0.10)');
  g.fillStyle = warm; g.fillRect(0, 0, 960, 164);
  const img = g.getImageData(0, 0, 960, 164);
  let s = 5;
  for (let i = 0; i < img.data.length; i += 4) { s = (s * 16807) % 2147483647; const n = (s / 2147483647 - 0.5) * 16; img.data[i] += n; img.data[i + 1] += n; img.data[i + 2] += n; }
  g.putImageData(img, 0, 0);
}

const tmpV = new THREE.Vector3();
function scanBox(t) {
  // project the number's corners through the phone camera into screen px
  const pts = [[NR.x0, NR.y0], [NR.x1, NR.y0], [NR.x0, NR.y1], [NR.x1, NR.y1]].map(([u, v]) => {
    tmpV.copy(cardWorld(u, v)).project(phoneCam);
    return [(tmpV.x * 0.5 + 0.5) * SCREEN_W, (0.5 - tmpV.y * 0.5) * SCREEN_H];
  });
  const xs = pts.map((p) => p[0]), ys = pts.map((p) => p[1]);
  const lockBox = { x0: Math.min(...xs) - 16, x1: Math.max(...xs) + 16, y0: Math.min(...ys) - 14, y1: Math.max(...ys) + 14 };
  const L = t - 8.2;
  const lock = smooth(span(L, 1.25, 1.65));
  const cx = 270 + 70 * Math.sin(L * 2.3), cy = 560 + 90 * Math.sin(L * 1.7 + 1);
  const roam = { x0: cx - 150, x1: cx + 150, y0: cy - 60, y1: cy + 60 };
  const k = outExpo(span(L, 1.0, 1.5));
  const mix = (a, b) => a + (b - a) * k;
  return { box: { x0: mix(roam.x0, lockBox.x0), x1: mix(roam.x1, lockBox.x1), y0: mix(roam.y0, lockBox.y0), y1: mix(roam.y1, lockBox.y1) }, lock, lockBox };
}

function screenState(t) {
  let i = SCHED.length - 1;
  while (i > 0 && SCHED[i].at > t) i--;
  const cur = SCHED[i], prev = SCHED[i - 1];
  const L = t - cur.at;
  const st = { screen: cur.s, L, args: { ...(cur.args ?? {}) } };
  if (prev && cur.mix && L < cur.mix) {
    st.prev = prev.s; st.prevL = t - prev.at; st.prevArgs = { ...(prev.args ?? {}) }; st.mix = L / cur.mix;
  }
  for (const a of [st.args, st.prevArgs]) if (a) a.crop = crop;
  if (st.screen === 'scan' || st.prev === 'scan') {
    const sb = scanBox(t);
    const tgt = st.screen === 'scan' ? st.args : st.prevArgs;
    tgt.box = sb.box; tgt.lock = sb.lock;
  }
  return st;
}

// ---------------------------------------------------------------- shots
function lerpV(a, b, k) { return a.clone().lerp(b, k); }
const P = SP.P, N = SP.N, R = SP.R, U = SP.U;
const at = (base, n, r, u) => base.clone().addScaledVector(N, n).addScaledVector(R, r).addScaledVector(U, u);
const SHOTS = [
  { t0: 0, t1: 4.2, fov: 30,
    pos: (k) => lerpV(V(0.62, 0.22, 0.95), V(0.5, 0.19, 0.8), sine(k)),
    tgt: (k) => lerpV(V(-0.12, 0.17, -0.2), V(-0.1, 0.16, -0.18), sine(k)),
    focus: () => P, range: 0.45 },
  { t0: 4.2, t1: 8.2, fov: 26,
    pos: (k) => lerpV(V(0.16, 0.17, 0.36), V(0.06, 0.135, 0.29), sine(k)),
    tgt: (k) => lerpV(V(-0.27, 0.085, -0.08), V(-0.29, 0.075, -0.08), sine(k)),
    focus: (k, t) => (t < 6.1 ? qrCenter : numberCenter), range: 0.14 },
  { t0: 8.2, t1: 12.8, fov: 34, handheld: true,
    pos: (k, t) => {
      const b = basis(scanPose(t));
      return b.P.clone().addScaledVector(b.N, 0.235).addScaledVector(b.R, 0.07).addScaledVector(b.U, 0.055);
    },
    tgt: (k, t) => {
      const b = basis(scanPose(t));
      return b.P.clone().addScaledVector(b.N, -0.05).addScaledVector(b.R, -0.038).addScaledVector(b.U, -0.004);
    },
    whip: 0.42, focus: (k, t) => basis(scanPose(t)).P, range: 0.12 },
  { t0: 12.8, t1: 17.6, fov: 30,
    pos: (k) => lerpV(at(P, 0.4, 0.06, 0.035), at(P, 0.36, 0.02, 0.05), sine(k)),
    tgt: (k) => lerpV(at(P, 0, -0.075, 0.004), at(P, 0, -0.07, 0.006), sine(k)),
    focus: () => P, range: 0.22 },
  { t0: 17.6, t1: 22.1, fov: 30,
    pos: (k) => lerpV(at(P, 0.34, -0.02, 0.1), at(P, 0.31, 0.03, 0.075), sine(k)),
    tgt: (k) => lerpV(at(P, 0, -0.07, -0.012), at(P, 0, -0.066, -0.008), sine(k)),
    focus: () => at(P, 0, 0, -0.02), range: 0.22 },
  { t0: 22.1, t1: 26.4, fov: 32,
    pos: (k) => lerpV(at(P, 0.42, 0.06, 0.05), at(P, 0.39, -0.02, 0.045), sine(k)),
    tgt: (k) => lerpV(at(P, 0, -0.14, 0.012), at(P, 0, -0.15, 0.012), sine(k)),
    focus: (k) => at(P, 0, -0.07, 0.01), range: 0.35 },
  { t0: 26.4, t1: 31.0, fov: 30,
    pos: (k) => lerpV(at(P, 0.33, 0.05, 0.04), V(0.62, 0.24, 0.98), inOut(span(k, 0.4, 1))),
    tgt: (k) => lerpV(at(P, 0, -0.075, 0.004), V(-0.12, 0.17, -0.2), inOut(span(k, 0.4, 1))),
    focus: () => P, range: (k) => 0.22 + 0.25 * inOut(span(k, 0.4, 1)) },
  { t0: 31.0, t1: DURATION + 1, fov: 30,
    pos: (k) => lerpV(V(0.62, 0.24, 0.98), V(0.68, 0.26, 1.06), sine(k)),
    tgt: () => V(-0.12, 0.17, -0.2),
    focus: () => V(0.5, 0.3, 1.0), range: 0.05 },
];
const shotAt = (t) => SHOTS.find((s) => t >= s.t0 && t < s.t1) ?? (t < SHOTS[0].t0 ? SHOTS[0] : SHOTS[SHOTS.length - 1]);

function placeCamera(t) {
  const s = shotAt(t);
  const k = (t - s.t0) / (s.t1 - s.t0);
  const pos = s.pos(k, t), tgt = s.tgt(k, t);
  camera.fov = s.fov;
  camera.position.copy(pos);
  camera.up.set(0, 1, 0);
  camera.lookAt(tgt);
  if (s.whip) {
    // whip in: start swung far to the right and snap onto the subject
    const w = 1 - outExpo(span(t - s.t0, 0, s.whip));
    camera.rotateY(w * 0.85);
    camera.rotateX(-w * 0.12);
  }
  if (s.handheld) {
    camera.rotateX(0.004 * Math.sin(t * 2.1) + 0.002 * Math.sin(t * 5.3));
    camera.rotateY(0.005 * Math.sin(t * 1.6 + 2));
    camera.rotateZ(0.006 * Math.sin(t * 1.1));
  }
  camera.updateProjectionMatrix();
  camera.updateMatrixWorld();
  const range = typeof s.range === 'function' ? s.range(k) : s.range;
  return { focus: s.focus(k, t), range };
}

// ---------------------------------------------------------------- post
const composer = new EffectComposer(renderer, { frameBufferType: THREE.HalfFloatType });
composer.addPass(new RenderPass(scene, camera));
const n8ao = new N8AOPostPass(scene, camera, W, H);
n8ao.configuration.aoRadius = 0.12;
n8ao.configuration.distanceFalloff = 0.6;
n8ao.configuration.intensity = 2.2;
n8ao.configuration.gammaCorrection = false;
n8ao.setQualityMode('High');
composer.addPass(n8ao);
const dof = new DepthOfFieldEffect(camera, { worldFocusDistance: 1, worldFocusRange: 0.1, bokehScale: 4.5, resolutionScale: 1 });
composer.addPass(new EffectPass(camera, dof));
const bloom = new BloomEffect({ mipmapBlur: true, luminanceThreshold: 1.15, luminanceSmoothing: 0.4, intensity: 0.6, radius: 0.8 });
const tone = new ToneMappingEffect({ mode: ToneMappingMode.AGX });
const vignette = new VignetteEffect({ offset: 0.32, darkness: 0.62 });
composer.addPass(new EffectPass(camera, bloom, tone, vignette));

// accumulation: copy each finished sub-frame and average
const fbTex = new THREE.FramebufferTexture(W, H);
const accRT = new THREE.WebGLRenderTarget(W, H, { type: THREE.FloatType, depthBuffer: false });
const quadCam = new THREE.OrthographicCamera(-1, 1, 1, -1, 0, 1);
const quadGeo = new THREE.PlaneGeometry(2, 2);
const accMat = new THREE.ShaderMaterial({
  uniforms: { src: { value: fbTex }, w: { value: 1 } },
  vertexShader: 'varying vec2 vUv; void main(){ vUv = uv; gl_Position = vec4(position.xy, 0., 1.); }',
  fragmentShader: 'uniform sampler2D src; uniform float w; varying vec2 vUv; void main(){ gl_FragColor = vec4(texture2D(src, vUv).rgb * w, 1.); }',
  blending: THREE.AdditiveBlending, depthTest: false, depthWrite: false, transparent: true,
});
const accScene = new THREE.Scene(); accScene.add(new THREE.Mesh(quadGeo, accMat));
const outMat = new THREE.ShaderMaterial({
  uniforms: { src: { value: accRT.texture }, seed: { value: 0 }, grain: { value: 0.045 }, fade: { value: 1 } },
  vertexShader: 'varying vec2 vUv; void main(){ vUv = uv; gl_Position = vec4(position.xy, 0., 1.); }',
  fragmentShader: `
    uniform sampler2D src; uniform float seed, grain, fade; varying vec2 vUv;
    float h(vec2 p){ return fract(sin(dot(p, vec2(12.9898, 78.233)) + seed) * 43758.5453); }
    void main(){
      vec2 d = vUv - 0.5;
      float ca = 0.0011 * dot(d, d) * 4.0;
      vec3 c;
      c.r = texture2D(src, vUv - d * ca).r;
      c.g = texture2D(src, vUv).g;
      c.b = texture2D(src, vUv + d * ca).b;
      float n = (h(gl_FragCoord.xy) + h(gl_FragCoord.xy + 17.0) - 1.0);
      float lum = dot(c, vec3(0.299, 0.587, 0.114));
      c = mix(vec3(lum), c, 0.88);
      c = c * vec3(0.98, 1.0, 1.03) + vec3(0.004, 0.008, 0.012);
      c += n * grain * (0.35 + 0.65 * (1.0 - lum));
      gl_FragColor = vec4(c * fade, 1.0);
    }`,
  depthTest: false, depthWrite: false,
});
const outScene = new THREE.Scene(); outScene.add(new THREE.Mesh(quadGeo, outMat));

// ---------------------------------------------------------------- captions (DOM)
const overlay = document.getElementById('overlay');
const svg = document.getElementById('leaders');
const NS = 'http://www.w3.org/2000/svg';
const phoneUI = (x, y) => V((x / SCREEN_W - 0.5) * PHONE.screenW, (0.5 - y / SCREEN_H) * PHONE.screenH, PHONE.d / 2 + 0.0002).applyMatrix4(phone.group.matrixWorld);
const toPx = (v) => { const p = v.clone().project(camera); return [(p.x * 0.5 + 0.5) * W, (0.5 - p.y * 0.5) * H, p.z]; };

const L1 = 64, X0 = 100;
const CAPS = [
  { at: 0.3, out: 3.95, y: 92, size: 24, text: '9:12 pm  ·  Mirpur, Dhaka', color: 'rgba(255,255,255,0.6)' },
  { at: 0.55, out: 3.95, y: 128, size: L1, text: 'A corner shop.', dim: 2.0 },
  { at: 2.0, out: 3.95, y: 128 + 78, size: L1, text: 'No internet.' },

  { at: 4.55, out: 7.95, y: 120, size: L1, text: 'The QR needs data.', dim: 6.05, leader: { at: 4.9, to: () => qrCenter } },
  { at: 6.05, out: 7.95, y: 120 + 78, size: L1, text: "The number doesn't.", leader: { at: 6.4, to: () => numberCenter } },

  { at: 8.75, out: 12.55, y: 120, size: L1, text: 'Chalaiden reads it.', dim: 10.4 },
  { at: 10.4, out: 12.55, y: 120 + 78, size: L1, text: 'On the phone. Offline.', leader: { at: 10.7, to: (t) => { const s = scanBox(t).lockBox; return phoneUI((s.x0 + s.x1) / 2, s.y0 - 4); } } },

  { at: 13.1, out: 15.35, y: 120, size: L1, text: 'Straightened photo.', dim: 14.05, leader: { at: 13.4, to: () => phoneUI(40, 238) } },
  { at: 14.05, out: 15.35, y: 120 + 78, size: L1, text: 'Grouped like the card.', leader: { at: 14.35, to: () => phoneUI(52, 396) } },
  { at: 15.7, out: 17.45, y: 120, size: L1, text: 'Type the amount.', leader: { at: 16.0, to: () => phoneUI(150, 440) } },

  { at: 18.4, out: 21.95, y: 120, size: L1, text: 'Your PIN.', dim: 20.05, leader: { at: 18.7, to: () => phoneUI(160, 511) } },
  { at: 20.05, out: 21.95, y: 120 + 78, size: L1, text: 'Hold to pay.', leader: { at: 20.35, to: () => phoneUI(60, 1012) } },

  { at: 22.4, out: 26.2, y: 92, size: 24, text: 'Under the hood', color: 'rgba(255,255,255,0.6)' },
  { at: 22.6, out: 26.2, y: 128, size: L1, text: 'It answers *247#', dim: 24.2 },
  { at: 23.25, out: 26.2, y: 128 + 78, size: L1, text: 'for you, behind its own screen.', size2: 1 },

  { at: 26.9, out: 30.75, y: 92, size: 24, html: 'QR app: needs data  <span style="opacity:.55">→</span>  <span style="color:#7fa8ff">Chalaiden: *247#</span>', color: 'rgba(255,255,255,0.6)' },
  { at: 27.25, out: 30.75, y: 112, size: 150, text: '0 MB', weight: 400, spacing: -0.04 },
  { at: 28.0, out: 30.75, y: 280, size: 54, text: 'of mobile data used.' },
];
for (const c of CAPS) {
  const el = document.createElement('div');
  el.className = 'line';
  el.style.fontSize = `${c.size}px`;
  el.style.fontWeight = c.weight ?? 400;
  el.style.color = c.color ?? '#fff';
  if (c.spacing) el.style.letterSpacing = `${c.spacing}em`;
  if (c.html) { const w = document.createElement('span'); w.className = 'word'; w.innerHTML = c.html; el.appendChild(w); }
  else c.text.split(' ').forEach((word, i, arr) => {
    const w = document.createElement('span'); w.className = 'word'; w.textContent = word + (i < arr.length - 1 ? ' ' : ''); el.appendChild(w);
  });
  el.style.transform = `translate(${X0}px, ${c.y}px)`;
  overlay.appendChild(el);
  c.el = el; c.words = [...el.children];
  if (c.leader) {
    const path = document.createElementNS(NS, 'path');
    path.setAttribute('fill', 'none'); path.setAttribute('stroke', 'rgba(255,255,255,0.85)'); path.setAttribute('stroke-width', '1.6');
    path.setAttribute('pathLength', '1');
    const dot = document.createElementNS(NS, 'circle'); dot.setAttribute('r', '4.5'); dot.setAttribute('fill', '#fff');
    const ring = document.createElementNS(NS, 'circle'); ring.setAttribute('fill', 'none'); ring.setAttribute('stroke', 'rgba(255,255,255,0.7)'); ring.setAttribute('stroke-width', '1.4');
    svg.append(path, dot, ring);
    c.leader.path = path; c.leader.dot = dot; c.leader.ring = ring;
  }
}
// end lockup
const lockup = document.createElement('div');
lockup.style.cssText = 'position:absolute;left:0;top:0;width:1920px;height:1080px;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:26px;';
lockup.innerHTML = `
  <div style="display:flex;align-items:center;gap:30px">
    <svg viewBox="0 0 42 36" width="150" height="129" style="position:static;overflow:visible">
      <path d="M28.425,9.063 A13.359,13.359 0 1,0 28.425,26.937" stroke="#fff" stroke-width="7.875" stroke-linecap="round" fill="none"/>
      <circle cx="36.356" cy="18" r="4.5" fill="#3D7EFF"/>
    </svg>
    <div style="font: 700 150px Geist; letter-spacing:-0.035em; color:#fff; line-height:1">chalaiden</div>
  </div>
  <div class="tag" style="font: 400 40px Geist; color: rgba(255,255,255,.72); letter-spacing:-0.01em">Pay any bKash shop with no internet.</div>`;
overlay.appendChild(lockup);
const lockMark = lockup.children[0], lockTag = lockup.children[1];

function updateOverlay(t) {
  for (const c of CAPS) {
    const vis = t >= c.at - 0.01 && t < c.out + 0.5;
    c.el.style.display = vis ? '' : 'none';
    if (c.leader) for (const k of ['path', 'dot', 'ring']) c.leader[k].style.display = vis ? '' : 'none';
    if (!vis) continue;
    const outK = span(t, c.out, c.out + 0.4);
    const dimK = c.dim != null ? smooth(span(t, c.dim, c.dim + 0.5)) : 0;
    const base = (1 - outK) * (1 - 0.58 * dimK);
    c.words.forEach((w, i) => {
      const p = span(t, c.at + i * 0.07, c.at + i * 0.07 + 0.6);
      const e = outCubic(p);
      w.style.opacity = (e * base).toFixed(3);
      w.style.filter = `blur(${((1 - e) * 16 + outK * 10).toFixed(2)}px)`;
      w.style.transform = `translateY(${((1 - e) * 14).toFixed(1)}px)`;
    });
    if (c.leader) {
      const L = c.leader;
      const p = outCubic(span(t, L.at, L.at + 0.7));
      const [tx, ty] = toPx(L.to(t));
      const rect = c.el.getBoundingClientRect();
      const sx = rect.right + 26, sy = rect.top + rect.height * 0.56;
      const ex = tx - Math.sign(tx - sx) * 28;
      const d = `M ${sx} ${sy} L ${Math.max(sx, ex - Math.abs(ty - sy) * 0.35)} ${sy} L ${tx} ${ty}`;
      L.path.setAttribute('d', d);
      L.path.setAttribute('stroke-dasharray', `${p} 1`);
      const o = (1 - outK) * (1 - 0.58 * dimK);
      L.path.style.opacity = o;
      const dp = outCubic(span(t, L.at + 0.55, L.at + 0.85));
      L.dot.setAttribute('cx', tx); L.dot.setAttribute('cy', ty); L.dot.style.opacity = dp * o;
      const rp = span(t, L.at + 0.6, L.at + 1.6);
      L.ring.setAttribute('cx', tx); L.ring.setAttribute('cy', ty); L.ring.setAttribute('r', 5 + 18 * outCubic(rp));
      L.ring.style.opacity = (1 - rp) * o * (rp > 0 ? 1 : 0);
    }
  }
  const lk = outCubic(span(t, 31.3, 32.2));
  const lo = 1 - span(t, 33.8, 34.4);
  lockMark.style.opacity = lk * lo;
  lockMark.style.filter = `blur(${(1 - lk) * 18}px)`;
  lockMark.style.transform = `translateY(${(1 - lk) * 16}px) scale(${0.97 + 0.03 * lk})`;
  const tk = outCubic(span(t, 31.9, 32.7));
  lockTag.style.opacity = tk * lo;
  lockTag.style.filter = `blur(${(1 - tk) * 12}px)`;
  lockTag.style.transform = `translateY(${(1 - tk) * 10}px)`;
  lockup.style.display = t > 31 ? 'flex' : 'none';
}

// ---------------------------------------------------------------- per-frame scene state
let lastUI = '';
function updateWorld(t) {
  phone.group.matrix.copy(phoneMatrix(t));
  phone.group.matrixWorldNeedsUpdate = true;
  scene.updateMatrixWorld();

  const st = screenState(t);
  const key = JSON.stringify([st.screen, st.prev, (st.L * 60) | 0, st.args.box && Math.round(st.args.box.x0)]);
  if (key !== lastUI) { ui.draw(st); uiTex.needsUpdate = true; lastUI = key; }
  const scanning = st.screen === 'scan' || st.prev === 'scan';
  phone.vf.visible = scanning;
  screenLight.intensity = scanning ? 2.2 : st.screen === 'home' ? 1.2 : 0.8;

  // USSD ghosts
  const L = t - RUN_T0;
  const runVisible = t > RUN_T0 - 0.1 && t < 26.6;
  const fadeAll = 1 - span(t, 26.0, 26.5);
  // A deck: the newest dialog sits beside the phone, in focus; older ones step back and fade.
  const shown = USSD_AT.filter((x) => L > x).length;
  ghosts.forEach((g, i) => {
    const a = USSD_AT[i];
    const p = outExpo(span(L, a, a + 0.7));
    g.mesh.visible = runVisible && L > a;
    if (!g.mesh.visible) return;
    // age grows smoothly as later cards arrive
    let age = 0;
    for (let j = i + 1; j < USSD_AT.length; j++) age += outCubic(span(L, USSD_AT[j], USSD_AT[j] + 0.6));
    const slot = new THREE.Vector3(-0.115 - age * 0.042, 0.018 + age * 0.006, -0.012 - age * 0.05);
    const from = new THREE.Vector3(-0.01, -0.01, 0.006);
    const pos = from.lerp(slot, p);
    const sc = 0.25 + 0.75 * p;
    const m = new THREE.Matrix4().compose(pos, new THREE.Quaternion().setFromEuler(new THREE.Euler(0, 0.18 * p + 0.05 * age, 0)), V(sc, sc, sc));
    g.mesh.matrix.copy(phone.group.matrixWorld).multiply(m);
    g.mesh.matrixWorldNeedsUpdate = true;
    const typeEnd = i === 4 ? 3.4 : a + 0.62;
    const typeStart = i === 4 ? 3.0 : a + 0.3;
    const n = Math.floor(g.reply.length * span(L, typeStart, typeEnd));
    const sent = i === 4 ? L > 3.5 : L > a + 0.7;
    const key = n + (sent ? 's' : '');
    if (key !== g.typed) {
      const c = drawUssdCard(g.prompt, g.reply.slice(0, n) + (n < g.reply.length && L > typeStart ? '|' : ''), sent);
      g.tex.image = c; g.tex.needsUpdate = true; g.typed = key;
    }
    g.mesh.material.opacity = Math.max(0, 1 - age * 0.38) * fadeAll * clamp(p * 3);
    g.mesh.renderOrder = 10 - Math.round(age);
  });
  void shown;
  scene.updateMatrixWorld();
  return st;
}

function renderViewfinder() {
  const ghostVis = ghosts.map((g) => g.mesh.visible);
  phone.group.visible = false;
  ghosts.forEach((g) => (g.mesh.visible = false));
  renderer.setRenderTarget(vfRT);
  renderer.render(scene, phoneCam);
  renderer.setRenderTarget(null);
  phone.group.visible = true;
  ghosts.forEach((g, i) => (g.mesh.visible = ghostVis[i]));
}

const halton = (i, b) => { let f = 1, r = 0; while (i > 0) { f /= b; r += f * (i % b); i = Math.floor(i / b); } return r; };

async function renderAt(t, sub = SUB) {
  renderer.setRenderTarget(accRT);
  renderer.setClearColor(0x000000, 0);
  renderer.clear();
  renderer.setRenderTarget(null);
  for (let s = 0; s < sub; s++) {
    const ts = sub > 1 ? t + ((s + 0.5) / sub - 0.5) * (0.5 / FPS) : t;
    const st = updateWorld(ts);
    const { focus, range } = placeCamera(ts);
    if (sub > 1) camera.setViewOffset(W, H, halton(s + 1, 2) - 0.5, halton(s + 1, 3) - 0.5, W, H);
    else camera.clearViewOffset();
    dof.target = focus;
    dof.cocMaterial.worldFocusRange = range;
    if (phone.vf.visible) renderViewfinder();
    composer.render();
    renderer.copyFramebufferToTexture(fbTex);
    accMat.uniforms.w.value = 1 / sub;
    renderer.setRenderTarget(accRT);
    renderer.render(accScene, quadCam);
    renderer.setRenderTarget(null);
  }
  camera.clearViewOffset();
  placeCamera(t);
  outMat.uniforms.seed.value = (t * 37.13) % 100;
  outMat.uniforms.fade.value = Math.min(span(t, 0, 0.6), 1 - span(t, DURATION - 0.7, DURATION)) * (1 - 0.55 * span(t, 30.9, 32.0));
  renderer.render(outScene, quadCam);
  updateOverlay(t);
}

if (params.get('debug') === 'spot') { bulbLight.intensity = 0; scene.environmentIntensity = 0.05; }
window.DURATION = DURATION;
window.FPS = FPS;
window.renderFrame = async (f) => { await renderAt(f / FPS); return true; };
window.renderTime = async (t, sub) => { await renderAt(t, sub); return true; };
const tq = params.get('t');
if (tq != null) await renderAt(Number(tq), SUB);
window.ready = true;
document.title = 'ready';
