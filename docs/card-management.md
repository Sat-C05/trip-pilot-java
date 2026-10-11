# Workspace redesign and card CRUD

The workspace uses a responsive navigation sidebar, collection counts, searchable cards, clear field-state labels, destination summaries, expandable guide/source detail, and desktop/mobile authentication layouts. Existing plans collapse their research form under **Research more destinations**, while new plans and active clarification keep it open. Keyboard focus is visible, controls have usable touch targets, a skip link reaches the workspace, and the native card-edit dialog traps focus, supports Escape and returns focus to its trigger. Personal notes render as text, never HTML.

## Coursework CRUD mapping

| Operation | UI | Persistent API |
| --- | --- | --- |
| Create | Enter destinations and choose **Research destinations** | `POST /api/planning/sessions`, `POST /api/planning/sessions/{id}/revisions`, then `POST /api/planning/sessions/{id}/jobs` creates each research card |
| Read | Open a saved plan, search cards, expand **Guide details & sources**, open Inspector | `GET /api/planning/sessions/{id}/jobs`, `GET /api/planning/cards/{id}` and owner-scoped evidence/result APIs |
| Update | **Edit card** changes title and personal notes; **Rename plan** changes plan name | `PUT /api/planning/cards/{id}` with `{title, notes}`; `PUT /api/planning/sessions/{id}` with `{title}` |
| Delete | **Delete card** moves completed/cancelled cards to **Trash** | `DELETE /api/planning/cards/{id}` persists a deletion timestamp; default job lists exclude deleted cards |
| Restore | Open **Trash**, choose **Restore card** | `POST /api/planning/cards/{id}/restore`; title, notes and evidence survive |

Deletion is a **soft delete**, a real persisted change rather than a browser-only filter. It is reversible and does not erase research/audit/booking records or selected-trip contexts. It is not a permanent data-erasure feature. The workspace requests `includeDeleted=true` on the current plan's jobs or selected-trip detail to populate its Trash view. Lists retain the existing newest-100-record limit per context.

V8 adds nullable `card_title`, bounded `card_notes` and nullable `card_deleted_at` to research jobs; existing rows retain destination-derived titles, empty notes and visible cards. No earlier migration is edited. Restart the backend with the new package to apply V8 and load the new routes, preserving the existing database and launch configuration.

Card title and notes are personal presentation metadata. They never change immutable destination/dates/travelers, validated facts, saved excerpts, model snapshots, selected evidence or booking authorization. Change travel requirements through the research form or selected-trip follow-up; a new research revision/card preserves prior evidence. Refresh sources creates a fresh research card rather than editing history. Raw quotes/prices/policies cannot be entered as validated facts through card editing.

All card read/update/delete/restore operations require authentication and ownership. Mutations require CSRF. Title is nonblank and at most 120 characters; notes are at most 2,000. Deleted cards must be restored before editing. Active research cannot be deleted: cancel it and wait for a terminal state first. Delete/restore are idempotent. Plan titles are nonblank and at most 120 characters.

## Verification

`scripts/verify-phase4.ps1 -BrowserOnly` runs the production frontend browser and planning/card API suite on a new disposable MySQL schema, preserving the application database and running services. Checks cover owner/CSRF boundaries, active-delete rejection, input bounds, immutable research and retained selected contexts, persisted rename/edit/delete/restore, text-only notes, search, reload, keyboard dialog behavior, comparison, selected follow-ups, Inspector, authentication and mobile overflow. Browser screenshots and report stay in ignored `runtime-data/phase4-browser`.

See [hosting recommendations](hosting-options.md) for the deployment constraints of this stack. This revision does not change the evaluated model, retrieval, validation or booking protocols.

Final extension verification on 2026-10-11: **102 backend + 8 provider tests, zero failures/errors/skips**, eight real MySQL migrations, actual pinned model/internet/worker/SSE, final production planning/card CRUD/Inspector/booking desktop/mobile browsers, both Java packages, frontend production build and packaged/protected-route smoke passed through `scripts/verify-phase5.ps1`. The focused nine-test planning/CRUD/browser suite also passed. Desktop/mobile/auth/editor/Trash/Inspector and booking screenshots were reviewed. Earlier seven-migration assertion and textarea/focus failures were corrected and are not counted as passing runs. The model/retrieval/booking protocols were unchanged; the separate 60-case Phase 6 model evaluation remains historical evidence rather than a newly repeated evaluation. The user retains the application backend restart to load V8.
