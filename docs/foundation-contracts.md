# Phase 1 foundation contracts

Phase 1 completed on 2026-10-11. Identity, owner-scoped planning-session creation/list/read, three MySQL migrations, typed foundation contracts, validated policy configuration, trace redaction and interrupted-job recovery are implemented. Phase 2 now implements adapters, safe online retrieval, durable workers, counters/events and SSE through migration V4. Semantic validation and bookings remain future work. See phase2-research.md for current acceptance; this document retains the foundation contracts.

## Module ownership

| Module | Owns | Boundary |
|---|---|---|
| identity | Users, password verification, sessions, principal | Supplies authenticated owner ID; clients never select an owner |
| planning | Planning sessions, immutable request revisions, trip selections | Owns India scope and selected-context creation |
| conversations | Messages under a session or selected context | Messages are not evidence |
| research | Jobs, tasks, budgets, transitions, cancellation | Calls adapters; controls three application-wide workers |
| model | Provider-neutral inference requests and immutable model snapshots | One shared inference slot; no action execution |
| retrieval | Search/page adapters, source snapshots and passages | Issues evidence IDs after retrieval; validates destinations |
| validation | Candidate assessment and accepted field revisions | Publishes only validated facts, explicit gaps and deterministic estimates |
| presentation | Owner-scoped REST/SSE result views | Renders persisted accepted records; no model text as factual cards |
| booking | Capability, bounded authorization, provider operations | Separate from general web retrieval; disabled by default |
| observability | Redacted trace events and run metadata | Never treats hidden model reasoning as available |

## Identity and ownership target

Implemented local registration uses BCrypt passwords and durable Spring Session JDBC sessions, with CSRF protection and same-origin requests. GET /api/identity/csrf supplies the token; registration accepts JSON; POST /api/identity/login accepts form username/password. Login rotates the session and logout invalidates it. Cookies are HttpOnly, SameSite=Lax, expire after 30 minutes of inactivity and use SESSION_COOKIE_SECURE=true for HTTPS. No development account is hard-coded.

Every planning session has an immutable owner. Request revisions, selected contexts, conversations, jobs, evidence and results inherit that owner through their parent. Check ownership before every read, mutation and SSE subscription; another user's object returns 404. UUID unpredictability is not authorization. Repository queries scope by principal and parent ID. A selected trip creates its own context rather than sharing another trip's conversation or evidence scope.

Future migrations must preserve parent/child ownership consistency, immutable revision references and foreign keys. Where two parent IDs coexist, enforce their relationship with a composite foreign key or a checked transaction. Do not expose domain endpoints until real-MySQL migration and cross-user access tests pass.

## API target

Current-principal, planning-session, requirement-revision, job start/read/cancel and owner-scoped SSE endpoints below are implemented. Selection and booking routes remain denied by security. Source/task/event-log routes are documented in phase2-research.md. JSON uses UTC timestamps and ISO dates; monetary amounts use decimal strings plus ISO currency. India is the MVP scope; draft requirements preserve missing essentials for future clarification.

| Operation | Route | Contract |
|---|---|---|
| Current principal | GET /api/identity/me | Session identity; unauthenticated returns 401 |
| Create planning session | POST /api/planning/sessions | Owner assigned by backend |
| Submit requirement revision | POST /api/planning/sessions/{id}/revisions | Immutable dates, origin, destination intent, travelers, budget and preferences |
| Start research | POST /api/planning/sessions/{id}/jobs | 202 after durable job/configuration snapshot; no model work before commit |
| Inspect job | GET /api/research/jobs/{id} | State, bounded progress, explicit error/partial-result reasons |
| Cancel | POST /api/research/jobs/{id}/cancel | Idempotent; cannot rewrite completed results |
| Observe | GET /api/research/jobs/{id}/events | Owner-checked SSE; durable event ID and Last-Event-ID replay |
| Select a trip | POST /api/planning/sessions/{id}/selections | Creates independent selected context referencing a result revision |

Implemented errors contain code, safe message and traceId, excluding secrets, stack traces and raw provider bodies. Field-specific issues are a future extension. Job-start retries will require a request key bound to owner and payload. Unknown JSON fields are rejected, including attempts to supply an owner.

## Job snapshot and transitions

Persist owner/context, immutable requirement revision, model provider and artifact digest, runtime version, context limit, sampling settings, prompt/harness version, tool allowlist, numeric budgets, freshness policy version and trace ID when queuing. Changing settings affects new jobs only. An unavailable or changed pinned artifact fails explicitly rather than resolving a mutable tag silently.

