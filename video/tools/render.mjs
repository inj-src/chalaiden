// Renders the film frame by frame in GPU Chrome and writes PNGs.
//   node tools/render.mjs --stills 2,6.5,10 [--sub 1]     -> out/stills/t_<time>.png
//   node tools/render.mjs [--from 0] [--to 1035] [--sub 6] -> out/frames/00000.png ...
import { createServer } from 'node:http';
import { readFile, mkdir, writeFile } from 'node:fs/promises';
import { extname, join, normalize } from 'node:path';
import { chromium } from 'playwright-core';

const ROOT = new URL('..', import.meta.url).pathname;
const args = Object.fromEntries(process.argv.slice(2).reduce((a, v, i, arr) => (v.startsWith('--') ? [...a, [v.slice(2), arr[i + 1]?.startsWith('--') || arr[i + 1] == null ? true : arr[i + 1]]] : a), []));
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript', '.json': 'application/json', '.gltf': 'model/gltf+json', '.bin': 'application/octet-stream', '.jpg': 'image/jpeg', '.png': 'image/png', '.hdr': 'application/octet-stream', '.woff2': 'font/woff2', '.ttf': 'font/ttf' };

const server = createServer(async (req, res) => {
  try {
    const p = normalize(decodeURIComponent(req.url.split('?')[0])).replace(/^\/+/, '') || 'index.html';
    const data = await readFile(join(ROOT, p));
    res.writeHead(200, { 'content-type': MIME[extname(p)] ?? 'application/octet-stream' });
    res.end(data);
  } catch { res.writeHead(404); res.end(); }
}).listen(0);
const port = server.address().port;

const browser = await chromium.launch({
  executablePath: '/usr/bin/google-chrome',
  headless: true,
  args: ['--headless=new', '--use-angle=gl', '--ignore-gpu-blocklist', '--enable-gpu-rasterization', '--force-color-profile=srgb'],
  env: { ...process.env, __NV_PRIME_RENDER_OFFLOAD: '1', __GLX_VENDOR_LIBRARY_NAME: 'nvidia', __VK_LAYER_NV_optimus: 'NVIDIA_only' },
});
const page = await browser.newPage({ viewport: { width: 1920, height: 1080 }, deviceScaleFactor: 1 });
page.on('console', (m) => { if ((m.type() === 'error' || m.type() === 'warning') && !m.text().includes('favicon')) console.log('[page]', m.type(), m.text()); });
page.on('response', (r) => { if (r.status() >= 400) console.log('[404]', r.url()); });
page.on('pageerror', (e) => { console.log('[pageerror]', e.stack); process.exit(1); });
const sub = args.sub ?? 6;
await page.goto(`http://localhost:${port}/index.html?sub=${sub}${args.debug ? '&debug=' + args.debug : ''}`);
await page.waitForFunction(() => window.ready === true, null, { timeout: 180000 });
const gpu = await page.evaluate(() => {
  const gl = document.createElement('canvas').getContext('webgl2');
  const e = gl.getExtension('WEBGL_debug_renderer_info');
  return e ? gl.getParameter(e.UNMASKED_RENDERER_WEBGL) : 'unknown';
});
console.log('GPU:', gpu);
const stage = await page.$('#stage');

if (args.stills) {
  await mkdir(join(ROOT, 'out/stills'), { recursive: true });
  for (const t of String(args.stills).split(',').map(Number)) {
    const t0 = Date.now();
    await page.evaluate(async ([t, s]) => { await window.renderTime(t, s); await new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r))); }, [t, Number(sub)]);
    await stage.screenshot({ path: join(ROOT, `out/stills/t_${t.toFixed(2)}.png`) });
    console.log(`still ${t}s  ${Date.now() - t0}ms`);
  }
} else {
  const fps = await page.evaluate(() => window.FPS);
  const total = Math.round(await page.evaluate(() => window.DURATION) * fps);
  const from = Number(args.from ?? 0), to = Math.min(Number(args.to ?? total), total);
  await mkdir(join(ROOT, `out/${args.out ?? 'frames'}`), { recursive: true });
  const t0 = Date.now();
  const step = Number(args.step ?? 1);
  for (let f = from; f < to; f += step) {
    await page.evaluate(async (f) => { await window.renderFrame(f); await new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r))); }, f);
    const buf = await stage.screenshot({ type: 'png' });
    await writeFile(join(ROOT, `out/${args.out ?? 'frames'}/${String(f / step).padStart(5, '0')}.png`), buf);
    if (f % (30 * step) === 0) {
      const el = (Date.now() - t0) / 1000, done = f - from + 1;
      console.log(`frame ${f}/${to}  ${(el / done).toFixed(2)}s/f  eta ${((to - f) * el / done / 60).toFixed(1)}min`);
    }
  }
}
await browser.close();
server.close();
