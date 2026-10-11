# Coursework deployment — preparation in progress

Checked 2026-10-11. User selected Gemini API free-tier inference. **Nothing is deployed.** Existing local services and data are preserved. The six original phases and V8 coursework acceptance remain historical passing results; hosted acceptance is a separate pending gate.

## Candidate stack and verified constraints

- Vercel Hobby for the React `frontend` directory: personal/non-commercial coursework only. [Plan](https://vercel.com/docs/plans/hobby).
- Render Free for a single backend Docker web service: 512 MB/0.1 CPU, 750 workspace hours/month, idle sleep after 15 minutes, ephemeral filesystem, possible restarts and outbound-traffic suspension. Quotas are shared with other services. [Compute](https://render.com/docs/compute-plans), [free service limits](https://render.com/docs/free).
- Aiven Free MySQL: one node, 1 CPU, 1 GB RAM/disk, 76 connections and backups, no credit card required. Inactive services can be powered off. Provisioning eligibility and regions must be confirmed in the account. [Limits](https://aiven.io/docs/products/mysql/concepts/mysql-free-tier).
- Gemini API: India is supported, subject to account/age eligibility. Selected model free input/output prices do not prove usable quota. Confirm model RPM/TPM/RPD in the actual project. `gemini-2.5-flash-lite` is one documented free candidate, not yet accepted for TripPilot. Free-tier content may improve Google products. [Eligibility](https://ai.google.dev/gemini-api/docs/available-regions), [pricing](https://ai.google.dev/gemini-api/docs/pricing), [project quotas](https://ai.google.dev/gemini-api/docs/rate-limits).

Do not select paid compute or billing upgrades as a fallback without user instruction. Current three research workers and one global inference slot remain. Budget for several extraction calls per job, plus at most one existing output repair. A 429 fails safely; the adapter adds no hidden HTTP retries. Retain the local evaluated model for local coursework fallback.

## Prepared artifacts

`render.yaml` is a candidate Blueprint with automatic deployment off, one free Docker service and readiness checks. Set secret values in Render, never in source. `infra/deployment/Dockerfile` uses Java 21, builds only the backend with the Maven wrapper, and runs as a non-root user. `.dockerignore` allowlists build source; no local databases, credentials, runtime traces, internal notes or Git metadata enter the build context. Docker was not installed or started for this work; the actual Linux image build and hosted memory fit remain unverified.

`application-hosted.yml` is opt-in. Local 8002/13306/11436 defaults remain. The container entrypoint reads Render's `PORT`, binds all interfaces, sets secure/HttpOnly/SameSite=Lax session cookies and uses 256 MB maximum Java heap with bounded native allocations. Eight JDBC connections accommodate the dispatcher's held ownership connection and workers without using Aiven's full allowance. Actual container RSS/startup/three-worker load must fit 512 MB; these settings are a candidate, not measured acceptance.

Forwarded headers are not trusted: the current API needs no forwarded identity or HTTPS redirects, and cookie security is explicit. Do not enable arbitrary forwarded-header trust on the directly reachable backend. All endpoints retain session authentication, CSRF and owner checks. A private-response filter sets no-store for API errors and SSE as well as normal API responses.

## Fresh database and backend setup

1. Sign in to the selected accounts; create **Free MySQL**, not a paid trial service. Choose the nearest mutually available backend/database regions. Start with an empty dedicated database/schema; do not import local databases or user records.
2. Obtain the service CA certificate and add it as Render secret file **mysql-ca.pem**, mounted at `/etc/secrets/mysql-ca.pem`. Supply `DB_URL=jdbc:mysql://HOST:PORT/SCHEMA` without credentials/query parameters, `DB_USERNAME`, `DB_PASSWORD`, server-only `GEMINI_API_KEY`, and the evaluated exact `MODEL_ID`. Do not paste secrets into chat. The entrypoint creates a certificate-only trust store in `/tmp`; Connector/J uses `VERIFY_IDENTITY`, no fallback to the system store. A wrong CA/hostname must fail connection. [Connector/J TLS](https://dev.mysql.com/doc/connector-j/en/connector-j-reference-using-ssl.html).
3. Confirm all V1–V8 Flyway migrations apply to the empty hosted schema and Spring JDBC sessions persist. Verify the actual MySQL version, migration permissions and named-lock support. Only one dispatcher may use this schema. Initial hosted booking is forcibly disabled in this profile; no provider process or ledger is uploaded.
4. Connect only reviewed public source to Render. The original workspace is dirty; do not force-push, reset, or stage internal files. The previous source ZIP is historical and excludes this deployment work. The updated source-export helper now includes the candidate deployment files and scans for Google API-key patterns too.

## Gemini adapter and evaluation gate

The new `GeminiModelAdapter` uses tool-free `v1beta` GenerateContent with the existing JSON schema, selected evidence, temperature/seed/output cap and deadline. The API key is sent in a header to the fixed official HTTPS origin and never enters a job snapshot. No search grounding, URL tools, function calls or automatic HTTP retry are enabled. Retrieval, scope binding, exact quote/citation validation, freshness/conflict handling and repair budgets stay in the backend. [REST contract](https://ai.google.dev/api/generate-content), [structured output](https://ai.google.dev/gemini-api/docs/generate-content/structured-output).

`RoutingModelAdapter` dispatches using each immutable job snapshot, with one shared application slot across Ollama/Gemini. Existing Ollama jobs keep their captured path; they are not silently migrated. The cloud fingerprint hashes model/API/adapter configuration identity: **it is not a cloud-weight digest or training-lineage attestation**. The returned `modelVersion` must equal the selected model ID; aliases that return another version are rejected until that exact selectable version is separately evaluated. Token totals include provider thinking tokens, and truncated/blocked/malformed/oversized/tool outputs cannot publish. Provider endpoint secrets/errors/prompts are excluded from diagnostics. Cancellation prevents acceptance of a late result; it cannot guarantee cancellation of remote provider computation.

Before acceptance, use a server-side key in an isolated evaluation environment and test production extraction/validation on public/synthetic fixtures: all four useful guide fields, missing evidence, injected instructions, wrong city, stale sources, conflicting quotes, fabricated passage IDs and scope errors; bounded repair/retry; quota/timeout/cancellation; 4K and 8K context; latency and token use. Then execute durable real-MySQL/live-guide/worker/SSE tests with the cloud route, not only mocked HTTP tests. Never send existing personal records for evaluation. Run scripts/evaluate-gemini.ps1 -ModelId EXACT_VERSION with GEMINI_API_KEY set privately and Java 21 selected. It exercises synthetic extraction/validation at 4K/8K, with four focused guide calls and one shared repair cap; it makes at least 18 calls, up to 30 with repairs, so confirm quota first. It logs case names/counters/timings, not prompt or raw output bodies. This runner is prepared and compiled, not yet executed. Live Gemini evaluation and quota verification are pending account access; local Ollama acceptance does not establish Gemini acceptance.

## Vercel same-origin proxy

After the Render URL exists, run:

```powershell
./scripts/configure-vercel.ps1 -BackendOrigin https://YOUR-SERVICE.onrender.com
```

The helper validates the HTTPS Render origin and refuses to overwrite existing configuration. It generates `frontend/vercel.json`; import `frontend` as the Vercel root, `npm run build`, `dist`. Relative `/api` URLs and native EventSource stay unchanged. The rewrite retains `/api` on the backend path. No model/database secrets belong in Vercel or `VITE_*` variables.

The configuration explicitly disables external-rewrite caching and adds private/no-store CDN headers. Vercel now honors upstream cache headers for new projects, making this important for sessions and private card data. [Rewrite/cache behavior](https://vercel.com/docs/routing/rewrites). Backend SSE already uses 30-second streams, 500 ms heartbeats, event IDs and `Last-Event-ID` replay; Vercel documents a 120-second external-origin response timeout. Actual flushing, reconnect/replay and retained-event expiry must be tested through the deployed host. [Timeout](https://vercel.com/docs/errors/router_external_target_error).

## Hosted acceptance record — all pending

| Gate | Required evidence |
| --- | --- |
| TLS/startup | HTTPS frontend/backend, verified DB CA+hostname, V1–V8 applied, readiness, no credentials in output |
| Access | Registration/login/logout, secure session cookie, anonymous denial, second-owner denial, mutation without CSRF denied, no-store errors/results/streams |
| Coursework CRUD | Research creates card; read/edit title+notes; plan rename; Trash/delete/restore; reload; all survive backend restart |
| Research | Live guides + evaluated Gemini, useful four guide fields, honest missing commercial quotes, citations and Inspector |
| Planning | Comparison, selected context isolation, fresh follow-ups and refresh |
| SSE | Actual progress flush, reconnect after 30 seconds with cursor, ordered replay, explicit expired cursor behavior |
| Recovery | Restart during active job marks interruption without replay; queued jobs recover; saved evidence/session/CRUD remain |
| Resources | Startup and three-worker load within 512 MB; small JDBC pool; provider quotas/storage/bandwidth measured |
| Mobile | Hosted desktop/mobile auth, cards/edit/Trash/Inspector/comparison without overflow |
| Public admission | Review exposure and registration/research abuse controls before unrestricted public sharing |

Current new verification: focused adapter/cache/evidence tests passed **39 tests, zero failures/errors/skips**. No live Gemini test, Docker image build, hosted database migration, proxy/browser/restart acceptance or actual deployment has been completed. Necessary next action: account access and actual Gemini model quota/version selection, then isolated live evaluation and hosted provisioning.