`QUEUED -> RUNNING -> SUCCEEDED | PARTIAL | FAILED`; `QUEUED -> CANCELLED`; `RUNNING -> CANCEL_REQUESTED -> CANCELLED`. Terminal states never transition backward. Persist state changes and events atomically and reject stale concurrent updates using a version. Cancellation stops new work, signals active adapters and discards results arriving after cancellation. Budget exhaustion yields PARTIAL only when some publishable result exists; otherwise FAILED with a specific reason.

After restart, orphaned RUNNING or CANCEL_REQUESTED jobs become INTERRUPTED and retain their traces. A user retry creates a new linked job with a fresh snapshot. Do not silently replay actions. Durable lease/worker ownership is required before recovering queued work. The MVP runs one backend instance; three workers are shared across all users/jobs within it. Multiple replicas require a database-wide admission mechanism before enabling them.

## Evidence and fields

A source snapshot contains a backend ID, requested/final URL, retrieval time, response status/type, normalized content hash and bounded text. A passage contains its source ID, stable offsets and text. Search snippets guide retrieval but do not support consequential price, availability or policy claims. Evidence ownership and context are checked before candidate validation. Old content is retained as a revision, never edited into a new retrieval timestamp.

| Field state | Required representation |
|---|---|
| SUPPORTED | Scoped value, backend-known passages, retrieval/validation timestamps |
| ESTIMATED | Deterministic formula, supported inputs, currency, assumptions and rounding |
| CONFLICTING | Competing scoped claims and their passages; no silently chosen value |
| UNAVAILABLE | Null value and explicit missing/inaccessible/unsupported reason |
| STALE | Prior scoped value and passages with expiry reason; not current availability |

Unknown evidence IDs, owner/context mismatch, unsupported exact values and wrong dates/travelers/room scope fail validation. Every required field must be represented, including gaps. Store starting prices separately from date-specific all-in quotes. Semantic support remains uncertain and needs evaluation; schema validity alone cannot establish support.

## Tool and policy boundaries

Research actions are limited to typed search and safe public-page retrieval. Search accepts a bounded query and result count; fetch accepts a backend-approved public URL and bounded extraction options. No shell, SQL, arbitrary network calls or booking actions are exposed through these tools. Validate DNS-resolved addresses, each redirect, schemes, response types, byte limits and deadlines; reject private, loopback, link-local and metadata destinations. Source content cannot expand the allowlist. Extraction receives selected passages without action tools and emits candidates with backend-issued passage IDs.

Booking provider destinations are separately configured, never exempted through the general fetch tool. Enabling capability is not user authorization. Booking contracts are deferred to Phase 5.

Validated HarnessProperties defaults: 12 model turns including repairs, 8 searches, 12 fetched pages, 1 output repair, 600-second job deadline, 90-second model deadline, 15-second fetch deadline, 2 MiB page limit, 30,000 source characters and 768 output tokens. Initial freshness windows are price 6 hours, availability 15 minutes, policy 7 days and description 30 days; trace retention is 14 days. These are configurable initial policies, not claims about provider validity. Phase 2 snapshots policies and charges counters before calls, including failures; Phase 3 enforces field freshness. Terminal event cleanup uses the captured retention policy. Model evaluation uses 4096-8192 context tokens and serialized inference. Repairs must preserve fields not named in a structural violation; failed repair remains visible.

Trace model/runtime/configuration IDs, accepted/rejected calls, passage/candidate references, decisions, timings and safe error codes. Redact passwords, cookies, authorization headers, provider keys, payment fields and sensitive traveler identifiers before persistence. Store synthetic model-evaluation output under ignored runtime-data; do not commit raw real-user diagnostic traces.

## Required next checks

Completed checks: Java 21 Maven verification passed 14 tests, including four real-MySQL integration tests; all three migrations applied on MySQL 8.4.11. Tests cover CSRF, durable sessions, password hashing, session rotation/logout, two-user isolation, composite ownership foreign keys, invalid input, database state checks and idempotent restart recovery. Recovery marks orphan RUNNING/CANCEL_REQUESTED jobs INTERRUPTED and increments versions; queued and terminal jobs remain unchanged. Typed evidence validates exact offsets; Phase 2 adds connection-bound DNS pinning and redirect/size/type/deadline safety. The trace redactor masks known sensitive keys and credentials; future trace producers must still allowlist fields and exclude raw sensitive input.

Held-out local-model checks passed 40/40 repeated short cases and 20/20 larger-context cases with at most one repair. See the evaluation report for failures, scope and limitations. Phase 2 acceptance passed 36 tests, including real online evidence stored through workers and SSE. Queued jobs are claimed after exclusive dispatcher startup; orphan active runs are interrupted without replay. Phase 3 implements strict extractive support/scope/freshness checks, field states, bounded repair/retry and atomic result records through V5. See [validation contract](phase3-validation.md).

