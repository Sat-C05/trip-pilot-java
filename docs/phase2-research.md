> Phase 3 supersedes the passage-selection executor and acquisition-only success semantics described in this historical Phase 2 report. See [current validation/results](phase3-validation.md). Guide coverage and safe retrieval remain unchanged.

# Phase 2: active research and evidence

The backend supports authenticated requirement revisions, durable research admission, online search/fetch, immutable source/passages, bounded local inference, cancellation, restart recovery, redacted diagnostics and replayable SSE. This phase acquires evidence. SUCCEEDED means acquisition and the passage-selection protocol completed; it does not mean travel claims have passed semantic validation. Phase 3 assembles validated fields; Phase 4 adds the interactive planning UI.

## Search and retrieval

The initial SearchProvider implementation uses the public Wikivoyage MediaWiki search API. It searches travel guides without an API key or subscription. Coverage is limited to those guides: it does not provide general whole-web search, current inventory, date-specific quotes or authoritative provider policies. Such information remains unavailable. The destination intent is the search query; personal traveler identifiers, chat history and preferences are not sent to the search provider. Search metadata guides retrieval and is not itself supporting evidence.

SafeWebClient separately retrieves public HTML/plain-text pages and internal search JSON. It accepts HTTP/HTTPS with standard ports, rejects URL credentials/fragments/local names, resolves all destination addresses, rejects private/special/mixed answers, and pins the validated addresses to the actual connection. Redirects are manual, individually validated, limited to three and cannot downgrade HTTPS. No cookies, proxy, automatic retries or compressed responses are accepted. Only allowed content types are read. Byte limits apply while reading, including chunked responses. The call deadline includes executor/DNS waiting; cancellation aborts the request/future. Platform DNS cannot always be interrupted, so three I/O threads and three pending slots bound it; cancelled queued work is removed.

jsoup removes script/style/navigation/form content and extracts bounded normalized text. Source snapshots store requested/final URLs, retrieval time, HTTP status/type, title, normalized content SHA-256 and text. Passages use backend UUIDs and exact offsets in that saved text. Content stays untrusted and cannot add tools or authorize bookings. Immutable evidence is separate from messages and trace events.

## Durable execution

Admission commits a QUEUED job with immutable requirement/model/harness references before work begins. Model snapshots include the artifact/runtime/context/sampling/output/deadline/prompt settings. Owner-scoped request keys are idempotent for the same session/revision/retry payload; a changed payload gets 409. The queue admits at most 100 queued/active jobs across users. A retry references a terminal job in the same owned session and creates a new snapshot; there is no automatic action replay.

One dispatcher holds a MySQL named lock on a dedicated checked-out connection. A second dispatcher fails startup before recovery. This is a single-backend MVP, not a multi-replica scheduler. Three long-lived workers atomically claim jobs with row locks and persist their instance ownership. Queued work resumes after restart; orphan RUNNING/CANCEL_REQUESTED jobs and unfinished tasks become INTERRUPTED, with a durable event. Invalid legacy snapshots fail explicitly rather than blocking the queue.

Every search/fetch/model task and counter commits before its external call, including failed calls. Job locks, worker identity and current state fence stale workers; version changes are atomic. Search/page/model-turn limits and job/call/byte/character/output deadlines use each job's captured harness. The current acquisition pass performs one destination search, retrieves up to three discovered pages and performs one tool-free model passage-selection call. The inference semaphore is shared by all workers. Context uses up to four 1,000-character passage previews; character caps are not token guarantees, and Phase 3 must implement stronger passage/token budgeting and validation.

Cancellation of a queued job is immediate; an active job first becomes CANCEL_REQUESTED. New calls stop, active adapters are signalled and late sources/model selections are discarded. Previously committed evidence remains inspectable. No new results are published after cancellation. Graceful shutdown interrupts active runs; unclean shutdown is recovered on the next exclusively owned startup. GPU abortion is best effort, requiring the dedicated runtime's parallel=1 setting. Empty search/no accessible evidence yields FAILED with a safe code; incomplete acquisition or failed selection with saved evidence yields PARTIAL. Neither state invents facts.

## Owner-scoped API

All mutations require the existing session and CSRF token. Other owners receive 404, including SSE/evidence/tasks.

