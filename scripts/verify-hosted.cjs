// Opt-in public/synthetic acceptance. Creates fresh test accounts; never imports local records.
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const { randomUUID } = require('node:crypto');
const { spawn } = require('node:child_process');
const { request } = require('playwright');
const [origin, output] = process.argv.slice(2);
if (!/^https:\/\/[a-z0-9-]+\.vercel\.app$/.test(origin || '')) throw new Error('Supply a Vercel HTTPS origin.');
const resolved = path.resolve(output || '');
const runtime = path.resolve(__dirname, '../runtime-data') + path.sep;
if (!resolved.startsWith(runtime) || fs.existsSync(resolved)) throw new Error('Use a fresh output directory inside runtime-data.');
fs.mkdirSync(resolved, { recursive: true });
const password = 'synthetic-phase2-password-42';
const accounts = [], jobs = [], checks = [];
const clients = [];
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const terminal = state => ['SUCCEEDED', 'PARTIAL', 'FAILED', 'CANCELLED', 'INTERRUPTED'].includes(state);
async function call(client, method, url, data, status, csrf = true, extra = {}) {
  const headers = { ...(extra.headers || {}) };
  if (method !== 'GET' && csrf) {
    const token = await (await client.get('/api/identity/csrf')).json();
    headers[token.headerName] = token.token;
  }
  const res = await client.fetch(url, { method, ...(data === undefined ? {} : { data }), ...extra, headers });
  assert.equal(res.status(), status, `${method} ${url} status ${res.status()}`);
  assert.match(res.headers()['cache-control'] || '', /no-store/);
  assert.notEqual(res.headers()['x-vercel-cache'], 'HIT', 'Private response must not be a CDN HIT');
  return status === 204 ? null : res.json();
}
async function account() {
  const client = await request.newContext({ baseURL: origin, timeout: 60000 }); clients.push(client);
  const email = `hosted-${randomUUID()}@example.invalid`;
  await call(client, 'POST', '/api/identity/register', { email, password }, 201);
  await call(client, 'POST', '/api/identity/login', undefined, 204, true, { form: { username: email, password } });
  accounts.push({ email, password });
  return client;
}
async function stream(client, id, after = 0, interruptMs = 0) {
  const cookies = (await client.storageState()).cookies.map(c => `${c.name}=${c.value}`).join('; ');
  const controller = new AbortController();
  let timer, text = '', firstByteMs = null;
  const started = Date.now();
  try {
    const res = await fetch(`${origin}/api/research/jobs/${id}/events`, {
      headers: { Cookie: cookies, ...(after ? { 'Last-Event-ID': String(after) } : {}) }, signal: controller.signal
    });
    assert.equal(res.status, 200); assert.match(res.headers.get('cache-control'), /no-store/);
    if (interruptMs) timer = setTimeout(() => controller.abort(), interruptMs);
    for await (const chunk of res.body) {
      if (firstByteMs === null) firstByteMs = Date.now() - started;
      text += Buffer.from(chunk).toString('utf8');
    }
  } catch (error) { if (error.name !== 'AbortError') throw error; }
  finally { clearTimeout(timer); }
  const ids = [...text.matchAll(/^id:\s*(\d+)/gm)].map(m => Number(m[1]));
  assert.ok(ids.every((id, index) => id > after && (!index || id > ids[index - 1])), 'Ordered cursor replay');
  return { ids, firstByteMs, text };
}
async function waitJob(client, id) {
  const limit = Date.now() + 630000;
  while (Date.now() < limit) {
    const job = await call(client, 'GET', `/api/research/jobs/${id}`, undefined, 200);
    if (terminal(job.status)) {
      assert.ok(['SUCCEEDED', 'PARTIAL'].includes(job.status), `Research ${job.status}/${job.outcomeCode}`);
      const inspection = await call(client, 'GET', `/api/research/jobs/${id}/inspector`, undefined, 200);
      assert.equal(inspection.model.provider, 'gemini'); assert.equal(inspection.model.modelId, 'gemini-3.5-flash-lite');
      for (const field of inspection.result.fields) for (const citation of field.citations) {
        const source = inspection.sources.find(s => s.id === citation.sourceId);
        assert.ok(source && source.text.includes(citation.quote)); assert.equal(source.finalUrl, citation.url);
      }
      assert.ok(inspection.result.fields.some(f => f.state === 'SUPPORTED'));
      assert.ok(inspection.result.fields.filter(f => ['PRICE', 'AVAILABILITY', 'POLICY'].includes(f.field)).every(f => f.state === 'UNAVAILABLE'));
      jobs.push({ id, contextId: job.contextId, state: job.status, modelTurns: job.modelTurns });
      console.log(`Hosted research passed: ${job.status}, ${job.modelTurns} model turns.`);
      return inspection;
    }
    await sleep(1500);
  }
  throw new Error('Hosted research exceeded captured deadline.');
}
(async () => {
  const a = await account(), b = await account();
  await call(a, 'POST', '/api/planning/sessions', { title: 'Missing CSRF must fail' }, 403, false);
  const cookie = (await a.storageState()).cookies.find(c => c.name === 'SESSION');
  assert.ok(cookie && cookie.secure && cookie.httpOnly && cookie.sameSite === 'Lax');
  assert.equal(cookie.domain, new URL(origin).hostname);
  const plan = await call(a, 'POST', '/api/planning/sessions', { title: 'Synthetic phase two research' }, 201);
  fs.writeFileSync(path.join(resolved, 'private-fixture.json'), JSON.stringify({ accounts, planId: plan.id, jobs }, null, 2));
  await call(b, 'GET', `/api/planning/sessions/${plan.id}`, undefined, 404);
  checks.push('auth-cookie-csrf-owner-private-cache');
  const requirements = { departure: 'Delhi', destinationIntent: 'Jaipur', country: 'IN', arrival: '2026-11-01', departureDate: '2026-11-04', budget: '20000', currency: 'INR', travelers: 1, preferences: [] };
  for (const destination of ['Jaipur', 'Udaipur']) {
    const revision = await call(a, 'POST', `/api/planning/sessions/${plan.id}/revisions`, { ...requirements, destinationIntent: destination }, 201);
    const job = await call(a, 'POST', `/api/planning/sessions/${plan.id}/jobs`, { revisionId: revision.id, requestKey: randomUUID() }, 202);
    const initial = await stream(a, job.id, 0, 8000);
    assert.ok(initial.ids.length && initial.firstByteMs < 15000, 'Progress must flush before completion');
    const cursor = initial.ids.at(-1);
    const reconnection = await stream(a, job.id, cursor);
    await waitJob(a, job.id);
    const replay = await stream(a, job.id, initial.ids[0]);
    assert.ok(replay.text.includes('event:JOB_FINISHED'));
    await call(a, 'GET', `/api/research/jobs/${job.id}/events`, undefined, 409, true, { headers: { 'Last-Event-ID': '9223372036854775807' } });
    await call(b, 'GET', `/api/research/jobs/${job.id}/inspector`, undefined, 404);
    await call(b, 'PUT', `/api/planning/cards/${job.id}`, { title: 'Denied', notes: '' }, 404);
    checks.push(`research-${destination}-citations-snapshot-sse-reconnect-replay-cursor`);
    fs.writeFileSync(path.join(resolved, `sse-${destination}.json`), JSON.stringify({ firstByteMs: initial.firstByteMs, initialIds: initial.ids, reconnectedIds: reconnection.ids, replayIds: replay.ids }, null, 2));
  }
  const selected = await call(a, 'POST', `/api/planning/sessions/${plan.id}/selected-trips`, { jobId: jobs[0].id }, 201);
  const follow = await call(a, 'POST', `/api/planning/trips/${selected.id}/research`, { requirements, question: 'What train access is described in the guide?', focus: 'TRANSPORT', requestKey: randomUUID() }, 202);
  const followed = await waitJob(a, follow.id);
  assert.equal(followed.job.contextId, selected.id);
  const planningJobs = await call(a, 'GET', `/api/planning/sessions/${plan.id}/jobs`, undefined, 200);
  assert.equal(planningJobs.length, 2, 'Selected follow-up must remain isolated from planning cards');
  assert.equal((await call(a, 'GET', '/api/booking/capability', undefined, 200)).enabled, false);
  checks.push('selected-follow-up-isolation-booking-disabled');
  fs.writeFileSync(path.join(resolved, 'private-fixture.json'), JSON.stringify({ accounts, planId: plan.id, jobs, selectedId: selected.id }, null, 2));
  await new Promise((resolve, reject) => {
    const child = spawn(process.execPath, [path.join(__dirname, 'verify-planning-ui.cjs'), '0', accounts[0].email, resolved], {
      stdio: 'inherit', env: { ...process.env, PHASE4_HOSTED_ORIGIN: origin }
    });
    child.on('error', reject); child.on('exit', code => code === 0 ? resolve() : reject(new Error('Hosted browser acceptance failed')));
  });
  await call(a, 'POST', '/api/identity/logout', undefined, 204);
  await call(a, 'GET', '/api/planning/sessions', undefined, 401);
  fs.writeFileSync(path.join(resolved, 'hosted-report.json'), JSON.stringify({ passed: true, origin, checks, jobs, restartRecovery: 'pending', renderLoadMemory: 'pending' }, null, 2));
  console.log('Hosted API/browser acceptance passed. Restart recovery and Render load measurements remain pending.');
})().catch(error => { fs.writeFileSync(path.join(resolved, 'hosted-report.json'), JSON.stringify({ passed: false, origin, checks, jobs, failure: error.message, restartRecovery: 'pending', renderLoadMemory: 'pending' }, null, 2)); console.error(error.message); process.exitCode = 1; }).finally(async () => { await Promise.all(clients.map(c => c.dispose())); });
