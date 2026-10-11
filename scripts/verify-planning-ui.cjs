// Browser acceptance against a real isolated backend and the production React build.
const fs = require('node:fs');
const path = require('node:path');
const http = require('node:http');
const assert = require('node:assert/strict');
const { chromium } = require('playwright');
const [backendPort, email, output] = process.argv.slice(2);
const dist = path.resolve(__dirname, '../frontend/dist');
const errors = [];
let browser, page;
const server = http.createServer((req, res) => {
  if (req.url.startsWith('/api/') || req.url.startsWith('/actuator/')) {
    const upstream = http.request({ hostname: '127.0.0.1', port: Number(backendPort), method: req.method, path: req.url, headers: { ...req.headers, host: `127.0.0.1:${backendPort}` } }, response => { res.writeHead(response.statusCode, response.headers); response.pipe(res); });
    upstream.on('error', () => { if (!res.headersSent) res.writeHead(502); res.end(); });
    res.on('close', () => upstream.destroy()); req.pipe(upstream); return;
  }
  const relative = decodeURIComponent(req.url.split('?')[0]);
  const target = path.resolve(dist, '.' + (relative === '/' ? '/index.html' : relative));
  if (!target.startsWith(dist + path.sep) || !fs.existsSync(target)) { res.writeHead(404); res.end(); return; }
  res.setHeader('Content-Type', target.endsWith('.js') ? 'application/javascript' : target.endsWith('.css') ? 'text/css' : 'text/html'); fs.createReadStream(target).pipe(res);
});
async function text(page, value) { await page.getByText(value, { exact: true }).waitFor(); }
(async () => {
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const base = `http://127.0.0.1:${server.address().port}`;
  browser = await chromium.launch({ channel: process.env.PHASE4_BROWSER_CHANNEL || 'chrome', headless: true });
  page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
  page.on('pageerror', error => errors.push(error.message));
  await page.goto(base);
  await page.getByLabel('Email', { exact: true }).fill(email);
  await page.getByLabel('Password', { exact: true }).fill('synthetic-phase2-password-42');
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  await page.getByRole('button', { name: /Synthetic phase two research/ }).click();
  await page.locator('.trip-card').nth(1).waitFor();
  assert.equal(await page.locator('.trip-card').count(), 2);
  await page.getByLabel('Compare', { exact: true }).nth(0).check();
  await page.getByLabel('Compare', { exact: true }).nth(1).check();
  await text(page, 'Compare the evidence');
  assert.equal(await page.locator('table tbody tr').count(), 7);
  const jaipur = page.locator('.trip-card').filter({ has: page.getByRole('heading', { name: 'Jaipur', exact: true }) });
  await jaipur.getByRole('button', { name: 'Select trip', exact: true }).click();
  await text(page, 'SELECTED TRIP / PRIVATE CONTEXT');
  await page.getByLabel('Research topic', { exact: true }).selectOption('TRANSPORT');
  await page.getByLabel('Follow-up question or requirement').fill('Is train access described? browser-scope-42');
  const followResponse = page.waitForResponse(response => response.url().includes('/research') && response.request().method() === 'POST');
  await page.getByRole('button', { name: 'Research / refresh this trip', exact: true }).click();
  const submitted = await followResponse; assert.equal(submitted.status(), 202, await submitted.text());
  await page.locator('.message p').filter({ hasText: 'Is train access described? browser-scope-42' }).waitFor();
  await page.getByRole('button', { name: 'Cancel research', exact: true }).click();
  await page.getByText(/cancelled ·/).waitFor();
  await page.getByRole('button', { name: '← Back to comparison', exact: true }).click();
  const udaipur = page.locator('.trip-card').filter({ has: page.getByRole('heading', { name: 'Udaipur', exact: true }) });
  await udaipur.getByRole('button', { name: 'Select trip', exact: true }).click();
  await text(page, 'SELECTED TRIP / PRIVATE CONTEXT');
  assert.equal(await page.getByText('Is train access described? browser-scope-42', { exact: true }).count(), 0);
  await page.screenshot({ path: path.join(output, 'selected-desktop.png'), fullPage: true });
  await page.reload();
  await page.getByRole('button', { name: /Synthetic phase two research/ }).click();
  await page.locator('.sidebar').getByRole('button', { name: /Jaipur.*Separate research/ }).click();
  await page.locator('.message p').filter({ hasText: 'Is train access described? browser-scope-42' }).waitFor();
  await page.setViewportSize({ width: 390, height: 844 });
  await page.screenshot({ path: path.join(output, 'selected-mobile.png'), fullPage: true });
  assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), 'Mobile page must not overflow horizontally');
  await page.getByRole('button', { name: '+ New plan', exact: true }).click();
  await page.getByLabel('Destinations to compare', { exact: false }).fill('Agra');
  await page.getByRole('button', { name: 'Research destinations', exact: true }).click();
  await page.getByText(/To tailor the plan, clarify:/).waitFor();
  await page.getByRole('button', { name: 'Cancel research', exact: true }).click();
  await page.getByText(/cancelled ·/).waitFor();
  await page.screenshot({ path: path.join(output, 'planning-mobile.png'), fullPage: true });
  assert.equal(await page.getByText('Is train access described? browser-scope-42', { exact: true }).count(), 0);
  await page.getByRole('button', { name: 'Sign out', exact: true }).click();
  await page.getByRole('button', { name: 'New here? Create an account', exact: true }).click();
  await page.getByLabel('Email', { exact: true }).fill(`browser-${Date.now()}@example.invalid`);
  await page.getByLabel('Password', { exact: true }).fill('synthetic-browser-password-42');
  await page.getByRole('button', { name: 'Create account & sign in', exact: true }).click();
  await text(page, 'Your plans');
  assert.equal(await page.locator('.sidebar nav button').count(), 0);
  assert.equal(await page.locator('.trip-card').count(), 0);
  assert.deepEqual(errors, []);
  fs.writeFileSync(path.join(output, 'report.json'), JSON.stringify({ passed: true, flows: ['sign-in', 'validated-cards', 'comparison', 'selection', 'scoped-follow-up', 'cancel', 'independent-second-trip', 'reload', 'mobile', 'clarification', 'register', 'logout', 'owner-isolation'], pageErrors: errors }, null, 2));
  console.log('Production browser acceptance passed: auth, comparison, selection, scoped follow-ups/cancellation/reload, clarification and mobile isolation.');
})().catch(async error => { if (page) { await page.screenshot({ path: path.join(output, 'failure.png'), fullPage: true }).catch(() => {}); console.log(await page.locator('body').innerText()); } console.error(error); process.exitCode = 1; }).finally(async () => { if (browser) await browser.close(); server.closeAllConnections(); server.close(); });


