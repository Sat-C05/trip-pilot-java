# Phase 4 — Planning experience

The React workspace supports account registration/sign-in/out, saved planning sessions, request clarification, up to three explicitly entered Indian destinations, live research progress, comparison, selection and separate selected-trip exploration. Cards show only backend-validated records, exact citations and retrieval times. Missing/conflicting/stale values and estimates stay visible. Comparison does not rank unavailable prices or invent a best destination.

## Selected contexts and refresh

V6 scopes request revisions to contexts, links selected snapshots to owner/session-scoped source jobs, records research topics and per-context follow-up messages, and stores image attribution. Selecting a terminal option requires current supported or conflicting validated excerpts. Source differences keep their alternatives and use an explicit Select with source differences button; selection does not resolve them. Stale/unavailable-only options require refresh. Repeated selection returns the same context. Each selected trip receives a separate requirements revision; destination changes require another context. Dates/budgets/travelers/preferences can be revised independently.

Every follow-up or refresh admits a new job and retrieves new sources under the existing three-worker/one-inference-slot harness. It never silently reuses old evidence for current claims. The backend chooses a bounded topic query; departure/dates/budget/preferences/questions are not sent to the search provider. A 500-character maximum excerpt of the saved question may guide tool-free extraction as untrusted scope hints. The full question remains in its own conversation record and is excluded from trace events. Follow-ups return scoped validated excerpts and gaps, not unrestricted generated advice or transaction execution.

User-authored messages are separate from evidence. Requirements and job results are immutable revisions. The selected snapshot records the original result; its display uses the original job's read-time freshness downgrade, so selecting cannot make stale evidence fresh. New research does not overwrite earlier evidence or the original selection. Admission, selection and follow-up request keys reject incompatible payloads. A lost follow-up response can be retried with the same key without creating another job/message.

## API additions

All routes require session authentication; mutations also require CSRF. Other owners receive 404. Unknown JSON fields remain rejected.

| Route | Behavior |
|---|---|
| POST `/api/planning/clarification` | Validate requirements; list essential unknowns without guessing |
| GET `/api/planning/revisions/{id}` | Read an owned revision |
| GET `/api/planning/sessions/{id}/jobs` | Planning-context jobs only; excludes selected-trip follow-ups |
| POST `/api/planning/sessions/{id}/selected-trips` | Select an owned planning job with current validated evidence, using `{jobId}` |
| GET `/api/planning/sessions/{id}/selected-trips` | List independently selected contexts |
| GET `/api/planning/trips/{id}` | Context, latest requirements, freshness-aware selected result and own jobs |
| GET `/api/planning/trips/{id}/messages` | Context's saved follow-ups with response job IDs |
| POST `/api/planning/trips/{id}/research` | New scoped job: `{requirements, question, focus, requestKey}` |
| GET `/api/research/jobs/{id}/images` | Retrieved, attributed source illustrations or an empty list |

Topics: ALL, DESCRIPTION, STAY, TRANSPORT, ACTIVITY, PRICE, AVAILABILITY and POLICY. Descriptive topics affect guide query/passage selection. Volatile topics still expose gaps because the current provider lacks scoped inventory/quote/policy data. SSE progress reuses durable job events, reconnects using event IDs, and closes streams when switching contexts/logging out.

## Images and factual boundaries

The backend considers one sufficiently sized raster illustration linked from a retrieved Wikivoyage article. It fetches Commons metadata through the same safe web client and charges the fetch budget before the request. Only exact Wikimedia upload/thumbnail hosts, Commons description URLs, plain-text author metadata and explicit CC BY/CC BY-SA/CC0/public-domain licensing are accepted. Unusable attribution, missing metadata, cancellation, network failure or a broken image produces an explicit image gap. Illustrations are attributed source assets, not validated proof of travel suitability. Remote pixels load directly in the browser without referrer headers; attribution and source/license links remain visible.

Guide excerpts are attributed and semantically unverified. India is the declared request country; destination text is user-provided rather than a geocoded country guarantee. Current prices/availability/policies and total cost estimates remain unavailable. Entered budget is a user limit, not a quote. Duration uses date differences and shows its assumptions. Booking execution remains unavailable.

## Verification

Run `scripts/verify-phase4.ps1` with the existing isolated MySQL on 13306 and enough memory for the pinned model. It builds the production frontend, enables all database/internet/model/browser checks and invokes the disposable-schema/owned-runtime lifecycle of the Phase 3 verifier, including packaged service smoke. Application services/data are preserved. `-BrowserOnly` runs focused database/browser checks and is not full live acceptance.

Playwright packages must be installed; `-NodeModules` accepts their containing directory. The default resolves this desktop's bundled dependency runtime. Chrome is the default headless channel; use `-BrowserChannel msedge` when appropriate. No browser install/download is performed. The browser test serves production assets with a temporary local reverse proxy to the real isolated Spring backend; it seeds clearly synthetic supported excerpts through the normal validation executor with a deterministic test adapter. Separate live tests exercise actual internet, pinned local model, worker/SSE and Commons attribution. Synthetic fixtures are not represented as real travel research. Reports and desktop/mobile screenshots remain ignored under `runtime-data/phase4-browser`.

Final acceptance on 2026-10-11: scripts/verify-phase4.ps1 passed 68 backend tests with zero failures/errors/skips, both Java packages, frontend production build, actual MySQL/internet/pinned-model/worker/SSE/Commons checks, production browser desktop/mobile flows and packaged-service smoke. Screenshots were visually inspected. Initial Commons thumbnail-host/guide-icon, research-topic label and requirements round-trip serialization failures were corrected; strict validation was preserved. Existing services and application data were preserved. Required checks include owner/CSRF boundaries, selection/idempotency, context/revision/message/evidence isolation, stale selection/read downgrade, volatile gaps, fresh retrieval, image publication cancellation, safe attribution, and production browser auth/comparison/selection/follow-up/cancel/reload/clarification/mobile/no-console-errors flows.

Start/restart the backend to load V6 and the new routes. Keep backend/proxy 8002, database 13306 and dedicated model 11436. Never reset application data. Phase 5 booking demonstration requires subsequent authorization.


