// Real backend + separate provider + production assets; no browser API stubs.
const fs = require('node:fs');
const path = require('node:path');
const http = require('node:http');
const assert = require('node:assert/strict');
const { chromium } = require('playwright');
const [backendPort, email, output, tripId] = process.argv.slice(2);
const dist = path.resolve(__dirname, '../frontend/dist');
let browser;
const errors = [];
const server = http.createServer((req, res) => {
  if (req.url.startsWith('/api/')) {
    const upstream = http.request({ hostname: '127.0.0.1', port: Number(backendPort), method: req.method, path: req.url, headers: { ...req.headers, host: `127.0.0.1:${backendPort}` } }, response => { res.writeHead(response.statusCode, response.headers); response.pipe(res); });
    upstream.on('error', () => { if (!res.headersSent) res.writeHead(502); res.end(); }); req.pipe(upstream); return;
  }
  const target = path.resolve(dist, '.' + (req.url === '/' ? '/index.html' : req.url.split('?')[0]));
  if (!target.startsWith(dist + path.sep) || !fs.existsSync(target)) { res.writeHead(404); res.end(); return; }
  res.setHeader('Content-Type', target.endsWith('.js') ? 'application/javascript' : target.endsWith('.css') ? 'text/css' : 'text/html'); fs.createReadStream(target).pipe(res);
});
(async () => {
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  browser = await chromium.launch({ channel: process.env.PHASE4_BROWSER_CHANNEL || 'chrome', headless: true });
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
  page.on('pageerror', error => errors.push(error.message));
  await page.goto(`http://127.0.0.1:${server.address().port}`);
  await page.getByLabel('Email', { exact: true }).fill(email);
  await page.getByLabel('Password', { exact: true }).fill('synthetic-phase2-password-42');
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  async function open() {
    await page.getByRole('button', { name: /Synthetic phase two research/ }).click();
    await page.locator('.sidebar').getByRole('button', { name: /Jaipur.*Separate research/ }).click();
    await page.getByRole('region', { name: 'Booking demonstration' }).waitFor();
  }
  await open();
  const panel = page.getByRole('region', { name: 'Booking demonstration' });
  await panel.getByText(/Demo provider.*no payment or real reservations/).waitFor();
  await panel.getByRole('button', { name: 'Check stay inventory', exact: true }).click();
  await panel.getByRole('button', { name: 'Get quote', exact: true }).click();
  await panel.getByText('All-inclusive stay total: INR 4000.00', { exact: true }).waitFor();
  assert.equal(await panel.getByRole('button', { name: 'Authorize this stay', exact: true }).isDisabled(), true);
  await panel.getByLabel('I authorize this exact stay', { exact: false }).check();
  const authorized = page.waitForResponse(r => r.url().endsWith('/authorizations') && r.request().method() === 'POST');
  await panel.getByRole('button', { name: 'Authorize this stay', exact: true }).click();
  assert.equal((await authorized).status(), 200);
  await panel.getByText('Stay status: authorized', { exact: true }).waitFor();
  await panel.getByRole('button', { name: 'Submit authorized stay', exact: true }).click();
  await panel.getByText('Stay status: confirmed', { exact: true }).waitFor();
  assert.equal(await panel.getByRole('button', { name: 'Check stay inventory', exact: true }).isDisabled(), true);
  await page.screenshot({ path: path.join(output, 'booking-desktop.png'), fullPage: true });
  await page.reload(); await open();
  await panel.getByText('Stay status: confirmed', { exact: true }).waitFor();
  await panel.getByRole('button', { name: 'Review saved quote', exact: true }).click();
  await panel.getByText('All-inclusive stay total: INR 4000.00', { exact: true }).waitFor();
  await page.setViewportSize({ width: 390, height: 844 });
  await page.screenshot({ path: path.join(output, 'booking-mobile.png'), fullPage: true });
  assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), 'Booking panel mobile overflow');
  assert.deepEqual(errors, []);
  console.log('Production booking browser passed: labeled demo, consent, authorization, submit, confirmation, reload and mobile. Trip ' + tripId);
})().catch(error => { console.error(error); process.exitCode = 1; }).finally(async () => { if (browser) await browser.close(); await new Promise(resolve => server.close(resolve)); });