| Operation | Route |
|---|---|
| Submit immutable requirements | POST /api/planning/sessions/{sessionId}/revisions |
| Admit job (202) | POST /api/planning/sessions/{sessionId}/jobs |
| Inspect job/state/counters | GET /api/research/jobs/{jobId} |
| Cancel idempotently | POST /api/research/jobs/{jobId}/cancel |
| Inspect durable attempts | GET /api/research/jobs/{jobId}/tasks |
| Read source metadata/text/passages | GET /api/research/jobs/{jobId}/sources |
| Read retained diagnostics | GET /api/research/jobs/{jobId}/event-log?after=0 |
| Subscribe/replay SSE | GET /api/research/jobs/{jobId}/events |

Revision body example:

```json
{"departure":"Delhi","destinationIntent":"Jaipur","country":"IN","preferences":[]}
```

Admission body uses the revision UUID returned above:

```json
{"revisionId":"<revision UUID>","requestKey":"jaipur-research-1"}
```

`retryOf` is optional. Clients cannot submit owners, tools, policies or model snapshots. SSE events have durable numeric IDs and named types; reconnect with Last-Event-ID. Invalid cursors get 400; unavailable/unowned cursor IDs get 409 after job ownership is checked. A reset without the header reads retained history. Streams close after terminal history or 30 seconds; reconnect with the last ID. At most 64 subscriptions exist globally. Heartbeat comments are not evidence. The frontend remains the static foundation page.

Trace producers allowlist IDs, states, safe failure codes, counters, model identifiers, selection IDs and timings. Raw prompts, model text, page content, search queries, cookies and credentials are excluded from events. JSON/support failures remain diagnosable without storing sensitive raw outputs. Event retention is configurable per snapshotted job (default 14 days), with hourly cleanup of terminal-job events; active events and evidence/revisions are retained. There is no hidden model reasoning capture.

## Local operation and verification

Use the existing direct MySQL on 13306 and Java 21 helpers; preserve MySQL80/3306. Backend is 8002 and frontend 5173. The dedicated model endpoint now defaults to 11436. Start it in its own PowerShell terminal:

```powershell
$env:OLLAMA_HOST='127.0.0.1:11436'
$env:OLLAMA_NUM_PARALLEL='1'
$env:OLLAMA_MAX_LOADED_MODELS='1'
$env:OLLAMA_MAX_QUEUE='3'
ollama serve
```

Use the already installed pinned Ollama 0.20.4 / gemma4:e4b artifact. No automatic download or tag update occurs. Backend MODEL_BASE_URL can override the endpoint; an unrelated desktop runtime's concurrency configuration cannot be assumed. Restart the backend to load new code/configuration; model changes affect only newly admitted jobs.

`scripts/verify-phase2.ps1` requires the existing database listener and a free model port. It starts its own dedicated runtime, runs Java 21 Maven verification with database/online/model/live flags, then unloads/stops that owned runtime. Existing database/backend/frontend services are preserved. `-ModelPort` selects another free port. Normal Maven skips live checks unless the corresponding environment flags are enabled; database-free results do not establish lifecycle/access acceptance.

Acceptance includes SSRF/DNS/redirect/type/size/deadline/cancellation failures, stalled DNS with bounded I/O, actual public search/fetch, serial inference, two-user isolation, CSRF/unknown-field rejection, idempotency, durable task counters and failures, late-result rejection, restart fencing, SSE replay, three actual workers across six jobs, second-instance rejection and trace retention/redaction. Final verification passed all 36 tests with no failures/errors/skips. The real worker/model/SSE path saved three Jaipur sources (one search, three fetches, one inference, 16 events). Packaged service and frontend HTTP smoke checks also passed.

References: [MediaWiki search API](https://www.mediawiki.org/wiki/API:Search), [Apache DNS resolver](https://hc.apache.org/httpcomponents-client-5.6.x/current/httpclient5/apidocs/org/apache/hc/client5/http/DnsResolver.html), [jsoup parsing](https://jsoup.org/cookbook/input/parse-document-from-string), [Connector/J instant/timezone settings](https://dev.mysql.com/doc/connector-j/en/connector-j-connp-props-datetime-types-processing.html). Remaining limitations are provider coverage, local-model JSON/semantic reliability, best-effort provider cancellation and later evidence validation/UI/booking phases.

