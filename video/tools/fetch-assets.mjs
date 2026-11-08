// Downloads the CC0 Poly Haven assets the scene uses into ./assets.
// Usage: node tools/fetch-assets.mjs
import { mkdir, writeFile, access } from 'node:fs/promises';
import { dirname, join } from 'node:path';

const ROOT = new URL('../assets/', import.meta.url).pathname;
const MODELS = [
  'lightbulb_01', 'pull_chain_light_socket', 'potted_plant_04', 'plastic_crate_01',
  'long_life_food', 'cardboard_box_01', 'office_notepads', 'russian_food_cans_01',
  'pot_enamel_01', 'vintage_electric_kettle', 'painted_wooden_shelves',
];
const TEXTURES = ['dark_wood', 'plastered_wall_04', 'corrugated_iron_02'];
const HDRI = 'warm_restaurant_night';

const exists = (p) => access(p).then(() => true, () => false);
async function get(url, out) {
  if (await exists(out)) return;
  const r = await fetch(url);
  if (!r.ok) throw new Error(`${r.status} ${url}`);
  await mkdir(dirname(out), { recursive: true });
  await writeFile(out, Buffer.from(await r.arrayBuffer()));
  console.log('  ', out.replace(ROOT, ''));
}
const files = async (id) => (await fetch(`https://api.polyhaven.com/files/${id}`)).json();

for (const id of MODELS) {
  const f = await files(id);
  const g = f.gltf?.['1k']?.gltf ?? f.gltf?.['2k']?.gltf;
  if (!g) { console.warn('no gltf for', id); continue; }
  console.log(id);
  await get(g.url, join(ROOT, 'models', id, `${id}.gltf`));
  for (const [rel, inc] of Object.entries(g.include ?? {})) await get(inc.url, join(ROOT, 'models', id, rel));
}
for (const id of TEXTURES) {
  const f = await files(id);
  console.log(id);
  for (const [map, key] of [['Diffuse', 'diff'], ['nor_gl', 'nor'], ['Rough', 'rough'], ['arm', 'arm']]) {
    const e = f[map]?.['2k']?.jpg ?? f[map]?.['2k']?.png;
    if (e) await get(e.url, join(ROOT, 'textures', id, `${key}.${e.url.split('.').pop()}`));
  }
}
{
  const f = await files(HDRI);
  await get(f.hdri['1k'].hdr.url, join(ROOT, 'hdri', `${HDRI}.hdr`));
}
console.log('done');
