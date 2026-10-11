# Backend

Phase 2 includes immutable revisions and job admission, Wikivoyage search/safe fetching, source/passages, three durable workers, pinned local inference, cancellation/restart recovery and owner-scoped diagnostics/SSE. Phase 3 adds strict tool-free extraction/validation, bounded repair/retry and persisted scoped result records. See [validation implementation and verification](../docs/phase3-validation.md) and [research acquisition](../docs/phase2-research.md).

Spring Boot modular monolith targeting Java 21. Run from the repository root with `.\mvnw.cmd -pl backend spring-boot:run` after starting MySQL and setting `DB_PASSWORD`. Default address: `127.0.0.1:8002`; health: `/actuator/health`.

The backend requires MySQL 8.4. Flyway owns the baseline, identity/ownership/research-scope tables and official Spring Session JDBC tables. Registration/login/logout and owner-scoped planning-session create/read/list exist. Other domain capabilities are denied until their implementing phases. No development account or credential is hard-coded.

GET /api/identity/csrf returns headerName/token and a session cookie. Send that header plus cookie with all mutations, including registration and login. POST /api/identity/register accepts email/password JSON (12-72 UTF-8 bytes); POST /api/identity/login accepts form-encoded username=email and password. Login rotates the session ID; POST /api/identity/logout invalidates it. GET /api/identity/me returns the authenticated account. POST /api/planning/sessions accepts title; GET on the collection lists the owner's latest 100 sessions, and GET /{id} returns 404 for another owner's object. Unknown JSON properties are rejected. Frontend auth and planning screens are implemented in Phase 4.

Sessions are stored in MySQL, expire after 30 minutes of inactivity and use HttpOnly/SameSite=Lax cookies. Set SESSION_COOKIE_SECURE=true when using HTTPS. The default server remains localhost bound.

`.env.example` documents variables; Spring does not automatically load `.env`. Set variables in your terminal/IDE. The selected model is pinned Ollama 0.20.4 / gemma4:e4b; the dedicated runtime defaults to 11436 and requires one loaded model/one inference request. Jobs snapshot model and harness settings. Phase 5 adds a disabled-by-default booking orchestrator using a separate configured provider, explicit quote-specific consent/ceiling/deadline, V7 scoped durable attempts, stable keys and read-only uncertain-result reconciliation. See [booking APIs/setup and verified boundaries](../docs/phase5-booking.md).

Packages own separate concerns. Keep controllers, services and persistence under the owning module, expose narrow interfaces across modules, and avoid a shared catch-all business service. Define data/tool contracts before dependent implementation.

Current full acceptance: `scripts/verify-phase5.ps1`, 80 backend and 7 provider tests with zero failures/errors/skips, seven migrations, packages/frontend, live research and both production browser flows. Start/restart backend for V7; preserve existing services/data. Old Phase 2–4 counts below are historical verification evidence.

All 36 tests passed on Java 21/MySQL 8.4 through scripts/verify-phase2.ps1, including real online/local-model acquisition, three-worker saturation and owner-scoped SSE. Database tests require PHASE1_DB_TESTS=true; online/model/live tests have separate opt-in flags enabled by the helper. They never use H2. Restart recovery records interrupted active jobs/tasks without replay; queued jobs are claimed after exclusive dispatcher startup. Eight forward migrations are available. Use scripts/verify-phase3.ps1 for isolated verification, especially when an application backend is already running. GET /api/research/jobs/{id}/result exposes backend-assembled field states and attributed excerpts; acquisition success alone does not imply completeness. Phase 3 is complete: all 58 backend tests pass with zero failures/errors/skips through scripts/verify-phase3.ps1 -Smoke, including real model extraction/adversarial fixtures, real online/MySQL/worker/SSE and packaged-service smoke. Start/restart the backend to load V6/new planning routes. Phase 4 implements clarification, cards/comparison, independent selected-trip revisions/history/research and Commons image attribution. See [planning API/verification](../docs/phase4-planning.md); `scripts/verify-phase5.ps1` is the current full runner.





Phase6 adds owner-scoped GET /api/research/jobs/{id}/inspector: coherent saved job/captured non-endpoint model/budgets/tasks/sources/refreshed validation view, null result when absent. Event-log remains separately cursor-paginated (100 rows). No raw prompts/replies or endpoint credentials are exposed. See ../docs/phase6-delivery.md.

V8 adds separate card presentation metadata. GET/PUT/DELETE /api/planning/cards/{id} and POST /api/planning/cards/{id}/restore require ownership; mutations require CSRF. DELETE is a terminal-job-only soft delete; selected evidence, research/audit and booking rows remain. Default context job lists hide deleted cards; includeDeleted=true includes them for Trash. PUT /api/planning/sessions/{id} renames a plan. See [coursework CRUD](../docs/card-management.md). Restart the existing backend with the latest package to apply V8 without replacing its database.
