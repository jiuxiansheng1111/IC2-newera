// Render the actual JSON-backed viewer into transparent PNG previews.
// Uses the bundled Playwright runtime and an installed headless browser; no downloads.
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require(process.env.IC2NE_PLAYWRIGHT_PATH || 'playwright');

(async () => {
  const executablePath = process.env.IC2NE_BROWSER_PATH;
  if (!executablePath) throw new Error('Set IC2NE_BROWSER_PATH to an installed Chromium browser.');
  const out = path.join(__dirname, 'renders');
  fs.mkdirSync(out, { recursive: true });
  const browser = await chromium.launch({ executablePath, headless: true });
  try {
    const page = await browser.newPage({ viewport: { width: 736, height: 680 }, deviceScaleFactor: 1 });
    page.on('pageerror', error => process.stderr.write(error.message + '\n'));
    await page.goto(process.env.IC2NE_PREVIEW_URL || 'http://127.0.0.1:8819/', { waitUntil: 'networkidle' });
    const frame = page.frameLocator('iframe');
    const select = frame.locator('#ic2ne-model-choice');
    const options = await select.locator('option').allTextContents();
    const catalog = await frame.locator('#ic2ne-model-data').textContent();
    const models = JSON.parse(catalog).models;
    for (let i = 0; i < models.length; i++) {
      await select.selectOption(String(i));
      await frame.locator('#ic2ne-model-canvas').screenshot({ path: path.join(out, models[i].id + '.png'), omitBackground: true });
    }
    process.stdout.write(JSON.stringify({ previews: options.length, directory: out }) + '\n');
  } finally { await browser.close(); }
})().catch(error => { process.stderr.write(error.stack + '\n'); process.exitCode = 1; });
