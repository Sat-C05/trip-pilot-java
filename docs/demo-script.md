# TripPilot presentation walkthrough

Allow 10–15 minutes plus model warmup. Use a synthetic account and public guide requests; do not display local credential files, personal conversations or runtime traces. Keep the latest source package/evaluation report available if online providers fail during presentation.

## Before presenting

1. Install Java 21, Node/npm, MySQL 8.4 and the selected Ollama runtime/artifact from README. Use direct project MySQL 13306; leave the existing MySQL80/3306 untouched. Start missing database/model services with the provided helpers. The model helper preserves existing 11436 listeners and downloads nothing.
2. Load saved local database variables, build with `./mvnw.cmd -B -ntp -DskipTests package`, and run the packaged backend using Java 21. Default8002 and frontend 5173; if preserving an existing8082 workspace, set `SERVER_PORT=8082` and `BACKEND_URL=http://127.0.0.1:8082`. Do not run two dispatchers against the same schema. Restart your existing backend after code updates.
3. For simulated booking, start the separate provider with its local ledger on 8081, then explicitly set `BOOKING_ENABLED=true` before starting the demo backend. The default is disabled. Confirm health endpoints. Build/install frontend dependencies and start its Vite server. Register a new presentation account through the UI.
4. Run `./scripts/verify-phase6.ps1` before the presentation; review its passing results and limitations. Evaluation requires available RAM and working internet. Temporary verification services do not replace normal application services.

## Research and comparison (3–5 minutes)

Enter **Jaipur, Mumbai**, leave unknown dates/travelers/budget blank and start research. Explain that each request searches online Wikivoyage guides and saves fetched evidence. Three research workers share one inference slot. Show live counters/progress, then compare the guide fields. Useful hotel/transport/activity sentences can be supported without travel dates, but current quotes/inventory/policies remain explicit gaps. Entering a budget never turns it into a price.

Open the exact excerpts/citations and retrieval times. Point out that attributed source text still needs meaning/suitability review. If an external search/model fails, show the recorded outcome and refresh once services are healthy; do not claim failure as evidence or alter an old card. Initial guide research is not an unrestricted whole-web search or verified Delhi-to-Mumbai ticket itinerary.

## Inspector (2 minutes)

Open **Research Inspector** on the card. Show the captured `extractive-v2` model/prompt/artifact and limits, task outcomes, event timeline and validation decisions. Click **Inspect cited passage** to reveal saved source text and offsets/hash. Explain that proposed quotes receive backend-known travel scope and exact support checks; raw personal prompts/model replies are excluded. Missing result/expired events are explicit rather than fabricated. Current facts and decisions belong to this job/context/revision.

## Independent selected trips (2 minutes)

Select Jaipur, ask a transport question with the **Getting there & around** topic and start fresh research. Show the separately saved question/history, new source timestamps and research revision. Return to comparison and select Mumbai; demonstrate that Jaipur's question is absent. Reopen/reload Jaipur to recover its saved history. Refresh does not overwrite the selected historical evidence. Source differences remain unresolved when a conflicting option is selected; stale/unavailable-only choices need refresh.

## Optional simulated booking (3 minutes)

In a selected trip, save complete future dates, one traveler and an INR budget (for example 20000). Request simulated inventory and a quote. Review the all-inclusive stay total, exact dates/travelers, policy and expiry. Without explicit consent/ceiling there is no submission. Authorize one quoted stay, then submit that authorization. Show the confirmed provider reference and reload to recover durable status. Explain that no payment or commercial reservation occurs. Feature enabled is not spending permission; chat/model text cannot grant authorization.

For timeout-after-create behavior, show the passing real HTTP reconciliation acceptance record rather than introducing destructive failure knobs into the live demo. Stable keys and durable SUBMITTING precede calls; uncertain submissions are status-only and never replayed. Provider NOT_FOUND remains unresolved, blocking a new authorization.

## Close (1 minute)

Show the evaluation report and source ZIP/manifest/clean build evidence. State the observed checks and limits: guide-only evidence, semantic uncertainty, small local-model resource/format limits and a simulated one-stay provider. No push, deployment or real payment/provider integration is part of this delivery.

## Operator restart for this preserved workspace

The original backend currently uses 8082 and frontend 5173 proxies there. After stopping your backend in its own terminal, restart from the project root:

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
. ./scripts/use-local-environment.ps1
$env:SERVER_PORT='8082'
$env:MODEL_BASE_URL='http://127.0.0.1:11436'
$env:BOOKING_ENABLED='false'
& "$env:JAVA_HOME/bin/java.exe" -Xmx384m -jar backend/target/backend-0.1.0-SNAPSHOT.jar
```

Keep the database/model/frontend running. Open a saved card's Inspector after restart to confirm the new route loaded. This launch leaves booking disabled; use the explicit optional setup above for the presentation simulation. Default configuration remains 8002; 8082 preserves this workspace's existing proxy.
